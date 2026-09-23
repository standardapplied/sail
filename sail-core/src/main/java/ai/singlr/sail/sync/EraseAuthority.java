/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.sync;

import ai.singlr.sail.common.Strings;
import ai.singlr.sail.config.YamlUtil;
import ai.singlr.sail.store.ChangeLog;
import ai.singlr.sail.store.Erasure;
import ai.singlr.sail.store.Snapshots;
import ai.singlr.sail.store.Sqlite;
import java.util.Objects;
import java.util.Optional;

/**
 * Main's decision on a node's request to erase, made against main's own copy — never the node's. A
 * spec is erased by an admin or by its owner: the assignee, or its creator when it is unassigned,
 * read from the live row or from the last state its tombstone kept. A spec main does not hold, or
 * has already erased, has nothing left to protect. A whole project is erased only by an admin, and
 * nothing else is erased on request: messages and runs go with what they belong to, or by main's
 * own retention.
 */
final class EraseAuthority {

  private final Sqlite db;
  private final ChangeLog changeLog;

  EraseAuthority(Sqlite db) {
    this.db = Objects.requireNonNull(db, "db");
    this.changeLog = new ChangeLog(db);
  }

  /** Why {@code principal} may not erase {@code type} {@code id}; empty when it may. */
  Optional<String> refusal(SyncPrincipal principal, String type, String id) {
    if (!principal.canWrite()) {
      return Optional.of("a read-only role cannot prune");
    }
    if (!Erasure.SPEC.equals(type) && !Erasure.PROJECT.equals(type)) {
      return Optional.of("only specs and projects are pruned on request, not a " + type);
    }
    if (principal.admin()) {
      return Optional.empty();
    }
    if (Erasure.PROJECT.equals(type)) {
      return Optional.of("pruning a whole project is admin-only");
    }
    var owner = owner(id);
    if (owner.isEmpty() || owner.get().equals(principal.handle())) {
      return Optional.empty();
    }
    if (owner.get().isBlank()) {
      return Optional.of("spec '" + id + "' has no owner; only an admin can prune it");
    }
    return Optional.of("spec '" + id + "' belongs to '" + owner.get() + "'; ask them or an admin");
  }

  private Optional<String> owner(String specId) {
    var live =
        db.queryOne(
            "SELECT assignee, created_by FROM specs WHERE id = ?",
            row -> ownerOf(row.text(0), row.text(1)),
            specId);
    if (live.isPresent()) {
      return live;
    }
    return changeLog
        .head(Erasure.SPEC, specId)
        .filter(head -> head.kind() == ChangeLog.Kind.TOMBSTONE)
        .map(head -> YamlUtil.parseMap(head.snapshot()))
        .map(last -> ownerOf(Snapshots.text(last, "assignee"), Snapshots.text(last, "created_by")));
  }

  private static String ownerOf(String assignee, String createdBy) {
    return Strings.isNotBlank(assignee) ? assignee : Objects.toString(createdBy, "");
  }
}
