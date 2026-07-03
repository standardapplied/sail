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
  void requireFavoursTheExplicitNameThenCwdThenTheCurrent() {
    CurrentProject.set(state(), "acme");
    assertEquals("globex", CurrentProject.require("globex", "initech", state()), "explicit wins");
    assertEquals("initech", CurrentProject.require(null, "initech", state()), "cwd beats current");
    assertEquals("acme", CurrentProject.require(null, null, state()), "falls back to current");
    assertEquals("acme", CurrentProject.require("  ", null, state()), "blank is not explicit");
  }

  @Test
  void requireFailsWithGuidanceNamingBothOptionsWhenNothingIsAvailable() {
    var e =
        assertThrows(
            IllegalStateException.class, () -> CurrentProject.require(null, null, state()));
    assertTrue(e.getMessage().contains("sail project switch"), e.getMessage());
    assertTrue(e.getMessage().contains("--project"), e.getMessage());
  }

  @Test
  void inferPrefersCwdOverCurrent() {
    CurrentProject.set(state(), "acme");
    assertEquals("initech", CurrentProject.infer("initech", state()).orElseThrow());
    assertEquals("acme", CurrentProject.infer(null, state()).orElseThrow());
    assertTrue(CurrentProject.infer(null, tempDir.resolve("missing")).isEmpty());
  }

  @Test
  void scopeResolvesExplicitThenInferred() {
    CurrentProject.set(state(), "acme");
    assertEquals("globex", CurrentProject.scope("globex", false, "initech", state()).orElseThrow());
    assertEquals("initech", CurrentProject.scope(null, false, "initech", state()).orElseThrow());
    assertEquals("acme", CurrentProject.scope(null, false, null, state()).orElseThrow());
  }

  @Test
  void scopeIsUnboundedForAllProjectsOrStar() {
    assertTrue(CurrentProject.scope(null, true, "initech", state()).isEmpty());
    assertTrue(CurrentProject.scope("*", false, "initech", state()).isEmpty());
    assertTrue(CurrentProject.scope("*", true, null, state()).isEmpty());
  }

  @Test
  void scopeRejectsAProjectCombinedWithAllProjects() {
    var e =
        assertThrows(
            IllegalArgumentException.class,
            () -> CurrentProject.scope("globex", true, null, state()));
    assertTrue(e.getMessage().contains("--all-projects"), e.getMessage());
  }

  @Test
  void scopeFailsWithGuidanceWhenNothingIsAvailable() {
    var e =
        assertThrows(
            IllegalStateException.class, () -> CurrentProject.scope(null, false, null, state()));
    assertTrue(e.getMessage().contains("--all-projects"), e.getMessage());
    assertTrue(e.getMessage().contains("sail project switch"), e.getMessage());
  }
}
