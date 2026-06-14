/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.engine;

import ai.singlr.sail.common.Strings;
import java.net.Inet4Address;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.util.Set;

/**
 * Detects the server's primary IPv4 address from network interfaces. Pure utility — no shell
 * commands, no side effects. Skips loopback, link-local, and container bridge interfaces.
 */
public final class NetworkDetector {

  private static final Set<String> SKIP_PREFIXES =
      Set.of("lo", "incusbr", "docker", "veth", "br-", "podman", "virbr", "lxd");

  private NetworkDetector() {}

  /**
   * Detects the primary non-loopback IPv4 address of the server. Skips container bridges (incusbr,
   * docker, veth, podman, etc.) and link-local addresses (169.254.x.x).
   *
   * @return the detected IPv4 address string, or null if none found
   */
  public static String detectPrimaryIpv4() throws SocketException {
    var ifaces = NetworkInterface.getNetworkInterfaces();
    if (ifaces == null) {
      return null;
    }

    String fallback = null;

    while (ifaces.hasMoreElements()) {
      var iface = ifaces.nextElement();
      if (iface.isLoopback() || !iface.isUp()) {
        continue;
      }
      if (shouldSkip(iface.getName())) {
        continue;
      }

      var addrs = iface.getInetAddresses();
      while (addrs.hasMoreElements()) {
        var addr = addrs.nextElement();
        if (!(addr instanceof Inet4Address ipv4)) {
          continue;
        }
        if (ipv4.isLoopbackAddress() || ipv4.isLinkLocalAddress()) {
          continue;
        }
        if (ipv4.isSiteLocalAddress()) {
          return ipv4.getHostAddress();
        }
        if (fallback == null) {
          fallback = ipv4.getHostAddress();
        }
      }
    }

    return fallback;
  }

  /**
   * Validates that a string looks like a valid IPv4 address.
   *
   * @return true if the string is a valid dotted-quad IPv4 address
   */
  public static boolean isValidIpv4(String ip) {
    if (Strings.isBlank(ip)) {
      return false;
    }
    var parts = ip.split("\\.");
    if (parts.length != 4) {
      return false;
    }
    for (var part : parts) {
      try {
        var n = Integer.parseInt(part);
        if (n < 0 || n > 255) {
          return false;
        }
      } catch (NumberFormatException e) {
        return false;
      }
    }
    return true;
  }

  private static boolean shouldSkip(String name) {
    for (var prefix : SKIP_PREFIXES) {
      if (name.startsWith(prefix)) {
        return true;
      }
    }
    return false;
  }
}
