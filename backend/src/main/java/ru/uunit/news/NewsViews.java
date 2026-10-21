package ru.uunit.news;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class NewsViews {

  public record Result(int views, boolean counted) {}

  private static final Duration WINDOW = Duration.ofHours(24);
  private static final long CLEANUP_INTERVAL_MILLIS = Duration.ofMinutes(1).toMillis();
  private final JdbcTemplate db;
  private final AtomicLong lastCleanup = new AtomicLong();

  public NewsViews(JdbcTemplate db) {
    this.db = db;
  }

  @Transactional
  public Result record(UUID newsId, UUID accountId, String sessionId) {
    int current = db
      .query(
        "SELECT views FROM news WHERE id=? AND NOT deleted AND published_revision_id IS NOT NULL FOR UPDATE",
        (row, index) -> row.getInt("views"),
        newsId
      )
      .stream()
      .findFirst()
      .orElseThrow(ApiException::missing);
    Instant now = Instant.now();
    Timestamp cutoff = Timestamp.from(now.minus(WINDOW));
    String key = viewerKey(accountId, sessionId);
    int changed = db.update(
      """
      INSERT INTO news_viewers(news_id,viewer_key,viewed_at) VALUES(?,?,?)
      ON CONFLICT(news_id,viewer_key) DO UPDATE SET viewed_at=EXCLUDED.viewed_at
      WHERE news_viewers.viewed_at<=?
      """,
      newsId,
      key,
      Timestamp.from(now),
      cutoff
    );
    if (changed > 0) current = db.queryForObject(
      "UPDATE news SET views=CASE WHEN views<2147483647 THEN views+1 ELSE views END WHERE id=? RETURNING views",
      Integer.class,
      newsId
    );
    cleanup(now, cutoff);
    return new Result(current, changed > 0);
  }

  private String viewerKey(UUID accountId, String sessionId) {
    String identity;
    String prefix;
    if (accountId != null) {
      identity = accountId.toString();
      prefix = "u:";
    } else {
      if (sessionId == null || sessionId.isBlank()) throw ApiException.bad(
        "Не найдена сессия браузера"
      );
      identity = sessionId;
      prefix = "s:";
    }
    try {
      return (
        prefix +
        HexFormat.of().formatHex(
          MessageDigest.getInstance("SHA-256").digest(identity.getBytes(StandardCharsets.UTF_8))
        )
      );
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  private void cleanup(Instant now, Timestamp cutoff) {
    long previous = lastCleanup.get();
    long millis = now.toEpochMilli();
    if (
      millis - previous < CLEANUP_INTERVAL_MILLIS || !lastCleanup.compareAndSet(previous, millis)
    ) return;
    // A bounded batch prevents a busy article from triggering an unbounded retention sweep.
    db.update(
      """
      DELETE FROM news_viewers WHERE (news_id,viewer_key) IN (
        SELECT news_id,viewer_key FROM news_viewers WHERE viewed_at<?
        ORDER BY viewed_at LIMIT 500 FOR UPDATE SKIP LOCKED
      )
      """,
      cutoff
    );
  }
}
