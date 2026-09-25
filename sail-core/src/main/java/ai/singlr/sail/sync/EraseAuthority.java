/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.sync;

import ai.singlr.sail.identity.Actor;
import ai.singlr.sail.store.ChangeLog;
import ai.singlr.sail.store.Erasure;
import ai.singlr.sail.store.RunStore;
import ai.singlr.sail.store.SpecStore;
import ai.singlr.sail.store.Sqlite;
import java.util.List;
import java.util.Optional;

/**
 * Main's decision on a node's request to erase, made against main's own copy — never the node's. A
 * spec is erased once it is archived, cancelled or deleted, by an admin or by its owner ({@link
 * SpecStore#ownerOf}), and never while a run of it is unfinished. One main has already erased has
 * nothing left to protect, so asking again only answers the erasure it has; one main holds nothing
 * of has no owner main can establish. A whole project is erased only by an admin, and only one main
 * holds something of; nothing else is erased on request: messages and runs go with what they belong
 * to, or by main's own retention.
 */
final class EraseAuthority {

  private final ChangeLog changeLog;
  private final SpecStore specs;
  private final RunStore runs;
  private final Erasure erasure;

  EraseAuthority(Sqlite db) {
    this.changeLog = new ChangeLog(db);
    this.specs = new SpecStore(db);
    this.runs = new RunStore(db);
    this.erasure = new Erasure(db);
  }

  /** Why {@code principal} may not erase {@code type} {@code id}; empty when it may. */
  Optional<String> refusal(Actor principal, String type, String id) {
    if (!principal.canWrite()) {
      return Optional.of("a read-only role cannot prune");
    }
    if (!Erasure.SPEC.equals(type) && !Erasure.PROJECT.equals(type)) {
      return Optional.of("only specs and projects are pruned on request, not a " + type);
    }
    if (changeLog.isErased(type, id)) {
      return Optional.empty();
    }
    if (Erasure.PROJECT.equals(type)) {
      if (!principal.isAdmin()) {
        return Optional.of("pruning a whole project is admin-only");
      }
      return erasure.holds(new Erasure.Target(type, id))
          ? Optional.empty()
          : Optional.of("main holds no project '" + id + "'");
    }
    var spec = specs.lastKnown(id);
    if (spec.isEmpty()) {
      return Optional.of(
          "main holds no spec '" + id + "', so it cannot tell whose it is; sync it before pruning");
    }
    if (!spec.get().prunable()) {
      return Optional.of(
          "spec '"
              + id
              + "' is "
              + spec.get().status().wire()
              + " on main; archive or cancel it before pruning");
    }
    var owner = spec.get().owner();
    if (principal.isAdmin() || owner.equals(principal.handle())) {
      return Optional.empty();
    }
    if (owner.isBlank()) {
      return Optional.of("spec '" + id + "' has no owner; only an admin can prune it");
    }
    return Optional.of("spec '" + id + "' belongs to '" + owner + "'; ask them or an admin");
  }

  /** Why erasing {@code plan} must wait: a run in it has not finished. Empty when none is live. */
  Optional<String> busy(List<Erasure.Target> plan) {
    var unfinished =
        runs.unfinished(
            plan.stream()
                .filter(target -> Erasure.RUN.equals(target.type()))
                .map(Erasure.Target::id)
                .toList());
    return unfinished.isEmpty()
        ? Optional.empty()
        : Optional.of(
            "run '" + unfinished.getFirst() + "' has not finished; stop it before pruning");
  }
}
