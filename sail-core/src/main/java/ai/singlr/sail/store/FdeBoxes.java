/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.store;

import ai.singlr.sail.common.DateTimeUtils;
import ai.singlr.sail.common.Strings;
import java.util.Objects;
import java.util.Optional;

/**
 * The one box each FDE syncs from, as main records it: the first box to sync as an FDE is that
 * FDE's box, and a session from any other box for it is refused until an admin releases it ({@code
 * sail fde release-box}). Local to main: never journaled, never synced.
 */
public final class FdeBoxes {

  private final Sqlite db;

  public FdeBoxes(Sqlite db) {
    this.db = Objects.requireNonNull(db, "db");
  }

  /**
   * Records {@code box} as {@code handle}'s box unless another box is recorded for it, in one
   * statement so two first sessions cannot both win. Returns the box recorded for {@code handle}
   * when it is another one, which is refused; empty when {@code box} is the FDE's box.
   */
  public Optional<String> claim(String handle, String box) {
    Strings.requireNonBlank(handle, "A box is claimed for an FDE handle");
    Strings.requireNonBlank(box, "A box claims an FDE by its box id");
    db.execute(
        """
        INSERT INTO fde_boxes (handle, box_id, claimed_at) VALUES (?, ?, ?)
        ON CONFLICT(handle) DO NOTHING""",
        handle,
        box,
        DateTimeUtils.now().toString());
    return boxOf(handle).filter(recorded -> !recorded.equals(box));
  }

  /** The box recorded for {@code handle}, or empty when none has synced as it yet. */
  public Optional<String> boxOf(String handle) {
    return db.queryOne("SELECT box_id FROM fde_boxes WHERE handle = ?", row -> row.text(0), handle);
  }

  /** Forgets {@code handle}'s box, so the next box to sync as it is recorded. */
  public boolean release(String handle) {
    return db.transaction(
        () -> {
          var recorded = boxOf(handle).isPresent();
          db.execute("DELETE FROM fde_boxes WHERE handle = ?", handle);
          return recorded;
        });
  }
}
