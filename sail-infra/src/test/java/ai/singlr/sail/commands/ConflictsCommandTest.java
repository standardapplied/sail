/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.commands;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import ai.singlr.sail.api.ApiException;
import ai.singlr.sail.api.HostOperations;
import ai.singlr.sail.api.OperationsFactory;
import ai.singlr.sail.api.Resolution;
import ai.singlr.sail.api.SessionYield;
import ai.singlr.sail.api.SyncScheduler;
import ai.singlr.sail.config.SyncConfig;
import ai.singlr.sail.config.YamlUtil;
import ai.singlr.sail.engine.ConflictOperations;
import ai.singlr.sail.engine.ShellExecutor;
import ai.singlr.sail.engine.SyncOperations;
import ai.singlr.sail.store.BlobStore;
import ai.singlr.sail.store.FileStore;
import ai.singlr.sail.store.RoomStore;
import ai.singlr.sail.store.SchemaManager;
import ai.singlr.sail.store.SpecStore;
import ai.singlr.sail.store.Sqlite;
import ai.singlr.sail.store.SyncConflicts;
import ai.singlr.sail.sync.ConflictMerge;
import ai.singlr.sail.sync.SyncBox;
import ai.singlr.sail.sync.SyncedEntities;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
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
    assertEquals(Resolution.Strategy.MINE, ConflictsCommand.Resolve.strategy(true, false, false));
    assertEquals(Resolution.Strategy.THEIRS, ConflictsCommand.Resolve.strategy(false, true, false));
    assertEquals(Resolution.Strategy.MERGE, ConflictsCommand.Resolve.strategy(false, false, true));
    assertNull(ConflictsCommand.Resolve.strategy(false, false, false));
    assertNull(ConflictsCommand.Resolve.strategy(true, true, false));
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

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void showPreservesContentConflictsWhenEqualSizeFilesHaveIdenticalPreviews(boolean binary) {
    try (var main = new SyncBox("main");
        var node = new SyncBox("node")) {
      var base =
          binary
              ? new byte[] {0, 1}
              : ("x".repeat(64 * 1024) + "1").getBytes(StandardCharsets.UTF_8);
      var mine = base.clone();
      var theirs = base.clone();
      mine[mine.length - 1]++;
      theirs[theirs.length - 1] += 2;
      var mainFiles = new FileStore(main.db);
      var nodeFiles = new FileStore(node.db);
      mainFiles.put("acme", "file", new ByteArrayInputStream(base), 0644);
      SyncBox.round(main.db, node.db, "file");
      mainFiles.put("acme", "file", new ByteArrayInputStream(theirs), 0644);
      nodeFiles.put("acme", "file", new ByteArrayInputStream(mine), 0644);
      SyncBox.round(main.db, node.db, "file");

      var conflict = new ConflictOperations(node.db).find("file", "acme/file");
      assertEquals(List.of("content"), conflict.fields());
      var diff =
          ConflictMerge.diff(
              ConflictOperations.parse(conflict.baseSnapshot()),
              ConflictOperations.parse(conflict.localSnapshot()),
              ConflictOperations.parse(conflict.remoteSnapshot()),
              conflict.fields());
      assertEquals(
          List.of("content"), diff.stream().map(ConflictMerge.FieldChange::field).toList());
      assertTrue(diff.getFirst().clash());
      var rendered = ConflictsCommand.Show.render(conflict);
      assertTrue(rendered.contains("✗ content"));
      for (var bytes : List.of(base, mine, theirs)) {
        assertTrue(rendered.contains("SHA-256: " + BlobStore.hash(bytes)));
      }
      assertTrue(
          rendered.contains(binary ? "<binary, 2 bytes>" : "… (preview; 65537 bytes total)"));
    }
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
    park("spec", "title", "main's title");
    park("room", "wake", "main's wake");
  }

  private void park(String type, String field, String theirs) {
    var replica = SyncedEntities.replicas(db, "node", "node").get(type);
    var local = replica.current("auth");
    var remote = new LinkedHashMap<>(local);
    remote.put(field, theirs);
    replica.recordConflict("auth", local, local, remote, List.of(field));
  }

  private SyncConflicts.Conflict parkedSpec() {
    return conflicts.pendingFor("spec", "auth").orElseThrow();
  }

  private String title() {
    return new SpecStore(db).findById("auth").orElseThrow().title();
  }

  private record Ran(int exit, String out, String err, Exception escaped) {}

  private Ran run(Object command, String... args) {
    var out = new ByteArrayOutputStream();
    var err = new ByteArrayOutputStream();
    var originalOut = System.out;
    var originalErr = System.err;
    var escaped = new Exception[1];
    try (var outStream = new PrintStream(out, true, StandardCharsets.UTF_8);
        var errStream = new PrintStream(err, true, StandardCharsets.UTF_8)) {
      System.setOut(outStream);
      System.setErr(errStream);
      var exit =
          new CommandLine(command)
              .setExecutionExceptionHandler(
                  (thrown, commandLine, parsed) -> {
                    escaped[0] = thrown;
                    return 1;
                  })
              .execute(args);
      return new Ran(
          exit,
          out.toString(StandardCharsets.UTF_8),
          err.toString(StandardCharsets.UTF_8),
          escaped[0]);
    } finally {
      System.setOut(originalOut);
      System.setErr(originalErr);
    }
  }

  private ConflictsCommand.Show show() {
    return new ConflictsCommand.Show(this::operations);
  }

  private ConflictsCommand.Resolve resolve() {
    return resolve(file -> fail("the editor must not open"));
  }

  private ConflictsCommand.Resolve resolve(ConflictsCommand.Resolve.Editor editor) {
    return new ConflictsCommand.Resolve(this::operations, editor);
  }

  private static ConflictsCommand.Resolve.Editor mergingTitle(
      List<Path> opened, Runnable meanwhile) {
    return file -> {
      opened.add(file);
      Files.writeString(file, retitled(Files.readString(file)));
      meanwhile.run();
      return 0;
    };
  }

  private static String retitled(String template) {
    assertTrue(template.contains("\ntitle: node title\n"), template);
    return template.replace("\ntitle: node title\n", "\ntitle: merged title\n");
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

    var ambiguous = run(resolve(), "auth", "--mine");
    var shown = run(show(), "auth");

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

    var shown = run(show(), "auth", "--type", "room");
    var resolved = run(resolve(), "auth", "--type", "spec", "--theirs");

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

    var stale = run(resolve(), "auth", "--type", "spec", "--mine");

    assertEquals(1, stale.exit());
    assertEquals(409, assertInstanceOf(ApiException.class, stale.escaped()).status());
    assertTrue(stale.escaped().getMessage().contains("Run 'sail sync' to refresh it"));
    assertEquals(2, conflicts.pending().size());
  }

  @Test
  void aTemplateShownOnTheCommandLineSettlesASpecThroughAMergeFileButNotARoom() throws IOException {
    parkASpecAndItsRoomUnderOneId();
    var shown = run(show(), "auth", "--type", "spec", "--template");
    var file = Files.writeString(tempDir.resolve("merged.yaml"), retitled(shown.out()));

    var room = run(resolve(), "auth", "--type", "room", "--merge-file", file.toString());
    var spec = run(resolve(), "auth", "--type", "spec", "--merge-file", file.toString());

    assertEquals(0, shown.exit());
    assertEquals(1, room.exit(), "a room has no field-level merge");
    assertEquals(
        "Field-level --merge isn't available for this conflict; use --mine or --theirs.",
        room.escaped().getMessage());
    assertEquals(0, spec.exit());
    assertEquals("merged title", title());
    assertEquals(
        List.of("room"),
        conflicts.pending().stream().map(SyncConflicts.Conflict::entityType).toList());
  }

  @Test
  void aMergeFileThatDoesNotNameThisVersionOfTheConflictIsRefusedAndTouchesNothing()
      throws IOException {
    parkASpecAndItsRoomUnderOneId();
    var template = run(show(), "auth", "--type", "spec", "--template").out();
    var unnamed =
        Files.writeString(
            tempDir.resolve("unnamed.yaml"),
            retitled(template).replaceAll("(?m)^" + ConflictMerge.CONFLICT + ": .*\n", ""));
    var stale = Files.writeString(tempDir.resolve("stale.yaml"), retitled(template));
    var rev = new SpecStore(db).revOf("auth");

    var parked = parkedSpec();
    var absent = run(resolve(), "auth", "--type", "spec", "--merge-file", unnamed.toString());
    assertEquals(1, absent.exit());
    assertEquals(400, assertInstanceOf(ApiException.class, absent.escaped()).status());
    assertEquals(
        "A merged record must start from 'sail conflicts show auth --template'.",
        absent.escaped().getMessage());
    assertEquals(parked, parkedSpec());

    park("spec", "title", "main's title, revised");
    var reparked = parkedSpec();
    var moved = run(resolve(), "auth", "--type", "spec", "--merge-file", stale.toString());
    assertEquals(1, moved.exit());
    assertEquals(409, assertInstanceOf(ApiException.class, moved.escaped()).status());
    assertEquals(
        "'auth' was re-recorded after this merge was started. Start again from the fresh version.",
        moved.escaped().getMessage());
    assertEquals(reparked, parkedSpec());

    assertEquals("node title", title());
    assertEquals(rev, new SpecStore(db).revOf("auth"));
  }

  @Test
  void aConflictTheBoxHasSinceWrittenOverIsRefusedAMergeBeforeTheEditorOpens() {
    parkASpecAndItsRoomUnderOneId();
    new SpecStore(db).setContent("auth", "written after the conflict was recorded", "");
    var opened = new ArrayList<Path>();

    var stale = run(resolve(mergingTitle(opened, () -> {})), "auth", "--type", "spec", "--merge");

    assertEquals(1, stale.exit());
    assertEquals(409, assertInstanceOf(ApiException.class, stale.escaped()).status());
    assertEquals(List.of(), opened);
    assertEquals(2, conflicts.pending().size());
  }

  @Test
  void aMergeSavedInTheEditorSettlesTheSpecAndLeavesNoFileBehind() {
    parkASpecAndItsRoomUnderOneId();
    var opened = new ArrayList<Path>();

    var saved = run(resolve(mergingTitle(opened, () -> {})), "auth", "--type", "spec", "--merge");

    assertEquals(0, saved.exit(), saved.err());
    assertTrue(saved.out().contains("Resolved"), saved.out());
    assertEquals("merged title", title());
    assertFalse(Files.exists(opened.getFirst()));
    assertEquals(
        List.of("room"),
        conflicts.pending().stream().map(SyncConflicts.Conflict::entityType).toList());
  }

  @Test
  void aMergeRefusedAfterTheEditorIsKeptAndItsPathPrintedBesideTheError() throws IOException {
    parkASpecAndItsRoomUnderOneId();
    var opened = new ArrayList<Path>();

    var refused =
        run(
            resolve(mergingTitle(opened, () -> park("spec", "title", "main's title, revised"))),
            "auth",
            "--type",
            "spec",
            "--merge");

    var kept = opened.getFirst();
    try {
      assertEquals(1, refused.exit());
      assertEquals(409, assertInstanceOf(ApiException.class, refused.escaped()).status());
      assertTrue(refused.err().contains("kept for reference at " + kept), refused.err());
      assertTrue(Files.readString(kept).contains("\ntitle: merged title\n"));
      assertEquals("node title", title());
      assertEquals(2, conflicts.pending().size());
    } finally {
      Files.deleteIfExists(kept);
    }
  }

  @Test
  void aMergeSavedInAnotherEncodingIsKeptAndItsPathPrinted() throws IOException {
    parkASpecAndItsRoomUnderOneId();
    var opened = new ArrayList<Path>();

    var unreadable =
        run(
            resolve(
                file -> {
                  opened.add(file);
                  Files.write(file, "title: café\n".getBytes(StandardCharsets.ISO_8859_1));
                  return 0;
                }),
            "auth",
            "--type",
            "spec",
            "--merge");

    var kept = opened.getFirst();
    try {
      assertEquals(1, unreadable.exit());
      assertInstanceOf(CharacterCodingException.class, unreadable.escaped());
      assertTrue(unreadable.err().contains("kept for reference at " + kept), unreadable.err());
      assertTrue(Files.exists(kept));
      assertEquals("node title", title());
      assertEquals(2, conflicts.pending().size());
    } finally {
      Files.deleteIfExists(kept);
    }
  }

  @Test
  void anEditorThatFailsAbortsTheMergeAndLeavesNoFileBehind() {
    parkASpecAndItsRoomUnderOneId();
    var opened = new ArrayList<Path>();

    var aborted =
        run(
            resolve(
                file -> {
                  opened.add(file);
                  return 1;
                }),
            "auth",
            "--type",
            "spec",
            "--merge");
    var unlaunched =
        run(
            resolve(
                file -> {
                  opened.add(file);
                  throw new IOException("Cannot run program \"nano\"");
                }),
            "auth",
            "--type",
            "spec",
            "--merge");

    assertEquals(1, aborted.exit());
    assertNull(aborted.escaped());
    assertTrue(aborted.err().contains("Editor exited non-zero; aborting."), aborted.err());
    assertEquals(1, unlaunched.exit());
    assertInstanceOf(IOException.class, unlaunched.escaped());
    assertEquals(2, opened.size());
    opened.forEach(file -> assertFalse(Files.exists(file), file.toString()));
    assertEquals(2, conflicts.pending().size());
    assertEquals("node title", title());
  }

  @Test
  void theEditorIsTheCommandItNamesRunOnTheTemplate() {
    parkASpecAndItsRoomUnderOneId();

    var aborted =
        run(
            resolve(ConflictsCommand.Resolve.Editor.command("false")),
            "auth",
            "--type",
            "spec",
            "--merge");
    var saved =
        run(
            resolve(ConflictsCommand.Resolve.Editor.command("true")),
            "auth",
            "--type",
            "spec",
            "--merge");

    assertEquals(1, aborted.exit());
    assertEquals(0, saved.exit(), saved.err());
    assertEquals("node title", title(), "a template saved untouched keeps mine on the clash");
    assertEquals(
        List.of("room"),
        conflicts.pending().stream().map(SyncConflicts.Conflict::entityType).toList());
  }

  @Test
  void theHelpPointsAMergeFileAtTheTemplateShowPrints() {
    var verbs = new CommandLine(new ConflictsCommand()).getSubcommands();

    var template = verbs.get("show").getCommandSpec().findOption("--template");
    var mergeFile = verbs.get("resolve").getCommandSpec().findOption("--merge-file");

    assertTrue(template.description()[0].contains("'resolve --merge-file'"));
    assertTrue(mergeFile.description()[0].contains("made by 'show --template'"));
  }

  @Test
  void aVerbAimedAtNothingParkedSaysSoAndFails() {
    assertEquals(1, run(show(), "ghost").exit());
    assertEquals(
        "No open conflict for 'ghost'.", run(show(), "ghost", "--template").escaped().getMessage());
    assertEquals(1, run(resolve(), "ghost", "--mine").exit());
    assertEquals(
        1, run(resolve(), "ghost", "--mine", "--theirs").exit(), "two strategies are no decision");
  }
}
