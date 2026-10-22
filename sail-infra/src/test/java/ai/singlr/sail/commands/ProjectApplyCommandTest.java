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
import ai.singlr.sail.config.SailYaml;
import ai.singlr.sail.config.YamlUtil;
import ai.singlr.sail.engine.Banner;
import ai.singlr.sail.engine.ContainerSailSetup;
import ai.singlr.sail.engine.ContainerState;
import ai.singlr.sail.engine.FileMutexTest;
import ai.singlr.sail.engine.OperatorCommand;
import ai.singlr.sail.engine.PairingTrust;
import ai.singlr.sail.engine.ProvisionState;
import ai.singlr.sail.engine.SailPaths;
import ai.singlr.sail.identity.Actor;
import ai.singlr.sail.identity.Role;
import java.io.ByteArrayOutputStream;
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

  private static SailYaml claudeProject() {
    return SailYaml.fromMap(YamlUtil.parseMap("name: acme\nagent:\n  type: claude-code\n"));
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
    var descriptor = dir.resolve("acme.yaml");
    var errors = new ByteArrayOutputStream();
    var originalErr = System.err;
    System.setErr(new PrintStream(errors, true, StandardCharsets.UTF_8));
    try {
      var apply = applying(database, "-f", descriptor.toString(), "--yes");

      try (var pairing = PairingTrust.hold(database)) {
        FileMutexTest.awaitParked(Thread.ofPlatform().start(apply));
        assertFalse(apply.isDone(), "the apply waits before it reads the workstation key");
      }

      assertEquals(1, apply.get());
      assertEquals(
          Banner.errorLine(
                  "Project descriptor not found: "
                      + descriptor
                      + "\n  Run 'sail project init' to author one, sync it from main with 'sail"
                      + " sync', or specify one with --file.",
                  Ansi.AUTO)
              + System.lineSeparator(),
          errors.toString(StandardCharsets.UTF_8));
    } finally {
      System.setErr(originalErr);
    }
  }

  @Test
  @Timeout(60)
  void aDryRunWritesNoKeySoItDoesNotWaitForAPairing(@TempDir Path dir) throws Exception {
    var database = dir.resolve("sail.db");
    var originalErr = System.err;
    System.setErr(new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8));
    try (var pairing = PairingTrust.hold(database)) {
      var apply = applying(database, "-f", dir.resolve("acme.yaml").toString(), "--dry-run");
      Thread.ofPlatform().start(apply);

      assertEquals(1, apply.get());
    } finally {
      System.setErr(originalErr);
    }
  }

  @Test
  @Timeout(60)
  void aDescriptorNamingAnotherUserIsRefusedBeforeAnythingIsCreated(@TempDir Path dir)
      throws Exception {
    var project = "refuse-" + Long.toHexString(System.nanoTime());
    var descriptor = dir.resolve(project + ".yaml");
    Files.writeString(
        descriptor,
        """
        name: %s
        resources:
          cpu: 2
          memory: 4GB
          disk: 50GB
        ssh:
          user: bob
        """
            .formatted(project));
    var errors = new ByteArrayOutputStream();
    var originalErr = System.err;
    System.setErr(new PrintStream(errors, true, StandardCharsets.UTF_8));
    try {
      var apply = applying(dir.resolve("sail.db"), "-f", descriptor.toString(), "--yes");
      apply.run();

      assertEquals(1, apply.get());
      assertEquals(
          Banner.errorLine(otherUserRefusal(project, "bob", OperatorCommand.here()), Ansi.AUTO)
              + System.lineSeparator(),
          errors.toString(StandardCharsets.UTF_8));
      assertFalse(Files.exists(SailPaths.projectDir(project)), "nothing is recorded for it");
    } finally {
      System.setErr(originalErr);
    }
  }

  @Test
  void planTargetRefusesAnotherUserBeforeTheContainerIsStarted() {
    var thrown =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                ProjectApplyCommand.planTarget(
                    "alpha",
                    new ContainerState.Stopped(),
                    "name: alpha\nssh:\n  user: bob\n",
                    OperatorCommand.PLAIN));

    assertEquals(otherUserRefusal("alpha", "bob", OperatorCommand.PLAIN), thrown.getMessage());
  }

  @Test
  void planTargetAcceptsDevAndAnSshBlockThatNamesNoUser() {
    var dev =
        ProjectApplyCommand.planTarget(
            "alpha",
            new ContainerState.Stopped(),
            "name: alpha\nssh:\n  user: dev\n",
            OperatorCommand.PLAIN);
    var keysOnly =
        ProjectApplyCommand.planTarget(
            "alpha",
            new ContainerState.Stopped(),
            "name: alpha\nssh:\n  authorized_keys:\n    - ssh-ed25519 AAAA alice@laptop\n",
            OperatorCommand.PLAIN);

    assertEquals("dev", dev.config().sshUser());
    assertEquals("dev", keysOnly.config().sshUser());
  }

  @Test
  void theRecheckLineSaysWhyAnExistingContainerIsProvisionedAgain() {
    var interrupted = new ProvisionState("SSH_CONFIGURED", null, null, null, "inputs-x");
    var finished = new ProvisionState("COMPLETE", null, null, null, "inputs-x");
    var changedSinceInterrupted =
        "The descriptor or this box's workstation key changed since the interrupted run: every"
            + " step is checked again.";
    var changedSinceFinished =
        "The descriptor or this box's workstation key changed since the last provisioning run:"
            + " every step is checked again.";
    var noRecord =
        "No record of the descriptor this container was provisioned from: every step is checked"
            + " again.";

    assertEquals(changedSinceInterrupted, ProjectApplyCommand.recheckLine(interrupted, true));
    assertEquals(changedSinceInterrupted, ProjectApplyCommand.recheckLine(interrupted, false));
    assertEquals(changedSinceFinished, ProjectApplyCommand.recheckLine(null, true));
    assertEquals(changedSinceFinished, ProjectApplyCommand.recheckLine(finished, true));
    assertEquals(noRecord, ProjectApplyCommand.recheckLine(null, false));
    assertEquals(noRecord, ProjectApplyCommand.recheckLine(finished, false));
  }

  private static String otherUserRefusal(String project, String user, OperatorCommand operator) {
    return "ssh.user is '"
        + user
        + "', but sail runs every agent, shell and hook as 'dev' (uid 1000, /home/dev). Remove"
        + " 'ssh.user' from "
        + project
        + "'s descriptor (or set it to dev) with 'sail project edit "
        + project
        + "', then apply again. A container already created as '"
        + user
        + "' must be recreated: '"
        + operator.sail("project destroy " + project)
        + "', then '"
        + operator.sail("project apply " + project)
        + "'.";
  }

  private static FutureTask<Integer> applying(Path database, String... args) {
    var command = new CommandLine(new ProjectApplyCommand(() -> database));
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
  void dryRunSkipsCanonicalBundlePersistenceEntirely() {
    var missingSource = Path.of("/definitely/not/there/sail.yaml");

    assertDoesNotThrow(
        () ->
            ProjectApplyCommand.persistCanonicalBundle(
                "web", missingSource, true, OperatorCommand.PLAIN),
        "a source that cannot even be read is never touched under dry-run");
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
                    "alpha", new ContainerState.Stopped(), "name: beta\n", OperatorCommand.PLAIN));
    assertTrue(
        thrown.getMessage().contains("beta"),
        "a bad descriptor must fail before bulk apply starts the container, leaving observed"
            + " state untouched — the single-project path already behaves this way");
  }

  @Test
  void planTargetRefusesADeletedKeyNamingWhereItsTextGoes() {
    var thrown =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                ProjectApplyCommand.planTarget(
                    "alpha",
                    new ContainerState.Stopped(),
                    """
                    name: alpha
                    agent:
                      type: codex
                      review_pipeline:
                        stages:
                          - name: sign-off
                            type: human
                            skill: acme-review
                    """,
                    OperatorCommand.PLAIN));
    assertTrue(
        thrown
            .getMessage()
            .startsWith("agent.review_pipeline.stages[sign-off].skill is no longer read"),
        thrown.getMessage());
  }

  @Test
  void planTargetResolvesActionAndConfigForAValidTarget() {
    var plan =
        ProjectApplyCommand.planTarget(
            "alpha", new ContainerState.Stopped(), "name: alpha\n", OperatorCommand.PLAIN);
    assertEquals(ProjectApplyCommand.Action.STARTED, plan.action());
    assertEquals("alpha", plan.config().name());

    var machineryOnly =
        ProjectApplyCommand.planTarget(
            "legacy", new ContainerState.Running("10.0.0.4"), null, OperatorCommand.PLAIN);
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
