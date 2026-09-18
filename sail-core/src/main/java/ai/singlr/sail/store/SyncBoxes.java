/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.store;

import ai.singlr.sail.common.DateTimeUtils;
import java.util.Optional;

/**
 * Main's record of which box id each authenticated sync principal presented first. A box id is a
 * claim until main binds it here: a later session that presents a known box id under a different
 * principal, or a known principal under a different box id, is refused, so no node can adopt
 * another's identity. A principal's binding is released when the operator registers a new SSH key
 * for that FDE — the moment a rebuilt box rejoins.
 */
public final class SyncBoxes {

  private final Sqlite db;

  public SyncBoxes(Sqlite db) {
    this.db = db;
  }

  /**
   * Binds {@code boxId} to {@code principal} on first sight; on a later sight returns the reason
   * the pair must be refused, or empty when it matches the recorded binding.
   */
  public Optional<String> bind(String principal, String boxId) {
    return db.transaction(
        () -> {
          var boundPrincipal =
              db.queryOne(
                  "SELECT principal FROM sync_boxes WHERE box_id = ?", row -> row.text(0), boxId);
          if (boundPrincipal.isPresent()) {
            return boundPrincipal.get().equals(principal)
                ? Optional.<String>empty()
                : Optional.of("box id " + boxId + " is bound to another key; run sail join again");
          }
          var boundBox =
              db.queryOne(
                  "SELECT box_id FROM sync_boxes WHERE principal = ?",
                  row -> row.text(0),
                  principal);
          if (boundBox.isPresent()) {
            return Optional.of(
                principal
                    + " is bound to box "
                    + boundBox.get()
                    + ", not "
                    + boxId
                    + "; register this box's key on main with 'sail fde key add' to rebind");
          }
          db.execute(
              "INSERT INTO sync_boxes (box_id, principal, bound_at) VALUES (?, ?, ?)",
              boxId,
              principal,
              DateTimeUtils.now().toString());
          return Optional.empty();
        });
  }

  /** Releases {@code principal}'s binding so its next session binds a fresh box id. */
  public void release(String principal) {
    db.execute("DELETE FROM sync_boxes WHERE principal = ?", principal);
  }
}
