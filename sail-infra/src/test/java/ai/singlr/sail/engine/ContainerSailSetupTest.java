/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.LinkedHashMap;
import org.junit.jupiter.api.Test;

class ContainerSailSetupTest {

  private static final String CONTAINER = "light-grid";
  private static final String PROBE = "cmp -s --";

  @Test
  void aVerifiedContainerIsAlreadyPresentAndRunsNoInstaller() throws Exception {
    var shell =
        new ScriptedShellExecutor(new ShellExec.Result(0, "", ""))
            .onOk("config device get " + CONTAINER, "/run/sail\n")
            .onOk(PROBE);

    var result = ContainerSailSetup.ensureInstalled(shell, CONTAINER);

    assertEquals(ContainerSailSetup.Result.ALREADY_PRESENT, result);
    assertTrue(
        shell.invocations().stream().noneMatch(c -> c.contains("chmod 0755")),
        "a current container must cost zero installer shells on the dispatch hot path");
    assertEquals(
        1,
        shell.invocations().stream().filter(c -> c.contains(CONTAINER + " --user 1000")).count(),
        "the staleness probe is exactly one shell: verify stamp and contents");
  }

  @Test
  void theProbeVerifiesObservedContentsNotJustTheStamp() throws Exception {
    var shell =
        new ScriptedShellExecutor(new ShellExec.Result(0, "", ""))
            .onOk("config device get " + CONTAINER, "/run/sail\n")
            .onOk(PROBE);

    ContainerSailSetup.ensureInstalled(shell, CONTAINER);

    var probe =
        shell.invocations().stream().filter(c -> c.contains(PROBE)).findFirst().orElseThrow();
    assertTrue(probe.contains(ContainerSailSetup.STAMP_PATH), "the probe must read the stamp");
    assertTrue(
        probe.contains(ContainerSailSetup.fingerprint()),
        "the probe must carry the expected fingerprint");
    ContainerSailSetup.installedFiles()
        .forEach(
            (path, content) -> {
              assertTrue(probe.contains(path), "the probe must check the installed file " + path);
              assertTrue(
                  probe.contains(content),
                  "the probe must compare " + path + " against this binary's payload");
            });
  }

  @Test
  void theProbeRequiresBinScriptsToStayExecutable() throws Exception {
    var shell =
        new ScriptedShellExecutor(new ShellExec.Result(0, "", ""))
            .onOk("config device get " + CONTAINER, "/run/sail\n")
            .onOk(PROBE);

    ContainerSailSetup.ensureInstalled(shell, CONTAINER);

    var probe =
        shell.invocations().stream().filter(c -> c.contains(PROBE)).findFirst().orElseThrow();
    assertTrue(
        probe.contains("[ -x \"$path\" ]"),
        "byte-identical scripts with a dropped executable bit are still stale — hooks and the"
            + " spec CLI fail with EACCES, so the probe must fail and trigger a heal");
    assertTrue(
        probe.contains(SpecCliHelper.SCRIPT_DIR),
        "the executable check must scope to the bin directory the scripts install into");
  }

  @Test
  void theSplitOverloadProbesOnTheObserverAndInstallsOnTheMutator() throws Exception {
    var probe = new ScriptedShellExecutor(new ShellExec.Result(0, "", "")).onFail(PROBE, "stale");
    var mutator =
        new ScriptedShellExecutor(new ShellExec.Result(0, "", ""))
            .onOk("config device get " + CONTAINER, "/run/sail\n");

    var result = ContainerSailSetup.ensureInstalled(probe, mutator, CONTAINER);

    assertEquals(ContainerSailSetup.Result.UPDATED, result);
    assertTrue(
        probe.invocations().stream().allMatch(c -> c.contains(PROBE)),
        "the observer shell must carry only the staleness probe");
    assertTrue(
        mutator.invocations().stream().noneMatch(c -> c.contains(PROBE)),
        "the mutator shell must never carry the probe — a dry-run mutator would blind it");
    assertTrue(
        mutator.invocations().stream().anyMatch(c -> c.contains("chmod 0755")),
        "installers must ride the mutator shell");
  }

  @Test
  void aFailedVerificationInstallsEverythingAndStamps() throws Exception {
    var shell =
        new ScriptedShellExecutor(new ShellExec.Result(0, "", ""))
            .onOk("config device get " + CONTAINER, "/run/sail\n")
            .onFail(PROBE, "No such file or directory");

    var result = ContainerSailSetup.ensureInstalled(shell, CONTAINER);

    assertEquals(ContainerSailSetup.Result.UPDATED, result);
    var commands = shell.invocations();
    for (var path : ContainerSailSetup.installedFiles().keySet()) {
      if (path.equals(SpecCliHelper.PROFILE_PATH)) {
        continue;
      }
      assertTrue(
          commands.stream().anyMatch(c -> c.endsWith(" " + path)),
          "every sail-owned file must be rewritten, missing: " + path);
    }
    assertTrue(
        commands.stream().anyMatch(c -> c.contains(SpecCliHelper.PROFILE_PATH)),
        "the profile PATH line must be ensured");
    assertTrue(
        commands.stream()
            .anyMatch(
                c ->
                    c.contains(ContainerSailSetup.fingerprint())
                        && c.endsWith(" " + ContainerSailSetup.STAMP_PATH)),
        "a full install must stamp the current fingerprint");
  }

  @Test
  void aTamperedPayloadReinstallsAndRestampsEvenWithACurrentStamp() throws Exception {
    var shell =
        new ScriptedShellExecutor(new ShellExec.Result(0, "", ""))
            .onOk("config device get " + CONTAINER, "/run/sail\n")
            .onFail(PROBE, "");

    var result = ContainerSailSetup.ensureInstalled(shell, CONTAINER);

    assertEquals(
        ContainerSailSetup.Result.UPDATED,
        result,
        "observed state that does not match what this binary would install is stale by"
            + " definition — a matching stamp alone is never proof");
    assertTrue(
        shell.invocations().stream()
            .anyMatch(
                c ->
                    c.contains(ContainerSailSetup.fingerprint())
                        && c.endsWith(" " + ContainerSailSetup.STAMP_PATH)),
        "the reinstall must rewrite the stamp to the current fingerprint");
  }

  @Test
  void fingerprintIsDeterministic() {
    assertEquals(ContainerSailSetup.fingerprint(), ContainerSailSetup.fingerprint());
    assertEquals(
        ContainerSailSetup.fingerprint(),
        ContainerSailSetup.fingerprintOf(ContainerSailSetup.installedFiles()));
  }

  @Test
  void fingerprintChangesWhenAnySinglePayloadChanges() {
    var baseline = ContainerSailSetup.fingerprint();
    for (var path : ContainerSailSetup.installedFiles().keySet()) {
      var mutated = ContainerSailSetup.installedFiles();
      mutated.put(path, mutated.get(path) + "\n# drift");
      assertNotEquals(
          baseline,
          ContainerSailSetup.fingerprintOf(mutated),
          "a change to " + path + " must change the fingerprint");
    }
  }

  @Test
  void fingerprintCoversEveryInstalledFile() {
    var files = ContainerSailSetup.installedFiles();

    assertEquals(
        java.util.List.of(
            SailEventHelper.SCRIPT_PATH,
            SailStopGate.SCRIPT_PATH,
            SailRoomRelay.SCRIPT_PATH,
            SailSessionReport.SCRIPT_PATH,
            SpecCliHelper.SCRIPT_PATH,
            SpecCliHelper.PROFILE_PATH,
            ClaudeCodeHookConfig.SETTINGS_PATH,
            CodexHookConfig.SETTINGS_PATH),
        java.util.List.copyOf(files.keySet()),
        "the fingerprint must cover every sail-owned in-container file, in stable order — a"
            + " script riding in this list IS its rollout: the fingerprint changes and every"
            + " container converges on next apply or dispatch");
    files.forEach((path, content) -> assertFalse(content.isBlank(), path + " has no payload"));
  }

  @Test
  void theSessionReportRidesTheFingerprintSoItRollsOutByItself() {
    var without = new LinkedHashMap<>(ContainerSailSetup.installedFiles());
    without.remove(SailSessionReport.SCRIPT_PATH);

    assertNotEquals(
        ContainerSailSetup.fingerprint(),
        ContainerSailSetup.fingerprintOf(without),
        "shipping the session report changes the fingerprint — that IS the rollout: every"
            + " container converges on next apply or dispatch");
  }

  @Test
  void fingerprintDependsOnPathOrderAndBoundaries() {
    var swapped = new LinkedHashMap<String, String>();
    ContainerSailSetup.installedFiles().reversed().forEach(swapped::put);

    assertNotEquals(
        ContainerSailSetup.fingerprint(),
        ContainerSailSetup.fingerprintOf(swapped),
        "the hash is over ordered (path, content) pairs, so order is part of the content");
  }

  @Test
  void refreshHappensEvenWhenSourcePathUnchanged() throws Exception {
    var hostDir = SailPaths.apiSocketHostDir().toString();
    var shell =
        new ScriptedShellExecutor(new ShellExec.Result(0, "", ""))
            .onOk("config device get " + CONTAINER, hostDir + "\n")
            .onOk(PROBE);

    ContainerSailSetup.ensureInstalled(shell, CONTAINER);

    var commands = shell.invocations();
    assertTrue(
        commands.stream().anyMatch(c -> c.contains("config device remove")),
        "identical source paths must NOT short-circuit the refresh — Incus tracks the bind by"
            + " inode, and the source directory can be recreated under the same path");
    assertTrue(
        commands.stream().anyMatch(c -> c.contains("config device add")),
        "the bind mount must be re-added after removal");
  }

  @Test
  void addsFreshMountWhenNotConfigured() throws Exception {
    var shell =
        new ScriptedShellExecutor(new ShellExec.Result(0, "", ""))
            .onFail("config device get " + CONTAINER, "Device not found")
            .onOk(PROBE);

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
  void aFailedStampWriteFailsLoud() {
    var shell =
        new ScriptedShellExecutor(new ShellExec.Result(0, "", ""))
            .onOk("config device get " + CONTAINER, "/run/sail\n")
            .onFail(PROBE, "missing")
            .onFail(ContainerSailSetup.STAMP_PATH, "read-only filesystem");

    assertThrows(IOException.class, () -> ContainerSailSetup.ensureInstalled(shell, CONTAINER));
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
