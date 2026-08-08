/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;
import org.junit.jupiter.api.Test;

class ContainerSetupSweepTest {

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

    var reconciled = ContainerSetupSweep.sweep(shell, Set.of("acme", "parked"));

    assertEquals(1, reconciled);
    var commands = String.join("\n", shell.invocations());
    assertTrue(commands.contains("acme"));
    assertFalse(commands.contains("rogue"), "a container outside the catalog is never touched");
    assertFalse(
        shell.invocations().stream().anyMatch(c -> c.contains("parked") && c.contains("device")),
        "a stopped container cannot be reconciled and is skipped");
  }

  @Test
  void reconcileRefreshesTheMountAndProbesTheMachineryStamp() throws Exception {
    var shell = new ScriptedShellExecutor(new ShellExec.Result(0, "", ""));

    assertTrue(ContainerSetupSweep.reconcile(shell, "acme"));

    var commands = String.join("\n", shell.invocations());
    assertTrue(
        commands.contains("config device"),
        "reconcile must force-refresh the socket bind mount: " + commands);
    assertTrue(
        commands.contains("cat " + ContainerSailSetup.STAMP_PATH),
        "reconcile must probe the machinery stamp: " + commands);
  }

  @Test
  void reconcileIsBestEffortAndNeverThrowsForAWedgedContainer() throws Exception {
    var wedged =
        new ScriptedShellExecutor(new ShellExec.Result(0, "", ""))
            .onFail("device add acme", "container wedged");

    assertFalse(ContainerSetupSweep.reconcile(wedged, "acme"));
  }

  @Test
  void sweepSurvivesAFailingContainerAndAnUnlistableDaemon() throws Exception {
    var failing =
        new ScriptedShellExecutor(new ShellExec.Result(0, "", ""))
            .onOk("incus list --format json", LIST_JSON)
            .onFail("device add acme", "container wedged");

    assertEquals(0, ContainerSetupSweep.sweep(failing, Set.of("acme")));

    var unlistable =
        new ScriptedShellExecutor(new ShellExec.Result(0, "", ""))
            .onFail("incus list --format json", "daemon down");

    assertEquals(0, ContainerSetupSweep.sweep(unlistable, Set.of("acme")));
  }
}
