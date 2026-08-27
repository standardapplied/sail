/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.commands;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

@EnabledOnOs(OS.LINUX)
class PtySelfTestCommandTest {

  @Test
  void openingARealPtySucceeds() {
    assertEquals(0, new PtySelfTestCommand().call(), "the pty FFM layer initializes and works");
  }
}
