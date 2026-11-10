/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.authority;

import ai.singlr.sail.access.Action;
import ai.singlr.sail.access.Refusal;
import ai.singlr.sail.access.Resource;
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
 * room gives a voice in its conversation, never its settings. A room lands only in a project this
 * box holds ({@link ProjectStore#refusesInto}): a project it does not is not there, which is said
 * before anything is decided. Then the one fact: a push names only its pusher ({@link
 * Attribution}).
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
    var unheld =
        projects.refusesInto(
            actor, rooms.liveProject(id).orElse(null), Snapshots.text(next, PROJECT), false);
    if (unheld.isPresent()) {
      return unheld;
    }
    var refused =
        WriteAuthority.namesNothing(held, next)
            ? WriteAuthority.unnamed(actor, Resource.Kind.ROOM)
            : WriteAuthority.refused(
                actor,
                held == null
                    ? Taking.of(
                        Resources.room(id, Snapshots.text(next, PROJECT), null), Action.CREATE)
                    : Taking.of(
                        Resources.room(id, Snapshots.text(held, PROJECT), rooms.ownerOf(id, held)),
                        Action.CHANGE));
    return refused.isPresent()
        ? refused
        : attribution.decide(actor, held, next, "created_by", null);
  }
}
