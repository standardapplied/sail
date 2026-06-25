/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.engine;

import ai.singlr.sail.store.Sqlite;

/**
 * Opens a handle to the control-plane database. The seam that lets command tests inject a seeded
 * database in place of the real on-disk one: production code uses {@link #DEFAULT}, which resolves
 * the live path, while a test supplies an opener over a migrated temp database. The caller owns the
 * returned handle and closes it (typically via try-with-resources), so an opener over a file path
 * may be invoked repeatedly.
 */
@FunctionalInterface
public interface ControlPlaneDb {

  /** Opens a new handle to the control-plane database; the caller closes it. */
  Sqlite open();

  /** The production opener: the control-plane database at its on-disk location. */
  ControlPlaneDb DEFAULT = () -> Sqlite.open(SailPaths.controlPlaneDb());
}
