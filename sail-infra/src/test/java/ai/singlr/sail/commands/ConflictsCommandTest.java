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

import ai.singlr.sail.api.ApiException;
import ai.singlr.sail.api.HostOperations;
import ai.singlr.sail.api.OperationsFactory;
import ai.singlr.sail.api.SessionYield;
import ai.singlr.sail.api.SyncScheduler;
import ai.singlr.sail.config.SyncConfig;
import ai.singlr.sail.config.YamlUtil;
import ai.singlr.sail.engine.ShellExecutor;
import ai.singlr.sail.engine.SyncOperations;
import ai.singlr.sail.store.RoomStore;
import ai.singlr.sail.store.SchemaManager;
import ai.singlr.sail.store.SpecStore;
import ai.singlr.sail.store.Sqlite;
import ai.singlr.sail.store.SyncConflicts;
import ai.singlr.sail.sync.SyncBox;
import ai.singlr.sail.sync.SyncedEntities;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

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
    return text;
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

    var binary = "<binary, 4 bytes>";
    assertTrue(ConflictsCommand.Show.show(binary, true).startsWith("<binary, 4 bytes>"));
    assertEquals("", ConflictsCommand.Show.show("", true), "blank content renders empty");
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

  private HostOperations operations() {
    var config = new SyncConfig("node", "sail@main", "node");
    return OperationsFactory.create(
            db,
            new ShellExecutor(true),
            "sail.yaml",
            null,
            null,
            SyncScheduler.disabled(),
            SessionYield.NONE)
        .useControlPlane(
            db,
            tempDir,
            new SyncOperations(
                db,
                "node",
                tempDir,
                () -> config,
                target -> {
                  throw new IOException("main unavailable");
                }));
  }

  private void parkASpecAndItsRoomUnderOneId() {
    new SpecStore(db).create(SyncBox.spec("auth", "node title", "pending"));
    new RoomStore(db).ensureFor("auth", "proj", "Auth", "uday", "mention", "uday");
    var replicas = SyncedEntities.replicas(db, "node", "node");
    for (var parked : List.of(List.of("spec", "title"), List.of("room", "wake"))) {
      var replica = replicas.get(parked.getFirst());
      var field = parked.getLast();
      var local = replica.current("auth");
      var remote = new LinkedHashMap<>(local);
      remote.put(field, "main's " + field);
      replica.recordConflict("auth", local, local, remote, List.of(field));
    }
  }

  private record Ran(int exit, String out, Exception escaped) {}

  private Ran run(Object command, String... args) {
    var out = new ByteArrayOutputStream();
    var original = System.out;
    var escaped = new Exception[1];
    try (var stream = new PrintStream(out, true, StandardCharsets.UTF_8)) {
      System.setOut(stream);
      var exit =
          new CommandLine(command)
              .setExecutionExceptionHandler(
                  (thrown, commandLine, parsed) -> {
                    escaped[0] = thrown;
                    return 1;
                  })
              .execute(args);
      return new Ran(exit, out.toString(StandardCharsets.UTF_8), escaped[0]);
    } finally {
      System.setOut(original);
    }
  }

  @Test
  void theListVerbPrintsWhatIsParkedOnThisBox() {
    parkASpecAndItsRoomUnderOneId();

    var listed = run(new ConflictsCommand(this::operations), "--json");

    assertEquals(0, listed.exit());
    assertEquals(
        List.of("room", "spec"),
        YamlUtil.parseList(listed.out().strip()).stream()
            .map(row -> (String) row.get("type"))
            .sorted()
            .toList());
  }

  @Test
  void anIdParkedUnderSeveralTypesIsRefusedByNameUntilTypeSaysWhich() {
    parkASpecAndItsRoomUnderOneId();

    var ambiguous = run(new ConflictsCommand.Resolve(this::operations), "auth", "--mine");
    var shown = run(new ConflictsCommand.Show(this::operations), "auth");

    for (var refused : List.of(ambiguous, shown)) {
      assertEquals(1, refused.exit());
      assertEquals(
          "'auth' has open conflicts as spec and room: pass --type",
          refused.escaped().getMessage());
    }
    assertEquals(2, conflicts.pending().size(), "a refusal settles nothing");
  }

  @Test
  void typeSettlesTheNamedConflictAndLeavesItsTwinParked() {
    parkASpecAndItsRoomUnderOneId();

    var shown = run(new ConflictsCommand.Show(this::operations), "auth", "--type", "room");
    var resolved =
        run(new ConflictsCommand.Resolve(this::operations), "auth", "--type", "spec", "--theirs");

    assertEquals(0, shown.exit());
    assertTrue(shown.out().contains("wake"), shown.out());
    assertEquals(0, resolved.exit());
    assertTrue(resolved.out().contains("Resolved"), resolved.out());
    assertEquals(
        List.of("room"),
        conflicts.pending().stream().map(SyncConflicts.Conflict::entityType).toList());
    assertEquals("main's title", new SpecStore(db).findById("auth").orElseThrow().title());
  }

  @Test
  void aConflictTheBoxHasSinceWrittenOverIsRefusedOnTheCommandLineWithTheRemedy() {
    parkASpecAndItsRoomUnderOneId();
    new SpecStore(db).setContent("auth", "written after the conflict was recorded", "");

    var stale =
        run(new ConflictsCommand.Resolve(this::operations), "auth", "--type", "spec", "--mine");

    assertEquals(1, stale.exit());
    assertEquals(409, assertInstanceOf(ApiException.class, stale.escaped()).status());
    assertTrue(stale.escaped().getMessage().contains("Run 'sail sync' to refresh it"));
    assertEquals(2, conflicts.pending().size());
  }

  @Test
  void aMergedRecordFromAFileSettlesASpecAndIsRefusedForWhatHasNoFieldsToMerge()
      throws IOException {
    parkASpecAndItsRoomUnderOneId();
    var merged =
        new LinkedHashMap<>(
            YamlUtil.parseMap(
                new ai.singlr.sail.engine.ConflictOperations(db)
                    .find("spec", "auth")
                    .localSnapshot()));
    merged.put("title", "merged title");
    var file = Files.writeString(tempDir.resolve("merged.yaml"), YamlUtil.dumpJson(merged));

    var room =
        run(
            new ConflictsCommand.Resolve(this::operations),
            "auth",
            "--type",
            "room",
            "--merge-file",
            file.toString());
    var spec =
        run(
            new ConflictsCommand.Resolve(this::operations),
            "auth",
            "--type",
            "spec",
            "--merge-file",
            file.toString());

    assertEquals(1, room.exit(), "a room has no field-level merge");
    assertEquals(0, spec.exit());
    assertEquals("merged title", new SpecStore(db).findById("auth").orElseThrow().title());
    assertEquals(
        List.of("room"),
        conflicts.pending().stream().map(SyncConflicts.Conflict::entityType).toList());
  }

  @Test
  void aVerbAimedAtNothingParkedSaysSoAndFails() {
    assertEquals(1, run(new ConflictsCommand.Show(this::operations), "ghost").exit());
    assertEquals(1, run(new ConflictsCommand.Resolve(this::operations), "ghost", "--mine").exit());
    assertEquals(
        1,
        run(new ConflictsCommand.Resolve(this::operations), "ghost", "--mine", "--theirs").exit(),
        "two strategies are no decision");
  }
}
