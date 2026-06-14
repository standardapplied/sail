/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.config;

import ai.singlr.sail.common.Strings;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Where this box sits in the db-sync star. A box is either the {@code main} devbox — the single
 * authority every node reconciles against — or a {@code node} that points at main's SSH gateway
 * target so {@code sail sync} reaches it without {@code --main}. Unset until an operator designates
 * the box; main failover is manual (out of scope for v1), so the role is plain declared state.
 */
public record SyncConfig(String role, String main) {

  public static final String ROLE_MAIN = "main";
  public static final String ROLE_NODE = "node";

  public SyncConfig {
    role = Strings.isBlank(role) ? null : role;
    main = Strings.isBlank(main) ? null : main;
  }

  /** An undeclared box: neither main nor pointed at one. */
  public static SyncConfig unset() {
    return new SyncConfig(null, null);
  }

  /** True when this box carries the main role. */
  public boolean isMain() {
    return ROLE_MAIN.equals(role);
  }

  public static SyncConfig fromMap(Map<String, Object> map) {
    if (map == null) {
      return unset();
    }
    return new SyncConfig((String) map.get("role"), (String) map.get("main"));
  }

  public Map<String, Object> toMap() {
    var map = new LinkedHashMap<String, Object>();
    map.put("role", role);
    map.put("main", main);
    return map;
  }
}
