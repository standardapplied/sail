/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.sync;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.sail.common.DateTimeUtils;
import ai.singlr.sail.config.SpecStatus;
import ai.singlr.sail.config.YamlUtil;
import ai.singlr.sail.identity.Acting;
import ai.singlr.sail.identity.Actor;
import ai.singlr.sail.store.ChangeLog;
import ai.singlr.sail.store.EraseRequests;
import ai.singlr.sail.store.Erasure;
import ai.singlr.sail.store.FdeStore;
import ai.singlr.sail.store.FileStore;
import ai.singlr.sail.store.MessageStore;
import ai.singlr.sail.store.ProjectStore;
import ai.singlr.sail.store.Revisions;
import ai.singlr.sail.store.RoomStore;
import ai.singlr.sail.store.RunStore;
import ai.singlr.sail.store.SpecStore;
import ai.singlr.sail.store.SyncConflicts;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * C1, T1 and T2 over real sessions, main plus two nodes: once every box has had a round with no new
 * writes, each holds exactly main's version of every synced entity — fields, author, creator,
 * revision, tombstone and erasure. A deletion a node adopted is main's, never one of its own to
 * offer again; a resolve that takes main's side holds main's revision under main's author; a room
 * adopts every synced field main holds, and one re-created over its own tombstone descends from it.
 */
class ConvergenceSyncTest {

  @TempDir Path dir;
  private SyncBox main;
  private SyncBox ada;
  private SyncBox bob;

  @BeforeEach
  void setUp() {
    main = new SyncBox(dir, "main");
    ada = new SyncBox(dir, "ada");
    bob = new SyncBox(dir, "bob");
    Acting.as(
        "root",
        () -> {
          new FdeStore(main.db).add("ada", null, null, "member");
          new FdeStore(main.db).add("bob", null, null, "member");
        });
  }

  @AfterEach
  void tearDown() {
    bob.close();
    ada.close();
    main.close();
  }

  /** A synced type an FDE deletes and brings back, as the convergence cases drive it. */
  enum Kind {
    SPEC("spec", "s", "one", "two", "three") {
      @Override
      void make(SyncBox box, String as, String value) {
        ownSpec(box, as, "s", "bob");
        change(box, as, value);
      }

      @Override
      void change(SyncBox box, String as, String value) {
        retitle(box, as, "s", value);
      }

      @Override
      void delete(SyncBox box, String as) {
        Acting.as(as, () -> box.specs.delete("s"));
      }

      @Override
      void restore(SyncBox box, String as, String value) {
        var rev =
            box.specs.history("s").reversed().stream()
                .filter(entry -> !entry.deleted())
                .filter(entry -> value.equals(YamlUtil.parseMap(entry.snapshot()).get("title")))
                .findFirst()
                .orElseThrow()
                .rev();
        Acting.as(as, () -> box.specs.restore("s", rev));
      }

      @Override
      String value(SyncBox box) {
        return box.specs.findById("s").map(SpecStore.SpecRow::title).orElse(null);
      }
    },
    ROOM("room", "den", "on", "mention", "off") {
      @Override
      void make(SyncBox box, String as, String value) {
        Acting.as(as, () -> new RoomStore(box.db).create(room("den", "bob")));
        change(box, as, value);
      }

      @Override
      void change(SyncBox box, String as, String value) {
        Acting.as(as, () -> new RoomStore(box.db).updateWake("den", value));
      }

      @Override
      void delete(SyncBox box, String as) {
        Acting.as(as, () -> new RoomStore(box.db).delete("den"));
      }

      @Override
      void restore(SyncBox box, String as, String value) {
        Acting.as(as, () -> new RoomStore(box.db).restoreDeleted("den"));
        if (!value.equals(value(box))) {
          change(box, as, value);
        }
      }

      @Override
      String value(SyncBox box) {
        return new RoomStore(box.db).findById("den").map(RoomStore.RoomRow::wake).orElse(null);
      }
    },
    FILE("file", "acme/a.txt", "one", "two", "three") {
      @Override
      void make(SyncBox box, String as, String value) {
        change(box, as, value);
      }

      @Override
      void change(SyncBox box, String as, String value) {
        putFile(box, as, "a.txt", value);
      }

      @Override
      void delete(SyncBox box, String as) {
        Acting.as(as, () -> new FileStore(box.db).delete("acme", "a.txt"));
      }

      @Override
      void restore(SyncBox box, String as, String value) {
        change(box, as, value);
      }

      @Override
      String value(SyncBox box) {
        var files = new FileStore(box.db);
        return files
            .find("acme", "a.txt")
            .map(row -> files.blobs().text(row.contentHash()))
            .orElse(null);
      }
    },
    PROJECT("project", "acme", "one", "two", "three") {
      @Override
      void make(SyncBox box, String as, String value) {
        change(box, as, value);
      }

      @Override
      void change(SyncBox box, String as, String value) {
        Acting.as(
            as,
            () -> new ProjectStore(box.db).upsert("acme", "name: acme\nimage: " + value + "\n"));
      }

      @Override
      void delete(SyncBox box, String as) {
        Acting.as(as, () -> new ProjectStore(box.db).delete("acme"));
      }

      @Override
      void restore(SyncBox box, String as, String value) {
        change(box, as, value);
      }

      @Override
      String value(SyncBox box) {
        return new ProjectStore(box.db)
            .findByName("acme")
            .map(row -> YamlUtil.parseMap(row.definition()).get("image").toString())
            .orElse(null);
      }
    };

    final String type;
    final String id;
    final String first;
    final String second;
    final String third;

    Kind(String type, String id, String first, String second, String third) {
      this.type = type;
      this.id = id;
      this.first = first;
      this.second = second;
      this.third = third;
    }

    /** Makes the entity on {@code box} as {@code as}, bob's to work, holding {@code value}. */
    abstract void make(SyncBox box, String as, String value);

    abstract void change(SyncBox box, String as, String value);

    abstract void delete(SyncBox box, String as);

    /** Brings the deleted entity back on {@code box}, holding {@code value}. */
    abstract void restore(SyncBox box, String as, String value);

    /** What the entity holds on {@code box}; null when it holds none. */
    abstract String value(SyncBox box);
  }

  @ParameterizedTest
  @EnumSource(Kind.class)
  void aDeletionTheNodesAdoptedStaysRestoredWhenMainRestoresIt(Kind kind) {
    kind.make(main, "bob", kind.first);
    SyncBox.quiesce(main, ada, bob);
    kind.delete(main, "root");
    SyncBox.quiesce(main, ada, bob);

    kind.restore(main, "root", kind.first);
    var adas = SyncBox.round(main, ada);
    var bobs = SyncBox.round(main, bob);

    assertEquals(kind.first, kind.value(main), "main's restore was undone");
    assertNoDenials(adas);
    assertNoDenials(bobs);
    assertEquals(List.of(), ada.conflicts.pending());
    assertEquals(List.of(), bob.conflicts.pending());
    assertConverged();
    assertEveryBoxHolds(kind, kind.first);
  }

  @ParameterizedTest
  @EnumSource(Kind.class)
  void aDeletionANodeAdoptedBeforeThisReleaseStaysRestoredWhenMainRestoresIt(Kind kind) {
    kind.make(main, "bob", kind.first);
    SyncBox.quiesce(main, ada, bob);
    var synced = new ChangeLog(bob.db).head(kind.type, kind.id).orElseThrow().rev();
    kind.delete(main, "root");
    SyncBox.quiesce(main, ada, bob);
    bob.db.execute(
        """
        UPDATE change_log SET snapshot = json_set(snapshot, '$._base_rev', ?)
        WHERE seq = (SELECT seq FROM change_heads WHERE entity_type = ? AND entity_id = ?)""",
        synced,
        kind.type,
        kind.id);

    kind.restore(main, "root", kind.first);
    var bobs = SyncBox.round(main, bob);

    assertEquals(kind.first, kind.value(main), "main's restore was undone");
    assertNoDenials(bobs);
    assertEquals(List.of(), bob.conflicts.pending());
    assertConverged();
    assertEveryBoxHolds(kind, kind.first);
  }

  @ParameterizedTest
  @EnumSource(Kind.class)
  void aDeletionTheNodesAdoptedStaysRestoredWhenTheOtherNodeRestoresIt(Kind kind) {
    kind.make(main, "bob", kind.first);
    SyncBox.quiesce(main, ada, bob);
    kind.delete(main, "root");
    SyncBox.quiesce(main, ada, bob);

    kind.restore(bob, "bob", kind.first);
    var bobs = SyncBox.round(main, bob);
    var adas = SyncBox.round(main, ada);

    assertEquals(kind.first, kind.value(main), "bob's restore was undone");
    assertNoDenials(bobs);
    assertNoDenials(adas);
    assertEquals(List.of(), ada.conflicts.pending());
    assertConverged();
    assertEveryBoxHolds(kind, kind.first);
  }

  @ParameterizedTest
  @EnumSource(Kind.class)
  void aDeletionTheNodesAdoptedRestoredToAnOlderRevisionParksNoConflict(Kind kind) {
    kind.make(main, "bob", kind.first);
    kind.change(main, "root", kind.second);
    SyncBox.quiesce(main, ada, bob);
    kind.delete(main, "root");
    SyncBox.quiesce(main, ada, bob);

    kind.restore(main, "root", kind.first);
    SyncBox.round(main, ada);
    SyncBox.round(main, bob);

    assertEquals(List.of(), ada.conflicts.pending(), "ada");
    assertEquals(List.of(), bob.conflicts.pending(), "bob");
    assertConverged();
    assertEveryBoxHolds(kind, kind.first);
  }

  @ParameterizedTest
  @EnumSource(Kind.class)
  void aConflictResolvedTheirsHoldsMainsRevisionUnderMainsAuthor(Kind kind) {
    kind.make(main, "bob", kind.first);
    SyncBox.quiesce(main, ada, bob);
    kind.change(main, "root", kind.second);
    kind.change(bob, "bob", kind.third);
    SyncBox.round(main, bob);
    var mains = new ChangeLog(main.db).head(kind.type, kind.id).orElseThrow();
    assertParkedAgainst(mains, bob.conflicts.pendingFor(kind.type, kind.id).orElseThrow());

    resolve(bob, kind.type, kind.id, Resolve.THEIRS);

    var bobs = new ChangeLog(bob.db).head(kind.type, kind.id).orElseThrow();
    assertEquals(mains.rev(), bobs.rev());
    assertEquals("root", bobs.actor());
    assertConverged();
    assertEveryBoxHolds(kind, kind.second);
  }

  @ParameterizedTest
  @EnumSource(Kind.class)
  void aDeleteVersusEditResolvedTheirsHoldsMainsTombstoneUnderItsDeleter(Kind kind) {
    kind.make(main, "bob", kind.first);
    SyncBox.quiesce(main, ada, bob);
    kind.delete(main, "root");
    kind.change(bob, "bob", kind.second);
    SyncBox.round(main, bob);
    var mains = new ChangeLog(main.db).head(kind.type, kind.id).orElseThrow();
    assertParkedAgainst(mains, bob.conflicts.pendingFor(kind.type, kind.id).orElseThrow());

    resolve(bob, kind.type, kind.id, Resolve.THEIRS);

    var bobs = new ChangeLog(bob.db).head(kind.type, kind.id).orElseThrow();
    assertEquals(ChangeLog.Kind.TOMBSTONE, bobs.kind());
    assertEquals(mains.rev(), bobs.rev());
    assertEquals("root", bobs.actor(), "the deleter, not main");
    assertConverged();
    assertEveryBoxHolds(kind, null);
  }

  @ParameterizedTest
  @EnumSource(Kind.class)
  void aConflictResolvedMineIsOfferedOverMainsRevision(Kind kind) {
    kind.make(main, "bob", kind.first);
    SyncBox.quiesce(main, ada, bob);
    kind.change(main, "root", kind.second);
    kind.change(bob, "bob", kind.third);
    SyncBox.round(main, bob);

    resolve(bob, kind.type, kind.id, Resolve.MINE);

    assertConverged();
    assertEveryBoxHolds(kind, kind.third);
  }

  @Test
  void aNodeOfflineWhileARoomWasDeletedAndReCreatedAdoptsMainsCreatedAt() {
    Acting.as("bob", () -> new RoomStore(bob.db).create(room("den", "bob")));
    SyncBox.round(main, bob);
    SyncBox.round(main, ada);
    Acting.as("bob", () -> new RoomStore(bob.db).delete("den"));
    SyncBox.round(main, bob);
    Acting.as("bob", () -> new RoomStore(bob.db).create(room("den", "bob")));
    SyncBox.round(main, bob);

    SyncBox.round(main, ada);

    var mains = new RoomStore(main.db).findById("den").orElseThrow().createdAt();
    assertEquals(mains, new RoomStore(bob.db).findById("den").orElseThrow().createdAt());
    assertEquals(
        mains,
        new RoomStore(ada.db).findById("den").orElseThrow().createdAt(),
        "created_at is a synced field the node adopts from main");
    assertConverged();
  }

  @Test
  void aRoomDeletedAndReCreatedOfflineOnOneBoxContinuesItsCounterAndParksNoConflict() {
    Acting.as("bob", () -> new RoomStore(bob.db).create(room("den", "bob")));
    SyncBox.round(main, bob);
    Acting.as("bob", () -> new RoomStore(bob.db).delete("den"));
    Acting.as("bob", () -> new RoomStore(bob.db).create(room("den", "bob")));

    var rooms = new RoomStore(bob.db);
    assertEquals(3, Revisions.counterOf(rooms.latestRev("den")), "after the tombstone at 2");
    assertEquals(new RoomStore(main.db).latestRev("den"), rooms.baseRevOf("den"));
    SyncBox.round(main, bob);
    assertEquals(List.of(), bob.conflicts.pending());
    assertConverged();
  }

  @Test
  void aRoomReCreatedOverItsTombstoneOnAnotherNodeKeepsOneCreatorEverywhere() {
    Acting.as("ada", () -> new RoomStore(ada.db).create(room("den", "bob")));
    SyncBox.round(main, ada);
    SyncBox.round(main, bob);
    Acting.as("bob", () -> new RoomStore(bob.db).delete("den"));
    SyncBox.round(main, bob);
    Acting.as("bob", () -> new RoomStore(bob.db).create(room("den", "bob")));

    assertConverged();
    for (var box : List.of(main, ada, bob)) {
      assertEquals("ada", new RoomStore(box.db).findById("den").orElseThrow().createdBy(), box.id);
    }
  }

  @Test
  void aRoomDeletedOnMainAndRestoredOnTheNodeConverges() {
    Acting.as("ada", () -> new RoomStore(ada.db).create(room("den", "ada")));
    SyncBox.round(main, ada);
    SyncBox.round(main, bob);
    Acting.as("root", () -> new RoomStore(main.db).delete("den"));
    SyncBox.round(main, ada);

    Acting.as("ada", () -> new RoomStore(ada.db).restoreDeleted("den"));

    assertConverged();
    assertTrue(new RoomStore(main.db).findById("den").isPresent());
  }

  @Test
  void aRoomConflictResolvedMineConverges() {
    Acting.as("ada", () -> new RoomStore(ada.db).create(room("den", "ada")));
    SyncBox.round(main, ada);
    SyncBox.round(main, bob);
    Acting.as("root", () -> new RoomStore(main.db).updateWake("den", "off"));
    Acting.as("ada", () -> new RoomStore(ada.db).updateWake("den", "mention"));
    SyncBox.round(main, ada);

    resolve(ada, "room", "den", Resolve.MINE);

    assertConverged();
    assertEquals("mention", new RoomStore(main.db).findById("den").orElseThrow().wake());
  }

  @Test
  void disjointSpecEditsOnANodeAndMainConverge() {
    ownSpec(main, "ada", "s", "ada");
    SyncBox.round(main, ada);
    SyncBox.round(main, bob);
    retitle(ada, "ada", "s", "By ada");
    reprioritize(main, "root", "s", 3);

    assertConverged();
    var merged = main.specs.findById("s").orElseThrow();
    assertEquals("By ada", merged.title());
    assertEquals(3, merged.priority());
  }

  @Test
  void aSpecConflictResolvedMineConverges() {
    ownSpec(main, "ada", "s", "ada");
    SyncBox.round(main, ada);
    retitle(main, "root", "s", "By root");
    retitle(ada, "ada", "s", "By ada");
    SyncBox.round(main, ada);

    resolve(ada, "spec", "s", Resolve.MINE);

    assertConverged();
    assertEquals("By ada", main.specs.findById("s").orElseThrow().title());
  }

  @Test
  void aSpecConflictResolvedByMergeConverges() {
    ownSpec(main, "ada", "s", "ada");
    SyncBox.round(main, ada);
    retitle(main, "root", "s", "By root");
    retitle(ada, "ada", "s", "By ada");
    SyncBox.round(main, ada);

    resolve(ada, "spec", "s", Resolve.MERGE);

    assertConverged();
  }

  @Test
  void aDeleteVersusEditResolvedMineWhereTheNodeDeletedConverges() {
    ownSpec(main, "ada", "s", "ada");
    SyncBox.round(main, ada);
    SyncBox.round(main, bob);
    retitle(main, "root", "s", "By root");
    Acting.as("ada", () -> ada.specs.delete("s"));
    SyncBox.round(main, ada);

    resolve(ada, "spec", "s", Resolve.MINE);

    assertConverged();
    assertTrue(main.specs.findById("s").isEmpty());
  }

  @Test
  void aSpecDeletedOnANodeAndRestoredOnMainKeepsOneCreatorEverywhere() {
    ownSpec(ada, "ada", "s", "ada");
    SyncBox.round(main, ada);
    SyncBox.round(main, bob);
    Acting.as("ada", () -> ada.specs.delete("s"));
    SyncBox.round(main, ada);
    SyncBox.round(main, bob);

    Acting.as("root", () -> main.specs.restore("s", main.specs.history("s").getFirst().rev()));

    assertConverged();
    for (var box : List.of(main, ada, bob)) {
      assertEquals("ada", box.specs.findById("s").orElseThrow().createdBy(), box.id);
    }
  }

  @Test
  void aSpecRestoredOnTheNodeFromItsOwnTombstoneConverges() {
    ownSpec(ada, "ada", "s", "ada");
    SyncBox.round(main, ada);
    Acting.as("ada", () -> ada.specs.delete("s"));
    SyncBox.round(main, ada);
    SyncBox.round(main, bob);

    Acting.as("ada", () -> ada.specs.restore("s", ada.specs.history("s").getFirst().rev()));

    assertConverged();
    assertTrue(main.specs.findById("s").isPresent());
  }

  @Test
  void aDeniedEditAndDeleteOfAnotherMembersSpecConverge() {
    ownSpec(main, "bob", "s", "bob");
    SyncBox.round(main, ada);
    SyncBox.round(main, bob);
    retitle(ada, "ada", "s", "Taken over");
    Acting.as("ada", () -> ada.specs.delete("s"));

    var denied = SyncBox.round(main, ada);

    assertEquals(1, denied.getFirst().denials().size(), "announced once");
    assertConverged();
    assertEquals("Spec s", ada.specs.findById("s").orElseThrow().title());
  }

  @Test
  void aSpecReassignedAwayWhileTheNodeEditedConverges() {
    ownSpec(main, "ada", "s", "ada");
    SyncBox.round(main, ada);
    SyncBox.round(main, bob);
    assign(main, "root", "s", "bob");
    retitle(ada, "ada", "s", "Offline edit");
    reprioritize(bob, "bob", "s", 5);

    SyncBox.round(main, bob);
    SyncBox.round(main, ada);

    assertConverged();
    assertEquals(5, main.specs.findById("s").orElseThrow().priority());
  }

  @Test
  void aNodesPruneReachesEveryBoxWithOneAuthor() {
    archivedSpecWithAPost();

    Acting.as("ada", () -> new EraseRequests(ada.db).request(Erasure.SPEC, "s", "ada"));

    assertConverged();
    assertTrue(new ChangeLog(bob.db).isErased("spec", "s"));
  }

  @Test
  void mainsPruneReachesEveryBoxWithOneAuthor() {
    archivedSpecWithAPost();
    var erasure = new Erasure(main.db);

    Acting.as(
        "root",
        () ->
            erasure.erase(
                erasure.closure(List.of(new Erasure.Target(Erasure.SPEC, "s"))), "local"));

    assertConverged();
    assertTrue(new ChangeLog(ada.db).isErased("spec", "s"));
  }

  @Test
  void aFileConflictResolvedMineConverges() {
    putFile(main, "root", "a.txt", "one");
    SyncBox.round(main, ada);
    SyncBox.round(main, bob);
    putFile(main, "root", "a.txt", "main");
    putFile(ada, "ada", "a.txt", "ada");
    SyncBox.round(main, ada);

    resolve(ada, "file", "acme/a.txt", Resolve.MINE);

    assertConverged();
    assertEquals("ada", Kind.FILE.value(main));
  }

  @Test
  void aRunItsPostsAndAReplyConvergeOnEveryBox() {
    ownSpec(main, "ada", "s", "ada");
    SyncBox.round(main, ada);
    var runId = run(ada, "ada", "s");
    var runs = new RunStore(ada.db);
    var principal = runs.findById(runId).orElseThrow().principal();
    var post =
        Acting.by(
            Actor.agentPrincipal(principal, "ada"),
            () -> new MessageStore(ada.db).append("s", principal, "working", null));
    Acting.as("ada", () -> new MessageStore(ada.db).append("s", "ada", "note", post.id()));
    Acting.as("ada", () -> runs.complete(runId, "completed", 0));
    SyncBox.round(main, ada);

    Acting.as("root", () -> new MessageStore(main.db).append("s", "root", "ack", post.id()));

    assertConverged();
    assertEquals(3, new MessageStore(bob.db).list("s", null, 10).size());
  }

  @Test
  void aDeniedRunAndItsPostsLeaveEveryBoxAlike() {
    ownSpec(main, "bob", "s", "bob");
    SyncBox.round(main, ada);
    var runId = run(ada, "ada", "s");
    ada.db.execute("INSERT INTO run_principals (run_id, principal) VALUES (?, 'bob')", runId);
    Acting.as("ada", () -> new RunStore(ada.db).complete(runId, "completed", 0));
    Acting.as("ada", () -> new MessageStore(ada.db).append("s", "bob", "as bob", null));

    SyncBox.round(main, ada);

    assertConverged();
    assertTrue(new RunStore(main.db).findById(runId).isEmpty());
  }

  private void assertConverged() {
    SyncBox.quiesce(main, ada, bob);
    SyncBox.assertEqualToMain(main, ada);
    SyncBox.assertEqualToMain(main, bob);
  }

  private static void assertParkedAgainst(ChangeLog.Entry mains, SyncConflicts.Conflict parked) {
    assertEquals(mains.rev(), parked.remoteRev(), "the conflict keeps main's revision");
    assertEquals(mains.actor(), parked.remoteAuthor(), "and the author main recorded");
  }

  private void assertEveryBoxHolds(Kind kind, String value) {
    for (var box : List.of(main, ada, bob)) {
      assertEquals(value, kind.value(box), box.id);
    }
  }

  private static void assertNoDenials(List<SyncSession.TypeReport> reports) {
    for (var report : reports) {
      assertEquals(List.of(), report.denials(), report.toString());
    }
  }

  private void archivedSpecWithAPost() {
    ownSpec(ada, "ada", "s", "ada");
    Acting.as("ada", () -> new MessageStore(ada.db).append("s", "ada", "hello", null));
    SyncBox.round(main, ada);
    SyncBox.round(main, bob);
    Acting.as("ada", () -> ada.specs.updateStatus("s", SpecStatus.ARCHIVED));
    SyncBox.round(main, ada);
  }

  /** How a parked conflict is settled, as {@code sail conflicts resolve} offers it. */
  private enum Resolve {
    MINE,
    THEIRS,
    MERGE
  }

  private static void resolve(SyncBox box, String type, String id, Resolve strategy) {
    var conflict = box.conflicts.pendingFor(type, id).orElseThrow();
    var local = parse(conflict.localSnapshot());
    var theirs = conflict.theirs();
    var chosen =
        switch (strategy) {
          case MINE -> local;
          case THEIRS -> theirs.snapshot();
          case MERGE -> merged(conflict.baseSnapshot(), local, theirs.snapshot(), conflict);
        };
    var rev =
        Acting.as(
            box.id,
            () ->
                SyncedEntities.require(type).resolver(box.db).resolveConflict(id, chosen, theirs));
    box.conflicts.resolve(conflict.id(), rev);
  }

  private static Map<String, Object> merged(
      String base,
      Map<String, Object> local,
      Map<String, Object> remote,
      SyncConflicts.Conflict conflict) {
    var merged =
        new LinkedHashMap<>(
            ConflictMerge.parseTemplate(
                ConflictMerge.mergeTemplate(
                    parse(base), local, remote, conflict.fields(), "sha256:x")));
    merged.remove(ConflictMerge.CONFLICT);
    return merged;
  }

  private static Map<String, Object> parse(String json) {
    return json == null || json.isBlank() ? null : YamlUtil.parseMap(json);
  }

  private static SpecStore.SpecRow spec(String id, String assignee) {
    return new SpecStore.SpecRow(
        id,
        "acme",
        "Spec " + id,
        SpecStatus.PENDING,
        assignee,
        null,
        null,
        null,
        null,
        0,
        null,
        "",
        "",
        null,
        List.of(),
        List.of());
  }

  private static RoomStore.RoomRow room(String id, String assignee) {
    return new RoomStore.RoomRow(id, "acme", id, assignee, null, null, null, null, null, null);
  }

  private static void ownSpec(SyncBox box, String as, String id, String assignee) {
    Acting.as(
        as,
        () -> {
          box.specs.create(spec(id, assignee));
          new RoomStore(box.db)
              .create(
                  new RoomStore.RoomRow(
                      id, "acme", "Spec " + id, assignee, null, null, null, null, null, null));
        });
  }

  private static void retitle(SyncBox box, String as, String id, String title) {
    edit(box, as, id, row -> with(row, title, row.priority(), row.assignee()));
  }

  private static void reprioritize(SyncBox box, String as, String id, int priority) {
    edit(box, as, id, row -> with(row, row.title(), priority, row.assignee()));
  }

  private static void assign(SyncBox box, String as, String id, String assignee) {
    edit(box, as, id, row -> with(row, row.title(), row.priority(), assignee));
  }

  private static void edit(
      SyncBox box, String as, String id, UnaryOperator<SpecStore.SpecRow> change) {
    var row = box.specs.findById(id).orElseThrow();
    Acting.as(as, () -> box.specs.update(change.apply(row)));
  }

  private static SpecStore.SpecRow with(
      SpecStore.SpecRow row, String title, int priority, String assignee) {
    return new SpecStore.SpecRow(
        row.id(),
        row.project(),
        title,
        row.status(),
        assignee,
        row.agent(),
        row.model(),
        row.reasoningEffort(),
        row.branch(),
        priority,
        row.createdBy(),
        row.createdAt(),
        row.updatedAt(),
        row.updatedBy(),
        row.dependsOn(),
        row.repos(),
        row.roomId());
  }

  private static void putFile(SyncBox box, String as, String path, String text) {
    Acting.as(as, () -> new FileStore(box.db).put("acme", path, content(text), 420));
  }

  private static InputStream content(String text) {
    return new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8));
  }

  private static String run(SyncBox box, String fde, String specId) {
    var id = DateTimeUtils.newId().toString();
    Acting.as(
        fde,
        () ->
            new RunStore(box.db)
                .create(
                    id,
                    "acme",
                    specId,
                    fde,
                    "build",
                    "claude-code",
                    "b",
                    "t",
                    null,
                    null,
                    "/log",
                    "unit"));
    return id;
  }
}
