/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import org.junit.jupiter.api.Test;

class ApiResponseTest {

  @Test
  void twoArgConstructorHasNoHeaders() {
    var r = new ApiResponse(200, Map.of("a", 1));
    assertEquals(200, r.status());
    assertTrue(r.headers().isEmpty());
  }

  @Test
  void withHeaderAddsToCopy() {
    var original = new ApiResponse(200, Map.of("a", 1));
    var withEtag = original.withHeader("ETag", "\"v1\"");

    assertTrue(original.headers().isEmpty());
    assertEquals("\"v1\"", withEtag.headers().get("ETag"));
    assertEquals(200, withEtag.status());
  }

  @Test
  void withHeaderNullValueReturnsSameInstance() {
    var original = new ApiResponse(200, Map.of("a", 1));
    var unchanged = original.withHeader("ETag", null);

    assertSame(original, unchanged);
  }

  @Test
  void withHeaderChainsMultipleHeaders() {
    var r =
        new ApiResponse(200, Map.of("a", 1))
            .withHeader("ETag", "\"v1\"")
            .withHeader("X-Custom", "abc");

    assertEquals(2, r.headers().size());
    assertEquals("\"v1\"", r.headers().get("ETag"));
    assertEquals("abc", r.headers().get("X-Custom"));
  }

  @Test
  void threeArgConstructorCopiesHeadersDefensively() {
    var headers = new java.util.HashMap<String, String>();
    headers.put("ETag", "\"v1\"");
    var r = new ApiResponse(200, Map.of(), headers);
    headers.put("ETag", "\"v2\"");

    assertEquals("\"v1\"", r.headers().get("ETag"));
  }
}
