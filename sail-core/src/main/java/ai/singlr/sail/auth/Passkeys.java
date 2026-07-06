/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.auth;

import ai.singlr.sail.common.Strings;
import ai.singlr.sail.store.WebauthnCredentialStore.Credential;
import java.nio.ByteBuffer;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Pure helpers for managing an FDE's registered passkeys. Naming: a label tells apart multiple
 * credentials of the same FDE (never the FDE's own name), so when enrollment skips the label prompt
 * the default is device-descriptive — the authenticator's well-known AAGUID name plus the
 * enrollment date, e.g. {@code "iCloud Keychain · 2026-07-06"}. Selection: {@link #resolveByPrefix}
 * resolves a credential from a git-style unambiguous id prefix and reports ambiguity rather than
 * guessing.
 */
public final class Passkeys {

  private static final Base64.Encoder URL = Base64.getUrlEncoder().withoutPadding();
  private static final int SHORT_ID_LENGTH = 12;
  private static final String UNKNOWN_AUTHENTICATOR = "passkey";

  private static final Map<String, String> WELL_KNOWN_AAGUIDS =
      Map.ofEntries(
          Map.entry("fbfc3007-154e-4ecc-8c0b-6e020557d7bd", "iCloud Keychain"),
          Map.entry("dd4ec289-e01d-41c9-bb89-70fa845d4bf2", "iCloud Keychain (Managed)"),
          Map.entry("ea9b8d66-4d01-1d21-3ce4-b6b48cb575d4", "Google Password Manager"),
          Map.entry("adce0002-35bc-c60a-648b-0b25f1f05503", "Chrome on Mac"),
          Map.entry("08987058-cadc-4b81-b6e1-30de50dcbe96", "Windows Hello"),
          Map.entry("9ddd1817-af5a-4672-a2b9-3e3dd95000a9", "Windows Hello"),
          Map.entry("6028b017-b1d4-4c02-b4b3-afcdafc96bb2", "Windows Hello"),
          Map.entry("bada5566-a7aa-401f-bd96-45619a55120d", "1Password"),
          Map.entry("d548826e-79b4-db40-a3d8-11116f7e8349", "Bitwarden"),
          Map.entry("531126d6-e717-415c-9320-3d9aa6981239", "Dashlane"),
          Map.entry("ee882879-721c-4913-9775-3dfcce97072a", "YubiKey 5"),
          Map.entry("cb69481e-8ff7-4039-93ec-0a2729a154a8", "YubiKey 5"),
          Map.entry("fa2b99dc-9e39-4257-8f92-4a30d23c4118", "YubiKey 5 NFC"),
          Map.entry("2fc0579f-8113-47ea-b116-bb5a8db9202a", "YubiKey 5 NFC"));

  private Passkeys() {}

  /** How a credential id prefix resolved against an FDE's registered passkeys. */
  public sealed interface Resolution permits Match, NotFound, Ambiguous {}

  /** Exactly one credential matched. */
  public record Match(Credential credential) implements Resolution {}

  /** No credential matched. */
  public record NotFound() implements Resolution {}

  /** More than one credential matched; a longer prefix is needed. */
  public record Ambiguous(List<Credential> candidates) implements Resolution {}

  /** The credential id in its canonical CLI form: base64url without padding. */
  public static String encodeId(byte[] credentialId) {
    return URL.encodeToString(credentialId);
  }

  /** A display-length prefix of the encoded credential id, long enough to be unambiguous. */
  public static String shortId(byte[] credentialId) {
    var encoded = encodeId(credentialId);
    return encoded.length() <= SHORT_ID_LENGTH ? encoded : encoded.substring(0, SHORT_ID_LENGTH);
  }

  /** Resolves a non-blank id prefix against {@code credentials}, git-style. */
  public static Resolution resolveByPrefix(List<Credential> credentials, String prefix) {
    if (Strings.isBlank(prefix)) {
      throw new IllegalArgumentException("Credential id prefix must not be blank.");
    }
    var matches =
        credentials.stream()
            .filter(credential -> encodeId(credential.credentialId()).startsWith(prefix))
            .toList();
    return switch (matches.size()) {
      case 0 -> new NotFound();
      case 1 -> new Match(matches.getFirst());
      default -> new Ambiguous(matches);
    };
  }

  /** The authenticator's well-known name for {@code aaguid}, or {@code "passkey"}. */
  public static String authenticatorName(byte[] aaguid) {
    if (aaguid == null || aaguid.length != 16) {
      return UNKNOWN_AUTHENTICATOR;
    }
    var buffer = ByteBuffer.wrap(aaguid);
    var uuid = new UUID(buffer.getLong(), buffer.getLong());
    return WELL_KNOWN_AAGUIDS.getOrDefault(uuid.toString(), UNKNOWN_AUTHENTICATOR);
  }

  /** The label stored when enrollment skips the prompt: authenticator name plus enrollment date. */
  public static String defaultLabel(byte[] aaguid, Instant enrolledAt) {
    return authenticatorName(aaguid) + " · " + enrolledAt.toString().substring(0, 10);
  }

  /** The stored label, or the device-descriptive default for rows enrolled before labels. */
  public static String displayLabel(Credential credential) {
    return Strings.isBlank(credential.label())
        ? defaultLabel(credential.aaguid(), Instant.parse(credential.createdAt()))
        : credential.label();
  }
}
