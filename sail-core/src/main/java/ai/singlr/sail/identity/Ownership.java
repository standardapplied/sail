/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.identity;

import ai.singlr.sail.common.Strings;
import java.util.Objects;

/**
 * Whose a spec or a room is: the one derivation of ownership. Every decision that turns on it — who
 * may change a spec, who may post in its room, whose box wakes it, who may prune it — reads {@link
 * #ownerOf}, on every box and through every door.
 */
public final class Ownership {

  private Ownership() {}

  /**
   * The owner of a spec or room: its assignee, or its creator while it is unassigned. Blank when
   * neither is known.
   */
  public static String ownerOf(String assignee, String createdBy) {
    return Strings.isNotBlank(assignee) ? assignee : Objects.toString(createdBy, "");
  }

  /**
   * Whether {@code identity} owns the spec or room {@link #ownerOf} names; a blank identity owns
   * nothing.
   */
  public static boolean owns(String identity, String assignee, String createdBy) {
    return owns(identity, ownerOf(assignee, createdBy));
  }

  /**
   * Whether {@code identity} is {@code owner} itself. An agent principal is its own handle here,
   * never the FDE it acts for: the decisions that take this form are an FDE's alone. A blank
   * identity owns nothing.
   */
  public static boolean owns(String identity, String owner) {
    return Strings.isNotBlank(identity) && identity.equals(owner);
  }
}
