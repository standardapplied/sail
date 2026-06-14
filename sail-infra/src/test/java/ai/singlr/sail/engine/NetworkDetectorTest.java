/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.engine;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class NetworkDetectorTest {

  @Test
  void detectReturnsNonNull() throws Exception {
    var ip = NetworkDetector.detectPrimaryIpv4();
    if (ip != null) {
      assertTrue(NetworkDetector.isValidIpv4(ip), "Detected IP should be valid: " + ip);
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"192.168.1.1", "10.0.0.1", "172.16.0.1", "255.255.255.255", "0.0.0.0"})
  void validIpv4Addresses(String ip) {
    assertTrue(NetworkDetector.isValidIpv4(ip));
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "",
        "not-an-ip",
        "192.168.1",
        "192.168.1.1.1",
        "256.1.1.1",
        "192.168.1.-1",
        "192.168.1.abc",
        "::1"
      })
  void invalidIpv4Addresses(String ip) {
    assertFalse(NetworkDetector.isValidIpv4(ip));
  }

  @Test
  void nullIpIsInvalid() {
    assertFalse(NetworkDetector.isValidIpv4(null));
  }

  @Test
  void blankIpIsInvalid() {
    assertFalse(NetworkDetector.isValidIpv4("   "));
  }
}
