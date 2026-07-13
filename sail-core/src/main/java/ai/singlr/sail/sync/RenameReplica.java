/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.sync;

import java.util.Map;
import java.util.Set;

/** A replica that moves two entity identities as one compare-and-set operation. */
public interface RenameReplica {

  record Rename(
      String oldName,
      String newName,
      String oldDefinition,
      String newDefinition,
      String actor,
      String baseOldRev,
      String priorOldRev,
      String priorTargetRev,
      String oldRev,
      String newRev) {}

  sealed interface Commit {
    record Accepted(Rename rename) implements Commit {}

    record Rejected(Map<String, Object> oldSnapshot, Map<String, Object> targetSnapshot)
        implements Commit {}
  }

  Set<Rename> renames();

  boolean hasApplied(Rename rename);

  boolean pullRename(Rename rename);

  void acceptRename(Rename localRename, Rename committedRename);

  Commit commitRename(Rename rename);
}
