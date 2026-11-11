/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.access;

import ai.singlr.sail.common.Strings;
import ai.singlr.sail.identity.Ownership;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Stream;

/**
 * Every {@link Resource}, and the one place its owners are built: a caller selects the facts a row
 * carries — the assignee and creator, the owner of the spec a run works, the owners of a
 * conversation ({@code RoomStore.owners}), whoever's box runs a run being created — and the factory
 * here turns them into the owners {@link Access} decides by, so deciding a list costs no query per
 * row. Whose a spec is ({@link Ownership#ownerOf}), whose a run is ({@link #run}), whose a project
 * is (its creator). Where today's rules genuinely derive owners differently, each derivation is its
 * own factory, never a flag: a room's settings answer to its owner ({@link #room}) while its
 * conversation answers to every owner of the work in it ({@link #conversation}). What a caller
 * selects is its own to see, so a door that sees less passes less.
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
        null,
        null,
        null);
  }

  /**
   * {@code spec} as born in the room whose conversation {@code room} is, when that is not its own:
   * a create or a claim of it there also takes that conversation's owners.
   */
  public static Resource bornIn(Resource spec, Resource room) {
    return new Resource(
        spec.kind(),
        spec.id(),
        spec.project(),
        spec.owners(),
        spec.assignee(),
        spec.spec(),
        room,
        spec.madeBy());
  }

  /**
   * {@code resource}, a spec or a conversation, as made by {@code principal}: the spec's maker, or
   * the maker of the spec whose own room the conversation is, as the journal's first revision of it
   * records ({@code ChangeLog.bornBy}); none, null, when the journal no longer keeps that revision.
   */
  public static Resource madeBy(Resource resource, String principal) {
    return new Resource(
        resource.kind(),
        resource.id(),
        resource.project(),
        resource.owners(),
        resource.assignee(),
        resource.spec(),
        resource.bornIn(),
        principal);
  }

  /**
   * Room {@code id} of {@code project} as its settings (roster, wake, title, assignee) are decided:
   * {@code owner}'s, whom {@code RoomStore.ownerOf} names — a spec's identity room its spec's
   * owner, as the box last knew the spec, any other room the owner its own row names — since only
   * the box can tell which row a room answers to.
   */
  public static Resource room(String id, String project, String owner) {
    return new Resource(Resource.Kind.ROOM, id, project, one(owner), null, null, null, null);
  }

  /**
   * Conversation {@code id} of {@code project}, a room as a post or a birth into it is decided:
   * {@code owners} are every owner of the work in it ({@code RoomStore.owners}), wider than the
   * room's own.
   */
  public static Resource conversation(String id, String project, List<String> owners) {
    return new Resource(Resource.Kind.ROOM, id, project, owners, null, null, null, null);
  }

  /** Review {@code id} of spec {@code specId}, in {@code project}: its spec's owner's. */
  public static Resource review(String id, String project, String specId, String specOwner) {
    return new Resource(
        Resource.Kind.REVIEW, id, project, one(specOwner), null, specId, null, null);
  }

  /**
   * Run {@code id} of {@code project} as the people it works for may stop it and read its log: the
   * FDE it acts for ({@code owner}), and the owner of spec {@code specId} as {@code specOwner}
   * finds it for the caller, or for a run that works no spec the box that ran it ({@code node}). So
   * work that moved to another owner can still be stopped by the FDE whose box runs it.
   */
  public static Resource run(
      String id,
      String project,
      String owner,
      String specId,
      String node,
      Function<String, Optional<String>> specOwner) {
    var adHoc = Strings.isBlank(specId);
    return new Resource(
        Resource.Kind.RUN,
        id,
        project,
        Stream.of(owner, adHoc ? node : specOwner.apply(specId).orElse(null)).toList(),
        null,
        adHoc ? null : specId,
        null,
        null);
  }

  /**
   * Run {@code id} of {@code project} as it is created to work spec {@code specId} (blank for
   * none): it answers only to {@code worker}, whoever's box runs the work it names as its rule
   * selects it — its spec's assignee, the owner of the room it works, or else the FDE it acts for.
   * A spec's creator does not count: a spec executes only on its assignee's box.
   */
  public static Resource launched(String id, String project, String specId, String worker) {
    return new Resource(
        Resource.Kind.RUN,
        id,
        project,
        one(worker),
        null,
        Strings.isBlank(specId) ? null : specId,
        null,
        null);
  }

  /** Project {@code name}, filed under itself: its creator's ({@code projects.created_by}). */
  public static Resource project(String name, String createdBy) {
    return new Resource(Resource.Kind.PROJECT, name, name, one(createdBy), null, null, null, null);
  }

  /**
   * The box of {@code fde}: its sync health, bus stats and main, which are its person's. A box that
   * names no FDE is no one's, so an admin's alone.
   */
  public static Resource box(String fde) {
    return new Resource(
        Resource.Kind.BOX,
        Strings.isBlank(fde) ? null : fde,
        null,
        one(fde),
        null,
        null,
        null,
        null);
  }

  /** The org's people, tokens and pairings, which no one owns: an admin's alone. */
  public static Resource people() {
    return new Resource(Resource.Kind.PEOPLE, "people", null, List.of(), null, null, null, null);
  }

  private static List<String> one(String owner) {
    return Strings.isBlank(owner) ? List.of() : List.of(owner);
  }
}
