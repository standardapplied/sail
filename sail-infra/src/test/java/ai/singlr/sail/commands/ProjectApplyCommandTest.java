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
import ai.singlr.sail.engine.IncusDeviceManager;
import ai.singlr.sail.engine.ScriptedShellExecutor;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
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
  void rejectsJsonCombinedWithDryRun() {
    var err = new StringWriter();
    var cmd = new CommandLine(new Sail());
    cmd.setErr(new PrintWriter(err));

    var exit = cmd.execute("project", "apply", "web", "--json", "--dry-run");

    assertNotEquals(0, exit, "dry-run narration on stdout would corrupt the JSON document");
    assertTrue(err.toString().contains("--json and --dry-run"));
  }

  @Test
  void dryRunSkipsCanonicalBundlePersistenceEntirely() throws Exception {
    var missingSource = Path.of("/definitely/not/there/sail.yaml");

    ProjectApplyCommand.persistCanonicalBundle("web", missingSource, true);

    assertThrows(
        Exception.class,
        () ->
            ProjectApplyCommand.syncProjectBundle(
                missingSource, missingSource.resolveSibling("canonical.yaml")),
        "the same source outside dry-run would fail loudly — proof the dry run never reached"
            + " the filesystem");
  }

  @Test
  void rejectsFileWithAll() {
    var cmd = new CommandLine(new Sail());
    cmd.setErr(new PrintWriter(new StringWriter()));

    var exit = cmd.execute("project", "apply", "--all", "-f", "custom.yaml");

    assertNotEquals(0, exit);
  }

  @Test
  void aDescriptorNameMismatchFailsBeforeAnyContainerWork() {
    var thrown =
        assertThrows(
            IllegalStateException.class,
            () -> ProjectApplyCommand.requireMatchingName("alpha", "beta", Path.of("sail.yaml")));

    assertTrue(thrown.getMessage().contains("alpha"));
    assertTrue(thrown.getMessage().contains("beta"));
    assertTrue(thrown.getMessage().contains("sail.yaml"));
  }

  @Test
  void aMatchingOrOmittedNamePassesTheMismatchGate() {
    ProjectApplyCommand.requireMatchingName("alpha", "alpha", Path.of("sail.yaml"));
    ProjectApplyCommand.requireMatchingName(null, "beta", null);
    ProjectApplyCommand.requireMatchingName("", "beta", null);
  }

  @Test
  void applyRefusesADescriptorThatRedirectsToAnotherProject(@TempDir Path dir) throws Exception {
    var yaml = dir.resolve("sail.yaml");
    Files.writeString(yaml, "name: beta\n");
    var cmd = new CommandLine(new Sail());
    cmd.setErr(new PrintWriter(new StringWriter()));

    var exit = cmd.execute("project", "apply", "alpha", "-f", yaml.toString());

    assertNotEquals(0, exit, "a descriptor naming a different project must never be applied");
  }

  @Test
  void planTargetValidatesTheDescriptorBeforeAnyLifecycleMutation() {
    var thrown =
        assertThrows(
            IllegalStateException.class,
            () ->
                ProjectApplyCommand.planTarget(
                    "alpha", new ContainerState.Stopped(), "name: beta\n"));
    assertTrue(
        thrown.getMessage().contains("beta"),
        "a bad descriptor must fail before bulk apply starts the container, leaving observed"
            + " state untouched — the single-project path already behaves this way");
  }

  @Test
  void planTargetResolvesActionAndConfigForAValidTarget() {
    var plan =
        ProjectApplyCommand.planTarget("alpha", new ContainerState.Stopped(), "name: alpha\n");
    assertEquals(ProjectApplyCommand.Action.STARTED, plan.action());
    assertEquals("alpha", plan.config().name());

    var machineryOnly =
        ProjectApplyCommand.planTarget("legacy", new ContainerState.Running("10.0.0.4"), null);
    assertEquals(ProjectApplyCommand.Action.CONVERGED, machineryOnly.action());
    assertEquals(null, machineryOnly.config(), "no descriptor means machinery-only convergence");
  }

  @Test
  void rejectsLiteralSailYamlFileWithAll() {
    var cmd = new CommandLine(new Sail());
    cmd.setErr(new PrintWriter(new StringWriter()));

    var exit = cmd.execute("project", "apply", "--all", "-f", "sail.yaml");

    assertNotEquals(
        0,
        exit,
        "this command's -f has no default, so the literal 'sail.yaml' is an explicit override"
            + " and must hit the same --all incompatibility as any other path");
  }

  @Test
  void anExplicitFileIsNeverReinterpretedAsAbsent() {
    assertFalse(
        ProjectApplyCommand.usesCatalog("sail.yaml", "alpha"),
        "an operator who typed -f sail.yaml named a file; the legacy default-value mapping of"
            + " older commands must not silently redirect apply to the catalog");
    assertTrue(ProjectApplyCommand.usesCatalog(null, "alpha"));
    assertFalse(ProjectApplyCommand.usesCatalog(null, null));
  }

  @Test
  void allSummaryLineTurnsRedOnAnyFailure() {
    assertTrue(ProjectApplyCommand.allSummaryLine(3, 0).contains("bold,green ✓"));
    assertTrue(ProjectApplyCommand.allSummaryLine(3, 0).contains("Applied 3."));
    var failing = ProjectApplyCommand.allSummaryLine(2, 1);
    assertTrue(failing.contains("bold,red ✗"));
    assertTrue(failing.contains("Applied 2, failed 1."));
  }

  @Test
  void applyAllFailsTheCommandWhenAnyProjectFailed() {
    var thrown =
        assertThrows(IllegalStateException.class, () -> ProjectApplyCommand.requireAllApplied(2));

    assertTrue(thrown.getMessage().contains("2 project(s)"));
    ProjectApplyCommand.requireAllApplied(0);
  }

  @Test
  void allOnlyTargetsSailManagedContainers() throws Exception {
    var probe =
        new ScriptedShellExecutor()
            .onOk("incus config device get legacy sail-api-sock source", "/run/sail/api");
    var devices = new IncusDeviceManager(probe);

    assertTrue(
        ProjectApplyCommand.sailManaged("legacy", devices),
        "the API-socket device is the instance-level provenance Sail itself attached");
    assertFalse(
        ProjectApplyCommand.sailManaged("web", devices),
        "a catalog row is name-level intent, never instance provenance: a stale entry left by"
            + " destroy-without-purge must not hand a foreign container with a colliding name"
            + " the API mount and box credential");
    assertFalse(
        ProjectApplyCommand.sailManaged("foreign", devices),
        "an unrelated Incus instance must never receive the API mount and box credential");
  }

  @Test
  void aCatalogRowWithoutTheDeviceGetsAVisibleWarningNotASilentSkip() {
    var warning = ProjectApplyCommand.unmanagedSkipWarning("web", true);
    assertTrue(warning.contains("web"));
    assertTrue(
        warning.contains("sail project apply web"),
        "the operator is told the explicit single-project escape hatch");
    assertEquals(
        null,
        ProjectApplyCommand.unmanagedSkipWarning("stranger", false),
        "a foreign container with no catalog row is not Sail's to narrate");
  }

  @Test
  void anInvalidInstanceNameIsForeignBeforeAnyProbeRuns() throws Exception {
    var probe = new ScriptedShellExecutor();
    var devices = new IncusDeviceManager(probe);

    assertFalse(ProjectApplyCommand.sailManaged("Not_A_Project", devices));
    assertTrue(probe.invocations().isEmpty(), "an invalid name must never reach incus");
  }

  @Test
  void aDryRunResolvesTheCanonicalPathWithoutMaterializing() throws Exception {
    var project = "dry-run-materialize-proof";

    var path =
        ProjectApplyCommand.materializeUnlessDryRun(project, "name: " + project + "\n", true);

    assertEquals(ProjectApplyCommand.defaultDescriptorPath(project), path);
    assertFalse(Files.exists(path), "a dry run must never write the canonical descriptor");
  }

  @Test
  void jsonModeDiscardsProgressNarrationFromStdout() {
    assertEquals(System.out, ProjectApplyCommand.progressOut(false));
    var jsonOut = ProjectApplyCommand.progressOut(true);
    assertNotEquals(System.out, jsonOut);
    jsonOut.println("[apply] progress noise must never precede the JSON document");
    assertFalse(jsonOut.checkError(), "the discard stream swallows writes without erroring");
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
