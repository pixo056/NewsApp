package ru.uunit.news;

import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class Communities {

  public static final Set<String> CATEGORIES = Set.of(
    "Университет",
    "Технологии",
    "Наука",
    "Спорт",
    "Культура",
    "Общество"
  );
  private static final Set<String> COLORS = Set.of(
    "purple",
    "lime",
    "peach",
    "mint",
    "pink",
    "sky"
  );
  private final JdbcTemplate db;
  private final Accounts accounts;

  public Communities(JdbcTemplate db, Accounts accounts) {
    this.db = db;
    this.accounts = accounts;
  }

  public List<Map<String, Object>> list(Accounts.User user) {
    return db
      .queryForList(
        "SELECT c.*, (SELECT count(*) FROM subscriptions s WHERE s.community_id=c.id) AS members FROM communities c ORDER BY official DESC,created_at"
      )
      .stream()
      .map(row -> view(row, user))
      .toList();
  }

  private Map<String, Object> view(Map<String, Object> row, Accounts.User user) {
    Map<String, Object> result = new LinkedHashMap<>();
    for (String key : List.of(
      "id",
      "name",
      "description",
      "initials",
      "color",
      "category",
      "members"
    ))
      result.put(key, row.get(key));
    result.put("members", row.get("members").toString());
    result.put("verified", row.get("official"));
    result.put("ownerId", row.get("owner_id"));
    result.put("dailyLimit", row.get("daily_limit"));
    result.put(
      "canManage",
      user != null &&
        (accounts.admin(user) ||
          (!Boolean.TRUE.equals(row.get("official")) && user.id().equals(row.get("owner_id"))))
    );
    return result;
  }

  public Map<String, Object> require(UUID id) {
    return db
      .queryForList("SELECT * FROM communities WHERE id=?", id)
      .stream()
      .findFirst()
      .orElseThrow(ApiException::missing);
  }

  @Transactional
  public UUID create(
    Accounts.User user,
    String name,
    String description,
    String category,
    String color
  ) {
    accounts.verified(user);
    if (
      name == null ||
      name.trim().length() < 3 ||
      name.length() > 100 ||
      description == null ||
      description.length() > 500 ||
      category == null ||
      color == null ||
      !CATEGORIES.contains(category) ||
      !COLORS.contains(color)
    ) throw ApiException.bad("Проверьте название (3–100), описание (до 500), категорию и цвет");
    db.queryForObject("SELECT id FROM app_users WHERE id=? FOR UPDATE", UUID.class, user.id());
    if (
      db.queryForObject(
        "SELECT count(*) FROM communities WHERE owner_id=?",
        Integer.class,
        user.id()
      ) >= 10
    ) throw ApiException.bad("Можно создать не более 10 пабликов");
    UUID id = UUID.randomUUID();
    String initials = name
      .trim()
      .substring(0, Math.min(2, name.trim().length()))
      .toUpperCase(Locale.ROOT);
    db.update(
      "INSERT INTO communities(id,name,description,initials,color,category,owner_id) VALUES(?,?,?,?,?,?,?)",
      id,
      name.trim(),
      description.trim(),
      initials,
      color,
      category,
      user.id()
    );
    return id;
  }

  @Transactional
  public void update(UUID id, Accounts.User user, String name, String description) {
    Map<String, Object> c = require(id);
    if (
      !accounts.admin(user) &&
      (Boolean.TRUE.equals(c.get("official")) || !user.id().equals(c.get("owner_id")))
    ) throw ApiException.forbidden();
    if (
      name == null ||
      name.trim().length() < 3 ||
      name.length() > 100 ||
      description == null ||
      description.length() > 500
    ) throw ApiException.bad("Проверьте название и описание");
    db.update(
      "UPDATE communities SET name=?,description=? WHERE id=?",
      name.trim(),
      description.trim(),
      id
    );
  }

  @Transactional
  public void settings(UUID id, Accounts.User user, int dailyLimit, boolean official) {
    if (!accounts.admin(user)) throw ApiException.forbidden();
    require(id);
    if (dailyLimit < 1 || dailyLimit > 1000) throw ApiException.bad(
      "Лимит должен быть от 1 до 1000"
    );
    db.update(
      "UPDATE communities SET daily_limit=?,official=? WHERE id=?",
      dailyLimit,
      official,
      id
    );
    db.update(
      "INSERT INTO audit_log(id,actor_id,action,target_id,detail) VALUES(?,?,'community-settings',?,?)",
      UUID.randomUUID(),
      user.id(),
      id,
      "limit=" + dailyLimit + ", official=" + official
    );
  }
}
