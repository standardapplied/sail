/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.sail.api.ApiException;
import ai.singlr.sail.api.Resolution;
import ai.singlr.sail.common.DateTimeUtils;
import ai.singlr.sail.config.YamlUtil;
import ai.singlr.sail.identity.Acting;
import ai.singlr.sail.identity.ActingAs;
import ai.singlr.sail.identity.Actor;
import ai.singlr.sail.identity.Role;
import ai.singlr.sail.store.FdeStore;
import ai.singlr.sail.store.FileStore;
import ai.singlr.sail.store.RoomStore;
import ai.singlr.sail.store.RunStore;
import ai.singlr.sail.store.Sqlite;
import ai.singlr.sail.store.SyncConflicts;
import ai.singlr.sail.sync.ConflictMerge;
import ai.singlr.sail.sync.StoreReplica;
import ai.singlr.sail.sync.SyncBox;
import ai.singlr.sail.sync.SyncedEntities;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;

@ActingAs
class ConflictOperationsTest {

  @TempDir Path tempDir;
  private SyncBox main;
  private SyncBox node;
  private Map<String, StoreReplica> replicas;
  private ConflictOperations operations;

  @BeforeEach
  void setUp() {
    main = new SyncBox(tempDir, "main");
    node = new SyncBox(tempDir, "node");
    new FdeStore(main.db).add("node", null, null, "admin");
    replicas = SyncedEntities.replicas(node.db, node.id, node.id);
    operations = new ConflictOperations(node.db);
  }

  @AfterEach
  void tearDown() {
    node.close();
    main.close();
  }

  private void round() throws IOException {
    try (var link =
        SyncBox.connect(
            Acting.by(
                Actor.sync("node", Role.MEMBER),
                () -> main.server(Actor.sync("node", Role.MEMBER))),
            node)) {
      for (var type : List.of("spec", "room")) {
        link.reconcile(type, replicas.get(type));
      }
    }
  }

  private SyncConflicts.Conflict parkTitle() throws IOException {
    return Acting.system(
        () -> {
          main.specs.create(SyncBox.spec("auth", "base title", "pending"));
          round();
          main.specs.update(SyncBox.spec("auth", "main title", "pending"));
          node.specs.update(SyncBox.spec("auth", "node title", "pending"));
          round();
          return node.conflicts.pendingFor("spec", "auth").orElseThrow();
        });
  }

  private Resolution resolution(Resolution.Strategy strategy) {
    return strategy == Resolution.Strategy.MERGE
        ? mergedTitle(operations.mergeTemplate("spec", "auth"))
        : new Resolution(strategy, null);
  }

  private static Resolution mergedTitle(String template) {
    var merged = new LinkedHashMap<>(ConflictMerge.parseTemplate(template));
    merged.put("title", "merged title");
    return new Resolution(Resolution.Strategy.MERGE, YamlUtil.dumpToString(merged));
  }

  private static Resolution mine() {
    return new Resolution(Resolution.Strategy.MINE, null);
  }

  private static Resolution theirs() {
    return new Resolution(Resolution.Strategy.THEIRS, null);
  }

  private String bodyOf(SyncBox box) {
    return box.specs.getContent("auth").orElseThrow().body();
  }

  private String titleOf(SyncBox box) {
    return box.specs.findById("auth").orElseThrow().title();
  }

  @ParameterizedTest
  @EnumSource(Resolution.Strategy.class)
  void aLocalEditAfterDetectionRefusesTheResolveAndTouchesNothing(Resolution.Strategy strategy)
      throws IOException {
    var conflict = parkTitle();
    var decided = resolution(strategy);
    node.specs.setContent("auth", "written after the conflict was recorded", "");
    var rev = node.specs.revOf("auth");

    var refused =
        assertThrows(ApiException.class, () -> operations.resolve("spec", "auth", decided));

    assertEquals(409, refused.status());
    assertEquals(
        """
        'auth' changed on this box after this conflict was recorded (body).
        Run 'sail sync' to refresh it, then %s."""
            .formatted(strategy == Resolution.Strategy.MERGE ? "start the merge again" : "resolve"),
        refused.getMessage());
    assertEquals("written after the conflict was recorded", bodyOf(node));
    assertEquals("node title", titleOf(node));
    assertEquals(rev, node.specs.revOf("auth"));
    assertEquals(conflict, node.conflicts.pendingFor("spec", "auth").orElseThrow());
  }

  @Test
  void aBodyClashMergesThroughItsTemplateWhateverLineBreaksMainsBodyHas() throws IOException {
    main.specs.create(SyncBox.spec("auth", "title", "pending"));
    round();
    main.specs.setContent("auth", "main: first\r\nkey: value\rlast", "");
    node.specs.setContent("auth", "node line one\nnode line two", "");
    round();
    var template = operations.mergeTemplate("spec", "auth");
    assertTrue(template.contains("#   body: theirs = main: first\n#     key: value\n#     last\n"));
    var merged = new LinkedHashMap<>(ConflictMerge.parseTemplate(template));
    assertEquals("node line one\nnode line two", merged.get("body"));
    merged.put("body", "merged\nbody");

    operations.resolve(
        "spec", "auth", new Resolution(Resolution.Strategy.MERGE, YamlUtil.dumpToString(merged)));
    round();

    assertEquals(List.of(), node.conflicts.pending());
    assertEquals("merged\nbody", bodyOf(main));
    assertEquals("merged\nbody", bodyOf(node));
  }

  @Test
  void aTemplateNamesItsConflictAsTextNoYamlToolReadsAsANumber() throws IOException {
    parkTitle();

    var named = ConflictMerge.parseTemplate(operations.mergeTemplate("spec", "auth"));

    assertTrue(
        named.get(ConflictMerge.CONFLICT) instanceof String text
            && text.matches("sha256:[0-9a-f]{16}"),
        String.valueOf(named.get(ConflictMerge.CONFLICT)));
  }

  @Test
  void aReRecordThatChangesOnlyWhoWroteASideKeepsTheMergeValid() throws IOException {
    parkTitle();
    var started = mergedTitle(operations.mergeTemplate("spec", "auth"));
    node.db.execute(
        "UPDATE sync_conflicts SET remote_snapshot = json_set(remote_snapshot, '$._actor',"
            + " 'someone else') WHERE entity_id = 'auth' AND status = 'pending'");

    operations.resolve("spec", "auth", started);

    assertEquals(List.of(), node.conflicts.pending());
    assertEquals("merged title", titleOf(node));
  }

  @Test
  void aConflictThisBoxHasWrittenOverIsRefusedATemplateBeforeAnyoneMergesIt() throws IOException {
    parkTitle();
    node.specs.setContent("auth", "written after the conflict was recorded", "");

    var refused = assertThrows(ApiException.class, () -> operations.mergeTemplate("spec", "auth"));

    assertEquals(409, refused.status());
    assertTrue(
        refused.getMessage().contains("Run 'sail sync' to refresh it"), refused.getMessage());
  }

  @Test
  void onlyAnOpenSpecConflictWithBothSidesPresentHasATemplate() throws IOException {
    main.specs.create(SyncBox.spec("auth", "base title", "pending"));
    new RoomStore(main.db).ensureFor("auth", "proj", "Auth", "uday", "mention");
    round();
    new RoomStore(main.db).updateWake("auth", "on");
    new RoomStore(node.db).updateWake("auth", "off");
    main.specs.delete("auth");
    node.specs.update(SyncBox.spec("auth", "node title", "pending"));
    round();

    for (var type : List.of("room", "spec")) {
      var refused =
          assertThrows(
              IllegalArgumentException.class, () -> operations.mergeTemplate(type, "auth"));
      assertEquals(
          "Field-level --merge isn't available for this conflict; use --mine or --theirs.",
          refused.getMessage(),
          type);
    }
    var ghost = assertThrows(ApiException.class, () -> operations.mergeTemplate(null, "ghost"));
    assertEquals(404, ghost.status());
    assertEquals("No open conflict for 'ghost'.", ghost.getMessage());
  }

  @Test
  void aMergeStartedBeforeARoundBroughtMainsNewsIsRefusedAndMainKeepsIt() throws IOException {
    parkTitle();
    var started = mergedTitle(operations.mergeTemplate("spec", "auth"));
    main.specs.setContent("auth", "main wrote this while the editor was open", "");
    round();
    var parked = node.conflicts.pendingFor("spec", "auth").orElseThrow();
    var rev = node.specs.revOf("auth");

    var refused =
        assertThrows(ApiException.class, () -> operations.resolve("spec", "auth", started));

    assertEquals(409, refused.status());
    assertEquals(
        "'auth' was re-recorded after this merge was started. Start again from the fresh version.",
        refused.getMessage());
    assertEquals(parked, node.conflicts.pendingFor("spec", "auth").orElseThrow());
    assertEquals(rev, node.specs.revOf("auth"));
    round();
    assertEquals("main wrote this while the editor was open", bodyOf(main));
    assertEquals("main title", titleOf(main));
  }

  @Test
  void aMergeStartedAfterTheRoundSettlesOnTheMergedTitleAndMainsBody() throws IOException {
    parkTitle();
    main.specs.setContent("auth", "main wrote this while the editor was open", "");
    round();

    operations.resolve("spec", "auth", mergedTitle(operations.mergeTemplate("spec", "auth")));
    round();

    assertEquals(List.of(), node.conflicts.pending());
    for (var box : List.of(main, node)) {
      assertEquals("merged title", titleOf(box), box.id);
      assertEquals("main wrote this while the editor was open", bodyOf(box), box.id);
    }
  }

  @Test
  void aRoundThatBringsNoNewsReRecordsTheConflictButKeepsTheMergeValid() throws IOException {
    parkTitle();
    var started = mergedTitle(operations.mergeTemplate("spec", "auth"));
    var recorded = node.conflicts.pendingFor("spec", "auth").orElseThrow().id();
    round();
    assertNotEquals(recorded, node.conflicts.pendingFor("spec", "auth").orElseThrow().id());

    operations.resolve("spec", "auth", started);
    round();

    assertEquals(List.of(), node.conflicts.pending());
    assertEquals("merged title", titleOf(main));
    assertEquals("merged title", titleOf(node));
  }

  static Stream<Arguments> unboundMerges() {
    var unnamed = "A merged record must start from 'sail conflicts show auth --template'.";
    return Stream.of(
        Arguments.of(null, 400, unnamed),
        Arguments.of(" ", 400, unnamed),
        Arguments.of(7, 400, unnamed),
        Arguments.of(
            "0000000000000000",
            409,
            "'auth' was re-recorded after this merge was started. Start again from the fresh"
                + " version."));
  }

  @ParameterizedTest
  @MethodSource("unboundMerges")
  void aMergeThatDoesNotNameThisConflictIsRefusedAndTouchesNothing(
      Object madeFrom, int status, String message) throws IOException {
    parkTitle();
    var merged =
        new LinkedHashMap<>(ConflictMerge.parseTemplate(operations.mergeTemplate("spec", "auth")));
    merged.put("title", "merged title");
    merged.put(ConflictMerge.CONFLICT, madeFrom);
    var parked = node.conflicts.pendingFor("spec", "auth").orElseThrow();
    var rev = node.specs.revOf("auth");

    var refused =
        assertThrows(
            ApiException.class,
            () ->
                operations.resolve(
                    "spec",
                    "auth",
                    new Resolution(Resolution.Strategy.MERGE, YamlUtil.dumpJson(merged))));

    assertEquals(status, refused.status());
    assertEquals(message, refused.getMessage());
    assertEquals("node title", titleOf(node));
    assertEquals(rev, node.specs.revOf("auth"));
    assertEquals(parked, node.conflicts.pendingFor("spec", "auth").orElseThrow());
  }

  @Test
  void aMergesBindingNeverReachesTheRowTheChangeLogOrTheWire() throws IOException {
    parkTitle();

    operations.resolve("spec", "auth", mergedTitle(operations.mergeTemplate("spec", "auth")));
    String wire;
    try (var link =
        SyncBox.connect(
            Acting.by(
                Actor.sync("node", Role.MEMBER),
                () -> main.server(Actor.sync("node", Role.MEMBER))),
            node)) {
      link.reconcile("spec", replicas.get("spec"));
      wire = link.log().toString();
    }

    assertTrue(wire.contains("merged title"), wire);
    assertFalse(wire.contains(ConflictMerge.CONFLICT), wire);
    for (var box : List.of(main, node)) {
      assertEquals("merged title", titleOf(box), box.id);
      assertEquals(List.of(), rowsNaming(box, ConflictMerge.CONFLICT), box.id);
    }
  }

  private static List<String> rowsNaming(SyncBox box, String key) {
    var tables =
        box.db.query(
            "SELECT name FROM sqlite_master WHERE type = 'table' AND name NOT LIKE 'sqlite_%'",
            row -> row.text(0));
    var naming = new ArrayList<String>();
    for (var table : tables) {
      for (var row :
          box.db.query("SELECT * FROM \"" + table + "\"", ConflictOperationsTest::cells)) {
        if (row.stream()
            .anyMatch(cell -> cell.contains("\"" + key + "\"") || cell.contains(key + ":"))) {
          naming.add(table + ": " + row);
        }
      }
    }
    return naming;
  }

  private static List<String> cells(Sqlite.Row row) {
    return IntStream.range(0, row.columnCount())
        .mapToObj(column -> Objects.toString(row.text(column), ""))
        .toList();
  }

  @Test
  void onceARoundReRecordsTheConflictTheSameResolveSucceedsAndTheNewerEditIsPushed()
      throws IOException {
    parkTitle();
    node.specs.setContent("auth", "written after the conflict was recorded", "");
    assertThrows(ApiException.class, () -> operations.resolve("spec", "auth", mine()));

    round();
    operations.resolve("spec", "auth", mine());
    round();

    assertEquals(List.of(), node.conflicts.pending());
    assertEquals("node title", titleOf(main));
    assertEquals("written after the conflict was recorded", bodyOf(main));
    assertEquals("written after the conflict was recorded", bodyOf(node));
  }

  @Test
  void mainMovingADisjointFieldAfterDetectionMergesWithTheResolvedSide() throws IOException {
    parkTitle();
    main.specs.setContent("auth", "main wrote this after the node parked", "");

    operations.resolve("spec", "auth", mine());
    round();

    assertEquals(List.of(), node.conflicts.pending());
    for (var box : List.of(main, node)) {
      assertEquals("node title", titleOf(box), box.id);
      assertEquals("main wrote this after the node parked", bodyOf(box), box.id);
    }
  }

  @Test
  void mainMovingTheSameFieldAfterDetectionParksAgainWithFreshSnapshots() throws IOException {
    parkTitle();
    main.specs.update(SyncBox.spec("auth", "main title, revised", "pending"));

    operations.resolve("spec", "auth", mine());
    round();

    var parked = node.conflicts.pendingFor("spec", "auth").orElseThrow();
    assertEquals(List.of("title"), parked.fields());
    assertEquals("main title", ConflictOperations.parse(parked.baseSnapshot()).get("title"));
    assertEquals("node title", ConflictOperations.parse(parked.localSnapshot()).get("title"));
    assertEquals(
        "main title, revised", ConflictOperations.parse(parked.remoteSnapshot()).get("title"));
    assertEquals("main title, revised", titleOf(main));
    assertEquals("node title", titleOf(node));
  }

  @Test
  void takingMainsRecordedSideNeverHidesWhatMainWroteSince() throws IOException {
    parkTitle();
    main.specs.update(SyncBox.spec("auth", "main title, revised", "pending"));

    operations.resolve("spec", "auth", theirs());
    assertEquals("main title", titleOf(node));
    round();

    assertEquals(List.of(), node.conflicts.pending());
    assertEquals("main title, revised", titleOf(main));
    assertEquals("main title, revised", titleOf(node));
  }

  @Test
  void aSpecAndItsRoomParkedTogetherAreEachAddressedByType() throws IOException {
    main.specs.create(SyncBox.spec("auth", "base title", "pending"));
    new RoomStore(main.db).ensureFor("auth", "proj", "Auth", "uday", "mention");
    round();
    main.specs.update(SyncBox.spec("auth", "main title", "pending"));
    new RoomStore(main.db).updateWake("auth", "on");
    node.specs.update(SyncBox.spec("auth", "node title", "pending"));
    new RoomStore(node.db).updateWake("auth", "off");
    round();

    var ambiguous = assertThrows(ApiException.class, () -> operations.find(null, "auth"));
    assertEquals(400, ambiguous.status());
    assertEquals("'auth' has open conflicts as spec and room: pass --type", ambiguous.getMessage());
    assertEquals(
        ambiguous.getMessage(),
        assertThrows(ApiException.class, () -> operations.resolve(" ", "auth", mine()))
            .getMessage());
    assertEquals("spec", operations.find("spec", "auth").entityType());
    assertEquals("room", operations.find("room", "auth").entityType());
    assertNull(operations.find("file", "auth"));

    operations.resolve("spec", "auth", mine());
    assertEquals("room", operations.find(null, "auth").entityType());
    operations.resolve("room", "auth", theirs());

    assertEquals(List.of(), operations.list());
    assertEquals("node title", titleOf(node));
    assertEquals("on", new RoomStore(node.db).findById("auth").orElseThrow().wake());
  }

  @Test
  void aRunsHeartbeatStampingOnIsNotAChangeButItsLifecycleMovingIs() {
    var runs = new RunStore(node.db);
    var id = DateTimeUtils.newId().toString();
    runs.create(
        id,
        "backend",
        "auth",
        node.id,
        node.id,
        "build",
        "claude-code",
        "feat/auth",
        "do it",
        123,
        null,
        "/home/dev/.sail/runs/" + id + "/agent.log",
        "sail-agent-" + id);
    var recorded = replicas.get("run").current(id);
    var remote = new LinkedHashMap<>(recorded);
    remote.put("branch", "feat/elsewhere");
    replicas.get("run").recordConflict(id, null, recorded, remote, List.of("branch"));
    assertTrue(runs.stampActivity(id, Duration.ZERO));

    operations.resolve("run", id, mine());

    assertEquals(List.of(), operations.list());

    replicas
        .get("run")
        .recordConflict(id, null, replicas.get("run").current(id), remote, List.of("branch"));
    runs.complete(id, "completed", 0);

    var refused = assertThrows(ApiException.class, () -> operations.resolve("run", id, mine()));

    assertTrue(refused.getMessage().contains("status"), refused.getMessage());
    assertEquals("completed", runs.findById(id).orElseThrow().status());
  }

  @Test
  void aFileConflictResolvesThroughTheFileStoreByItsIdAlone() {
    var files = new FileStore(node.db);
    ai.singlr.sail.store.ContentFixtures.put(files, "acme", "x.txt", "mine");
    var local = files.comparableSnapshot("acme/x.txt");
    var remote = new LinkedHashMap<>(local);
    remote.put("content_hash", new ai.singlr.sail.store.BlobStore(node.db).putText("theirs"));
    node.conflicts.record(
        "file",
        "acme/x.txt",
        null,
        YamlUtil.dumpJson(local),
        YamlUtil.dumpJson(remote),
        List.of("content"));

    operations.resolve(null, "acme/x.txt", theirs());

    assertEquals(List.of(), operations.list());
    assertEquals(
        b64("theirs"), ai.singlr.sail.store.ContentFixtures.encoded(files, "acme", "x.txt"));
  }

  @Test
  void resolvingWhatIsNotParkedIsNotFound() {
    assertNull(operations.find(null, "ghost"));
    var refused =
        assertThrows(ApiException.class, () -> operations.resolve("spec", "ghost", mine()));
    assertEquals(404, refused.status());
    assertEquals("No open conflict for 'ghost'.", refused.getMessage());
  }

  private static String b64(String text) {
    return Base64.getEncoder().encodeToString(text.getBytes(StandardCharsets.UTF_8));
  }
}
