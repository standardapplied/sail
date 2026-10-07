/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.authority;

import ai.singlr.sail.identity.Actor;
import ai.singlr.sail.store.ProjectStore;
import ai.singlr.sail.store.Sqlite;
import java.util.Map;
import java.util.Optional;

/**
 * Who may write a project's descriptor: any role that can write ({@link WriterAuthority}), since
 * there is no project membership to mirror. A rename — the deletion of the old name that blocks its
 * resurrection ({@link ProjectStore#renames}) — is an admin's alone: it moves every spec and file
 * of the project, whoever's they are, so it is refused before any of them is written.
 */
public final class ProjectAuthority implements WriteAuthority {

  private final WriterAuthority writers;

  /** The rule, deciding on {@code db}'s copy. */
  public ProjectAuthority(Sqlite db) {
    this.writers = new WriterAuthority(db, "projects");
  }

  @Override
  public Optional<Refusal> decide(
      Actor actor, String id, Map<String, Object> held, Map<String, Object> next) {
    var refused = writers.decide(actor, id, held, next);
    if (refused.isPresent()
        || WriteAuthority.decided(actor)
        || actor.isAdmin()
        || !ProjectStore.renames(next)) {
      return refused;
    }
    return Refusal.of(
        Refusal.Kind.ADMIN_ONLY,
        "Renaming project '"
            + id
            + "' moves every spec and file in it, whoever's they are, and is an admin-only"
            + " action.",
        "Ask an admin to rename it.");
  }
}
