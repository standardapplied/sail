/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.config;

import ai.singlr.sail.common.Strings;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The WebAuthn Relying Party settings the control plane validates passkey ceremonies against: the
 * {@code rpId} (the registrable domain the credential is scoped to), a human-facing {@code rpName},
 * and the set of allowed {@code origins} a {@code clientDataJSON} may carry. These come from the
 * TLS-terminating reverse proxy's public {@code https://} origin — sail terminates no TLS itself —
 * so the operator declares them here. A config with no {@code rpId} or no origins is {@link
 * #isConfigured() unconfigured}: passkey login stays disabled until it is set. {@code
 * sessionTtlHours} is the lifetime of a session minted by a passkey login, defaulting to 30 days
 * when unset and bounded to 1 hour–90 days.
 */
public record WebauthnConfig(
    String rpId, String rpName, List<String> origins, Integer sessionTtlHours) {

  public static final int MIN_SESSION_TTL_HOURS = 1;
  public static final int MAX_SESSION_TTL_HOURS = 90 * 24;
  public static final int DEFAULT_SESSION_TTL_HOURS = 30 * 24;

  public WebauthnConfig {
    origins = origins == null ? List.of() : List.copyOf(origins);
    if (sessionTtlHours != null
        && (sessionTtlHours < MIN_SESSION_TTL_HOURS || sessionTtlHours > MAX_SESSION_TTL_HOURS)) {
      throw new IllegalArgumentException(
          "Invalid session TTL: "
              + sessionTtlHours
              + " hours. Expected "
              + MIN_SESSION_TTL_HOURS
              + " to "
              + MAX_SESSION_TTL_HOURS
              + " (1 hour to 90 days).");
    }
  }

  public WebauthnConfig(String rpId, String rpName, List<String> origins) {
    this(rpId, rpName, origins, null);
  }

  /** An empty, disabled configuration. */
  public static WebauthnConfig disabled() {
    return new WebauthnConfig(null, null, List.of());
  }

  /** The lifetime of a session minted by a passkey login; 30 days unless configured. */
  public Duration sessionTtl() {
    return Duration.ofHours(Objects.requireNonNullElse(sessionTtlHours, DEFAULT_SESSION_TTL_HOURS));
  }

  /** True when an {@code rpId} and at least one origin are present, so login can be served. */
  public boolean isConfigured() {
    return Strings.isNotBlank(rpId) && !origins.isEmpty();
  }

  /** The display name, falling back to the {@code rpId} when none was configured. */
  public String resolvedRpName() {
    return Strings.isBlank(rpName) ? rpId : rpName;
  }

  @SuppressWarnings("unchecked")
  public static WebauthnConfig fromMap(Map<String, Object> map) {
    if (map == null) {
      return disabled();
    }
    var origins =
        switch (map.get("origins")) {
          case List<?> list -> list.stream().map(Objects::toString).toList();
          case null -> List.<String>of();
          case Object single -> List.of(single.toString());
        };
    var raw = map.get("session_ttl_hours");
    var sessionTtlHours = raw == null ? null : Integer.valueOf(raw.toString().strip());
    return new WebauthnConfig(
        (String) map.get("rp_id"), (String) map.get("rp_name"), origins, sessionTtlHours);
  }

  public Map<String, Object> toMap() {
    var map = new LinkedHashMap<String, Object>();
    map.put("rp_id", rpId);
    map.put("rp_name", rpName);
    map.put("origins", origins);
    if (sessionTtlHours != null) {
      map.put("session_ttl_hours", sessionTtlHours);
    }
    return map;
  }
}
