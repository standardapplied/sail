/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.commands;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

class PtySelfTestCommandTest {

  @Test
  void theSelfTestPassesOnThisSystem() {
    assertEquals(0, new PtySelfTestCommand().call(), "the pty FFM layer initializes and works");
  }

  @Test
  void thePtyProbeSpawnsThroughTheShimResizesAndReadsTheChildsSize() {
    assertDoesNotThrow(PtySelfTestCommand::ptyProbe);
  }

  @Test
  void theTcpProbeOpensASessionWithATicketOverLoopbackAndHearsItsEcho() {
    assertDoesNotThrow(PtySelfTestCommand::tcpProbe);
  }

  /** systemd's descriptor store, which only Linux has. */
  @Test
  @EnabledOnOs(OS.LINUX)
  void theStoreProbePushesAMasterThroughTheBindingAndReadsThroughTheCopy() {
    assertDoesNotThrow(PtySelfTestCommand::fdStoreProbe);
  }

  @Test
  void theJsonProbeCoversEveryJsonVerbsRecord() {
    var json = PtySelfTestCommand.jsonProbe();
    for (var key : PtySelfTestCommand.JSON_KEYS) {
      assertTrue(json.contains("\"" + key + "\""), "expected '" + key + "' in " + json);
    }
  }
}
