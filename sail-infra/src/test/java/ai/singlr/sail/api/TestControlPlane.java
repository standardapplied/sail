/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.api;

import ai.singlr.sail.config.SyncConfig;
import ai.singlr.sail.engine.SyncOperations;
import ai.singlr.sail.store.Sqlite;
import java.io.IOException;
import java.nio.file.Path;

/**
 * Wires a hand-built {@link SailOperations} to its control-plane database, as {@link
 * OperationsFactory} wires every production instance, on a box whose sync reaches no main.
 */
public final class TestControlPlane {

  private TestControlPlane() {}

  /** {@code operations} over {@code db}, on a box that syncs nobody. */
  public static SailOperations standalone(SailOperations operations, Sqlite db, Path projects) {
    return on(operations, db, projects, SyncConfig.unset());
  }

  /** {@code operations} over {@code db}, on the box {@code box} configures. */
  public static SailOperations on(
      SailOperations operations, Sqlite db, Path projects, SyncConfig box) {
    return operations.useControlPlane(
        db,
        projects,
        new SyncOperations(
            db,
            "box",
            projects,
            () -> box,
            target -> {
              throw new IOException("main unavailable");
            }));
  }
}
