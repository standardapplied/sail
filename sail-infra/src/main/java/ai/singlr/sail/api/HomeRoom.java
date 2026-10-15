/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.api;

import ai.singlr.sail.common.Strings;
import ai.singlr.sail.config.SailYaml;
import ai.singlr.sail.engine.NameValidator;
import ai.singlr.sail.identity.Ownership;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.zip.CRC32;

/**
 * An FDE's standing conversation with a project's agent: one room per FDE per project, created by
 * the box that applies the project with the member the definition names already seated. After that
 * it is a room like any other. The definition is read once: a room that exists is its owner's, and
 * a later apply leaves it alone. The room is born seated, in one write, so a refused member leaves
 * no room behind and an empty roster is only ever its owner's choice.
 */
public final class HomeRoom {

  private static final int MAX_ID = NameValidator.MAX_SPEC_ID_LENGTH;

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
   * room id. A handle a room id cannot carry verbatim (upper case, '.', '_', or too long) is folded
   * to the room id alphabet, cut to fit, and suffixed with the handle's CRC-32, so every FDE still
   * gets an id of their own.
   */
  public static String idOf(String project, String handle) {
    var verbatim = project + "--" + handle;
    if (NameValidator.isValidSpecId(verbatim)) {
      return verbatim;
    }
    var suffix = "-" + digest(handle);
    var folded = project + "--" + handle.toLowerCase(Locale.ROOT).replaceAll("[._]", "-");
    var room = folded.substring(0, Math.min(folded.length(), MAX_ID - suffix.length()));
    return room + suffix;
  }

  private static String digest(String handle) {
    var crc = new CRC32();
    crc.update(handle.getBytes(StandardCharsets.UTF_8));
    return HexFormat.of().toHexDigits((int) crc.getValue());
  }

  /**
   * Leaves {@code handle}'s home room for {@code definition} in the catalog, seating the project's
   * member, through the same door the API's room creation takes, so every admission check runs as
   * it does there. Runs as the bound actor, the box's FDE.
   */
  public static Outcome ensure(Operations operations, SailYaml definition, String handle) {
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
      return existing(found.value().room(), handle);
    }
    var unread = (Result.Failure<RoomDetailResponse>) held;
    if (unread.errorCode() != ErrorCode.ROOM_NOT_FOUND) {
      throw refusal(unread);
    }
    var seat = new EngageRequest(member.agent(), member.mode(), member.model(), false);
    refuse(operations.createRoom(new RoomCreateRequest(id, project, project, null, seat), handle));
    return new Outcome(id, true, true, null);
  }

  private static Outcome existing(RoomView room, String handle) {
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

  private static void refuse(Result<?> result) {
    if (result instanceof Result.Failure<?> failure) {
      throw refusal(failure);
    }
  }

  private static ApiException refusal(Result.Failure<?> failure) {
    return new ApiException(failure.errorCode(), failure.errorMessage(), failure.action());
  }
}
