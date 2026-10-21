package ru.uunit.news;

import static org.junit.jupiter.api.Assertions.*;

import java.sql.Connection;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class NewsQuotaIntegrationTest {

  @Autowired
  JdbcTemplate db;

  @Autowired
  DataSource dataSource;

  @Autowired
  PlatformTransactionManager transactions;

  @Autowired
  Accounts accounts;

  @Autowired
  Communities communities;

  @Autowired
  NewsService news;

  @Test
  void submissionUsesLimitCommittedWhileWaitingForCommunityLock() throws Exception {
    Accounts.User author = accounts.find("uunit.admin");
    UUID community = communities.create(
      author,
      "Проверка квоты " + UUID.randomUUID(),
      "",
      "Технологии",
      "lime"
    );
    NewsService.Input input = new NewsService.Input(
      "Проверка изменения лимита паблика",
      "Проверяем, что ожидающая отправка использует актуальный лимит после решения администратора.",
      "Технологии",
      community,
      "",
      List.of()
    );
    news.submit(author, input, null);
    ExecutorService worker = Executors.newSingleThreadExecutor();
    String application = "quota-test-" + UUID.randomUUID();
    try (Connection editor = dataSource.getConnection()) {
      editor.setAutoCommit(false);
      try {
        try (
          var update = editor.prepareStatement("UPDATE communities SET daily_limit=1 WHERE id=?")
        ) {
          update.setObject(1, community);
          assertEquals(1, update.executeUpdate());
        }
        Future<UUID> submission = worker.submit(() ->
          new TransactionTemplate(transactions).execute(status -> {
            db.queryForObject(
              "SELECT set_config('application_name',?,true)",
              String.class,
              application
            );
            return news.submit(author, input, null);
          })
        );
        awaitCommunityLock(application, submission);
        editor.commit();
        ExecutionException failure = assertThrows(ExecutionException.class, () ->
          submission.get(10, TimeUnit.SECONDS)
        );
        assertInstanceOf(ApiException.class, failure.getCause());
        assertTrue(failure.getCause().getMessage().contains("лимит"));
        assertEquals(
          1,
          db.queryForObject(
            "SELECT count(*) FROM news WHERE community_id=?",
            Integer.class,
            community
          )
        );
      } finally {
        editor.rollback();
      }
    } finally {
      worker.shutdownNow();
      worker.awaitTermination(10, TimeUnit.SECONDS);
      db.update(
        "UPDATE news SET latest_revision_id=NULL,published_revision_id=NULL WHERE community_id=?",
        community
      );
      db.update(
        "DELETE FROM news_revisions WHERE news_id IN (SELECT id FROM news WHERE community_id=?)",
        community
      );
      db.update("DELETE FROM news WHERE community_id=?", community);
      db.update("DELETE FROM communities WHERE id=?", community);
    }
  }

  @Test
  void ownerCannotEditAfterOfficialStatusCommitsWhileWaiting() throws Exception {
    Accounts.User owner = accounts.find("club.owner");
    String original = "Проверка прав " + UUID.randomUUID();
    UUID community = communities.create(owner, original, "", "Технологии", "lime");
    ExecutorService worker = Executors.newSingleThreadExecutor();
    String application = "rights-test-" + UUID.randomUUID();
    try (Connection editor = dataSource.getConnection()) {
      editor.setAutoCommit(false);
      try {
        try (
          var update = editor.prepareStatement("UPDATE communities SET official=TRUE WHERE id=?")
        ) {
          update.setObject(1, community);
          assertEquals(1, update.executeUpdate());
        }
        Future<?> change = worker.submit(() ->
          new TransactionTemplate(transactions).execute(status -> {
            db.queryForObject(
              "SELECT set_config('application_name',?,true)",
              String.class,
              application
            );
            communities.update(community, owner, "Недопустимая правка владельца", "");
            return null;
          })
        );
        awaitCommunityLock(application, change);
        editor.commit();
        ExecutionException failure = assertThrows(ExecutionException.class, () ->
          change.get(10, TimeUnit.SECONDS)
        );
        ApiException denied = assertInstanceOf(ApiException.class, failure.getCause());
        assertEquals(org.springframework.http.HttpStatus.FORBIDDEN, denied.status);
        assertEquals(
          original,
          db.queryForObject("SELECT name FROM communities WHERE id=?", String.class, community)
        );
        assertTrue(
          db.queryForObject("SELECT official FROM communities WHERE id=?", Boolean.class, community)
        );
      } finally {
        editor.rollback();
      }
    } finally {
      worker.shutdownNow();
      worker.awaitTermination(10, TimeUnit.SECONDS);
      db.update("DELETE FROM communities WHERE id=?", community);
    }
  }

  private void awaitCommunityLock(String application, Future<?> request)
    throws InterruptedException {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
    boolean waiting = false;
    while (System.nanoTime() < deadline) {
      waiting = db.queryForObject(
        "SELECT count(*)>0 FROM pg_stat_activity WHERE application_name=? AND wait_event_type='Lock'",
        Boolean.class,
        application
      );
      if (waiting || request.isDone()) break;
      Thread.sleep(20);
    }
    assertTrue(
      waiting,
      "The request must reach the blocked community lock before the admin commits"
    );
  }
}
