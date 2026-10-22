package ru.uunit.news;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class NewsViewsIntegrationTest {

  @Autowired
  MockMvc mvc;

  @Autowired
  JdbcTemplate db;

  @Autowired
  Accounts accounts;

  @Autowired
  NewsService news;

  @Autowired
  NewsViews views;

  private UUID create(boolean publish) {
    Accounts.User admin = accounts.find("uunit.admin");
    UUID id = news.submit(
      admin,
      new NewsService.Input(
        "Новость для проверки просмотров",
        "Проверяем реальные просмотры новости, повторные открытия и защиту от параллельных запросов.",
        "Технологии",
        null,
        "",
        List.of()
      ),
      null
    );
    if (publish) news.decide(
      db.queryForObject("SELECT latest_revision_id FROM news WHERE id=?", UUID.class, id),
      admin,
      true,
      null
    );
    return id;
  }

  @Test
  void anonymousRefreshesCountOncePerBrowserSession() throws Exception {
    UUID id = create(true);
    MockHttpSession browser = new MockHttpSession();
    mvc
      .perform(
        post("/api/news/" + id + "/view")
          .session(browser)
          .with(csrf())
      )
      .andExpect(status().isOk())
      .andExpect(jsonPath("views").value(1))
      .andExpect(jsonPath("counted").value(true));
    mvc
      .perform(
        post("/api/news/" + id + "/view")
          .session(browser)
          .with(csrf())
      )
      .andExpect(status().isOk())
      .andExpect(jsonPath("views").value(1))
      .andExpect(jsonPath("counted").value(false));
    mvc
      .perform(
        post("/api/news/" + id + "/view")
          .session(new MockHttpSession())
          .with(csrf())
      )
      .andExpect(status().isOk())
      .andExpect(jsonPath("views").value(2));
    List<String> keys = db.queryForList(
      "SELECT viewer_key FROM news_viewers WHERE news_id=?",
      String.class,
      id
    );
    assertEquals(2, keys.size());
    assertTrue(keys.stream().allMatch(key -> key.matches("s:[0-9a-f]{64}")));
    assertFalse(keys.contains(browser.getId()));
  }

  @Test
  void accountViewsCountOnceAcrossBrowsers() throws Exception {
    UUID id = create(true);
    for (int index = 0; index < 2; index++) {
      mvc
        .perform(
          post("/api/news/" + id + "/view")
            .session(new MockHttpSession())
            .with(user("club.owner"))
            .with(csrf())
        )
        .andExpect(status().isOk())
        .andExpect(jsonPath("views").value(1))
        .andExpect(jsonPath("counted").value(index == 0));
    }
    mvc
      .perform(
        post("/api/news/" + id + "/view")
          .with(user("uunit.admin"))
          .with(csrf())
      )
      .andExpect(status().isOk())
      .andExpect(jsonPath("views").value(2));
  }

  @Test
  void repeatsDoNotExtendTheTwentyFourHourWindow() {
    UUID id = create(true);
    UUID account = accounts.find("club.owner").id();
    assertTrue(views.record(id, account, null).counted());
    Timestamp previous = Timestamp.from(
      Instant.now().minus(Duration.ofHours(23)).truncatedTo(java.time.temporal.ChronoUnit.MICROS)
    );
    db.update("UPDATE news_viewers SET viewed_at=? WHERE news_id=?", previous, id);
    assertFalse(views.record(id, account, null).counted());
    assertEquals(
      previous.toInstant().truncatedTo(java.time.temporal.ChronoUnit.MICROS),
      db
        .queryForObject("SELECT viewed_at FROM news_viewers WHERE news_id=?", Timestamp.class, id)
        .toInstant()
    );
    db.update(
      "UPDATE news_viewers SET viewed_at=? WHERE news_id=?",
      Timestamp.from(Instant.now().minus(Duration.ofHours(25))),
      id
    );
    NewsViews.Result result = views.record(id, account, null);
    assertTrue(result.counted());
    assertEquals(2, result.views());
  }

  @Test
  void draftDeletedAndMissingNewsCannotReceiveViews() throws Exception {
    UUID draft = create(false);
    UUID deleted = create(true);
    news.delete(deleted, accounts.find("uunit.admin"), false);
    for (UUID id : List.of(draft, deleted, UUID.randomUUID())) {
      mvc.perform(post("/api/news/" + id + "/view").with(csrf())).andExpect(status().isNotFound());
    }
    assertEquals(
      0,
      db.queryForObject(
        "SELECT count(*) FROM news_viewers WHERE news_id IN (?,?)",
        Integer.class,
        draft,
        deleted
      )
    );
    assertEquals(
      0,
      db.queryForObject(
        "SELECT sum(views) FROM news WHERE id IN (?,?)",
        Integer.class,
        draft,
        deleted
      )
    );
  }

  @Test
  void expiredViewerKeysAreCleanedInBoundedBatchesWithoutReducingTotals() {
    UUID old = create(true);
    UUID current = create(true);
    db.update("UPDATE news SET views=600 WHERE id=?", old);
    List<Object[]> expired = new ArrayList<>();
    Timestamp time = Timestamp.from(Instant.now().minus(Duration.ofHours(25)));
    for (int index = 0; index < 501; index++) expired.add(new Object[] {
      old,
      "s:" + "%064x".formatted(index),
      time,
    });
    db.batchUpdate("INSERT INTO news_viewers(news_id,viewer_key,viewed_at) VALUES(?,?,?)", expired);
    new NewsViews(db).record(current, null, "current-browser");
    assertEquals(
      1,
      db.queryForObject("SELECT count(*) FROM news_viewers WHERE news_id=?", Integer.class, old)
    );
    new NewsViews(db).record(current, null, "current-browser");
    assertEquals(
      0,
      db.queryForObject("SELECT count(*) FROM news_viewers WHERE news_id=?", Integer.class, old)
    );
    assertEquals(600, db.queryForObject("SELECT views FROM news WHERE id=?", Integer.class, old));
    assertEquals(1, db.queryForObject("SELECT views FROM news WHERE id=?", Integer.class, current));
  }

  @Test
  @Transactional(propagation = Propagation.NOT_SUPPORTED)
  void parallelRequestsFromOneViewerCountOnce() throws Exception {
    UUID id = create(true);
    UUID account = accounts.find("club.owner").id();
    ExecutorService pool = Executors.newFixedThreadPool(8);
    CountDownLatch start = new CountDownLatch(1);
    try {
      List<Future<NewsViews.Result>> requests = new ArrayList<>();
      for (int index = 0; index < 8; index++) requests.add(
        pool.submit(() -> {
          assertTrue(start.await(10, TimeUnit.SECONDS));
          return views.record(id, account, "unused-account-session");
        })
      );
      start.countDown();
      int counted = 0;
      for (Future<NewsViews.Result> request : requests) {
        NewsViews.Result result = request.get(15, TimeUnit.SECONDS);
        if (result.counted()) counted++;
        assertEquals(1, result.views());
      }
      assertEquals(1, counted);
      assertEquals(1, db.queryForObject("SELECT views FROM news WHERE id=?", Integer.class, id));
      assertEquals(
        1,
        db.queryForObject("SELECT count(*) FROM news_viewers WHERE news_id=?", Integer.class, id)
      );
    } finally {
      pool.shutdownNow();
      pool.awaitTermination(10, TimeUnit.SECONDS);
      db.update("DELETE FROM notifications WHERE news_id=?", id);
      db.update(
        "DELETE FROM audit_log WHERE target_id IN (SELECT id FROM news_revisions WHERE news_id=?)",
        id
      );
      db.update(
        "UPDATE news SET latest_revision_id=NULL,published_revision_id=NULL,main_revision_id=NULL WHERE id=?",
        id
      );
      db.update("DELETE FROM news_revisions WHERE news_id=?", id);
      db.update("DELETE FROM news WHERE id=?", id);
    }
  }
}
