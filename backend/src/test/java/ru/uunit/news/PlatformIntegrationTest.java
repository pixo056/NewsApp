package ru.uunit.news;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class PlatformIntegrationTest {

  @Autowired
  MockMvc mvc;

  @Autowired
  JdbcTemplate db;

  @Autowired
  ObjectMapper mapper;

  @Autowired
  Accounts accounts;

  @Autowired
  NewsService news;

  @Autowired
  MediaService media;

  @Autowired
  PasswordEncoder passwords;

  private final String body =
    "Полезная новость о студенческой жизни, которую мы проверяем в настоящей базе PostgreSQL.";

  private Accounts.User owner() {
    return accounts.find("club.owner");
  }

  private Accounts.User admin() {
    return accounts.find("uunit.admin");
  }

  private UUID club() {
    return db.queryForObject("SELECT id FROM communities WHERE name='IT-сообщество'", UUID.class);
  }

  private UUID official() {
    return db.queryForObject("SELECT id FROM communities WHERE official LIMIT 1", UUID.class);
  }

  private Accounts.User outsider() {
    String name = "test" + UUID.randomUUID().toString().substring(0, 8);
    Accounts.User user = accounts.register(
      name,
      name + "@test.local",
      "Участник теста",
      "StrongPass!123"
    );
    db.update("UPDATE app_users SET verified=TRUE WHERE id=?", user.id());
    return accounts.find(name);
  }

  private UUID pending(UUID id) {
    return db.queryForObject("SELECT latest_revision_id FROM news WHERE id=?", UUID.class, id);
  }

  private UUID submit(Accounts.User user, UUID community) {
    return news.submit(
      user,
      new NewsService.Input(
        "Заголовок тестовой новости",
        body,
        "Технологии",
        community,
        "",
        List.of()
      ),
      null
    );
  }

  @AfterEach
  void cleanTestUploads() throws Exception {
    Path directory = Path.of(System.getenv("NEWSAPP_LOCAL_DIR"), "tests", "media");
    if (Files.isDirectory(directory)) try (var files = Files.list(directory)) {
      for (Path p : files.toList()) if (Files.isRegularFile(p)) Files.deleteIfExists(p);
    }
  }

  @Test
  void localMailboxIsNotExposedToWifiClients() throws Exception {
    mvc.perform(get("/api/dev/mailbox")).andExpect(status().isOk());
    mvc
      .perform(get("/api/dev/mailbox").header("X-NewsApp-Mailbox", "remote"))
      .andExpect(status().isForbidden());
    mvc
      .perform(get("/api/auth/me").header("X-NewsApp-Mailbox", "remote"))
      .andExpect(jsonPath("localMailbox").value(false));
    mvc
      .perform(
        get("/api/dev/mailbox").with(request -> {
          request.setRemoteAddr("192.0.2.20");
          return request;
        })
      )
      .andExpect(status().isForbidden());
  }

  @Test
  void guestsCannotSeeQueueOrPendingNews() throws Exception {
    mvc.perform(get("/api/auth/me")).andExpect(jsonPath("localMailbox").value(true));
    UUID id = submit(owner(), official());
    mvc.perform(get("/api/news/" + id)).andExpect(status().isNotFound());
    mvc.perform(get("/api/admin/revisions")).andExpect(status().isUnauthorized());
    mvc
      .perform(
        post("/api/admin/revisions/" + pending(id) + "/approve")
          .with(user("club.owner"))
          .with(csrf())
      )
      .andExpect(status().isForbidden());
    mvc.perform(get("/api/me/news/" + id).with(user("uunit.admin"))).andExpect(status().isOk());
  }

  @Test
  void onlyPlatformAdministratorsApproveOrdinaryCommunityNews() throws Exception {
    Accounts.User stranger = outsider();
    UUID id = submit(stranger, club());
    mvc
      .perform(
        post("/api/admin/revisions/" + pending(id) + "/approve")
          .with(user(stranger.username()))
          .with(csrf())
      )
      .andExpect(status().isForbidden());
    mvc
      .perform(
        post("/api/admin/revisions/" + pending(id) + "/approve")
          .with(user("club.owner"))
          .with(csrf())
      )
      .andExpect(status().isForbidden());
    mvc.perform(get("/api/news/" + id)).andExpect(status().isNotFound());
    mvc
      .perform(get("/api/me/news/" + id).with(user("club.owner")))
      .andExpect(status().isNotFound());
    mvc
      .perform(get("/api/cms/news/" + id).with(user("club.owner")))
      .andExpect(status().isNotFound());
    mvc
      .perform(
        post("/api/admin/revisions/" + pending(id) + "/approve")
          .with(user("uunit.admin"))
          .with(csrf())
      )
      .andExpect(status().isOk());
    mvc
      .perform(get("/api/news/" + id))
      .andExpect(status().isOk())
      .andExpect(jsonPath("status").value("published"));
    mvc
      .perform(
        patch("/api/admin/communities/" + club())
          .with(user("club.owner"))
          .with(csrf())
          .contentType("application/json")
          .content("{\"dailyLimit\":999,\"official\":true}")
      )
      .andExpect(status().isForbidden());
  }

  @Test
  void editingAndRejectionPreservePublicRevision() throws Exception {
    UUID id = submit(owner(), club());
    news.decide(pending(id), admin(), true, null);
    news.submit(
      owner(),
      new NewsService.Input(
        "Совершенно новый заголовок",
        body,
        "Технологии",
        club(),
        "",
        List.of()
      ),
      id
    );
    mvc
      .perform(get("/api/news/" + id))
      .andExpect(jsonPath("title").value("Заголовок тестовой новости"));
    mvc
      .perform(get("/api/me/news/" + id).with(user("club.owner")))
      .andExpect(jsonPath("title").value("Совершенно новый заголовок"));
    news.decide(pending(id), admin(), false, "Уточните фактические обстоятельства");
    mvc
      .perform(get("/api/news/" + id))
      .andExpect(jsonPath("title").value("Заголовок тестовой новости"));
    mvc
      .perform(
        post("/api/admin/revisions/" + pending(id) + "/approve")
          .with(user("uunit.admin"))
          .with(csrf())
      )
      .andExpect(status().isConflict());
    news.submit(
      owner(),
      new NewsService.Input(
        "Совершенно новый заголовок",
        body,
        "Технологии",
        club(),
        "",
        List.of()
      ),
      id
    );
    news.decide(pending(id), admin(), true, null);
    mvc
      .perform(get("/api/news/" + id))
      .andExpect(jsonPath("title").value("Совершенно новый заголовок"));
  }

  @Test
  void nonOwnerCannotEditDeleteOrReadPrivateRevision() throws Exception {
    UUID id = submit(owner(), club());
    Accounts.User stranger = outsider();
    mvc
      .perform(get("/api/me/news/" + id).with(user(stranger.username())))
      .andExpect(status().isNotFound());
    mvc
      .perform(
        delete("/api/news/" + id)
          .with(user(stranger.username()))
          .with(csrf())
      )
      .andExpect(status().isForbidden());
    assertThrows(ApiException.class, () ->
      news.submit(
        stranger,
        new NewsService.Input("Чужие изменения новости", body, "Технологии", club(), "", List.of()),
        id
      )
    );
  }

  @Test
  void validatesInputVerificationAndCsrf() throws Exception {
    mvc
      .perform(
        post("/api/news").with(user("club.owner")).contentType("application/json").content("{}")
      )
      .andExpect(status().isForbidden());
    mvc
      .perform(
        post("/api/news")
          .with(user("club.owner"))
          .with(csrf())
          .contentType("application/json")
          .content("{}")
      )
      .andExpect(status().isBadRequest());
    mvc
      .perform(
        post("/api/news")
          .with(user("club.owner"))
          .with(csrf())
          .contentType("application/json")
          .content(
            mapper.writeValueAsString(
              new NewsService.Input(
                "Короткий текст новости",
                "коротко",
                "Наука",
                null,
                "",
                List.of()
              )
            )
          )
      )
      .andExpect(status().isBadRequest());
    Accounts.User unverified = accounts.register(
      "unverified",
      "unverified@test.local",
      "Новый участник",
      "StrongPass!123"
    );
    assertThrows(ApiException.class, () -> submit(unverified, null));
  }

  @Test
  void registrationCannotChooseAdminRole() throws Exception {
    mvc
      .perform(
        post("/api/auth/register")
          .with(csrf())
          .contentType("application/json")
          .content(
            "{\"username\":\"rolecheck\",\"email\":\"rolecheck@test.local\",\"name\":\"Проверка\",\"password\":\"StrongPass!123\",\"role\":\"ADMIN\"}"
          )
      )
      .andExpect(status().isOk());
    assertEquals("USER", accounts.find("rolecheck").role());
    assertFalse(accounts.find("rolecheck").verified());
  }

  @Test
  void resetAndVerifyTokensAreOneTimeAndExpire() {
    Accounts.User user = accounts.register(
      "tokentest",
      "tokentest@test.local",
      "Проверка токена",
      "StrongPass!123"
    );
    String verify = accounts
      .mailbox()
      .stream()
      .filter(m -> m.recipient().equals(user.email()))
      .findFirst()
      .orElseThrow()
      .actionUrl()
      .split("token=")[1];
    accounts.consume(verify, "verify", null);
    assertTrue(accounts.find(user.username()).verified());
    assertThrows(ApiException.class, () -> accounts.consume(verify, "verify", null));
    accounts.forgot(user.email());
    String reset = accounts
      .mailbox()
      .stream()
      .filter(m -> m.recipient().equals(user.email()) && m.subject().contains("Восстановление"))
      .findFirst()
      .orElseThrow()
      .actionUrl()
      .split("token=")[1];
    accounts.consume(reset, "reset", "OtherPassword!123");
    assertTrue(
      passwords.matches(
        "OtherPassword!123",
        db.queryForObject("SELECT password_hash FROM app_users WHERE id=?", String.class, user.id())
      )
    );
    assertThrows(ApiException.class, () -> accounts.consume(reset, "reset", "AnotherPass!123"));
    accounts.forgot(user.email());
    db.update(
      "UPDATE account_tokens SET expires_at=now()-interval '1 minute' WHERE user_id=?",
      user.id()
    );
    String expired = accounts.mailbox().getFirst().actionUrl().split("token=")[1];
    assertThrows(ApiException.class, () -> accounts.consume(expired, "reset", "AnotherPass!123"));
  }

  @Test
  void mediaIsPrivateUntilApprovalAndCannotBeStolen() throws Exception {
    byte[] bytes = Files.readAllBytes(
      Path.of(System.getenv("NEWSAPP_LOCAL_DIR")).getParent().resolve("public/images/campus.jpg")
    );
    Map<String, Object> asset = media.upload(
      new MockMultipartFile("file", "campus.jpg", "image/jpeg", bytes),
      owner()
    );
    UUID assetId = (UUID) asset.get("id");
    mvc.perform(get("/api/media/" + assetId)).andExpect(status().isNotFound());
    Accounts.User stranger = outsider();
    assertThrows(ApiException.class, () ->
      news.submit(
        stranger,
        new NewsService.Input("Попытка чужого вложения", body, "Наука", null, "", List.of(assetId)),
        null
      )
    );
    UUID id = news.submit(
      owner(),
      new NewsService.Input(
        "Новость с настоящим фото",
        body,
        "Наука",
        club(),
        "",
        List.of(assetId)
      ),
      null
    );
    mvc.perform(get("/api/media/" + assetId)).andExpect(status().isNotFound());
    news.decide(pending(id), admin(), true, null);
    mvc
      .perform(get("/api/media/" + assetId))
      .andExpect(status().isOk())
      .andExpect(content().contentType("image/jpeg"));
    news.delete(id, owner(), false);
    mvc.perform(get("/api/media/" + assetId)).andExpect(status().isNotFound());
    assertThrows(ApiException.class, () ->
      media.upload(
        new MockMultipartFile("file", "fake.jpg", "image/jpeg", "<script>bad</script>".getBytes()),
        owner()
      )
    );
  }

  @Test
  void wifiPasswordResetLimitsAreIndependent() throws Exception {
    for (int attempt = 0; attempt < 15; attempt++) {
      mvc
        .perform(
          post("/api/auth/forgot-password")
            .with(csrf())
            .header("X-NewsApp-Client-IP", "198.51.100.31")
            .contentType("application/json")
            .content("{\"email\":\"missing@example.invalid\"}")
        )
        .andExpect(status().isOk());
    }
    mvc
      .perform(
        post("/api/auth/forgot-password")
          .with(csrf())
          .header("X-NewsApp-Client-IP", "198.51.100.31")
          .contentType("application/json")
          .content("{\"email\":\"missing@example.invalid\"}")
      )
      .andExpect(status().isTooManyRequests());
    mvc
      .perform(
        post("/api/auth/forgot-password")
          .with(csrf())
          .header("X-NewsApp-Client-IP", "198.51.100.32")
          .contentType("application/json")
          .content("{\"email\":\"missing@example.invalid\"}")
      )
      .andExpect(status().isOk());
  }

  @Test
  void videoIsProcessedAndSupportsRangeRequests() throws Exception {
    Path fixture = Files.createTempFile("newsapp-test-", ".mp4");
    try {
      Process process = new ProcessBuilder(
        System.getenv("NEWSAPP_FFMPEG"),
        "-v",
        "error",
        "-y",
        "-f",
        "lavfi",
        "-i",
        "color=c=purple:s=320x240:d=1",
        "-c:v",
        "libx264",
        "-pix_fmt",
        "yuv420p",
        fixture.toString()
      )
        .redirectError(ProcessBuilder.Redirect.INHERIT)
        .start();
      assertTrue(process.waitFor(30, TimeUnit.SECONDS));
      assertEquals(0, process.exitValue());
      Map<String, Object> asset = media.upload(
        new MockMultipartFile("file", "clip.mp4", "video/mp4", Files.readAllBytes(fixture)),
        owner()
      );
      UUID assetId = (UUID) asset.get("id");
      UUID id = news.submit(
        owner(),
        new NewsService.Input(
          "Видео без текста допускается",
          "",
          "Наука",
          club(),
          "",
          List.of(assetId)
        ),
        null
      );
      news.decide(pending(id), admin(), true, null);
      mvc
        .perform(get("/api/media/" + assetId).header("Range", "bytes=0-100"))
        .andExpect(status().isPartialContent())
        .andExpect(header().string("Content-Type", "video/mp4"));
      mvc.perform(get("/api/media/" + assetId + "/poster")).andExpect(status().isOk());
    } finally {
      Files.deleteIfExists(fixture);
    }
  }

  @Test
  void threeMinuteVideoLimitIsEnforcedWithoutTruncatingValidClips() throws Exception {
    for (int seconds : new int[] { 180, 181 }) {
      Path fixture = Files.createTempFile("newsapp-duration-test-", ".mp4");
      try {
        Process process = new ProcessBuilder(
          System.getenv("NEWSAPP_FFMPEG"),
          "-v",
          "error",
          "-y",
          "-f",
          "lavfi",
          "-i",
          "color=c=purple:s=32x32:r=1:d=" + seconds,
          "-c:v",
          "libx264",
          "-pix_fmt",
          "yuv420p",
          fixture.toString()
        )
          .redirectError(ProcessBuilder.Redirect.INHERIT)
          .start();
        assertTrue(process.waitFor(30, TimeUnit.SECONDS));
        assertEquals(0, process.exitValue());
        var file = new MockMultipartFile(
          "file",
          "clip.mp4",
          "video/mp4",
          Files.readAllBytes(fixture)
        );
        if (seconds == 180) {
          var result = media.upload(file, owner());
          assertEquals(180.0, ((Number) result.get("duration")).doubleValue(), 0.25);
          Path encoded = Path.of(
            System.getenv("NEWSAPP_LOCAL_DIR"),
            "tests",
            "media",
            result.get("id") + ".mp4"
          );
          Process probe = new ProcessBuilder(
            System.getenv("NEWSAPP_FFPROBE"),
            "-v",
            "error",
            "-show_entries",
            "format=duration",
            "-of",
            "default=noprint_wrappers=1:nokey=1",
            encoded.toString()
          ).start();
          assertTrue(probe.waitFor(30, TimeUnit.SECONDS));
          assertEquals(0, probe.exitValue());
          assertEquals(
            180.0,
            Double.parseDouble(new String(probe.getInputStream().readAllBytes()).trim()),
            0.25
          );
        } else {
          assertThrows(ApiException.class, () -> media.upload(file, owner()));
        }
      } finally {
        Files.deleteIfExists(fixture);
      }
    }
    var oversized = new MockMultipartFile("file", "large.mp4", "video/mp4", new byte[] { 1 }) {
      @Override
      public long getSize() {
        return 200L * 1024 * 1024 + 1;
      }
    };
    assertThrows(ApiException.class, () -> media.upload(oversized, owner()));
  }

  @Test
  void bookmarksSubscriptionsSearchAndPaginationWork() throws Exception {
    UUID id = submit(owner(), club());
    news.decide(pending(id), admin(), true, null);
    mvc
      .perform(
        put("/api/news/" + id + "/bookmark")
          .with(user("club.owner"))
          .with(csrf())
      )
      .andExpect(status().isOk());
    mvc
      .perform(get("/api/news?saved=true").with(user("club.owner")))
      .andExpect(jsonPath("items[0].id").value(id.toString()));
    mvc
      .perform(
        put("/api/communities/" + club() + "/subscription")
          .with(user("club.owner"))
          .with(csrf())
      )
      .andExpect(status().isOk());
    mvc
      .perform(get("/api/news?subscriptions=true").with(user("club.owner")))
      .andExpect(status().isOk())
      .andExpect(jsonPath("items.length()").value(2));
    NewsService.Page first = news.feed(null, null, null, null, null, 1, false, false);
    assertEquals(1, first.items().size());
    assertNotNull(first.nextCursor());
    NewsService.Page second = news.feed(
      null,
      null,
      null,
      null,
      first.nextCursor(),
      1,
      false,
      false
    );
    assertNotEquals(first.items().getFirst().get("id"), second.items().getFirst().get("id"));
    assertEquals(
      1,
      news.feed(null, null, "Заголовок тестовой", null, null, 20, false, false).items().size()
    );
    news.delete(id, owner(), false);
    mvc
      .perform(get("/api/news?saved=true").with(user("club.owner")))
      .andExpect(jsonPath("items.length()").value(0));
  }

  @Test
  @Transactional(propagation = Propagation.NOT_SUPPORTED)
  void concurrentSubmissionsCannotBypassDailyLimit() throws Exception {
    Accounts.User user = outsider();
    ExecutorService pool = Executors.newFixedThreadPool(7);
    try {
      List<Callable<Boolean>> work = new ArrayList<>();
      for (int i = 0; i < 7; i++) work.add(() -> {
        try {
          submit(user, null);
          return true;
        } catch (ApiException e) {
          return false;
        }
      });
      int success = 0;
      for (Future<Boolean> result : pool.invokeAll(work)) if (result.get()) success++;
      assertEquals(5, success);
    } finally {
      pool.shutdownNow();
    }
  }
}
