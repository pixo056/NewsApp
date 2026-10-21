package ru.uunit.news;

import jakarta.servlet.http.HttpServletRequest;
import java.net.InetAddress;

/** The local Vite proxy replaces this header with its socket peer, never a browser header. */
final class ClientAddress {

  private ClientAddress() {}

  static String of(HttpServletRequest request) {
    String remote = request.getRemoteAddr();
    try {
      InetAddress peer = InetAddress.ofLiteral(remote);
      String forwarded = request.getHeader("X-NewsApp-Client-IP");
      if (peer.isLoopbackAddress() && forwarded != null) {
        return InetAddress.ofLiteral(forwarded).getHostAddress();
      }
      return peer.getHostAddress();
    } catch (IllegalArgumentException e) {
      // Malformed markers never become a separate rate-limit bucket or trigger DNS.
      return remote;
    }
  }
}
