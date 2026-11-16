/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.engine;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import ai.singlr.sail.Sail;
import ai.singlr.sail.commands.SailCommandLine;
import ai.singlr.sail.common.Remedied;
import java.util.List;
import org.junit.jupiter.api.Test;

class ContainerStateGuardTest {

  @Test
  void requireRunningPassesWhenRunning() {
    assertDoesNotThrow(
        () -> ContainerStateGuard.requireRunning(new ContainerState.Running("10.0.0.5"), "demo"));
  }

  @Test
  void requireRunningRejectsStoppedWithStartHint() {
    var ex =
        assertThrows(
            IllegalStateException.class,
            () -> ContainerStateGuard.requireRunning(new ContainerState.Stopped(), "demo"));
    assertEquals(
        "Project 'demo' is stopped. Start it with: sail project start demo", ex.getMessage());
  }

  @Test
  void requireRunningRejectsFrozenWithTheCommandThatResumesIt() {
    var ex =
        assertThrows(
            IllegalStateException.class,
            () -> ContainerStateGuard.requireRunning(new ContainerState.Frozen(), "demo"));
    assertEquals(
        "Project 'demo' is frozen. Resume it with: sail project start demo", ex.getMessage());
  }

  /**
   * The proof's medium 2: a spec's workspace stopped or frozen names the workspace door that starts
   * it, which parses as Sail's own command as printed; the project's name door, which refuses a
   * workspace, is never named for one.
   */
  @Test
  void aStoppedOrFrozenWorkspaceNamesTheWorkspaceStartAsPrinted() {
    var place = RunPlace.of("acme", "u2");
    for (var state : List.of(new ContainerState.Stopped(), new ContainerState.Frozen())) {
      var ex =
          assertThrows(
              IllegalStateException.class, () -> ContainerStateGuard.requireRunning(state, place));
      var word = state instanceof ContainerState.Stopped ? "stopped" : "frozen";
      assertEquals(
          "Workspace u2 of acme is "
              + word
              + ". Start it with: sail project workspace start acme u2",
          ex.getMessage());
    }
    var leaf =
        SailCommandLine.of(new Sail()).parseArgs("project", "workspace", "start", "acme", "u2");
    while (leaf.hasSubcommand()) {
      leaf = leaf.subcommand();
    }
    assertEquals("sail project workspace start", leaf.commandSpec().qualifiedName(" "));
    assertEquals(
        List.of("acme", "u2"),
        leaf.matchedPositionals().stream()
            .map(p -> String.valueOf((Object) p.getValue()))
            .toList());
    assertThrows(
        IllegalStateException.class,
        () ->
            ContainerStateGuard.requireRunning(
                new ContainerState.Stopped(), RunPlace.of("acme", null)),
        "a project's own container keeps its own door");
  }

  @Test
  void requireCreatedPassesWhenFrozen() {
    assertDoesNotThrow(
        () -> ContainerStateGuard.requireCreated(new ContainerState.Frozen(), "demo"));
  }

  @Test
  void requireRunningRejectsNotCreatedWithCreateHint() {
    var ex =
        assertThrows(
            IllegalStateException.class,
            () -> ContainerStateGuard.requireRunning(new ContainerState.NotCreated(), "demo"));
    assertEquals(
        "Project 'demo' has no container on this box. Create it: sail project apply demo",
        ex.getMessage());
  }

  @Test
  void aSudoCallerIsToldToStartOrCreateTheProjectWithSudo() {
    var sudo = OperatorCommand.SUDO;

    assertEquals(
        "Project 'demo' is stopped. Start it with: sudo sail project start demo",
        sudo.render(
            (Remedied)
                assertThrows(
                    IllegalStateException.class,
                    () ->
                        ContainerStateGuard.requireRunning(new ContainerState.Stopped(), "demo"))));
    assertEquals(
        "Project 'demo' has no container on this box. Create it: sudo sail project apply demo",
        sudo.render(
            (Remedied)
                assertThrows(
                    IllegalStateException.class,
                    () ->
                        ContainerStateGuard.requireCreated(
                            new ContainerState.NotCreated(), "demo"))));
  }

  @Test
  void requireRunningRejectsErrorWithMessage() {
    var ex =
        assertThrows(
            IllegalStateException.class,
            () -> ContainerStateGuard.requireRunning(new ContainerState.Error("boom"), "demo"));
    assertEquals("Container error: boom", ex.getMessage());
  }

  @Test
  void requireCreatedPassesWhenRunning() {
    assertDoesNotThrow(
        () -> ContainerStateGuard.requireCreated(new ContainerState.Running(null), "demo"));
  }

  @Test
  void requireCreatedPassesWhenStopped() {
    assertDoesNotThrow(
        () -> ContainerStateGuard.requireCreated(new ContainerState.Stopped(), "demo"));
  }

  @Test
  void requireCreatedRejectsNotCreatedWithCreateHint() {
    var ex =
        assertThrows(
            IllegalStateException.class,
            () -> ContainerStateGuard.requireCreated(new ContainerState.NotCreated(), "demo"));
    assertEquals(
        "Project 'demo' has no container on this box. Create it: sail project apply demo",
        ex.getMessage());
  }

  @Test
  void requireCreatedRejectsErrorWithMessage() {
    var ex =
        assertThrows(
            IllegalStateException.class,
            () -> ContainerStateGuard.requireCreated(new ContainerState.Error("boom"), "demo"));
    assertEquals("Container error: boom", ex.getMessage());
  }
}
