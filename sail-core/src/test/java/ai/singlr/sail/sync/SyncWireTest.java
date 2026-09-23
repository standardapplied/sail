/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.sync;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.sail.config.YamlUtil;
import ai.singlr.sail.store.ChangeLog;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SyncWireTest {

  private static Map<String, Object> snapshot() {
    var map = new LinkedHashMap<String, Object>();
    map.put("title", "Auth");
    map.put("status", "in_progress");
    map.put("priority", 3);
    map.put("depends_on", List.of("db", "api"));
    map.put("assignee", null);
    map.put("body", "line one\nline two\twith a \"quote\"");
    return map;
  }

  private static final List<SyncWire.Request> REQUESTS =
      List.of(
          SyncWire.Hello.of("0.44.1", "box-7"),
          new SyncWire.Heads(),
          new SyncWire.Pull("spec", 41, 500),
          new SyncWire.Need("file", List.of("acme/a", "acme/b")),
          new SyncWire.Push(
              "spec",
              List.of(
                  new MainReplica.Offer("auth", snapshot(), "2-base"),
                  new MainReplica.Offer("gone", null, "3-x"))),
          new SyncWire.FetchFdes(),
          new SyncWire.Bye());

  private static final List<SyncWire.Response> RESPONSES =
      List.of(
          new SyncWire.Welcome(4, "0.44.0", "main-box"),
          new SyncWire.Refuse("hello required"),
          new SyncWire.Tips(Map.of("spec", 7L, "file", 0L)),
          new SyncWire.Page(
              List.of(
                  new SyncWire.Entry(5, "auth", "3-abc", false, snapshot()),
                  new SyncWire.Entry(9, "gone", "4-def", true, null)),
              9,
              false,
              12),
          new SyncWire.Results(
              List.of(
                  new SyncWire.Accepted("auth", "7-feed"),
                  new SyncWire.Stale("b"),
                  new SyncWire.Refused("d", "read-only")),
              99),
          new SyncWire.Fdes(
              List.of(
                  Map.of("handle", "ada", "role", "admin", "status", "active"),
                  Map.of("handle", "uday", "role", "member", "status", "disabled"))),
          new SyncWire.Failed("disk full", "store"));

  @Test
  void aChunkCannotBeAnnouncedUntilItsBytesMatchItsHash() {
    var output = new java.io.ByteArrayOutputStream();
    var hash = ai.singlr.sail.store.BlobStore.hash(new byte[] {1});
    assertThrows(
        IllegalArgumentException.class, () -> SyncWire.writeChunk(output, hash, new byte[] {2}));
    assertEquals(0, output.size());
  }

  @Test
  void contentLengthsMustBeIntegersAndEntriesMustBeObjects() {
    var hash = ai.singlr.sail.store.BlobStore.hash(new byte[] {1});
    for (var size : List.of("\"1\"", "1.5", "null")) {
      var line = "{\"op\":\"chunk\",\"hash\":\"" + hash + "\",\"size\":" + size + "}";
      assertThrows(IllegalArgumentException.class, () -> SyncWire.decodeRequest(line));
      assertThrows(IllegalArgumentException.class, () -> SyncWire.decodeResponse(line));
    }
    assertThrows(
        IllegalArgumentException.class,
        () -> SyncWire.decodeResponse("{\"op\":\"page\",\"entries\":[1]}"));
  }

  @Test
  void rpcFailuresDistinguishMalformedMessagesFromClosedAndBrokenStreams() {
    var malformed =
        assertThrows(
            SyncTransportException.class,
            () ->
                Rpc.exchange(
                    new ByteStreams.Input("{\"op\":\"unknown\"}\n"),
                    new ByteStreams.Output(),
                    new SyncWire.Heads()));
    assertEquals("protocol", malformed.kind());
    assertTrue(malformed.getMessage().contains("heads"));
    assertEquals(
        "unreachable",
        assertThrows(
                SyncTransportException.class,
                () -> Rpc.receive(new ByteStreams.Input(""), "manifest"))
            .kind());
    var broken =
        new java.io.InputStream() {
          @Override
          public int read() throws java.io.IOException {
            throw new java.io.IOException("connection reset");
          }
        };
    var failure = assertThrows(SyncTransportException.class, () -> Rpc.receive(broken, "chunk"));
    assertEquals("unreachable", failure.kind());
    assertTrue(failure.getMessage().contains("connection reset"));
  }

  @Test
  void everyRequestRoundTripsOnOneLineTaggedWithItsOp() {
    for (var request : REQUESTS) {
      var line = SyncWire.encode(request);
      assertFalse(line.contains("\n"), "a body's newlines must be escaped, never framed");
      assertTrue(YamlUtil.parseMap(line).containsKey("op"), line);
      assertEquals(request, SyncWire.decodeRequest(line));
    }
  }

  @Test
  void everyResponseRoundTripsOnOneLineTaggedWithItsOp() {
    for (var response : RESPONSES) {
      var line = SyncWire.encode(response);
      assertFalse(line.contains("\n"));
      assertTrue(YamlUtil.parseMap(line).containsKey("op"), line);
      assertEquals(response, SyncWire.decodeResponse(line));
    }
  }

  @Test
  void aHelloOfThisBuildCarriesTheProtocolAndTheFloor() {
    var hello = SyncWire.Hello.of("0.44.3", "box");
    assertEquals(SyncWire.PROTOCOL, hello.protocol());
    assertEquals(SyncWire.UPGRADE_FLOOR, hello.floor());
    assertEquals("0.44.3", hello.version());
  }

  @Test
  void anUnknownOpIsRejectedNamingItOnEitherSide() {
    var request =
        assertThrows(
            IllegalArgumentException.class, () -> SyncWire.decodeRequest("{\"op\": \"dance\"}"));
    assertTrue(request.getMessage().contains("dance"));
    var response =
        assertThrows(
            IllegalArgumentException.class, () -> SyncWire.decodeResponse("{\"op\": \"waltz\"}"));
    assertTrue(response.getMessage().contains("waltz"));
    var untagged =
        assertThrows(
            IllegalArgumentException.class, () -> SyncWire.decodeResponse("{\"rev\": \"3-a\"}"));
    assertTrue(untagged.getMessage().contains("Unknown sync op"));
    assertThrows(IllegalArgumentException.class, () -> SyncWire.decodeRequest("{\"entities\": 1}"));
  }

  @Test
  void aRefusalCarriesItsReasonAndNothingElse() {
    var line = SyncWire.encode(new SyncWire.Refuse("upgrade to 0.44.0: sail upgrade"));
    assertEquals(
        Map.of("op", "refuse", "reason", "upgrade to 0.44.0: sail upgrade"),
        YamlUtil.parseMap(line));
  }

  @Test
  void aTombstoneEntryCarriesNoSnapshotAndDecodesAsDeleted() {
    var page =
        new SyncWire.Page(List.of(new SyncWire.Entry(3, "gone", "2-x", true, null)), 3, true, 3);
    var line = SyncWire.encode(page);
    assertFalse(line.contains("snapshot"));
    var decoded = (SyncWire.Page) SyncWire.decodeResponse(line);
    assertTrue(decoded.entries().getFirst().deleted());
    assertNull(decoded.entries().getFirst().snapshot());
  }

  @Test
  void everyEntryNamesItsKindSoAnErasureIsNeverReadAsARevision() {
    var entries =
        List.of(
            new SyncWire.Entry(1, "live", "1-a", false, Map.of("title", "t")),
            new SyncWire.Entry(2, "gone", "2-b", true, null),
            new SyncWire.Entry(3, "erased", "3-c", true, null, ChangeLog.Kind.ERASURE));
    var line = SyncWire.encode(new SyncWire.Page(entries, 3, true, 3));

    var decoded = ((SyncWire.Page) SyncWire.decodeResponse(line)).entries();

    assertEquals(
        List.of(ChangeLog.Kind.REVISION, ChangeLog.Kind.TOMBSTONE, ChangeLog.Kind.ERASURE),
        decoded.stream().map(SyncWire.Entry::kind).toList());
    assertEquals(entries, decoded);
    assertTrue(decoded.get(2).erased());
    assertFalse(decoded.get(1).erased());
    assertTrue(line.contains("\"kind\": \"erasure\""), line);
  }

  @Test
  void anEntryThatNamesNoKindOrContradictsItselfIsRefused() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            SyncWire.decodeResponse(
                "{\"op\": \"page\", \"entries\": [{\"seq\": 1, \"id\": \"a\", \"rev\": \"1-a\","
                    + " \"deleted\": true}], \"next\": 1, \"done\": true, \"maxSeq\": 1}"));
    assertThrows(
        IllegalArgumentException.class,
        () -> new SyncWire.Entry(1, "a", "1-a", false, null, ChangeLog.Kind.ERASURE));
    assertThrows(
        IllegalArgumentException.class,
        () -> new SyncWire.Entry(1, "a", "1-a", true, null, ChangeLog.Kind.REVISION));
    assertThrows(
        IllegalArgumentException.class, () -> new SyncWire.Entry(1, "a", "1-a", true, null, null));
  }

  @Test
  void anEraseOfferCarriesTheFlagAndNoSnapshotAndARevisionOfferNoFlag() {
    var push =
        new SyncWire.Push(
            "spec",
            List.of(
                MainReplica.Offer.erasure("old"), new MainReplica.Offer("new", Map.of(), null)));
    var line = SyncWire.encode(push);

    var decoded = (SyncWire.Push) SyncWire.decodeRequest(line);

    assertEquals(push, decoded);
    assertTrue(decoded.offers().getFirst().erase());
    assertFalse(decoded.offers().getLast().erase());
    assertEquals(1, line.split("\"erase\"", -1).length - 1, "only the erase offer names it");
    assertThrows(
        IllegalArgumentException.class, () -> new MainReplica.Offer("x", Map.of(), null, true));
  }

  @Test
  void aResultWithoutAVerdictIsRejected() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            SyncWire.decodeResponse(
                "{\"op\": \"results\", \"results\": [{\"id\": \"a\"}], \"maxSeq\": 1}"));
  }

  @Test
  void missingNumbersDecodeToZeroAndMissingListsToEmpty() {
    var page = (SyncWire.Page) SyncWire.decodeResponse("{\"op\": \"page\"}");
    assertTrue(page.entries().isEmpty());
    assertEquals(0, page.next());
    assertFalse(page.done());
    var tips = (SyncWire.Tips) SyncWire.decodeResponse("{\"op\": \"tips\", \"tips\": null}");
    assertTrue(tips.tips().isEmpty());
    var need = (SyncWire.Need) SyncWire.decodeRequest("{\"op\": \"need\", \"type\": \"spec\"}");
    assertTrue(need.ids().isEmpty());
    var failed = (SyncWire.Failed) SyncWire.decodeResponse("{\"op\": \"failed\"}");
    assertNull(failed.message());
  }

  @Test
  void aFrameLargerThanTheLibraryDefaultParsesOnBothEnds() {
    var content = "x".repeat(4 * 1024 * 1024);
    var push =
        new SyncWire.Push(
            "message", List.of(new MainReplica.Offer("m1", Map.of("body", content), null)));
    assertEquals(push, SyncWire.decodeRequest(SyncWire.encode(push)));
    var page =
        new SyncWire.Page(
            List.of(new SyncWire.Entry(1, "m1", "1-main", false, Map.of("body", content))),
            1,
            true,
            1);
    assertEquals(page, SyncWire.decodeResponse(SyncWire.encode(page)));
  }

  @Test
  void aFrameAdmitsItemsUntilTheBoundAndKnowsWhatCouldNeverFit() {
    var defaults = new SyncWire.Frame();
    assertTrue(defaults.canEverAdmit(SyncWire.MAX_FRAME / 2));
    assertFalse(defaults.canEverAdmit(SyncWire.MAX_FRAME));
    var frame = new SyncWire.Frame(600);
    assertTrue(frame.isEmpty());
    assertTrue(frame.canEverAdmit(300));
    assertFalse(frame.canEverAdmit(400), "the envelope is reserved beside the item");
    assertTrue(frame.admits(300));
    frame.add(300);
    assertFalse(frame.isEmpty());
    assertFalse(frame.admits(300));
    assertTrue(frame.admits(40));
  }

  @Test
  void anEntrysEncodedLengthIsExactlyWhatThePageCarriesForIt() {
    var entry = new SyncWire.Entry(5, "auth", "3-abc", false, snapshot());
    var lone = SyncWire.encode(new SyncWire.Page(List.of(entry), 5, true, 5));
    var pair = SyncWire.encode(new SyncWire.Page(List.of(entry, entry), 5, true, 5));
    assertEquals(SyncWire.encodedLength(entry) + 2, pair.length() - lone.length());
    var offer = new MainReplica.Offer("auth", snapshot(), "2-base");
    var one = SyncWire.encode(new SyncWire.Push("spec", List.of(offer)));
    var two = SyncWire.encode(new SyncWire.Push("spec", List.of(offer, offer)));
    assertEquals(SyncWire.encodedLength(offer) + 2, two.length() - one.length());
  }

  @Test
  void readFramedReadsOneLinePerCallWithoutTheTerminator() throws Exception {
    var in = new ai.singlr.sail.sync.ByteStreams.Input("first\nsecond\n");
    assertEquals("first", SyncWire.readFramed(in));
    assertEquals("second", SyncWire.readFramed(in));
    assertNull(SyncWire.readFramed(in));
  }

  @Test
  void readFramedNamesAChannelThatClosedMidMessageInsteadOfReturningTheFragment() {
    var in = new ai.singlr.sail.sync.ByteStreams.Input("first\n{\"op\": \"page\", \"entr");

    var thrown =
        assertThrows(
            SyncTransportException.class,
            () -> {
              SyncWire.readFramed(in);
              SyncWire.readFramed(in);
            });

    assertEquals("unreachable", thrown.kind());
    assertTrue(thrown.getMessage().contains("closed mid-message"), thrown.getMessage());
  }

  @Test
  void readFramedAcceptsAMessageExactlyAtTheBoundAndRejectsOneOver() throws Exception {
    assertEquals(
        "abcd", SyncWire.readFramed(new ai.singlr.sail.sync.ByteStreams.Input("abcd\n"), 4));
    assertThrows(
        SyncTransportException.class,
        () -> SyncWire.readFramed(new ai.singlr.sail.sync.ByteStreams.Input("abcde"), 4));
  }

  @Test
  void contextNamesTheTypeForTypedRequestsAndTheOpOtherwise() {
    assertEquals("spec", SyncWire.context(new SyncWire.Pull("spec", 0, 1)));
    assertEquals("file", SyncWire.context(new SyncWire.Need("file", List.of())));
    assertEquals("run", SyncWire.context(new SyncWire.Push("run", List.of())));
    assertEquals("hello", SyncWire.context(SyncWire.Hello.of("0.44.0", "b")));
    assertEquals("heads", SyncWire.context(new SyncWire.Heads()));
    assertEquals("fde", SyncWire.context(new SyncWire.FetchFdes()));
    assertEquals("session", SyncWire.context(new SyncWire.Bye()));
  }

  @Test
  void binaryChunksPreserveEveryByteAndReturnToLineMode() throws Exception {
    var payload = new byte[] {0, -1, -2, 10, 13, 65};
    var output = new java.io.ByteArrayOutputStream();
    SyncWire.writeChunk(output, ai.singlr.sail.store.BlobStore.hash(payload), payload);
    output.write("{\"op\":\"done\"}\n".getBytes(java.nio.charset.StandardCharsets.UTF_8));
    var input = new java.io.ByteArrayInputStream(output.toByteArray());
    var chunk = (SyncWire.Chunk) SyncWire.decodeResponse(SyncWire.readLine(input));
    org.junit.jupiter.api.Assertions.assertArrayEquals(
        payload, SyncWire.readBytes(input, chunk.size()));
    assertEquals(new SyncWire.Done(), SyncWire.decodeResponse(SyncWire.readLine(input)));
    org.junit.jupiter.api.Assertions.assertNull(SyncWire.readLine(input));
  }

  @Test
  void malformedLinesAndTruncatedChunksHaveDistinctFailures() {
    var malformed =
        assertThrows(
            SyncTransportException.class,
            () -> SyncWire.readLine(new java.io.ByteArrayInputStream(new byte[] {-1, 10})));
    assertEquals("protocol", malformed.kind());
    var cut =
        assertThrows(
            SyncTransportException.class,
            () -> SyncWire.readBytes(new java.io.ByteArrayInputStream(new byte[] {1}), 2));
    assertEquals("unreachable", cut.kind());
    assertThrows(
        IllegalArgumentException.class,
        () ->
            SyncWire.readBytes(
                java.io.InputStream.nullInputStream(), ai.singlr.sail.store.FastCdc.MAX + 1));
  }
}
