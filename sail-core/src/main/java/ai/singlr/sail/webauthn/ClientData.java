/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.webauthn;

import ai.singlr.sail.config.YamlUtil;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * The decoded {@code clientDataJSON} (W3C WebAuthn §5.8.1) a browser sends in both ceremonies: the
 * ceremony {@code type}, the server {@code challenge} (base64url), and the {@code origin}. Its
 * SHA-256 hash is retained because assertion signatures are computed over {@code authenticatorData
 * ‖ SHA-256(clientDataJSON)}. JSON is parsed with the project's snakeyaml-engine (JSON ⊂ YAML 1.2).
 */
public record ClientData(
    String type, byte[] challenge, String origin, boolean crossOrigin, byte[] hash) {

  /** Ceremony type for a registration ({@code navigator.credentials.create}). */
  public static final String TYPE_CREATE = "webauthn.create";

  /** Ceremony type for an assertion ({@code navigator.credentials.get}). */
  public static final String TYPE_GET = "webauthn.get";

  public static ClientData parse(byte[] clientDataJson) {
    var map = YamlUtil.parseMapStrict(new String(clientDataJson, StandardCharsets.UTF_8));
    var type = requireString(map.get("type"), "type");
    var origin = requireString(map.get("origin"), "origin");
    var challengeB64 = requireString(map.get("challenge"), "challenge");
    byte[] challenge;
    try {
      challenge = Base64.getUrlDecoder().decode(challengeB64);
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException("clientDataJSON challenge is not valid base64url", e);
    }
    var crossOrigin = Boolean.TRUE.equals(map.get("crossOrigin"));
    return new ClientData(type, challenge, origin, crossOrigin, Hashes.sha256(clientDataJson));
  }

  private static String requireString(Object value, String field) {
    if (value instanceof String s && !s.isBlank()) {
      return s;
    }
    throw new IllegalArgumentException("clientDataJSON missing string field: " + field);
  }
}
