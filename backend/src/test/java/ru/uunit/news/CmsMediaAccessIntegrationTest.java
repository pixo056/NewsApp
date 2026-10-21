package ru.uunit.news;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class CmsMediaAccessIntegrationTest {

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
  MediaService media;

  private final List<UUID> uploads = new ArrayList<>();
  private static final String BODY =
    "Проверяем доступ к настоящим вложениям новости при редакторских правках, скрытии и смене статуса паблика.";

  private Accounts.User owner() {
    return accounts.find("club.owner");
  }

  private Accounts.User admin() {
    return accounts.find("uunit.admin");
  }

  private Accounts.User author() {
    String name = "cmsmedia" + UUID.randomUUID().toString().substring(0, 8);
    Accounts.User user = accounts.register(
      name,
      name + "@test.local",
      "Автор вложений",
      "StrongPass!123"
    );
    db.update("UPDATE app_users SET verified=TRUE WHERE id=?", user.id());
    return accounts.find(name);
  }

  private UUID group() {
    return communities.create(
      owner(),
      "Медиа CMS " + UUID.randomUUID(),
      "",
      "Технологии",
      "purple"
    );
  }

  private UUID photo(Accounts.User uploader) throws Exception {
    var bytes = new ByteArrayOutputStream();
    assertTrue(ImageIO.write(new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB), "jpg", bytes));
    UUID id = (UUID) media
      .upload(
        new MockMultipartFile("file", "actual-photo.jpg", "image/jpeg", bytes.toByteArray()),
        uploader
      )
      .get("id");
    uploads.add(id);
    return id;
  }

  private NewsService.Input input(UUID group, List<UUID> photos) {
    return new NewsService.Input(
      "Новость для проверки доступа к медиа",
      BODY,
      "Технологии",
      group,
      "",
      photos
    );
  }

  private UUID create(Accounts.User author, UUID group, List<UUID> photos, boolean published) {
    UUID id = news.submit(author, input(group, photos), null);
    if (published) news.decide(
      db.queryForObject("SELECT latest_revision_id FROM news WHERE id=?", UUID.class, id),
      admin(),
      true,
      null
    );
    return id;
  }

  private void readable(UUID photo, Accounts.User reader) throws Exception {
    mvc
      .perform(get("/api/media/" + photo).with(user(reader.username())))
      .andExpect(status().isOk())
      .andExpect(content().contentType("image/jpeg"))
      .andExpect(result -> assertTrue(result.getResponse().getContentAsByteArray().length > 0));
  }

  private void denied(UUID photo, Accounts.User reader) throws Exception {
    if (reader == null) mvc.perform(get("/api/media/" + photo)).andExpect(status().isNotFound());
    else mvc
      .perform(get("/api/media/" + photo).with(user(reader.username())))
      .andExpect(status().isNotFound());
  }

  @AfterEach
  void removeOnlyThisTestsFixtureFiles() throws Exception {
    Path directory = Path.of(System.getenv("NEWSAPP_LOCAL_DIR"), "tests", "media");
    for (UUID id : uploads) Files.deleteIfExists(directory.resolve(id + ".jpg"));
  }

  @Test
  void ordinaryOwnerCannotPreviewAnotherAuthorsHiddenAttachments() throws Exception {
    Accounts.User author = author();
    Accounts.User unrelated = author();
    UUID group = group();
    UUID photo = photo(author);
    UUID id = create(author, group, List.of(photo), true);
    mvc.perform(get("/api/media/" + photo)).andExpect(status().isOk());
    cms.visibility(id, admin(), true);
    mvc
      .perform(get("/api/cms/news/" + id).with(user(owner().username())))
      .andExpect(status().isNotFound());
    denied(photo, owner());
    readable(photo, author);
    readable(photo, admin());
    denied(photo, unrelated);
    denied(photo, null);
  }

  @Test
  void originalAuthorCanReadEditorialUploadsBeforeApprovalAndAfterHiding() throws Exception {
    Accounts.User author = author();
    Accounts.User unrelated = author();
    UUID group = group();
    UUID id = create(author, group, List.of(), true);
    UUID editorialPhoto = photo(admin());
    // An unbound upload remains private to its uploader and the administrator.
    denied(editorialPhoto, author);
    denied(editorialPhoto, unrelated);
    denied(editorialPhoto, null);
    cms.editNews(id, admin(), input(group, List.of(editorialPhoto)));
    readable(editorialPhoto, author);
    denied(editorialPhoto, owner());
    denied(editorialPhoto, unrelated);
    denied(editorialPhoto, null);
    cms.visibility(id, admin(), true);
    readable(editorialPhoto, author);
    denied(editorialPhoto, unrelated);
    denied(editorialPhoto, null);
  }

  @Test
  void officialStatusRevokesFormerOrdinaryOwnerButKeepsAuthorAndAdminAccess() throws Exception {
    Accounts.User author = author();
    Accounts.User unrelated = author();
    UUID group = group();
    UUID photo = photo(author);
    UUID id = create(author, group, List.of(photo), false);
    denied(photo, owner());
    cms.visibility(id, admin(), true);
    communities.settings(group, admin(), 5, true);
    denied(photo, owner());
    denied(photo, unrelated);
    denied(photo, null);
    readable(photo, author);
    readable(photo, admin());
  }
}
