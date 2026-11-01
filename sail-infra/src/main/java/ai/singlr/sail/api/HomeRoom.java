/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.api;

import ai.singlr.sail.common.Strings;
import ai.singlr.sail.config.ProjectDefinition;
import ai.singlr.sail.engine.NameValidator;
import ai.singlr.sail.identity.Ownership;
import ai.singlr.sail.webauthn.Hashes;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * An FDE's standing conversation with a project's agent: one room per FDE per project, created by
 * the box that applies the project with the member the definition names already seated. After that
 * it is a room like any other. The definition is read once: a room that exists is its owner's, and
 * a later apply leaves it alone. The room is born seated, in one write, so a refused member leaves
 * no room behind and an empty roster is only ever its owner's choice.
 */
public final class HomeRoom {

  private HomeRoom() {}

  /** What an apply did to the home room, or why it has none ({@code skipped}). */
  public record Outcome(String id, boolean created, boolean engaged, String skipped) {

    static Outcome skipped(String reason) {
      return new Outcome(null, false, false, reason);
    }

    public Map<String, Object> toMap() {
      var map = new LinkedHashMap<String, Object>();
      if (skipped != null) {
        map.put("skipped", skipped);
        return map;
      }
      map.put("id", id);
      map.put("created", created);
      map.put("engaged", engaged);
      return map;
    }
  }

  /**
   * {@code handle}'s home room id in {@code project}: {@code <project>--<handle>} when that is a
   * room id and its last {@code --} is the pair's seam, so no two pairs spell it. Otherwise the
   * SHA-256 of the pair after {@code home--}: its 64 hex characters are longer than any handle, so
   * it can never be another FDE's verbatim id.
   */
  public static String idOf(String project, String handle) {
    var verbatim = project + "--" + handle;
    if (!handle.contains("--") && NameValidator.isValidSpecId(verbatim)) {
      return verbatim;
    }
    var pair = (project + "\0" + handle).getBytes(StandardCharsets.UTF_8);
    return "home--" + HexFormat.of().formatHex(Hashes.sha256(pair));
  }

  /**
   * Leaves {@code handle}'s home room for {@code definition} in the catalog, seating the project's
   * member, through the same door the API's room creation takes, so every admission check runs as
   * it does there. Runs as the bound actor, the box's FDE.
   */
  public static Outcome ensure(Operations operations, ProjectDefinition definition, String handle) {
    if (Strings.isBlank(handle)) {
      return Outcome.skipped("this box names no FDE, so it has no home room");
    }
    var member = definition.agent() == null ? null : definition.agent().projectMember();
    if (member == null) {
      return Outcome.skipped("the definition names no agent, so it has no home room");
    }
    var project = definition.name();
    var id = idOf(project, handle);
    var held = operations.room(id);
    if (held instanceof Result.Success<RoomDetailResponse> found) {
      return existing(operations, found.value().room(), project, handle);
    }
    var unread = (Result.Failure<RoomDetailResponse>) held;
    if (unread.errorCode() != ErrorCode.ROOM_NOT_FOUND) {
      throw ApiException.of(unread);
    }
    var seat = new EngageRequest(member.agent(), member.mode(), member.model(), false);
    refuse(operations.createRoom(new RoomCreateRequest(id, project, project, null, seat), handle));
    return new Outcome(id, true, true, null);
  }

  private static Outcome existing(
      Operations operations, RoomView room, String project, String handle) {
    requireNoSpec(operations, room.id());
    if (!project.equals(room.project())) {
      throw new ApiException(
          ErrorCode.CONFLICT,
          "Room '%s' belongs to project '%s', not '%s', whose home room it would be."
              .formatted(room.id(), room.project(), project),
          "Free room id '%s', then apply again.".formatted(room.id()));
    }
    if (!Ownership.owns(handle, room.assignee(), room.createdBy())) {
      var owner = Ownership.ownerOf(room.assignee(), room.createdBy());
      throw new ApiException(
          ErrorCode.CONFLICT,
          "Room '%s' belongs to '%s', not to %s, whose home room it would be."
              .formatted(room.id(), owner, handle),
          "Have %s or an admin delete room '%s', then apply again.".formatted(owner, room.id()));
    }
    return new Outcome(room.id(), false, !room.members().isEmpty(), null);
  }

  /**
   * A spec's own room answers to the spec's id, so finding a room is not finding a home room: the
   * id must still be free of every spec, as {@code POST /v1/rooms} demands of a new one.
   */
  private static void requireNoSpec(Operations operations, String id) {
    var spec = operations.globalSpec(id);
    if (spec instanceof Result.Success<GlobalSpecDetailResponse>) {
      throw new ApiException(
          ErrorCode.CONFLICT,
          "Spec '" + id + "' already owns that conversation id.",
          "Pick a room id that is not a spec id.");
    }
    var missing = (Result.Failure<GlobalSpecDetailResponse>) spec;
    if (missing.errorCode() != ErrorCode.SPEC_NOT_FOUND) {
      throw ApiException.of(missing);
    }
  }

  private static void refuse(Result<?> result) {
    if (result instanceof Result.Failure<?> failure) {
      throw ApiException.of(failure);
    }
  }
}
