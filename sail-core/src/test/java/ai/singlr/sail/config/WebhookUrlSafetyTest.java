/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.config;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class WebhookUrlSafetyTest {

  @Test
  void requireSafeAcceptsPublicHttps() {
    assertDoesNotThrow(() -> WebhookUrlSafety.requireSafe("https://ntfy.sh/topic"));
  }

  @Test
  void requireSafeRejectsNonHttpScheme() {
    var ex =
        assertThrows(
            IllegalArgumentException.class,
            () -> WebhookUrlSafety.requireSafe("file:///etc/passwd"));
    assertTrue(ex.getMessage().contains("scheme"));
  }

  @Test
  void requireSafeRejectsMissingHost() {
    assertThrows(
        IllegalArgumentException.class, () -> WebhookUrlSafety.requireSafe("https:///nohost"));
  }

  @Test
  void requireSafeRejectsPrivateHost() {
    var ex =
        assertThrows(
            IllegalArgumentException.class,
            () -> WebhookUrlSafety.requireSafe("http://10.1.2.3/hook"));
    assertTrue(ex.getMessage().contains("Private"));
  }

  @Test
  void isPrivateHostFlagsLoopbackAndLocalNames() {
    assertTrue(WebhookUrlSafety.isPrivateHost("localhost"));
    assertTrue(WebhookUrlSafety.isPrivateHost("127.0.0.1"));
    assertTrue(WebhookUrlSafety.isPrivateHost("[::1]"));
    assertTrue(WebhookUrlSafety.isPrivateHost("::1"));
  }

  @Test
  void isPrivateHostFlagsRfc1918Ranges() {
    assertTrue(WebhookUrlSafety.isPrivateHost("10.0.0.1"));
    assertTrue(WebhookUrlSafety.isPrivateHost("192.168.1.1"));
    assertTrue(WebhookUrlSafety.isPrivateHost("172.16.0.1"));
    assertTrue(WebhookUrlSafety.isPrivateHost("172.31.255.255"));
  }

  @Test
  void isPrivateHostAllowsPublic172Octet() {
    assertFalse(WebhookUrlSafety.isPrivateHost("172.32.0.1"));
    assertFalse(WebhookUrlSafety.isPrivateHost("172.15.0.1"));
  }

  @Test
  void isPrivateHostFlagsCloudMetadataAndUnspecified() {
    assertTrue(WebhookUrlSafety.isPrivateHost("169.254.169.254"));
    assertTrue(WebhookUrlSafety.isPrivateHost("0.0.0.0"));
  }

  @Test
  void isPrivateHostFlagsIpv6UniqueLocalAndLinkLocal() {
    assertTrue(WebhookUrlSafety.isPrivateHost("fd00::1"));
    assertTrue(WebhookUrlSafety.isPrivateHost("fe80::1"));
  }

  @Test
  void isPrivateHostFlagsObfuscatedAndMappedAddresses() {
    assertTrue(WebhookUrlSafety.isPrivateHost("2130706433"), "decimal 127.0.0.1");
    assertTrue(WebhookUrlSafety.isPrivateHost("[::ffff:169.254.169.254]"), "IPv4-mapped metadata");
    assertTrue(WebhookUrlSafety.isPrivateHost("[::ffff:127.0.0.1]"), "IPv4-mapped loopback");
    assertTrue(WebhookUrlSafety.isPrivateHost("100.64.0.1"), "CGNAT 100.64/10");
    assertTrue(WebhookUrlSafety.isPrivateHost("169.254.1.1"), "full link-local /16");
  }

  @Test
  void isPrivateHostTreatsUnresolvableAsPrivate() {
    assertTrue(WebhookUrlSafety.isPrivateHost("no-such-host.invalid"));
  }
}
