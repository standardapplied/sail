/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.pty;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class PtySessionHostChildCommandTest {

  private static PtySession.Origin origin(String project, String room, String... command) {
    return new PtySession.Origin("s", "inst-s", "uday", project, room, List.of(command));
  }

  @Test
  void aProjectlessSessionRunsTheCommandAsGiven() {
    assertEquals(List.of("htop"), PtySessionHost.childCommand(origin(null, "", "htop"), Map.of()));
    assertEquals(List.of("htop"), PtySessionHost.childCommand(origin("", "", "htop"), Map.of()));
    assertEquals(List.of("htop"), PtySessionHost.childCommand(origin("   ", "", "htop"), Map.of()));
  }

  @Test
  void anEmptyRequestDefaultsToALoginShell() {
    assertEquals(List.of("bash", "-l"), PtySessionHost.requestedOrShell(List.of()));
    assertEquals(List.of("claude"), PtySessionHost.requestedOrShell(List.of("claude")));
  }

  @Test
  void aProjectSessionWrapsTheCommandInTheDevUserTtyExecLane() {
    var command =
        PtySessionHost.childCommand(
            origin("acme", "", "bash", "-l"), PtySessionHost.childEnv("", "0.39.2"));

    assertEquals("incus", command.getFirst());
    assertTrue(command.contains("-t"), "a container session needs its own tty");
    assertTrue(command.contains("acme"));
    assertTrue(command.containsAll(List.of("bash", "-l")), "the default is a login shell");
    assertTrue(command.contains("TERM=xterm-256color"), "the terminal type crosses explicitly");
    assertTrue(command.contains("COLORTERM=truecolor"), "truecolor is declared, not probed");
    assertTrue(command.contains("TERM_PROGRAM=mast"), command.toString());
    assertTrue(command.contains("TERM_PROGRAM_VERSION=0.39.2"), command.toString());
    assertFalse(
        command.stream().anyMatch(arg -> arg.startsWith("SAIL_ROOM_ID=")),
        "an unbound session exports no room: " + command);
  }

  @Test
  void aRoomBoundProjectSessionCarriesTheRoomIntoTheContainer() {
    var env = PtySessionHost.childEnv("design-talk", "0.39.2");
    var command = PtySessionHost.childCommand(origin("acme", "design-talk", "claude"), env);

    assertEquals("design-talk", env.get("SAIL_ROOM_ID"));
    assertTrue(command.contains("SAIL_ROOM_ID=design-talk"), command.toString());
    assertEquals("--env", command.get(command.indexOf("SAIL_ROOM_ID=design-talk") - 1));
    assertEquals("claude", command.getLast());
  }

  @Test
  void theChildLearnsWhatTerminalItIsIn() {
    var env = PtySessionHost.childEnv("", "0.39.2");

    assertEquals("xterm-256color", env.get("TERM"), "containers ship no other terminfo");
    assertEquals("truecolor", env.get("COLORTERM"));
    assertEquals("mast", env.get("TERM_PROGRAM"));
    assertEquals("0.39.2", env.get("TERM_PROGRAM_VERSION"));
    assertFalse(env.containsKey("SAIL_ROOM_ID"));
  }

  @Test
  void aProjectSessionWrapsACustomCommandToo() {
    var command = PtySessionHost.childCommand(origin("acme", "", "htop"), Map.of());

    assertEquals("incus", command.getFirst());
    assertTrue(command.contains("htop"));
    assertEquals("htop", command.getLast());
  }

  @Test
  void aProjectSessionRejectsAnInvalidContainerName() {
    assertThrows(
        IllegalArgumentException.class,
        () -> PtySessionHost.childCommand(origin("proj;rm-rf", "", "bash"), Map.of()));
  }
}
