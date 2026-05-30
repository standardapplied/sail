/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.api;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Base64;
import org.junit.jupiter.api.Test;

class TlsMaterialTest {

  // Self-signed P-256 cert + PKCS#8 key generated once with openssl (CN=sail-test), for tests only.
  private static final String CERT =
      """
      -----BEGIN CERTIFICATE-----
      MIIBfDCCASOgAwIBAgIUeG1w+UKlApbOBg/utRHEVzfEC5EwCgYIKoZIzj0EAwIw
      FDESMBAGA1UEAwwJc2FpbC10ZXN0MB4XDTI2MDUzMDE2MDAyNloXDTM2MDUyNzE2
      MDAyNlowFDESMBAGA1UEAwwJc2FpbC10ZXN0MFkwEwYHKoZIzj0CAQYIKoZIzj0D
      AQcDQgAEKYpeY7vIHGLtLU4z9XVwWQezNm38lWhOxcfyBIIKfokUZgNajzYO+0Mx
      r/gTaZ+469xRbf/cc1GUrP8SY10NAaNTMFEwHQYDVR0OBBYEFJAE7+TgKk8N+meT
      coR3lAHCohBdMB8GA1UdIwQYMBaAFJAE7+TgKk8N+meTcoR3lAHCohBdMA8GA1Ud
      EwEB/wQFMAMBAf8wCgYIKoZIzj0EAwIDRwAwRAIgdgWGN9kY14kVV8C05v3I+EU8
      qC3xkjG6Vk+K0FbLL8gCICBZaHh7zeKNpUsC2mlMEKW804XpY9WVZo56ilIQJ/d6
      -----END CERTIFICATE-----
      """;

  private static final String KEY =
      """
      -----BEGIN PRIVATE KEY-----
      MIGHAgEAMBMGByqGSM49AgEGCCqGSM49AwEHBG0wawIBAQQgKJoiBcmLFdgoQKMV
      DuihOq8om5cCiAyjwzwut2gRENGhRANCAAQpil5ju8gcYu0tTjP1dXBZB7M2bfyV
      aE7Fx/IEggp+iRRmA1qPNg77QzGv+BNpn7jr3FFt/9xzUZSs/xJjXQ0B
      -----END PRIVATE KEY-----
      """;

  @Test
  void buildsServerContextFromOperatorPem() {
    var context = TlsMaterial.serverContext(CERT, KEY);
    assertEquals("TLS", context.getProtocol());
    assertNotNull(context.getSocketFactory());
  }

  @Test
  void describesTheLeafSubject() {
    assertTrue(TlsMaterial.describe(CERT).contains("sail-test"));
  }

  @Test
  void describeIsLenientOnGarbage() {
    assertEquals("<unparseable certificate>", TlsMaterial.describe("not a certificate"));
  }

  @Test
  void rejectsEmptyCertificatePem() {
    assertThrows(IllegalArgumentException.class, () -> TlsMaterial.serverContext("", KEY));
  }

  @Test
  void rejectsGarbageCertificatePem() {
    assertThrows(
        IllegalArgumentException.class, () -> TlsMaterial.serverContext("no cert here", KEY));
  }

  @Test
  void rejectsNonPkcs8KeyBlock() {
    var sec1 = "-----BEGIN EC PRIVATE KEY-----\nAAAA\n-----END EC PRIVATE KEY-----";
    assertThrows(IllegalArgumentException.class, () -> TlsMaterial.serverContext(CERT, sec1));
  }

  @Test
  void rejectsKeyBlockMissingEndMarker() {
    var truncated = "-----BEGIN PRIVATE KEY-----\nAAAA";
    assertThrows(IllegalArgumentException.class, () -> TlsMaterial.serverContext(CERT, truncated));
  }

  @Test
  void rejectsKeyThatIsNotValidPkcs8() {
    var garbage =
        "-----BEGIN PRIVATE KEY-----\n"
            + Base64.getEncoder().encodeToString("not a real pkcs8 key".getBytes())
            + "\n-----END PRIVATE KEY-----";
    assertThrows(IllegalArgumentException.class, () -> TlsMaterial.serverContext(CERT, garbage));
  }
}
