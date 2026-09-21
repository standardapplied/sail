/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.sync;

import static org.junit.jupiter.api.Assertions.assertEquals;

import ai.singlr.sail.store.ChangeLog;
import ai.singlr.sail.store.FdeStore;
import ai.singlr.sail.store.MessageStore;
import ai.singlr.sail.store.SyncConflicts;
import ai.singlr.sail.store.SyncState;
import java.io.IOException;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A reply is only valid once its parent exists, so a page or a push batch must reach the store in
 * the order main or the node produced it. Both directions run over the protocol-4 pipe.
 */
class ReplyChainSyncTest {

  private static final int CHAIN = 30;

  @TempDir Path tempDir;
  private SyncBox main;
  private SyncBox node;
  private MessageStore mainMessages;
  private MessageStore nodeMessages;
  private StoreReplica nodeReplica;

  @BeforeEach
  void setUp() {
    main = new SyncBox(tempDir, "main");
    node = new SyncBox(tempDir, "node");
    for (var box : new SyncBox[] {main, node}) {
      box.db.execute(
          """
          INSERT INTO rooms (id, title, project, created_at, updated_at)
          VALUES ('room', 'Room', 'acme', 'now', 'now')""");
    }
    new FdeStore(main.db).add("node", null, null, "admin");
    mainMessages = new MessageStore(main.db);
    nodeMessages = new MessageStore(node.db);
    nodeReplica =
        new StoreReplica(
            node.id,
            nodeMessages,
            new ChangeLog(node.db),
            new SyncConflicts(node.db),
            new SyncState(node.db));
  }

  @AfterEach
  void tearDown() {
    node.close();
    main.close();
  }

  private SyncBox.Link connect() throws IOException {
    return SyncBox.connect(main.server(new SyncPrincipal("node", true)), node);
  }

  private static void chain(MessageStore messages) {
    String parent = null;
    for (var i = 0; i < CHAIN; i++) {
      parent = messages.append("room", "node", "message " + i, parent).id();
    }
  }

  @Test
  void aReplyChainPullsIntoAnEmptyNodeInOneRound() throws Exception {
    chain(mainMessages);
    try (var link = connect()) {
      var report = link.reconcile("message", nodeReplica);
      assertEquals(CHAIN, report.report().pulled());
    }
    assertEquals(CHAIN, nodeMessages.list("room", null, CHAIN + 1).size());
    assertEquals(new ChangeLog(main.db).maxSeq("message"), nodeReplica.checkpoint("main"));
  }

  @Test
  void aReplyChainPushesFromANodeInOneRound() throws Exception {
    chain(nodeMessages);
    try (var link = connect()) {
      var report = link.reconcile("message", nodeReplica);
      assertEquals(CHAIN, report.report().pushed());
    }
    assertEquals(CHAIN, mainMessages.list("room", null, CHAIN + 1).size());
  }
}
