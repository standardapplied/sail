/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.engine;

import static org.junit.jupiter.api.Assertions.assertFalse;

import org.junit.jupiter.api.Test;

class SttyTest {

  @Test
  void aTestRunnersStdoutIsAPipeNotATerminal() {
    assertFalse(Stty.printsToTerminal(), "surefire reads this JVM's stdout through a pipe");
  }
}
