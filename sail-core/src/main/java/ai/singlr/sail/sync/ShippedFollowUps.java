/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.sync;

import ai.singlr.sail.config.SpecStatus;
import ai.singlr.sail.identity.Actor;
import ai.singlr.sail.store.Erasure;
import ai.singlr.sail.store.ReviewStore;
import ai.singlr.sail.store.Sqlite;
import java.util.Objects;

/**
 * Main's half of a follow-up shipping: when a spec another box marked {@code done} commits here,
 * the findings it was drafted from are resolved in their reviews' content ({@link
 * ReviewStore#resolveSourceFindings}), by main as this box's machinery. Only main resolves them — a
 * node marking its follow-up done leaves them to main — since the source review is as often another
 * FDE's, whose review a node may not write but main always may.
 */
public final class ShippedFollowUps implements SyncTransitionSink {

  private final ReviewStore reviews;

  public ShippedFollowUps(Sqlite db) {
    this.reviews = new ReviewStore(Objects.requireNonNull(db, "db"));
  }

  @Override
  public void onTransition(SyncTransition transition) {
    if (Erasure.SPEC.equals(transition.entityType())
        && SpecStatus.DONE.wire().equals(transition.to())
        && !SpecStatus.DONE.wire().equals(transition.from())) {
      Actor.run(Actor.system(), () -> reviews.resolveSourceFindings(transition.entityId()));
    }
  }
}
