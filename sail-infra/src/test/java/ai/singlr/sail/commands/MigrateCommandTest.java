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

import ai.singlr.sail.common.Remedied;
import ai.singlr.sail.common.Remedy;
import ai.singlr.sail.engine.Apt;
import ai.singlr.sail.engine.HostProvisioner;
import ai.singlr.sail.engine.NeedrestartPolicy;
import ai.singlr.sail.engine.OperatorCommand;
import ai.singlr.sail.engine.ScriptedShellExecutor;
import ai.singlr.sail.engine.ShellExec;
import ai.singlr.sail.engine.SshIdentityProvisioner;
import ai.singlr.sail.engine.SshdKeepalive;
import ai.singlr.sail.engine.SystemdServiceInstaller;
import ai.singlr.sail.identity.Actor;
import ai.singlr.sail.store.ChangeLog;
import ai.singlr.sail.store.ProjectStore;
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
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine.Help.Ansi;

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
                MigrateCommand.converge(
                    fresh,
                    true,
                    new MigrateCommand.Scope.Refused("staged"),
                    (db, json) -> steps.add("host")));

    assertEquals("staged", refused.getMessage());
    assertFalse(Files.exists(fresh.getParent()));
    assertTrue(steps.isEmpty());
  }

  @Test
  void aDatabaseOnlyRunCreatesTheSchemaAndLeavesTheHostAlone() {
    var copy = tempDir.resolve("copy/sail.db");
    var steps = new ArrayList<String>();

    var err =
        captureStderr(
            () ->
                MigrateCommand.converge(
                    copy,
                    true,
                    new MigrateCommand.Scope.DatabaseOnly("only the copy"),
                    (db, json) -> steps.add("host")));

    assertEquals(List.of(), steps);
    assertEquals("only the copy\n", err);
    try (var migrated = Sqlite.open(copy)) {
      assertEquals(SchemaManager.CURRENT_VERSION, new SchemaManager(migrated).currentVersion());
      assertDoesNotThrow(() -> new SpecStore(migrated).findById("none"));
    }
  }

  @Test
  void aMigratedDatabaseHoldsTheDemoOnceSeededByThisBoxsMachinery() {
    var path = tempDir.resolve("demo/sail.db");

    MigrateCommand.converge(
        path, true, new MigrateCommand.Scope.DatabaseOnly(""), (db, json) -> {});
    MigrateCommand.converge(
        path, true, new MigrateCommand.Scope.DatabaseOnly(""), (db, json) -> {});

    try (var migrated = Sqlite.open(path)) {
      assertEquals(
          List.of("demo"),
          new ProjectStore(migrated).list().stream().map(ProjectStore.ProjectRow::name).toList());
      assertEquals(
          List.of(Actor.system().handle()),
          new ChangeLog(migrated)
              .history("project", "demo").stream().map(ChangeLog.Entry::actor).toList());
    }
  }

  @Test
  void aBoxRunPreparesTheDatabaseThenConvergesTheHost() {
    var steps = new ArrayList<String>();
    var path = tempDir.resolve("box/sail.db");

    MigrateCommand.converge(
        path,
        true,
        new MigrateCommand.Scope.Box(),
        (db, json) -> steps.add("host at v" + new SchemaManager(db).currentVersion()));

    assertEquals(List.of("host at v" + SchemaManager.CURRENT_VERSION), steps);
  }

  @Test
  void aReaderIsToldWhichDatabaseWasPreparedAtWhichSchemaAndThatTheDemoWasSeeded() {
    var path = tempDir.resolve("told/sail.db");

    var out =
        captureStdout(
            () ->
                MigrateCommand.converge(
                    path, false, new MigrateCommand.Scope.Box(), (db, json) -> {}));

    assertEquals(
        Ansi.AUTO.string(
                "  @|green ✓|@ Database: "
                    + path
                    + " @|faint (schema v"
                    + SchemaManager.CURRENT_VERSION
                    + ")|@")
            + "\n"
            + Ansi.AUTO.string("  @|green ✓|@ demo project seeded")
            + "\n",
        out);
  }

  @Test
  void aDatabaseOfAnotherReleaseIsRefusedAndTheHostIsLeftAlone() {
    var path = tempDir.resolve("old/sail.db");
    try (var old = Sqlite.open(Files.createDirectories(path.getParent()).resolve("sail.db"))) {
      old.execute(
          "CREATE TABLE schema_version (version INTEGER PRIMARY KEY, applied_at TEXT NOT NULL)");
      old.execute("INSERT INTO schema_version (version, applied_at) VALUES (244, 'staged')");
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
    var steps = new ArrayList<String>();

    var refused =
        assertThrows(
            Remedied.State.class,
            () ->
                MigrateCommand.converge(
                    path, true, new MigrateCommand.Scope.Box(), (db, json) -> steps.add("host")));

    assertEquals(List.of(Remedy.sail("init")), refused.remedy().commands());
    assertEquals(List.of(), steps);
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
        false, shell, SystemdServiceInstaller.Mode.USER, home, binary, true);
    assertTrue(shell.invocations().isEmpty(), "no systemctl where the box has no api service");
    assertFalse(Files.exists(home.resolve(".sail/services/sail-pty-host.service")));

    MigrateCommand.ensurePtyHostService(
        true, shell, SystemdServiceInstaller.Mode.USER, home, binary, true);
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
  void aMainWhoseSailApiReadsASharedDataDirPointsThePtyHostThereTooOnce(@TempDir Path fsRoot)
      throws Exception {
    var content = "[Service]\nEnvironment=SAIL_DATA_DIR=/var/lib/sail\nUMask=0007\n";
    var apis = fsRoot.resolve("etc/systemd/system/sail-api.service.d/10-sail-data-dir.conf");
    var ptys = fsRoot.resolve("etc/systemd/system/sail-pty-host.service.d/10-sail-data-dir.conf");
    Files.createDirectories(apis.getParent());
    Files.writeString(apis, content);
    var shell = new ScriptedShellExecutor(new ShellExec.Result(0, "", ""));

    var out = captureStdout(() -> MigrateCommand.ensureHostDropIns(true, shell, fsRoot, false));

    assertEquals(
        SshIdentityProvisioner.ptyHostDataDir(content).installCommand(ptys.toString()),
        shell.arguments().getLast(),
        "the pty host gets sail-api's bytes");
    assertTrue(
        out.endsWith("  ✓ Point the pty session host at the shared data directory written\n"), out);

    Files.createDirectories(ptys.getParent());
    Files.writeString(ptys, content);
    MigrateCommand.ensureHostDropIns(true, shell, fsRoot, true);
    assertEquals(
        1,
        shell.invocations().stream().filter(command -> command.contains("sail-pty-host")).count(),
        "a pty host already pointed there is left alone");
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
  void migrateHoldsAnUnheldIncusOnceAndSaysSoOnlyThatTime() {
    var shell =
        new ScriptedShellExecutor()
            .onceOnOk("dpkg-query -W", "incus install installed\nincus-client install installed\n")
            .onOk("dpkg-query", "incus hold installed\nincus-client hold installed\n")
            .onOk("apt-mark");

    MigrateCommand.ensureIncusHold(false, shell, false, OperatorCommand.SUDO);
    assertEquals(List.of(), shell.invocations(), "a non-root migrate holds nothing");

    var first =
        captureStdout(
            () -> MigrateCommand.ensureIncusHold(true, shell, false, OperatorCommand.SUDO));
    var second =
        captureStdout(
            () -> MigrateCommand.ensureIncusHold(true, shell, false, OperatorCommand.SUDO));

    assertEquals("  ✓ incus held (sudo sail host incus upgrade moves it)\n", first);
    assertEquals("", second);
    assertEquals(
        List.of(String.join(" ", Apt.HOLD) + " incus incus-client"),
        shell.invocations().stream().filter(command -> command.contains("apt-mark")).toList());
  }

  @Test
  void aHoldThatFailsWarnsWithTheRerun() {
    var shell =
        new ScriptedShellExecutor()
            .onOk("dpkg-query", "incus install installed\n")
            .onFail("apt-mark", "E: Could not get lock /var/lib/dpkg/lock");

    var out =
        captureStdout(
            () -> MigrateCommand.ensureIncusHold(true, shell, false, OperatorCommand.PLAIN));
    var quiet =
        captureStdout(
            () -> MigrateCommand.ensureIncusHold(true, shell, true, OperatorCommand.PLAIN));

    assertEquals(
        "  ⚠ apt-mark hold incus failed: E: Could not get lock /var/lib/dpkg/lock. Run 'sail"
            + " migrate' again to hold Incus.\n",
        out);
    assertEquals("", quiet);
  }

  @Test
  void initAndMigrateWriteTheSameNeedrestartPolicy(@TempDir Path dir) throws Exception {
    var provisioning = new ScriptedShellExecutor(new ShellExec.Result(0, "", ""));
    new HostProvisioner(provisioning, null, OperatorCommand.SUDO)
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
}
