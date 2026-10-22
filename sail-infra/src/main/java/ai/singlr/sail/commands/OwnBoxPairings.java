/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.commands;

import ai.singlr.sail.commands.FdeCommand.Box;
import ai.singlr.sail.config.ProjectRegistry;
import ai.singlr.sail.engine.OperatorCommand;
import ai.singlr.sail.engine.PairingTrust;
import ai.singlr.sail.store.DataMigration;
import ai.singlr.sail.store.FdePairingStore;
import ai.singlr.sail.store.Sqlite;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Revokes, through {@link PairingRevocation}, every pairing on this box but its own FDE's: a
 * connect code logs in as the operator, who sees every project here, and is minted only for the
 * box's own FDE since this release. It edits the operator's login and the containers, so {@code
 * migrate} runs it only when it converges the box itself, never in a rehearsal on a copy. Each
 * revocation is said as it commits: a later one that fails cannot take back the notice of one
 * already done.
 */
final class OwnBoxPairings implements DataMigration {

  static final String NAME = "pairings-own-box-v1";

  private final Supplier<Box> box;
  private final Consumer<String> said;
  private final OperatorCommand operator;

  OwnBoxPairings(Supplier<Box> box, Consumer<String> said, OperatorCommand operator) {
    this.box = box;
    this.said = said;
    this.operator = operator;
  }

  @Override
  public String name() {
    return NAME;
  }

  @Override
  public boolean resumable() {
    return true;
  }

  @Override
  public Report apply(Sqlite db, ProjectRegistry projects, Prompter prompter) {
    var here = box.get();
    var notes = new ArrayList<String>();
    try (var held = PairingTrust.hold(here.database())) {
      for (var handle : new FdePairingStore(db).handles()) {
        if (!handle.equals(here.handle())) {
          PairingRevocation.revoke(here, db, handle);
          var note = revoked(here, handle, operator);
          said.accept(note);
          notes.add(note);
        }
      }
    } catch (IOException failure) {
      throw new UncheckedIOException(
          "Could not take the pairing lock (" + failure.getMessage() + ").", failure);
    }
    return Report.of(notes.size(), 0, 0, List.copyOf(notes));
  }

  /**
   * The notice of {@code handle}'s revoked code. A box that names no FDE of its own revokes every
   * pairing, its operator's too, so it names how that operator pairs again.
   */
  private static String revoked(Box here, String handle, OperatorCommand operator) {
    var note =
        "Revoked "
            + handle
            + "'s connect code: a code logs in as "
            + here.operator()
            + " and is minted only for this box's own FDE.";
    if (here.handle() != null) {
      return note;
    }
    return note
        + " This box names none: if it is "
        + handle
        + ", name it with '"
        + operator.sail("host config set sync-handle " + handle)
        + "' and pair again.";
  }
}
