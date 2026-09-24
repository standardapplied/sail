/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.commands;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.sail.engine.IncusDeviceManager;
import ai.singlr.sail.engine.ScriptedShellExecutor;
import ai.singlr.sail.engine.ShellExec;
import ai.singlr.sail.engine.SshdKeepalive;
import ai.singlr.sail.engine.SystemdServiceInstaller;
import ai.singlr.sail.store.DataMigration;
import ai.singlr.sail.store.LegacyDataMigration;
import ai.singlr.sail.store.SchemaManager;
import ai.singlr.sail.store.SpecStore;
import ai.singlr.sail.store.Sqlite;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MigrateCommandTest {

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
    var base = ai.singlr.sail.config.HostYaml.fromMap(java.util.Map.of("storage_backend", "dir"));
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
  void applyMigrationsBringsTheSchemaCurrent() {
    MigrateCommand.applyMigrations(
        db, "test.db", DataMigration.Prompter.NON_INTERACTIVE, false, true);

    assertTrue(
        new SchemaManager(db).currentVersion() > 0, "schema should have migrated from empty");
  }

  @Test
  void migratedSchemaExposesTheCurrentSpecColumns() {
    MigrateCommand.applyMigrations(
        db, "test.db", DataMigration.Prompter.NON_INTERACTIVE, false, true);

    assertDoesNotThrow(() -> new SpecStore(db).findById("none"));
  }

  @Test
  void migrateRegistersTheV1DataFloorOnce() {
    MigrateCommand.applyMigrations(
        db, "test.db", DataMigration.Prompter.NON_INTERACTIVE, false, true);

    assertEquals(
        1L,
        db.queryOne(
                "SELECT COUNT(*) FROM data_migrations WHERE name = ?",
                row -> row.integer(0),
                LegacyDataMigration.NAME)
            .orElseThrow());
  }

  private static final Path INSTALLED = Path.of("/usr/local/bin/sail");

  @Test
  void aRehearsalConvergesTheCopyAndWithholdsHostStateWithoutAskingWhereSailIs() {
    var copy = Path.of("/root/rehearsal/sail.db");

    var line =
        MigrateCommand.hostStateWithheld(
                true,
                copy,
                INSTALLED,
                () -> {
                  throw new AssertionError("a rehearsal never names a binary");
                })
            .orElseThrow();

    assertTrue(line.contains("Rehearsal: migrated " + copy + " only"), line);
    assertTrue(line.contains("not the provisioned /var/lib/sail"), line);
    assertTrue(line.contains("no keys, units, services or workspace files"), line);
  }

  @Test
  void onlyTheInstalledBinaryConvergesHostState() {
    var db = Path.of("/var/lib/sail/sail.db");
    var staged = Path.of("/tmp/sail-new");

    assertTrue(MigrateCommand.hostStateWithheld(false, db, INSTALLED, () -> INSTALLED).isEmpty());
    var line = MigrateCommand.hostStateWithheld(false, db, staged, () -> INSTALLED).orElseThrow();

    assertTrue(line.contains("this is " + staged + ", not the installed " + INSTALLED), line);
    assertTrue(line.contains("'sudo " + INSTALLED + " migrate'"), line);
  }

  @Test
  void aBoxWithoutAnInstalledBinaryMigratesItsDatabaseAndSaysWhyNothingElse() {
    var line =
        MigrateCommand.hostStateWithheld(
                false,
                Path.of("/root/.sail/sail.db"),
                Path.of("/opt/sail"),
                () -> {
                  throw new IllegalStateException(
                      "No sail binary is installed at " + INSTALLED + ". Install it there.");
                })
            .orElseThrow();

    assertTrue(line.contains("host state left as is"), line);
    assertTrue(line.contains("No sail binary is installed at " + INSTALLED), line);
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
    var out = new java.io.ByteArrayOutputStream();
    var original = System.out;
    System.setOut(new java.io.PrintStream(out, true, java.nio.charset.StandardCharsets.UTF_8));
    try {
      action.run();
    } finally {
      System.setOut(original);
    }
    return out.toString(java.nio.charset.StandardCharsets.UTF_8);
  }

  private static void writeOldUnit(Path home) throws Exception {
    var unit = home.resolve(".sail/services/sail-pty-host.service");
    Files.createDirectories(unit.getParent());
    Files.writeString(unit, "[Service]\nExecStart=/usr/local/bin/sail _pty-host\n");
  }

  private static ai.singlr.sail.pty.PtySessionHost liveHost(Path socket, Path sessions)
      throws Exception {
    return PtyHostCommand.startHost(
        socket,
        sessions,
        token -> new ai.singlr.sail.pty.PtyIdentity("uday", true),
        ai.singlr.sail.pty.PtyRooms.NONE,
        ai.singlr.sail.pty.PtyEvents.NONE);
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
  void ensureSshdKeepaliveWritesTheDropInAsRootAndLeavesACurrentOneAlone(@TempDir Path etc)
      throws Exception {
    var dropIn = etc.resolve("sshd_config.d/10-sail.conf");
    var shell = new ScriptedShellExecutor(new ShellExec.Result(0, "", ""));

    MigrateCommand.ensureSshdKeepalive(false, shell, dropIn, true);
    assertTrue(shell.invocations().isEmpty(), "a non-root migrate never touches sshd");

    MigrateCommand.ensureSshdKeepalive(true, shell, dropIn, true);
    assertEquals(1, shell.invocations().size(), "root writes the drop-in and reloads sshd");
    var install = shell.invocations().getFirst();
    assertTrue(install.contains("ClientAliveInterval 15"), install);
    assertTrue(install.contains("ClientAliveCountMax 3"), install);
    assertTrue(install.contains(dropIn.toString()), install);
    assertTrue(install.contains("systemctl reload ssh"), install);

    Files.createDirectories(dropIn.getParent());
    Files.writeString(dropIn, SshdKeepalive.content());
    MigrateCommand.ensureSshdKeepalive(true, shell, dropIn, true);
    assertEquals(1, shell.invocations().size(), "a drop-in already current is left alone");
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
