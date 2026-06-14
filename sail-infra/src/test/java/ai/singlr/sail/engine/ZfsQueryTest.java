/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ZfsQueryTest {

  @Test
  void queryPoolParsesOutput() throws Exception {
    var shell = new ScriptedShellExecutor().onOk("zpool list", "960G\t150G\t810G\t15%");

    var usage = ZfsQuery.queryPool(shell, "devpool");

    assertNotNull(usage);
    assertEquals("960G", usage.size());
    assertEquals("150G", usage.allocated());
    assertEquals("810G", usage.free());
    assertEquals("15%", usage.capacityPercent());
  }

  @Test
  void queryPoolParsesSpaceSeparatedOutput() throws Exception {
    var shell = new ScriptedShellExecutor().onOk("zpool list", "960G   150G   810G   15%\n");

    var usage = ZfsQuery.queryPool(shell, "devpool");

    assertNotNull(usage);
    assertEquals("960G", usage.size());
    assertEquals("150G", usage.allocated());
    assertEquals("810G", usage.free());
    assertEquals("15%", usage.capacityPercent());
  }

  @Test
  void queryPoolReturnsNullOnFailure() throws Exception {
    var shell = new ScriptedShellExecutor().onFail("zpool list", "no such pool");

    var usage = ZfsQuery.queryPool(shell, "devpool");

    assertNull(usage);
  }

  @Test
  void queryPoolReturnsNullOnInsufficientColumns() throws Exception {
    var shell = new ScriptedShellExecutor().onOk("zpool list", "960G\t150G");

    var usage = ZfsQuery.queryPool(shell, "devpool");

    assertNull(usage);
  }

  @Test
  void queryPoolReturnsNullOnEmptyOutput() throws Exception {
    var shell = new ScriptedShellExecutor().onOk("zpool list", "  \n");

    var usage = ZfsQuery.queryPool(shell, "devpool");

    assertNull(usage);
  }

  @Test
  void queryPoolPassesCorrectCommand() throws Exception {
    var shell = new ScriptedShellExecutor().onOk("zpool list", "960G\t150G\t810G\t15%");

    ZfsQuery.queryPool(shell, "mypool");

    var invocation = shell.invocations().getFirst();
    assertTrue(invocation.contains("zpool list mypool -H -o size,alloc,free,cap"));
  }
}
