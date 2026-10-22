/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.commands;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import ai.singlr.sail.api.SyncReport;
import ai.singlr.sail.api.SyncRequest;
import ai.singlr.sail.config.SpecStatus;
import ai.singlr.sail.config.SyncConfig;
import ai.singlr.sail.engine.OperatorCommand;
import ai.singlr.sail.engine.PipedSyncChannel;
import ai.singlr.sail.engine.ShellExec;
import ai.singlr.sail.engine.SyncOperations;
import ai.singlr.sail.identity.Acting;
import ai.singlr.sail.identity.Actor;
import ai.singlr.sail.identity.ProjectScope;
import ai.singlr.sail.identity.Role;
import ai.singlr.sail.store.AuthSessionStore;
import ai.singlr.sail.store.DataMigration;
import ai.singlr.sail.store.DataMigrations;
import ai.singlr.sail.store.DatabaseCatalog;
import ai.singlr.sail.store.FdeStore;
import ai.singlr.sail.store.MigrationRunner;
import ai.singlr.sail.store.ProjectStore;
import ai.singlr.sail.store.SchemaManager;
import ai.singlr.sail.store.SpecStore;
import ai.singlr.sail.store.Sqlite;
import ai.singlr.sail.sync.SyncDatabase;
import ai.singlr.sail.sync.SyncTransitionSink;
import ai.singlr.sail.sync.SyncTransportException;
import ai.singlr.sail.sync.SyncWire;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A node's round after the main at its address is set up again: main's {@code _sync} server answers
 * a real session of {@code nia}, from the database the node synced with, from a new empty one, or
 * from a copy of the first. A node never says it is in sync with a database it did not sync with,
 * and moves nothing to or from one.
 */
class MainRebuiltRoundTest {

  private static final String TARGET = "sail@main";

  @TempDir Path tempDir;
  private final List<SyncDatabase> mains = new ArrayList<>();
  private Main serving;
  private PipedSyncChannel.Main answering;
  private Sqlite nodeDb;
  private SyncOperations node;

  /** One main database answering at {@link #TARGET} as the box {@code id}. */
  private record Main(SyncDatabase database, String id, String token) {
    Sqlite db() {
      return database.db();
    }
  }

  @BeforeEach
  void setUp() {
    serving = main("a.db", "main", "gamma");
    nodeDb = Sqlite.open(tempDir.resolve("node.db"));
    new SchemaManager(nodeDb).migrate();
    node =
        new SyncOperations(
            nodeDb,
            "node",
            tempDir.resolve("projects"),
            () -> new SyncConfig(SyncConfig.ROLE_NODE, TARGET, "nia", "node-box"),
            target -> {
              if (answering != null) {
                return PipedSyncChannel.to(answering);
              }
              var main = serving;
              return PipedSyncChannel.to(
                  (in, out) ->
                      SyncServerCommand.serve(
                          main.database(),
                          main.id(),
                          main.token(),
                          in,
                          out,
                          SyncTransitionSink.NONE,
                          () -> new SyncConfig(SyncConfig.ROLE_MAIN, null, "ada", main.id())));
            },
            event -> {},
            SyncOperations.PLAIN_NOTICES,
            new NoContainers());
    node.prepare();
  }

  @AfterEach
  void tearDown() {
    nodeDb.close();
    mains.forEach(SyncDatabase::close);
  }

  /** A main set up at {@code file} as the box {@code id}, holding {@code project}, nia added. */
  private Main main(String file, String id, String project) {
    var path = tempDir.resolve(file);
    try (var db = Sqlite.open(path)) {
      MigrationRunner.applyAll(db, DataMigrations.all(), DataMigration.Prompter.NON_INTERACTIVE);
    }
    var database = SyncDatabase.converge(path, id);
    mains.add(database);
    var fdes = new FdeStore(database.db());
    fdes.add("ada", "Ada", "ada@x.dev", "admin");
    fdes.add("nia", "Nia", "nia@x.dev", "member");
    Acting.system(() -> new ProjectStore(database.db()).upsert(project, "name: " + project + "\n"));
    return new Main(database, id, token(database.db()));
  }

  /** A main serving a copy of {@code source}'s database file, as the box {@code id}. */
  private Main copyOf(Main source, String file, String id) {
    var path = tempDir.resolve(file);
    source.db().execute("VACUUM INTO ?", path.toString());
    var database = SyncDatabase.converge(path, id);
    mains.add(database);
    return new Main(database, id, token(database.db()));
  }

  private static String token(Sqlite db) {
    var nia = new FdeStore(db).byHandle("nia").orElseThrow();
    return new AuthSessionStore(db).create(nia.id(), Duration.ofHours(1)).token();
  }

  private SyncReport round() throws Exception {
    var err = System.err;
    System.setErr(new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8));
    try {
      return Actor.call(
          new Actor("nia", Role.MEMBER, Actor.Lane.CLI), () -> node.sync(new SyncRequest(null)));
    } finally {
      System.setErr(err);
    }
  }

  private static String rendered(SyncReport round) {
    return SyncCommand.render(round, false, OperatorCommand.PLAIN::render);
  }

  private void write(String id) {
    Acting.as("nia", () -> new SpecStore(nodeDb).create(spec(id)));
  }

  private static void mainWrite(Main main, String id) {
    Acting.as("nia", () -> new SpecStore(main.db()).create(spec(id)));
  }

  /**
   * The synced rows a database holds, its log and its checkpoints. Main's own record of the box
   * that said hello ({@code fde_boxes}) is local to main and never synced, so it is not here.
   */
  private static List<String> held(Sqlite db) {
    var held = new ArrayList<String>();
    held.addAll(db.query("SELECT 'project ' || name FROM projects ORDER BY name", r -> r.text(0)));
    held.addAll(
        db.query(
            "SELECT 'spec ' || id || ' ' || rev || ' ' || COALESCE(base_rev, '-') FROM specs"
                + " ORDER BY id",
            r -> r.text(0)));
    held.addAll(
        db.query(
            "SELECT 'log ' || entity_type || ' ' || entity_id || ' ' || rev FROM change_log"
                + " ORDER BY seq",
            r -> r.text(0)));
    held.addAll(
        db.query(
            "SELECT 'checkpoint ' || peer || ' ' || entity_type || ' ' || checkpoint"
                + " FROM sync_state ORDER BY peer, entity_type",
            r -> r.text(0)));
    held.addAll(db.query("SELECT 'catalog ' || COALESCE(main, '-') FROM catalog", r -> r.text(0)));
    return held;
  }

  private String refusal(Main now) {
    return "Main at "
        + TARGET
        + " is not the database this box synced with (catalog "
        + new DatabaseCatalog(mains.getFirst().db()).id()
        + ", now "
        + new DatabaseCatalog(now.db()).id()
        + "). Restore main's database (/var/lib/sail/sail.db) from a copy, then sync again.";
  }

  @Test
  void aNodeRecordsTheCatalogOfTheMainDatabaseItFirstSyncsWith() throws Exception {
    assertEquals(List.of(), new DatabaseCatalog(nodeDb).main().stream().toList());

    round();

    var catalog = new DatabaseCatalog(serving.db()).id();
    assertEquals(List.of(catalog), new DatabaseCatalog(nodeDb).main().stream().toList());
    assertNotEquals(catalog, new DatabaseCatalog(nodeDb).id());
  }

  @Test
  void twoRoundsWithTheSameMainAndNothingChangedAreAlreadyInSync() throws Exception {
    round();

    assertEquals("  ✓ Already in sync with main.", rendered(round()));
  }

  @Test
  void anEmptyMainAtTheSameAddressIsRefusedBeforeAnythingMoves() throws Exception {
    round();
    write("g2");
    var before = held(nodeDb);
    var rebuilt = main("b.db", "main-b", "demo");
    var mainBefore = held(rebuilt.db());
    serving = rebuilt;

    var refused = assertThrows(SyncTransportException.class, this::round);

    assertEquals(refusal(rebuilt), refused.getMessage());
    assertEquals("refused", refused.kind());
    assertEquals(before, held(nodeDb));
    assertEquals(mainBefore, held(rebuilt.db()));
  }

  @Test
  void anEmptyMainUnderTheBoxIdTheNodeSyncedWithIsRefusedOnItsCatalog() throws Exception {
    round();
    write("g2");
    var before = held(nodeDb);
    var rebuilt = main("b.db", "main", "demo");
    var mainBefore = held(rebuilt.db());
    serving = rebuilt;

    var refused = assertThrows(SyncTransportException.class, this::round);

    assertEquals(refusal(rebuilt), refused.getMessage());
    assertEquals(before, held(nodeDb));
    assertEquals(mainBefore, held(rebuilt.db()));
  }

  @Test
  void aMainRebuiltFromACopyOfItsDatabaseTakesTheNodesNewerWork() throws Exception {
    round();
    var restored = copyOf(serving, "copy.db", "main-restored");
    write("g2");
    serving = restored;

    var round = round();

    assertEquals(1, round.report().pushed());
    assertEquals(
        List.of("g2"), restored.db().query("SELECT id FROM specs ORDER BY id", r -> r.text(0)));
    assertEquals("  ✓ Already in sync with main.", rendered(round()));
  }

  @Test
  void aMainRestoredInPlaceFromAnOlderCopyIsPagedFromTheCopyNotFromTheNodesOldCheckpoint()
      throws Exception {
    round();
    var restored = copyOf(serving, "copy.db", serving.id());
    for (var id : List.of("m1", "m2", "m3")) {
      mainWrite(serving, id);
    }
    round();
    serving = restored;
    mainWrite(restored, "m4");

    var round = round();

    assertNotEquals("  ✓ Already in sync with main.", rendered(round));
    assertEquals(
        List.of("m4"),
        nodeDb.query("SELECT id FROM specs WHERE id = 'm4'", r -> r.text(0)),
        "main's write after the restore reaches the node");
    assertEquals("  ✓ Already in sync with main.", rendered(round()));
  }

  @Test
  void aNodeThatSyncedBeforeCatalogsRecordsMainsOnItsNextRound() throws Exception {
    round();
    nodeDb.execute("UPDATE catalog SET main = NULL");

    assertEquals("  ✓ Already in sync with main.", rendered(round()));
    assertEquals(
        List.of(new DatabaseCatalog(serving.db()).id()),
        new DatabaseCatalog(nodeDb).main().stream().toList());
  }

  @Test
  void aRefusalIsWhatTheNodeSaysEvenWhenMainHangsUpBeforeItsGoodbye() throws Exception {
    round();
    var other =
        new SyncWire.Welcome(
            SyncWire.PROTOCOL, "0.54.0", "main", "nia", 0, ProjectScope.all(), "another");
    answering =
        (in, out) -> {
          SyncWire.readFramed(in);
          in.close();
          out.write((SyncWire.encode(other) + "\n").getBytes(StandardCharsets.UTF_8));
          out.flush();
        };

    var refused = assertThrows(SyncTransportException.class, this::round);

    assertEquals(
        "Main at sail@main is not the database this box synced with (catalog "
            + new DatabaseCatalog(serving.db()).id()
            + ", now another). Restore main's database (/var/lib/sail/sail.db) from a copy, then"
            + " sync again.",
        refused.getMessage());
    assertEquals(1, refused.getSuppressed().length);
  }

  @Test
  void aMainThatNamesNoCatalogIsRefusedBeforeAnythingMovesEvenBeforeOneIsRecorded()
      throws Exception {
    round();
    nodeDb.execute("UPDATE catalog SET main = NULL");
    write("g2");
    var before = held(nodeDb);
    var older =
        new SyncWire.Welcome(
            SyncWire.PROTOCOL, "0.53.0", "main", "nia", 0, ProjectScope.all(), null);
    answering =
        (in, out) -> {
          SyncWire.readFramed(in);
          in.close();
          out.write((SyncWire.encode(older) + "\n").getBytes(StandardCharsets.UTF_8));
          out.flush();
        };

    var refused = assertThrows(SyncTransportException.class, this::round);

    assertEquals("refused", refused.kind());
    assertEquals(
        "Main at sail@main names no database catalog, so this box cannot tell whether it is the"
            + " database it synced with: upgrade main ('sudo sail upgrade' on main), then sync"
            + " again.",
        OperatorCommand.SUDO.render(refused),
        "read through sudo on a system box");
    assertEquals(
        "Main at sail@main names no database catalog, so this box cannot tell whether it is the"
            + " database it synced with: upgrade main ('sail upgrade' on main), then sync again.",
        OperatorCommand.PLAIN.render(refused),
        "read by root logged in directly");
    assertEquals(before, held(nodeDb));
  }

  private static SpecStore.SpecRow spec(String id) {
    return new SpecStore.SpecRow(
        id,
        "gamma",
        "the " + id + " spec",
        SpecStatus.PENDING,
        null,
        null,
        null,
        null,
        null,
        0,
        "nia",
        "",
        "",
        "nia",
        List.of(),
        List.of());
  }

  /** An Incus that holds no container. */
  private static final class NoContainers implements ShellExec {
    @Override
    public Result exec(List<String> command) {
      return new Result(0, "[]", "");
    }

    @Override
    public Result exec(List<String> command, Path workDir, Duration timeout) {
      return exec(command);
    }

    @Override
    public boolean isDryRun() {
      return false;
    }
  }
}
