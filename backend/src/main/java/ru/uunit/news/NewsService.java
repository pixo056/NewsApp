package ru.uunit.news;

import com.fasterxml.jackson.databind.node.NullNode;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class NewsService {

  public record Input(
    String title,
    String text,
    String category,
    UUID communityId,
    String source,
    List<UUID> mediaIds
  ) {}

  public record Page(List<Map<String, Object>> items, String nextCursor) {}

  private final JdbcTemplate db;
  private final Accounts accounts;
  private final Communities communities;
  private static final String SELECT = """
  SELECT n.*,r.id AS revision_id,r.title,r.body,r.category,r.source_url,r.status,r.rejection_reason,r.submitted_at,
  u.display_name AS author,c.official,c.owner_id FROM news n JOIN news_revisions r ON r.id=%s
  JOIN app_users u ON u.id=n.author_id LEFT JOIN communities c ON c.id=n.community_id
  """;

  public NewsService(JdbcTemplate db, Accounts accounts, Communities communities) {
    this.db = db;
    this.accounts = accounts;
    this.communities = communities;
  }

  public boolean moderator(Map<String, Object> row, Accounts.User user) {
    return (
      accounts.admin(user) ||
      (user != null &&
        row.get("community_id") != null &&
        !Boolean.TRUE.equals(row.get("official")) &&
        user.id().equals(row.get("owner_id")))
    );
  }

  private boolean editorialAuthor(Map<String, Object> row, Accounts.User user) {
    return (
      accounts.admin(user) ||
      (user != null &&
        user.id().equals(row.get("author_id")) &&
        user.id().equals(row.get("owner_id")) &&
        !Boolean.TRUE.equals(row.get("official")) &&
        !Boolean.TRUE.equals(row.get("deleted")))
    );
  }

  public Map<String, Object> newsRow(UUID id) {
    return db
      .queryForList(
        "SELECT n.*,c.official,c.owner_id FROM news n LEFT JOIN communities c ON c.id=n.community_id WHERE n.id=? AND NOT n.deleted",
        id
      )
      .stream()
      .findFirst()
      .orElseThrow(ApiException::missing);
  }

  public Map<String, Object> view(Map<String, Object> row, Accounts.User user) {
    Map<String, Object> p = new LinkedHashMap<>();
    for (String key : List.of("id", "title", "category", "author", "status"))
      p.put(key, row.get(key));
    p.put("text", row.get("body"));
    p.put("source", row.get("source_url"));
    p.put("authorId", row.get("author_id"));
    p.put("communityId", row.get("community_id"));
    p.put("revisionId", row.get("revision_id"));
    p.put(
      "publishedAt",
      (
        (Timestamp) (row.get("published_at") != null
          ? row.get("published_at")
          : row.get("submitted_at"))
      )
        .toInstant()
        .toString()
    );
    p.put("rejectionReason", row.get("rejection_reason"));
    p.put("views", row.get("views").toString());
    p.put("canEdit", user != null && user.id().equals(row.get("author_id")));
    p.put("canModerate", moderator(row, user));
    p.put(
      "hasPendingChanges",
      row.get("published_revision_id") != null &&
        !row.get("published_revision_id").equals(row.get("latest_revision_id")) &&
        db.queryForObject(
          "SELECT status='pending' FROM news_revisions WHERE id=?",
          Boolean.class,
          row.get("latest_revision_id")
        )
    );
    Map<String, Long> reactionCounts = new LinkedHashMap<>();
    ReactionService.KINDS.forEach(kind -> reactionCounts.put(kind, 0L));
    String myReaction = null;
    long totalReactions = 0;
    for (Map<String, Object> reaction : db.queryForList(
      "SELECT kind,count(*) AS total,bool_or(user_id=?::uuid) AS selected FROM reactions WHERE news_id=? GROUP BY kind",
      user == null ? null : user.id(),
      row.get("id")
    )) {
      String kind = (String) reaction.get("kind");
      long total = ((Number) reaction.get("total")).longValue();
      reactionCounts.put(kind, total);
      totalReactions += total;
      if (Boolean.TRUE.equals(reaction.get("selected"))) myReaction = kind;
    }
    p.put("reactionCounts", reactionCounts);
    p.put("myReaction", myReaction == null ? NullNode.getInstance() : myReaction);
    p.put("reactions", totalReactions);
    p.put("liked", "like".equals(myReaction));
    List<Map<String, Object>> media = db
      .queryForList(
        "SELECT m.* FROM revision_media rm JOIN media_assets m ON m.id=rm.media_id WHERE rm.revision_id=? ORDER BY rm.sort_order",
        row.get("revision_id")
      )
      .stream()
      .map(m -> {
        Map<String, Object> result = new LinkedHashMap<>();
        for (String key : List.of("id", "kind", "width", "height", "duration"))
          result.put(key, m.get(key));
        result.put("url", "/api/media/" + m.get("id"));
        if (m.get("poster_filename") != null) result.put(
          "posterUrl",
          "/api/media/" + m.get("id") + "/poster"
        );
        return result;
      })
      .toList();
    p.put("media", media);
    p.put("video", media.stream().anyMatch(m -> "video".equals(m.get("kind"))));
    media
      .stream()
      .filter(m -> "image".equals(m.get("kind")))
      .findFirst()
      .ifPresent(m -> p.put("image", m.get("url")));
    media
      .stream()
      .filter(m -> "video".equals(m.get("kind")))
      .findFirst()
      .ifPresent(m -> {
        p.put("videoUrl", m.get("url"));
        p.put("poster", m.get("posterUrl"));
      });
    return p;
  }

  public Page feed(
    Accounts.User user,
    String category,
    String query,
    UUID community,
    String cursor,
    int limit,
    boolean subscriptions,
    boolean saved
  ) {
    limit = Math.max(1, Math.min(limit, 50));
    List<Object> args = new ArrayList<>();
    StringBuilder sql = new StringBuilder(
      SELECT.formatted("n.published_revision_id") + " WHERE NOT n.deleted"
    );
    if (category != null && !category.isBlank() && !category.equals("Все")) {
      sql.append(" AND r.category=?");
      args.add(category);
    }
    if (query != null && !query.isBlank()) {
      if (query.length() > 200) throw ApiException.bad("Слишком длинный запрос");
      sql.append(" AND (r.title ILIKE ? OR r.body ILIKE ?)");
      args.add("%" + query + "%");
      args.add("%" + query + "%");
    }
    if (community != null) {
      sql.append(" AND n.community_id=?");
      args.add(community);
    }
    if (subscriptions || saved) {
      if (user == null) throw new ApiException(
        org.springframework.http.HttpStatus.UNAUTHORIZED,
        "Войдите в аккаунт"
      );
      sql.append(
        subscriptions
          ? " AND EXISTS(SELECT 1 FROM subscriptions s WHERE s.community_id=n.community_id AND s.user_id=?)"
          : " AND EXISTS(SELECT 1 FROM bookmarks b WHERE b.news_id=n.id AND b.user_id=?)"
      );
      args.add(user.id());
    }
    if (cursor != null && !cursor.isBlank()) {
      try {
        String[] parts = new String(
          Base64.getUrlDecoder().decode(cursor),
          StandardCharsets.UTF_8
        ).split("\\|", 2);
        sql.append(" AND (n.published_at,n.id)<(?,?)");
        args.add(Timestamp.from(Instant.parse(parts[0])));
        args.add(UUID.fromString(parts[1]));
      } catch (RuntimeException e) {
        throw ApiException.bad("Некорректный курсор ленты");
      }
    }
    sql.append(" ORDER BY n.published_at DESC,n.id DESC LIMIT ?");
    args.add(limit + 1);
    List<Map<String, Object>> rows = db.queryForList(sql.toString(), args.toArray());
    boolean more = rows.size() > limit;
    List<Map<String, Object>> visible = rows.subList(0, Math.min(limit, rows.size()));
    String next = null;
    if (more) {
      Map<String, Object> last = visible.getLast();
      String raw = ((Timestamp) last.get("published_at")).toInstant() + "|" + last.get("id");
      next = Base64.getUrlEncoder()
        .withoutPadding()
        .encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }
    return new Page(
      visible
        .stream()
        .map(r -> view(r, user))
        .toList(),
      next
    );
  }

  public Map<String, Object> detail(UUID id, Accounts.User user, boolean latest) {
    Map<String, Object> news = newsRow(id);
    boolean privileged =
      user != null && (user.id().equals(news.get("author_id")) || moderator(news, user));
    if (latest && !privileged) throw ApiException.missing();
    UUID revision = (UUID) news.get(latest ? "latest_revision_id" : "published_revision_id");
    if (revision == null) {
      if (!privileged) throw ApiException.missing();
      revision = (UUID) news.get("latest_revision_id");
    }
    return view(db.queryForMap(SELECT.formatted("?") + " WHERE n.id=?", revision, id), user);
  }

  public List<Map<String, Object>> mine(Accounts.User user) {
    return db
      .queryForList(
        SELECT.formatted("n.latest_revision_id") +
          " WHERE n.author_id=? AND NOT n.deleted ORDER BY n.created_at DESC",
        user.id()
      )
      .stream()
      .map(row -> view(row, user))
      .toList();
  }

  private int characters(String s) {
    return s == null ? 0 : s.codePointCount(0, s.length());
  }

  @Transactional
  public UUID submit(Accounts.User user, Input input, UUID existing) {
    accounts.verified(user);
    Validated validated = validateInput(user, input, existing);
    List<UUID> ids = validated.ids();
    String text = validated.text();
    db.queryForObject(
      "SELECT id FROM app_users WHERE id=? FOR NO KEY UPDATE",
      UUID.class,
      user.id()
    );
    Map<String, Object> community = null;
    if (input.communityId != null) {
      community = db
        .queryForList("SELECT * FROM communities WHERE id=? FOR UPDATE", input.communityId)
        .stream()
        .findFirst()
        .orElseThrow(ApiException::missing);
    }
    checkQuota(user, community, input.communityId, true);
    UUID id = existing == null ? UUID.randomUUID() : existing;
    if (existing == null) db.update(
      "INSERT INTO news(id,author_id,community_id) VALUES(?,?,?)",
      id,
      user.id(),
      input.communityId
    );
    else {
      Map<String, Object> row = db
        .queryForList("SELECT * FROM news WHERE id=? AND NOT deleted FOR UPDATE", id)
        .stream()
        .findFirst()
        .orElseThrow(ApiException::missing);
      if (!user.id().equals(row.get("author_id"))) throw ApiException.forbidden();
      if (!Objects.equals(input.communityId, row.get("community_id"))) throw ApiException.bad(
        "При редактировании нельзя изменить паблик"
      );
      withdrawPending(row);
    }
    saveRevision(id, input, text, ids);
    return id;
  }

  private record Validated(List<UUID> ids, String text) {}

  private Validated validateInput(Accounts.User user, Input input, UUID editorialNews) {
    if (input == null) throw ApiException.bad("Заполните поля новости");
    List<UUID> ids = input.mediaIds == null ? List.of() : input.mediaIds;
    if (
      characters(input.title) < 10 ||
      characters(input.title) > 150 ||
      input.title.isBlank() ||
      input.category == null ||
      !Communities.CATEGORIES.contains(input.category) ||
      ids.size() > 4 ||
      ids.stream().anyMatch(Objects::isNull) ||
      new HashSet<>(ids).size() != ids.size()
    ) throw ApiException.bad(
      "Проверьте заголовок (10–150), категорию и вложения (до 4 фото или одно видео)"
    );
    if (input.source != null && !input.source.isBlank()) try {
      URI source = URI.create(input.source);
      if (
        input.source.length() > 2000 ||
        !Set.of("https", "http").contains(source.getScheme()) ||
        source.getHost() == null
      ) throw new IllegalArgumentException();
    } catch (IllegalArgumentException e) {
      throw ApiException.bad("Источник должен быть корректной HTTP/HTTPS-ссылкой");
    }
    List<Map<String, Object>> media = new ArrayList<>();
    for (UUID id : ids)
      media.add(
        db
          .queryForList(
            "SELECT m.* FROM media_assets m WHERE m.id=? AND (m.owner_id=? OR EXISTS(" +
              "SELECT 1 FROM revision_media rm JOIN news_revisions r ON r.id=rm.revision_id " +
              "WHERE rm.media_id=m.id AND r.news_id=?)) FOR KEY SHARE OF m",
            id,
            user.id(),
            editorialNews
          )
          .stream()
          .findFirst()
          .orElseThrow(() ->
            ApiException.bad("Вложение не найдено или принадлежит другому пользователю")
          )
      );
    long videos = media
      .stream()
      .filter(m -> "video".equals(m.get("kind")))
      .count();
    if (videos > 0 && media.size() != 1) throw ApiException.bad(
      "Прикрепите одно видео либо до четырёх фотографий"
    );
    String text = input.text == null ? "" : input.text;
    if (characters(text) > 10000 || (videos == 0 && characters(text) < 50)) throw ApiException.bad(
      "Текст: 50–10 000 символов. С видео текст необязателен."
    );
    return new Validated(ids, text);
  }

  private void checkQuota(
    Accounts.User user,
    Map<String, Object> community,
    UUID communityId,
    boolean personal
  ) {
    Timestamp day = Timestamp.from(
      LocalDate.now(ZoneId.of("Asia/Yekaterinburg"))
        .atStartOfDay(ZoneId.of("Asia/Yekaterinburg"))
        .toInstant()
    );
    int allowance =
      community != null && user.id().equals(community.get("owner_id"))
        ? ((Number) community.get("daily_limit")).intValue()
        : 5;
    if (
      personal &&
      !accounts.admin(user) &&
      db.queryForObject(
        "SELECT count(*) FROM news_revisions r JOIN news n ON n.id=r.news_id WHERE n.author_id=? AND r.submitted_at>=?",
        Integer.class,
        user.id(),
        day
      ) >= allowance
    ) throw ApiException.bad("Суточный лимит предложений исчерпан");
    if (
      community != null &&
      db.queryForObject(
        "SELECT count(*) FROM news_revisions r JOIN news n ON n.id=r.news_id WHERE n.community_id=? AND r.submitted_at>=?",
        Integer.class,
        communityId,
        day
      ) >= ((Number) community.get("daily_limit")).intValue()
    ) throw ApiException.bad("Суточный лимит паблика исчерпан");
  }

  private void withdrawPending(Map<String, Object> row) {
    db.update(
      "UPDATE news_revisions SET status='withdrawn' WHERE id=? AND status='pending'",
      row.get("latest_revision_id")
    );
  }

  private void saveRevision(UUID id, Input input, String text, List<UUID> ids) {
    UUID revision = UUID.randomUUID();
    db.update(
      "INSERT INTO news_revisions(id,news_id,title,body,category,source_url,status) VALUES(?,?,?,?,?,?,'pending')",
      revision,
      id,
      input.title,
      text,
      input.category,
      input.source == null ? "" : input.source.trim()
    );
    for (int i = 0; i < ids.size(); i++) db.update(
      "INSERT INTO revision_media(revision_id,media_id,sort_order) VALUES(?,?,?)",
      revision,
      ids.get(i),
      i
    );
    db.update("UPDATE news SET latest_revision_id=? WHERE id=?", revision, id);
  }

  @Transactional
  public void editorialEdit(UUID id, Accounts.User editor, Input input) {
    accounts.verified(editor);
    Map<String, Object> before = db
      .queryForList(
        "SELECT n.*,c.official,c.owner_id FROM news n LEFT JOIN communities c ON c.id=n.community_id WHERE n.id=?",
        id
      )
      .stream()
      .findFirst()
      .orElseThrow(ApiException::missing);
    if (!editorialAuthor(before, editor)) throw ApiException.forbidden();
    if (input == null) throw ApiException.bad("Заполните поля новости");
    if (input.mediaIds() == null) input = new Input(
      input.title(),
      input.text(),
      input.category(),
      input.communityId(),
      input.source(),
      db.queryForList(
        "SELECT media_id FROM revision_media WHERE revision_id=? ORDER BY sort_order",
        UUID.class,
        before.get("latest_revision_id")
      )
    );
    Validated validated = validateInput(editor, input, id);
    db.queryForObject(
      "SELECT id FROM app_users WHERE id=? FOR NO KEY UPDATE",
      UUID.class,
      editor.id()
    );
    Map<String, Object> community = null;
    if (before.get("community_id") != null) community = db
      .queryForList("SELECT * FROM communities WHERE id=? FOR UPDATE", before.get("community_id"))
      .stream()
      .findFirst()
      .orElseThrow(ApiException::missing);
    Map<String, Object> row = db
      .queryForList("SELECT * FROM news WHERE id=? FOR UPDATE", id)
      .stream()
      .findFirst()
      .orElseThrow(ApiException::missing);
    Map<String, Object> permission = new HashMap<>(row);
    if (community != null) {
      permission.put("official", community.get("official"));
      permission.put("owner_id", community.get("owner_id"));
    }
    if (!editorialAuthor(permission, editor)) throw ApiException.forbidden();
    if (!Objects.equals(input.communityId(), row.get("community_id"))) throw ApiException.bad(
      "При редактировании нельзя изменить паблик"
    );
    checkQuota(editor, community, input.communityId(), !accounts.admin(editor));
    withdrawPending(row);
    saveRevision(id, input, validated.text(), validated.ids());
    db.update(
      "INSERT INTO audit_log(id,actor_id,action,target_id,detail) VALUES(?,?,'cms-edit',?,?)",
      UUID.randomUUID(),
      editor.id(),
      id,
      "Редакторская правка отправлена на модерацию"
    );
  }

  public List<Map<String, Object>> queue(Accounts.User user) {
    return db
      .queryForList(
        SELECT.formatted("n.latest_revision_id") +
          " WHERE NOT n.deleted AND r.status='pending' ORDER BY r.submitted_at"
      )
      .stream()
      .filter(row -> moderator(row, user))
      .map(row -> view(row, user))
      .toList();
  }

  @Transactional
  public void decide(UUID revision, Accounts.User user, boolean approve, String reason) {
    UUID newsId = db
      .query(
        "SELECT news_id FROM news_revisions WHERE id=?",
        (r, n) -> r.getObject(1, UUID.class),
        revision
      )
      .stream()
      .findFirst()
      .orElseThrow(ApiException::missing);
    lockNewsAndCommunity(newsId);
    Map<String, Object> row = newsRow(newsId);
    if (!moderator(row, user)) throw ApiException.forbidden();
    String status = db.queryForObject(
      "SELECT status FROM news_revisions WHERE id=? FOR UPDATE",
      String.class,
      revision
    );
    if (
      !"pending".equals(status) || !revision.equals(row.get("latest_revision_id"))
    ) throw new ApiException(
      org.springframework.http.HttpStatus.CONFLICT,
      "Заявка уже рассмотрена или заменена новой версией"
    );
    if (
      !approve && (reason == null || reason.trim().length() < 5 || reason.length() > 2000)
    ) throw ApiException.bad("Укажите причину отклонения: 5–2000 символов");
    db.update(
      "UPDATE news_revisions SET status=?,reviewer_id=?,reviewed_at=now(),rejection_reason=? WHERE id=?",
      approve ? "published" : "rejected",
      user.id(),
      approve ? null : reason.trim(),
      revision
    );
    if (approve) db.update(
      "UPDATE news SET published_revision_id=?,published_at=coalesce(published_at,now()) WHERE id=?",
      revision,
      newsId
    );
    String message = approve
      ? "Ваша новость одобрена и опубликована"
      : "Новость отклонена: " + reason.trim();
    db.update(
      "INSERT INTO notifications(id,user_id,message,news_id) VALUES(?,?,?,?)",
      UUID.randomUUID(),
      row.get("author_id"),
      message.substring(0, Math.min(500, message.length())),
      newsId
    );
    db.update(
      "INSERT INTO audit_log(id,actor_id,action,target_id,detail) VALUES(?,?,?,?,?)",
      UUID.randomUUID(),
      user.id(),
      approve ? "approve" : "reject",
      revision,
      approve ? "" : reason.trim()
    );
  }

  @Transactional
  public void delete(UUID id, Accounts.User user, boolean moderation) {
    lockNewsAndCommunity(id);
    Map<String, Object> row = newsRow(id);
    if (
      moderation ? !moderator(row, user) : !user.id().equals(row.get("author_id"))
    ) throw ApiException.forbidden();
    db.update("UPDATE news SET deleted=TRUE WHERE id=?", id);
    db.update(
      "INSERT INTO audit_log(id,actor_id,action,target_id) VALUES(?,?,?,?)",
      UUID.randomUUID(),
      user.id(),
      moderation ? "hide" : "delete",
      id
    );
  }

  private void lockNewsAndCommunity(UUID id) {
    Map<String, Object> identity = db
      .queryForList("SELECT community_id FROM news WHERE id=?", id)
      .stream()
      .findFirst()
      .orElseThrow(ApiException::missing);
    if (identity.get("community_id") != null) db.queryForObject(
      "SELECT id FROM communities WHERE id=? FOR UPDATE",
      UUID.class,
      identity.get("community_id")
    );
    db.queryForObject("SELECT id FROM news WHERE id=? FOR UPDATE", UUID.class, id);
  }
}
