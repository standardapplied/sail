/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.commands;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.sail.api.EventBus;
import ai.singlr.sail.api.SailApiServer;
import ai.singlr.sail.api.SailOperations;
import ai.singlr.sail.api.SpecStoreAuditPersister;
import ai.singlr.sail.config.SpecStatus;
import ai.singlr.sail.config.SyncConfig;
import ai.singlr.sail.config.YamlUtil;
import ai.singlr.sail.engine.ShellExecutor;
import ai.singlr.sail.engine.SyncOperations;
import ai.singlr.sail.identity.Acting;
import ai.singlr.sail.store.EventStore;
import ai.singlr.sail.store.FdeStore;
import ai.singlr.sail.store.ReviewStore;
import ai.singlr.sail.store.RoomStore;
import ai.singlr.sail.store.SchemaManager;
import ai.singlr.sail.store.SpecStore;
import ai.singlr.sail.store.Sqlite;
import ai.singlr.sail.store.TokenStore;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

/** {@code sail spec prune} always reports first, and erases only with {@code --apply}. */
class ApiSpecPruneCommandTest {

  @TempDir Path tempDir;
  private Sqlite db;
  private SpecStore specs;
  private SailApiServer server;
  private String token;

  @BeforeEach
  void setUp() throws Exception {
    Acting.system(
        () -> {
          db = Sqlite.open(tempDir.resolve("test.db"));
          new SchemaManager(db).migrate();
          var tokens = new TokenStore(db);
          var fde = new FdeStore(db).add("uday", "Uday", "uday@example.com", "admin");
          token = tokens.create("uday", "admin", fde.id(), null).token();
          specs = new SpecStore(db);
          var bus = new EventBus();
          var persister = new SpecStoreAuditPersister(new EventStore(db));
          var operations =
              new SailOperations(
                      new ShellExecutor(false),
                      "sail.yaml",
                      bus,
                      persister,
                      specs,
                      new ReviewStore(db))
                  .useRooms(new RoomStore(db))
                  .useControlPlane(
                      db,
                      tempDir,
                      new SyncOperations(
                          db,
                          "main",
                          tempDir,
                          SyncConfig::unset,
                          target -> {
                            throw new IOException("no main");
                          }));
          server = new SailApiServer("127.0.0.1", 0, operations, tokens, bus, persister);
          server.start();
          specs.create(archived("old"));
          specs.setContent("old", "a body only old holds", "");
        });
  }

  @AfterEach
  void tearDown() {
    if (server != null) server.close();
    if (db != null) db.close();
  }

  @Test
  void withoutApplyItOnlyReports() {
    var run = run("prune", "old");

    assertEquals(0, run.exit(), run.err());
    assertTrue(run.out().contains("Would erase 1 specs"), run.out());
    assertTrue(run.out().contains("re-run with --apply"), run.out());
    assertTrue(specs.findById("old").isPresent());
  }

  @Test
  void applyPrintsTheReportAndThenErases() {
    var run = run("prune", "old", "--apply");

    assertEquals(0, run.exit(), run.err());
    var erasing = run.out().indexOf("Erasing 1 specs");
    var erased = run.out().indexOf("Erased 1 specs");
    assertTrue(erasing >= 0 && erased > erasing, run.out());
    assertFalse(run.out().contains("re-run with --apply"), run.out());
    assertTrue(run.out().contains("Other boxes erase it on their next sync"), run.out());
    assertTrue(specs.findById("old").isEmpty());
  }

  @Test
  void jsonPrintsTheReportAndApplyJsonOnlyWhatWasErased() {
    var dry = YamlUtil.parseMap(run("prune", "old", "--json").out());
    assertEquals(true, dry.get("dry_run"));
    assertEquals(1, dry.get("specs"));

    var applied = YamlUtil.parseMap(run("prune", "old", "--apply", "--json").out());
    assertEquals(false, applied.get("dry_run"));
    assertEquals(
        List.of(Map.of("type", "spec", "id", "old"), Map.of("type", "room", "id", "old")),
        applied.get("entries"),
        "the spec and the identity room it minted, which has no room row yet");
  }

  @Test
  void aPolicyChoosesByStatusAndWholeDays() {
    db.execute("UPDATE specs SET archived_at = '2026-01-01T00:00:00Z'");

    var run = run("prune", "--status", "archived", "--older-than", "30d", "-p", "proj", "--json");

    assertEquals(0, run.exit(), run.err());
    assertEquals(1, YamlUtil.parseMap(run.out()).get("specs"));
  }

  @Test
  void aSelectionThatIsNotOneIsRefusedBeforeAnythingIsSent() {
    for (var args :
        List.of(
            new String[] {"prune"},
            new String[] {"prune", "old", "--status", "archived", "--older-than", "1d"},
            new String[] {"prune", "old", "--older-than", "1d"},
            new String[] {"prune", "--status", "archived"},
            new String[] {"prune", "--status", "archived", "--older-than", "12h"},
            new String[] {"prune", "--status", "archived", "--older-than", "1d", "-p", "a b"},
            new String[] {"prune", "no/such"})) {
      var run = run(args);
      assertNotEquals(0, run.exit(), String.join(" ", args));
    }
    assertTrue(specs.findById("old").isPresent());
  }

  @Test
  void aNodesPruneSaysMainErasesIt() {
    var requested =
        ApiSpecPruneCommand.render(
            Map.of("dry_run", false, "requested", true, "specs", 2, "blob_bytes", 10));

    assertTrue(requested.contains("Asked main to erase 2 specs"), requested);
    assertTrue(requested.contains("10 bytes of content"), requested);
  }

  private record Run(int exit, String out, String err) {}

  private Run run(String... args) {
    var originalOut = System.out;
    var originalErr = System.err;
    var out = new ByteArrayOutputStream();
    var err = new ByteArrayOutputStream();
    System.setOut(new PrintStream(out, true, StandardCharsets.UTF_8));
    System.setErr(new PrintStream(err, true, StandardCharsets.UTF_8));
    try {
      System.setProperty("SAIL_SERVER", "http://127.0.0.1:" + server.port());
      System.setProperty("SAIL_TOKEN", token);
      var exit = new CommandLine(new SpecCommand()).execute(args);
      return new Run(
          exit, out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8));
    } finally {
      System.setOut(originalOut);
      System.setErr(originalErr);
      System.clearProperty("SAIL_SERVER");
      System.clearProperty("SAIL_TOKEN");
    }
  }

  private static SpecStore.SpecRow archived(String id) {
    return new SpecStore.SpecRow(
        id,
        "proj",
        id,
        SpecStatus.ARCHIVED,
        "uday",
        null,
        null,
        null,
        null,
        0,
        "uday",
        "",
        "",
        "uday",
        List.of(),
        List.of());
  }
}
