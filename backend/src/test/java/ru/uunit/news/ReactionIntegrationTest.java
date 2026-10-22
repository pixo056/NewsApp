package ru.uunit.news;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class ReactionIntegrationTest {

  @Autowired
  MockMvc mvc;

  @Autowired
  JdbcTemplate db;

  @Autowired
  Accounts accounts;

  @Autowired
  NewsService news;

  @Autowired
  ReactionService reactions;

  @Autowired
  PlatformTransactionManager transactions;

  private UUID create(boolean publish) {
    Accounts.User admin = accounts.find("uunit.admin");
    UUID id = news.submit(
      admin,
      new NewsService.Input(
        "Новость для проверки реакций",
        "Проверяем выбор реакции, замену эмоции и сохранение только одного голоса от каждого участника.",
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
  void guestSeesCountsWithoutAnyAccountSelectionAndOldRowsDefaultToLike() throws Exception {
    UUID id = create(true);
    db.update(
      "INSERT INTO reactions(user_id,news_id) VALUES(?,?)",
      accounts.find("club.owner").id(),
      id
    );
    reactions.set(id, accounts.find("uunit.admin"), "celebrate");
    mvc
      .perform(get("/api/news/" + id + "/reactions"))
      .andExpect(status().isOk())
      .andExpect(jsonPath("counts.like").value(1))
      .andExpect(jsonPath("counts.celebrate").value(1))
      .andExpect(jsonPath("counts.insightful").value(0))
      .andExpect(jsonPath("counts.support").value(0))
      .andExpect(jsonPath("total").value(2))
      .andExpect(jsonPath("selected").isEmpty());
    mvc
      .perform(get("/api/news/" + id + "/reactions").with(user("club.owner")))
      .andExpect(status().isOk())
      .andExpect(jsonPath("selected").value("like"));
    mvc
      .perform(get("/api/news/" + id + "/reactions").with(user("uunit.admin")))
      .andExpect(status().isOk())
      .andExpect(jsonPath("selected").value("celebrate"));
    mvc
      .perform(get("/api/news/" + id))
      .andExpect(status().isOk())
      .andExpect(jsonPath("reactionCounts.like").value(1))
      .andExpect(jsonPath("reactionCounts.celebrate").value(1))
      .andExpect(jsonPath("reactions").value(2))
      .andExpect(jsonPath("myReaction").isEmpty())
      .andExpect(jsonPath("liked").value(false));
    mvc
      .perform(
        get("/api/news").param("q", "Новость для проверки реакций").with(user("uunit.admin"))
      )
      .andExpect(status().isOk())
      .andExpect(jsonPath("items[0].id").value(id.toString()))
      .andExpect(jsonPath("items[0].reactionCounts.like").value(1))
      .andExpect(jsonPath("items[0].reactionCounts.celebrate").value(1))
      .andExpect(jsonPath("items[0].reactions").value(2))
      .andExpect(jsonPath("items[0].myReaction").value("celebrate"))
      .andExpect(jsonPath("items[0].liked").value(false));
  }

  @Test
  void changingReactionReplacesOneVoteAndRemovalIsIdempotent() throws Exception {
    UUID id = create(true);
    for (String kind : ReactionService.KINDS) {
      mvc
        .perform(
          put("/api/news/" + id + "/reaction")
            .with(user("club.owner"))
            .with(csrf())
            .contentType("application/json")
            .content("{\"kind\":\"" + kind + "\"}")
        )
        .andExpect(status().isOk())
        .andExpect(jsonPath("selected").value(kind))
        .andExpect(jsonPath("counts." + kind).value(1))
        .andExpect(jsonPath("total").value(1));
    }
    assertEquals(
      1,
      db.queryForObject("SELECT count(*) FROM reactions WHERE news_id=?", Integer.class, id)
    );
    for (int attempt = 0; attempt < 2; attempt++) {
      mvc
        .perform(
          delete("/api/news/" + id + "/reaction")
            .with(user("club.owner"))
            .with(csrf())
        )
        .andExpect(status().isOk())
        .andExpect(jsonPath("selected").isEmpty())
        .andExpect(jsonPath("total").value(0));
    }
  }

  @Test
  void oldPutWithoutBodyStillCreatesALike() throws Exception {
    UUID id = create(true);
    reactions.set(id, accounts.find("club.owner"), "insightful");
    mvc
      .perform(
        put("/api/news/" + id + "/reaction")
          .with(user("club.owner"))
          .with(csrf())
      )
      .andExpect(status().isOk())
      .andExpect(jsonPath("selected").value("like"))
      .andExpect(jsonPath("counts.like").value(1))
      .andExpect(jsonPath("counts.insightful").value(0))
      .andExpect(jsonPath("total").value(1));
  }

  @Test
  void invalidSelectionsCannotReplaceAnExistingReaction() throws Exception {
    UUID id = create(true);
    reactions.set(id, accounts.find("club.owner"), "support");
    for (String input : List.of(
      "{\"kind\":\"arbitrary\"}",
      "{}",
      "{\"kind\":null}",
      "{\"kind\":\"LIKE\"}"
    )) {
      mvc
        .perform(
          put("/api/news/" + id + "/reaction")
            .with(user("club.owner"))
            .with(csrf())
            .contentType("application/json")
            .content(input)
        )
        .andExpect(status().isBadRequest());
    }
    assertEquals("support", reactions.summary(id, accounts.find("club.owner")).selected());
  }

  @Test
  void guestMissingCsrfAndBlockedAccountCannotChangeReactions() throws Exception {
    UUID id = create(true);
    mvc
      .perform(put("/api/news/" + id + "/reaction").with(csrf()))
      .andExpect(status().isUnauthorized());
    mvc
      .perform(put("/api/news/" + id + "/reaction").with(user("club.owner")))
      .andExpect(status().isForbidden());
    db.update("UPDATE app_users SET blocked=TRUE WHERE username='club.owner'");
    mvc
      .perform(
        put("/api/news/" + id + "/reaction")
          .with(user("club.owner"))
          .with(csrf())
      )
      .andExpect(status().isUnauthorized());
    assertEquals(0, reactions.summary(id, null).total());
  }

  @Test
  void pendingHiddenAndMissingNewsHaveNoPublicReactionAccess() throws Exception {
    UUID pending = create(false);
    UUID hidden = create(true);
    news.delete(hidden, accounts.find("uunit.admin"), true);
    for (UUID id : List.of(pending, hidden, UUID.randomUUID())) {
      mvc.perform(get("/api/news/" + id + "/reactions")).andExpect(status().isNotFound());
      mvc
        .perform(get("/api/news/" + id + "/reactions").with(user("uunit.admin")))
        .andExpect(status().isNotFound());
      mvc
        .perform(
          put("/api/news/" + id + "/reaction")
            .with(user("uunit.admin"))
            .with(csrf())
        )
        .andExpect(status().isNotFound());
      mvc
        .perform(
          delete("/api/news/" + id + "/reaction")
            .with(user("uunit.admin"))
            .with(csrf())
        )
        .andExpect(status().isNotFound());
    }
  }

  @Test
  @Transactional(propagation = Propagation.NOT_SUPPORTED)
  void reactingAndAuthorEditingDoNotDeadlockWhileNewsIsLocked() throws Exception {
    assertEditingAndReactionCanComplete(false);
  }

  @Test
  @Transactional(propagation = Propagation.NOT_SUPPORTED)
  void reactingAndEditorialEditingDoNotDeadlockWhileNewsIsLocked() throws Exception {
    assertEditingAndReactionCanComplete(true);
  }

  private void assertEditingAndReactionCanComplete(boolean editorial) throws Exception {
    UUID id = create(true);
    Accounts.User author = accounts.find("uunit.admin");
    NewsService.Input input = new NewsService.Input(
      "Одновременная реакция и правка новости",
      "Проверяем, что редактор может завершить правку, пока тот же пользователь ставит реакцию.",
      "Технологии",
      null,
      "",
      List.of()
    );
    ExecutorService worker = Executors.newSingleThreadExecutor();
    CompletableFuture<Integer> backendPid = new CompletableFuture<>();
    List<Future<?>> edit = new ArrayList<>();
    try {
      new TransactionTemplate(transactions).executeWithoutResult(status -> {
        db.execute("SET LOCAL lock_timeout='5s'");
        int mainPid = db.queryForObject("SELECT pg_backend_pid()", Integer.class);
        db.queryForObject("SELECT id FROM news WHERE id=? FOR UPDATE", UUID.class, id);
        edit.add(
          worker.submit(() ->
            new TransactionTemplate(transactions).executeWithoutResult(change -> {
              db.execute("SET LOCAL lock_timeout='15s'");
              backendPid.complete(db.queryForObject("SELECT pg_backend_pid()", Integer.class));
              if (editorial) news.editorialEdit(id, author, input);
              else news.submit(author, input, id);
            })
          )
        );
        try {
          int editorPid = backendPid.get(5, TimeUnit.SECONDS);
          long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
          boolean waiting = false;
          while (System.nanoTime() < deadline && !edit.getFirst().isDone()) {
            db.execute("SELECT pg_stat_clear_snapshot()");
            waiting = db.queryForObject(
              "SELECT count(*)>0 FROM pg_stat_activity WHERE pid=? AND wait_event_type='Lock' " +
                "AND ?=ANY(pg_blocking_pids(pid))",
              Boolean.class,
              editorPid,
              mainPid
            );
            if (waiting) break;
            Thread.sleep(20);
          }
          assertTrue(
            waiting,
            "The editor must hold its quota lock and wait for this transaction's news lock"
          );
          ReactionService.Summary result = reactions.set(id, author, "support");
          assertEquals("support", result.selected());
          assertEquals(1, result.total());
        } catch (InterruptedException exception) {
          Thread.currentThread().interrupt();
          throw new AssertionError("Interrupted while waiting for the editor", exception);
        } catch (ExecutionException | TimeoutException exception) {
          throw new AssertionError("The editor did not acquire its connection", exception);
        }
      });
      edit.getFirst().get(10, TimeUnit.SECONDS);
      assertEquals("support", reactions.summary(id, author).selected());
      assertEquals(
        "pending",
        db.queryForObject(
          "SELECT r.status FROM news n JOIN news_revisions r ON r.id=n.latest_revision_id WHERE n.id=?",
          String.class,
          id
        )
      );
    } finally {
      worker.shutdownNow();
      worker.awaitTermination(20, TimeUnit.SECONDS);
      cleanup(id);
    }
  }

  @Test
  @Transactional(propagation = Propagation.NOT_SUPPORTED)
  void simultaneousSelectionsKeepOnlyOneReactionPerAccount() throws Exception {
    UUID id = create(true);
    Accounts.User user = accounts.find("club.owner");
    ExecutorService pool = Executors.newFixedThreadPool(8);
    CountDownLatch start = new CountDownLatch(1);
    try {
      List<Future<ReactionService.Summary>> requests = new ArrayList<>();
      for (int index = 0; index < 8; index++) {
        String kind = ReactionService.KINDS.get(index % ReactionService.KINDS.size());
        requests.add(
          pool.submit(() -> {
            assertTrue(start.await(10, TimeUnit.SECONDS));
            return reactions.set(id, user, kind);
          })
        );
      }
      start.countDown();
      for (Future<ReactionService.Summary> request : requests) {
        ReactionService.Summary result = request.get(15, TimeUnit.SECONDS);
        assertEquals(1, result.total());
        assertEquals(1L, result.counts().get(result.selected()));
      }
      assertEquals(
        1,
        db.queryForObject("SELECT count(*) FROM reactions WHERE news_id=?", Integer.class, id)
      );
      assertEquals(1, reactions.summary(id, user).total());
    } finally {
      pool.shutdownNow();
      pool.awaitTermination(10, TimeUnit.SECONDS);
      cleanup(id);
    }
  }

  private void cleanup(UUID id) {
    db.update("DELETE FROM reactions WHERE news_id=?", id);
    db.update("DELETE FROM notifications WHERE news_id=?", id);
    db.update(
      "DELETE FROM audit_log WHERE target_id=? OR target_id IN (SELECT id FROM news_revisions WHERE news_id=?)",
      id,
      id
    );
    db.update("UPDATE news SET latest_revision_id=NULL,published_revision_id=NULL,main_revision_id=NULL WHERE id=?", id);
    db.update("DELETE FROM news_revisions WHERE news_id=?", id);
    db.update("DELETE FROM news WHERE id=?", id);
  }
}
