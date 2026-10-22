/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.commands;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.sail.common.Remedy;
import ai.singlr.sail.config.HostYaml;
import ai.singlr.sail.engine.HostProvisioner;
import ai.singlr.sail.engine.IncusDeviceManager;
import ai.singlr.sail.engine.NeedrestartPolicy;
import ai.singlr.sail.engine.OperatorCommand;
import ai.singlr.sail.engine.ScriptedShellExecutor;
import ai.singlr.sail.engine.ShellExec;
import ai.singlr.sail.engine.SshdKeepalive;
import ai.singlr.sail.engine.SystemdServiceInstaller;
import ai.singlr.sail.identity.Actor;
import ai.singlr.sail.pty.PtyEvents;
import ai.singlr.sail.pty.PtyIdentity;
import ai.singlr.sail.pty.PtyRooms;
import ai.singlr.sail.pty.PtySessionHost;
import ai.singlr.sail.store.DataMigration;
import ai.singlr.sail.store.DataMigrator;
import ai.singlr.sail.store.LegacyDataMigration;
import ai.singlr.sail.store.SchemaManager;
import ai.singlr.sail.store.SpecStore;
import ai.singlr.sail.store.Sqlite;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MigrateCommandTest {

  private static final Path INSTALLED = Path.of("/usr/local/bin/sail");
  private static final Path DB = Path.of("/var/lib/sail/sail.db");

  @TempDir Path tempDir;
  private Sqlite db;

  @BeforeEach
  void setUp() {
    db = Sqlite.open(tempDir.resolve("test.db"));
  }

  @AfterEach
  void tearDown() {
    if (db != null) {
      db.close();
    }
  }

  @Test
  void aBoxIdIsAssignedFromTheHostnameOnlyToARoledBoxWithoutOne() {
    var base = HostYaml.fromMap(Map.of("storage_backend", "dir"));
    assertTrue(MigrateCommand.assignBoxId(base, "devbox").isEmpty(), "no role, no identity");
    var node =
        HostConfigSetCommand.applyChange(
            HostConfigSetCommand.applyChange(base, "sync-role", "node"),
            "sync-main",
            "sail@maindevbox");
    var assigned = MigrateCommand.assignBoxId(node, "devbox").orElseThrow();
    assertEquals("devbox", assigned.sync().boxId());
    assertEquals("sail@maindevbox", assigned.sync().main());
    assertTrue(MigrateCommand.assignBoxId(assigned, "renamed").isEmpty(), "an id is kept");
  }

  @Test
  void aMigrationNoteNamesItsCommandAsItsReaderTypesIt() {
    var runs =
        List.of(
            new DataMigrator.Run(
                "materialized-files",
                false,
                new DataMigration.Report(
                    1,
                    0,
                    1,
                    List.of(Remedy.of("restore access and rerun ", Remedy.sail("migrate"))))));

    assertTrue(
        summaryLines(runs, OperatorCommand.SUDO)
            .contains("restore access and rerun sudo sail migrate"));
    assertTrue(
        summaryLines(runs, OperatorCommand.PLAIN)
            .contains("restore access and rerun sail migrate"));
  }

  private static List<String> summaryLines(List<DataMigrator.Run> runs, OperatorCommand reader) {
    return captureStdout(() -> MigrateCommand.printSummary("test.db", 1, 1, runs, reader))
        .lines()
        .toList();
  }

  @Test
  void applyMigrationsBringsTheSchemaCurrent() {
    MigrateCommand.applyMigrations(
        db, "test.db", DataMigration.Prompter.NON_INTERACTIVE, false, true, OperatorCommand.PLAIN);

    assertTrue(
        new SchemaManager(db).currentVersion() > 0, "schema should have migrated from empty");
  }

  @Test
  void migratedSchemaExposesTheCurrentSpecColumns() {
    MigrateCommand.applyMigrations(
        db, "test.db", DataMigration.Prompter.NON_INTERACTIVE, false, true, OperatorCommand.PLAIN);

    assertDoesNotThrow(() -> new SpecStore(db).findById("none"));
  }

  @Test
  void migrateRegistersTheV1DataFloorOnce() {
    MigrateCommand.applyMigrations(
        db, "test.db", DataMigration.Prompter.NON_INTERACTIVE, false, true, OperatorCommand.PLAIN);

    assertEquals(
        1L,
        db.queryOne(
                "SELECT COUNT(*) FROM data_migrations WHERE name = ?",
                row -> row.integer(0),
                LegacyDataMigration.NAME)
            .orElseThrow());
  }

  @Test
  void aRehearsalMigratesOnlyTheCopyWhicheverBinaryRunsIt() {
    var copy = Path.of("/root/rehearsal/sail.db");
    var staged = Path.of("/tmp/sail-new");

    var scope =
        assertInstanceOf(
            MigrateCommand.Scope.DatabaseOnly.class,
            MigrateCommand.scope(true, copy, staged, Optional.of(INSTALLED)));

    assertTrue(scope.why().contains("Rehearsal: migrated " + copy + " only"), scope.why());
    assertTrue(scope.why().contains("not the provisioned /var/lib/sail"), scope.why());
  }

  @Test
  void theInstalledBinaryConvergesTheBox() {
    assertEquals(
        new MigrateCommand.Scope.Box(),
        MigrateCommand.scope(false, DB, INSTALLED, Optional.of(INSTALLED)));
  }

  @Test
  void aStagedBinaryIsRefusedAndToldHowToInstallOrRehearse() {
    var staged = Path.of("/tmp/sail-new");

    var scope =
        assertInstanceOf(
            MigrateCommand.Scope.Refused.class,
            MigrateCommand.scope(false, DB, staged, Optional.of(INSTALLED)));

    assertTrue(scope.why().contains("This is " + staged + ", not the installed " + INSTALLED));
    assertTrue(scope.why().contains("sudo " + INSTALLED + " upgrade --binary " + staged));
    assertTrue(scope.why().contains("SAIL_DATA_DIR=<copy> " + staged + " migrate"));
  }

  @Test
  void aBoxWithNothingInstalledMigratesItsDatabaseAndSaysHowToConvergeTheRest() {
    var scope =
        assertInstanceOf(
            MigrateCommand.Scope.DatabaseOnly.class,
            MigrateCommand.scope(false, DB, Path.of("/opt/sail"), Optional.empty()));

    assertTrue(scope.why().contains("host state left as is"), scope.why());
    assertTrue(scope.why().contains("No sail is installed at " + INSTALLED), scope.why());
  }

  @Test
  void aRefusedRunNeverOpensTheDatabase() {
    var fresh = tempDir.resolve("fresh/sail.db");
    var steps = new ArrayList<String>();

    var refused =
        assertThrows(
            IllegalStateException.class,
            () ->
                MigrateCommand.runMigrations(
                    fresh,
                    true,
                    true,
                    new MigrateCommand.Scope.Refused("staged"),
                    record(steps),
                    OperatorCommand.PLAIN));

    assertEquals("staged", refused.getMessage());
    assertFalse(Files.exists(fresh.getParent()));
    assertTrue(steps.isEmpty());
  }

  @Test
  void aDatabaseOnlyRunImportsIntoTheDatabaseAndLeavesTheHostAlone() {
    var copy = tempDir.resolve("copy/sail.db");
    var steps = new ArrayList<String>();

    var err =
        captureStderr(
            () ->
                MigrateCommand.runMigrations(
                    copy,
                    true,
                    true,
                    new MigrateCommand.Scope.DatabaseOnly("only the copy"),
                    record(steps),
                    OperatorCommand.PLAIN));

    assertEquals(List.of("imports"), steps);
    assertTrue(err.contains("only the copy"), err);
    try (var migrated = Sqlite.open(copy)) {
      assertTrue(new SchemaManager(migrated).currentVersion() > 0);
    }
  }

  @Test
  void aBoxRunImportsThenConvergesTheHost() {
    var steps = new ArrayList<String>();

    MigrateCommand.runMigrations(
        tempDir.resolve("box/sail.db"),
        true,
        true,
        new MigrateCommand.Scope.Box(),
        record(steps),
        OperatorCommand.PLAIN);

    assertEquals(List.of("imports", "host"), steps);
  }

  @Test
  void theImportsWriteAsThisBoxsMachinery() {
    var importedAs = new ArrayList<Actor>();

    MigrateCommand.runMigrations(
        tempDir.resolve("box/sail.db"),
        true,
        true,
        new MigrateCommand.Scope.DatabaseOnly("only the copy"),
        new MigrateCommand.Convergence(
            (db, json) -> importedAs.add(Actor.current()), (db, json) -> {}),
        OperatorCommand.PLAIN);

    assertEquals(List.of(Actor.system()), importedAs);
  }

  private static MigrateCommand.Convergence record(List<String> steps) {
    return new MigrateCommand.Convergence(
        (db, json) -> steps.add("imports"), (db, json) -> steps.add("host"));
  }

  private static String captureStderr(Runnable action) {
    var err = new ByteArrayOutputStream();
    var original = System.err;
    System.setErr(new PrintStream(err, true, StandardCharsets.UTF_8));
    try {
      action.run();
    } finally {
      System.setErr(original);
    }
    return err.toString(StandardCharsets.UTF_8);
  }

  @Test
  void ensurePtyHostServiceInstallsOnlyOnAProvisionedHost(@TempDir Path home) {
    var shell = new ScriptedShellExecutor(new ShellExec.Result(0, "", ""));
    var binary = Path.of("/usr/local/bin/sail");

    MigrateCommand.ensurePtyHostService(
        false,
        shell,
        SystemdServiceInstaller.Mode.USER,
        home,
        binary,
        home.resolve("pty.sock"),
        true);
    assertTrue(shell.invocations().isEmpty(), "no systemctl where the box has no api service");
    assertFalse(Files.exists(home.resolve(".sail/services/sail-pty-host.service")));

    MigrateCommand.ensurePtyHostService(
        true,
        shell,
        SystemdServiceInstaller.Mode.USER,
        home,
        binary,
        home.resolve("pty.sock"),
        true);
    assertTrue(
        Files.exists(home.resolve(".sail/services/sail-pty-host.service")),
        "a provisioned host gets the pty-host unit");
    assertTrue(
        shell.invocations().stream()
            .anyMatch(c -> c.contains("systemctl --user restart sail-pty-host.service")),
        "and it is enabled and (re)started so the upgraded binary takes effect");
  }

  private static String captureStdout(Runnable action) {
    var out = new ByteArrayOutputStream();
    var original = System.out;
    System.setOut(new PrintStream(out, true, StandardCharsets.UTF_8));
    try {
      action.run();
    } finally {
      System.setOut(original);
    }
    return out.toString(StandardCharsets.UTF_8);
  }

  private static void writeOldUnit(Path home) throws Exception {
    var unit = home.resolve(".sail/services/sail-pty-host.service");
    Files.createDirectories(unit.getParent());
    Files.writeString(unit, "[Service]\nExecStart=/usr/local/bin/sail _pty-host\n");
  }

  private static PtySessionHost liveHost(Path socket, Path sessions) throws Exception {
    return PtyHostCommand.startHost(
        socket, sessions, token -> new PtyIdentity("uday", true), PtyRooms.NONE, PtyEvents.NONE);
  }

  @Test
  @org.junit.jupiter.api.condition.EnabledOnOs(org.junit.jupiter.api.condition.OS.LINUX)
  void theFirstUpgradePastAnOldUnitNamesTheLiveSessionsItEnds(@TempDir Path home) throws Exception {
    var socket = home.resolve("pty.sock");
    writeOldUnit(home);
    try (var host = liveHost(socket, home.resolve("sessions"));
        var client = SessionClient.connect(socket, "")) {
      client.create("s1", List.of("sh", "-c", "read a"), "/tmp", "", "", 80, 24);
      client.create("s2", List.of("sh", "-c", "read a"), "/tmp", "", "", 80, 24);
      var shell = new ScriptedShellExecutor(new ShellExec.Result(0, "", ""));

      var out =
          captureStdout(
              () ->
                  MigrateCommand.ensurePtyHostService(
                      true,
                      shell,
                      SystemdServiceInstaller.Mode.USER,
                      home,
                      Path.of("/usr/local/bin/sail"),
                      socket,
                      false));

      assertTrue(out.contains("2 live sessions ended"), out);
      assertTrue(out.contains("predates live handoff"), out);
      assertTrue(
          shell.invocations().stream()
              .anyMatch(c -> c.contains("systemctl --user restart sail-pty-host.service")),
          "the restart still proceeds");
      assertTrue(
          Files.readString(home.resolve(".sail/services/sail-pty-host.service"))
              .contains("FileDescriptorStoreMax="),
          "the unit now carries the store, so the next upgrade is live");
    }
  }

  @Test
  @org.junit.jupiter.api.condition.EnabledOnOs(org.junit.jupiter.api.condition.OS.LINUX)
  void noLiveSessionsMeansNoNoticeAndANewUnitMeansNoNoticeEither(@TempDir Path home)
      throws Exception {
    var socket = home.resolve("pty.sock");
    writeOldUnit(home);
    try (var host = liveHost(socket, home.resolve("sessions"))) {
      var shell = new ScriptedShellExecutor(new ShellExec.Result(0, "", ""));
      var out =
          captureStdout(
              () ->
                  MigrateCommand.ensurePtyHostService(
                      true,
                      shell,
                      SystemdServiceInstaller.Mode.USER,
                      home,
                      Path.of("/usr/local/bin/sail"),
                      socket,
                      false));
      assertFalse(out.contains("pty host restarted"), out);

      try (var client = SessionClient.connect(socket, "")) {
        client.create("s1", List.of("sh", "-c", "read a"), "/tmp", "", "", 80, 24);
      }
      var again =
          captureStdout(
              () ->
                  MigrateCommand.ensurePtyHostService(
                      true,
                      shell,
                      SystemdServiceInstaller.Mode.USER,
                      home,
                      Path.of("/usr/local/bin/sail"),
                      socket,
                      false));
      assertFalse(again.contains("pty host restarted"), "the unit now has the store: " + again);
    }
  }

  @Test
  void anUnreachableHostIsSaidToBeUnaskableAndTheRestartProceeds(@TempDir Path home)
      throws Exception {
    writeOldUnit(home);
    var shell = new ScriptedShellExecutor(new ShellExec.Result(0, "", ""));

    var out =
        captureStdout(
            () ->
                MigrateCommand.ensurePtyHostService(
                    true,
                    shell,
                    SystemdServiceInstaller.Mode.USER,
                    home,
                    Path.of("/usr/local/bin/sail"),
                    home.resolve("nobody.sock"),
                    false));

    assertTrue(out.contains("could not be asked"), out);
    assertTrue(out.contains("predates live handoff"), out);
    assertTrue(
        shell.invocations().stream()
            .anyMatch(c -> c.contains("systemctl --user restart sail-pty-host.service")));
  }

  @Test
  void migrateWritesEveryHostDropInAsRootAndLeavesACurrentOneAlone(@TempDir Path fsRoot)
      throws Exception {
    var sshd = fsRoot.resolve("etc/ssh/sshd_config.d/10-sail.conf");
    var needrestart = fsRoot.resolve("etc/needrestart/conf.d/50-sail.conf");
    var shell = new ScriptedShellExecutor(new ShellExec.Result(0, "", ""));

    MigrateCommand.ensureHostDropIns(false, shell, fsRoot, false);
    assertEquals(List.of(), shell.arguments(), "a non-root migrate never touches /etc");

    var out = captureStdout(() -> MigrateCommand.ensureHostDropIns(true, shell, fsRoot, false));
    assertEquals(
        List.of(
            SshdKeepalive.DROP_IN.installCommand(sshd.toString()),
            NeedrestartPolicy.DROP_IN.installCommand(needrestart.toString())),
        shell.arguments());
    assertEquals("  ✓ sshd keepalive drop-in written\n  ✓ needrestart policy written\n", out);

    Files.createDirectories(sshd.getParent());
    Files.writeString(sshd, SshdKeepalive.DROP_IN.content());
    Files.createDirectories(needrestart.getParent());
    Files.writeString(needrestart, NeedrestartPolicy.DROP_IN.content());
    MigrateCommand.ensureHostDropIns(true, shell, fsRoot, true);
    assertEquals(2, shell.arguments().size(), "drop-ins already current are left alone");
  }

  @Test
  void aDropInThatCannotBeWrittenWarnsAndTheNextIsStillWritten(@TempDir Path fsRoot) {
    var shell =
        new ScriptedShellExecutor(new ShellExec.Result(0, "", ""))
            .onFail("sshd_config.d", "read-only file system");

    var out = captureStdout(() -> MigrateCommand.ensureHostDropIns(true, shell, fsRoot, false));

    assertEquals(
        "  ⚠ Failed to install "
            + fsRoot.resolve("etc/ssh/sshd_config.d/10-sail.conf")
            + ": read-only file system\n  ✓ needrestart policy written\n",
        out);
    assertEquals(2, shell.arguments().size());
  }

  @Test
  void initAndMigrateWriteTheSameNeedrestartPolicy(@TempDir Path dir) throws Exception {
    var provisioning = new ScriptedShellExecutor(new ShellExec.Result(0, "", ""));
    new HostProvisioner(provisioning, null)
        .provision(
            "dir", null, "devpool", "incusbr0", null, dir.resolve("host.yaml"), dir.resolve("db"));
    var migrating = new ScriptedShellExecutor(new ShellExec.Result(0, "", ""));
    MigrateCommand.ensureHostDropIns(true, migrating, dir, true);

    var byInit = NeedrestartPolicy.DROP_IN.installCommand(NeedrestartPolicy.DROP_IN.path());
    assertTrue(provisioning.arguments().contains(byInit), provisioning.invocations().toString());
    var byMigrate = migrating.arguments().getLast();
    assertEquals(byInit.subList(0, 5), byMigrate.subList(0, 5), "the same script and bytes");
    assertEquals(
        dir.resolve(NeedrestartPolicy.DROP_IN.path().substring(1)).toString(), byMigrate.get(5));
  }

  @Test
  void migrationConvergesOnlyTheContainersSailProvisioned() {
    var probe =
        new ScriptedShellExecutor()
            .onOk("incus config device get app sail-api-sock source", "/var/lib/sail/api");
    var devices = new IncusDeviceManager(probe);

    assertEquals(
        List.of("app"),
        MigrateCommand.sailManagedContainers(List.of("app", "stranger", "Not_A_Project"), devices),
        "an upgrade converges Sail's containers; a foreign instance on the same host must never"
            + " be handed the API socket, the box credential, or the provenance marker apply --all"
            + " trusts");
    assertTrue(
        probe.invocations().stream().noneMatch(cmd -> cmd.contains("Not_A_Project")),
        "an invalid name never reaches incus");
  }
}
