/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */
package ai.singlr.sail.sync;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.sail.config.YamlUtil;
import ai.singlr.sail.identity.ActingAs;
import ai.singlr.sail.identity.Actor;
import ai.singlr.sail.identity.Role;
import ai.singlr.sail.store.BlobStore;
import ai.singlr.sail.store.FileStore;
import java.io.ByteArrayInputStream;
import java.io.FilterInputStream;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

@ActingAs
class SyncContentFailureTest {
  @Test
  void aReadFailureInsideAChunkNamesTheHashAndStoresNothing() throws Exception {
    try (var main = new SyncBox("main");
        var node = new SyncBox("node")) {
      var files = new FileStore(main.db);
      files.put("project", "file", new ByteArrayInputStream(new byte[] {1, 2, 3}), 0644);
      var hash = files.find("project", "file").orElseThrow().contentHash();
      var failure =
          assertThrows(
              SyncTransportException.class,
              () -> {
                try (var link =
                    SyncBox.connect(
                        main.server(Actor.sync("node", Role.MEMBER)),
                        node.db,
                        node.id,
                        SyncWire.MAX_FRAME,
                        output -> output,
                        output -> output,
                        input ->
                            new FilterInputStream(input) {
                              @Override
                              public int read(byte[] bytes, int offset, int length)
                                  throws IOException {
                                throw new IOException("link reset");
                              }
                            })) {
                  link.reconcile(
                      "file", SyncedEntities.replicas(node.db, node.id, node.id).get("file"));
                }
              });
      assertEquals("unreachable", failure.kind());
      assertTrue(failure.getMessage().contains(hash));
      assertTrue(failure.getMessage().contains("link reset"));
      assertEquals(
          0L, node.db.queryOne("SELECT COUNT(*) FROM chunks", row -> row.integer(0)).orElseThrow());
      assertEquals(0L, node.syncState.checkpoint("main", "file"));
    }
  }

  enum AnswerFault {
    BAD_BLOB_INVENTORY,
    BAD_CHUNK_INVENTORY,
    WRONG_RESULTS,
    MISSING_RESULTS,
    WRONG_RESULT_ID,
    REFUSED_RESULT,
    BAD_HEADS,
    UNKNOWN_FAILURE_KIND,
    NEED_NO_PROGRESS
  }

  @ParameterizedTest
  @EnumSource(AnswerFault.class)
  void anInvalidAnswerCannotAcknowledgeTheNodesPendingWrite(AnswerFault fault) throws Exception {
    try (var main = new SyncBox("main");
        var node = new SyncBox("node")) {
      var files = new FileStore(node.db);
      files.put("project", "file", new ByteArrayInputStream(new byte[] {1, 2, 3}), 0644);
      var revision = files.latestRev("project/file");
      var failure =
          assertThrows(
              SyncTransportException.class,
              () -> {
                try (var link =
                    SyncBox.connect(
                        main.server(Actor.sync("node", Role.MEMBER)),
                        node,
                        SyncWire.MAX_FRAME,
                        output -> corruptAnswer(output, fault))) {
                  link.reconcile(
                      "file", SyncedEntities.replicas(node.db, "node", "node").get("file"));
                }
              });
      assertEquals(fault == AnswerFault.REFUSED_RESULT ? "refused" : "protocol", failure.kind());
      assertEquals(revision, files.latestRev("project/file"));
      assertTrue(files.dirtyIds().contains("project/file"));
      assertEquals(0, node.syncState.checkpoint("main", "file"));
    }
  }

  private static OutputStream corruptAnswer(OutputStream output, AnswerFault fault) {
    return new FilterOutputStream(output) {
      private int inventory;

      @Override
      public void write(byte[] bytes, int offset, int length) throws IOException {
        var line = new String(bytes, offset, length, StandardCharsets.UTF_8);
        var message = new LinkedHashMap<>(YamlUtil.parseMap(line));
        var op = message.get("op");
        if ("lack".equals(op)) {
          inventory++;
          if (inventory == 1 && fault == AnswerFault.BAD_BLOB_INVENTORY
              || inventory == 2 && fault == AnswerFault.BAD_CHUNK_INVENTORY)
            message.put("hashes", List.of(BlobStore.hash(new byte[] {9})));
        }
        if ("tips".equals(op) && fault == AnswerFault.BAD_HEADS) message.put("op", "done");
        if ("tips".equals(op) && fault == AnswerFault.UNKNOWN_FAILURE_KIND) {
          message.put("op", "failed");
          message.put("kind", "future-kind");
          message.put("message", "unknown peer failure");
        }
        if ("page".equals(op) && fault == AnswerFault.NEED_NO_PROGRESS) message.put("next", 0);
        if ("results".equals(op)) {
          switch (fault) {
            case WRONG_RESULTS -> message.put("op", "done");
            case MISSING_RESULTS -> message.put("results", List.of());
            case WRONG_RESULT_ID ->
                message.put(
                    "results", List.of(Map.of("id", "wrong", "accepted", Map.of("rev", "1-main"))));
            case REFUSED_RESULT ->
                message.put(
                    "results",
                    List.of(Map.of("id", "project/file", "refused", Map.of("reason", "policy"))));
            default -> {}
          }
        }
        out.write(YamlUtil.dumpJson(message).getBytes(StandardCharsets.UTF_8));
      }
    };
  }

  enum Fault {
    MISSING_MANIFEST,
    WRONG_MANIFEST,
    DUPLICATE_MANIFEST,
    MISSING_CHUNK,
    WRONG_CHUNK,
    OVERSIZED_CHUNK,
    CORRUPT_CHUNK,
    WRONG_WHOLE_HASH,
    MALFORMED_RESPONSE,
    UNEXPECTED_MANIFEST_RESPONSE,
    UNEXPECTED_CHUNK_RESPONSE
  }

  @ParameterizedTest
  @EnumSource(Fault.class)
  void invalidUploadsNeverPublishAFileOrUnverifiedContent(Fault fault) throws Exception {
    try (var main = new SyncBox("main");
        var node = new SyncBox("node")) {
      var files = new FileStore(node.db);
      files.put("project", "file", new ByteArrayInputStream(new byte[] {1, 2, 3}), 0644);
      var hash = files.find("project", "file").orElseThrow().contentHash();
      var other = files.blobs().put(new ByteArrayInputStream(new byte[] {7, 8, 9}));
      assertThrows(
          RuntimeException.class,
          () -> {
            try (var link =
                SyncBox.connect(
                    main.server(Actor.sync("node", Role.MEMBER)),
                    node,
                    SyncWire.MAX_FRAME,
                    output -> output,
                    output -> corrupt(output, fault, other))) {
              link.reconcile("file", SyncedEntities.replicas(node.db, "node", "node").get("file"));
            }
          });
      assertTrue(new FileStore(main.db).list("project").isEmpty());
      assertFalse(new BlobStore(main.db).has(hash));
      main.db.query(
          "SELECT hash, bytes FROM chunks",
          row -> {
            assertEquals(row.text(0), BlobStore.hash(row.bytes(1)));
            return row.text(0);
          });
    }
  }

  @ParameterizedTest
  @EnumSource(Fault.class)
  void invalidContentCannotAdvanceTheCheckpointOrReachTheStore(Fault fault) throws Exception {
    try (var main = new SyncBox("main");
        var node = new SyncBox("node")) {
      var files = new FileStore(main.db);
      files.put("project", "file", new ByteArrayInputStream(new byte[] {1, 2, 3}), 0644);
      var hash = files.find("project", "file").orElseThrow().contentHash();
      var other = files.blobs().put(new ByteArrayInputStream(new byte[] {7, 8, 9}));
      var failure =
          assertThrows(
              SyncTransportException.class,
              () -> {
                try (var link =
                    SyncBox.connect(
                        main.server(Actor.sync("node", Role.MEMBER)),
                        node,
                        SyncWire.MAX_FRAME,
                        output -> corrupt(output, fault, other))) {
                  link.reconcile(
                      "file", SyncedEntities.replicas(node.db, "node", "node").get("file"));
                }
              });
      assertEquals("protocol", failure.kind());
      assertEquals(0, node.syncState.checkpoint("main", "file"));
      assertTrue(new FileStore(node.db).list("project").isEmpty());
      assertFalse(new BlobStore(node.db).has(hash));
      node.db.query(
          "SELECT hash, bytes FROM chunks",
          row -> {
            assertEquals(row.text(0), BlobStore.hash(row.bytes(1)));
            return row.text(0);
          });
    }
  }

  private static OutputStream corrupt(OutputStream output, Fault fault, String other) {
    return new FilterOutputStream(output) {
      private int raw;
      private boolean skipNewline;

      @Override
      public void write(int value) throws IOException {
        if (skipNewline && value == '\n') skipNewline = false;
        else out.write(value);
      }

      @Override
      public void write(byte[] bytes, int offset, int length) throws IOException {
        if (raw > 0) {
          raw -= length;
          if (fault == Fault.MISSING_CHUNK) return;
          if (fault == Fault.CORRUPT_CHUNK) {
            out.write(new byte[] {3, 2, 1});
            return;
          }
          out.write(bytes, offset, length);
          return;
        }
        var line = new String(bytes, offset, length, StandardCharsets.UTF_8);
        var message = new LinkedHashMap<>(YamlUtil.parseMap(line));
        var op = message.get("op");
        if ("manifest".equals(op)) {
          switch (fault) {
            case MISSING_MANIFEST -> {
              skipNewline = !line.endsWith("\n");
              return;
            }
            case WRONG_MANIFEST -> message.put("hash", other);
            case DUPLICATE_MANIFEST ->
                out.write((line.stripTrailing() + "\n").getBytes(StandardCharsets.UTF_8));
            case WRONG_WHOLE_HASH -> message.put("chunks", List.of(other));
            case MALFORMED_RESPONSE -> message = new LinkedHashMap<>(Map.of("op", "invalid"));
            case UNEXPECTED_MANIFEST_RESPONSE ->
                message = new LinkedHashMap<>(Map.of("op", "lack", "hashes", List.of()));
            default -> {}
          }
        }
        if ("chunk".equals(op)) {
          raw = ((Number) message.get("size")).intValue();
          switch (fault) {
            case MISSING_CHUNK -> {
              return;
            }
            case WRONG_CHUNK -> message.put("hash", other);
            case OVERSIZED_CHUNK -> message.put("size", raw + 1);
            case UNEXPECTED_CHUNK_RESPONSE ->
                message = new LinkedHashMap<>(Map.of("op", "lack", "hashes", List.of()));
            default -> {}
          }
        }
        out.write(YamlUtil.dumpJson(message).getBytes(StandardCharsets.UTF_8));
        if (line.endsWith("\n")) out.write('\n');
      }
    };
  }
}
