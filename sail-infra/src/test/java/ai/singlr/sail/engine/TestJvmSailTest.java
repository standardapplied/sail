/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.engine;

import static org.junit.jupiter.api.Assertions.assertFalse;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * This module's tests launch agents through the real paths, which take lock files under the data
 * directory: they must be this test JVM's, never the person's Sail nor another fork's.
 */
class TestJvmSailTest {

  @Test
  void theLocksALaunchTakesAreThisJvmsOwn() {
    var persons = Path.of(System.getenv("HOME"), ".sail");
    assertFalse(SailPaths.locksDir().startsWith(persons), SailPaths.locksDir().toString());
    assertFalse(
        SailPaths.locksDir().startsWith(SailPaths.systemDataDir()),
        SailPaths.locksDir().toString());
  }
}
