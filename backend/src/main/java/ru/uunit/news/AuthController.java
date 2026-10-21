package ru.uunit.news;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Duration;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api")
public class AuthController {

  private final Accounts accounts;
  private final RateLimiter rates;
  private final JdbcTemplate db;

  public AuthController(Accounts accounts, RateLimiter rates, JdbcTemplate db) {
    this.accounts = accounts;
    this.rates = rates;
    this.db = db;
  }

  record Registration(
    @NotBlank
    @Pattern(
      regexp = "[a-zA-Z0-9_.-]{3,32}",
      message = "Логин: 3–32 латинские буквы, цифры, точка, дефис или подчёркивание"
    )
    String username,
    @NotBlank @Email(message = "Укажите корректную почту") @Size(max = 254) String email,
    @NotBlank(message = "Укажите имя") @Size(max = 80) String name,
    @NotBlank String password
  ) {}

  record EmailBody(@NotBlank @Email @Size(max = 254) String email) {}

  record TokenBody(@NotBlank @Size(max = 200) String token, String password) {}

  record Profile(@NotBlank @Size(max = 80) String name, @Size(max = 500) String bio) {}

  record Session(Accounts.User user, boolean localMailbox) {}

  @GetMapping("/health")
  Map<String, String> health() {
    db.queryForObject("SELECT 1", Integer.class);
    return Map.of(
      "status",
      "ok",
      "java",
      System.getProperty("java.version"),
      "application",
      "newsapp"
    );
  }

  @GetMapping("/auth/csrf")
  Map<String, String> csrf(CsrfToken token) {
    return Map.of("token", token.getToken(), "headerName", token.getHeaderName());
  }

  @GetMapping("/auth/me")
  Session me(Authentication auth, HttpServletRequest request) {
    return new Session(
      accounts.current(auth),
      accounts.isLocalMailbox() && localMailboxAccess(request)
    );
  }

  @PostMapping("/auth/register")
  Map<String, String> register(@Valid @RequestBody Registration body, HttpServletRequest request) {
    rates.check("register:" + request.getRemoteAddr(), 10, Duration.ofHours(1));
    accounts.register(body.username, body.email, body.name, body.password);
    return Map.of(
      "message",
      "Аккаунт создан. Войдите и подтвердите почту через локальный почтовый ящик."
    );
  }

  @PostMapping("/auth/forgot-password")
  Map<String, String> forgot(@Valid @RequestBody EmailBody body, HttpServletRequest request) {
    rates.check("reset:" + request.getRemoteAddr(), 15, Duration.ofMinutes(15));
    accounts.forgot(body.email);
    return Map.of(
      "message",
      "Если аккаунт существует, письмо сохранено в локальном почтовом ящике."
    );
  }

  @PostMapping("/auth/verify-email")
  Map<String, String> verify(@Valid @RequestBody TokenBody body) {
    accounts.consume(body.token, "verify", null);
    return Map.of("message", "Почта подтверждена");
  }

  @PostMapping("/auth/reset-password")
  Map<String, String> reset(@Valid @RequestBody TokenBody body) {
    accounts.consume(body.token, "reset", body.password);
    return Map.of("message", "Пароль изменён. Войдите заново.");
  }

  @PostMapping("/auth/resend-verification")
  Map<String, String> resend(Authentication auth) {
    Accounts.User user = accounts.require(auth);
    rates.check("verify:" + user.id(), 3, Duration.ofMinutes(15));
    if (!user.verified()) accounts.sendToken(user.id(), user.email(), "verify");
    return Map.of("message", "Письмо сохранено в локальном почтовом ящике");
  }

  @PatchMapping("/me")
  Accounts.User profile(@Valid @RequestBody Profile body, Authentication auth) {
    Accounts.User user = accounts.require(auth);
    db.update(
      "UPDATE app_users SET display_name=?,bio=? WHERE id=?",
      body.name.trim(),
      body.bio == null ? "" : body.bio.trim(),
      user.id()
    );
    return accounts.find(user.username());
  }

  @GetMapping("/dev/mailbox")
  Object mailbox(HttpServletRequest request) {
    if (!localMailboxAccess(request)) throw ApiException.forbidden();
    return accounts.mailbox();
  }

  private boolean localMailboxAccess(HttpServletRequest request) {
    String remote = request.getRemoteAddr();
    return (
      (remote.equals("127.0.0.1") || remote.equals("::1") || remote.equals("0:0:0:0:0:0:0:1")) &&
      !"remote".equals(request.getHeader("X-NewsApp-Mailbox"))
    );
  }
}
