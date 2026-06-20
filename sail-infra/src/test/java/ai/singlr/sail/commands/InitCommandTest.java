/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.commands;

import static org.junit.jupiter.api.Assertions.assertNotEquals;

import org.junit.jupiter.api.Test;
import picocli.CommandLine;

class InitCommandTest {

  @Test
  void rejectsBothAsMainAndMain() {
    var code = new CommandLine(new InitCommand()).execute("--as-main", "--main", "sail@host");
    assertNotEquals(0, code, "asking to be both main and a node is invalid");
  }

  @Test
  void rejectsNeitherAsMainNorMain() {
    var code = new CommandLine(new InitCommand()).execute();
    assertNotEquals(0, code, "the box must be told whether it is main or a node");
  }
}
