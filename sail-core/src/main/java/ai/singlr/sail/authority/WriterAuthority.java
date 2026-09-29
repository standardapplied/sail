/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.authority;

import ai.singlr.sail.identity.Actor;
import ai.singlr.sail.store.Sqlite;
import java.util.Map;
import java.util.Optional;

/**
 * Who may write a type every writer may change — a project's files and descriptors: any role that
 * can write, since there is no project membership to mirror. A pushed revision still names only
 * whom its pusher may write as.
 */
public final class WriterAuthority implements WriteAuthority {

  private final String noun;
  private final Attribution attribution;

  /** The rule for the type a read-only refusal names as {@code noun} ("files"). */
  public WriterAuthority(Sqlite db, String noun) {
    this.noun = noun;
    this.attribution = new Attribution(db);
  }

  @Override
  public Optional<Refusal> decide(
      Actor actor, String id, Map<String, Object> held, Map<String, Object> next) {
    if (WriteAuthority.decided(actor)) {
      return Optional.empty();
    }
    if (!actor.canWrite()) {
      return Refusal.readOnly("change " + noun);
    }
    return attribution.decide(actor, held, next, null, null);
  }
}
