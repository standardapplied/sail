/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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

    var board = store.board();
    assertEquals(1, board.draft());
    assertEquals(2, board.pending());
    assertEquals(1, board.inProgress());
    assertEquals(1, board.review());
    assertEquals(1, board.done());
    assertEquals("b", board.nextReadyId());
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
  void backfillMakesAPreJournalSpecSyncable() {
    store.create(spec("oauth", "OAuth flow", "done"));
    db.execute("DELETE FROM change_log WHERE entity_type = 'spec' AND entity_id = ?", "oauth");
    assertFalse(store.syncEntityIds().contains("oauth"), "simulated pre-journal spec");

    assertEquals(1, store.backfillRevisions());
    assertTrue(store.syncEntityIds().contains("oauth"), "now visible to sync");
  }

  @Test
  void backfillIsANoOpForAnAlreadyJournaledSpec() {
    store.create(spec("oauth", "OAuth flow", "done"));
    assertEquals(0, store.backfillRevisions(), "create already journaled it");
  }

  @Test
  void backfillJournalsEveryPreJournalSpecAcrossProjects() {
    store.create(spec("a", "acme", "A", "pending"));
    store.create(spec("b", "zenith", "B", "pending"));
    db.execute("DELETE FROM change_log WHERE entity_type = 'spec'");

    assertEquals(2, store.backfillRevisions());
    assertTrue(store.syncEntityIds().containsAll(List.of("a", "b")));
  }
}
