/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.commands;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.sail.pty.PtySessionHost;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

@EnabledOnOs(OS.LINUX)
class SessionClientTest {

  @TempDir Path dir;

  @Test
  void verbsRoundTripAndErrorsSurfaceAsExceptions() throws Exception {
    try (var host =
        new PtySessionHost(
            dir.resolve("h.sock"),
            dir.resolve("s"),
            64 * 1024,
            token -> new ai.singlr.sail.pty.PtyIdentity("uday", true),
            ai.singlr.sail.pty.PtyEvents.NONE)) {
      host.start();
      try (var client = SessionClient.connect(dir.resolve("h.sock"))) {
        client.create("s1", List.of("sh", "-c", "read a"), "/tmp", "", 80, 24);
        var listed = client.list();
        assertEquals(1, listed.size());
        assertEquals("s1", listed.getFirst().name());
        assertTrue(listed.getFirst().live());

        var dup =
            assertThrows(
                IOException.class, () -> client.create("s1", List.of("sh"), "/tmp", "", 80, 24));
        assertTrue(dup.getMessage().contains("already running"));

        client.kill("s1");
        assertEquals(0, client.list().size());
      }
    }
  }

  @Test
  void aMissingHostExplainsItselfInsteadOfStackTracing() {
    var error =
        assertThrows(IOException.class, () -> SessionClient.connect(dir.resolve("absent.sock")));
    assertTrue(error.getMessage().contains("session host"), error.getMessage());
  }
}
