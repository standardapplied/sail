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
import ai.singlr.sail.store.FdeStore;
import ai.singlr.sail.store.Sqlite;
import java.nio.file.Path;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * The operator of this box's root CLI, resolved in one place for every command that acts as it: the
 * box's owner, an admin, on main or a standalone box; on a node, its FDE with the role main's
 * roster gives it, so a node never promises what main then refuses. A node that has not synced the
 * roster yet cannot tell, and says so.
 */
public final class CliOperator {

  private CliOperator() {}

  /** The operator this box's configuration and synced roster name. */
  public static Actor of(SyncConfig config, Supplier<FdeStore> roster) {
    var handle = config.handle();
    if (!config.isNode()) {
      return Actor.cliOperator(handle);
    }
    var fde =
        Strings.isBlank(handle) ? Optional.<FdeStore.Fde>empty() : roster.get().byHandle(handle);
    return fde.map(found -> new Actor(handle, Role.fromAttribute(found.role()), Actor.Lane.CLI))
        .orElseThrow(
            () ->
                new ApiException(
                    ErrorCode.CONFLICT,
                    "This node does not know its FDE's role yet, so it cannot tell what you may do.",
                    "Run 'sail sync' first, then try again."));
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
    if (!config.isNode()) {
      return of(config, () -> null);
    }
    try (var db = Sqlite.open(controlPlane)) {
      return of(config, () -> new FdeStore(db));
    }
  }
}
