/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.commands;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.sail.config.YamlUtil;
import ai.singlr.sail.store.SchemaManager;
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
  private SyncConflicts conflicts;

  @BeforeEach
  void setUp() {
    db = Sqlite.open(tempDir.resolve("test.db"));
    new SchemaManager(db).migrate();
    conflicts = new SyncConflicts(db);
  }

  @AfterEach
  void tearDown() {
    db.close();
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
  void mergeIsOfferedOnlyForSpecsNotProjectsOrFiles() {
    conflicts.record("project", "acme", "{}", "{}", "{}", List.of("definition"));
    conflicts.record("file", "acme/x.txt", "{}", "{}", "{}", List.of("content"));

    assertFalse(
        ConflictsCommand.Resolve.mergeable(conflicts.pendingFor("project", "acme").orElseThrow()),
        "a project definition is a single blob — resolve with --mine/--theirs");
    assertFalse(
        ConflictsCommand.Resolve.mergeable(
            conflicts.pendingFor("file", "acme/x.txt").orElseThrow()));
  }

  @Test
  void fileConflictsAreNeverFieldMergeable() {
    conflicts.record("file", "acme/x.txt", "{}", "{}", "{}", List.of("content"));
    assertFalse(
        ConflictsCommand.Resolve.mergeable(
            conflicts.pendingFor("file", "acme/x.txt").orElseThrow()));
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
