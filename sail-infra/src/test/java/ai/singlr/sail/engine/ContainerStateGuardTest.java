/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.engine;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

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
  void requireRunningRejectsNotCreatedWithCreateHint() {
    var ex =
        assertThrows(
            IllegalStateException.class,
            () -> ContainerStateGuard.requireRunning(new ContainerState.NotCreated(), "demo"));
    assertEquals(
        "Project 'demo' does not exist. Run 'sail project create' first.", ex.getMessage());
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
        "Project 'demo' does not exist. Run 'sail project create' first.", ex.getMessage());
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
