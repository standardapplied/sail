/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

class DirQueryTest {

  @Test
  void parsesOutput() throws Exception {
    var output =
        """
             Size  Used Avail Use%
             894G  120G  774G  14%
            """;
    var shell = new ScriptedShellExecutor().on("df", new ShellExec.Result(0, output, ""));

    var usage = DirQuery.queryFilesystem(shell);

    assertNotNull(usage);
    assertEquals("894G", usage.size());
    assertEquals("120G", usage.used());
    assertEquals("774G", usage.available());
    assertEquals("14%", usage.usePercent());
  }

  @Test
  void returnsNullOnFailure() throws Exception {
    var shell = new ScriptedShellExecutor().onFail("df", "No such file or directory");

    var usage = DirQuery.queryFilesystem(shell);

    assertNull(usage);
  }

  @Test
  void returnsNullOnInsufficientColumns() throws Exception {
    var output = "  Size\n  894G\n";
    var shell = new ScriptedShellExecutor().on("df", new ShellExec.Result(0, output, ""));

    var usage = DirQuery.queryFilesystem(shell);

    assertNull(usage);
  }
}
