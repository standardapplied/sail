/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.commands;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class SessionCommandTest {

  @Test
  void aProjectSessionWrapsItsCommandInTheDevUserExecLane() {
    var command = SessionCommand.commandFor("acme", List.of());

    assertEquals("incus", command.getFirst());
    assertTrue(command.contains("acme"));
    assertTrue(command.containsAll(List.of("bash", "-l")), "the default is a login shell");

    var custom = SessionCommand.commandFor("acme", List.of("htop"));
    assertTrue(custom.contains("htop"));
  }

  @Test
  void aProjectlessSessionRunsTheCommandAsGiven() {
    assertEquals(List.of("bash", "-l"), SessionCommand.commandFor(null, List.of()));
    assertEquals(List.of("htop"), SessionCommand.commandFor(null, List.of("htop")));
  }
}
