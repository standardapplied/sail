/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.webauthn;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import org.junit.jupiter.api.Test;

class ClientDataTest {

  private static byte[] json(String s) {
    return s.getBytes(StandardCharsets.UTF_8);
  }

  @Test
  void parsesRealClientDataJson() throws Exception {
    var challenge = "T1xCsnxM2DNL2KdK5CLa6fMhD7OBqho6syzInk_n-Uo";
    var raw =
        json(
            "{\"type\":\"webauthn.create\",\"challenge\":\""
                + challenge
                + "\",\"origin\":\"https://example.com\",\"crossOrigin\":false}");

    var data = ClientData.parse(raw);
    assertEquals(ClientData.TYPE_CREATE, data.type());
    assertEquals("https://example.com", data.origin());
    assertFalse(data.crossOrigin());
    assertArrayEquals(Base64.getUrlDecoder().decode(challenge), data.challenge());
    assertArrayEquals(MessageDigest.getInstance("SHA-256").digest(raw), data.hash());
  }

  @Test
  void crossOriginTrueIsParsed() {
    var raw =
        json(
            "{\"type\":\"webauthn.get\",\"challenge\":\"AAAA\",\"origin\":\"https://h\",\"crossOrigin\":true}");
    assertTrue(ClientData.parse(raw).crossOrigin());
  }

  @Test
  void rejectsMissingFields() {
    assertThrows(
        IllegalArgumentException.class,
        () -> ClientData.parse(json("{\"challenge\":\"AAAA\",\"origin\":\"https://h\"}")));
    assertThrows(
        IllegalArgumentException.class,
        () -> ClientData.parse(json("{\"type\":\"webauthn.get\",\"origin\":\"https://h\"}")));
    assertThrows(
        IllegalArgumentException.class,
        () -> ClientData.parse(json("{\"type\":\"webauthn.get\",\"challenge\":\"AAAA\"}")));
  }

  @Test
  void rejectsBlankAndNonStringFields() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            ClientData.parse(
                json("{\"type\":\"  \",\"challenge\":\"AAAA\",\"origin\":\"https://h\"}")));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            ClientData.parse(
                json("{\"type\":123,\"challenge\":\"AAAA\",\"origin\":\"https://h\"}")));
  }

  @Test
  void rejectsInvalidBase64UrlChallenge() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            ClientData.parse(
                json(
                    "{\"type\":\"webauthn.get\",\"challenge\":\"not base64!!\",\"origin\":\"https://h\"}")));
  }

  @Test
  void rejectsDuplicateChallengeKey() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            ClientData.parse(
                json(
                    "{\"type\":\"webauthn.get\",\"origin\":\"https://h\",\"challenge\":\"AAAA\","
                        + "\"challenge\":\"BBBB\"}")));
  }
}
