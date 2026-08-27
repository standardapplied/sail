/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.pty;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class PtySessionHostChildCommandTest {

  @Test
  void aProjectlessSessionRunsTheCommandAsGiven() {
    assertEquals(List.of("htop"), PtySessionHost.childCommand(List.of("htop"), null));
    assertEquals(List.of("htop"), PtySessionHost.childCommand(List.of("htop"), ""));
    assertEquals(List.of("htop"), PtySessionHost.childCommand(List.of("htop"), "   "));
  }

  @Test
  void aProjectlessEmptyCommandDefaultsToALoginShell() {
    assertEquals(List.of("bash", "-l"), PtySessionHost.childCommand(List.of(), null));
  }

  @Test
  void aProjectSessionWrapsTheCommandInTheDevUserTtyExecLane() {
    var command = PtySessionHost.childCommand(List.of(), "acme");

    assertEquals("incus", command.getFirst());
    assertTrue(command.contains("-t"), "a container session needs its own tty");
    assertTrue(command.contains("acme"));
    assertTrue(command.containsAll(List.of("bash", "-l")), "the default is a login shell");
  }

  @Test
  void aProjectSessionWrapsACustomCommandToo() {
    var command = PtySessionHost.childCommand(List.of("htop"), "acme");

    assertEquals("incus", command.getFirst());
    assertTrue(command.contains("htop"));
    assertEquals("htop", command.getLast());
  }

  @Test
  void aProjectSessionRejectsAnInvalidContainerName() {
    assertThrows(
        IllegalArgumentException.class,
        () -> PtySessionHost.childCommand(List.of("bash"), "proj;rm-rf"));
  }
}
