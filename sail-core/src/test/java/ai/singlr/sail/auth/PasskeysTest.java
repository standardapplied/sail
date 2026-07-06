/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

import ai.singlr.sail.store.WebauthnCredentialStore.Credential;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PasskeysTest {

  private static final byte[] ICLOUD_KEYCHAIN = aaguid("fbfc3007-154e-4ecc-8c0b-6e020557d7bd");

  private static byte[] aaguid(String uuid) {
    var parsed = UUID.fromString(uuid);
    return ByteBuffer.allocate(16)
        .putLong(parsed.getMostSignificantBits())
        .putLong(parsed.getLeastSignificantBits())
        .array();
  }

  private static Credential credential(String id, String label) {
    return new Credential(
        id.getBytes(StandardCharsets.UTF_8),
        "fde-1",
        new byte[] {1},
        -7,
        0,
        null,
        false,
        false,
        label,
        "2026-07-06T10:15:30Z",
        null);
  }

  @Test
  void encodeIdIsUrlSafeWithoutPadding() {
    assertEquals("_-8", Passkeys.encodeId(new byte[] {(byte) 0xff, (byte) 0xef}));
  }

  @Test
  void shortIdTruncatesLongIdsAndKeepsShortOnes() {
    assertEquals("AAAAAAAAAAAA", Passkeys.shortId(new byte[12]));
    assertEquals(12, Passkeys.shortId(new byte[32]).length());
    assertEquals(Passkeys.encodeId(new byte[3]), Passkeys.shortId(new byte[3]));
  }

  @Test
  void resolvesUniquePrefixToMatch() {
    var credentials = List.of(credential("alpha", "a"), credential("bravo", "b"));
    var prefix = Passkeys.encodeId("alpha".getBytes(StandardCharsets.UTF_8)).substring(0, 3);
    var match =
        assertInstanceOf(Passkeys.Match.class, Passkeys.resolveByPrefix(credentials, prefix));
    assertEquals("a", match.credential().label());
  }

  @Test
  void resolvesFullIdToMatch() {
    var credentials = List.of(credential("alpha", "a"));
    var full = Passkeys.encodeId("alpha".getBytes(StandardCharsets.UTF_8));
    assertInstanceOf(Passkeys.Match.class, Passkeys.resolveByPrefix(credentials, full));
  }

  @Test
  void unknownPrefixResolvesToNotFound() {
    var credentials = List.of(credential("alpha", "a"));
    assertInstanceOf(Passkeys.NotFound.class, Passkeys.resolveByPrefix(credentials, "zzzz"));
  }

  @Test
  void sharedPrefixResolvesToAmbiguousWithAllCandidates() {
    var credentials = List.of(credential("alpha-one", "a"), credential("alpha-two", "b"));
    var ambiguous =
        assertInstanceOf(
            Passkeys.Ambiguous.class,
            Passkeys.resolveByPrefix(
                credentials, Passkeys.encodeId("alpha".getBytes(StandardCharsets.UTF_8))));
    assertEquals(2, ambiguous.candidates().size());
  }

  @Test
  void blankPrefixIsRejected() {
    var credentials = List.of(credential("alpha", "a"));
    assertThrows(IllegalArgumentException.class, () -> Passkeys.resolveByPrefix(credentials, "  "));
    assertThrows(IllegalArgumentException.class, () -> Passkeys.resolveByPrefix(credentials, null));
  }

  @Test
  void wellKnownAaguidMapsToAuthenticatorName() {
    assertEquals("iCloud Keychain", Passkeys.authenticatorName(ICLOUD_KEYCHAIN));
    assertEquals(
        "YubiKey 5", Passkeys.authenticatorName(aaguid("ee882879-721c-4913-9775-3dfcce97072a")));
  }

  @Test
  void unknownNullAndMalformedAaguidsFallBackToPasskey() {
    assertEquals("passkey", Passkeys.authenticatorName(new byte[16]));
    assertEquals("passkey", Passkeys.authenticatorName(null));
    assertEquals("passkey", Passkeys.authenticatorName(new byte[4]));
  }

  @Test
  void defaultLabelJoinsAuthenticatorNameAndEnrollmentDate() {
    assertEquals(
        "iCloud Keychain · 2026-07-06",
        Passkeys.defaultLabel(ICLOUD_KEYCHAIN, Instant.parse("2026-07-06T23:59:59Z")));
    assertEquals(
        "passkey · 2026-01-02", Passkeys.defaultLabel(null, Instant.parse("2026-01-02T00:00:00Z")));
  }

  @Test
  void displayLabelPrefersStoredLabel() {
    assertEquals("uday's macbook", Passkeys.displayLabel(credential("alpha", "uday's macbook")));
  }

  @Test
  void displayLabelFallsBackToDefaultForUnlabeledRows() {
    assertEquals("passkey · 2026-07-06", Passkeys.displayLabel(credential("alpha", null)));
    assertEquals("passkey · 2026-07-06", Passkeys.displayLabel(credential("alpha", " ")));
  }
}
