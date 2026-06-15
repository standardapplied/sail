/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.api;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One request parsed off the local Unix-domain socket: an HTTP method, the path (without query
 * string), the decoded query parameters, and the raw body. The body is decoded on demand as a
 * {@code application/x-www-form-urlencoded} form, which is what the in-container {@code spec} CLI
 * sends via {@code curl --data-urlencode} — so titles and markdown bodies with any characters are
 * escaped by curl and never hand-built as JSON in a shell script.
 */
record LocalApiRequest(String method, String path, Map<String, String> query, byte[] body) {

  /** The body parsed as URL-encoded form fields; empty when there is no body. */
  Map<String, String> form() {
    return body.length == 0 ? Map.of() : decode(new String(body, StandardCharsets.UTF_8));
  }

  /** The raw body as a UTF-8 string. */
  String bodyText() {
    return new String(body, StandardCharsets.UTF_8);
  }

  /** Splits a {@code k=v&k2=v2} string into a map, URL-decoding each key and value. */
  static Map<String, String> decode(String encoded) {
    var out = new LinkedHashMap<String, String>();
    if (encoded == null || encoded.isEmpty()) {
      return out;
    }
    for (var pair : encoded.split("&")) {
      if (pair.isEmpty()) {
        continue;
      }
      var eq = pair.indexOf('=');
      if (eq < 0) {
        out.put(URLDecoder.decode(pair, StandardCharsets.UTF_8), "");
      } else {
        out.put(
            URLDecoder.decode(pair.substring(0, eq), StandardCharsets.UTF_8),
            URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8));
      }
    }
    return out;
  }
}
