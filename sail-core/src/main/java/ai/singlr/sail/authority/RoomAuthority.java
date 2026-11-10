/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.authority;

import ai.singlr.sail.access.Action;
import ai.singlr.sail.access.Resources;
import ai.singlr.sail.identity.Actor;
import ai.singlr.sail.store.ProjectStore;
import ai.singlr.sail.store.RoomStore;
import ai.singlr.sail.store.Snapshots;
import ai.singlr.sail.store.Sqlite;
import java.util.Map;
import java.util.Optional;

/**
 * Who may write a room: a birth is a {@link Action#CREATE}, and every later revision and its
 * tombstone — roster, wake, title and assignee alike — a {@link Action#CHANGE} of the room as its
 * owner has it ({@link RoomStore#ownerOf}), each decided by {@link Access}. Owning a spec born in a
 * room gives a voice in its conversation, never its settings. The facts: a room lands only in a
 * project this box holds ({@link ProjectStore#refusesInto}), and a push names only its pusher
 * ({@link Attribution}).
 */
public final class RoomAuthority implements WriteAuthority {

  private static final String PROJECT = "project";

  private final RoomStore rooms;
  private final ProjectStore projects;
  private final Attribution attribution;

  /** The rule, deciding on {@code db}'s copy. */
  public RoomAuthority(Sqlite db) {
    this.rooms = new RoomStore(db);
    this.projects = new ProjectStore(db);
    this.attribution = new Attribution(db);
  }

  @Override
  public Optional<Refusal> decide(
      Actor actor, String id, Map<String, Object> held, Map<String, Object> next) {
    var room =
        held == null
            ? Resources.room(id, Snapshots.text(next, PROJECT), null)
            : Resources.room(id, Snapshots.text(held, PROJECT), rooms.ownerOf(id, held));
    var refused =
        WriteAuthority.refused(
            actor, Taking.of(room, held == null ? Action.CREATE : Action.CHANGE));
    if (refused.isPresent()) {
      return refused;
    }
    var unheld =
        projects.refusesInto(
            actor, rooms.liveProject(id).orElse(null), Snapshots.text(next, PROJECT), false);
    return unheld.isPresent() ? unheld : attribution.decide(actor, held, next, "created_by", null);
  }
}
