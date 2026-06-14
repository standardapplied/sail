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
  void threeArgConstructorDefaultsSkipIfExistsToFalse() {
    var file = new GeneratedFile("/path/to/file", "content", false);

    assertFalse(file.skipIfExists());
  }

  @Test
  void threeArgConstructorPreservesExecutable() {
    var file = new GeneratedFile("/path/to/script.sh", "#!/bin/bash", true);

    assertTrue(file.executable());
    assertFalse(file.skipIfExists());
  }

  @Test
  void fourArgConstructorSetsSkipIfExists() {
    var file = new GeneratedFile("/path/to/CLAUDE.md", "content", false, true);

    assertTrue(file.skipIfExists());
    assertFalse(file.executable());
  }

  @Test
  void fourArgConstructorAllFields() {
    var file = new GeneratedFile("/home/dev/workspace/CLAUDE.md", "# Context", false, true);

    assertEquals("/home/dev/workspace/CLAUDE.md", file.remotePath());
    assertEquals("# Context", file.content());
    assertFalse(file.executable());
    assertTrue(file.skipIfExists());
  }
}
