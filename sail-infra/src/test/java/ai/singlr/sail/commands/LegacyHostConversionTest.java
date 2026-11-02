/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.commands;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.sail.common.Remedied;
import ai.singlr.sail.common.Remedy;
import ai.singlr.sail.engine.ContainerExec;
import ai.singlr.sail.engine.ScriptedShellExecutor;
import ai.singlr.sail.engine.ShellExec;
import ai.singlr.sail.engine.SystemdServiceInstaller;
import ai.singlr.sail.store.LegacyConversion;
import ai.singlr.sail.store.LegacyDatabase;
import ai.singlr.sail.store.SchemaManager;
import ai.singlr.sail.store.Sqlite;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LegacyHostConversionTest {

  @TempDir Path dir;
  private Path db;
  private Path proc;
  private final List<String> calls = new ArrayList<>();
  private final List<String> printed = new ArrayList<>();

  private class FakeService implements LegacyHostConversion.Service {
    boolean running;
    boolean failsToStart;

    FakeService(boolean running) {
      this.running = running;
    }

    @Override
    public boolean running() {
      calls.add("running");
      return running;
    }

    @Override
    public void stop() {
      calls.add("stop");
      running = false;
    }

    @Override
    public void start() throws IOException {
      calls.add("start");
      if (failsToStart) {
        throw new IOException("unit is masked");
      }
      running = true;
    }
  }

  @BeforeEach
  void setUp() throws IOException {
    db = dir.resolve("sail.db");
    proc = Files.createDirectories(dir.resolve("proc"));
    LegacyDatabase.create(db, "catalog").close();
  }

  private LegacyConversion conversion() {
    var host = new LegacyHostConversion.BoxHost(db, proc, new ScriptedShellExecutor());
    return LegacyConversion.at(db, dir.resolve("host.yaml"), host).orElseThrow();
  }

  private void convert(FakeService service) {
    LegacyHostConversion.convert(conversion(), db, Optional.ofNullable(service), printed::add);
  }

  private static void assertUnchangedSaid(IllegalStateException refused, Path db) {
    assertTrue(
        Remedied.reasonOf(refused)
            .contains(" Nothing was changed: " + db + " is still Sail 0.58's"),
        refused.getMessage());
    assertTrue(
        Remedied.remedyOf(refused)
            .plain()
            .endsWith(
                " To keep this box on 0.58 meanwhile, reinstall it: curl -fsSL"
                    + " https://raw.githubusercontent.com/standardapplied/sail/main/install.sh"
                    + " | sudo bash -s 0.58.0 && sudo systemctl restart sail-api"),
        refused.getMessage());
  }

  private int version() {
    try (var sqlite = Sqlite.open(db)) {
      return new SchemaManager(sqlite).currentVersion();
    }
  }

  private void holder(String pid, Path target, String command) throws IOException {
    var process = Files.createDirectories(proc.resolve(pid).resolve("fd"));
    Files.createSymbolicLink(process.resolve("3"), target);
    Files.writeString(proc.resolve(pid).resolve("cmdline"), command.replace(' ', '\0') + "\0");
  }

  @Test
  void aRunningSailApiIsStoppedForTheConversionAndStartedAgainAfterIt() {
    var service = new FakeService(true);

    convert(service);

    assertEquals(List.of("running", "stop", "start"), calls);
    assertTrue(service.running);
    assertEquals(SchemaManager.CURRENT_VERSION, version());
    assertEquals(1, printed.size());
    assertTrue(
        printed.getFirst().contains("Converted " + db + " from Sail 0.58."), printed.getFirst());
  }

  @Test
  void aStoppedSailApiStaysStopped() {
    var service = new FakeService(false);

    convert(service);

    assertEquals(List.of("running"), calls);
    assertFalse(service.running);
    assertEquals(SchemaManager.CURRENT_VERSION, version());
  }

  @Test
  void aRehearsalTouchesNoService() {
    convert(null);

    assertEquals(List.of(), calls);
    assertEquals(SchemaManager.CURRENT_VERSION, version());
  }

  @Test
  void aConversionThatMustWaitRefusesBeforeSailApiIsAskedAnything() {
    try (var legacy = Sqlite.open(db)) {
      legacy.execute(
          "INSERT INTO runs (id, project, agent, status, started_at) VALUES ('run-1', 'sail',"
              + " 'claude-code', 'running', '2026-11-02T18:19:21Z')");
    }
    var service = new FakeService(true);

    var refused = assertThrows(IllegalStateException.class, () -> convert(service));

    assertEquals(List.of(), calls);
    assertTrue(service.running);
    assertEquals(LegacyConversion.VERSION, version());
    assertUnchangedSaid(refused, db);
    assertTrue(Remedied.reasonOf(refused).endsWith("cannot serve."), refused.getMessage());
  }

  @Test
  void anotherHolderRefusesTheConversionAndSailApiStaysStoppedSayingSo() throws IOException {
    holder("4242", db.toRealPath(), "/usr/local/bin/sail _sync");
    var service = new FakeService(true);

    var refused = assertThrows(IllegalStateException.class, () -> convert(service));

    assertEquals(List.of("running", "stop"), calls);
    assertEquals(LegacyConversion.VERSION, version());
    assertTrue(
        Remedied.reasonOf(refused)
            .contains("Other processes hold " + db + " open, so it is not converted"),
        refused.getMessage());
    assertTrue(
        Remedied.reasonOf(refused).contains("4242 (/usr/local/bin/sail _sync)"),
        refused.getMessage());
    assertTrue(
        Remedied.reasonOf(refused).endsWith(", so sail-api stays stopped until it is converted."),
        refused.getMessage());
    assertEquals(
        List.of("migrate"),
        Remedied.remedyOf(refused).commands().stream().map(Remedy.Command::line).toList());
    assertUnchangedSaid(refused, db);
    assertFalse(Files.exists(dir.resolve("sail.db.0.58.bak")));
  }

  @Test
  void aHolderIsNeitherThisProcessNorOneWhoseTablesNameAnotherFile() throws IOException {
    holder(Long.toString(ProcessHandle.current().pid()), db.toRealPath(), "java");
    holder("77", dir.resolve("other.db"), "sail serve");
    holder("self", db.toRealPath(), "proc self link");
    Files.createDirectories(proc.resolve("88"));

    assertDoesNotThrow(() -> LegacyHostConversion.requireNoOtherHolder(db, proc));
  }

  @Test
  void aRunHasEndedOnlyWhenItsUnitIsInactiveOrFailedOrItsContainerIsStoppedOrGone() {
    var shell =
        new ScriptedShellExecutor(new ShellExec.Result(1, "", "Failed to connect to bus"))
            .onOk("sail-agent-live.service", "active\n")
            .onOk("sail-agent-stopping.service", "deactivating\n")
            .onOk("sail-agent-gone.service", "inactive\n")
            .onOk("sail-agent-crashed.service", "failed\n")
            .on(
                "sail-agent-orphan.service",
                new ShellExec.Result(1, "", "Error: Instance not found"))
            .on("info -- deleted", new ShellExec.Result(1, "", "Error: Instance not found"))
            .onOk("info -- parked", "Name: parked\nStatus: STOPPED\nType: container\n")
            .on("parked", new ShellExec.Result(1, "", "Error: Instance is not running"))
            .onOk("info -- sail", "Name: sail\nStatus: RUNNING\n")
            .on("info -- unreachable", new ShellExec.Result(1, "", "Error: connection refused"))
            .onThrow("sail-agent-hung.service", new IOException("incus timed out"));
    var host = new LegacyHostConversion.BoxHost(db, proc, shell);

    assertTrue(host.running("sail", "sail-agent-live"));
    assertTrue(host.running("sail", "sail-agent-stopping"));
    assertFalse(host.running("sail", "sail-agent-gone"));
    assertFalse(host.running("sail", "sail-agent-crashed"));
    assertFalse(host.running("deleted", "sail-agent-orphan"));
    assertFalse(host.running("parked", "sail-agent-left"));
    assertTrue(host.running("unreachable", "sail-agent-unknown"));
    assertTrue(host.running("sail", "sail-agent-unanswered"));
    assertTrue(host.running("sail", "sail-agent-hung"));
    assertEquals(
        ContainerExec.asDevUser(
            "sail",
            List.of(
                "systemctl",
                "--user",
                "show",
                "--property=ActiveState",
                "--value",
                "--",
                "sail-agent-gone.service")),
        shell.arguments().get(2));
    assertTrue(
        shell.invocations().contains("incus info -- deleted"), shell.invocations().toString());
  }

  @Test
  void sailApiIsMeantToRunInEveryStateButInactive() throws Exception {
    for (var state : List.of("active", "activating", "deactivating", "failed", "inactive")) {
      var shell =
          new ScriptedShellExecutor()
              .onOk("--property=ActiveState", "ActiveState=" + state + "\nLoadState=loaded\n");
      var sailApi =
          new LegacyHostConversion.SailApi(
              HostServiceInstallers.create(
                  shell,
                  SystemdServiceInstaller.Mode.SYSTEM,
                  dir,
                  () -> Path.of("/usr/local/bin/sail")));

      assertEquals(!state.equals("inactive"), sailApi.running(), state);
    }
  }

  @Test
  void aFailureOnceTheDatabaseIsConvertedNeverSaysNothingChangedAndStartsSailApiAgain() {
    var service =
        new FakeService(true) {
          @Override
          public void stop() {
            super.stop();
            LegacyHostConversion.convert(conversion(), db, Optional.empty(), line -> {});
          }
        };

    var refused = assertThrows(IllegalStateException.class, () -> convert(service));

    assertTrue(
        Remedied.reasonOf(refused).startsWith("A backup of a 0.58 database is already at"),
        refused.getMessage());
    assertFalse(refused.getMessage().contains("Nothing was changed"), refused.getMessage());
    assertEquals(List.of("running", "stop", "start"), calls);
    assertEquals(SchemaManager.CURRENT_VERSION, version());
  }

  @Test
  void aSailApiThatDoesNotStartAfterAFailureOnTheConvertedDatabaseKeepsTheFailureFirst() {
    var service =
        new FakeService(true) {
          @Override
          public void stop() {
            super.stop();
            LegacyHostConversion.convert(conversion(), db, Optional.empty(), line -> {});
          }
        };
    service.failsToStart = true;

    var refused = assertThrows(IllegalStateException.class, () -> convert(service));

    assertTrue(
        Remedied.reasonOf(refused).startsWith("A backup of a 0.58 database is already at"),
        refused.getMessage());
    assertEquals(1, refused.getSuppressed().length);
    assertTrue(
        refused.getSuppressed()[0].getMessage().contains("did not start again"),
        refused.getSuppressed()[0].getMessage());
  }

  @Test
  void aConversionAnotherMigrateMadeFirstSaysNothingAndStartsTheServiceAgain() throws IOException {
    var conversion = conversion();
    LegacyHostConversion.convert(conversion(), db, Optional.empty(), line -> {});
    Files.move(dir.resolve("sail.db.0.58.bak"), dir.resolve("aside.bak"));
    var service = new FakeService(true);

    LegacyHostConversion.convert(conversion, db, Optional.of(service), printed::add);

    assertEquals(List.of("running", "stop", "start"), calls);
    assertEquals(List.of(), printed);
  }

  @Test
  void aSailApiThatCannotBeStoppedRefusesNamingHowToStopIt() {
    var service =
        new FakeService(true) {
          @Override
          public void stop() {
            throw new IllegalStateException("access denied");
          }
        };

    var refused = assertThrows(IllegalStateException.class, () -> convert(service));

    assertTrue(
        Remedied.reasonOf(refused)
            .startsWith("Could not stop sail-api to convert the 0.58 database: access denied."),
        refused.getMessage());
    assertUnchangedSaid(refused, db);
    assertEquals(
        List.of("systemctl stop sail-api", "migrate"),
        Remedied.remedyOf(refused).commands().stream().map(Remedy.Command::line).toList());
    assertEquals(LegacyConversion.VERSION, version());
  }

  @Test
  void aSailApiThatDoesNotStartAgainSaysTheDatabaseWasConverted() {
    var service = new FakeService(true);
    service.failsToStart = true;

    var refused = assertThrows(IllegalStateException.class, () -> convert(service));

    assertEquals(
        "The 0.58 database was converted, but sail-api did not start again: unit is masked",
        Remedied.reasonOf(refused));
    assertEquals(
        List.of("systemctl start sail-api"),
        Remedied.remedyOf(refused).commands().stream().map(Remedy.Command::line).toList());
    assertEquals(SchemaManager.CURRENT_VERSION, version());
    assertTrue(printed.getFirst().contains("backed up at"), printed.getFirst());
  }

  @Test
  void aReportSaysWhatWasKeptAndDroppedAndWhereTheBackupIs() {
    var kept = new LinkedHashMap<String, Long>();
    kept.put("projects", 8L);
    kept.put("specs", 133L);
    var dropped = new LinkedHashMap<String, Long>();
    dropped.put("specs", 669L);
    dropped.put("files", 0L);
    dropped.put("runs", 1988L);
    var backup = dir.resolve("sail.db.0.58.bak");

    var main =
        LegacyHostConversion.described(
            db, new LegacyConversion.Report(backup, false, kept, dropped, Optional.empty()));
    var node =
        LegacyHostConversion.described(
            db,
            new LegacyConversion.Report(
                backup, true, kept, dropped, Optional.of("database is locked")));

    assertEquals(
        "  ✓ Converted "
            + db
            + " from Sail 0.58. Kept 8 projects, 133 specs. Dropped 669 specs, 1988 runs.\n"
            + "    The 0.58 database is backed up at "
            + backup,
        main);
    assertTrue(
        node.startsWith(
            "  ✓ Converted "
                + db
                + " from Sail 0.58 as a node. Kept this box's own rows; its next sync pulls what"
                + " main kept. Dropped 669 specs, 1988 runs."),
        node);
    assertTrue(
        node.endsWith("  ⚠ The space the dropped rows held was not reclaimed: database is locked"),
        node);
  }
}
