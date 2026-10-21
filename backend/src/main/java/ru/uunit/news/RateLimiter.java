package ru.uunit.news;

import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

@Component
public class RateLimiter {

  private record Window(long start, int count) {}

  private final ConcurrentHashMap<String, Window> windows = new ConcurrentHashMap<>();

  public void check(String key, int maximum, Duration duration) {
    long now = System.currentTimeMillis();
    Window updated = windows.compute(key, (k, old) ->
      old == null || now - old.start > duration.toMillis()
        ? new Window(now, 1)
        : new Window(old.start, old.count + 1)
    );
    if (windows.size() > 10000) windows
      .entrySet()
      .removeIf(e -> now - e.getValue().start > Duration.ofDays(1).toMillis());
    if (updated.count > maximum) throw new ApiException(
      HttpStatus.TOO_MANY_REQUESTS,
      "Слишком много попыток. Попробуйте позже."
    );
  }
}
