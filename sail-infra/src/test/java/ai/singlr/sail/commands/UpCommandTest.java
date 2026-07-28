/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.commands;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.sail.engine.ScriptedShellExecutor;
import ai.singlr.sail.engine.ShellExec;
import org.junit.jupiter.api.Test;

class UpCommandTest {

  @Test
  void reconcileSetupRefreshesTheMountAndHelperScripts() {
    var shell = new ScriptedShellExecutor(new ShellExec.Result(0, "", ""));

    UpCommand.reconcileSetup(shell, "acme");

    var commands = String.join("\n", shell.invocations());
    assertTrue(
        commands.contains("config device"),
        "up must force-refresh the socket bind mount: " + commands);
    assertTrue(
        commands.contains("grep -qsF"),
        "up must probe the helper scripts' staleness markers: " + commands);
  }

  @Test
  void reconcileSetupIsBestEffortAndNeverBlocksTheStart() {
    var wedged =
        new ScriptedShellExecutor(new ShellExec.Result(0, "", ""))
            .onFail("device add acme", "container wedged");

    assertDoesNotThrow(() -> UpCommand.reconcileSetup(wedged, "acme"));
  }
}
