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
 * #fromAttribute} resolves the stored value, treating a missing role as {@link #ADMIN} for backward
 * compatibility with single-operator deployments created before roles were enforced, and any
 * unrecognized value as the least-privileged {@link #VIEWER} (fail-safe).
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
   * Resolves the role from the {@code token.role} exchange attribute. A null or blank value means a
   * pre-roles operator token and maps to {@link #ADMIN}; an unrecognized value maps to {@link
   * #VIEWER} so a malformed role can never silently escalate.
   */
  public static Role fromAttribute(Object attribute) {
    if (attribute == null) {
      return ADMIN;
    }
    var value = attribute.toString().strip().toLowerCase(Locale.ROOT);
    return switch (value) {
      case "", "admin" -> ADMIN;
      case "member" -> MEMBER;
      case "viewer" -> VIEWER;
      default -> VIEWER;
    };
  }
}
