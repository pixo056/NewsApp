package ru.uunit.news;

import java.time.Duration;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api")
public class PlatformController {

  private final JdbcTemplate db;
  private final Accounts accounts;
  private final Communities communities;
  private final NewsService news;
  private final RateLimiter rates;

  public PlatformController(
    JdbcTemplate db,
    Accounts accounts,
    Communities communities,
    NewsService news,
    RateLimiter rates
  ) {
    this.db = db;
    this.accounts = accounts;
    this.communities = communities;
    this.news = news;
    this.rates = rates;
  }

  record CommunityInput(String name, String description, String category, String color) {}

  record Settings(int dailyLimit, boolean official) {}

  record Reason(String reason) {}

  record Report(String reason, String comment) {}

  record Resolution(String resolution) {}

  @GetMapping("/news")
  NewsService.Page feed(
    Authentication auth,
    @RequestParam(required = false) String category,
    @RequestParam(required = false) String q,
    @RequestParam(required = false) UUID communityId,
    @RequestParam(required = false) String cursor,
    @RequestParam(defaultValue = "20") int limit,
    @RequestParam(defaultValue = "false") boolean subscriptions,
    @RequestParam(defaultValue = "false") boolean saved
  ) {
    return news.feed(
      accounts.current(auth),
      category,
      q,
      communityId,
      cursor,
      limit,
      subscriptions,
      saved
    );
  }

  @GetMapping("/news/{id}")
  Object detail(@PathVariable UUID id, Authentication auth) {
    return news.detail(id, accounts.current(auth), false);
  }

  @PostMapping("/news/{id}/view")
  void view(@PathVariable UUID id, jakarta.servlet.http.HttpServletRequest request) {
    rates.check("view:" + request.getRemoteAddr(), 200, Duration.ofMinutes(1));
    db.update(
      "UPDATE news SET views=views+1 WHERE id=? AND NOT deleted AND published_revision_id IS NOT NULL",
      id
    );
  }

  @GetMapping("/me/news")
  Object mine(Authentication auth) {
    return news.mine(accounts.require(auth));
  }

  @GetMapping("/me/news/{id}")
  Object own(@PathVariable UUID id, Authentication auth) {
    return news.detail(id, accounts.require(auth), true);
  }

  @PostMapping("/news")
  Object create(@RequestBody NewsService.Input input, Authentication auth) {
    UUID id = news.submit(accounts.require(auth), input, null);
    return Map.of("id", id);
  }

  @PostMapping("/news/{id}/revisions")
  Object edit(@PathVariable UUID id, @RequestBody NewsService.Input input, Authentication auth) {
    news.submit(accounts.require(auth), input, id);
    return Map.of("id", id);
  }

  @DeleteMapping("/news/{id}")
  void delete(@PathVariable UUID id, Authentication auth) {
    news.delete(id, accounts.require(auth), false);
  }

  @GetMapping("/communities")
  Object communities(Authentication auth) {
    return communities.list(accounts.current(auth));
  }

  @PostMapping("/communities")
  Object createCommunity(@RequestBody CommunityInput input, Authentication auth) {
    UUID id = communities.create(
      accounts.require(auth),
      input.name,
      input.description,
      input.category,
      input.color
    );
    return Map.of("id", id);
  }

  @PatchMapping("/communities/{id}")
  void editCommunity(
    @PathVariable UUID id,
    @RequestBody CommunityInput input,
    Authentication auth
  ) {
    communities.update(id, accounts.require(auth), input.name, input.description);
  }

  @PatchMapping("/admin/communities/{id}")
  void communitySettings(@PathVariable UUID id, @RequestBody Settings input, Authentication auth) {
    communities.settings(id, accounts.require(auth), input.dailyLimit, input.official);
  }

  @GetMapping("/me/preferences")
  Object preferences(Authentication auth) {
    UUID id = accounts.require(auth).id();
    return Map.of(
      "saved",
      db.queryForList(
        "SELECT b.news_id FROM bookmarks b JOIN news n ON n.id=b.news_id WHERE b.user_id=? AND NOT n.deleted AND n.published_revision_id IS NOT NULL",
        UUID.class,
        id
      ),
      "followed",
      db.queryForList("SELECT community_id FROM subscriptions WHERE user_id=?", UUID.class, id)
    );
  }

  @RequestMapping(
    value = "/communities/{id}/subscription",
    method = { RequestMethod.PUT, RequestMethod.DELETE }
  )
  void subscription(
    @PathVariable UUID id,
    Authentication auth,
    jakarta.servlet.http.HttpServletRequest request
  ) {
    Accounts.User user = accounts.require(auth);
    communities.require(id);
    if (request.getMethod().equals("PUT")) db.update(
      "INSERT INTO subscriptions(user_id,community_id) VALUES(?,?) ON CONFLICT DO NOTHING",
      user.id(),
      id
    );
    else db.update("DELETE FROM subscriptions WHERE user_id=? AND community_id=?", user.id(), id);
  }

  @RequestMapping(
    value = "/news/{id}/bookmark",
    method = { RequestMethod.PUT, RequestMethod.DELETE }
  )
  void bookmark(
    @PathVariable UUID id,
    Authentication auth,
    jakarta.servlet.http.HttpServletRequest request
  ) {
    Accounts.User user = accounts.require(auth);
    published(id);
    if (request.getMethod().equals("PUT")) db.update(
      "INSERT INTO bookmarks(user_id,news_id) VALUES(?,?) ON CONFLICT DO NOTHING",
      user.id(),
      id
    );
    else db.update("DELETE FROM bookmarks WHERE user_id=? AND news_id=?", user.id(), id);
  }

  @RequestMapping(
    value = "/news/{id}/reaction",
    method = { RequestMethod.PUT, RequestMethod.DELETE }
  )
  void reaction(
    @PathVariable UUID id,
    Authentication auth,
    jakarta.servlet.http.HttpServletRequest request
  ) {
    Accounts.User user = accounts.require(auth);
    published(id);
    if (request.getMethod().equals("PUT")) db.update(
      "INSERT INTO reactions(user_id,news_id) VALUES(?,?) ON CONFLICT DO NOTHING",
      user.id(),
      id
    );
    else db.update("DELETE FROM reactions WHERE user_id=? AND news_id=?", user.id(), id);
  }

  private void published(UUID id) {
    if (news.newsRow(id).get("published_revision_id") == null) throw ApiException.missing();
  }

  @PostMapping("/news/{id}/reports")
  void report(@PathVariable UUID id, @RequestBody Report input, Authentication auth) {
    Accounts.User user = accounts.require(auth);
    accounts.verified(user);
    published(id);
    rates.check("report:" + user.id(), 10, Duration.ofDays(1));
    if (
      input.reason == null ||
      input.reason.isBlank() ||
      input.reason.length() > 100 ||
      (input.comment != null && input.comment.length() > 2000)
    ) throw ApiException.bad("Укажите причину и комментарий до 2000 символов");
    db.update(
      "INSERT INTO reports(id,news_id,reporter_id,reason,comment) VALUES(?,?,?,?,?)",
      UUID.randomUUID(),
      id,
      user.id(),
      input.reason,
      input.comment == null ? "" : input.comment
    );
  }

  @GetMapping("/admin/revisions")
  Object queue(Authentication auth) {
    return news.queue(accounts.require(auth));
  }

  @PostMapping("/admin/revisions/{id}/approve")
  void approve(@PathVariable UUID id, Authentication auth) {
    news.decide(id, accounts.require(auth), true, null);
  }

  @PostMapping("/admin/revisions/{id}/reject")
  void reject(@PathVariable UUID id, @RequestBody Reason reason, Authentication auth) {
    news.decide(id, accounts.require(auth), false, reason.reason);
  }

  @PostMapping("/admin/news/{id}/hide")
  void hide(@PathVariable UUID id, Authentication auth) {
    news.delete(id, accounts.require(auth), true);
  }

  @GetMapping("/admin/reports")
  Object reports(Authentication auth) {
    Accounts.User user = accounts.require(auth);
    return db
      .queryForList(
        "SELECT r.*,n.community_id,c.official,c.owner_id,rev.title,u.display_name AS reporter FROM reports r JOIN news n ON n.id=r.news_id JOIN news_revisions rev ON rev.id=n.published_revision_id JOIN app_users u ON u.id=r.reporter_id LEFT JOIN communities c ON c.id=n.community_id ORDER BY r.created_at DESC"
      )
      .stream()
      .filter(r -> news.moderator(r, user))
      .map(r -> {
        Map<String, Object> m = new LinkedHashMap<>();
        for (String k : List.of(
          "id",
          "title",
          "reason",
          "comment",
          "status",
          "resolution",
          "reporter"
        ))
          m.put(k, r.get(k));
        m.put("newsId", r.get("news_id"));
        return m;
      })
      .toList();
  }

  @PatchMapping("/admin/reports/{id}")
  void resolve(@PathVariable UUID id, @RequestBody Resolution input, Authentication auth) {
    Accounts.User user = accounts.require(auth);
    Map<String, Object> report = db
      .queryForList("SELECT * FROM reports WHERE id=?", id)
      .stream()
      .findFirst()
      .orElseThrow(ApiException::missing);
    Map<String, Object> row = db.queryForMap(
      "SELECT n.*,c.official,c.owner_id FROM news n LEFT JOIN communities c ON c.id=n.community_id WHERE n.id=?",
      report.get("news_id")
    );
    if (!news.moderator(row, user)) throw ApiException.forbidden();
    if (
      input.resolution == null || input.resolution.isBlank() || input.resolution.length() > 2000
    ) throw ApiException.bad("Укажите результат рассмотрения до 2000 символов");
    db.update(
      "UPDATE reports SET status='resolved',resolution=?,reviewer_id=?,reviewed_at=now() WHERE id=?",
      input.resolution,
      user.id(),
      id
    );
  }

  @GetMapping("/me/notifications")
  Object notifications(Authentication auth) {
    return db.queryForList(
      "SELECT id,message,news_id AS \"newsId\",read_at IS NOT NULL AS read,created_at AS \"createdAt\" FROM notifications WHERE user_id=? ORDER BY created_at DESC LIMIT 100",
      accounts.require(auth).id()
    );
  }

  @PostMapping("/me/notifications/read")
  void readNotifications(Authentication auth) {
    db.update(
      "UPDATE notifications SET read_at=now() WHERE user_id=? AND read_at IS NULL",
      accounts.require(auth).id()
    );
  }

  @GetMapping("/admin/audit")
  Object audit(Authentication auth) {
    Accounts.User user = accounts.require(auth);
    if (!accounts.admin(user)) throw ApiException.forbidden();
    return db.queryForList(
      "SELECT a.id,a.action,a.detail,a.target_id AS \"targetId\",a.created_at AS \"createdAt\",u.username FROM audit_log a JOIN app_users u ON u.id=a.actor_id ORDER BY a.created_at DESC LIMIT 100"
    );
  }
}
