package ru.uunit.news;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.core.session.SessionRegistry;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class CmsUsersIntegrationTest {

  private static final String PASSWORD = "CmsTestPassword!123";

  @Autowired
  MockMvc mvc;

  @Autowired
  JdbcTemplate db;

  @Autowired
  ObjectMapper mapper;

  @Autowired
  Accounts accounts;

  @Autowired
  PasswordEncoder passwords;

  @Autowired
  SessionRegistry sessions;

  private final List<Accounts.User> fixtures = new ArrayList<>();

  private Accounts.User fixture(String prefix, String role) {
    String username = prefix + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
    UUID id = UUID.randomUUID();
    db.update(
      "INSERT INTO app_users(id,username,email,display_name,password_hash,role,verified) VALUES(?,?,?,?,?,?,TRUE)",
      id,
      username,
      username + "@example.invalid",
      "Участник CMS",
      passwords.encode(PASSWORD),
      role
    );
    Accounts.User result = accounts.find(username);
    fixtures.add(result);
    return result;
  }

  private JsonNode patchUser(UUID id, Map<String, Object> values) throws Exception {
    return mapper.readTree(
      mvc
        .perform(
          patch("/api/cms/users/" + id)
            .with(user("uunit.admin"))
            .with(csrf())
            .contentType("application/json")
            .content(mapper.writeValueAsString(values))
        )
        .andExpect(status().isOk())
        .andReturn()
        .getResponse()
        .getContentAsString()
    );
  }

  private MockHttpSession login(Accounts.User account, int expectedStatus) throws Exception {
    var result = mvc
      .perform(
        post("/api/auth/login")
          .with(csrf())
          .header("X-NewsApp-Client-IP", "198.51.100.73")
          .param("username", account.username())
          .param("password", PASSWORD)
      )
      .andExpect(status().is(expectedStatus))
      .andReturn();
    return (MockHttpSession) result.getRequest().getSession(false);
  }

  @AfterEach
  void cleanup() {
    for (Accounts.User account : fixtures) {
      accounts.endSessions(account.username());
      db.update(
        "DELETE FROM audit_log WHERE target_id=? OR actor_id=?",
        account.id(),
        account.id()
      );
      db.update("DELETE FROM account_tokens WHERE user_id=?", account.id());
      db.update("DELETE FROM local_mail WHERE recipient=?", account.email());
      db.update("DELETE FROM app_users WHERE id=?", account.id());
    }
  }

  @Test
  void userManagementIsForPlatformAdministratorsOnlyAndRequiresCsrf() throws Exception {
    Accounts.User target = fixture("cmsrights", "USER");
    mvc.perform(get("/api/cms/users")).andExpect(status().isUnauthorized());
    for (String caller : List.of("club.owner", target.username())) {
      mvc.perform(get("/api/cms/users").with(user(caller))).andExpect(status().isForbidden());
      mvc
        .perform(get("/api/cms/users/" + target.id()).with(user(caller)))
        .andExpect(status().isForbidden());
      mvc
        .perform(
          patch("/api/cms/users/" + target.id())
            .with(user(caller))
            .with(csrf())
            .contentType("application/json")
            .content("{\"name\":\"Новое имя\"}")
        )
        .andExpect(status().isForbidden());
    }
    mvc
      .perform(
        patch("/api/cms/users/" + target.id())
          .with(user("uunit.admin"))
          .contentType("application/json")
          .content("{\"name\":\"Новое имя\"}")
      )
      .andExpect(status().isForbidden());
    mvc
      .perform(get("/api/cms/users/" + UUID.randomUUID()).with(user("uunit.admin")))
      .andExpect(status().isNotFound());
  }

  @Test
  void listsArePagedFilteredSortedAndNeverExposeAuthenticationSecrets() throws Exception {
    String prefix = "cms" + UUID.randomUUID().toString().substring(0, 6);
    for (int n = 0; n < 3; n++) fixture(prefix, "USER");
    List<String> usernames = fixtures.stream().map(Accounts.User::username).sorted().toList();
    JsonNode first = mapper.readTree(
      mvc
        .perform(
          get("/api/cms/users")
            .with(user("uunit.admin"))
            .param("q", prefix)
            .param("perPage", "2")
            .param("sort", "username")
            .param("order", "ASC")
        )
        .andExpect(status().isOk())
        .andReturn()
        .getResponse()
        .getContentAsString()
    );
    assertEquals(3, first.path("total").asInt());
    assertEquals(2, first.path("items").size());
    assertEquals(usernames.getFirst(), first.path("items").get(0).path("username").asText());
    Set<String> expected = Set.of(
      "id",
      "username",
      "name",
      "email",
      "bio",
      "role",
      "verified",
      "blocked",
      "blockReason",
      "createdAt"
    );
    Set<String> actual = new HashSet<>();
    first.path("items").get(0).fieldNames().forEachRemaining(actual::add);
    assertEquals(expected, actual);
    mvc
      .perform(
        get("/api/cms/users")
          .with(user("uunit.admin"))
          .param("q", prefix)
          .param("page", "2")
          .param("perPage", "2")
          .param("sort", "username")
          .param("order", "ASC")
      )
      .andExpect(status().isOk())
      .andExpect(jsonPath("total").value(3))
      .andExpect(jsonPath("items.length()").value(1))
      .andExpect(jsonPath("items[0].username").value(usernames.getLast()));
    Accounts.User blocked = fixtures.getFirst();
    patchUser(blocked.id(), Map.of("blocked", true, "blockReason", "Повторные нарушения правил"));
    mvc
      .perform(
        get("/api/cms/users")
          .with(user("uunit.admin"))
          .param("q", prefix)
          .param("blocked", "true")
          .param("role", "USER")
      )
      .andExpect(status().isOk())
      .andExpect(jsonPath("total").value(1))
      .andExpect(jsonPath("items[0].id").value(blocked.id().toString()));
    mvc
      .perform(get("/api/cms/users/" + blocked.id()).with(user("uunit.admin")))
      .andExpect(status().isOk())
      .andExpect(jsonPath("blocked").value(true))
      .andExpect(jsonPath("password_hash").doesNotExist())
      .andExpect(jsonPath("passwordHash").doesNotExist());
    for (Map.Entry<String, String> invalid : Map.of(
      "page",
      "0",
      "perPage",
      "101",
      "sort",
      "password_hash",
      "order",
      "DESC;DROP",
      "role",
      "OWNER"
    ).entrySet()) {
      mvc
        .perform(
          get("/api/cms/users")
            .with(user("uunit.admin"))
            .param(invalid.getKey(), invalid.getValue())
        )
        .andExpect(status().isBadRequest());
    }
    mvc
      .perform(get("/api/cms/users").with(user("uunit.admin")).param("q", "%' OR TRUE --"))
      .andExpect(status().isOk())
      .andExpect(jsonPath("total").value(0));
  }

  @Test
  void updatesValidateProfilesAndRejectRoleOrCredentialChanges() throws Exception {
    Accounts.User target = fixture("cmsedit", "USER");
    String hash = db.queryForObject(
      "SELECT password_hash FROM app_users WHERE id=?",
      String.class,
      target.id()
    );
    JsonNode updated = patchUser(
      target.id(),
      Map.of("name", "  Новое имя  ", "bio", "Описание пользователя")
    );
    assertEquals("Новое имя", updated.path("name").asText());
    assertEquals("Описание пользователя", updated.path("bio").asText());
    for (Map<String, Object> invalid : List.<Map<String, Object>>of(
      Map.of("name", "   "),
      Map.of("name", "x".repeat(81)),
      Map.of("bio", "x".repeat(501)),
      Map.of("role", "ADMIN"),
      Map.of("verified", true),
      Map.of("email", "other@example.invalid"),
      Map.of("password", "new password"),
      Map.of("blocked", "true"),
      Map.of("blocked", true),
      Map.of("blocked", true, "blockReason", "1234"),
      Map.of("blocked", true, "blockReason", "x".repeat(501))
    )) {
      mvc
        .perform(
          patch("/api/cms/users/" + target.id())
            .with(user("uunit.admin"))
            .with(csrf())
            .contentType("application/json")
            .content(mapper.writeValueAsString(invalid))
        )
        .andExpect(status().isBadRequest());
    }
    assertEquals("USER", accounts.find(target.username()).role());
    assertEquals(target.email(), accounts.find(target.username()).email());
    assertEquals(
      hash,
      db.queryForObject("SELECT password_hash FROM app_users WHERE id=?", String.class, target.id())
    );
    var audit = db.queryForMap(
      "SELECT actor_id,action,detail FROM audit_log WHERE target_id=?",
      target.id()
    );
    assertEquals(accounts.find("uunit.admin").id(), audit.get("actor_id"));
    assertEquals("user.updated", audit.get("action"));
    assertFalse(audit.get("detail").toString().contains(PASSWORD));
    assertFalse(audit.get("detail").toString().contains(hash));
  }

  @Test
  void administratorsAndOwnAccountCannotBeBlocked() throws Exception {
    Accounts.User another = fixture("cmsadmin", "ADMIN");
    for (UUID id : List.of(accounts.find("uunit.admin").id(), another.id())) {
      mvc
        .perform(
          patch("/api/cms/users/" + id)
            .with(user("uunit.admin"))
            .with(csrf())
            .contentType("application/json")
            .content("{\"blocked\":true,\"blockReason\":\"Причина блокировки\"}")
        )
        .andExpect(status().isBadRequest());
      assertFalse(db.queryForObject("SELECT blocked FROM app_users WHERE id=?", Boolean.class, id));
    }
  }

  @Test
  void blockingEndsSessionsRejectsStaleAuthenticationAndResetThenUnblockingRestoresLogin()
    throws Exception {
    Accounts.User target = fixture("cmsblock", "USER");
    MockHttpSession oldSession = login(target, 200);
    mvc.perform(get("/api/auth/me").session(oldSession)).andExpect(status().isOk());
    accounts.forgot(target.email());
    String token = accounts
      .mailbox()
      .stream()
      .filter(m -> m.recipient().equals(target.email()))
      .findFirst()
      .orElseThrow()
      .actionUrl()
      .split("token=")[1];
    JsonNode blocked = patchUser(
      target.id(),
      Map.of("blocked", true, "blockReason", "Повторная публикация спама")
    );
    assertTrue(blocked.path("blocked").asBoolean());
    assertTrue(sessions.getSessionInformation(oldSession.getId()).isExpired());
    mvc.perform(get("/api/auth/me").session(oldSession)).andExpect(status().isUnauthorized());
    mvc
      .perform(get("/api/auth/me").with(user(target.username())))
      .andExpect(status().isUnauthorized());
    mvc
      .perform(get("/api/me/preferences").with(user(target.username())))
      .andExpect(status().isUnauthorized());
    login(target, 401);
    mvc
      .perform(
        post("/api/auth/reset-password")
          .with(csrf())
          .contentType("application/json")
          .content(
            mapper.writeValueAsString(Map.of("token", token, "password", "OtherPassword!123"))
          )
      )
      .andExpect(status().isBadRequest());
    long before = db.queryForObject(
      "SELECT count(*) FROM local_mail WHERE recipient=?",
      Long.class,
      target.email()
    );
    accounts.forgot(target.email());
    assertEquals(
      before,
      db.queryForObject(
        "SELECT count(*) FROM local_mail WHERE recipient=?",
        Long.class,
        target.email()
      )
    );
    JsonNode restored = patchUser(target.id(), Map.of("blocked", false));
    assertFalse(restored.path("blocked").asBoolean());
    assertEquals("", restored.path("blockReason").asText());
    MockHttpSession fresh = login(target, 200);
    mvc.perform(get("/api/auth/me").session(fresh)).andExpect(status().isOk());
    assertThrows(ApiException.class, () -> accounts.consume(token, "reset", "OtherPassword!123"));
    assertEquals(
      2L,
      db.queryForObject(
        "SELECT count(*) FROM audit_log WHERE target_id=? AND action IN ('user.blocked','user.unblocked')",
        Long.class,
        target.id()
      )
    );
  }
}
