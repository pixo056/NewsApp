package ru.uunit.news;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class FeedReviewIntegrationTest {

  @Autowired
  NewsService news;

  @Autowired
  Communities communities;

  @Autowired
  Accounts accounts;

  @Autowired
  JdbcTemplate db;

  @Autowired
  MockMvc mvc;

  private Accounts.User admin() {
    return accounts.find("uunit.admin");
  }

  private Accounts.User owner() {
    return accounts.find("club.owner");
  }

  private UUID club() {
    return db.queryForObject("SELECT id FROM communities WHERE name='IT-сообщество'", UUID.class);
  }

  private UUID revision(UUID id) {
    return db.queryForObject("SELECT latest_revision_id FROM news WHERE id=?", UUID.class, id);
  }

  private NewsService.Input input(String title, UUID group) {
    return new NewsService.Input(
      title,
      "Текст проверяемой новости достаточной длины для настоящей проверки публикации и поиска.",
      "Технологии",
      group,
      "",
      List.of()
    );
  }

  private boolean mainHas(UUID id) {
    return news
      .feed(null, null, null, null, null, 50, false, false)
      .items()
      .stream()
      .anyMatch(p -> id.equals(p.get("id")));
  }

  @Test
  void creatorApprovalIsPublicInCommunityButNotInMainFeed() throws Exception {
    UUID id = news.submit(owner(), input("Тест двух этапов проверки", club()), null);
    UUID rev = revision(id);
    news.decide(rev, owner(), true, null);
    assertFalse(mainHas(id));
    assertEquals("published", news.detail(id, null, false).get("status"));
    assertEquals("pending", news.detail(id, null, false).get("mainStatus"));
    assertTrue(
      news
        .feed(null, null, null, club(), null, 50, false, false)
        .items()
        .stream()
        .anyMatch(p -> id.equals(p.get("id")))
    );
    assertFalse(
      news
        .queue(owner())
        .stream()
        .anyMatch(p -> id.equals(p.get("id")))
    );
    assertTrue(
      news
        .queue(admin())
        .stream()
        .anyMatch(p -> id.equals(p.get("id")))
    );
    mvc
      .perform(
        post("/api/admin/revisions/" + rev + "/main/approve")
          .with(user("club.owner"))
          .with(csrf())
      )
      .andExpect(status().isForbidden());
    mvc
      .perform(
        post("/api/admin/revisions/" + rev + "/main/approve")
          .with(user("uunit.admin"))
          .with(csrf())
      )
      .andExpect(status().isOk());
    assertTrue(mainHas(id));
    assertThrows(ApiException.class, () -> news.decideMain(rev, admin(), true, null));
  }

  @Test
  void mainRejectionLeavesCommunityPublicationAndReason() {
    UUID id = news.submit(owner(), input("Новость для отдельного паблика", club()), null);
    news.decide(revision(id), owner(), true, null);
    news.decideMain(revision(id), admin(), false, "Материал подходит только для сообщества");
    assertFalse(mainHas(id));
    var post = news.detail(id, null, false);
    assertEquals("published", post.get("status"));
    assertEquals("rejected", post.get("mainStatus"));
    assertNotNull(post.get("mainRejectionReason"));
  }

  @Test
  void editsKeepIndependentApprovedRevisionsUntilEachReview() {
    UUID id = news.submit(owner(), input("Первая одобренная версия", club()), null);
    UUID first = revision(id);
    assertThrows(ApiException.class, () -> news.decideMain(first, admin(), true, null));
    news.decide(first, owner(), true, null);
    news.decideMain(first, admin(), true, null);
    news.submit(owner(), input("Вторая обновлённая версия", club()), id);
    UUID second = revision(id);
    assertEquals(first, news.detail(id, null, false).get("revisionId"));
    assertThrows(ApiException.class, () -> news.detail(id, null, false, second));
    news.decide(second, owner(), true, null);
    assertEquals(second, news.detail(id, null, false).get("revisionId"));
    assertEquals(first, news.detail(id, null, false, first).get("revisionId"));
    assertEquals(
      first,
      news
        .feed(null, null, null, null, null, 50, false, false)
        .items()
        .stream()
        .filter(p -> id.equals(p.get("id")))
        .findFirst()
        .orElseThrow()
        .get("revisionId")
    );
    news.decideMain(second, admin(), false, "Уточните фактическую информацию");
    assertEquals(
      first,
      db.queryForObject("SELECT main_revision_id FROM news WHERE id=?", UUID.class, id)
    );
    assertEquals(
      second,
      db.queryForObject("SELECT published_revision_id FROM news WHERE id=?", UUID.class, id)
    );
  }

  @Test
  void personalAndOfficialNewsStillRequireOnlyPlatformApproval() {
    for (UUID group : Arrays.asList(
      null,
      db.queryForObject("SELECT id FROM communities WHERE official LIMIT 1", UUID.class)
    )) {
      UUID id = news.submit(admin(), input("Официальная новость проверена", group), null);
      assertThrows(ApiException.class, () -> news.decide(revision(id), owner(), true, null));
      news.decide(revision(id), admin(), true, null);
      assertTrue(mainHas(id));
    }
  }

  @Test
  void russianSearchFoldsCaseSpacesAndYoAndPaginatesExactTitlesFirst() {
    UUID exact = news.submit(admin(), input("Ёжик   в кампусе", null), null);
    UUID longer = news.submit(admin(), input("Встреча: ежик в кампусе сегодня", null), null);
    for (UUID id : List.of(exact, longer)) news.decide(revision(id), admin(), true, null);
    var first = news.feed(null, null, "  ЕЖИК\u00a0 В\tКАМПУСЕ  ", null, null, 1, false, false);
    assertEquals(exact, first.items().getFirst().get("id"));
    assertNotNull(first.nextCursor());
    var second = news.feed(null, null, "ежик в кампусе", null, first.nextCursor(), 1, false, false);
    assertEquals(longer, second.items().getFirst().get("id"));
    assertTrue(news.feed(null, null, "%_", null, null, 50, false, false).items().isEmpty());
    for (String q : List.of("ёЖиК в КАМПУСЕ", "ежик   в\nкампусе"))
      assertEquals(2, news.feed(null, null, q, null, null, 50, false, false).items().size());
  }

  @Test
  void communitiesUseSameNormalizationAndExactNamesWin() {
    UUID a = communities.create(admin(), "Клуб Ёжик", "Описание сообщества", "Наука", "purple");
    communities.create(admin(), "Большой клуб ежик", "Описание сообщества", "Наука", "purple");
    var found = communities.list(null, "  КЛУБ\tЕЖИК ");
    assertEquals(2, found.size());
    assertEquals(a, found.getFirst().get("id"));
  }

  @Test
  void recommendationsExcludeFollowedInactiveAndHiddenCommunities() {
    // Isolate candidates without depending on seed catalogue order.
    db.update(
      "INSERT INTO subscriptions(user_id,community_id) SELECT ?,id FROM communities ON CONFLICT DO NOTHING",
      owner().id()
    );
    UUID active = communities.create(
      admin(),
      "Активный новый паблик",
      "Описание",
      "Наука",
      "purple"
    );
    UUID inactive = communities.create(
      admin(),
      "Неактивный новый паблик",
      "Описание",
      "Наука",
      "purple"
    );
    UUID id = news.submit(admin(), input("Свежая новость нового паблика", active), null);
    news.decide(revision(id), admin(), true, null);
    var groups = communities.recommendations(owner());
    assertEquals(
      List.of(active),
      groups
        .stream()
        .map(p -> p.get("id"))
        .toList()
    );
    assertFalse(groups.stream().anyMatch(p -> inactive.equals(p.get("id"))));
    db.update("UPDATE news SET deleted=TRUE WHERE id=?", id);
    assertTrue(communities.recommendations(owner()).isEmpty());
    db.update("UPDATE news SET deleted=FALSE,published_at=now()-interval '31 days' WHERE id=?", id);
    assertTrue(communities.recommendations(owner()).isEmpty());
  }
}
