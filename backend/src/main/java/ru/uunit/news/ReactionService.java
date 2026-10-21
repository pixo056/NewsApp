package ru.uunit.news;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ReactionService {

  public static final List<String> KINDS = List.of("like", "celebrate", "insightful", "support");

  @JsonInclude(JsonInclude.Include.ALWAYS)
  public record Summary(Map<String, Long> counts, String selected, long total) {}

  private final JdbcTemplate db;

  public ReactionService(JdbcTemplate db) {
    this.db = db;
  }

  public Summary summary(UUID newsId, Accounts.User user) {
    List<Map<String, Object>> rows = db.queryForList(
      "SELECT r.kind,count(r.user_id) AS count,bool_or(r.user_id=?::uuid) AS selected " +
        "FROM news n LEFT JOIN reactions r ON r.news_id=n.id " +
        "WHERE n.id=? AND NOT n.deleted AND n.published_revision_id IS NOT NULL GROUP BY r.kind",
      user == null ? null : user.id(),
      newsId
    );
    if (rows.isEmpty()) throw ApiException.missing();
    Map<String, Long> counts = new LinkedHashMap<>();
    KINDS.forEach(kind -> counts.put(kind, 0L));
    String selected = null;
    long total = 0;
    for (Map<String, Object> row : rows) {
      String kind = (String) row.get("kind");
      if (kind == null) continue;
      long count = ((Number) row.get("count")).longValue();
      counts.put(kind, count);
      total += count;
      if (Boolean.TRUE.equals(row.get("selected"))) selected = kind;
    }
    return new Summary(counts, selected, total);
  }

  @Transactional
  public Summary set(UUID newsId, Accounts.User user, String kind) {
    if (kind == null || !KINDS.contains(kind)) throw ApiException.bad(
      "Выберите допустимую реакцию"
    );
    lockPublished(newsId);
    db.update(
      "INSERT INTO reactions(user_id,news_id,kind) VALUES(?,?,?) " +
        "ON CONFLICT(user_id,news_id) DO UPDATE SET kind=excluded.kind",
      user.id(),
      newsId,
      kind
    );
    return summary(newsId, user);
  }

  @Transactional
  public Summary remove(UUID newsId, Accounts.User user) {
    lockPublished(newsId);
    db.update("DELETE FROM reactions WHERE user_id=? AND news_id=?", user.id(), newsId);
    return summary(newsId, user);
  }

  private void lockPublished(UUID newsId) {
    if (
      db
        .queryForList(
          "SELECT id FROM news WHERE id=? AND NOT deleted AND published_revision_id IS NOT NULL FOR UPDATE",
          newsId
        )
        .isEmpty()
    ) throw ApiException.missing();
  }
}
