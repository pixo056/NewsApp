package ru.uunit.news;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Connection;
import java.util.*;
import java.util.concurrent.*;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.UserRequestPostProcessor;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class CmsIntegrationTest {

  @Autowired
  MockMvc mvc;

  @Autowired
  JdbcTemplate db;

  @Autowired
  Accounts accounts;

  @Autowired
  Communities communities;

  @Autowired
  NewsService news;

  @Autowired
  CmsService cms;

  @Autowired
  ObjectMapper mapper;

  @Autowired
  DataSource dataSource;

  @Autowired
  org.springframework.transaction.PlatformTransactionManager transactions;

  private final String body =
    "Проверяем редакторскую работу с новостями, права сообщества, вложения и сохранность опубликованной версии.";

  private Accounts.User admin() {
    return accounts.find("uunit.admin");
  }

  private Accounts.User owner() {
    return accounts.find("club.owner");
  }

  private UUID club() {
    return db.queryForObject("SELECT id FROM communities WHERE name='IT-сообщество'", UUID.class);
  }

  private UUID official() {
    return db.queryForObject("SELECT id FROM communities WHERE official LIMIT 1", UUID.class);
  }

  private UserRequestPostProcessor as(Accounts.User user) {
    return user(user.username());
  }

  private Accounts.User outsider() {
    String login = "cms." + UUID.randomUUID().toString().substring(0, 8);
    Accounts.User user = accounts.register(
      login,
      login + "@test.local",
      "Автор CMS-теста",
      "StrongPass!123"
    );
    db.update("UPDATE app_users SET verified=TRUE WHERE id=?", user.id());
    return accounts.find(login);
  }

  private UUID asset(Accounts.User user) {
    UUID id = UUID.randomUUID();
    db.update(
      "INSERT INTO media_assets(id,owner_id,kind,filename,width,height,size_bytes) VALUES(?,?,'image',?,1,1,1)",
      id,
      user.id(),
      id + ".jpg"
    );
    return id;
  }

  private NewsService.Input input(String title, UUID community, List<UUID> media) {
    return new NewsService.Input(title, body, "Технологии", community, "https://uust.ru/", media);
  }

  private UUID create(
    Accounts.User author,
    UUID community,
    String title,
    boolean published,
    List<UUID> media
  ) {
    UUID id = news.submit(author, input(title, community, media), null);
    if (published) news.decide(latest(id), admin(), true, null);
    return id;
  }

  private UUID latest(UUID id) {
    return db.queryForObject("SELECT latest_revision_id FROM news WHERE id=?", UUID.class, id);
  }

  private JsonNode list(String resource, Accounts.User user, String q, int page, int size)
    throws Exception {
    return mapper.readTree(
      mvc
        .perform(
          get("/api/cms/" + resource)
            .with(as(user))
            .param("q", q)
            .param("page", String.valueOf(page))
            .param("perPage", String.valueOf(size))
            .param("sort", "id")
            .param("order", "ASC")
        )
        .andExpect(status().isOk())
        .andReturn()
        .getResponse()
        .getContentAsString()
    );
  }

  @Test
  void ownersReceiveOnlyTheirOrdinaryResourcesBeforePagination() throws Exception {
    String marker = "scope" + UUID.randomUUID().toString().substring(0, 8);
    Set<String> own = new HashSet<>();
    for (int index = 0; index < 3; index++) own.add(
      create(
        index == 0 ? outsider() : owner(),
        club(),
        marker + " Новости своего паблика " + index,
        true,
        List.of()
      ).toString()
    );
    for (int index = 0; index < 4; index++) create(
      admin(),
      official(),
      marker + " Закрытая официальная заявка " + index,
      false,
      List.of()
    );
    JsonNode first = list("news", owner(), marker, 1, 2);
    JsonNode second = list("news", owner(), marker, 2, 2);
    assertEquals(3, first.path("total").asInt());
    assertEquals(2, first.path("items").size());
    assertEquals(1, second.path("items").size());
    Set<String> seen = new HashSet<>();
    first.path("items").forEach(item -> seen.add(item.path("id").asText()));
    second.path("items").forEach(item -> seen.add(item.path("id").asText()));
    assertEquals(own, seen);
    assertEquals(7, list("news", admin(), marker, 1, 100).path("total").asInt());
    JsonNode groups = list("communities", owner(), "", 1, 100);
    for (JsonNode group : groups.path("items")) {
      assertEquals(owner().id().toString(), group.path("ownerId").asText());
      assertFalse(group.path("official").asBoolean());
      assertEquals(group.path("official"), group.path("verified"));
      assertFalse(group.has("email"));
    }
  }

  @Test
  void ownersEditAndApproveTheirNewsButCannotRestoreAdministrativeHiding() throws Exception {
    UUID id = create(
      owner(),
      club(),
      "Собственное предложение владельца паблика",
      false,
      List.of()
    );
    mvc
      .perform(get("/api/cms/news/" + id).with(as(owner())))
      .andExpect(status().isOk())
      .andExpect(jsonPath("canEdit").value(true))
      .andExpect(jsonPath("canModerate").value(true));
    mvc
      .perform(
        patch("/api/cms/news/" + id)
          .with(as(owner()))
          .with(csrf())
          .contentType("application/json")
          .content(
            mapper.writeValueAsString(
              input("Исправленное предложение владельца", club(), List.of())
            )
          )
      )
      .andExpect(status().isOk())
      .andExpect(jsonPath("status").value("pending"));
    mvc
      .perform(
        post("/api/admin/revisions/" + latest(id) + "/approve")
          .with(as(owner()))
          .with(csrf())
      )
      .andExpect(status().isOk());
    mvc.perform(get("/api/news/" + id)).andExpect(status().isOk());
    cms.editNews(id, owner(), input("Правки владельца после одобрения", club(), List.of()));
    mvc
      .perform(get("/api/news/" + id))
      .andExpect(jsonPath("title").value("Исправленное предложение владельца"));
    cms.visibility(id, admin(), true);
    mvc.perform(get("/api/cms/news/" + id).with(as(owner()))).andExpect(status().isNotFound());
    mvc
      .perform(
        post("/api/cms/news/" + id + "/visibility")
          .with(as(owner()))
          .with(csrf())
          .contentType("application/json")
          .content("{\"hidden\":false}")
      )
      .andExpect(status().isForbidden());
    mvc.perform(get("/api/news/" + id)).andExpect(status().isNotFound());
  }

  @Test
  void ownersCanReviewButCannotRewriteAnotherAuthorsPendingNews() throws Exception {
    UUID id = create(outsider(), club(), "Чужая заявка внутри паблика владельца", false, List.of());
    mvc
      .perform(get("/api/cms/news/" + id).with(as(owner())))
      .andExpect(status().isOk())
      .andExpect(jsonPath("canModerate").value(true))
      .andExpect(jsonPath("canEdit").value(false));
    mvc.perform(get("/api/me/news/" + id).with(as(owner()))).andExpect(status().isOk());
    mvc.perform(get("/api/news/" + id).with(as(owner()))).andExpect(status().isOk());
    mvc
      .perform(
        patch("/api/cms/news/" + id)
          .with(as(owner()))
          .with(csrf())
          .contentType("application/json")
          .content(
            mapper.writeValueAsString(
              input("Попытка изменить чужое предложение", club(), List.of())
            )
          )
      )
      .andExpect(status().isForbidden());
    assertEquals(
      "pending",
      db.queryForObject("SELECT status FROM news_revisions WHERE id=?", String.class, latest(id))
    );
  }

  @Test
  void ownerRejectsOtherAuthorsRevisionsAndHidesOnlyTheirCommunityNews() throws Exception {
    Accounts.User author = outsider();
    UUID id = create(author, club(), "Заявка другого автора для владельца", false, List.of());
    news.decide(latest(id), owner(), false, "Дополните проверенный источник");
    assertEquals("rejected", cms.detail("news", id, owner()).get("status"));
    mvc.perform(get("/api/news/" + id)).andExpect(status().isNotFound());
    news.submit(author, input("Исправленная заявка другого автора", club(), List.of()), id);
    news.decide(latest(id), owner(), true, null);
    news.submit(author, input("Новая версия ожидает модерации", club(), List.of()), id);
    news.decide(latest(id), owner(), false, "Проверьте факты перед публикацией");
    mvc
      .perform(get("/api/news/" + id))
      .andExpect(jsonPath("title").value("Исправленная заявка другого автора"));
    cms.visibility(id, owner(), true);
    mvc.perform(get("/api/news/" + id)).andExpect(status().isNotFound());
    assertThrows(ApiException.class, () -> cms.visibility(id, owner(), false));
    cms.visibility(id, admin(), false);
    mvc.perform(get("/api/news/" + id)).andExpect(status().isOk());
  }

  @Test
  void personalOfficialAndUnrelatedCommunitiesStayOutOfOwnerScope() throws Exception {
    Accounts.User outsider = outsider();
    UUID other = communities.create(outsider, "Чужой паблик CMS", "", "Наука", "mint");
    for (UUID community : Arrays.asList(null, official(), other)) {
      UUID id = create(admin(), community, "Недоступная владельцу новость", true, List.of());
      mvc.perform(get("/api/cms/news/" + id).with(as(owner()))).andExpect(status().isNotFound());
      mvc
        .perform(
          patch("/api/cms/news/" + id)
            .with(as(owner()))
            .with(csrf())
            .contentType("application/json")
            .content(
              mapper.writeValueAsString(
                input("Недопустимая правка владельца", community, List.of())
              )
            )
        )
        .andExpect(status().isForbidden());
      mvc
        .perform(
          post("/api/cms/news/" + id + "/visibility")
            .with(as(owner()))
            .with(csrf())
            .contentType("application/json")
            .content("{\"hidden\":true}")
        )
        .andExpect(status().isForbidden());
      mvc.perform(get("/api/cms/news/" + id).with(as(admin()))).andExpect(status().isOk());
    }
    mvc.perform(get("/api/cms/summary")).andExpect(status().isUnauthorized());
    Accounts.User reader = outsider();
    mvc.perform(get("/api/cms/news").with(as(reader))).andExpect(status().isForbidden());
  }

  @Test
  void editorialRevisionPreservesAuthorMediaAndPublicVersion() throws Exception {
    Accounts.User author = outsider();
    UUID photo = asset(author);
    UUID id = create(author, club(), "Первоначальная опубликованная новость", true, List.of(photo));
    UUID published = latest(id);
    NewsService.Input edited = input("Исправленная редактором новость", club(), List.of(photo));
    JsonNode response = mapper.readTree(
      mvc
        .perform(
          patch("/api/cms/news/" + id)
            .with(as(admin()))
            .with(csrf())
            .contentType("application/json")
            .content(mapper.writeValueAsString(edited))
        )
        .andExpect(status().isOk())
        .andExpect(jsonPath("status").value("pending"))
        .andExpect(jsonPath("authorId").value(author.id().toString()))
        .andExpect(jsonPath("mediaIds[0]").value(photo.toString()))
        .andReturn()
        .getResponse()
        .getContentAsString()
    );
    assertNotEquals(published.toString(), response.path("revisionId").asText());
    assertEquals(
      published,
      db.queryForObject("SELECT published_revision_id FROM news WHERE id=?", UUID.class, id)
    );
    mvc
      .perform(get("/api/news/" + id))
      .andExpect(jsonPath("title").value("Первоначальная опубликованная новость"));
    mvc
      .perform(
        patch("/api/cms/news/" + id)
          .with(as(admin()))
          .with(csrf())
          .contentType("application/json")
          .content(mapper.writeValueAsString(input("Повторная редакторская правка", club(), null)))
      )
      .andExpect(status().isOk())
      .andExpect(jsonPath("mediaIds[0]").value(photo.toString()));
    assertEquals(
      "withdrawn",
      db.queryForObject(
        "SELECT status FROM news_revisions WHERE id=?",
        String.class,
        UUID.fromString(response.path("revisionId").asText())
      )
    );
    news.decide(latest(id), admin(), true, null);
    mvc
      .perform(get("/api/news/" + id))
      .andExpect(jsonPath("title").value("Повторная редакторская правка"));
    assertEquals(
      2,
      db.queryForObject(
        "SELECT count(*) FROM audit_log WHERE target_id=? AND action='cms-edit'",
        Integer.class,
        id
      )
    );
  }

  @Test
  void editorialUploadsRemainEditableByTheOriginalAuthor() {
    Accounts.User author = outsider();
    UUID id = create(author, club(), "Новость автора до редакторской правки", true, List.of());
    UUID editorialPhoto = asset(admin());
    cms.editNews(
      id,
      admin(),
      input("Редактор добавил свою фотографию", club(), List.of(editorialPhoto))
    );
    news.submit(
      author,
      input("Автор продолжает работу с новостью", club(), List.of(editorialPhoto)),
      id
    );
    assertEquals(
      editorialPhoto,
      db.queryForObject(
        "SELECT media_id FROM revision_media WHERE revision_id=?",
        UUID.class,
        latest(id)
      )
    );
    assertEquals(
      author.id(),
      db.queryForObject("SELECT author_id FROM news WHERE id=?", UUID.class, id)
    );
  }

  @Test
  void editorCannotStealUnrelatedAttachmentsOrReassignCommunityAuthor() throws Exception {
    Accounts.User author = outsider();
    UUID id = create(author, club(), "Новость для проверки редакторских границ", true, List.of());
    UUID old = latest(id);
    UUID otherPhoto = asset(author);
    mvc
      .perform(
        patch("/api/cms/news/" + id)
          .with(as(admin()))
          .with(csrf())
          .contentType("application/json")
          .content(
            mapper.writeValueAsString(
              input("Попытка присвоить чужое вложение", club(), List.of(otherPhoto))
            )
          )
      )
      .andExpect(status().isBadRequest());
    mvc
      .perform(
        patch("/api/cms/news/" + id)
          .with(as(admin()))
          .with(csrf())
          .contentType("application/json")
          .content(
            mapper.writeValueAsString(
              input("Попытка переместить новость в паблик", official(), List.of())
            )
          )
      )
      .andExpect(status().isBadRequest());
    assertEquals(old, latest(id));
    Map<String, Object> payload = mapper.convertValue(
      input("Попытка переименования автора новости", club(), List.of()),
      Map.class
    );
    payload.put("authorId", owner().id());
    payload.put("ownerId", owner().id());
    mvc
      .perform(
        patch("/api/cms/news/" + id)
          .with(as(admin()))
          .with(csrf())
          .contentType("application/json")
          .content(mapper.writeValueAsString(payload))
      )
      .andExpect(status().isOk())
      .andExpect(jsonPath("authorId").value(author.id().toString()));
  }

  @Test
  void hiddenNewsCanBeRestoredAndActionsAreAudited() throws Exception {
    UUID id = create(admin(), club(), "Новость для скрытия и восстановления", true, List.of());
    for (boolean hidden : List.of(true, false)) {
      mvc
        .perform(
          post("/api/cms/news/" + id + "/visibility")
            .with(as(admin()))
            .with(csrf())
            .contentType("application/json")
            .content("{\"hidden\":" + hidden + "}")
        )
        .andExpect(status().isOk())
        .andExpect(jsonPath("deleted").value(hidden));
      mvc
        .perform(get("/api/news/" + id))
        .andExpect(hidden ? status().isNotFound() : status().isOk());
      if (hidden) {
        mvc
          .perform(
            get("/api/cms/news")
              .with(as(admin()))
              .param("status", "hidden")
              .param("q", "Новость для скрытия")
          )
          .andExpect(jsonPath("total").value(1));
        mvc
          .perform(
            get("/api/cms/news")
              .with(as(admin()))
              .param("status", "published")
              .param("q", "Новость для скрытия")
          )
          .andExpect(jsonPath("total").value(0));
      }
    }
    assertEquals(
      2,
      db.queryForObject(
        "SELECT count(*) FROM audit_log WHERE target_id=? AND action IN ('cms-hide','cms-restore')",
        Integer.class,
        id
      )
    );
    mvc.perform(get("/api/cms/audit").with(as(owner()))).andExpect(status().isForbidden());
    JsonNode audit = list("audit", admin(), "cms-restore", 1, 100);
    assertTrue(audit.path("total").asInt() > 0);
    UUID auditId = UUID.fromString(audit.path("items").get(0).path("id").asText());
    mvc
      .perform(get("/api/cms/audit/" + auditId).with(as(admin())))
      .andExpect(status().isOk())
      .andExpect(jsonPath("action").value("cms-restore"));
  }

  @Test
  void communitySettingsCannotEscalateOwnersAndOfficialStatusRevokesScope() throws Exception {
    UUID group = club();
    int limit = db.queryForObject(
      "SELECT daily_limit FROM communities WHERE id=?",
      Integer.class,
      group
    );
    mvc
      .perform(
        patch("/api/cms/communities/" + group)
          .with(as(owner()))
          .with(csrf())
          .contentType("application/json")
          .content(
            "{\"name\":\"Переименованный паблик\",\"description\":\"Описание\",\"dailyLimit\":" +
              limit +
              ",\"official\":false}"
          )
      )
      .andExpect(status().isOk())
      .andExpect(jsonPath("name").value("Переименованный паблик"));
    for (String payload : List.of("{\"dailyLimit\":1000}", "{\"official\":true}")) {
      mvc
        .perform(
          patch("/api/cms/communities/" + group)
            .with(as(owner()))
            .with(csrf())
            .contentType("application/json")
            .content(payload)
        )
        .andExpect(status().isForbidden());
    }
    mvc
      .perform(
        patch("/api/cms/communities/" + group)
          .with(as(admin()))
          .with(csrf())
          .contentType("application/json")
          .content("{\"dailyLimit\":30,\"official\":true}")
      )
      .andExpect(status().isOk())
      .andExpect(jsonPath("official").value(true))
      .andExpect(jsonPath("dailyLimit").value(30));
    mvc
      .perform(get("/api/cms/communities/" + group).with(as(owner())))
      .andExpect(status().isNotFound());
    mvc
      .perform(
        patch("/api/cms/communities/" + group)
          .with(as(owner()))
          .with(csrf())
          .contentType("application/json")
          .content("{\"description\":\"Нельзя\"}")
      )
      .andExpect(status().isForbidden());
  }

  @Test
  void reportsAreScopedResolvedAndRecordedInAudit() throws Exception {
    Accounts.User reporter = outsider();
    UUID ownNews = create(admin(), club(), "Жалоба на новость обычного паблика", true, List.of());
    UUID officialNews = create(
      admin(),
      official(),
      "Жалоба на официальную новость",
      true,
      List.of()
    );
    UUID ownReport = UUID.randomUUID(),
      officialReport = UUID.randomUUID();
    for (UUID[] pair : List.of(
      new UUID[] { ownReport, ownNews },
      new UUID[] { officialReport, officialNews }
    )) {
      db.update(
        "INSERT INTO reports(id,news_id,reporter_id,reason,comment) VALUES(?,?,?,'CMS report marker','Проверьте материал')",
        pair[0],
        pair[1],
        reporter.id()
      );
    }
    JsonNode visible = list("reports", admin(), "CMS report marker", 1, 1);
    assertEquals(2, visible.path("total").asInt());
    assertEquals(1, list("reports", owner(), "CMS report marker", 1, 100).path("total").asInt());
    mvc
      .perform(get("/api/cms/reports/" + officialReport).with(as(owner())))
      .andExpect(status().isNotFound());
    mvc
      .perform(
        patch("/api/cms/reports/" + officialReport)
          .with(as(owner()))
          .with(csrf())
          .contentType("application/json")
          .content("{\"resolution\":\"Рассмотрено\"}")
      )
      .andExpect(status().isForbidden());
    mvc
      .perform(
        patch("/api/cms/reports/" + ownReport)
          .with(as(owner()))
          .with(csrf())
          .contentType("application/json")
          .content("{\"resolution\":\"Материал проверен, нарушения не обнаружены\"}")
      )
      .andExpect(status().isOk())
      .andExpect(jsonPath("status").value("resolved"));
    mvc
      .perform(
        get("/api/cms/reports")
          .with(as(admin()))
          .param("q", "CMS report marker")
          .param("status", "resolved")
      )
      .andExpect(status().isOk())
      .andExpect(jsonPath("total").value(1));
    assertEquals(
      1,
      db.queryForObject(
        "SELECT count(*) FROM audit_log WHERE target_id=? AND action='cms-report-resolve'",
        Integer.class,
        ownReport
      )
    );
  }

  @Test
  void queryBodyAndCsrfValidationRejectInvalidCmsChanges() throws Exception {
    for (String[] query : List.of(
      new String[] { "page", "0" },
      new String[] { "perPage", "101" },
      new String[] { "q", "x".repeat(201) },
      new String[] { "sort", "id; DROP TABLE news" },
      new String[] { "order", "SIDEWAYS" },
      new String[] { "status", "unknown" }
    )) {
      mvc
        .perform(get("/api/cms/news").with(as(admin())).param(query[0], query[1]))
        .andExpect(status().isBadRequest());
    }
    UUID id = create(admin(), club(), "Новость для валидации полей CMS", true, List.of());
    for (NewsService.Input input : List.of(
      input("Коротко", club(), List.of()),
      new NewsService.Input(
        "Недопустимый текст редакторской правки",
        "мало",
        "Технологии",
        club(),
        "",
        List.of()
      ),
      new NewsService.Input(
        "Недопустимый источник редакторской правки",
        body,
        "Технологии",
        club(),
        "javascript:alert(1)",
        List.of()
      )
    )) {
      mvc
        .perform(
          patch("/api/cms/news/" + id)
            .with(as(admin()))
            .with(csrf())
            .contentType("application/json")
            .content(mapper.writeValueAsString(input))
        )
        .andExpect(status().isBadRequest());
    }
    mvc
      .perform(
        post("/api/cms/news/" + id + "/visibility")
          .with(as(admin()))
          .contentType("application/json")
          .content("{\"hidden\":true}")
      )
      .andExpect(status().isForbidden());
    mvc
      .perform(
        post("/api/cms/news/" + id + "/visibility")
          .with(as(admin()))
          .with(csrf())
          .contentType("application/json")
          .content("{}")
      )
      .andExpect(status().isBadRequest());
    mvc
      .perform(
        patch("/api/cms/communities/" + club())
          .with(as(admin()))
          .with(csrf())
          .contentType("application/json")
          .content("{\"dailyLimit\":1001}")
      )
      .andExpect(status().isBadRequest());
  }

  @Test
  void summaryCountsMatchScopedListsAndPendingReports() throws Exception {
    UUID pendingNews = create(
      owner(),
      club(),
      "Новая CMS-заявка без даты публикации",
      false,
      List.of()
    );
    mvc
      .perform(get("/api/cms/news/" + pendingNews).with(as(owner())))
      .andExpect(status().isOk())
      .andExpect(jsonPath("publishedAt").doesNotExist());
    JsonNode summary = mapper.readTree(
      mvc
        .perform(get("/api/cms/summary").with(as(owner())))
        .andExpect(status().isOk())
        .andReturn()
        .getResponse()
        .getContentAsString()
    );
    assertEquals(
      list("news", owner(), "", 1, 100).path("total").asLong(),
      summary.path("news").asLong()
    );
    assertEquals(
      list("communities", owner(), "", 1, 100).path("total").asLong(),
      summary.path("communities").asLong()
    );
    long pending = db.queryForObject(
      "SELECT count(*) FROM news n JOIN news_revisions r ON r.id=n.latest_revision_id JOIN communities c ON c.id=n.community_id WHERE c.owner_id=? AND NOT c.official AND NOT n.deleted AND r.status='pending'",
      Long.class,
      owner().id()
    );
    assertEquals(pending, summary.path("pending").asLong());
    assertFalse(summary.has("users"));
    assertEquals(
      list("reports", owner(), "", 1, 100).path("total").asLong(),
      summary.path("reports").asLong()
    );
  }

  @Test
  @Transactional(propagation = Propagation.NOT_SUPPORTED)
  void pendingModerationUsesOfficialStatusCommittedWhileWaiting() throws Exception {
    Accounts.User owner = owner();
    UUID community = communities.create(
      owner,
      "CMS race " + UUID.randomUUID(),
      "",
      "Технологии",
      "lime"
    );
    UUID id = create(admin(), community, "Заявка для проверки смены модератора", false, List.of());
    UUID revision = latest(id);
    String application = "cms-approve-" + UUID.randomUUID();
    ExecutorService worker = Executors.newSingleThreadExecutor();
    try (Connection editor = dataSource.getConnection()) {
      editor.setAutoCommit(false);
      try {
        try (
          var update = editor.prepareStatement("UPDATE communities SET official=TRUE WHERE id=?")
        ) {
          update.setObject(1, community);
          update.executeUpdate();
        }
        Future<?> approval = worker.submit(() -> {
          new org.springframework.transaction.support.TransactionTemplate(transactions).execute(
            status -> {
              db.queryForObject(
                "SELECT set_config('application_name',?,true)",
                String.class,
                application
              );
              news.decide(revision, owner, true, null);
              return null;
            }
          );
        });
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        boolean waiting = false;
        while (System.nanoTime() < deadline) {
          waiting = db.queryForObject(
            "SELECT count(*)>0 FROM pg_stat_activity WHERE application_name=? AND wait_event_type='Lock'",
            Boolean.class,
            application
          );
          if (waiting || approval.isDone()) break;
          Thread.sleep(20);
        }
        assertTrue(waiting, "Moderation must wait for the community-status transaction");
        editor.commit();
        ExecutionException failure = assertThrows(ExecutionException.class, () ->
          approval.get(10, TimeUnit.SECONDS)
        );
        ApiException denied = assertInstanceOf(ApiException.class, failure.getCause());
        assertEquals(org.springframework.http.HttpStatus.FORBIDDEN, denied.status);
        assertEquals(
          "pending",
          db.queryForObject("SELECT status FROM news_revisions WHERE id=?", String.class, revision)
        );
      } finally {
        editor.rollback();
      }
    } finally {
      worker.shutdownNow();
      worker.awaitTermination(10, TimeUnit.SECONDS);
      db.update("DELETE FROM notifications WHERE news_id=?", id);
      db.update("DELETE FROM audit_log WHERE target_id=? OR target_id=?", id, revision);
      db.update(
        "UPDATE news SET latest_revision_id=NULL,published_revision_id=NULL,main_revision_id=NULL WHERE id=?",
        id
      );
      db.update("DELETE FROM news_revisions WHERE news_id=?", id);
      db.update("DELETE FROM news WHERE id=?", id);
      db.update("DELETE FROM communities WHERE id=?", community);
    }
  }
}
