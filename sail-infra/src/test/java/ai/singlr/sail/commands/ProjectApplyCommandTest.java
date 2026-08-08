/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.commands;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.sail.Sail;
import ai.singlr.sail.engine.ContainerSailSetup;
import ai.singlr.sail.engine.ContainerState;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.List;
import org.junit.jupiter.api.Test;
import picocli.CommandLine;

class ProjectApplyCommandTest {

  private static ProjectApplyCommand.Outcome outcome(
      ContainerSailSetup.Result machinery, boolean hostnameRealigned) {
    return new ProjectApplyCommand.Outcome(3, 1, 4, machinery, hostnameRealigned, List.of());
  }

  @Test
  void planCreatesAnAbsentContainer() {
    assertEquals(
        ProjectApplyCommand.Action.CREATED,
        ProjectApplyCommand.plan(new ContainerState.NotCreated()));
  }

  @Test
  void planStartsAStoppedContainer() {
    assertEquals(
        ProjectApplyCommand.Action.STARTED, ProjectApplyCommand.plan(new ContainerState.Stopped()));
  }

  @Test
  void planOnlyConvergesARunningContainer() {
    assertEquals(
        ProjectApplyCommand.Action.CONVERGED,
        ProjectApplyCommand.plan(new ContainerState.Running("10.0.0.4")));
  }

  @Test
  void planFailsLoudOnAContainerError() {
    var error = new ContainerState.Error("incus list failed");

    var thrown = assertThrows(IllegalStateException.class, () -> ProjectApplyCommand.plan(error));
    assertTrue(thrown.getMessage().contains("incus list failed"));
  }

  @Test
  void summaryLineReportsActionCountsAndMachinery() {
    var line =
        ProjectApplyCommand.summaryLine(
            ProjectApplyCommand.Action.CREATED, outcome(ContainerSailSetup.Result.UPDATED, false));

    assertTrue(line.contains("created"));
    assertTrue(line.contains("3 added"));
    assertTrue(line.contains("1 removed"));
    assertTrue(line.contains("4 skipped"));
    assertTrue(line.contains("machinery updated"));
    assertFalse(line.contains("hostname"), "a hostname already current is not mentioned");
  }

  @Test
  void summaryLineNotesAHostnameThatHadToBeRealigned() {
    var line =
        ProjectApplyCommand.summaryLine(
            ProjectApplyCommand.Action.CONVERGED,
            outcome(ContainerSailSetup.Result.ALREADY_PRESENT, true));

    assertTrue(line.contains("converged"));
    assertTrue(line.contains("machinery current"));
    assertTrue(line.contains("hostname realigned"));
  }

  @Test
  void jsonSummaryCarriesTheFullDiffShape() {
    var row =
        ProjectApplyCommand.jsonSummary(
            "web",
            ProjectApplyCommand.Action.STARTED,
            new ProjectApplyCommand.Outcome(
                0,
                0,
                0,
                ContainerSailSetup.Result.UPDATED,
                true,
                List.of("no descriptor in the catalog — converged machinery only")));

    assertEquals("web", row.get("name"));
    assertEquals("started", row.get("action"));
    assertEquals(0, row.get("added"));
    assertEquals("updated", row.get("machinery"));
    assertEquals(true, row.get("hostname_realigned"));
    assertEquals(
        List.of("no descriptor in the catalog — converged machinery only"), row.get("warnings"));
  }

  @Test
  void jsonSummaryOmitsWarningsWhenThereAreNone() {
    var row =
        ProjectApplyCommand.jsonSummary(
            "web",
            ProjectApplyCommand.Action.CONVERGED,
            outcome(ContainerSailSetup.Result.ALREADY_PRESENT, false));

    assertEquals("current", row.get("machinery"));
    assertFalse(row.containsKey("warnings"));
  }

  @Test
  void provisioningRequiresRootOutsideDryRun() {
    var thrown =
        assertThrows(
            IllegalStateException.class,
            () -> ProjectApplyCommand.requireRootToProvision("web", false, false));

    assertTrue(thrown.getMessage().contains("Root privileges required"));
    assertTrue(thrown.getMessage().contains("sudo sail project apply web"));
  }

  @Test
  void dryRunAndRootSkipTheRootGate() {
    ProjectApplyCommand.requireRootToProvision("web", true, false);
    ProjectApplyCommand.requireRootToProvision("web", false, true);
  }

  @Test
  void helpAdvertisesAllFlags() {
    var cmd = new CommandLine(new Sail());
    var sw = new StringWriter();
    cmd.setOut(new PrintWriter(sw));

    var exit = cmd.execute("project", "apply", "--help");

    assertEquals(0, exit);
    var help = sw.toString();
    assertTrue(help.contains("--all"));
    assertTrue(help.contains("--dry-run"));
    assertTrue(help.contains("--json"));
    assertTrue(help.contains("--file"));
    assertTrue(help.contains("--yes"));
  }

  @Test
  void rejectsNameAndAllTogether() {
    var cmd = new CommandLine(new Sail());
    cmd.setErr(new PrintWriter(new StringWriter()));

    var exit = cmd.execute("project", "apply", "light-grid", "--all");

    assertNotEquals(0, exit);
  }

  @Test
  void rejectsFileWithAll() {
    var cmd = new CommandLine(new Sail());
    cmd.setErr(new PrintWriter(new StringWriter()));

    var exit = cmd.execute("project", "apply", "--all", "-f", "custom.yaml");

    assertNotEquals(0, exit);
  }

  @Test
  void deletedVerbsNoLongerParse() {
    for (var deleted : List.of("create", "reconfigure", "sync")) {
      var cmd = new CommandLine(new Sail());
      cmd.setErr(new PrintWriter(new StringWriter()));

      var exit = cmd.execute("project", deleted, "web");

      assertNotEquals(0, exit, "'project " + deleted + "' must not parse anymore");
    }
  }
}
