/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.gen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class GeneratedFileTest {

  @Test
  void threeArgConstructorDefaultsToOverwrite() {
    var file = new GeneratedFile("/path/to/file", "content", false);

    assertNull(file.mergeMarker(), "a plain generated file is overwritten, not merged");
  }

  @Test
  void threeArgConstructorPreservesExecutable() {
    var file = new GeneratedFile("/path/to/script.sh", "#!/bin/bash", true);

    assertTrue(file.executable());
    assertNull(file.mergeMarker());
  }

  @Test
  void mergedFactoryCarriesTheMarkerAndIsNotExecutable() {
    var file = GeneratedFile.merged("/home/dev/workspace/CLAUDE.md", "# Context", "<!-- m -->");

    assertEquals("/home/dev/workspace/CLAUDE.md", file.remotePath());
    assertEquals("# Context", file.content());
    assertFalse(file.executable());
    assertEquals("<!-- m -->", file.mergeMarker());
  }
}
