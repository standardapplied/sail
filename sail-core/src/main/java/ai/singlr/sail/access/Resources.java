/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.access;

import ai.singlr.sail.common.Strings;
import ai.singlr.sail.identity.Ownership;
import java.util.List;
import java.util.stream.Stream;

/**
 * Every {@link Resource}, built from the row already in hand, so deciding a list costs no query per
 * row. Every owner a decision reads is derived here, by the one rule for it: whose a spec or room
 * is ({@link Ownership#ownerOf}), whose a run is ({@link #run}), whose a project is (its creator).
 * Where today's rules genuinely derive owners differently, each derivation is its own factory,
 * never a flag: a room's settings answer to its owner ({@link #room}) while its conversation
 * answers to every owner of the work in it ({@link #conversation}); a run answers to the people it
 * works for ({@link #run}) while a run being created answers to whoever's box runs its work ({@link
 * #launched}). What a caller looks up before it calls (the spec owner a door may see, the
 * conversation's owners) is its own, so a door that sees less passes less.
 */
public final class Resources {

  private Resources() {}

  /**
   * Spec {@code id} of {@code project}: its assignee's, or its creator's while it is unassigned.
   */
  public static Resource spec(String id, String project, String assignee, String createdBy) {
    return new Resource(
        Resource.Kind.SPEC,
        id,
        project,
        one(Ownership.ownerOf(assignee, createdBy)),
        Strings.isBlank(assignee) ? null : assignee,
        null);
  }

  /**
   * Room {@code id} of {@code project} as its settings (roster, wake, title, assignee) are decided:
   * the owner its row names, its assignee or else its creator. A spec's identity room answers to
   * its spec, so its caller passes the spec's assignee and creator.
   */
  public static Resource room(String id, String project, String assignee, String createdBy) {
    return new Resource(
        Resource.Kind.ROOM, id, project, one(Ownership.ownerOf(assignee, createdBy)), null, null);
  }

  /**
   * Conversation {@code id} of {@code project}, a room as a post or a birth into it is decided:
   * {@code owners} are every owner of the work in it ({@code RoomStore.owners}), wider than the
   * room's own.
   */
  public static Resource conversation(String id, String project, List<String> owners) {
    return new Resource(Resource.Kind.ROOM, id, project, owners, null, null);
  }

  /**
   * Message {@code id}, filed under {@code project}, the project of its first write, and owned by
   * the owners of {@code conversation}, the conversation it was posted in.
   */
  public static Resource message(String id, String project, Resource conversation) {
    return new Resource(
        Resource.Kind.MESSAGE, id, project, conversation.owners(), null, conversation);
  }

  /** Review {@code id} of spec {@code specId}, in {@code project}: its spec's owner's. */
  public static Resource review(String id, String project, String specId, String specOwner) {
    return new Resource(
        Resource.Kind.REVIEW, id, project, one(specOwner), null, named(specId, project, specOwner));
  }

  /**
   * Run {@code id} of {@code project} as the people it works for may stop it and read its log: the
   * FDE it acts for ({@code owner}), and the owner of spec {@code specId} as the caller sees it
   * ({@code specOwner}), or for a run that works no spec the box that ran it ({@code node}). So
   * work that moved to another owner can still be stopped by the FDE whose box runs it.
   */
  public static Resource run(
      String id, String project, String owner, String specId, String node, String specOwner) {
    var adHoc = Strings.isBlank(specId);
    return new Resource(
        Resource.Kind.RUN,
        id,
        project,
        Stream.of(owner, adHoc ? node : specOwner).toList(),
        null,
        adHoc ? null : named(specId, project, specOwner));
  }

  /**
   * Run {@code id} of {@code project} as it is created to work spec {@code specId} (null for none):
   * it answers only to {@code worker}, whoever's box runs the work it names — its spec's assignee,
   * the owner of the room it works, or else the FDE it acts for. A spec's creator does not count: a
   * spec executes only on its assignee's box.
   */
  public static Resource launched(String id, String project, String specId, String worker) {
    return new Resource(
        Resource.Kind.RUN,
        id,
        project,
        one(worker),
        null,
        Strings.isBlank(specId) ? null : named(specId, project, null));
  }

  /** Project {@code name}, filed under itself: its creator's ({@code projects.created_by}). */
  public static Resource project(String name, String createdBy) {
    return new Resource(Resource.Kind.PROJECT, name, name, one(createdBy), null, null);
  }

  /** The box of {@code fde}: its sync health, bus stats and main, which are its person's. */
  public static Resource box(String fde) {
    return new Resource(Resource.Kind.BOX, fde, null, one(fde), null, null);
  }

  /** The org's people, tokens and pairings, which no one owns: an admin's alone. */
  public static Resource people() {
    return new Resource(Resource.Kind.PEOPLE, "people", null, List.of(), null, null);
  }

  private static Resource named(String specId, String project, String owner) {
    return new Resource(Resource.Kind.SPEC, specId, project, one(owner), null, null);
  }

  private static List<String> one(String owner) {
    return Strings.isBlank(owner) ? List.of() : List.of(owner);
  }
}
