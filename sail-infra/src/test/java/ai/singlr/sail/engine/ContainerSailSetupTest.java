/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import org.junit.jupiter.api.Test;

class ContainerSailSetupTest {

  private static final String CONTAINER = "light-grid";

  @Test
  void returnsAlreadyPresentWhenAllFilesExist() throws Exception {
    // refresh: device present, gets removed + re-added (3 incus calls: get, remove, add)
    // probe: all three sail files present → OK
    var shell =
        new ScriptedShellExecutor(new ShellExec.Result(0, "", ""))
            .onOk("config device get " + CONTAINER, "/run/sail\n")
            .onOk("test -f /home/dev/.sail/bin/sail-event.sh", "");

    var result = ContainerSailSetup.ensureInstalled(shell, CONTAINER);

    assertEquals(ContainerSailSetup.Result.ALREADY_PRESENT, result);
    var commands = shell.invocations();
    assertTrue(
        commands.stream().anyMatch(c -> c.contains("config device remove")),
        "the bind mount must be removed + re-added on every dispatch to refresh the inode");
    assertTrue(
        commands.stream().anyMatch(c -> c.contains("config device add")),
        "the bind mount must be re-added after removal");
  }

  @Test
  void aStaleSocketPathInTheScriptForcesAReinstall() throws Exception {
    var shell =
        new ScriptedShellExecutor(new ShellExec.Result(0, "", ""))
            .onOk("config device get " + CONTAINER, "/run/sail\n")
            .onFail("grep -qsF /var/lib/sail/run/api.sock", "");

    var result = ContainerSailSetup.ensureInstalled(shell, CONTAINER);

    assertEquals(
        ContainerSailSetup.Result.BACKFILLED,
        result,
        "a script still pointing at the old socket path is stale and must be rewritten");
    assertTrue(
        shell.invocations().stream()
            .anyMatch(c -> c.contains("grep -qsF /var/lib/sail/run/api.sock")),
        "the probe verifies the spec script references the current socket path, not just exists");
  }

  @Test
  void aHookFileMissingTheToolHeartbeatsForcesAReinstall() throws Exception {
    var shell =
        new ScriptedShellExecutor(new ShellExec.Result(0, "", ""))
            .onOk("config device get " + CONTAINER, "/run/sail\n")
            .onFail("grep -qsF " + ClaudeCodeHookConfig.PROGRESS_HOOK_MARKER, "");

    var result = ContainerSailSetup.ensureInstalled(shell, CONTAINER);

    assertEquals(
        ContainerSailSetup.Result.BACKFILLED,
        result,
        "a claude-settings.json written before the tool hooks existed is stale and must be "
            + "rewritten, or the stall watcher never sees progress and kills the agent at max_idle");
    assertTrue(
        shell.invocations().stream()
            .anyMatch(c -> c.contains("grep -qsF " + ClaudeCodeHookConfig.PROGRESS_HOOK_MARKER)),
        "the probe must verify the hook file carries the tool-progress heartbeats, not just exists");
  }

  @Test
  void refreshHappensEvenWhenSourcePathUnchanged() throws Exception {
    // Same source path on host and container — the bug class 0.12.5/0.12.6 missed.
    // refreshEventSocket must still tear down + re-add so the kernel re-resolves the inode.
    var hostDir = SailPaths.apiSocketHostDir().toString();
    var shell =
        new ScriptedShellExecutor(new ShellExec.Result(0, "", ""))
            .onOk("config device get " + CONTAINER, hostDir + "\n")
            .onOk("test -f /home/dev/.sail/bin/sail-event.sh", "");

    ContainerSailSetup.ensureInstalled(shell, CONTAINER);

    var commands = shell.invocations();
    assertTrue(
        commands.stream().anyMatch(c -> c.contains("config device remove")),
        "identical source paths must NOT short-circuit the refresh — that was the staleness bug");
  }

  @Test
  void addsFreshMountWhenNotConfigured() throws Exception {
    var shell =
        new ScriptedShellExecutor(new ShellExec.Result(0, "", ""))
            .onFail("config device get " + CONTAINER, "Device not found")
            .onOk("test -f /home/dev/.sail/bin/sail-event.sh", "");

    ContainerSailSetup.ensureInstalled(shell, CONTAINER);

    var commands = shell.invocations();
    assertTrue(
        commands.stream().anyMatch(c -> c.contains("config device add")),
        "fresh mount must be added when the device was not previously configured");
    assertFalse(
        commands.stream().anyMatch(c -> c.contains("config device remove")),
        "no remove needed when the device was absent");
  }

  @Test
  void probeCommandChecksEveryHelperFilePath() throws Exception {
    var shell =
        new ScriptedShellExecutor(new ShellExec.Result(0, "", ""))
            .onFail("config device get " + CONTAINER, "Device not found")
            .onOk("test -f /home/dev/.sail/bin/sail-event.sh", "");

    ContainerSailSetup.ensureInstalled(shell, CONTAINER);

    var probe =
        shell.invocations().stream().filter(c -> c.contains("test -f")).findFirst().orElseThrow();
    assertTrue(probe.contains(SailEventHelper.SCRIPT_PATH));
    assertTrue(probe.contains(SpecCliHelper.SCRIPT_PATH));
    assertTrue(probe.contains(ClaudeCodeHookConfig.SETTINGS_PATH));
    assertTrue(probe.contains(CodexHookConfig.SETTINGS_PATH));
    assertTrue(
        probe.contains(SpecCliHelper.PROFILE_PATH),
        "the probe must detect a container missing the spec-CLI PATH entry so reconfigure retrofits it");
  }

  @Test
  void backfillsHelperFilesWhenMissing() throws Exception {
    var shell =
        new ScriptedShellExecutor(new ShellExec.Result(0, "", ""))
            .onFail("config device get " + CONTAINER, "Device not found")
            .onFail("test -f /home/dev/.sail/bin/sail-event.sh", "missing");

    var result = ContainerSailSetup.ensureInstalled(shell, CONTAINER);

    assertEquals(ContainerSailSetup.Result.BACKFILLED, result);
    var commands = shell.invocations();
    assertTrue(
        commands.stream().anyMatch(c -> c.contains("mkdir -p /home/dev/.sail/bin")),
        "should re-install sail-event.sh helper");
    assertTrue(
        commands.stream().anyMatch(c -> c.contains("/home/dev/.sail/bin/spec")),
        "should re-install the spec CLI");
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
  void propagatesRefreshFailure() {
    var shell =
        new ScriptedShellExecutor(new ShellExec.Result(0, "", ""))
            .onFail("config device get", "Device not found")
            .onFail("config device add", "incus error");

    assertThrows(IOException.class, () -> ContainerSailSetup.ensureInstalled(shell, CONTAINER));
  }
}
