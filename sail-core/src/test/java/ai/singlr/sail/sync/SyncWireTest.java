/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.sync;

import static org.junit.jupiter.api.Assertions.*;

import java.io.StringReader;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Every request and response survives a JSON round trip on a single line, deletions included. */
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

  @Test
  void fetchRequestRoundTrips() {
    var line = SyncWire.encode(new SyncWire.Fetch());
    assertEquals(new SyncWire.Fetch(), SyncWire.decodeRequest(line));
  }

  @Test
  void byeRequestRoundTrips() {
    var line = SyncWire.encode(new SyncWire.Bye());
    assertEquals(new SyncWire.Bye(), SyncWire.decodeRequest(line));
  }

  @Test
  void fetchFdesRequestRoundTrips() {
    var line = SyncWire.encode(new SyncWire.FetchFdes());
    assertEquals(new SyncWire.FetchFdes(), SyncWire.decodeRequest(line));
  }

  @Test
  void fdesResponseRoundTripsTheRoster() {
    var roster =
        List.<Map<String, Object>>of(
            Map.of("handle", "ada", "role", "admin", "status", "active"),
            Map.of("handle", "uday", "role", "member", "status", "disabled"));
    var line = SyncWire.encode(new SyncWire.Fdes(roster));

    var decoded = (SyncWire.Fdes) SyncWire.decodeResponse(line);
    assertEquals(2, decoded.fdes().size());
    assertEquals("ada", decoded.fdes().getFirst().get("handle"));
    assertEquals("disabled", decoded.fdes().get(1).get("status"));
  }

  @Test
  void anEmptyFdesResponseDecodesToAnEmptyRoster() {
    var decoded = (SyncWire.Fdes) SyncWire.decodeResponse("{\"fdes\": []}");
    assertTrue(decoded.fdes().isEmpty());
  }

  @Test
  void aMalformedFdesValueDecodesToAnEmptyRoster() {
    var decoded = (SyncWire.Fdes) SyncWire.decodeResponse("{\"fdes\": null}");
    assertTrue(decoded.fdes().isEmpty());
  }

  @Test
  void commitRequestRoundTripsWithNestedSnapshotOnOneLine() {
    var line = SyncWire.encode(new SyncWire.Commit("auth", snapshot(), "2-base"));

    assertFalse(line.contains("\n"), "a spec body's newlines must be escaped, never framed");
    var decoded = (SyncWire.Commit) SyncWire.decodeRequest(line);
    assertEquals("auth", decoded.entityId());
    assertEquals("2-base", decoded.expectedRev());
    assertEquals("Auth", decoded.snapshot().get("title"));
    assertEquals(List.of("db", "api"), decoded.snapshot().get("depends_on"));
    assertEquals("line one\nline two\twith a \"quote\"", decoded.snapshot().get("body"));
    assertNull(decoded.snapshot().get("assignee"));
  }

  @Test
  void commitRequestCarriesDeletionAsExplicitNull() {
    var line = SyncWire.encode(new SyncWire.Commit("auth", null, null));
    var decoded = (SyncWire.Commit) SyncWire.decodeRequest(line);
    assertNull(decoded.snapshot());
    assertNull(decoded.expectedRev());
  }

  @Test
  void fetchedResponseRoundTripsEntitiesIncludingTombstone() {
    var entities = new LinkedHashMap<String, SyncWire.Snapshot>();
    entities.put("auth", new SyncWire.Snapshot("3-abc", snapshot()));
    entities.put("gone", new SyncWire.Snapshot("4-def", null));
    var line = SyncWire.encode(new SyncWire.Fetched("maindevbox", 42, entities));

    var decoded = (SyncWire.Fetched) SyncWire.decodeResponse(line);
    assertEquals("maindevbox", decoded.mainId());
    assertEquals(42, decoded.maxSeq());
    assertEquals("3-abc", decoded.entities().get("auth").rev());
    assertEquals("Auth", decoded.entities().get("auth").snapshot().get("title"));
    assertEquals("4-def", decoded.entities().get("gone").rev());
    assertNull(decoded.entities().get("gone").snapshot());
  }

  @Test
  void committedResponseRoundTrips() {
    var line = SyncWire.encode(new SyncWire.Committed("7-feed", 99));
    var decoded = (SyncWire.Committed) SyncWire.decodeResponse(line);
    assertEquals("7-feed", decoded.rev());
    assertEquals(99, decoded.maxSeq());
  }

  @Test
  void failedResponseRoundTrips() {
    var line = SyncWire.encode(new SyncWire.Failed("read-only"));
    var decoded = (SyncWire.Failed) SyncWire.decodeResponse(line);
    assertEquals("read-only", decoded.message());
  }

  @Test
  void rejectedResponseRoundTripsWithMainsCurrentState() {
    var line = SyncWire.encode(new SyncWire.Rejected("9-feed", snapshot()));
    var decoded = (SyncWire.Rejected) SyncWire.decodeResponse(line);
    assertEquals("9-feed", decoded.currentRev());
    assertEquals("Auth", decoded.currentSnapshot().get("title"));
  }

  @Test
  void rejectedResponseRoundTripsWithATombstone() {
    var line = SyncWire.encode(new SyncWire.Rejected("9-gone", null));
    var decoded = (SyncWire.Rejected) SyncWire.decodeResponse(line);
    assertEquals("9-gone", decoded.currentRev());
    assertNull(decoded.currentSnapshot());
  }

  @Test
  void unknownRequestOpIsRejected() {
    assertThrows(
        IllegalArgumentException.class, () -> SyncWire.decodeRequest("{\"op\": \"dance\"}"));
  }

  @Test
  void unrecognizedResponseIsRejected() {
    assertThrows(IllegalArgumentException.class, () -> SyncWire.decodeResponse("{\"hi\": 1}"));
  }

  @Test
  void aResponseMissingMaxSeqDecodesToZero() {
    var committed = (SyncWire.Committed) SyncWire.decodeResponse("{\"rev\": \"3-a\"}");
    assertEquals(0, committed.maxSeq());
  }

  @Test
  void aNullStringFieldDecodesToNull() {
    var committed = (SyncWire.Committed) SyncWire.decodeResponse("{\"rev\": null, \"maxSeq\": 5}");
    assertNull(committed.rev());
    assertEquals(5, committed.maxSeq());
  }

  @Test
  void aFetchedWithNullEntitiesDecodesToNoEntities() {
    var fetched =
        (SyncWire.Fetched)
            SyncWire.decodeResponse("{\"id\": \"m\", \"maxSeq\": 0, \"entities\": null}");
    assertTrue(fetched.entities().isEmpty());
  }

  @Test
  void readFramedReadsOneLinePerCallWithoutTheTerminator() throws Exception {
    var in = new StringReader("first\nsecond\n");
    assertEquals("first", SyncWire.readFramed(in));
    assertEquals("second", SyncWire.readFramed(in));
    assertNull(SyncWire.readFramed(in));
  }

  @Test
  void readFramedReturnsAFinalUnterminatedLineThenNull() throws Exception {
    var in = new StringReader("tail");
    assertEquals("tail", SyncWire.readFramed(in));
    assertNull(SyncWire.readFramed(in));
  }

  @Test
  void readFramedAcceptsAMessageExactlyAtTheBound() throws Exception {
    assertEquals("abcd", SyncWire.readFramed(new StringReader("abcd\n"), 4));
  }

  @Test
  void readFramedRejectsAMessageOverTheBound() {
    assertThrows(
        SyncTransportException.class, () -> SyncWire.readFramed(new StringReader("abcde"), 4));
  }
}
