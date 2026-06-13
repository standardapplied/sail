/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.sync;

import static org.junit.jupiter.api.Assertions.*;

import ai.singlr.sail.config.SpecStatus;
import ai.singlr.sail.store.SpecStore;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.PipedReader;
import java.io.PipedWriter;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The two-node harness from {@code TwoNodeSyncTest}, re-run through the real {@link SyncWire}
 * protocol: each node's engine drives a {@link RemoteMainReplica} over a byte pipe served by a
 * {@link SyncRpcServer} on a virtual thread. The pipe stands in for the SSH channel, so this proves
 * the serialization and the request loop converge exactly as the in-process engine does — and that
 * the write gate refuses a read-only push.
 */
class SyncTransportTest {

  @TempDir Path tempDir;
  private final SyncEngine engine = new SyncEngine();

  private SyncBox main;
  private SyncBox nodeA;
  private SyncBox nodeB;

  @BeforeEach
  void setUp() {
    main = new SyncBox(tempDir, "main");
    nodeA = new SyncBox(tempDir, "A");
    nodeB = new SyncBox(tempDir, "B");
  }

  @AfterEach
  void tearDown() {
    nodeB.close();
    nodeA.close();
    main.close();
  }

  private SpecStore.SpecRow spec(String id, String title, String status) {
    return SyncBox.spec(id, title, status);
  }

  private SyncEngine.Report syncToMain(SyncBox node) throws Exception {
    return syncOverWire(node, true);
  }

  @Test
  void aNodePullsMainsFdeRosterOverTheSameSession() throws Exception {
    var roster =
        java.util.List.<java.util.Map<String, Object>>of(
            java.util.Map.of("handle", "ada", "role", "admin"));
    var toServer = new PipedWriter();
    var serverIn = new BufferedReader(new PipedReader(toServer));
    var toClient = new PipedWriter();
    var clientIn = new BufferedReader(new PipedReader(toClient));
    var serverThread =
        Thread.ofVirtual()
            .start(
                () -> {
                  try {
                    new SyncRpcServer(main.replica, true, () -> roster).serve(serverIn, toClient);
                  } catch (IOException e) {
                    throw new UncheckedIOException(e);
                  }
                });

    try (var remote = new RemoteMainReplica(clientIn, toServer)) {
      engine.reconcile(nodeA.replica, remote);
      var pulled = remote.fetchFdes();
      assertEquals(1, pulled.size());
      assertEquals("ada", pulled.getFirst().get("handle"));
    } finally {
      serverThread.join();
    }
  }

  private SyncEngine.Report syncOverWire(SyncBox node, boolean writable) throws Exception {
    var toServer = new PipedWriter();
    var serverIn = new BufferedReader(new PipedReader(toServer));
    var toClient = new PipedWriter();
    var clientIn = new BufferedReader(new PipedReader(toClient));

    var server = new SyncRpcServer(main.replica, writable);
    var serverThread =
        Thread.ofVirtual()
            .start(
                () -> {
                  try {
                    server.serve(serverIn, toClient);
                  } catch (IOException e) {
                    throw new UncheckedIOException(e);
                  }
                });

    try (var remote = new RemoteMainReplica(clientIn, toServer)) {
      return engine.reconcile(node.replica, remote);
    } finally {
      serverThread.join();
    }
  }

  @Test
  void aLocalCreatePropagatesAcrossTheWire() throws Exception {
    nodeA.specs.create(spec("auth", "Auth", "pending"));

    var pushed = syncToMain(nodeA);
    assertEquals(1, pushed.pushed());
    assertEquals("Auth", main.specs.findById("auth").orElseThrow().title());

    var pulled = syncToMain(nodeB);
    assertEquals(1, pulled.pulled());
    assertEquals("Auth", nodeB.specs.findById("auth").orElseThrow().title());
  }

  @Test
  void disjointEditsAutoMergeOverTheWire() throws Exception {
    nodeA.specs.create(spec("auth", "Auth", "pending"));
    syncToMain(nodeA);
    syncToMain(nodeB);

    nodeA.specs.updateStatus("auth", SpecStatus.fromWire("in_progress"));
    nodeB.specs.setContent("auth", "node B body", "");

    syncToMain(nodeA);
    syncToMain(nodeB);
    syncToMain(nodeA);

    assertEquals("in_progress", nodeA.specs.findById("auth").orElseThrow().status().wire());
    assertEquals("in_progress", nodeB.specs.findById("auth").orElseThrow().status().wire());
    assertEquals("node B body", nodeA.specs.getContent("auth").orElseThrow().body());
    assertTrue(nodeA.conflicts.pending().isEmpty());
    assertTrue(nodeB.conflicts.pending().isEmpty());
  }

  @Test
  void sameFieldEditConflictsOverTheWireAndLeavesLocalWorkUntouched() throws Exception {
    nodeA.specs.create(spec("auth", "Auth", "pending"));
    syncToMain(nodeA);
    syncToMain(nodeB);

    nodeA.specs.update(spec("auth", "Title from A", "pending"));
    nodeB.specs.update(spec("auth", "Title from B", "pending"));

    syncToMain(nodeA);
    var report = syncToMain(nodeB);

    assertEquals(1, report.conflicts());
    assertEquals("Title from A", main.specs.findById("auth").orElseThrow().title());
    var pending = nodeB.conflicts.pending();
    assertEquals(List.of("title"), pending.getFirst().fields());
    assertEquals("Title from B", nodeB.specs.findById("auth").orElseThrow().title());
  }

  @Test
  void aLocalDeletePropagatesAcrossTheWire() throws Exception {
    nodeA.specs.create(spec("auth", "Auth", "pending"));
    syncToMain(nodeA);
    syncToMain(nodeB);

    nodeA.specs.delete("auth");
    syncToMain(nodeA);
    assertTrue(main.specs.findById("auth").isEmpty());

    syncToMain(nodeB);
    assertTrue(nodeB.specs.findById("auth").isEmpty());
  }

  @Test
  void deleteVersusEditConflictsOverTheWire() throws Exception {
    nodeA.specs.create(spec("auth", "Auth", "pending"));
    syncToMain(nodeA);
    syncToMain(nodeB);

    nodeA.specs.delete("auth");
    nodeB.specs.update(spec("auth", "Edited by B", "pending"));

    syncToMain(nodeA);
    var report = syncToMain(nodeB);

    assertEquals(1, report.conflicts());
    assertTrue(main.specs.findById("auth").isEmpty());
    assertEquals(List.of("<deleted>"), nodeB.conflicts.pending().getFirst().fields());
  }

  @Test
  void reSyncOverTheWireConvergesAndAdvancesTheCheckpoint() throws Exception {
    nodeA.specs.create(spec("auth", "Auth", "pending"));
    syncToMain(nodeA);

    assertEquals(main.replica.maxSeq(), nodeA.syncState.checkpoint("main"));
    assertTrue(main.replica.maxSeq() > 0);

    var second = syncToMain(nodeA);
    assertEquals(0, second.total());
  }

  @Test
  void aReadOnlyFdeMayPullButItsPushIsRefused() throws Exception {
    main.specs.create(spec("board", "Shared", "pending"));
    var pull = syncOverWire(nodeA, false);
    assertEquals(1, pull.pulled());
    assertEquals("Shared", nodeA.specs.findById("board").orElseThrow().title());

    nodeA.specs.create(spec("mine", "Local only", "pending"));
    assertThrows(SyncTransportException.class, () -> syncOverWire(nodeA, false));
    assertTrue(main.specs.findById("mine").isEmpty(), "the read-only push never reached main");
  }

  @Test
  void aStalePushIsRejectedByMainAndReReconciledIntoAConflict() throws Exception {
    nodeA.specs.create(spec("auth", "Auth", "pending"));
    syncToMain(nodeA);
    syncToMain(nodeB);

    nodeA.specs.update(spec("auth", "Title from A", "pending"));
    nodeB.specs.update(spec("auth", "Title from B", "pending"));

    var toServer = new PipedWriter();
    var serverIn = new BufferedReader(new PipedReader(toServer));
    var toClient = new PipedWriter();
    var clientIn = new BufferedReader(new PipedReader(toClient));
    var serverThread =
        Thread.ofVirtual()
            .start(
                () -> {
                  try {
                    new SyncRpcServer(main.replica, true).serve(serverIn, toClient);
                  } catch (IOException e) {
                    throw new UncheckedIOException(e);
                  }
                });

    try (var remoteA = new RemoteMainReplica(clientIn, toServer)) {
      remoteA.entityIds();
      syncToMain(nodeB);
      var report = engine.reconcile(nodeA.replica, remoteA);
      assertEquals(1, report.conflicts());
    } finally {
      serverThread.join();
    }

    assertEquals("Title from B", main.specs.findById("auth").orElseThrow().title());
    assertEquals(
        List.of("title"), nodeA.conflicts.pendingFor("spec", "auth").orElseThrow().fields());
    assertEquals("Title from A", nodeA.specs.findById("auth").orElseThrow().title());
  }
}
