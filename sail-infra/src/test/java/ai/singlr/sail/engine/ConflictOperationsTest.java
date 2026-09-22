/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.sail.api.ApiException;
import ai.singlr.sail.api.Resolution;
import ai.singlr.sail.common.DateTimeUtils;
import ai.singlr.sail.config.YamlUtil;
import ai.singlr.sail.store.FdeStore;
import ai.singlr.sail.store.FileStore;
import ai.singlr.sail.store.RoomStore;
import ai.singlr.sail.store.RunStore;
import ai.singlr.sail.store.SyncConflicts;
import ai.singlr.sail.sync.StoreReplica;
import ai.singlr.sail.sync.SyncBox;
import ai.singlr.sail.sync.SyncPrincipal;
import ai.singlr.sail.sync.SyncedEntities;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

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
    try (var link = SyncBox.connect(main.server(new SyncPrincipal("node", true)), node)) {
      for (var type : List.of("spec", "room")) {
        link.reconcile(type, replicas.get(type));
      }
    }
  }

  private SyncConflicts.Conflict parkTitle() throws IOException {
    main.specs.create(SyncBox.spec("auth", "base title", "pending"));
    round();
    main.specs.update(SyncBox.spec("auth", "main title", "pending"));
    node.specs.update(SyncBox.spec("auth", "node title", "pending"));
    round();
    return node.conflicts.pendingFor("spec", "auth").orElseThrow();
  }

  private static Resolution resolution(
      Resolution.Strategy strategy, SyncConflicts.Conflict conflict) {
    if (strategy != Resolution.Strategy.MERGE) {
      return new Resolution(strategy, null);
    }
    var merged = new LinkedHashMap<>(ConflictOperations.parse(conflict.localSnapshot()));
    merged.put("title", "merged title");
    return new Resolution(strategy, YamlUtil.dumpJson(merged));
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
    node.specs.setContent("auth", "written after the conflict was recorded", "");
    var rev = node.specs.revOf("auth");

    var refused =
        assertThrows(
            ApiException.class,
            () -> operations.resolve("spec", "auth", resolution(strategy, conflict)));

    assertEquals(409, refused.status());
    assertEquals(
        """
        'auth' changed on this box after this conflict was recorded (body).
        Run 'sail sync' to refresh it, then resolve.""",
        refused.getMessage());
    assertEquals("written after the conflict was recorded", bodyOf(node));
    assertEquals("node title", titleOf(node));
    assertEquals(rev, node.specs.revOf("auth"));
    assertEquals(conflict, node.conflicts.pendingFor("spec", "auth").orElseThrow());
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
    new RoomStore(main.db).ensureFor("auth", "proj", "Auth", "uday", "mention", "uday");
    round();
    main.specs.update(SyncBox.spec("auth", "main title", "pending"));
    new RoomStore(main.db).updateWake("auth", "on", "uday");
    node.specs.update(SyncBox.spec("auth", "node title", "pending"));
    new RoomStore(node.db).updateWake("auth", "off", "uday");
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
  void resolvingWhatIsNotParkedIsRefused() {
    assertNull(operations.find(null, "ghost"));
    assertThrows(IllegalArgumentException.class, () -> operations.resolve("spec", "ghost", mine()));
  }

  private static String b64(String text) {
    return Base64.getEncoder().encodeToString(text.getBytes(StandardCharsets.UTF_8));
  }
}
