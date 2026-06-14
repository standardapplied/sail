/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.config;

import ai.singlr.sail.common.Strings;
import java.net.InetAddress;
import java.net.URI;

/**
 * SSRF policy for outbound webhook URLs. Validation runs at config parse time (via {@link
 * Notifications}); the host check is also re-run at send time as defense-in-depth against
 * DNS-rebinding, since a name that resolved to a public address when the config was parsed may
 * resolve to a private one by the time the request is made.
 */
public final class WebhookUrlSafety {

  private WebhookUrlSafety() {}

  /**
   * Validates that {@code url} is an http(s) URL whose host does not target a private/internal
   * network. Throws {@link IllegalArgumentException} on any violation.
   */
  public static void requireSafe(String url) {
    URI uri;
    try {
      uri = URI.create(url);
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException(
          "Invalid notifications.url: '" + url + "'. Must be a valid URL.");
    }

    var scheme = uri.getScheme();
    if (scheme == null || (!scheme.equals("https") && !scheme.equals("http"))) {
      throw new IllegalArgumentException(
          "Invalid notifications.url scheme: '"
              + scheme
              + "'. Only https:// and http:// are allowed.");
    }

    var host = uri.getHost();
    if (Strings.isBlank(host)) {
      throw new IllegalArgumentException(
          "Invalid notifications.url: '" + url + "'. No hostname found.");
    }

    if (isPrivateHost(host)) {
      throw new IllegalArgumentException(
          "Invalid notifications.url: '"
              + url
              + "'. Private/internal network addresses are not allowed.");
    }
  }

  /**
   * Returns true if the hostname is, or resolves to, a private/internal address. The host is
   * resolved to its IP(s) and every resolved address is range-checked on its raw bytes, so a
   * public-looking name that resolves to an internal address — and obfuscated forms (decimal/hex
   * IPs, IPv4-mapped IPv6 like {@code ::ffff:169.254.169.254}) — are all caught. Resolution failure
   * is treated as unsafe (fail closed). Note: the connector re-resolves at send time, so the
   * send-time recheck remains the defense against DNS rebinding between this check and connect.
   */
  public static boolean isPrivateHost(String host) {
    var bare = host.toLowerCase();
    if (bare.startsWith("[") && bare.endsWith("]")) {
      bare = bare.substring(1, bare.length() - 1);
    }
    if (bare.equals("localhost")) {
      return true;
    }
    try {
      for (var addr : InetAddress.getAllByName(bare)) {
        if (isPrivateAddress(addr)) {
          return true;
        }
      }
      return false;
    } catch (Exception e) {
      return true;
    }
  }

  private static boolean isPrivateAddress(InetAddress addr) {
    if (addr.isLoopbackAddress()
        || addr.isLinkLocalAddress()
        || addr.isSiteLocalAddress()
        || addr.isAnyLocalAddress()
        || addr.isMulticastAddress()) {
      return true;
    }
    var bytes = addr.getAddress();
    if (bytes.length == 4) {
      return isPrivateV4(bytes);
    }
    if ((bytes[0] & 0xfe) == 0xfc) {
      return true;
    }
    if (isV4Mapped(bytes)) {
      return isPrivateV4(new byte[] {bytes[12], bytes[13], bytes[14], bytes[15]});
    }
    return false;
  }

  private static boolean isPrivateV4(byte[] b) {
    var o0 = b[0] & 0xff;
    var o1 = b[1] & 0xff;
    return o0 == 0
        || o0 == 10
        || o0 == 127
        || (o0 == 169 && o1 == 254)
        || (o0 == 172 && o1 >= 16 && o1 <= 31)
        || (o0 == 192 && o1 == 168)
        || (o0 == 100 && o1 >= 64 && o1 <= 127);
  }

  private static boolean isV4Mapped(byte[] b) {
    for (var i = 0; i < 10; i++) {
      if (b[i] != 0) {
        return false;
      }
    }
    return (b[10] & 0xff) == 0xff && (b[11] & 0xff) == 0xff;
  }
}
