package ru.uunit.news;

import java.awt.image.BufferedImage;
import java.nio.file.*;
import java.util.*;
import javax.imageio.ImageIO;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class Seeder implements CommandLineRunner {

  private final JdbcTemplate db;
  private final PasswordEncoder passwords;
  private final boolean seed;
  private final String adminPassword, ownerPassword;
  private final Path local;

  public Seeder(
    JdbcTemplate db,
    PasswordEncoder passwords,
    @Value("${app.seed}") boolean seed,
    @Value("${app.admin-password}") String adminPassword,
    @Value("${app.owner-password}") String ownerPassword,
    @Value("${app.local-directory}") String local
  ) {
    this.db = db;
    this.passwords = passwords;
    this.seed = seed;
    this.adminPassword = adminPassword;
    this.ownerPassword = ownerPassword;
    this.local = Path.of(local).toAbsolutePath().normalize();
  }

  @Override
  @Transactional
  public void run(String... args) throws Exception {
    if (!seed) return;
    UUID admin = user("uunit.admin", "Администрация УУНиТ", "ADMIN", adminPassword);
    UUID owner = user("club.owner", "Создатель IT-сообщества", "USER", ownerPassword);
    if (db.queryForObject("SELECT count(*) FROM communities", Integer.class) > 0) return;
    UUID official = UUID.randomUUID(),
      club = UUID.randomUUID(),
      sport = UUID.randomUUID();
    community(
      official,
      "УУНиТ",
      "Новости и события университета. Официальный паблик локальной демонстрации.",
      "УУ",
      "purple",
      "Университет",
      admin,
      true,
      20
    );
    community(
      club,
      "IT-сообщество",
      "Собираем команды, обсуждаем идеи и делимся опытом. Паблик модерирует его создатель.",
      "IT",
      "lime",
      "Технологии",
      owner,
      false,
      20
    );
    community(
      sport,
      "Спорт в кампусе",
      "Тренировки и студенческие соревнования. Здесь можно предложить свою спортивную новость.",
      "С",
      "mint",
      "Спорт",
      owner,
      false,
      10
    );
    UUID photo = UUID.randomUUID();
    Path source = local.getParent().resolve("public/images/campus.jpg");
    if (Files.exists(source)) {
      Files.createDirectories(local.resolve("media"));
      Files.copy(
        source,
        local.resolve("media/" + photo + ".jpg"),
        StandardCopyOption.REPLACE_EXISTING
      );
      BufferedImage img = ImageIO.read(source.toFile());
      db.update(
        "INSERT INTO media_assets(id,owner_id,kind,filename,width,height,size_bytes) VALUES(?,?,'image',?,?,?,?)",
        photo,
        admin,
        photo + ".jpg",
        img.getWidth(),
        img.getHeight(),
        Files.size(source)
      );
    } else photo = null;
    post(
      admin,
      official,
      "Добро пожаловать в Пульс университета",
      "Здесь появляются новости студенческой жизни, интересные события и истории людей нашего кампуса. Это стартовая публикация локальной версии платформы. Зарегистрируйтесь, подтвердите почту в тестовом почтовом ящике и предложите первую новость.",
      "Университет",
      photo,
      admin,
      true
    );
    post(
      owner,
      club,
      "Большие идеи начинаются с небольшой команды",
      "Соберите единомышленников и расскажите о своём проекте. В этом паблике заявки проверяет его создатель. После одобрения публикации появляются в общей ленте и в ленте подписчиков. Этот материал добавлен для знакомства с работающим приложением.",
      "Технологии",
      null,
      owner,
      true
    );
    post(
      owner,
      sport,
      "Встретимся на площадке: движение объединяет",
      "Расскажите о тренировке, соберите команду или поделитесь результатами соревнований. Добавляйте фотографии и видео, чтобы другим студентам было проще присоединиться. Это стартовый пример спортивной новости для локальной демонстрации.",
      "Спорт",
      null,
      owner,
      true
    );
    post(
      owner,
      official,
      "Предложение для университетской новостной ленты",
      "Пример заявки, ожидающей проверки администрацией университета. Администратор может одобрить этот материал или указать, что нужно исправить. После решения автор увидит уведомление в своём профиле.",
      "Университет",
      null,
      admin,
      false
    );
    post(
      admin,
      club,
      "Обсудим идеи следующей встречи IT-сообщества",
      "Эта заявка направлена в обычный новостной паблик. Решение может принять создатель IT-сообщества, войдя в свой аккаунт. Он управляет новостями своего паблика и не получает права на чужие сообщества или официальную ленту.",
      "Технологии",
      null,
      owner,
      false
    );
  }

  private UUID user(String username, String name, String role, String password) {
    List<UUID> ids = db.queryForList(
      "SELECT id FROM app_users WHERE username=?",
      UUID.class,
      username
    );
    if (!ids.isEmpty()) return ids.getFirst();
    UUID id = UUID.randomUUID();
    db.update(
      "INSERT INTO app_users(id,username,email,display_name,password_hash,role,verified) VALUES(?,?,?,?,?,?,TRUE)",
      id,
      username,
      username + "@local.test",
      name,
      passwords.encode(password),
      role
    );
    return id;
  }

  private void community(
    UUID id,
    String name,
    String description,
    String initials,
    String color,
    String category,
    UUID owner,
    boolean official,
    int limit
  ) {
    db.update(
      "INSERT INTO communities(id,name,description,initials,color,category,owner_id,official,daily_limit) VALUES(?,?,?,?,?,?,?,?,?)",
      id,
      name,
      description,
      initials,
      color,
      category,
      owner,
      official,
      limit
    );
  }

  private void post(
    UUID author,
    UUID community,
    String title,
    String text,
    String category,
    UUID media,
    UUID reviewer,
    boolean published
  ) {
    UUID id = UUID.randomUUID(),
      revision = UUID.randomUUID();
    db.update("INSERT INTO news(id,author_id,community_id) VALUES(?,?,?)", id, author, community);
    db.update(
      "INSERT INTO news_revisions(id,news_id,title,body,category,status,reviewer_id) VALUES(?,?,?,?,?,?,?)",
      revision,
      id,
      title,
      text,
      category,
      published ? "published" : "pending",
      published ? reviewer : null
    );
    if (media != null) db.update(
      "INSERT INTO revision_media(revision_id,media_id,sort_order) VALUES(?,?,0)",
      revision,
      media
    );
    db.update(
      "UPDATE news SET latest_revision_id=?,published_revision_id=?,published_at=? WHERE id=?",
      revision,
      published ? revision : null,
      published ? new java.sql.Timestamp(System.currentTimeMillis()) : null,
      id
    );
  }
}
