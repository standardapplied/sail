/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.sync;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.sail.store.BlobStore;
import ai.singlr.sail.store.FastCdc;
import ai.singlr.sail.store.SchemaManager;
import ai.singlr.sail.store.Sqlite;
import java.io.ByteArrayInputStream;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Random;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ContentReceiverTest {

  @Test
  void aChunkTheStoreCannotKeepFailsAsAStoreFailureNamingTheChunk() {
    var chunk = new byte[FastCdc.MIN];
    new Random(7).nextBytes(chunk);
    var hash = BlobStore.hash(chunk);
    var db = Sqlite.openMemory();
    new SchemaManager(db).migrate();
    var blobs = new BlobStore(db);
    db.close();
    var lines =
        new ArrayDeque<SyncWire.Content>(
            List.of(new SyncWire.Chunk(hash, chunk.length), new SyncWire.Done()));
    var receiver =
        new ContentReceiver(new ByteArrayInputStream(chunk), lines::poll, blobs, "file blob x");

    var failure =
        assertThrows(
            SyncTransportException.class, () -> receiver.chunks(Set.of(hash), chunk.length));

    assertEquals("store", failure.kind());
    assertTrue(failure.getMessage().startsWith("file blob x, chunk " + hash), failure.getMessage());
  }
}
