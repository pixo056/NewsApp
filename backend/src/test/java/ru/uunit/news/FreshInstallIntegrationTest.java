package ru.uunit.news;

import static org.junit.jupiter.api.Assertions.*;

import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest
@ActiveProfiles("test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class FreshInstallIntegrationTest {

  // A new schema in the test database verifies migration V1→V5 and first-run seed together.
  private static final String SCHEMA =
    "install_check_" + UUID.randomUUID().toString().replace("-", "");

  @DynamicPropertySource
  static void schema(DynamicPropertyRegistry properties) {
    properties.add(
      "spring.datasource.url",
      () -> "jdbc:postgresql://127.0.0.1:55432/newsapp_test?currentSchema=" + SCHEMA
    );
    properties.add("spring.flyway.schemas", () -> SCHEMA);
    properties.add("spring.flyway.default-schema", () -> SCHEMA);
    properties.add(
      "app.local-directory",
      () -> System.getenv("NEWSAPP_LOCAL_DIR") + "/tests/" + SCHEMA
    );
  }

  @Autowired
  JdbcTemplate db;

  @Autowired
  NewsService news;

  @Autowired
  Accounts accounts;

  @Test
  void firstRunSeedsPublicVersionsAndPendingQueuesWithoutNullDates() {
    assertEquals(2, db.queryForObject("SELECT count(*) FROM app_users", Integer.class));
    assertEquals(3, news.feed(null, null, null, null, null, 50, false, false).items().size());
    assertEquals(
      0,
      db.queryForObject(
        "SELECT count(*) FROM news WHERE main_revision_id IS NOT NULL AND main_published_at IS NULL",
        Integer.class
      )
    );
    assertEquals(2, news.queue(accounts.find("uunit.admin")).size());
    assertEquals(
      5,
      db.queryForObject(
        "SELECT count(*) FROM flyway_schema_history WHERE success AND version IS NOT NULL",
        Integer.class
      )
    );
  }

  @AfterAll
  void dropOnlyThisGeneratedTestSchema() {
    // SCHEMA is generated above, never a user-supplied identifier; public/user databases are untouched.
    db.execute("DROP SCHEMA " + SCHEMA + " CASCADE");
  }
}
