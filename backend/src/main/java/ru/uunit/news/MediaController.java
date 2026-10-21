package ru.uunit.news;

import java.time.Duration;
import java.util.UUID;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/media")
public class MediaController {

  private final MediaService media;
  private final Accounts accounts;
  private final RateLimiter rates;

  public MediaController(MediaService media, Accounts accounts, RateLimiter rates) {
    this.media = media;
    this.accounts = accounts;
    this.rates = rates;
  }

  @PostMapping
  Object upload(@RequestParam MultipartFile file, Authentication authentication)
    throws java.io.IOException {
    Accounts.User user = accounts.require(authentication);
    rates.check("upload:" + user.id(), 30, Duration.ofHours(1));
    return media.upload(file, user);
  }

  @GetMapping("/{id}")
  Object read(@PathVariable UUID id, Authentication auth) {
    return media.read(id, false, accounts.current(auth));
  }

  @GetMapping("/{id}/poster")
  Object poster(@PathVariable UUID id, Authentication auth) {
    return media.read(id, true, accounts.current(auth));
  }

  @DeleteMapping("/{id}")
  void remove(@PathVariable UUID id, Authentication auth) throws java.io.IOException {
    media.removeUnused(id, accounts.require(auth));
  }
}
