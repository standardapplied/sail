/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.commands;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.sail.Sail;
import ai.singlr.sail.api.ApiException;
import ai.singlr.sail.api.HomeRoom;
import ai.singlr.sail.api.SailOperations;
import ai.singlr.sail.config.ProjectDefinition;
import ai.singlr.sail.config.TestDefinitions;
import ai.singlr.sail.engine.Banner;
import ai.singlr.sail.engine.ContainerSailSetup;
import ai.singlr.sail.engine.ContainerState;
import ai.singlr.sail.engine.FileMutexTest;
import ai.singlr.sail.engine.OperatorCommand;
import ai.singlr.sail.engine.PairingTrust;
import ai.singlr.sail.engine.ProvisionState;
import ai.singlr.sail.engine.SailPaths;
import ai.singlr.sail.identity.Acting;
import ai.singlr.sail.identity.Actor;
import ai.singlr.sail.identity.Role;
import ai.singlr.sail.store.ProjectStore;
import ai.singlr.sail.store.SchemaManager;
import ai.singlr.sail.store.Sqlite;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.FutureTask;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;
import picocli.CommandLine.Help.Ansi;
import picocli.CommandLine.Model.CommandSpec;

class ProjectApplyCommandTest {

  private static ProjectApplyCommand.Outcome outcome(
      ContainerSailSetup.Result machinery, boolean hostnameRealigned) {
    return new ProjectApplyCommand.Outcome(3, 1, 4, machinery, hostnameRealigned, List.of(), null);
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
                List.of("no descriptor in the catalog — converged machinery only"),
                null));

    assertEquals("web", row.get("name"));
    assertEquals("started", row.get("action"));
    assertEquals(0, row.get("added"));
    assertEquals("updated", row.get("machinery"));
    assertEquals(true, row.get("hostname_realigned"));
    assertEquals(
        List.of("no descriptor in the catalog — converged machinery only"), row.get("warnings"));
  }

  @Test
  void jsonSummaryReportsTheHomeRoom() {
    var row =
        ProjectApplyCommand.jsonSummary(
            "acme",
            ProjectApplyCommand.Action.CONVERGED,
            new ProjectApplyCommand.Outcome(
                0,
                0,
                0,
                ContainerSailSetup.Result.ALREADY_PRESENT,
                false,
                List.of(),
                new HomeRoom.Outcome("acme--ada", false, true, null)));

    assertEquals(
        Map.of("id", "acme--ada", "created", false, "engaged", true), row.get("home_room"));
  }

  @Test
  void theApplyOutputNamesTheHomeRoomOrWhyThereIsNone() {
    assertNull(ProjectApplyCommand.homeRoomLine(null));
    assertEquals(
        "Home room acme--ada: created, engaged.",
        ProjectApplyCommand.homeRoomLine(new HomeRoom.Outcome("acme--ada", true, true, null)));
    assertEquals(
        "Home room acme--ada: present, no agent engaged.",
        ProjectApplyCommand.homeRoomLine(new HomeRoom.Outcome("acme--ada", false, false, null)));
    assertEquals(
        "No home room: this box names no FDE, so it has no home room.",
        ProjectApplyCommand.homeRoomLine(
            new HomeRoom.Outcome(
                null, false, false, "this box names no FDE, so it has no home room")));
  }

  @Test
  void aBoxThatNamesNoFdeMakesNoHomeRoomAndResolvesNoOperator() {
    var outcome =
        ProjectApplyCommand.ensureHomeRoom(
            null,
            claudeProject(),
            "",
            () -> {
              throw new AssertionError("a box with no FDE acts as no one");
            });

    assertEquals("this box names no FDE, so it has no home room", outcome.skipped());
  }

  @Test
  void theHomeRoomIsMadeAsTheBoxsOperator() {
    var resolved = new AtomicBoolean();

    var refused =
        assertThrows(
            ApiException.class,
            () ->
                ProjectApplyCommand.ensureHomeRoom(
                    new SailOperations(),
                    claudeProject(),
                    "ada",
                    () -> {
                      resolved.set(true);
                      return new Actor("ada", Role.MEMBER, Actor.Lane.CLI);
                    }));

    assertTrue(resolved.get());
    assertEquals(
        "This box keeps no room aggregate, so rooms cannot be served.", refused.getMessage());
  }

  private static ProjectDefinition claudeProject() {
    return TestDefinitions.yaml("name: acme\nagent:\n  type: claude-code\n");
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
    assertFalse(row.containsKey("home_room"));
  }

  @Test
  void anApplyOnABoxWhereOnlyHostInitRanIsRefusedBeforeItTouchesIncus(@TempDir Path dir)
      throws Exception {
    var box = hostInitOnly(dir);
    catalogue(box.database(), "name: acme\nresources:\n  cpu: 2\n  memory: 4GB\n  disk: 50GB\n");

    for (var args : List.of(new String[] {"acme", "--yes"}, new String[] {"--all", "--yes"})) {
      var ran = SailCli.run(new ProjectApplyCommand(() -> box), args);

      assertEquals(1, ran.exit());
      assertEquals("", ran.out());
      assertEquals(
          Banner.errorLine(
                  "This box has no sail-api yet, which every project container talks to. Set it"
                      + " up first: "
                      + OperatorCommand.here().sail("init --as-main --handle <you>")
                      + ", or "
                      + OperatorCommand.here().sail("init --main <main>")
                      + ".",
                  Ansi.AUTO)
              + System.lineSeparator(),
          ran.err());
    }
  }

  /** Records {@code yaml}'s definition in the catalog at {@code database}. */
  private static void catalogue(Path database, String yaml) {
    try (var db = Sqlite.open(database)) {
      new SchemaManager(db).migrate();
      Acting.system(() -> new ProjectStore(db).upsert(TestDefinitions.yaml(yaml)));
    }
  }

  private static ProjectApplyCommand.Box hostInitOnly(Path dir) throws IOException {
    var hostConfig = dir.resolve("host.yaml");
    Files.writeString(hostConfig, "pool: devpool\n");
    return new ProjectApplyCommand.Box(
        dir.resolve("sail.db"), hostConfig, SailPaths.apiSocketHostDir(dir));
  }

  @Test
  void aBoxWhoseSailApiRanIsLetProvision(@TempDir Path dataDir) throws Exception {
    Files.createDirectories(SailPaths.apiSocketHostDir(dataDir));

    assertDoesNotThrow(() -> ContainerSailSetup.requireApi(SailPaths.apiSocketHostDir(dataDir)));
  }

  @Test
  void provisioningRequiresRootOutsideDryRun() {
    var thrown =
        assertThrows(
            IllegalStateException.class,
            () -> ProjectApplyCommand.requireRootToProvision("web", false, false, applied("web")));

    assertEquals(
        "Root privileges required to provision 'web'. Run with: sudo sail project apply web",
        thrown.getMessage());
  }

  @Test
  void dryRunAndRootSkipTheRootGate() {
    ProjectApplyCommand.requireRootToProvision("web", true, false, applied("web"));
    ProjectApplyCommand.requireRootToProvision("web", false, true, applied("web"));
  }

  private static CommandSpec applied(String... args) {
    var apply = new CommandLine(new ProjectApplyCommand());
    apply.parseArgs(args);
    return apply.getCommandSpec();
  }

  @Test
  @Timeout(60)
  void anApplyReadsNothingWhileAPairingIsMadeOrRevoked(@TempDir Path dir) throws Exception {
    var database = dir.resolve("sail.db");
    var errors = new ByteArrayOutputStream();
    var originalErr = System.err;
    System.setErr(new PrintStream(errors, true, StandardCharsets.UTF_8));
    try {
      var apply = applying(database, "acme", "--yes");

      try (var pairing = PairingTrust.hold(database)) {
        FileMutexTest.awaitParked(Thread.ofPlatform().start(apply));
        assertFalse(apply.isDone(), "the apply waits before it reads the catalog");
      }

      assertEquals(1, apply.get());
      assertEquals(
          Banner.errorLine(
                  ProjectMutations.notFound("acme", OperatorCommand.here()).getMessage(), Ansi.AUTO)
              + System.lineSeparator(),
          errors.toString(StandardCharsets.UTF_8));
    } finally {
      System.setErr(originalErr);
    }
  }

  @Test
  void anApplyNamingNoProjectIsRefusedNamingBothWays(@TempDir Path dir) throws Exception {
    var errors = new ByteArrayOutputStream();
    var originalErr = System.err;
    System.setErr(new PrintStream(errors, true, StandardCharsets.UTF_8));
    try {
      var apply = applying(dir.resolve("sail.db"), "--yes");
      apply.run();

      assertEquals(1, apply.get());
    } finally {
      System.setErr(originalErr);
    }

    assertEquals(
        Banner.errorLine("Name the project to apply, or pass --all.", Ansi.AUTO)
            + System.lineSeparator(),
        errors.toString(StandardCharsets.UTF_8));
  }

  @Test
  @Timeout(60)
  void aDryRunWritesNoKeySoItDoesNotWaitForAPairing(@TempDir Path dir) throws Exception {
    var database = dir.resolve("sail.db");
    var originalErr = System.err;
    System.setErr(new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8));
    try (var pairing = PairingTrust.hold(database)) {
      var apply = applying(database, "acme", "--dry-run");
      Thread.ofPlatform().start(apply);

      assertEquals(1, apply.get());
    } finally {
      System.setErr(originalErr);
    }
  }

  @Test
  void theRecheckLineSaysWhyAnExistingContainerIsProvisionedAgain() {
    var interrupted = new ProvisionState("SSH_CONFIGURED", null, null, null, "inputs-x");
    var finished = new ProvisionState("COMPLETE", null, null, null, "inputs-x");
    var changedSinceInterrupted =
        "The definition or this box's workstation key changed since the interrupted run: every"
            + " step is checked again.";
    var changedSinceFinished =
        "The definition or this box's workstation key changed since the last provisioning run:"
            + " every step is checked again.";
    var noRecord =
        "No record of the definition this container was provisioned from: every step is checked"
            + " again.";

    assertEquals(changedSinceInterrupted, ProjectApplyCommand.recheckLine(interrupted, true));
    assertEquals(changedSinceInterrupted, ProjectApplyCommand.recheckLine(interrupted, false));
    assertEquals(changedSinceFinished, ProjectApplyCommand.recheckLine(null, true));
    assertEquals(changedSinceFinished, ProjectApplyCommand.recheckLine(finished, true));
    assertEquals(noRecord, ProjectApplyCommand.recheckLine(null, false));
    assertEquals(noRecord, ProjectApplyCommand.recheckLine(finished, false));
  }

  private static FutureTask<Integer> applying(Path database, String... args) {
    var command =
        new CommandLine(
            new ProjectApplyCommand(
                () ->
                    new ProjectApplyCommand.Box(
                        database, database.resolveSibling("host.yaml"), database.getParent())));
    command.setExecutionExceptionHandler((failure, line, parsed) -> 1);
    return new FutureTask<>(() -> command.execute(args));
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
    assertFalse(help.contains("--file"), "a definition is read from the catalog alone");
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
  void planTargetResolvesActionAndConfigForAValidTarget() {
    var alpha = TestDefinitions.named("alpha");
    var plan = ProjectApplyCommand.planTarget(new ContainerState.Stopped(), alpha);
    assertEquals(ProjectApplyCommand.Action.STARTED, plan.action());
    assertEquals(alpha, plan.config());

    var machineryOnly =
        ProjectApplyCommand.planTarget(new ContainerState.Running("10.0.0.4"), null);
    assertEquals(ProjectApplyCommand.Action.CONVERGED, machineryOnly.action());
    assertEquals(null, machineryOnly.config(), "no definition means machinery-only convergence");
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
  void aCatalogRowWithoutTheDeviceGetsAVisibleWarningNotASilentSkip() {
    var warning = ProjectApplyCommand.unmanagedSkipWarning("web", true, OperatorCommand.PLAIN);
    assertTrue(warning.contains("web"));
    assertTrue(
        warning.contains("sail project apply web"),
        "the operator is told the explicit single-project escape hatch");
    assertEquals(
        null,
        ProjectApplyCommand.unmanagedSkipWarning("stranger", false, OperatorCommand.PLAIN),
        "a foreign container with no catalog row is not Sail's to narrate");
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
    for (var deleted : List.of("init", "reconfigure", "sync")) {
      var cmd = new CommandLine(new Sail());
      cmd.setErr(new PrintWriter(new StringWriter()));

      var exit = cmd.execute("project", deleted, "web");

      assertNotEquals(0, exit, "'project " + deleted + "' must not parse anymore");
    }
  }
}
