/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.api;

import ai.singlr.sail.common.Strings;
import ai.singlr.sail.config.SailYaml;
import ai.singlr.sail.engine.NameValidator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * An FDE's standing conversation with a project's agent: one room per FDE per project, id {@code
 * <project>--<handle>}, created by the box that applies the project and engaged with the member the
 * definition names. After that it is a room like any other. The definition is read once: a room the
 * FDE has engaged, disengaged or otherwise revised is theirs, and a later apply leaves it alone.
 * Only a room nobody has revised since its creation and that seats no one, which a refused engage
 * leaves behind, is engaged again.
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

  private static String idOf(String project, String handle) {
    return project + "--" + handle;
  }

  /**
   * Leaves {@code handle}'s home room for {@code definition} in the catalog, engaged with the
   * project's member, through the same doors the API's room creation and engagement take, so every
   * admission check runs as it does there. Runs as the bound actor, the box's FDE. A refused engage
   * keeps the created room, which the next apply engages.
   */
  public static Outcome ensure(Operations operations, SailYaml definition, String handle) {
    if (Strings.isBlank(handle)) {
      return Outcome.skipped("this box names no FDE, so it has no home room");
    }
    if (definition.agent() == null) {
      return Outcome.skipped("the definition names no agent, so it has no home room");
    }
    var project = definition.name();
    var id = idOf(project, handle);
    if (!NameValidator.isValidSpecId(id)) {
      return Outcome.skipped(
          "'"
              + id
              + "' cannot name a room (lowercase letters, digits and '-', at most "
              + NameValidator.MAX_SPEC_ID_LENGTH
              + " characters), so there is no home room");
    }
    var held = operations.room(id);
    if (held instanceof Result.Success<RoomDetailResponse> found && !awaitingMember(found)) {
      return new Outcome(id, false, !found.value().room().members().isEmpty(), null);
    }
    if (held instanceof Result.Failure<RoomDetailResponse> unread
        && unread.errorCode() != ErrorCode.ROOM_NOT_FOUND) {
      throw refusal(unread);
    }
    var created = held.isFailure();
    if (created) {
      refuse(operations.createRoom(new RoomCreateRequest(id, project, project, null)));
    }
    var member = definition.agent().projectMember();
    refuse(
        operations.addRoomMember(
            id, new EngageRequest(member.agent(), member.mode(), member.model(), false), handle));
    return new Outcome(id, created, true, null);
  }

  private static boolean awaitingMember(Result.Success<RoomDetailResponse> found) {
    var room = found.value().room();
    return room.members().isEmpty() && Objects.equals(room.updatedAt(), room.createdAt());
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
