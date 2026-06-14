/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.commands;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.sail.config.SpecStatus;
import ai.singlr.sail.config.YamlUtil;
import ai.singlr.sail.store.FileStore;
import ai.singlr.sail.store.SchemaManager;
import ai.singlr.sail.store.SpecStore;
import ai.singlr.sail.store.Sqlite;
import ai.singlr.sail.store.SyncConflicts;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ConflictsCommandTest {

  @TempDir Path tempDir;
  private Sqlite db;
  private SpecStore specs;
  private SyncConflicts conflicts;

  @BeforeEach
  void setUp() {
    db = Sqlite.open(tempDir.resolve("test.db"));
    new SchemaManager(db).migrate();
    specs = new SpecStore(db);
    conflicts = new SyncConflicts(db);
  }

  @AfterEach
  void tearDown() {
    db.close();
  }

  private SpecStore.SpecRow spec(String id, String title) {
    return new SpecStore.SpecRow(
        id,
        "proj",
        title,
        SpecStatus.fromWire("pending"),
        null,
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

  private String json(Map<String, Object> map) {
    return YamlUtil.dumpJson(map);
  }

  @Test
  void renderListEmptyIsClean() {
    assertTrue(ConflictsCommand.renderList(List.of(), false).contains("No conflicts"));
  }

  @Test
  void renderListJsonCarriesEntityAndFields() {
    conflicts.record("spec", "auth", "{}", "{}", "{}", List.of("title"));
    var out = ConflictsCommand.renderList(conflicts.pending(), true);
    assertTrue(out.contains("\"entity\": \"auth\""));
    assertTrue(out.contains("\"title\""));
  }

  @Test
  void renderListHumanNamesTheConflictingEntity() {
    conflicts.record("spec", "auth", "{}", "{}", "{}", List.of("title"));
    var out = ConflictsCommand.renderList(conflicts.pending(), false);
    assertTrue(out.contains("auth"));
    assertTrue(out.contains("title"));
  }

  @Test
  void applyResolvesAndMarksTheConflict() {
    specs.create(spec("auth", "Mine"));
    var base = specs.comparableSnapshot("auth");
    var mine = new java.util.LinkedHashMap<>(base);
    mine.put("title", "Mine");
    var theirs = new java.util.LinkedHashMap<>(base);
    theirs.put("title", "Theirs");
    var id =
        conflicts.record("spec", "auth", json(base), json(mine), json(theirs), List.of("title"));
    var conflict = conflicts.pendingFor("spec", "auth").orElseThrow();

    ConflictsCommand.Resolve.apply(specs, conflicts, conflict, mine);

    assertTrue(conflicts.pending().isEmpty(), "the conflict is marked resolved");
    assertEquals("Mine", specs.findById("auth").orElseThrow().title());
    assertNotEquals(0, id);
  }

  @Test
  void strategyRequiresExactlyOneChoice() {
    assertEquals(
        ConflictsCommand.Resolve.Strategy.MINE,
        ConflictsCommand.Resolve.strategy(true, false, false));
    assertEquals(
        ConflictsCommand.Resolve.Strategy.THEIRS,
        ConflictsCommand.Resolve.strategy(false, true, false));
    assertEquals(
        ConflictsCommand.Resolve.Strategy.MERGE,
        ConflictsCommand.Resolve.strategy(false, false, true));
    assertNull(ConflictsCommand.Resolve.strategy(false, false, false));
    assertNull(ConflictsCommand.Resolve.strategy(true, true, false));
  }

  @Test
  void mergeableOnlyWhenBothSidesArePresent() {
    var both = conflicts.record("spec", "a", "{}", "{}", "{}", List.of("title"));
    assertTrue(ConflictsCommand.Resolve.mergeable(conflicts.pendingFor("spec", "a").orElseThrow()));
    assertNotEquals(0, both);

    conflicts.record("spec", "b", "{}", null, "{}", List.of("<deleted>"));
    assertFalse(
        ConflictsCommand.Resolve.mergeable(conflicts.pendingFor("spec", "b").orElseThrow()));
  }

  @Test
  void chooseSelectsTheStrategySnapshot() {
    var conflict =
        new SyncConflicts.Conflict(
            1,
            "spec",
            "auth",
            json(Map.of("title", "Base")),
            json(Map.of("title", "Mine")),
            json(Map.of("title", "Theirs")),
            List.of("title"),
            "now",
            "pending",
            null);

    assertEquals(
        "Mine",
        ConflictsCommand.Resolve.choose(conflict, ConflictsCommand.Resolve.Strategy.MINE, null)
            .get("title"));
    assertEquals(
        "Theirs",
        ConflictsCommand.Resolve.choose(conflict, ConflictsCommand.Resolve.Strategy.THEIRS, null)
            .get("title"));
    assertEquals(
        "Merged",
        ConflictsCommand.Resolve.choose(
                conflict, ConflictsCommand.Resolve.Strategy.MERGE, "title: Merged")
            .get("title"));
  }

  @Test
  void showRenderFlagsTheClashingField() {
    var conflict =
        new SyncConflicts.Conflict(
            1,
            "spec",
            "auth",
            json(Map.of("title", "Base", "status", "pending")),
            json(Map.of("title", "Mine", "status", "pending")),
            json(Map.of("title", "Theirs", "status", "in_progress")),
            List.of("title"),
            "now",
            "pending",
            null);

    var rendered = ConflictsCommand.Show.render(conflict);
    assertTrue(rendered.contains("auth"));
    assertTrue(rendered.contains("Mine"));
    assertTrue(rendered.contains("Theirs"));
    assertTrue(rendered.contains("in_progress"));
  }

  private static String b64(String text) {
    return Base64.getEncoder().encodeToString(text.getBytes());
  }

  @Test
  void renderListJsonCarriesEntityType() {
    conflicts.record("file", "acme/x.txt", "{}", "{}", "{}", List.of("content"));
    var out = ConflictsCommand.renderList(conflicts.pending(), true);
    assertTrue(out.contains("\"type\": \"file\""));
  }

  @Test
  void findUniqueResolvesByIdAcrossTypesAndReportsAbsenceAndAmbiguity() {
    assertNull(ConflictsCommand.findUnique(conflicts, "ghost"));

    conflicts.record("file", "acme/x.txt", "{}", "{}", "{}", List.of("content"));
    var found = ConflictsCommand.findUnique(conflicts, "acme/x.txt");
    assertEquals("file", found.entityType());

    conflicts.record("spec", "acme/x.txt", "{}", "{}", "{}", List.of("title"));
    assertNull(
        ConflictsCommand.findUnique(conflicts, "acme/x.txt"), "a cross-type clash is ambiguous");
  }

  @Test
  void resolverForDispatchesOnEntityType() {
    assertInstanceOf(FileStore.class, ConflictsCommand.resolverFor(db, "file"));
    assertInstanceOf(SpecStore.class, ConflictsCommand.resolverFor(db, "spec"));
  }

  @Test
  void applyResolvesAFileConflictThroughTheFileStore() {
    var files = new FileStore(db);
    files.put("acme", "x.txt", b64("mine"));
    var base = Map.<String, Object>of("content", b64("base"));
    var mine = Map.<String, Object>of("content", b64("mine"));
    var theirs = Map.<String, Object>of("content", b64("theirs"));
    conflicts.record(
        "file", "acme/x.txt", json(base), json(mine), json(theirs), List.of("content"));
    var conflict = ConflictsCommand.findUnique(conflicts, "acme/x.txt");

    ConflictsCommand.Resolve.apply(
        ConflictsCommand.resolverFor(db, conflict.entityType()), conflicts, conflict, theirs);

    assertTrue(conflicts.pending().isEmpty());
    assertEquals(b64("theirs"), files.find("acme", "x.txt").orElseThrow().content());
  }

  @Test
  void fileConflictsAreNeverFieldMergeable() {
    conflicts.record("file", "acme/x.txt", "{}", "{}", "{}", List.of("content"));
    assertFalse(
        ConflictsCommand.Resolve.mergeable(ConflictsCommand.findUnique(conflicts, "acme/x.txt")));
  }

  @Test
  void showDecodesFileContentAndSummarizesBinary() {
    assertEquals("hello", ConflictsCommand.Show.show(b64("hello"), true));
    assertEquals("\nline1\nline2", ConflictsCommand.Show.show(b64("line1\nline2\n"), true));
    assertEquals(
        "Theirs", ConflictsCommand.Show.show("Theirs", false), "spec values render verbatim");

    var binary = Base64.getEncoder().encodeToString(new byte[] {1, 2, 0, 3});
    assertTrue(ConflictsCommand.Show.show(binary, true).startsWith("<binary, 4 bytes>"));
    assertEquals("", ConflictsCommand.Show.show("", true), "blank content renders empty");
    assertTrue(ConflictsCommand.Show.looksBinary(new byte[] {0}));
    assertTrue(ConflictsCommand.Show.looksBinary(new byte[] {0x08}));
    assertTrue(ConflictsCommand.Show.looksBinary(new byte[] {0x1f}));
    assertFalse(ConflictsCommand.Show.looksBinary("tab\tnewline\r\n".getBytes()));
  }

  @Test
  void showRendersAFileConflictWithDecodedContent() {
    var conflict =
        new SyncConflicts.Conflict(
            1,
            "file",
            "acme/x.txt",
            json(Map.of("content", b64("base"))),
            json(Map.of("content", b64("mine"))),
            json(Map.of("content", b64("theirs"))),
            List.of("content"),
            "now",
            "pending",
            null);

    var rendered = ConflictsCommand.Show.render(conflict);
    assertTrue(rendered.contains("acme/x.txt"));
    assertTrue(rendered.contains("mine"));
    assertTrue(rendered.contains("theirs"));
  }
}
