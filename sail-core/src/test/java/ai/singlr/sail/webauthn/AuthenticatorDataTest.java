/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.webauthn;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.security.KeyPairGenerator;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import org.junit.jupiter.api.Test;

class AuthenticatorDataTest {

  private static final byte[] RP_ID_HASH = new byte[32];

  static {
    for (var i = 0; i < 32; i++) {
      RP_ID_HASH[i] = (byte) i;
    }
  }

  private static byte[] cat(byte[]... parts) {
    var out = new ByteArrayOutputStream();
    for (var p : parts) {
      out.writeBytes(p);
    }
    return out.toByteArray();
  }

  private static byte[] be16(int v) {
    return new byte[] {(byte) (v >> 8), (byte) v};
  }

  private static byte[] be32(long v) {
    return new byte[] {(byte) (v >> 24), (byte) (v >> 16), (byte) (v >> 8), (byte) v};
  }

  private static byte[] fixed32(BigInteger v) {
    var raw = v.toByteArray();
    var out = new byte[32];
    if (raw.length > 32) {
      System.arraycopy(raw, raw.length - 32, out, 0, 32);
    } else {
      System.arraycopy(raw, 0, out, 32 - raw.length, raw.length);
    }
    return out;
  }

  /** CBOR encoding of a COSE EC2/P-256/ES256 key from the given 32-byte coordinates. */
  private static byte[] coseEc2(byte[] x, byte[] y) {
    return cat(
        new byte[] {(byte) 0xa5, 0x01, 0x02, 0x03, 0x26, 0x20, 0x01, 0x21, 0x58, 0x20},
        x,
        new byte[] {0x22, 0x58, 0x20},
        y);
  }

  private static ECPublicKey newEcKey() throws Exception {
    var kpg = KeyPairGenerator.getInstance("EC");
    kpg.initialize(new ECGenParameterSpec("secp256r1"));
    return (ECPublicKey) kpg.generateKeyPair().getPublic();
  }

  @Test
  void parsesAssertionDataWithoutAttestedCredential() {
    var data = cat(RP_ID_HASH, new byte[] {0x05}, be32(7)); // UP | UV
    var ad = AuthenticatorData.parse(data);

    assertArrayEquals(RP_ID_HASH, ad.rpIdHash());
    assertTrue(ad.userPresent());
    assertTrue(ad.userVerified());
    assertEquals(7, ad.signCount());
    assertFalse(ad.hasAttestedCredentialData());
    assertNull(ad.attestedCredential());
  }

  @Test
  void parsesRegistrationDataWithAttestedCredential() throws Exception {
    var key = newEcKey();
    var x = fixed32(key.getW().getAffineX());
    var y = fixed32(key.getW().getAffineY());
    var credId = new byte[] {10, 11, 12, 13};
    var data =
        cat(
            RP_ID_HASH,
            new byte[] {0x45}, // UP | UV | AT
            be32(1),
            new byte[16], // aaguid
            be16(credId.length),
            credId,
            coseEc2(x, y));

    var ad = AuthenticatorData.parse(data);
    assertTrue(ad.hasAttestedCredentialData());
    var attested = ad.attestedCredential();
    assertNotNull(attested);
    assertArrayEquals(credId, attested.credentialId());
    assertEquals(CoseKey.ES256, attested.publicKey().algorithm());
    assertArrayEquals(key.getEncoded(), attested.publicKey().publicKey().getEncoded());
    // the retained COSE wire bytes re-parse to the same key
    var reparsed = CoseKey.parse((java.util.Map<?, ?>) Cbor.decode(attested.publicKeyCose()));
    assertArrayEquals(key.getEncoded(), reparsed.publicKey().getEncoded());
  }

  @Test
  void flagAccessorsAreFalseWhenFlagsClear() {
    var ad = AuthenticatorData.parse(cat(RP_ID_HASH, new byte[] {0x00}, be32(0)));
    assertFalse(ad.userPresent());
    assertFalse(ad.userVerified());
    assertFalse(ad.backupEligible());
    assertFalse(ad.backupState());
    assertFalse(ad.hasAttestedCredentialData());
    assertFalse(ad.hasExtensions());
  }

  @Test
  void exposesBackupFlags() {
    var data = cat(RP_ID_HASH, new byte[] {0x1d}, be32(0)); // UP | UV | BE | BS
    var ad = AuthenticatorData.parse(data);
    assertTrue(ad.backupEligible());
    assertTrue(ad.backupState());
  }

  @Test
  void consumesExtensionsWhenEdFlagSet() {
    var data = cat(RP_ID_HASH, new byte[] {(byte) 0x85}, be32(0), new byte[] {(byte) 0xa0});
    var ad = AuthenticatorData.parse(data);
    assertTrue(ad.hasExtensions());
    assertNull(ad.attestedCredential());
  }

  @Test
  void parsesAttestedCredentialFollowedByExtensions() throws Exception {
    var key = newEcKey();
    var data =
        cat(
            RP_ID_HASH,
            new byte[] {(byte) 0xc5}, // UP | UV | AT | ED
            be32(2),
            new byte[16],
            be16(2),
            new byte[] {1, 2},
            coseEc2(fixed32(key.getW().getAffineX()), fixed32(key.getW().getAffineY())),
            new byte[] {(byte) 0xa0}); // empty extensions map
    var ad = AuthenticatorData.parse(data);
    assertTrue(ad.hasExtensions());
    assertNotNull(ad.attestedCredential());
  }

  @Test
  void rejectsTooShort() {
    assertThrows(IllegalArgumentException.class, () -> AuthenticatorData.parse(new byte[36]));
  }

  @Test
  void rejectsTruncatedAttestedData() {
    var data = cat(RP_ID_HASH, new byte[] {0x45}, be32(0)); // AT set, nothing follows
    assertThrows(IllegalArgumentException.class, () -> AuthenticatorData.parse(data));
  }

  @Test
  void rejectsInvalidCredentialIdLength() {
    var zeroLen = cat(RP_ID_HASH, new byte[] {0x45}, be32(0), new byte[16], be16(0));
    assertThrows(IllegalArgumentException.class, () -> AuthenticatorData.parse(zeroLen));

    var overrun = cat(RP_ID_HASH, new byte[] {0x45}, be32(0), new byte[16], be16(50), new byte[4]);
    assertThrows(IllegalArgumentException.class, () -> AuthenticatorData.parse(overrun));

    var tooLong = cat(RP_ID_HASH, new byte[] {0x45}, be32(0), new byte[16], be16(1024));
    assertThrows(IllegalArgumentException.class, () -> AuthenticatorData.parse(tooLong));
  }

  @Test
  void rejectsCredentialPublicKeyThatIsNotACborMap() {
    var data =
        cat(
            RP_ID_HASH,
            new byte[] {0x45},
            be32(0),
            new byte[16],
            be16(1),
            new byte[] {9},
            new byte[] {0x01}); // CBOR integer 1, not a map
    assertThrows(IllegalArgumentException.class, () -> AuthenticatorData.parse(data));
  }

  @Test
  void rejectsTrailingBytes() {
    var data = cat(RP_ID_HASH, new byte[] {0x05}, be32(0), new byte[] {0x00});
    assertThrows(IllegalArgumentException.class, () -> AuthenticatorData.parse(data));
  }
}
