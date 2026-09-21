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
import java.io.StringReader;
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
    var in = new StringReader("first\nsecond\n");
    assertEquals("first", SyncWire.readFramed(in));
    assertEquals("second", SyncWire.readFramed(in));
    assertNull(SyncWire.readFramed(in));
  }

  @Test
  void readFramedNamesAChannelThatClosedMidMessageInsteadOfReturningTheFragment() {
    var in = new StringReader("first\n{\"op\": \"page\", \"entr");

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
    assertEquals("abcd", SyncWire.readFramed(new StringReader("abcd\n"), 4));
    assertThrows(
        SyncTransportException.class, () -> SyncWire.readFramed(new StringReader("abcde"), 4));
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
}
