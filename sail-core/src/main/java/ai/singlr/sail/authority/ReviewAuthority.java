/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.authority;

import ai.singlr.sail.access.Action;
import ai.singlr.sail.access.Refusal;
import ai.singlr.sail.access.Resources;
import ai.singlr.sail.identity.Actor;
import ai.singlr.sail.store.ChangeLog;
import ai.singlr.sail.store.ReviewStore;
import ai.singlr.sail.store.Snapshots;
import ai.singlr.sail.store.SpecStore;
import ai.singlr.sail.store.Sqlite;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Who may write a review: whoever may approve it ({@link Action#APPROVE}), the review being its
 * spec's owner's as this box holds the spec ({@link SpecStore#lastKnown}) — the FDE whose work it
 * gates. A review never moves to another spec, and a stage never moves to another review.
 */
public final class ReviewAuthority implements WriteAuthority {

  private static final String TYPE = "review";
  private static final String SPEC_ID = "spec_id";

  private final SpecStore specs;
  private final ReviewStore reviews;
  private final ChangeLog changeLog;
  private final Attribution attribution;

  /** The rule, deciding on {@code db}'s copy. */
  public ReviewAuthority(Sqlite db) {
    this.specs = new SpecStore(db);
    this.reviews = new ReviewStore(db);
    this.changeLog = new ChangeLog(db);
    this.attribution = new Attribution(db);
  }

  @Override
  public Optional<Refusal> decide(
      Actor actor, String id, Map<String, Object> held, Map<String, Object> next) {
    if (held == null && next == null) {
      return Optional.empty();
    }
    var row = Objects.requireNonNullElse(held, next);
    var specId = Snapshots.text(row, SPEC_ID);
    var refused =
        WriteAuthority.refused(
            actor,
            Taking.of(
                Resources.review(
                    id,
                    changeLog.filed(TYPE, id, row),
                    specId,
                    specId == null
                        ? null
                        : specs.lastKnown(specId).map(SpecStore.LastKnown::owner).orElse(null)),
                Action.APPROVE));
    if (refused.isPresent()) {
      return refused;
    }
    var attributed = attribution.decide(actor, held, next, null, null);
    if (attributed.isPresent()) {
      return attributed;
    }
    if (held != null && next != null && !Objects.equals(specId, Snapshots.text(next, SPEC_ID))) {
      return Refusal.fixed(TYPE, id, SPEC_ID);
    }
    return movedStage(id, next);
  }

  /** Why {@code next} may not carry a stage main holds under another review. */
  @SuppressWarnings("unchecked")
  private Optional<Refusal> movedStage(String id, Map<String, Object> next) {
    var stages = next == null ? null : (List<Map<String, Object>>) next.get("stages");
    for (var stage : Objects.requireNonNullElse(stages, List.<Map<String, Object>>of())) {
      var stageId = Snapshots.text(stage, "id");
      var owner = reviews.findStage(stageId).map(ReviewStore.StageRow::reviewId).orElse(id);
      if (!owner.equals(id)) {
        return Refusal.of(
            Refusal.Kind.FIXED,
            "Stage " + stageId + " belongs to review " + owner + ", not review " + id + ".",
            "A stage is set when its review creates it and never moves to another review.");
      }
    }
    return Optional.empty();
  }
}
