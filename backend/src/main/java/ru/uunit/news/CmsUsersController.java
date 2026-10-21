package ru.uunit.news;

import java.util.Map;
import java.util.UUID;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/cms/users")
public class CmsUsersController {

  private final Accounts accounts;
  private final CmsUsersService users;

  public CmsUsersController(Accounts accounts, CmsUsersService users) {
    this.accounts = accounts;
    this.users = users;
  }

  @GetMapping
  CmsUsersService.Page list(
    Authentication auth,
    @RequestParam(defaultValue = "1") int page,
    @RequestParam(defaultValue = "25") int perPage,
    @RequestParam(defaultValue = "") String q,
    @RequestParam(defaultValue = "createdAt") String sort,
    @RequestParam(defaultValue = "DESC") String order,
    @RequestParam(required = false) Boolean blocked,
    @RequestParam(required = false) String role
  ) {
    return users.list(accounts.require(auth), page, perPage, q, sort, order, blocked, role);
  }

  @GetMapping("/{id}")
  CmsUsersService.UserRecord detail(@PathVariable UUID id, Authentication auth) {
    return users.detail(accounts.require(auth), id);
  }

  @PatchMapping("/{id}")
  CmsUsersService.UserRecord update(
    @PathVariable UUID id,
    @RequestBody Map<String, Object> body,
    Authentication auth
  ) {
    return users.update(accounts.require(auth), id, body);
  }
}
