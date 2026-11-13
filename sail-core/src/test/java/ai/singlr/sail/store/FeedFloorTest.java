/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.sail.config.SpecStatus;
import ai.singlr.sail.identity.Acting;
import ai.singlr.sail.identity.ActingAs;
import ai.singlr.sail.identity.Actor;
import ai.singlr.sail.identity.ProjectScope;
import ai.singlr.sail.sync.SyncedEntities;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The change log as a feed a client resumes from: the newest position is the largest seq ever
 * assigned, the heads after a position are read in a reader's scope through an index, and a floor
 * rises for exactly the readers whose view a write changed with no entry they are told of to say
 * so: a row moved between projects, a person's grants or restrictions, a removal, history rewritten
 * under erasure.
 */
@ActingAs(value = Actor.Lane.CLI, handle = "uday")
class FeedFloorTest {

  private static final ProjectScope ACME = ProjectScope.of(List.of("acme"));
  private static final ProjectScope BETA = ProjectScope.of(List.of("beta"));
  private static final ProjectScope GAMMA = ProjectScope.of(List.of("gamma"));

  private Sqlite db;
  private ChangeLog log;
  private FeedFloors floors;
  private SpecStore specs;
  private RoomStore rooms;
  private MessageStore messages;
  private FdeStore fdes;

  @BeforeEach
  void setUp() {
    db = Sqlite.openMemory();
    new SchemaManager(db).migrate();
    HeldProjects.hold(db, "acme", "beta", "gamma");
    log = new ChangeLog(db);
    floors = new FeedFloors(db);
    specs = new SpecStore(db);
    rooms = new RoomStore(db);
    messages = new MessageStore(db);
    fdes = new FdeStore(db);
    fdes.add("vik", null, null, "viewer");
  }

  @AfterEach
  void tearDown() {
    db.close();
  }

  @Test
  void theNewestIsTheLargestSeqEverAssignedAndEachFloorKeepsOneEntry() {
    assertEquals(0, log.newest());
    assertEquals(0, floors.floor(null, ProjectScope.all()));
    spec("s", "acme");
    var written = log.newest();

    floors.raiseForEveryone();
    floors.raiseForEveryone();

    assertEquals(written + 2, log.newest());
    assertEquals(log.newest(), floors.floor(null, ProjectScope.all()));
    assertEquals(log.newest(), floors.floor("vik", ACME), "the box's floor is every reader's");
    assertEquals(1L, floorEntries());
  }

  @Test
  void historyRewrittenWithNoNewerEntryRaisesTheBoxsFloor() {
    spec("purged", "acme");
    spec("forgotten", "acme");

    log.purge("spec", "purged");
    var purged = floors.floor(null, ProjectScope.all());
    log.forget("spec", "forgotten");

    assertTrue(purged > 0);
    assertTrue(floors.floor(null, ProjectScope.all()) > purged);
  }

  @Test
  void anErasureOrACompactionRaisesNoFloor() {
    spec("erased", "acme");
    spec("kept", "acme");
    for (var i = 0; i < ChangeLog.HISTORY_REVISIONS + 2; i++) {
      specs.update(row("kept", "acme", "kept " + i));
    }

    new Erasure(db).erase(List.of(new Erasure.Target("spec", "erased")), ChangeLog.Entry.LOCAL);
    log.compact("spec", List.of("kept"), id -> null);

    assertEquals(0L, floorEntries(), "every reader of acme is told the erasure; nothing moved");
  }

  @Test
  void aSpecMovedBetweenProjectsRaisesTheFloorsOfBothAndOfNoOtherReader() {
    spec("moved", "acme");
    var before = log.newest();

    specs.update(row("moved", "beta"));

    var left = floors.floor(null, ACME);
    var entered = floors.floor(null, BETA);
    assertTrue(left > before, "acme's readers must drop it");
    assertTrue(entered > before, "beta's must read what was said under it");
    assertEquals(0, floors.floor(null, GAMMA));
    assertEquals(0, floors.floor(null, ProjectScope.all()), "a reader of every project is told it");
    specs.update(row("moved", "beta", "edited where it is"));
    assertEquals(entered, floors.floor(null, BETA), "an edit in place moves nothing");
    assertEquals(left, floors.floor(null, ACME));
    assertEquals(2L, floorEntries(), "one entry per project floor");
  }

  @Test
  void aRoomMovedBySyncAndAHeadMainRefilesRaiseBothProjectsFloors() {
    room("hall", "acme");
    var before = log.newest();
    moveRoom("hall", "beta");
    var moved = floors.floor(null, BETA);
    var left = floors.floor(null, ACME);
    assertTrue(moved > before);
    assertTrue(left > before);

    var posted = messages.append("hall", "uday", "said in beta", null);
    log.fileUnder("message", posted.id(), "beta");
    assertEquals(moved, floors.floor(null, BETA), "filed again where it already was");
    log.fileUnder("message", posted.id(), null);
    assertEquals(moved, floors.floor(null, BETA), "main named no project");
    log.fileUnder("message", posted.id(), "gamma");

    assertTrue(floors.floor(null, GAMMA) > moved);
    assertTrue(floors.floor(null, BETA) > moved);
    assertEquals(left, floors.floor(null, ACME));
  }

  @Test
  void aReplyFiledApartFromItsMovedConversationRaisesNoFloorUntilItEnds() {
    room("hall", "acme");
    var asked = messages.append("hall", "uday", "a thread", null);
    moveRoom("hall", "beta");
    var moved = floors.floor(null, BETA);
    var left = floors.floor(null, ACME);

    var answered = messages.append("hall", "uday", "a late answer", asked.id());
    assertEquals("acme", log.headProject("message", answered.id()).orElseThrow());
    assertEquals(moved, floors.floor(null, BETA), "no reader of beta reads it: it is acme's");
    assertEquals(
        left, floors.floor(null, ACME), "acme's are told nothing of a row they cannot read");

    new Erasure(db).erase(List.of(new Erasure.Target("message", answered.id())), "local");

    assertTrue(
        floors.floor(null, ACME) > left, "acme's would be told the end of a row never theirs");
    assertEquals(moved, floors.floor(null, BETA));
  }

  @Test
  void erasingARoomsMessagesRaisesNoFloorForItsReadersAreToldEachErasure() {
    room("hall", "acme");
    var older = messages.append("hall", "uday", "older", null);
    var question = messages.append("hall", "uday", "shall I?", null, MessageStore.Mark.QUESTION);
    var latest = messages.append("hall", "uday", "latest", null);

    for (var id : List.of(older.id(), question.id(), latest.id())) {
      new Erasure(db).erase(List.of(new Erasure.Target("message", id)), "local");
    }

    assertEquals(0, floors.floor(null, ACME), "a reader derives the room's activity afresh");
    assertEquals(0L, floorEntries());
  }

  @Test
  void aChangeOfWhatAnFdeReadsRaisesThatFdesFloorAloneAndANoOpRaisesNone() {
    fdes.add("ada", null, null, "viewer");
    fdes.restrict("vik");
    var restricted = floors.floor("vik", ACME);
    fdes.restrict("vik");
    assertEquals(restricted, floors.floor("vik", ACME));

    fdes.grant("vik", "acme");
    var granted = floors.floor("vik", ACME);
    fdes.grant("vik", "acme");
    assertEquals(granted, floors.floor("vik", ACME));

    fdes.revoke("vik", "acme");
    var revoked = floors.floor("vik", ACME);
    fdes.revoke("vik", "acme");
    assertEquals(revoked, floors.floor("vik", ACME));

    fdes.unrestrict("vik");
    var unrestricted = floors.floor("vik", ProjectScope.all());
    fdes.unrestrict("vik");

    assertTrue(0 < restricted && restricted < granted && granted < revoked);
    assertTrue(revoked < unrestricted);
    assertEquals(unrestricted, floors.floor("vik", ProjectScope.all()));
    assertEquals(0, floors.floor("ada", ProjectScope.all()), "another FDE's position stands");
    assertEquals(0, floors.floor(null, ProjectScope.all()), "so does one that acts for no FDE");
  }

  @Test
  void aRemovedPersonAddedBackAndTheGranteesOfAnErasedProjectReadAfresh() {
    var ada = fdes.add("ada", null, null, "viewer");
    fdes.restrict("vik");
    fdes.grant("vik", "gamma");
    var granted = floors.floor("vik", GAMMA);

    fdes.remove(ada.id());
    var removed = floors.floor("ada", ProjectScope.all());
    fdes.add("ada", null, null, "member");
    assertTrue(removed > granted, "one added back under the handle may read otherwise");
    assertEquals(removed, floors.floor("ada", ProjectScope.all()));

    fdes.revokeFromEveryone("gamma");
    var erased = floors.floor("vik", ProjectScope.of(List.of()));
    assertTrue(erased > removed, "vik read gamma, and reads it no more");
    fdes.revokeFromEveryone("gamma");
    assertEquals(erased, floors.floor("vik", ProjectScope.of(List.of())), "no one held it still");
    assertEquals(removed, floors.floor("ada", ProjectScope.all()), "ada was granted none of it");
  }

  @Test
  void theFloorsEntriesAreNoEntityToAnyOtherReaderOfTheJournal() {
    fdes.restrict("vik");
    floors.raiseForEveryone();
    spec("moved", "acme");
    specs.update(row("moved", "beta"));
    var moved = floors.floor(null, ACME);

    for (var entity : SyncedEntities.all()) {
      var heads = log.headsAfter(entity.type(), 0, 100);
      assertTrue(heads.stream().allMatch(head -> head.seq() < moved), entity.type());
      assertNotEquals(FeedFloors.TYPE, entity.type());
      assertTrue(log.maxSeq(entity.type()) < moved, entity.type());
      assertEquals(Set.of(), log.overflowing(entity.type(), null), entity.type());
    }
    assertEquals(List.of("beta"), log.heldProjects());
    assertEquals(
        List.of("moved"),
        log.changesAfter(List.of("spec", "room", "message"), 0, 10, ProjectScope.all()).stream()
            .map(ChangeLog.Change::entityId)
            .toList());
    assertTrue(new Erasure(db).closure(List.of(new Erasure.Target("project", "acme"))).size() == 1);
  }

  @Test
  void everyReadOfTheChangeLogOrItsHeadsNamesTheTypesItReads() throws IOException {
    assertFalse(
        namesItsTypes("db.query(\"SELECT seq FROM change_log ORDER BY seq\");"),
        "the sweep catches a read that names no type");
    var unnamed = new ArrayList<String>();
    for (var root : List.of("src/main/java", "../sail-infra/src/main/java")) {
      try (Stream<Path> sources = Files.walk(Path.of(root))) {
        for (var source : sources.filter(path -> path.toString().endsWith(".java")).toList()) {
          var text = Files.readString(source);
          var reads = READS.matcher(text);
          while (reads.find()) {
            for (var statement : statements(text, reads.start())) {
              if (!namesItsTypes(statement)) {
                unnamed.add(source.getFileName() + ": " + statement.strip());
              }
            }
          }
        }
      }
    }
    assertEquals(List.of(), unnamed, "a read that names no type would read the floor's entries");
  }

  @Test
  void aPageReadsEachRangeThroughAnIndexInSeqOrderWhateverTheHistory() {
    bulkHeads(200_000);
    var scoped = log.planOf(List.of("spec", "room", "message"), 199_000, 101, ACME);
    var all = log.planOf(List.of("spec", "room", "message"), 199_000, 101, ProjectScope.all());

    for (var plan : List.of(scoped, all)) {
      var reads =
          plan.stream().filter(step -> step.contains(" h ") || step.endsWith(" h")).toList();
      assertEquals(3, reads.size(), "one range per type: " + String.join("\n", plan));
      for (var read : reads) {
        assertTrue(read.startsWith("SEARCH h USING"), read);
      }
      assertTrue(
          plan.stream().noneMatch(step -> step.startsWith("SCAN h")), String.join("\n", plan));
    }
    assertTrue(scoped.stream().anyMatch(step -> step.contains("idx_change_heads_project")));
    assertTrue(all.stream().anyMatch(step -> step.contains("idx_change_heads_seq")));
    System.out.println("EXPLAIN QUERY PLAN, scoped:\n  " + String.join("\n  ", scoped));
    System.out.println("EXPLAIN QUERY PLAN, every project:\n  " + String.join("\n  ", all));

    var hidden = log.changesAfter(List.of("message"), 0, 100, GAMMA);
    assertEquals(List.of(), hidden, "nothing of gamma, however much of acme lies after 0");
    assertEquals(
        100, log.changesAfter(List.of("message"), 100_000, 100, ProjectScope.all()).size());
  }

  @Test
  void aWatcherIsWokenOnceForEachCommitThatAppendedAndNeverForOneRolledBack() throws Exception {
    var woken = new AtomicInteger();
    var watching = log.watch(woken::incrementAndGet);

    db.transaction(
        () -> {
          spec("one", "acme");
          spec("two", "acme");
          assertEquals(0, woken.get(), "not before the commit");
        });
    assertEquals(1, woken.get());
    assertThrows(
        IllegalStateException.class,
        () ->
            db.transaction(
                () -> {
                  spec("three", "acme");
                  throw new IllegalStateException("rolled back");
                }));
    assertEquals(1, woken.get());
    spec("four", "acme");
    assertEquals(2, woken.get());

    watching.close();
    spec("five", "acme");
    assertEquals(2, woken.get());
  }

  @Test
  void aScopedReaderIsToldOnlyOfHeadsFiledInItsScopeInSeqOrder() {
    spec("acme-only", "acme");
    spec("beta-only", "beta");
    spec("moved", "acme");
    specs.update(row("moved", "beta"));
    spec("acme-later", "acme");

    assertEquals(
        List.of("acme-only", "acme-later"), ids(log.changesAfter(List.of("spec"), 0, 10, ACME)));
    assertEquals(
        List.of("acme-only", "beta-only", "moved", "acme-later"),
        ids(log.changesAfter(List.of("spec"), 0, 10, ProjectScope.of(List.of("acme", "beta")))));
    assertEquals(4, log.changesAfter(List.of("spec"), 0, 10, ProjectScope.all()).size());
    assertEquals(1, log.changesAfter(List.of("spec"), 0, 1, ProjectScope.all()).size());
    assertEquals(List.of(), log.changesAfter(List.of("room"), 0, 10, ProjectScope.all()));
    assertEquals(List.of(), log.changesAfter(List.of("spec"), 0, 10, ProjectScope.of(List.of())));
  }

  private static final Pattern READS =
      Pattern.compile("(FROM|JOIN)\\s+(change_log|change_heads)\\b", Pattern.CASE_INSENSITIVE);

  private static final Pattern CONSTANT =
      Pattern.compile("static final String (\\w+) =[^;]*$", Pattern.DOTALL);

  /**
   * The statements that run the SQL read at {@code from}: the Java statement that holds it, or,
   * when that declares a constant the SQL begins and names no type itself, each statement that uses
   * the constant.
   */
  private static List<String> statements(String text, int from) {
    var held = text.substring(from, statementEnd(text, from));
    var declared = CONSTANT.matcher(text.substring(text.lastIndexOf(';', from) + 1, from));
    if (!declared.find() || namesItsTypes(held)) {
      return List.of(held);
    }
    var uses = new ArrayList<String>();
    var name = Pattern.compile("\\b" + declared.group(1) + "\\b(?=\\s*[+,)])").matcher(text);
    while (name.find()) {
      uses.add(text.substring(name.start(), statementEnd(text, name.start())));
    }
    return uses;
  }

  /** The end of the Java statement that holds the text starting at {@code from}. */
  private static int statementEnd(String text, int from) {
    var end = text.indexOf(';', from);
    return end < 0 ? text.length() : end;
  }

  /**
   * Whether a statement reading the log names the types it reads: by {@code entity_type}, or by a
   * project it names, under which no floor is ever filed.
   */
  private static boolean namesItsTypes(String statement) {
    return statement.contains("entity_type")
        || statement.contains("project = ?")
        || statement.contains("project IS NOT NULL");
  }

  private long floorEntries() {
    return db.queryOne(
            "SELECT count(*) FROM change_log WHERE entity_type = ?",
            row -> row.integer(0),
            FeedFloors.TYPE)
        .orElseThrow();
  }

  /** {@code count} heads of messages filed under acme, written as one insert per table. */
  private void bulkHeads(int count) {
    db.transaction(
        () -> {
          db.execute(
              """
              WITH RECURSIVE n(i) AS (SELECT 1 UNION ALL SELECT i + 1 FROM n WHERE i < ?)
              INSERT INTO change_log (entity_type, entity_id, rev, recorded_at, origin, snapshot)
              SELECT 'message', 'bulk-' || i, '1-bulk', 0, 'local', '{}' FROM n""",
              count);
          db.execute(
              """
              INSERT INTO change_heads (entity_type, entity_id, seq, project)
              SELECT entity_type, entity_id, seq, 'acme' FROM change_log
              WHERE entity_type = 'message' AND entity_id LIKE 'bulk-%'""");
        });
    db.execute("ANALYZE");
  }

  private static List<String> ids(List<ChangeLog.Change> changes) {
    return changes.stream().map(ChangeLog.Change::entityId).toList();
  }

  private void moveRoom(String id, String project) {
    Acting.system(
        () -> {
          var moved = new LinkedHashMap<String, Object>(rooms.currentForSync(id));
          moved.put("project", project);
          rooms.applyRevision(id, moved, "9-moved-to-" + project);
        });
  }

  private void room(String id, String project) {
    rooms.create(
        new RoomStore.RoomRow(id, project, id, "uday", null, null, "uday", null, null, "uday"));
  }

  private void spec(String id, String project) {
    specs.create(row(id, project));
  }

  private static SpecStore.SpecRow row(String id, String project) {
    return row(id, project, id);
  }

  private static SpecStore.SpecRow row(String id, String project, String title) {
    return new SpecStore.SpecRow(
        id,
        project,
        title,
        SpecStatus.DRAFT,
        null,
        null,
        null,
        null,
        null,
        0,
        "uday",
        null,
        null,
        "uday",
        List.of(),
        List.of());
  }
}
