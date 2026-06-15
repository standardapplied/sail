/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.junit.jupiter.api.Test;

class LocalApiRequestTest {

  @Test
  void decodeHandlesPairsKeyOnlyEmptyAndPercentEscapes() {
    var decoded = LocalApiRequest.decode("a=1&flag&&title=OAuth%20Flow");
    assertEquals("1", decoded.get("a"));
    assertEquals("", decoded.get("flag"));
    assertEquals("OAuth Flow", decoded.get("title"));
    assertEquals(3, decoded.size());
  }

  @Test
  void decodeOfEmptyOrNullIsEmpty() {
    assertTrue(LocalApiRequest.decode("").isEmpty());
    assertTrue(LocalApiRequest.decode(null).isEmpty());
  }

  @Test
  void formIsEmptyWithoutABodyAndParsedWithOne() {
    var noBody = new LocalApiRequest("GET", "/v1/specs", Map.of(), new byte[0]);
    assertTrue(noBody.form().isEmpty());

    var withBody =
        new LocalApiRequest(
            "POST", "/v1/specs", Map.of(), "id=x&title=Y".getBytes(StandardCharsets.UTF_8));
    assertEquals("x", withBody.form().get("id"));
  }

  @Test
  void bodyTextDecodesUtf8() {
    var req =
        new LocalApiRequest(
            "POST", "/v1/events", Map.of(), "café".getBytes(StandardCharsets.UTF_8));
    assertEquals("café", req.bodyText());
  }
}
