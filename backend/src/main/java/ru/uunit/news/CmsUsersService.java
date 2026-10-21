package ru.uunit.news;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Service
public class CmsUsersService {

  public record UserRecord(
    UUID id,
    String username,
    String name,
    String email,
    String bio,
    String role,
    boolean verified,
    boolean blocked,
    String blockReason,
    String createdAt
  ) {}

  public record Page(List<UserRecord> items, long total) {}

  private static final String COLUMNS =
    "id,username,display_name,email,bio,role,verified,blocked,block_reason,created_at";
  private static final Map<String, String> SORTS = Map.ofEntries(
    Map.entry("id", "id"),
    Map.entry("username", "username"),
    Map.entry("name", "display_name"),
    Map.entry("email", "email"),
    Map.entry("role", "role"),
    Map.entry("verified", "verified"),
    Map.entry("blocked", "blocked"),
    Map.entry("createdAt", "created_at")
  );
  private static final Set<String> MUTABLE = Set.of("name", "bio", "blocked", "blockReason");
  private final JdbcTemplate db;
  private final Accounts accounts;

  public CmsUsersService(JdbcTemplate db, Accounts accounts) {
    this.db = db;
    this.accounts = accounts;
  }

  private void administrator(Accounts.User actor) {
    if (!accounts.admin(actor)) throw ApiException.forbidden();
  }

  private UserRecord row(ResultSet r, int n) throws SQLException {
    return new UserRecord(
      r.getObject("id", UUID.class),
      r.getString("username"),
      r.getString("display_name"),
      r.getString("email"),
      r.getString("bio"),
      r.getString("role"),
      r.getBoolean("verified"),
      r.getBoolean("blocked"),
      r.getString("block_reason"),
      r.getTimestamp("created_at").toInstant().toString()
    );
  }

  public Page list(
    Accounts.User actor,
    int page,
    int perPage,
    String q,
    String sort,
    String order,
    Boolean blocked,
    String role
  ) {
    administrator(actor);
    if (page < 1 || perPage < 1 || perPage > 100) throw ApiException.bad(
      "Укажите страницу от 1 и размер страницы от 1 до 100"
    );
    String column = SORTS.get(sort);
    if (column == null || !("ASC".equals(order) || "DESC".equals(order))) throw ApiException.bad(
      "Некорректная сортировка пользователей"
    );
    if (q == null || q.length() > 200) throw ApiException.bad("Поисковый запрос: до 200 символов");
    if (role != null && !Set.of("USER", "ADMIN").contains(role)) throw ApiException.bad(
      "Некорректная роль"
    );
    StringBuilder where = new StringBuilder(" WHERE TRUE");
    List<Object> params = new ArrayList<>();
    if (!q.isBlank()) {
      String pattern =
        "%" + q.trim().replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%";
      where.append(
        " AND (username ILIKE ? ESCAPE '!' OR display_name ILIKE ? ESCAPE '!' OR email ILIKE ? ESCAPE '!')"
      );
      params.addAll(List.of(pattern, pattern, pattern));
    }
    if (blocked != null) {
      where.append(" AND blocked=?");
      params.add(blocked);
    }
    if (role != null) {
      where.append(" AND role=?");
      params.add(role);
    }
    Long total = db.queryForObject(
      "SELECT count(*) FROM app_users" + where,
      Long.class,
      params.toArray()
    );
    params.add(perPage);
    params.add(((long) page - 1) * perPage);
    List<UserRecord> items = db.query(
      "SELECT " +
        COLUMNS +
        " FROM app_users" +
        where +
        " ORDER BY " +
        column +
        " " +
        order +
        ",id " +
        order +
        " LIMIT ? OFFSET ?",
      this::row,
      params.toArray()
    );
    return new Page(items, total == null ? 0 : total);
  }

  public UserRecord detail(Accounts.User actor, UUID id) {
    administrator(actor);
    return db
      .query("SELECT " + COLUMNS + " FROM app_users WHERE id=?", this::row, id)
      .stream()
      .findFirst()
      .orElseThrow(ApiException::missing);
  }

  private String text(Object value, String field, int maximum, boolean required) {
    if (value == null && !required) return "";
    if (!(value instanceof String s)) throw ApiException.bad(
      "Поле «" + field + "» должно быть строкой"
    );
    String result = s.trim();
    if ((required && result.isBlank()) || s.length() > maximum) throw ApiException.bad(
      "Поле «" + field + "»: " + (required ? "от 1 до " : "до ") + maximum + " символов"
    );
    return result;
  }

  @Transactional
  public UserRecord update(Accounts.User actor, UUID id, Map<String, Object> input) {
    administrator(actor);
    if (
      input == null || input.isEmpty() || !MUTABLE.containsAll(input.keySet())
    ) throw ApiException.bad("Разрешено менять только имя, описание и блокировку пользователя");
    UserRecord previous = db
      .query("SELECT " + COLUMNS + " FROM app_users WHERE id=? FOR UPDATE", this::row, id)
      .stream()
      .findFirst()
      .orElseThrow(ApiException::missing);
    String name = input.containsKey("name")
      ? text(input.get("name"), "Имя", 80, true)
      : previous.name();
    String bio = input.containsKey("bio")
      ? text(input.get("bio"), "Описание", 500, false)
      : previous.bio();
    boolean blocked = previous.blocked();
    if (input.containsKey("blocked")) {
      if (!(input.get("blocked") instanceof Boolean b)) throw ApiException.bad(
        "Некорректное состояние блокировки"
      );
      blocked = b;
    }
    String reason = input.containsKey("blockReason")
      ? text(input.get("blockReason"), "Причина блокировки", 500, false)
      : previous.blockReason();
    if (
      blocked && ("ADMIN".equals(previous.role()) || actor.id().equals(id))
    ) throw ApiException.bad(
      "Нельзя заблокировать администратора платформы или собственный аккаунт"
    );
    if (blocked && reason.length() < 5) throw ApiException.bad(
      "Причина блокировки: от 5 до 500 символов"
    );
    if (!blocked) reason = "";
    List<String> changed = new ArrayList<>();
    if (!name.equals(previous.name())) changed.add("имя");
    if (!bio.equals(previous.bio())) changed.add("описание");
    if (blocked != previous.blocked()) changed.add(blocked ? "блокировка" : "снятие блокировки");
    if (!reason.equals(previous.blockReason())) changed.add("причина блокировки");
    if (changed.isEmpty()) return previous;
    db.update(
      "UPDATE app_users SET display_name=?,bio=?,blocked=?,block_reason=? WHERE id=?",
      name,
      bio,
      blocked,
      reason,
      id
    );
    String action =
      blocked != previous.blocked()
        ? blocked
          ? "user.blocked"
          : "user.unblocked"
        : "user.updated";
    String detail = "Изменены поля: " + String.join(", ", changed);
    if (blocked && (!previous.blocked() || !reason.equals(previous.blockReason()))) detail +=
      ". Причина: " + reason;
    db.update(
      "INSERT INTO audit_log(id,actor_id,action,target_id,detail) VALUES(?,?,?,?,?)",
      UUID.randomUUID(),
      actor.id(),
      action,
      id,
      detail
    );
    if (blocked) {
      db.update("UPDATE account_tokens SET used_at=now() WHERE user_id=? AND used_at IS NULL", id);
      TransactionSynchronizationManager.registerSynchronization(
        new TransactionSynchronization() {
          @Override
          public void afterCommit() {
            accounts.endSessions(previous.username());
          }
        }
      );
    }
    return detail(actor, id);
  }
}
