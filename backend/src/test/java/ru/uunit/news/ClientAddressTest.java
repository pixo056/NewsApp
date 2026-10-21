package ru.uunit.news;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

class ClientAddressTest {

  private MockHttpServletRequest request(String peer, String marker) {
    var request = new MockHttpServletRequest();
    request.setRemoteAddr(peer);
    if (marker != null) request.addHeader("X-NewsApp-Client-IP", marker);
    return request;
  }

  @Test
  void onlyLoopbackProxyCanSupplyClientIdentity() {
    assertEquals("192.0.2.20", ClientAddress.of(request("127.0.0.1", "192.0.2.20")));
    assertEquals("192.0.2.20", ClientAddress.of(request("::1", "::ffff:192.0.2.20")));
    assertEquals("192.0.2.30", ClientAddress.of(request("192.0.2.30", "192.0.2.20")));
    assertEquals("127.0.0.1", ClientAddress.of(request("127.0.0.1", "attacker.example")));
    assertEquals("127.0.0.1", ClientAddress.of(request("127.0.0.1", "192.0.2.20, 192.0.2.21")));
  }

  @Test
  void wifiVisitorsHaveSeparateAttemptLimits() {
    var rates = new RateLimiter();
    String first = "login:" + ClientAddress.of(request("127.0.0.1", "192.0.2.20"));
    String second = "login:" + ClientAddress.of(request("127.0.0.1", "192.0.2.21"));
    rates.check(first, 1, Duration.ofMinutes(15));
    assertThrows(ApiException.class, () -> rates.check(first, 1, Duration.ofMinutes(15)));
    assertDoesNotThrow(() -> rates.check(second, 1, Duration.ofMinutes(15)));
  }
}
