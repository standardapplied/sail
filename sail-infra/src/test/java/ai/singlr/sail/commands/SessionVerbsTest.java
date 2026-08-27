/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.commands;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

@EnabledOnOs(OS.LINUX)
class SessionVerbsTest {

  @TempDir Path dir;

  private int run(String... args) {
    return new CommandLine(new SessionCommand()).execute(args);
  }

  @Test
  void newLsKillRoundTripThroughTheRealHost() throws Exception {
    try (var host =
        PtyHostCommand.startHost(
            dir.resolve("h.sock"),
            dir.resolve("s"),
            token -> new ai.singlr.sail.pty.PtyIdentity("uday", true),
            ai.singlr.sail.pty.PtyEvents.NONE)) {
      var socket = dir.resolve("h.sock").toString();

      assertEquals(0, run("new", "--socket", socket, "t1", "--", "sh", "-c", "read a"));
      assertEquals(1, host.sessionCount());

      assertEquals(0, run("ls", "--socket", socket));

      assertEquals(0, run("kill", "t1", "--socket", socket));
      assertEquals(0, host.sessionCount());

      assertEquals(0, run("ls", "--socket", socket), "an empty listing is not an error");
    }
  }

  @Test
  void attachToAMissingSessionFailsBeforeTouchingTheTerminal() throws Exception {
    try (var host =
        PtyHostCommand.startHost(
            dir.resolve("h.sock"),
            dir.resolve("s"),
            token -> new ai.singlr.sail.pty.PtyIdentity("uday", true),
            ai.singlr.sail.pty.PtyEvents.NONE)) {
      var exit = run("attach", "ghost", "--socket", dir.resolve("h.sock").toString());
      assertNotEquals(0, exit, "a missing session is a loud failure, not a hung raw terminal");
      assertTrue(host.sessionCount() == 0);
    }
  }

  @Test
  void verbsExplainAMissingHostInsteadOfStackTracing() {
    var exit = run("ls", "--socket", dir.resolve("absent.sock").toString());
    assertNotEquals(0, exit);
  }
}
