/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.commands;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CurrentProjectTest {

  @TempDir Path tempDir;

  private Path state() {
    return tempDir.resolve("current-project");
  }

  @Test
  void setThenGetRoundTrips() {
    CurrentProject.set(state(), "acme");
    assertEquals("acme", CurrentProject.get(state()).orElseThrow());
  }

  @Test
  void getIsEmptyWhenUnsetOrBlank() throws Exception {
    assertTrue(CurrentProject.get(state()).isEmpty());
    Files.writeString(state(), "   \n");
    assertTrue(CurrentProject.get(state()).isEmpty());
  }

  @Test
  void requireFavoursTheExplicitNameThenTheCurrent() {
    CurrentProject.set(state(), "acme");
    assertEquals("globex", CurrentProject.require("globex", state()), "explicit wins");
    assertEquals("acme", CurrentProject.require(null, state()), "falls back to current");
    assertEquals("acme", CurrentProject.require("  ", state()), "blank is not explicit");
  }

  @Test
  void requireFailsWithGuidanceWhenNeitherIsAvailable() {
    var e = assertThrows(IllegalStateException.class, () -> CurrentProject.require(null, state()));
    assertTrue(e.getMessage().contains("sail switch"), e.getMessage());
  }
}
