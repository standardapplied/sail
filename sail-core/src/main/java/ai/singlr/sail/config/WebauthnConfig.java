/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.config;

import ai.singlr.sail.common.Strings;
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
 * #isConfigured() unconfigured}: passkey login stays disabled until it is set.
 */
public record WebauthnConfig(String rpId, String rpName, List<String> origins) {

  public WebauthnConfig {
    origins = origins == null ? List.of() : List.copyOf(origins);
  }

  /** An empty, disabled configuration. */
  public static WebauthnConfig disabled() {
    return new WebauthnConfig(null, null, List.of());
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
    return new WebauthnConfig((String) map.get("rp_id"), (String) map.get("rp_name"), origins);
  }

  public Map<String, Object> toMap() {
    var map = new LinkedHashMap<String, Object>();
    map.put("rp_id", rpId);
    map.put("rp_name", rpName);
    map.put("origins", origins);
    return map;
  }
}
