/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.access;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import ai.singlr.sail.access.Policy.Cell;
import ai.singlr.sail.access.Policy.Column;
import ai.singlr.sail.identity.Actor;
import ai.singlr.sail.identity.Role;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/** The permission table's own rules: its shape, and which column each principal reads. */
class PolicyTest {

  @Test
  void aRoleColumnNeverGrantsOwnWork() {
    var thrown =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                new Policy.Row(
                    Action.POST, Cell.NO, Cell.OWN_WORK, Cell.YES, Cell.YES, Cell.NO, Cell.YES));

    assertEquals(
        "The row of POST gives a role own work, which only an agent has.", thrown.getMessage());
    assertThrows(
        NullPointerException.class,
        () -> new Policy.Row(Action.POST, Cell.NO, Cell.YES, Cell.YES, Cell.YES, Cell.NO, null));
  }

  @Test
  void theTableHoldsOneRowPerActionInOrder() {
    var row =
        new Policy.Row(Action.READ, Cell.YES, Cell.YES, Cell.YES, Cell.YES, Cell.YES, Cell.YES);

    var thrown = assertThrows(IllegalArgumentException.class, () -> Policy.indexed(List.of(row)));

    assertEquals(
        "The permission table must hold one row per action, in order: "
            + List.of(Action.values())
            + ", not [READ].",
        thrown.getMessage());
  }

  @Test
  void onlyAnAgentsColumnHearsAConversationThroughTheVoicesThatSteerIt() {
    var heard =
        Arrays.stream(Actor.Lane.values())
            .collect(
                Collectors.toMap(
                    Function.identity(),
                    lane ->
                        Policy.hearsOnlySteering(
                            new Actor("claude/r-1", Role.MEMBER, lane, "ada"))));

    assertEquals(
        Map.of(
            Actor.Lane.CLI, false,
            Actor.Lane.API, false,
            Actor.Lane.AGENT, true,
            Actor.Lane.ROOM, true,
            Actor.Lane.SYNC, false,
            Actor.Lane.MAIN, false,
            Actor.Lane.SYSTEM, false),
        heard);
  }

  @Test
  void theInContainerCredentialNarrowsNothingUntilItHasALane() {
    assertEquals(
        Arrays.stream(Action.values())
            .collect(Collectors.toMap(Function.identity(), action -> Cell.YES)),
        Arrays.stream(Action.values())
            .collect(
                Collectors.toMap(
                    Function.identity(), action -> Policy.cell(action, Column.CONTAINER))));
  }

  @Test
  void aPersonReadsTheColumnOfTheirRoleAndAnAgentTheColumnOfItsLaneToo() {
    assertEquals(
        Map.of(Role.VIEWER, Column.VIEWER, Role.MEMBER, Column.MEMBER, Role.ADMIN, Column.ADMIN),
        Arrays.stream(Role.values()).collect(Collectors.toMap(Function.identity(), Column::role)));
    assertEquals(
        Map.of(
            Actor.Lane.CLI, Optional.empty(),
            Actor.Lane.API, Optional.empty(),
            Actor.Lane.SYNC, Optional.empty(),
            Actor.Lane.MAIN, Optional.empty(),
            Actor.Lane.SYSTEM, Optional.empty(),
            Actor.Lane.AGENT, Optional.of(Column.WORK_AGENT),
            Actor.Lane.ROOM, Optional.of(Column.ROOM_AGENT)),
        Arrays.stream(Actor.Lane.values())
            .collect(Collectors.toMap(Function.identity(), Column::lane)));
  }
}
