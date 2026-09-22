/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */
package ai.singlr.sail.sync;

import static org.junit.jupiter.api.Assertions.assertEquals;

import ai.singlr.sail.config.FileLimits;
import ai.singlr.sail.store.BlobStore;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The bytes main answers an upload that goes wrong, pinned line by line: a node reads the {@code
 * kind} to decide what to do next and shows the message to a person, so neither may drift when the
 * code behind them moves.
 */
class UploadFailureRepliesTest {

  private static final byte[] BYTES = {1, 2, 3};
  private static final String HASH = BlobStore.hash(BYTES);
  private static final String OTHER = BlobStore.hash(new byte[] {9});
  private static final SyncWire.Hello HELLO = SyncWire.Hello.of("0.45.0", "node-box");
  private static final SyncWire.Manifest MANIFEST =
      new SyncWire.Manifest(new BlobStore.Manifest(HASH, 3, List.of(HASH)));
  private static final SyncWire.Done DONE = new SyncWire.Done();
  private static final SyncWire.Chunk CHUNK = new SyncWire.Chunk(HASH, 3);

  @Test
  void manifestRunFailuresAreAnsweredByteForByte() throws Exception {
    assertReplies(
        List.of(new SyncWire.Chunk(HASH, 3)),
        new byte[0],
        failed("Expected manifest for blobs [" + HASH + "]", "protocol"));
    assertReplies(
        List.of(new SyncWire.Manifest(new BlobStore.Manifest(OTHER, 1, List.of(OTHER)))),
        new byte[0],
        failed("Unexpected blob manifest " + OTHER, "protocol"));
    assertReplies(
        List.of(MANIFEST, MANIFEST),
        new byte[0],
        failed("Unexpected blob manifest " + HASH, "protocol"));
    assertReplies(
        List.of(DONE),
        new byte[0],
        failed("Missing manifests for blobs [" + HASH + "]", "protocol"));
    assertReplies(List.of(), new byte[0], failed("Channel closed during upload", "unreachable"));
    assertReplies(
        List.of(new SyncWire.Heads()),
        new byte[0],
        failed("Expected content, got heads", "protocol"));
  }

  @Test
  void anOversizedBlobIsRefusedNamingTheCapAndWhereToRaiseIt() throws Exception {
    assertReplies(
        new FileLimits(2),
        List.of(MANIFEST, DONE),
        new byte[0],
        List.of(
            new SyncWire.Refuse(
                "blob "
                    + HASH
                    + ": File of 3 bytes exceeds limits.file_max (2 bytes); raise limits.file_max in host.yaml to share it")));
  }

  @Test
  void chunkRunFailuresAreAnsweredByteForByte() throws Exception {
    var lack = new SyncWire.Lack(List.of(HASH));
    assertReplies(
        List.of(MANIFEST, DONE, MANIFEST),
        new byte[0],
        List.of(lack, failed("Expected chunk for blobs [" + HASH + "]", "protocol")));
    assertReplies(
        List.of(MANIFEST, DONE, new SyncWire.Chunk(OTHER, 1)),
        new byte[] {9},
        List.of(lack, failed("Unexpected chunk " + OTHER, "protocol")));
    assertReplies(
        List.of(MANIFEST, DONE, new SyncWire.Chunk(HASH, 4)),
        new byte[] {1, 2, 3, 4},
        List.of(
            lack,
            failed(
                "Chunk " + HASH + " exceeds declared content size for blobs [" + HASH + "]",
                "protocol")));
    assertReplies(
        List.of(MANIFEST, DONE, CHUNK),
        new byte[] {3, 2, 1},
        List.of(lack, failed("Invalid chunk " + HASH + ": size or SHA-256 mismatch", "protocol")));
    assertReplies(
        List.of(MANIFEST, DONE, CHUNK),
        new byte[] {1, 2},
        List.of(
            lack,
            failed(
                "blobs ["
                    + HASH
                    + "], chunk "
                    + HASH
                    + ": Sync channel closed mid-chunk, after 2 of 3 bytes",
                "unreachable")));
    assertReplies(
        List.of(MANIFEST, DONE, DONE),
        new byte[0],
        List.of(
            lack, failed("Missing chunks [" + HASH + "] for blobs [" + HASH + "]", "protocol")));
    assertReplies(
        List.of(MANIFEST, DONE),
        new byte[0],
        List.of(lack, failed("Channel closed during upload", "unreachable")));
    var chunkThenDone = new ByteArrayOutputStream();
    chunkThenDone.writeBytes(BYTES);
    chunkThenDone.writeBytes(line(SyncWire.encode(DONE)));
    assertReplies(List.of(MANIFEST, DONE, CHUNK), chunkThenDone.toByteArray(), List.of(lack, DONE));
  }

  private static SyncWire.Failed failed(String message, String kind) {
    return new SyncWire.Failed("announce: " + message, kind);
  }

  private static void assertReplies(
      List<SyncWire.Request> afterAnnounce, byte[] trailing, SyncWire.Response last)
      throws Exception {
    assertReplies(afterAnnounce, trailing, List.of(last));
  }

  private static void assertReplies(
      List<SyncWire.Request> afterAnnounce, byte[] trailing, List<SyncWire.Response> replies)
      throws Exception {
    assertReplies(FileLimits.defaults(), afterAnnounce, trailing, replies);
  }

  private static void assertReplies(
      FileLimits limits,
      List<SyncWire.Request> afterAnnounce,
      byte[] trailing,
      List<SyncWire.Response> replies)
      throws Exception {
    var request = new ByteArrayOutputStream();
    request.writeBytes(line(SyncWire.encode(HELLO)));
    request.writeBytes(line(SyncWire.encode(new SyncWire.Announce(List.of(HASH)))));
    for (var content : afterAnnounce) request.writeBytes(line(SyncWire.encode(content)));
    request.writeBytes(trailing);
    var expected = new StringBuilder();
    expected.append(
        SyncWire.encode(new SyncWire.Welcome(SyncWire.PROTOCOL, SyncWire.UPGRADE_FLOOR, "main")));
    expected.append('\n');
    expected.append(SyncWire.encode(new SyncWire.Lack(List.of(HASH)))).append('\n');
    for (var reply : replies) expected.append(SyncWire.encode(reply)).append('\n');
    try (var main = new SyncBox("main")) {
      var output = new ByteStreams.Output();
      main.server(new SyncPrincipal("node", true))
          .content(main.db, limits)
          .serve(new ByteArrayInputStream(request.toByteArray()), output);
      assertEquals(expected.toString(), output.toString());
      assertEquals(
          replies.contains(DONE) ? 1L : 0L,
          main.db.queryOne("SELECT COUNT(*) FROM chunks", row -> row.integer(0)).orElseThrow());
    }
  }

  private static byte[] line(String text) {
    return (text + "\n").getBytes(StandardCharsets.UTF_8);
  }
}
