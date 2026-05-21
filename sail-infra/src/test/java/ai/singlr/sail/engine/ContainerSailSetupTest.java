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
  void returnsAlreadyPresentWhenMountMatchesAndAllFilesExist() throws Exception {
    // ensureEventSocket: existing source already equals the expected dir → ALREADY_PRESENT
    // file probe: all three sail files present → OK
    var hostDir = SailPaths.apiSocketHostDir().toString();
    var shell =
        new ScriptedShellExecutor()
            .onOk("config device get " + CONTAINER, hostDir + "\n")
            .onOk("test -f /home/dev/.sail/bin/sail-event.sh", "");

    var result = ContainerSailSetup.ensureInstalled(shell, CONTAINER);

    assertEquals(ContainerSailSetup.Result.ALREADY_PRESENT, result);
    assertEquals(
        2,
        shell.invocations().size(),
        "happy path: one device-get + one file probe, no installer runs");
  }

  @Test
  void backfillsWhenMountSourceDiffers() throws Exception {
    // ensureEventSocket: existing source is the legacy file-level mount → REPLACED
    var shell =
        new ScriptedShellExecutor(new ShellExec.Result(0, "", ""))
            .onOk("config device get " + CONTAINER, "/run/sail/api.sock\n")
            .onOk("test -f /home/dev/.sail/bin/sail-event.sh", "");

    var result = ContainerSailSetup.ensureInstalled(shell, CONTAINER);

    assertEquals(
        ContainerSailSetup.Result.BACKFILLED,
        result,
        "mount source changing from /run/sail/api.sock to /run/sail must trigger backfill");
    var commands = shell.invocations();
    assertTrue(
        commands.stream().anyMatch(c -> c.contains("config device remove")),
        "legacy device should be removed before re-adding with the directory source");
    assertTrue(
        commands.stream().anyMatch(c -> c.contains("config device add")),
        "new device should be added pointing at the directory");
  }

  @Test
  void probeCommandChecksAllThreeFilePaths() throws Exception {
    var hostDir = SailPaths.apiSocketHostDir().toString();
    var shell =
        new ScriptedShellExecutor()
            .onOk("config device get " + CONTAINER, hostDir + "\n")
            .onOk("test -f /home/dev/.sail/bin/sail-event.sh", "");

    ContainerSailSetup.ensureInstalled(shell, CONTAINER);

    var probe =
        shell.invocations().stream().filter(c -> c.contains("test -f")).findFirst().orElseThrow();
    assertTrue(probe.contains(SailEventHelper.SCRIPT_PATH));
    assertTrue(probe.contains(ClaudeCodeHookConfig.SETTINGS_PATH));
    assertTrue(probe.contains(CodexHookConfig.SETTINGS_PATH));
  }

  @Test
  void backfillsWhenFilesMissing() throws Exception {
    var hostDir = SailPaths.apiSocketHostDir().toString();
    var shell =
        new ScriptedShellExecutor(new ShellExec.Result(0, "", ""))
            .onOk("config device get " + CONTAINER, hostDir + "\n")
            .onFail("test -f /home/dev/.sail/bin/sail-event.sh", "missing");

    var result = ContainerSailSetup.ensureInstalled(shell, CONTAINER);

    assertEquals(ContainerSailSetup.Result.BACKFILLED, result);
    var commands = shell.invocations();
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
  void propagatesEnsureSocketFailure() {
    var shell =
        new ScriptedShellExecutor(new ShellExec.Result(0, "", ""))
            .onFail("config device get", "Device not found")
            .onFail("config device add", "incus error");

    assertThrows(IOException.class, () -> ContainerSailSetup.ensureInstalled(shell, CONTAINER));
  }
}
