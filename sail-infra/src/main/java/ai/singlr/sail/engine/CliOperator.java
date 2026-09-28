/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.engine;

import ai.singlr.sail.api.ApiException;
import ai.singlr.sail.api.ErrorCode;
import ai.singlr.sail.common.Strings;
import ai.singlr.sail.config.SyncConfig;
import ai.singlr.sail.identity.Actor;
import ai.singlr.sail.identity.Role;
import ai.singlr.sail.identity.RoleRule;
import ai.singlr.sail.store.FdeStore;
import ai.singlr.sail.store.Sqlite;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Supplier;

/**
 * The operator of this box's root CLI, resolved in one place for every command that acts as it: the
 * box's FDE, with the role {@link RoleRule} gives it — admin on main or a standalone box, whose
 * root they hold, and on a node the role main's roster gives it, so a node never promises what main
 * then refuses. Main or a standalone box with no sync handle has no FDE to be, and its root acts as
 * an admin with none. A node that cannot name its FDE's role — its roster has not synced, the FDE
 * is disabled, or it names no FDE — says so ({@link #unplaced}).
 */
public final class CliOperator {

  private CliOperator() {}

  /** The operator this box's configuration and roster name; a null roster is none kept yet. */
  public static Actor of(SyncConfig config, FdeStore roster) {
    var roles = new RoleRule(() -> config, roster);
    var handle = roles.boxFde().orElse(null);
    return roles
        .roleOfUnbound(Role.ADMIN)
        .map(role -> new Actor(handle, role, Actor.Lane.CLI))
        .orElseThrow(() -> unplaced(handle));
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
          "Name it with 'sudo sail host config set sync-handle <you>', then run 'sudo sail sync'.");
    }
    return new ApiException(
        ErrorCode.CONFLICT,
        "This box does not know its FDE's role ('"
            + handle
            + "'), so it cannot tell what you may do.",
        "Run 'sudo sail sync' first. If it still refuses, ask an admin whether '"
            + handle
            + "' is disabled.");
  }

  /**
   * Runs {@code work} as {@code operator}, or as no one for a preview: a preview writes nothing, so
   * a node whose roster has not synced can still describe what it would do.
   */
  public static <T> T actingUnlessPreview(
      boolean preview, Supplier<Actor> operator, ScopedValue.CallableOp<T, RuntimeException> work) {
    return preview ? work.call() : Actor.call(operator.get(), work);
  }

  /** The operator of this box, read from its host configuration and control-plane roster. */
  public static Actor current() {
    return current(NodeIdentity.config(), SailPaths.controlPlaneDb());
  }

  static Actor current(SyncConfig config, Path controlPlane) {
    if (!Files.exists(controlPlane)) {
      return of(config, null);
    }
    try (var db = Sqlite.open(controlPlane)) {
      return of(config, new FdeStore(db));
    }
  }
}
