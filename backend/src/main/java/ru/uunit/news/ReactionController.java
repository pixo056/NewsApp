package ru.uunit.news;

import java.util.UUID;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/news/{id}")
public class ReactionController {

  record Input(String kind) {}

  private final ReactionService reactions;
  private final Accounts accounts;

  public ReactionController(ReactionService reactions, Accounts accounts) {
    this.reactions = reactions;
    this.accounts = accounts;
  }

  @GetMapping("/reactions")
  ReactionService.Summary summary(@PathVariable UUID id, Authentication auth) {
    return reactions.summary(id, accounts.current(auth));
  }

  @PutMapping("/reaction")
  ReactionService.Summary set(
    @PathVariable UUID id,
    @RequestBody(required = false) Input input,
    Authentication auth
  ) {
    return reactions.set(id, accounts.require(auth), input == null ? "like" : input.kind());
  }

  @DeleteMapping("/reaction")
  ReactionService.Summary remove(@PathVariable UUID id, Authentication auth) {
    return reactions.remove(id, accounts.require(auth));
  }
}
