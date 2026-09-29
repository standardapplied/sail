/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.authority;

import ai.singlr.sail.common.Strings;
import ai.singlr.sail.identity.Actor;
import ai.singlr.sail.store.Snapshots;
import ai.singlr.sail.store.SpecStore;
import ai.singlr.sail.store.Sqlite;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Who may write a review: an admin, or an actor who acts for the owner of the review's spec as this
 * box holds it ({@link SpecStore#lastKnown}) — the FDE whose work it gates. A review never moves to
 * another spec.
 */
public final class ReviewAuthority implements WriteAuthority {

  private static final String SPEC_ID = "spec_id";

  private final SpecStore specs;
  private final Attribution attribution;

  public ReviewAuthority(Sqlite db) {
    this.specs = new SpecStore(db);
    this.attribution = new Attribution(db);
  }

  @Override
  public Optional<Refusal> decide(
      Actor actor, String id, Map<String, Object> held, Map<String, Object> next) {
    if (WriteAuthority.decided(actor) || (held == null && next == null)) {
      return Optional.empty();
    }
    if (!actor.canWrite()) {
      return Refusal.readOnly("change reviews");
    }
    var attributed = attribution.decide(actor, held, next, null, null);
    if (attributed.isPresent()) {
      return attributed;
    }
    var specId = Snapshots.text(Objects.requireNonNullElse(held, next), SPEC_ID);
    if (held != null && next != null && !Objects.equals(specId, Snapshots.text(next, SPEC_ID))) {
      return Refusal.fixed("review", id, SPEC_ID);
    }
    var owner =
        specId == null ? "" : specs.lastKnown(specId).map(SpecStore.LastKnown::owner).orElse("");
    if (actor.isAdmin() || actor.actsFor(owner)) {
      return Optional.empty();
    }
    return Refusal.of(
        Refusal.Kind.NOT_OWNER,
        "Review "
            + id
            + " is for spec '"
            + specId
            + "'"
            + (Strings.isNotBlank(owner)
                ? ", owned by '" + owner + "', not you."
                : ", which has no owner."),
        "Only "
            + (Strings.isNotBlank(owner) ? owner : "the spec's owner")
            + " or an admin may approve or dismiss it.");
  }
}
