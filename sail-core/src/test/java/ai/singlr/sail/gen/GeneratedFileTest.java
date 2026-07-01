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
  void carriesPathContentAndExecutableBit() {
    var file = new GeneratedFile("/home/dev/.claude/CLAUDE.md", "# context", false);

    assertEquals("/home/dev/.claude/CLAUDE.md", file.remotePath());
    assertEquals("# context", file.content());
    assertFalse(file.executable());
  }

  @Test
  void anExecutableFileIsMarkedExecutable() {
    var file = new GeneratedFile("/home/dev/.sail/spec-skill.sh", "#!/usr/bin/env bash", true);

    assertTrue(file.executable());
  }
}
