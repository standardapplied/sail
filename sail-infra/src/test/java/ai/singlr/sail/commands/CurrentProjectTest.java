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
  void requireFailsWithGuidanceNamingBothOptionsWhenNothingIsAvailable() {
    var e = assertThrows(IllegalStateException.class, () -> CurrentProject.require(null, state()));
    assertTrue(e.getMessage().contains("sail project switch"), e.getMessage());
    assertTrue(e.getMessage().contains("--project"), e.getMessage());
  }

  @Test
  void scopeResolvesExplicitThenTheCurrent() {
    CurrentProject.set(state(), "acme");
    assertEquals("globex", CurrentProject.scope("globex", false, state()).orElseThrow());
    assertEquals("acme", CurrentProject.scope(null, false, state()).orElseThrow());
  }

  @Test
  void scopeIsUnboundedForAllProjectsOrStar() {
    assertTrue(CurrentProject.scope(null, true, state()).isEmpty());
    assertTrue(CurrentProject.scope("*", false, state()).isEmpty());
    assertTrue(CurrentProject.scope("*", true, state()).isEmpty());
  }

  @Test
  void scopeRejectsAProjectCombinedWithAllProjects() {
    var e =
        assertThrows(
            IllegalArgumentException.class, () -> CurrentProject.scope("globex", true, state()));
    assertTrue(e.getMessage().contains("--all-projects"), e.getMessage());
  }

  @Test
  void scopeFailsWithGuidanceWhenNothingIsAvailable() {
    var e =
        assertThrows(IllegalStateException.class, () -> CurrentProject.scope(null, false, state()));
    assertTrue(e.getMessage().contains("--all-projects"), e.getMessage());
    assertTrue(e.getMessage().contains("sail project switch"), e.getMessage());
  }
}
