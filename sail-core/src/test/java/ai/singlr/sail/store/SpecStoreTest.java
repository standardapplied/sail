/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.sail.config.SpecStatus;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SpecStoreTest {

  @TempDir Path tempDir;
  private Sqlite db;
  private SpecStore store;

  @BeforeEach
  void setUp() {
    db = Sqlite.open(tempDir.resolve("test.db"));
    new SchemaManager(db).migrate();
    store = new SpecStore(db);
  }

  @AfterEach
  void tearDown() {
    if (db != null) db.close();
  }

  private SpecStore.SpecRow spec(String id, String title, String status) {
    return spec(id, "test-project", title, status);
  }

  private SpecStore.SpecRow spec(String id, String project, String title, String status) {
    return new SpecStore.SpecRow(
        id,
        project,
        title,
        SpecStatus.fromWire(status),
        null,
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

  @Test
  void assignedToMatchesOnlyANonBlankHandleEqualToTheAssignee() {
    var mine =
        new SpecStore.SpecRow(
            "s",
            "test-project",
            "T",
            SpecStatus.fromWire("pending"),
            "uday",
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
    assertTrue(mine.assignedTo("uday"));
    assertFalse(mine.assignedTo("mady"));
    assertFalse(mine.assignedTo(null), "a blank handle is assigned no spec");
    assertFalse(
        spec("s", "T", "pending").assignedTo("uday"), "an unassigned spec is owned by nobody");
  }

  @Test
  void createAndFindById() {
    store.create(spec("auth", "OAuth flow", "pending"));

    var found = store.findById("auth");
    assertTrue(found.isPresent());
    assertEquals("auth", found.get().id());
    assertEquals("OAuth flow", found.get().title());
    assertEquals(SpecStatus.PENDING, found.get().status());
  }

  @Test
  void findByIdReturnsEmptyForMissing() {
    assertTrue(store.findById("nonexistent").isEmpty());
  }

  @Test
  void wakeModePersistsThroughCreateUpdateAndList() {
    store.create(spec("auth", "OAuth", "pending").withWake("mention"));

    assertEquals("mention", store.findById("auth").orElseThrow().wake());
    assertEquals("mention", store.list(SpecStore.SpecFilter.all()).getFirst().wake());

    store.update(store.findById("auth").orElseThrow().withWake("off"));
    assertEquals("off", store.findById("auth").orElseThrow().wake());

    store.update(store.findById("auth").orElseThrow().withWake(null));
    assertTrue(store.findById("auth").orElseThrow().wake() == null);
  }

  @Test
  void wakeModeRidesTheSnapshotAndSurvivesApplyRevision() {
    store.create(spec("auth", "OAuth", "pending").withWake("on"));

    var snapshot = store.comparableSnapshot("auth");
    assertEquals("on", snapshot.get("wake"));

    store.applyRevision("auth", snapshot, "2-abc");
    assertEquals("on", store.findById("auth").orElseThrow().wake());
  }

  @Test
  void projectSpecsReturnsOnlyTheBucketAsConfigValues() {
    store.create(spec("mine", "acme", "OAuth", "pending"));
    store.create(spec("other", "zenith", "Search", "pending"));

    var specs = store.projectSpecs("acme");

    assertEquals(1, specs.size());
    var first = specs.getFirst();
    assertEquals("mine", first.id());
    assertEquals("acme", first.project());
    assertEquals("OAuth", first.title());
    assertEquals(SpecStatus.PENDING, first.status());
  }

  @Test
  void createWithDependenciesAndRepos() {
    store.create(spec("base", "Base feature", "done"));
    var spec =
        new SpecStore.SpecRow(
            "derived",
            "test-project",
            "Derived feature",
            SpecStatus.PENDING,
            "uday",
            "claude-code",
            null,
            null,
            "feat/derived",
            10,
            "uday",
            "",
            "",
            null,
            List.of("base"),
            List.of("backend", "frontend"));
    store.create(spec);

    var found = store.findById("derived").orElseThrow();
    assertEquals(List.of("base"), found.dependsOn());
    assertEquals(List.of("backend", "frontend"), found.repos());
    assertEquals("uday", found.assignee());
    assertEquals("claude-code", found.agent());
    assertEquals("feat/derived", found.branch());
    assertEquals(10, found.priority());
  }

  @Test
  void listAll() {
    store.create(spec("a", "First", "pending"));
    store.create(spec("b", "Second", "in_progress"));
    store.create(spec("c", "Third", "done"));

    var all = store.list(SpecStore.SpecFilter.all());
    assertEquals(3, all.size());
  }

  @Test
  void listFilterByStatus() {
    store.create(spec("a", "First", "pending"));
    store.create(spec("b", "Second", "in_progress"));
    store.create(spec("c", "Third", "done"));

    var pending = store.list(new SpecStore.SpecFilter(null, "pending", null, null, null));
    assertEquals(1, pending.size());
    assertEquals("a", pending.getFirst().id());
  }

  @Test
  void listFilterByMultipleStatuses() {
    store.create(spec("a", "First", "pending"));
    store.create(spec("b", "Second", "in_progress"));
    store.create(spec("c", "Third", "done"));

    var active =
        store.list(new SpecStore.SpecFilter(null, "pending,in_progress", null, null, null));
    assertEquals(2, active.size());
  }

  @Test
  void listFilterByAssignee() {
    var assigned =
        new SpecStore.SpecRow(
            "a",
            "test-project",
            "Assigned",
            SpecStatus.PENDING,
            "uday",
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
    store.create(assigned);
    store.create(spec("b", "Unassigned", "pending"));

    var result = store.list(new SpecStore.SpecFilter(null, null, "uday", null, null));
    assertEquals(1, result.size());
    assertEquals("a", result.getFirst().id());
  }

  @Test
  void listFilterBySearch() {
    store.create(spec("oauth-flow", "OAuth 2.0 authorization", "pending"));
    store.create(spec("payment", "Payment integration", "pending"));

    var result = store.list(new SpecStore.SpecFilter(null, null, null, null, "oauth"));
    assertEquals(1, result.size());
    assertEquals("oauth-flow", result.getFirst().id());
  }

  @Test
  void updateSpec() {
    store.create(spec("a", "Original", "draft"));

    var updated =
        new SpecStore.SpecRow(
            "a",
            "test-project",
            "Updated",
            SpecStatus.PENDING,
            "bob",
            null,
            null,
            null,
            "feat/a",
            5,
            null,
            "",
            "",
            null,
            List.of(),
            List.of("backend"));
    store.update(updated);

    var found = store.findById("a").orElseThrow();
    assertEquals("Updated", found.title());
    assertEquals(SpecStatus.PENDING, found.status());
    assertEquals("bob", found.assignee());
    assertEquals("feat/a", found.branch());
    assertEquals(5, found.priority());
    assertEquals(List.of("backend"), found.repos());
  }

  @Test
  void updateStatus() {
    store.create(spec("a", "Test", "pending"));
    store.updateStatus("a", SpecStatus.IN_PROGRESS);

    var found = store.findById("a").orElseThrow();
    assertEquals(SpecStatus.IN_PROGRESS, found.status());
  }

  @Test
  void compareAndSetStatusCommitsOnlyFromTheExpectedStatus() {
    store.create(spec("a", "Test", "in_progress"));

    assertTrue(store.compareAndSetStatus("a", SpecStatus.IN_PROGRESS, SpecStatus.CANCELLED));
    assertEquals(SpecStatus.CANCELLED, store.findById("a").orElseThrow().status());

    assertFalse(
        store.compareAndSetStatus("a", SpecStatus.IN_PROGRESS, SpecStatus.REVIEW),
        "a writer holding a stale read must lose to the transition that won");
    assertEquals(SpecStatus.CANCELLED, store.findById("a").orElseThrow().status());
  }

  @Test
  void compareAndSetStatusJournalsARevisionOnlyWhenItWins() {
    store.create(spec("a", "Test", "in_progress"));
    var created = store.revOf("a");

    store.compareAndSetStatus("a", SpecStatus.REVIEW, SpecStatus.AWAITING_MERGE);
    assertEquals(created, store.revOf("a"), "a lost CAS writes nothing, so no revision is minted");

    store.compareAndSetStatus("a", SpecStatus.IN_PROGRESS, SpecStatus.REVIEW);
    assertNotEquals(created, store.revOf("a"), "a won CAS journals like any other mutation");
  }

  @Test
  void updateReposAndStatusReplacesReposWithTheStatusTransition() {
    store.create(spec("a", "Test", "pending"));
    store.updateReposAndStatus("a", List.of("api", "web"), SpecStatus.IN_PROGRESS, "agent/a");

    var found = store.findById("a").orElseThrow();
    assertEquals(SpecStatus.IN_PROGRESS, found.status());
    assertEquals(List.of("api", "web"), found.repos());
    assertEquals("agent/a", found.branch());
    assertEquals(2, store.history("a").size());
  }

  @Test
  void updateReposAndStatusWithBlankBranchKeepsTheStoredOne() {
    store.create(spec("a", "Test", "pending"));
    store.updateReposAndStatus("a", List.of("api"), SpecStatus.IN_PROGRESS, "agent/a");
    store.updateReposAndStatus("a", List.of("api"), SpecStatus.IN_PROGRESS, null);
    store.updateReposAndStatus("a", List.of("api"), SpecStatus.IN_PROGRESS, "");

    assertEquals("agent/a", store.findById("a").orElseThrow().branch());
  }

  @Test
  void deleteSpec() {
    store.create(spec("a", "Doomed", "draft"));
    store.delete("a");
    assertTrue(store.findById("a").isEmpty());
  }

  @Test
  void setAndGetContent() {
    store.create(spec("a", "Test", "draft"));
    store.setContent("a", "# Spec body\n\nDetails here.", "## Plan\n\n1. Step one");

    var content = store.getContent("a").orElseThrow();
    assertEquals("# Spec body\n\nDetails here.", content.body());
    assertEquals("## Plan\n\n1. Step one", content.plan());
  }

  @Test
  void setContentUpdatesExisting() {
    store.create(spec("a", "Test", "draft"));
    store.setContent("a", "v1", "");
    store.setContent("a", "v2", "plan v2");

    var content = store.getContent("a").orElseThrow();
    assertEquals("v2", content.body());
    assertEquals("plan v2", content.plan());
  }

  @Test
  void readySpecsRespectsDependencies() {
    store.create(spec("base", "Base", "pending"));
    var dependent =
        new SpecStore.SpecRow(
            "child",
            "test-project",
            "Child",
            SpecStatus.PENDING,
            null,
            null,
            null,
            null,
            null,
            0,
            null,
            "",
            "",
            null,
            List.of("base"),
            List.of());
    store.create(dependent);

    var ready = store.readySpecs();
    assertEquals(1, ready.size());
    assertEquals("base", ready.getFirst().id());
  }

  @Test
  void readySpecsIncludesWhenDependenciesDone() {
    store.create(spec("base", "Base", "done"));
    var dependent =
        new SpecStore.SpecRow(
            "child",
            "test-project",
            "Child",
            SpecStatus.PENDING,
            null,
            null,
            null,
            null,
            null,
            0,
            null,
            "",
            "",
            null,
            List.of("base"),
            List.of());
    store.create(dependent);

    var ready = store.readySpecs();
    assertEquals(1, ready.size());
    assertEquals("child", ready.getFirst().id());
  }

  @Test
  void boardSummary() {
    store.create(spec("a", "A", "draft"));
    store.create(spec("b", "B", "pending"));
    store.create(spec("c", "C", "pending"));
    store.create(spec("d", "D", "in_progress"));
    store.create(spec("e", "E", "review"));
    store.create(spec("f", "F", "done"));
    store.create(spec("g", "G", "archived"));
    store.create(spec("h", "H", "awaiting_merge"));
    store.create(spec("i", "I", "cancelled"));

    var board = store.board();
    assertEquals(1, board.draft());
    assertEquals(2, board.pending());
    assertEquals(1, board.inProgress());
    assertEquals(1, board.review());
    assertEquals(1, board.awaitingMerge());
    assertEquals(1, board.done());
    assertEquals(1, board.cancelled());
    assertEquals(1, board.archived());
    assertEquals("b", board.nextReadyId());
  }

  @Test
  void cancelledPersistsAndReadsBack() {
    store.create(spec("killed", "Killed", "in_progress"));

    store.updateStatus("killed", SpecStatus.CANCELLED);

    assertEquals(SpecStatus.CANCELLED, store.findById("killed").get().status());
  }

  @Test
  void cancelledDependencyDoesNotUnblockDependents() {
    store.create(spec("base", "Base", "cancelled"));
    var dependent =
        new SpecStore.SpecRow(
            "child",
            "test-project",
            "Child",
            SpecStatus.PENDING,
            null,
            null,
            null,
            null,
            null,
            0,
            null,
            "",
            "",
            null,
            List.of("base"),
            List.of());
    store.create(dependent);

    assertTrue(store.readySpecs().isEmpty(), "cancelled work must not satisfy a dependency");
  }

  @Test
  void awaitingMergePersistsAndReadsBack() {
    store.create(spec("gate-passed", "Gate passed", "review"));

    store.updateStatus("gate-passed", SpecStatus.AWAITING_MERGE);

    assertEquals(SpecStatus.AWAITING_MERGE, store.findById("gate-passed").get().status());
  }

  @Test
  void awaitingMergeDependencyDoesNotUnblockDependents() {
    store.create(spec("base", "Base", "awaiting_merge"));
    var dependent =
        new SpecStore.SpecRow(
            "child",
            "test-project",
            "Child",
            SpecStatus.PENDING,
            null,
            null,
            null,
            null,
            null,
            0,
            null,
            "",
            "",
            null,
            List.of("base"),
            List.of());
    store.create(dependent);

    assertTrue(store.readySpecs().isEmpty(), "unmerged work must not satisfy a dependency");
  }

  @Test
  void applyRevisionRejectsAnUnknownStatusAsCorruption() {
    var snapshot = new java.util.LinkedHashMap<String, Object>();
    snapshot.put("title", "From the future");
    snapshot.put("status", "warp_speed");
    snapshot.put("project", "test-project");
    snapshot.put("body", "");
    snapshot.put("plan", "");

    var refusal =
        assertThrows(
            IllegalArgumentException.class,
            () -> store.applyRevision("future-spec", snapshot, "rev-1"));

    assertEquals(
        "Invalid spec status: 'warp_speed'. Must be one of: draft, pending, in_progress, review,"
            + " awaiting_merge, done, cancelled, archived",
        refusal.getMessage());
    assertTrue(store.findById("future-spec").isEmpty());
  }

  @Test
  void boardScopesCountsAndNextReadyToTheProject() {
    store.create(spec("sing-ready", "api", "Sing ready", "pending"));
    store.create(spec("sing-archived", "api", "Sing archived", "archived"));
    store.create(spec("other-ready", "light-grid", "Other ready", "pending"));

    var board = store.board("api");

    assertEquals(1, board.pending(), "counts only this project's specs");
    assertEquals(1, board.archived(), "archived specs are counted, not dropped");
    assertEquals(
        "sing-ready", board.nextReadyId(), "next-ready never leaks another project's spec");
  }

  @Test
  void readySpecsScopesToTheProject() {
    store.create(spec("sing-ready", "api", "Sing", "pending"));
    store.create(spec("other-ready", "light-grid", "Other", "pending"));

    var ready = store.readySpecs("api");

    assertEquals(1, ready.size());
    assertEquals("sing-ready", ready.getFirst().id());
  }

  @Test
  void reprojectMovesEverySpecToTheNewProjectAndLeavesOthers() {
    store.create(spec("a", "old", "A", "pending"));
    store.create(spec("b", "old", "B", "done"));
    store.create(spec("c", "other", "C", "pending"));

    store.reproject("old", "renamed");

    assertEquals(2, store.projectSpecs("renamed").size());
    assertTrue(store.projectSpecs("old").isEmpty());
    assertEquals(1, store.projectSpecs("other").size(), "other projects are untouched");
  }

  @Test
  void deleteCascadesDependenciesAndContent() {
    store.create(spec("base", "Base", "done"));
    var child =
        new SpecStore.SpecRow(
            "child",
            "test-project",
            "Child",
            SpecStatus.PENDING,
            null,
            null,
            null,
            null,
            null,
            0,
            null,
            "",
            "",
            null,
            List.of("base"),
            List.of("backend"));
    store.create(child);
    store.setContent("child", "body", "plan");

    store.delete("child");

    assertTrue(store.findById("child").isEmpty());
    assertTrue(store.getContent("child").isEmpty());
  }

  @Test
  void aSpecCanDependOnOneThatHasNotArrivedYet() {
    var billing =
        new SpecStore.SpecRow(
            "billing",
            "test-project",
            "Billing",
            SpecStatus.PENDING,
            null,
            null,
            null,
            null,
            null,
            0,
            null,
            "",
            "",
            null,
            List.of("auth"),
            List.of());

    store.create(billing);

    assertEquals(List.of("auth"), store.findById("billing").orElseThrow().dependsOn());
    assertTrue(store.findById("auth").isEmpty(), "the dependency need not exist");
  }

  @Test
  void roomIdRidesTheSnapshotAndLegacySnapshotsFallBackToIdentity() {
    store.create(spec("auth", "OAuth", "draft").withRoomId("design-room"));
    assertEquals("design-room", store.findById("auth").orElseThrow().roomIdOrIdentity());
    var snapshot = store.comparableSnapshot("auth");
    assertEquals("design-room", snapshot.get("room_id"), "the room link syncs");

    var legacy = new java.util.LinkedHashMap<String, Object>(snapshot);
    legacy.remove("room_id");
    store.applyRevision("auth", legacy, "9-legacy");
    assertEquals(
        "auth",
        store.findById("auth").orElseThrow().roomIdOrIdentity(),
        "a pre-decouple snapshot falls back to the identity room");
  }

  @Test
  void updateEngagementTouchesOnlyTheMirrorColumnAndJournals() {
    store.create(spec("auth", "OAuth", "draft"));
    store.updateStatus("auth", SpecStatus.IN_PROGRESS);
    var before = store.findById("auth").orElseThrow();
    var rev = store.revOf("auth");

    store.updateEngagement("auth", "{\"agent\":\"claude-code\",\"engaged_at\":\"t0\"}");

    var after = store.findById("auth").orElseThrow();
    assertEquals(SpecStatus.IN_PROGRESS, after.status(), "a mirror write never touches status");
    assertEquals(before.assignee(), after.assignee());
    assertEquals(before.title(), after.title());
    assertNotNull(after.engagement());
    assertNotEquals(rev, store.revOf("auth"), "the mirror write journals a revision");

    store.updateEngagement("auth", null);
    assertNull(store.findById("auth").orElseThrow().engagement());
  }

  @Test
  void engagementRoundTripsThroughCreateUpdateSnapshotAndList() {
    var engagement = "{\"agent\":\"claude-code\",\"mode\":\"full\",\"engaged_at\":\"t0\"}";
    store.create(spec("auth", "OAuth", "draft").withEngagement(engagement));
    store.create(spec("plain", "Other", "draft"));

    assertEquals(engagement, store.findById("auth").orElseThrow().engagement());
    assertEquals(
        List.of("auth"),
        store.listEngaged().stream().map(SpecStore.SpecRow::id).toList(),
        "only engaged rooms join the sweep");

    var journaled =
        db.queryOne(
                "SELECT snapshot FROM change_log WHERE entity_id = 'auth'"
                    + " ORDER BY seq DESC LIMIT 1",
                r -> r.text(0))
            .orElseThrow();
    assertTrue(journaled.contains("engagement"), "the engagement syncs as one atomic field");

    store.update(store.findById("auth").orElseThrow().withEngagement(null));
    assertTrue(store.listEngaged().isEmpty(), "disengaging clears the room");
    assertEquals(
        null, store.findById("auth").orElseThrow().engagement(), "the column reads back null");
  }
}
