package ru.uunit.news;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.session.SessionRegistry;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class Accounts {

  public record User(
    UUID id,
    String username,
    String email,
    String name,
    String bio,
    String role,
    boolean verified,
    boolean canModerate
  ) {}

  public record Mail(
    UUID id,
    String recipient,
    String subject,
    String body,
    String actionUrl,
    String createdAt
  ) {}

  private final JdbcTemplate db;
  private final PasswordEncoder passwords;
  private final SessionRegistry sessions;
  private final boolean localMailbox;

  public boolean isLocalMailbox() {
    return localMailbox;
  }

  public Accounts(
    JdbcTemplate db,
    PasswordEncoder passwords,
    SessionRegistry sessions,
    @Value("${app.local-mailbox}") boolean localMailbox
  ) {
    this.db = db;
    this.passwords = passwords;
    this.sessions = sessions;
    this.localMailbox = localMailbox;
  }

  public User find(String username) {
    return db
      .query(
        """
        SELECT u.*, (u.role='ADMIN' OR EXISTS(SELECT 1 FROM communities c WHERE c.owner_id=u.id AND NOT c.official)) AS can_moderate
        FROM app_users u WHERE u.username=?
        """,
        (r, n) ->
          new User(
            r.getObject("id", UUID.class),
            r.getString("username"),
            r.getString("email"),
            r.getString("display_name"),
            r.getString("bio"),
            r.getString("role"),
            r.getBoolean("verified"),
            r.getBoolean("can_moderate")
          ),
        username
      )
      .stream()
      .findFirst()
      .orElseThrow(ApiException::missing);
  }

  public User current(Authentication authentication) {
    if (
      authentication == null ||
      !authentication.isAuthenticated() ||
      "anonymousUser".equals(authentication.getPrincipal())
    ) return null;
    if (
      Boolean.TRUE.equals(
        db.queryForObject(
          "SELECT blocked FROM app_users WHERE username=?",
          Boolean.class,
          authentication.getName()
        )
      )
    ) throw new ApiException(
      org.springframework.http.HttpStatus.UNAUTHORIZED,
      "Аккаунт заблокирован. Обратитесь к администрации платформы."
    );
    return find(authentication.getName());
  }

  public User require(Authentication authentication) {
    User user = current(authentication);
    if (user == null) throw new ApiException(
      org.springframework.http.HttpStatus.UNAUTHORIZED,
      "Войдите в аккаунт"
    );
    return user;
  }

  public void verified(User user) {
    if (!user.verified) throw ApiException.bad(
      "Подтвердите почту перед публикацией. Письмо находится в локальном почтовом ящике."
    );
  }

  public boolean admin(User user) {
    return user != null && "ADMIN".equals(user.role);
  }

  public void checkPassword(String password) {
    if (
      password == null ||
      password.codePointCount(0, password.length()) < 10 ||
      password.getBytes(StandardCharsets.UTF_8).length > 72
    ) throw ApiException.bad("Пароль: минимум 10 символов, максимум 72 байта UTF-8");
  }

  @Transactional
  public User register(String username, String email, String name, String password) {
    checkPassword(password);
    username = username.toLowerCase(java.util.Locale.ROOT).trim();
    email = email.toLowerCase(java.util.Locale.ROOT).trim();
    if (
      db.queryForObject(
        "SELECT count(*) FROM app_users WHERE username=? OR email=?",
        Integer.class,
        username,
        email
      ) > 0
    ) throw ApiException.bad("Логин или почта уже используются");
    UUID id = UUID.randomUUID();
    db.update(
      "INSERT INTO app_users(id,username,email,display_name,password_hash,role) VALUES(?,?,?,?,?,'USER')",
      id,
      username,
      email,
      name.trim(),
      passwords.encode(password)
    );
    sendToken(id, email, "verify");
    return find(username);
  }

  public String hash(String raw) {
    try {
      return HexFormat.of().formatHex(
        MessageDigest.getInstance("SHA-256").digest(raw.getBytes(StandardCharsets.UTF_8))
      );
    } catch (java.security.NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  @Transactional
  public void sendToken(UUID id, String email, String purpose) {
    if (!localMailbox) throw ApiException.bad("Почтовый транспорт не настроен");
    db.update(
      "UPDATE account_tokens SET used_at=now() WHERE user_id=? AND purpose=? AND used_at IS NULL",
      id,
      purpose
    );
    byte[] bytes = new byte[32];
    new SecureRandom().nextBytes(bytes);
    String raw = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    db.update(
      "INSERT INTO account_tokens(id,user_id,token_hash,purpose,expires_at) VALUES(?,?,?,?,?)",
      UUID.randomUUID(),
      id,
      hash(raw),
      purpose,
      Timestamp.from(
        Instant.now().plus("verify".equals(purpose) ? Duration.ofDays(1) : Duration.ofMinutes(30))
      )
    );
    String subject = "verify".equals(purpose)
      ? "Подтвердите почту в Пульсе"
      : "Восстановление доступа к Пульсу";
    String link = "/auth/" + ("verify".equals(purpose) ? "verify" : "reset") + "?token=" + raw;
    db.update(
      "INSERT INTO local_mail(id,recipient,subject,body,action_url) VALUES(?,?,?,?,?)",
      UUID.randomUUID(),
      email,
      subject,
      "Это локальное тестовое письмо. Ссылка одноразовая. Если вы не запрашивали действие, пропустите письмо.",
      link
    );
  }

  @Transactional
  public void forgot(String email) {
    db.query(
      "SELECT id,email FROM app_users WHERE email=? AND NOT blocked",
      (r, n) -> new Object[] { r.getObject("id", UUID.class), r.getString("email") },
      email.toLowerCase(java.util.Locale.ROOT).trim()
    )
      .stream()
      .findFirst()
      .ifPresent(row -> sendToken((UUID) row[0], (String) row[1], "reset"));
  }

  @Transactional
  public void consume(String token, String purpose, String password) {
    if (token == null || token.length() > 200) throw ApiException.bad("Неверная ссылка");
    if ("reset".equals(purpose)) checkPassword(password);
    UUID userId = db
      .query(
        "SELECT user_id FROM account_tokens WHERE token_hash=? AND purpose=? AND used_at IS NULL AND expires_at>now()",
        (r, n) -> r.getObject(1, UUID.class),
        hash(token),
        purpose
      )
      .stream()
      .findFirst()
      .orElseThrow(() -> ApiException.bad("Ссылка недействительна, уже использована или истекла"));
    // Lock accounts before tokens, matching CMS blocking and preventing a reset
    // that races with a block from restoring access or consuming a stale token.
    if (
      Boolean.TRUE.equals(
        db.queryForObject(
          "SELECT blocked FROM app_users WHERE id=? FOR UPDATE",
          Boolean.class,
          userId
        )
      )
    ) throw ApiException.forbidden();
    if (
      db
        .query(
          "SELECT user_id FROM account_tokens WHERE token_hash=? AND purpose=? AND user_id=? AND used_at IS NULL AND expires_at>now() FOR UPDATE",
          (r, n) -> r.getObject(1, UUID.class),
          hash(token),
          purpose,
          userId
        )
        .isEmpty()
    ) throw ApiException.bad("Ссылка недействительна, уже использована или истекла");
    db.update("UPDATE account_tokens SET used_at=now() WHERE token_hash=?", hash(token));
    if ("verify".equals(purpose)) db.update(
      "UPDATE app_users SET verified=TRUE WHERE id=?",
      userId
    );
    else {
      db.update(
        "UPDATE app_users SET password_hash=? WHERE id=?",
        passwords.encode(password),
        userId
      );
      String username = db.queryForObject(
        "SELECT username FROM app_users WHERE id=?",
        String.class,
        userId
      );
      endSessions(username);
    }
  }

  public void endSessions(String username) {
    for (Object principal : sessions.getAllPrincipals())
      if (
        principal instanceof UserDetails details && details.getUsername().equals(username)
      ) sessions.getAllSessions(principal, false).forEach(s -> s.expireNow());
  }

  public List<Mail> mailbox() {
    if (!localMailbox) throw ApiException.missing();
    return db.query("SELECT * FROM local_mail ORDER BY created_at DESC LIMIT 100", (r, n) ->
      new Mail(
        r.getObject("id", UUID.class),
        r.getString("recipient"),
        r.getString("subject"),
        r.getString("body"),
        r.getString("action_url"),
        r.getTimestamp("created_at").toInstant().toString()
      )
    );
  }
}
