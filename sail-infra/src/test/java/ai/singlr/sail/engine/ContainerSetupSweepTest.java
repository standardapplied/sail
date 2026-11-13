/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class ContainerSetupSweepTest {

  @TempDir Path locks;

  private static final String LIST_JSON =
      """
      [{"name":"acme","status":"Running","state":{"network":{}}},
       {"name":"rogue","status":"Running","state":{"network":{}}},
       {"name":"parked","status":"Stopped"}]
      """;

  @Test
  void sweepRefreshesOnlyRunningCatalogProjects() throws Exception {
    var shell =
        new ScriptedShellExecutor(new ShellExec.Result(0, "", ""))
            .onOk("incus list --format json", LIST_JSON);

    var reconciled = ContainerSetupSweep.sweep(shell, Set.of("acme", "parked"), locks);

    assertEquals(1, reconciled);
    var commands = String.join("\n", shell.invocations());
    assertTrue(commands.contains("acme"));
    assertFalse(commands.contains("rogue"), "a container outside the catalog is never touched");
    assertFalse(
        shell.invocations().stream().anyMatch(c -> c.contains("parked") && c.contains("device")),
        "a stopped container cannot be reconciled and is skipped");
  }

  @Test
  void sweepAlsoRefreshesTheRunningWholeWorkspacesOfAHeldProjectAndNoTemplateCopyOrHalfMadeOne()
      throws Exception {
    var shell =
        new ScriptedShellExecutor(new ShellExec.Result(0, "", ""))
            .onOk(
                "incus list --format json",
                """
                [{"name": "acme-fix", "status": "Running",
                  "config": {"user.sail.project": "acme", "user.sail.workspace": "fix",
                             "user.sail.ready": "1"}},
                 {"name": "acme-half", "status": "Running",
                  "config": {"user.sail.project": "acme", "user.sail.workspace": "half"}},
                 {"name": "acme-copy", "status": "Running",
                  "config": {"user.sail.project": "acme", "user.sail.workspace": "fix",
                             "user.sail.ready": "1"}},
                 {"name": "acme-down", "status": "Stopped",
                  "config": {"user.sail.project": "acme", "user.sail.workspace": "down"}},
                 {"name": "acme--tmpl", "status": "Running", "config": {"user.sail.template.of": "acme"}},
                 {"name": "bravo-fix", "status": "Running",
                  "config": {"user.sail.project": "bravo", "user.sail.workspace": "fix"}},
                 {"name": "acme-half", "status": "Running", "config": {"user.sail.project": "acme"}}]
                """);

    var reconciled = ContainerSetupSweep.sweep(shell, Set.of("acme"), locks);

    assertEquals(1, reconciled);
    var touched =
        shell.invocations().stream()
            .filter(c -> c.contains("sail-api-sock"))
            .map(c -> c.split(" ")[4])
            .distinct()
            .toList();
    assertEquals(List.of("acme-fix"), touched);
  }

  @Test
  void reconcileRefreshesTheMountAndProbesTheMachinery() throws Exception {
    var shell = new ScriptedShellExecutor(new ShellExec.Result(0, "", ""));

    assertTrue(ContainerSetupSweep.reconcile(shell, "acme", locks));

    var commands = String.join("\n", shell.invocations());
    assertTrue(
        commands.contains("config device"),
        "reconcile must check the socket bind mount: " + commands);
    assertTrue(
        commands.contains(ContainerSailSetup.STAMP_PATH)
            && commands.contains(ContainerSailSetup.fingerprint()),
        "reconcile must verify the machinery stamp and contents: " + commands);
  }

  @Test
  void reconcileIsBestEffortAndNeverThrowsForAWedgedContainer() throws Exception {
    var wedged =
        new ScriptedShellExecutor(new ShellExec.Result(0, "", ""))
            .onFail("device add acme", "container wedged");

    assertFalse(ContainerSetupSweep.reconcile(wedged, "acme", locks));
  }

  @Test
  void sweepSurvivesAFailingContainerAndAnUnlistableDaemon() throws Exception {
    var failing =
        new ScriptedShellExecutor(new ShellExec.Result(0, "", ""))
            .onOk("incus list --format json", LIST_JSON)
            .onFail("device add acme", "container wedged");

    assertEquals(0, ContainerSetupSweep.sweep(failing, Set.of("acme"), locks));

    var unlistable =
        new ScriptedShellExecutor(new ShellExec.Result(0, "", ""))
            .onFail("incus list --format json", "daemon down");

    assertEquals(0, ContainerSetupSweep.sweep(unlistable, Set.of("acme"), locks));
  }

  @ParameterizedTest
  @CsvSource(
      delimiter = '|',
      value = {
        "Error: The device already exists | Error: The device already exists",
        "'' | exited 1"
      })
  void aFailedRefreshWarnsWithWhyIncusRefused(String stderr, String reason) throws Exception {
    var refusing =
        new ScriptedShellExecutor(new ShellExec.Result(0, "", ""))
            .onFail("config device get acme", "Error: Device not found")
            .onFail("device add acme", stderr);
    var err = System.err;
    var printed = new ByteArrayOutputStream();
    System.setErr(new PrintStream(printed, true, StandardCharsets.UTF_8));
    try {
      assertFalse(ContainerSetupSweep.reconcile(refusing, "acme", locks));
    } finally {
      System.setErr(err);
    }

    assertEquals(
        "sail setup: could not refresh helpers in 'acme' (Failed to add sail-api-sock to acme: "
            + reason
            + ")."
            + System.lineSeparator(),
        printed.toString(StandardCharsets.UTF_8));
  }
}
