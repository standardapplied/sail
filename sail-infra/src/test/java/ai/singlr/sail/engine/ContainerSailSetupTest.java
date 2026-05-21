/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.engine;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import org.junit.jupiter.api.Test;

class ContainerSailSetupTest {

  private static final String CONTAINER = "light-grid";

  @Test
  void returnsAlreadyPresentWhenAllThreeFilesExist() throws Exception {
    var shell = new ScriptedShellExecutor().onOk("test -f /home/dev/.sail/bin/sail-event.sh", "");

    var result = ContainerSailSetup.ensureInstalled(shell, CONTAINER);

    assertEquals(ContainerSailSetup.Result.ALREADY_PRESENT, result);
    assertEquals(
        1,
        shell.invocations().size(),
        "happy path is one shell call — no installer should run when files exist");
  }

  @Test
  void probeCommandChecksAllThreeFilePaths() throws Exception {
    var shell = new ScriptedShellExecutor().onOk("test -f /home/dev/.sail/bin/sail-event.sh", "");

    ContainerSailSetup.ensureInstalled(shell, CONTAINER);

    var probe = shell.invocations().getFirst();
    assertTrue(probe.contains(SailEventHelper.SCRIPT_PATH));
    assertTrue(probe.contains(ClaudeCodeHookConfig.SETTINGS_PATH));
    assertTrue(probe.contains(CodexHookConfig.SETTINGS_PATH));
  }

  @Test
  void backfillsWhenProbeFails() throws Exception {
    var shell =
        new ScriptedShellExecutor(new ShellExec.Result(0, "", ""))
            .onFail("test -f /home/dev/.sail/bin/sail-event.sh", "missing")
            .onFail("config device get", "Device not found");

    var result = ContainerSailSetup.ensureInstalled(shell, CONTAINER);

    assertEquals(ContainerSailSetup.Result.BACKFILLED, result);
    var commands = shell.invocations();
    assertTrue(commands.size() > 1, "backfill must execute multiple installer commands");
    assertTrue(
        commands.stream().anyMatch(c -> c.contains("mkdir -p /home/dev/.sail/bin")),
        "should re-install sail-event.sh helper");
    assertTrue(
        commands.stream().anyMatch(c -> c.contains("mkdir -p /home/dev/.sail")),
        "should re-install claude-settings.json");
    assertTrue(
        commands.stream().anyMatch(c -> c.contains("mkdir -p /home/dev/.codex")),
        "should re-install codex hooks.json");
  }

  @Test
  void rejectsInvalidContainerName() {
    var shell = new ScriptedShellExecutor();

    assertThrows(Exception.class, () -> ContainerSailSetup.ensureInstalled(shell, "../bad"));
  }

  @Test
  void propagatesInstallerFailure() {
    var shell =
        new ScriptedShellExecutor(new ShellExec.Result(0, "", ""))
            .onFail("test -f /home/dev/.sail/bin/sail-event.sh", "missing")
            .onFail("config device get", "Device not found")
            .onFail("config device add", "incus error");

    assertThrows(IOException.class, () -> ContainerSailSetup.ensureInstalled(shell, CONTAINER));
  }
}
