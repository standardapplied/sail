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
 * then refuses. A box with no sync handle has no FDE to be, and its root acts as an admin with
 * none. A node that cannot name its FDE's role — its roster has not synced, or the FDE is disabled
 * — says so.
 */
public final class CliOperator {

  private CliOperator() {}

  /** The operator this box's configuration and roster name; a null roster is none kept yet. */
  public static Actor of(SyncConfig config, Supplier<FdeStore> roster) {
    var handle = config.handle();
    if (Strings.isBlank(handle)) {
      if (config.isNode()) {
        throw unknownRole(handle);
      }
      return Actor.cliOperator(handle);
    }
    return new RoleRule(() -> config, roster.get())
        .roleOf(handle, Role.ADMIN)
        .map(role -> new Actor(handle, role, Actor.Lane.CLI))
        .orElseThrow(() -> unknownRole(handle));
  }

  private static ApiException unknownRole(String handle) {
    return new ApiException(
        ErrorCode.CONFLICT,
        "This box does not know its FDE's role"
            + (Strings.isBlank(handle) ? "" : " ('" + handle + "')")
            + ", so it cannot tell what you may do.",
        "Run 'sail sync' first. If it still refuses, ask an admin whether the FDE is disabled.");
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
      return of(config, () -> null);
    }
    try (var db = Sqlite.open(controlPlane)) {
      return of(config, () -> new FdeStore(db));
    }
  }
}
