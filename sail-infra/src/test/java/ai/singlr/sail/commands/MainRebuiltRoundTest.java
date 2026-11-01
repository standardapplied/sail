/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.commands;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import ai.singlr.sail.api.SyncReport;
import ai.singlr.sail.api.SyncRequest;
import ai.singlr.sail.common.DateTimeUtils;
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
import ai.singlr.sail.store.BoxDatabase;
import ai.singlr.sail.store.ChangeLog;
import ai.singlr.sail.store.DatabaseCatalog;
import ai.singlr.sail.store.Erasure;
import ai.singlr.sail.store.FdeStore;
import ai.singlr.sail.store.MessageStore;
import ai.singlr.sail.store.ProjectStore;
import ai.singlr.sail.store.RoomStore;
import ai.singlr.sail.store.RunStore;
import ai.singlr.sail.store.SchemaManager;
import ai.singlr.sail.store.SpecStore;
import ai.singlr.sail.store.Sqlite;
import ai.singlr.sail.sync.SyncDatabase;
import ai.singlr.sail.sync.SyncSession;
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
  private final List<String> notices = new ArrayList<>();

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
            remedy -> notices.add(OperatorCommand.SUDO.render(remedy)),
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
      BoxDatabase.prepare(db);
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
    notices.clear();
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

  private static final String RESTORED =
      "): main was restored from a copy older than this box's last sync. ";

  private static List<String> specIds(Sqlite db) {
    return db.query("SELECT id || ' ' || rev FROM specs ORDER BY id", r -> r.text(0));
  }

  /** Main writes {@code ids} after {@code copy} was taken, and the node pulls them. */
  private Main pulledAfterACopy(String copyFile, String copyId, String... ids) throws Exception {
    mainWrite(serving, "g0");
    round();
    var copy = copyOf(serving, copyFile, copyId);
    for (var id : ids) {
      mainWrite(serving, id);
    }
    round();
    return copy;
  }

  @Test
  void aMainRestoredInPlaceFromAnOlderCopyTakesTheSpecsItLostFromTheNodeIntoTheirHistory()
      throws Exception {
    serving = pulledAfterACopy("copy.db", serving.id(), "r-late-1", "r-late-2");

    var round = round();

    assertEquals(
        List.of(
            "main no longer holds 2 spec(s) this box had pulled (r-late-1, r-late-2"
                + RESTORED
                + "Each stays in its history: sudo sail spec history <id>."),
        notices);
    assertEquals(specIds(serving.db()), specIds(nodeDb), "the node holds what main holds");
    assertEquals(List.of(), round.denials());
    assertEquals(
        List.of("r-late-1", "r-late-1"),
        new SpecStore(nodeDb).history("r-late-1").stream().map(entry -> entry.entityId()).toList(),
        "the pulled version and its withdrawal stay in the spec's history");
    assertEquals("  ✓ Already in sync with main.", rendered(round()));
    assertEquals(List.of(), notices);
  }

  @Test
  void aMainRestoredInPlaceThatRevisedTheReadSpecPastHistoryAndCompactedItStillTakesTheLostSpecs()
      throws Exception {
    mainWrite(serving, "seed");
    round();
    var copy = copyOf(serving, "copy.db", serving.id());
    mainWrite(serving, "lost");
    Acting.as("nia", () -> new SpecStore(serving.db()).setContent("seed", "lost revision", ""));
    round();
    serving = copy;
    for (var i = 0; i < ChangeLog.HISTORY_REVISIONS + 5; i++) {
      var content = "restored revision " + i;
      Acting.as("nia", () -> new SpecStore(serving.db()).setContent("seed", content, ""));
    }
    new ChangeLog(serving.db()).compact("spec", List.of("seed"), id -> null);

    round();

    assertEquals(specIds(serving.db()), specIds(nodeDb));
  }

  @Test
  void aMainRestoredInPlaceThatWroteAsManyAsItLostStillTakesTheLostSpecsFromTheNode()
      throws Exception {
    serving = pulledAfterACopy("copy.db", serving.id(), "r-late-1", "r-late-2");
    mainWrite(serving, "after-1");
    mainWrite(serving, "after-2");

    round();

    assertEquals(
        List.of(
            "main no longer holds 2 spec(s) this box had pulled (r-late-1, r-late-2"
                + RESTORED
                + "Each stays in its history: sudo sail spec history <id>."),
        notices);
    assertEquals(specIds(serving.db()), specIds(nodeDb), "the node holds what main holds");
    assertEquals("  ✓ Already in sync with main.", rendered(round()));
    assertEquals(List.of(), notices);
  }

  @Test
  void aMainRestoredInPlaceThatWroteFiftyAfterLosingFiftyTakesTheFiftyFromTheNode()
      throws Exception {
    var lost = new ArrayList<String>();
    for (var i = 1; i <= 50; i++) {
      lost.add("lost-%02d".formatted(i));
    }
    serving = pulledAfterACopy("copy.db", serving.id(), lost.toArray(String[]::new));
    for (var i = 1; i <= 50; i++) {
      mainWrite(serving, "after-%02d".formatted(i));
    }

    round();

    assertEquals(
        List.of(
            "main no longer holds 50 spec(s) this box had pulled (lost-01, lost-02, lost-03,"
                + " lost-04, lost-05 and 45 more"
                + RESTORED
                + "Each stays in its history: sudo sail spec history <id>."),
        notices);
    assertEquals(specIds(serving.db()), specIds(nodeDb), "the node holds what main holds");
    assertEquals("  ✓ Already in sync with main.", rendered(round()));
  }

  @Test
  void aScopedNodeThatReadNothingOfATypeStillTakesWhatAMainRestoredInPlaceWroteBelowItsCheckpoint()
      throws Exception {
    var fdes = new FdeStore(serving.db());
    fdes.restrict("nia");
    fdes.grant("nia", "gamma");
    Acting.system(() -> new ProjectStore(serving.db()).upsert("delta", "name: delta\n"));
    round();
    var copy = copyOf(serving, "copy.db", serving.id());
    Acting.as("ada", () -> new SpecStore(serving.db()).create(spec("d1", "delta")));
    Acting.as("ada", () -> new SpecStore(serving.db()).create(spec("d2", "delta")));
    round();
    serving = copy;
    for (var id : List.of("visible-1", "visible-2", "visible-3")) {
      mainWrite(serving, id);
    }

    round();

    assertEquals(
        List.of("visible-1", "visible-2", "visible-3"),
        specIds(nodeDb).stream().map(r -> r.split(" ")[0]).toList());
  }

  @Test
  void aRoundCutShortBetweenAPagesAdoptionsAndItsCheckpointStillSeesTheRestoreThatLostThem()
      throws Exception {
    mainWrite(serving, "seed");
    round();
    var copy = copyOf(serving, "copy.db", serving.id());
    mainWrite(serving, "lost-1");
    mainWrite(serving, "lost-2");
    nodeDb.execute(
        "CREATE TRIGGER halt BEFORE UPDATE OF checkpoint ON sync_state"
            + " WHEN NEW.entity_type = 'spec' AND NEW.checkpoint > OLD.checkpoint"
            + " BEGIN SELECT RAISE(ABORT, 'halted'); END");
    assertThrows(SyncTransportException.class, this::round);
    nodeDb.execute("DROP TRIGGER halt");
    assertEquals(3, specIds(nodeDb).size(), "the page was adopted, its checkpoint never moved");
    serving = copy;
    mainWrite(serving, "new-1");

    round();

    assertEquals(specIds(serving.db()), specIds(nodeDb), "the node holds what main holds");
  }

  @Test
  void anErasureOfTheSpecAtTheNodesCheckpointIsAnOrdinaryRound() throws Exception {
    mainWrite(serving, "g0");
    mainWrite(serving, "g1");
    round();
    var erasure = new Erasure(serving.db());
    Acting.system(
        () -> erasure.erase(erasure.closure(List.of(new Erasure.Target("spec", "g1"))), "local"));

    var round = round();

    assertEquals(List.of(), notices);
    assertEquals(specIds(serving.db()), specIds(nodeDb));
    assertEquals(
        List.of(1),
        round.types().stream().filter(t -> "spec".equals(t.type())).map(t -> t.entries()).toList(),
        "the erasure alone is paged, not every head");
  }

  private static List<String> projectRows(Sqlite db) {
    return db.query(
        "SELECT name || ' ' || rev || ' ' || created_by || ' ' || created_at || ' ' || updated_by"
            + " || ' ' || updated_at || ' ' || definition FROM projects ORDER BY name",
        r -> r.text(0));
  }

  @Test
  void aMainBuiltFromACopyOnANewBoxTakesTheSpecsItLostFromTheNode() throws Exception {
    serving = pulledAfterACopy("copy.db", "main-restored", "r-late-1", "r-late-2");

    round();

    assertEquals(
        List.of(
            "main no longer holds 2 spec(s) this box had pulled (r-late-1, r-late-2"
                + RESTORED
                + "Each stays in its history: sudo sail spec history <id>."),
        notices);
    assertEquals(specIds(serving.db()), specIds(nodeDb));
    assertEquals("  ✓ Already in sync with main.", rendered(round()));
  }

  @Test
  void aRoundThatOnlyWithdrawsIsNotAlreadyInSync() throws Exception {
    round();
    var copy = copyOf(serving, "copy.db", serving.id());
    mainWrite(serving, "r-late-1");
    round();
    serving = copy;

    var round = round();

    assertEquals(
        "  ✓ Synced with main: 1 pulled, 0 pushed, 0 merged.\n"
            + "    spec: 1 pulled, 0 pushed, 0 merged (0 page(s), 0 entries)",
        rendered(round),
        "the withdrawal is what the round did, counted as a pull");
    assertFalse(
        round.types().stream()
            .filter(t -> "spec".equals(t.type()))
            .findFirst()
            .orElseThrow()
            .skipped(),
        "the type moved, in its JSON view too");
    assertEquals(List.of(), specIds(nodeDb));
    assertEquals("  ✓ Already in sync with main.", rendered(round()));
  }

  @Test
  void aNoticeNamesFiveLostRowsAndCountsTheRest() throws Exception {
    serving = pulledAfterACopy("copy.db", serving.id(), "l1", "l2", "l3", "l4", "l5", "l6", "l7");

    round();

    assertEquals(
        List.of(
            "main no longer holds 7 spec(s) this box had pulled (l1, l2, l3, l4, l5 and 2 more"
                + RESTORED
                + "Each stays in its history: sudo sail spec history <id>."),
        notices);
  }

  @Test
  void aLostSpecTheNodeChangedIsRefusedNeverPushedAndKeptInItsHistory() throws Exception {
    serving = pulledAfterACopy("copy.db", serving.id(), "r-late-1", "r-late-2");
    Acting.as("nia", () -> new SpecStore(nodeDb).updateStatus("r-late-1", SpecStatus.IN_PROGRESS));

    var round = round();

    assertEquals(
        List.of(
            "main no longer holds r-late-1: it was restored from an older copy. Yours is in its"
                + " history: sudo sail spec history r-late-1.",
            "main no longer holds 1 spec(s) this box had pulled (r-late-2"
                + RESTORED
                + "Each stays in its history: sudo sail spec history <id>."),
        notices);
    assertEquals(
        List.of("main no longer holds r-late-1: it was restored from an older copy"),
        round.denials().stream().map(SyncSession.Denial::reason).toList());
    assertEquals(List.of("g0"), serving.db().query("SELECT id FROM specs", r -> r.text(0)));
    assertEquals(specIds(serving.db()), specIds(nodeDb));
    assertEquals(
        "nia",
        new SpecStore(nodeDb)
            .history("r-late-1").stream()
                .map(entry -> entry.actor())
                .filter("nia"::equals)
                .findFirst()
                .orElseThrow(),
        "the node's own change stays in the spec's history");
  }

  @Test
  void aLostRoomMessageAndProjectEachLeaveTheNodeNamedOncePerType() throws Exception {
    mainWrite(serving, "g0");
    round();
    var copy = copyOf(serving, "copy.db", serving.id());
    Acting.system(
        () -> {
          new ProjectStore(serving.db()).upsert("delta", "name: delta\n");
          new RoomStore(serving.db()).ensureFor("r-room", "gamma", "late", null, null);
        });
    var message =
        Acting.as("nia", () -> new MessageStore(serving.db()).append("g0", "nia", "late", null));
    round();
    serving = copy;

    round();

    assertEquals(
        List.of(
            "main no longer holds 1 room(s) this box had pulled (r-room"
                + RESTORED
                + "Each stays in this box's change log.",
            "main no longer holds 1 project(s) this box had pulled (delta"
                + RESTORED
                + "Each stays in this box's change log.",
            "main no longer holds 1 message(s) this box had pulled ("
                + message.id()
                + RESTORED
                + "Each stays in this box's change log."),
        notices);
    assertEquals(
        held(serving.db()).stream().filter(row -> row.startsWith("project")).toList(),
        held(nodeDb).stream().filter(row -> row.startsWith("project")).toList());
    assertEquals(
        List.of(), nodeDb.query("SELECT id FROM rooms WHERE id = 'r-room'", r -> r.text(0)));
    assertEquals(List.of(), new MessageStore(nodeDb).findById(message.id()).stream().toList());
  }

  @Test
  void aLostReplyIsWithdrawnWithItsThreadNotRefused() throws Exception {
    mainWrite(serving, "g0");
    round();
    var copy = copyOf(serving, "copy.db", serving.id());
    var messages = new MessageStore(serving.db());
    var parent = Acting.as("nia", () -> messages.append("g0", "nia", "late", null));
    var reply = Acting.as("nia", () -> messages.append("g0", "nia", "reply", parent.id()));
    round();
    serving = copy;

    var round = round();

    assertEquals(List.of(), round.denials());
    assertEquals(
        List.of(
            "main no longer holds 2 message(s) this box had pulled ("
                + String.join(", ", List.of(parent.id(), reply.id()).stream().sorted().toList())
                + RESTORED
                + "Each stays in this box's change log."),
        notices);
  }

  @Test
  void aLostSpecTheNodeDeletedIsRefusedWithTheRestoreSentence() throws Exception {
    serving = pulledAfterACopy("copy.db", serving.id(), "r-late-1");
    Acting.as("nia", () -> new SpecStore(nodeDb).delete("r-late-1"));

    var round = round();

    assertEquals(
        List.of("main no longer holds r-late-1: it was restored from an older copy"),
        round.denials().stream().map(SyncSession.Denial::reason).toList());
    assertEquals(List.of("g0"), serving.db().query("SELECT id FROM specs", r -> r.text(0)));
    assertEquals(specIds(serving.db()), specIds(nodeDb));
    assertEquals("  ✓ Already in sync with main.", rendered(round()));
  }

  @Test
  void anInterruptedWithdrawalIsFinishedByTheNextRoundAndEveryRowIsNamedOnce() throws Exception {
    serving = pulledAfterACopy("copy.db", serving.id(), "r-late-1", "r-late-2");
    nodeDb.execute(
        "CREATE TRIGGER halt BEFORE DELETE ON specs WHEN (SELECT count(*) FROM specs) = 2"
            + " BEGIN SELECT RAISE(ABORT, 'halted'); END");
    assertThrows(SyncTransportException.class, this::round);
    var cutShort = List.copyOf(notices);
    assertEquals(1, cutShort.size(), "the row withdrawn before the halt is named: " + cutShort);
    nodeDb.execute("DROP TRIGGER halt");

    round();

    assertEquals(specIds(serving.db()), specIds(nodeDb), "the node holds what main holds");
    var named = new ArrayList<>(cutShort);
    named.addAll(notices);
    assertEquals(
        2,
        named.stream()
            .mapToInt(n -> (n.contains("r-late-1") ? 1 : 0) + (n.contains("r-late-2") ? 1 : 0))
            .sum(),
        "each row is named once, by the round that withdrew it: " + named);
    assertEquals("  ✓ Already in sync with main.", rendered(round()));
  }

  @Test
  void aLostRunStillUnderWayHereIsLeftThisRoundAndWithdrawnOnceItEnds() throws Exception {
    round();
    write("n1");
    var ended = reserveRun(nodeDb, "n1", "nia");
    Acting.system(() -> new RunStore(nodeDb).complete(ended, "completed", 0));
    round();
    var copy = copyOf(serving, "copy.db", serving.id());
    Acting.as("ada", () -> new SpecStore(serving.db()).create(spec("g1")));
    var mains = reserveRun(serving.db(), "g1", "ada");
    var run = reserveRun(nodeDb, "n1", "nia");
    round();
    assertEquals(List.of(ended, mains, run), runIds(serving.db()), "main holds all three");
    assertEquals(List.of(ended, mains, run), runIds(nodeDb));
    serving = copy;

    round();

    assertEquals(
        List.of(
            "main no longer holds 1 run(s) this box had pulled ("
                + mains
                + RESTORED
                + "Each stays in this box's change log.",
            "main no longer holds 1 spec(s) this box had pulled (g1"
                + RESTORED
                + "Each stays in its history: sudo sail spec history <id>."),
        notices);
    assertEquals(List.of(ended, run), runIds(nodeDb), "work under way here is left alone");
    Acting.system(() -> new RunStore(nodeDb).complete(run, "completed", 0));

    var next = round();

    assertEquals(
        List.of("main no longer holds " + run + ": it was restored from an older copy"),
        next.denials().stream().map(SyncSession.Denial::reason).toList());
    assertEquals(List.of(ended), runIds(nodeDb), "withdrawn once the work ended");
    assertEquals(List.of(ended), runIds(serving.db()), "never pushed to rebuild main");
    assertEquals("  ✓ Already in sync with main.", rendered(round()));
  }

  private static String reserveRun(Sqlite db, String spec, String handle) {
    var id = DateTimeUtils.newId().toString();
    Acting.system(
        () ->
            new RunStore(db)
                .reserveDispatch(
                    id,
                    "gamma",
                    spec,
                    handle,
                    "adhoc",
                    List.of(),
                    "claude-code",
                    "b",
                    "t",
                    "/l",
                    "u"));
    return id;
  }

  private static List<String> runIds(Sqlite db) {
    return db.query("SELECT id FROM runs ORDER BY id", r -> r.text(0));
  }

  @Test
  void aRowThisBoxTookFromANodesPushIsNeverOneItHeardFromMain() throws Exception {
    var elsewhere = main("o.db", "o", "gamma");
    mainWrite(elsewhere, "pushed");
    var pushed = new SpecStore(elsewhere.db());
    var snapshot = pushed.comparableSnapshot("pushed");
    var rev = pushed.latestRev("pushed");
    serving = pulledAfterACopy("copy.db", serving.id(), "r-late-1");
    Acting.by(
        Actor.sync("mady", Role.MEMBER),
        () -> new SpecStore(nodeDb).applyRevision("pushed", snapshot, rev));

    round();

    assertEquals(
        List.of(
            "main no longer holds 1 spec(s) this box had pulled (r-late-1"
                + RESTORED
                + "Each stays in its history: sudo sail spec history <id>."),
        notices);
    assertEquals(
        List.of("g0", "pushed"), specIds(nodeDb).stream().map(r -> r.split(" ")[0]).toList());
  }

  @Test
  void anOrdinaryRoundDropsNothingAndSaysNothing() throws Exception {
    mainWrite(serving, "g0");
    round();
    write("n1");
    mainWrite(serving, "g1");

    var round = round();

    assertEquals(List.of(), notices);
    assertEquals(
        List.of("g0", "g1", "n1"), specIds(nodeDb).stream().map(r -> r.split(" ")[0]).toList());
    assertEquals(
        List.of(1),
        round.types().stream().filter(t -> "spec".equals(t.type())).map(t -> t.entries()).toList(),
        "pulled from the checkpoint, not from the first head");
  }

  @Test
  void aNodesNewWorkStillReachesAMainRestoredFromACopy() throws Exception {
    serving = pulledAfterACopy("copy.db", "main-restored", "r-late-1");
    write("n-unpushed");

    var round = round();

    assertEquals(1, round.report().pushed());
    assertEquals(
        List.of("g0", "n-unpushed"),
        serving.db().query("SELECT id FROM specs ORDER BY id", r -> r.text(0)));
    assertEquals(specIds(serving.db()), specIds(nodeDb));
  }

  @Test
  void aScopedNodeWeighsOnlyTheRowsOfItsScope() throws Exception {
    var fdes = new FdeStore(serving.db());
    fdes.restrict("nia");
    fdes.grant("nia", "gamma");
    Acting.system(() -> new ProjectStore(serving.db()).upsert("delta", "name: delta\n"));
    Acting.as("ada", () -> new SpecStore(serving.db()).create(spec("d1", "delta")));
    serving = pulledAfterACopy("copy.db", serving.id(), "r-late-1");

    round();

    assertEquals(
        List.of(
            "main no longer holds 1 spec(s) this box had pulled (r-late-1"
                + RESTORED
                + "Each stays in its history: sudo sail spec history <id>."),
        notices);
    assertEquals(
        specIds(serving.db()).stream().filter(row -> !row.startsWith("d")).toList(),
        specIds(nodeDb));
  }

  @Test
  void aBoxMeetingADatabaseForTheFirstTimeHasHeardNothingFromItAndWithdrawsNothing()
      throws Exception {
    mainWrite(serving, "g0");
    round();
    nodeDb.execute("UPDATE catalog SET main = NULL");
    serving = main("b.db", "main-b", "gamma");

    round();

    assertEquals(List.of(), notices);
    assertEquals(
        List.of("g0"),
        nodeDb.query("SELECT id FROM specs", r -> r.text(0)),
        "heard from another main, so not this one's to take back");
    assertEquals(
        new DatabaseCatalog(serving.db()).id(), new DatabaseCatalog(nodeDb).main().orElseThrow());
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
            SyncWire.PROTOCOL, "0.54.0", "main", "nia", 4096, ProjectScope.all(), "another");
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
    var uncatalogued =
        new SyncWire.Welcome(
            SyncWire.PROTOCOL, "0.53.0", "main", "nia", 4096, ProjectScope.all(), null);
    answering =
        (in, out) -> {
          SyncWire.readFramed(in);
          in.close();
          out.write((SyncWire.encode(uncatalogued) + "\n").getBytes(StandardCharsets.UTF_8));
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
    return spec(id, "gamma");
  }

  private static SpecStore.SpecRow spec(String id, String project) {
    return new SpecStore.SpecRow(
        id,
        project,
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
