/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.common;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Minted secrets are prefixed, hex-encoded, of the requested byte length, and unpredictable — the
 * one seam every store's token/credential generation shares.
 */
class SecretsTest {

  @Test
  void mintPrefixesAndHexEncodesThirtyTwoBytesByDefault() {
    var token = Secrets.mint("sail");

    assertTrue(token.startsWith("sail_"), "the prefix and separator lead the token");
    assertEquals(64, token.substring("sail_".length()).length(), "32 bytes render as 64 hex chars");
    assertTrue(token.matches("sail_[0-9a-f]{64}"), "the body is lowercase hex");
  }

  @Test
  void mintHonorsAnExplicitByteLength() {
    var token = Secrets.mint("wac", 16);

    assertTrue(token.matches("wac_[0-9a-f]{32}"), "16 bytes render as 32 hex chars");
  }

  @Test
  void hexEncodesWithoutAPrefix() {
    var value = Secrets.hex(16);

    assertTrue(value.matches("[0-9a-f]{32}"), "raw hex, no prefix");
  }

  @Test
  void everyMintIsUnique() {
    assertNotEquals(Secrets.mint("sail"), Secrets.mint("sail"));
  }
}
