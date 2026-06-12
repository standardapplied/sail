/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.api;

import java.util.EnumSet;
import java.util.Locale;
import java.util.Set;

/**
 * Authorization role attached to an API credential. Each role grants a fixed set of {@link
 * Capability}. Roles are stored as lowercase strings on the token ({@code token.role}); {@link
 * #fromAttribute} resolves the stored value, failing safe to the least-privileged {@link #VIEWER}
 * for any missing, blank, or unrecognized value so a malformed role can never silently escalate.
 * Every credential carries an explicit role — {@code api_tokens.role} and {@code fdes.role} are
 * both {@code NOT NULL} with a CHECK constraint — so this fallback is defense in depth, not a path
 * any live credential travels.
 */
public enum Role {
  ADMIN(EnumSet.allOf(Capability.class)),
  MEMBER(EnumSet.of(Capability.READ, Capability.WRITE)),
  VIEWER(EnumSet.of(Capability.READ));

  private final Set<Capability> capabilities;

  Role(Set<Capability> capabilities) {
    this.capabilities = capabilities;
  }

  public boolean allows(Capability capability) {
    return capabilities.contains(capability);
  }

  /**
   * Resolves the role from the {@code token.role} exchange attribute. Any null, blank, or
   * unrecognized value fails safe to {@link #VIEWER} so a malformed or absent role can never
   * silently escalate to a privileged capability.
   */
  public static Role fromAttribute(Object attribute) {
    if (attribute == null) {
      return VIEWER;
    }
    var value = attribute.toString().strip().toLowerCase(Locale.ROOT);
    return switch (value) {
      case "admin" -> ADMIN;
      case "member" -> MEMBER;
      default -> VIEWER;
    };
  }
}
