/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.gen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class GeneratedFileTest {

  @Test
  void threeArgConstructorIsSailOwned() {
    var file = new GeneratedFile("/path/to/file", "content", false);

    assertEquals(
        GeneratedFile.Ownership.SAIL, file.ownership(), "a plain generated file is sail-owned");
  }

  @Test
  void threeArgConstructorPreservesExecutable() {
    var file = new GeneratedFile("/path/to/script.sh", "#!/bin/bash", true);

    assertTrue(file.executable());
    assertEquals(GeneratedFile.Ownership.SAIL, file.ownership());
  }

  @Test
  void engineerOwnedFactoryIsEngineerOwnedAndNotExecutable() {
    var file = GeneratedFile.engineerOwned("/home/dev/workspace/CLAUDE.md", "# Context");

    assertEquals("/home/dev/workspace/CLAUDE.md", file.remotePath());
    assertEquals("# Context", file.content());
    assertFalse(file.executable());
    assertEquals(GeneratedFile.Ownership.ENGINEER, file.ownership());
  }
}
