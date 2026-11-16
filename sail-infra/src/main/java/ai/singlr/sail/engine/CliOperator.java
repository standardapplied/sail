/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.engine;

import ai.singlr.sail.api.ApiException;
import ai.singlr.sail.api.ErrorCode;
import ai.singlr.sail.common.Remedy;
import ai.singlr.sail.common.Strings;
import ai.singlr.sail.config.SyncConfig;
import ai.singlr.sail.identity.Actor;
import ai.singlr.sail.identity.RoleRule;
import ai.singlr.sail.store.FdeStore;

/**
 * The operator of this box's root CLI, resolved in one place for every command that acts as it,
 * previews included: the box's own person ({@link RoleRule.Credential#box}) on the CLI lane, the
 * same principal the box credential stands for — admin on main or a standalone box, whose root they
 * hold, and on a node the role and the projects main's roster gives its FDE, so a node never
 * promises what main then refuses. Main or a standalone box with no sync handle has no FDE to be,
 * and its root acts as an admin with none. A node that cannot name its FDE's role — its roster has
 * not synced, the FDE is disabled, or it names no FDE — says so ({@link #unplaced}).
 */
public final class CliOperator {

  private CliOperator() {}

  /** The operator this box's configuration and roster name; a null roster is none kept yet. */
  public static Actor of(SyncConfig config, FdeStore roster) {
    var roles = new RoleRule(() -> config, roster);
    return roles.principal(RoleRule.Credential.box()).orElseThrow(() -> refusal(roles));
  }

  /**
   * Why whatever acts for this box's person is refused, by what is true of them: taken off every
   * project the box holds ({@link #withdrawn}), or not placed ({@link #unplaced}). The one choice
   * every door that acts for the box's person makes, the CLI's and the API's.
   */
  public static ApiException refusal(RoleRule roles) {
    var handle = roles.boxFde().orElse(null);
    return handle != null && roles.withdrawn(handle) ? withdrawn(handle) : unplaced(handle);
  }

  /**
   * The refusal for this box's person, {@code handle}, whom main has taken off every project the
   * box holds: a sync changes nothing until main grants them one again.
   */
  public static ApiException withdrawn(String handle) {
    return new ApiException(
        ErrorCode.CONFLICT,
        handle
            + " is no longer on any project this box holds, so this box gives them nothing until"
            + " main grants them one again.",
        Remedy.of(
            "Ask an admin on main to run '", Remedy.sail("fde grant", handle, "<project>"), "'."));
  }

  /**
   * The refusal for whatever acts for this box's FDE, {@code handle}, when the box cannot place it:
   * a node whose roster does not know that FDE yet or has disabled it, or a node that names none.
   */
  public static ApiException unplaced(String handle) {
    if (Strings.isBlank(handle)) {
      return new ApiException(
          ErrorCode.CONFLICT,
          "This box syncs with main but names no FDE of its own, so it cannot tell what you may do.",
          Remedy.of(
              "Name it with '",
              Remedy.sail("host config set sync-handle", "<you>"),
              "', then run '",
              Remedy.sail("sync"),
              "'."));
    }
    return new ApiException(
        ErrorCode.CONFLICT,
        "This box does not know its FDE's role ('"
            + handle
            + "'), so it cannot tell what you may do.",
        Remedy.of(
            "Run '",
            Remedy.sail("sync"),
            "' first. If it still refuses, ask an admin whether '" + handle + "' is disabled."));
  }
}
