/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.webauthn;

import java.util.Arrays;
import java.util.Map;

/**
 * Authenticator data (W3C WebAuthn §6.1) — the fixed binary structure an authenticator returns in
 * both registration and assertion: the SHA-256 hash of the RP ID, a flags byte, a signature
 * counter, and, when the AT flag is set (registration), the attested credential data carrying the
 * new credential id and its COSE public key. Only the embedded COSE key and the optional extensions
 * map are CBOR; the rest is parsed as fixed-offset bytes. Malformed or trailing data is rejected.
 */
public record AuthenticatorData(
    byte[] rpIdHash, int flags, long signCount, AttestedCredential attestedCredential) {

  /**
   * The credential registered in a ceremony with the AT flag set. {@code publicKeyCose} is the
   * exact COSE_Key wire encoding, retained so it can be stored verbatim and re-parsed at assertion.
   */
  public record AttestedCredential(
      byte[] aaguid, byte[] credentialId, CoseKey publicKey, byte[] publicKeyCose) {}

  private static final int FLAG_UP = 0x01;
  private static final int FLAG_UV = 0x04;
  private static final int FLAG_BE = 0x08;
  private static final int FLAG_BS = 0x10;
  private static final int FLAG_AT = 0x40;
  private static final int FLAG_ED = 0x80;

  private static final int RP_ID_HASH_LEN = 32;
  private static final int FIXED_LEN = 37; // rpIdHash(32) + flags(1) + signCount(4)
  private static final int AAGUID_LEN = 16;
  private static final int MAX_CREDENTIAL_ID_LEN = 1023;

  public boolean userPresent() {
    return (flags & FLAG_UP) != 0;
  }

  public boolean userVerified() {
    return (flags & FLAG_UV) != 0;
  }

  public boolean backupEligible() {
    return (flags & FLAG_BE) != 0;
  }

  public boolean backupState() {
    return (flags & FLAG_BS) != 0;
  }

  public boolean hasAttestedCredentialData() {
    return (flags & FLAG_AT) != 0;
  }

  public boolean hasExtensions() {
    return (flags & FLAG_ED) != 0;
  }

  /** Parses raw authenticator data, including attested credential data when the AT flag is set. */
  public static AuthenticatorData parse(byte[] data) {
    if (data.length < FIXED_LEN) {
      throw new IllegalArgumentException("Authenticator data too short: " + data.length);
    }
    var rpIdHash = Arrays.copyOf(data, RP_ID_HASH_LEN);
    var flags = data[32] & 0xff;
    var signCount = uint32(data, 33);

    var offset = FIXED_LEN;
    AttestedCredential attested = null;
    if ((flags & FLAG_AT) != 0) {
      if (data.length < offset + AAGUID_LEN + 2) {
        throw new IllegalArgumentException("Attested credential data truncated");
      }
      var aaguid = Arrays.copyOfRange(data, offset, offset + AAGUID_LEN);
      offset += AAGUID_LEN;
      var credIdLen = ((data[offset] & 0xff) << 8) | (data[offset + 1] & 0xff);
      offset += 2;
      if (credIdLen < 1 || credIdLen > MAX_CREDENTIAL_ID_LEN || data.length < offset + credIdLen) {
        throw new IllegalArgumentException("Invalid credential id length: " + credIdLen);
      }
      var credentialId = Arrays.copyOfRange(data, offset, offset + credIdLen);
      offset += credIdLen;

      var slice = Arrays.copyOfRange(data, offset, data.length);
      var reader = new Cbor(slice);
      if (!(reader.readValue() instanceof Map<?, ?> coseMap)) {
        throw new IllegalArgumentException("Credential public key is not a CBOR map");
      }
      var publicKeyCose = Arrays.copyOf(slice, reader.position());
      attested =
          new AttestedCredential(aaguid, credentialId, CoseKey.parse(coseMap), publicKeyCose);
      offset += reader.position();
    }

    if ((flags & FLAG_ED) != 0) {
      var reader = new Cbor(Arrays.copyOfRange(data, offset, data.length));
      reader.readValue(); // extensions: structurally validated, not used
      offset += reader.position();
    }

    if (offset != data.length) {
      throw new IllegalArgumentException("Trailing bytes after authenticator data");
    }
    return new AuthenticatorData(rpIdHash, flags, signCount, attested);
  }

  private static long uint32(byte[] data, int offset) {
    return ((data[offset] & 0xffL) << 24)
        | ((data[offset + 1] & 0xffL) << 16)
        | ((data[offset + 2] & 0xffL) << 8)
        | (data[offset + 3] & 0xffL);
  }
}
