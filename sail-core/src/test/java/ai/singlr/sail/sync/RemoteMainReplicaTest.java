/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.sync;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.io.StringReader;
import java.io.StringWriter;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * The client side in isolation: a canned server reply drives {@link RemoteMainReplica} so its
 * caching and every protocol-violation guard are exercised without a real channel.
 */
class RemoteMainReplicaTest {

  private static RemoteMainReplica replica(String serverLine) {
    return new RemoteMainReplica(new StringReader(serverLine + "\n"), new StringWriter());
  }

  @Test
  void oneFetchServesEveryReadAndIdAndMaxSeq() {
    var entities = new LinkedHashMap<String, SyncWire.Snapshot>();
    entities.put("auth", new SyncWire.Snapshot("3-a", Map.of("title", "Auth")));
    var replica = replica(SyncWire.encode(new SyncWire.Fetched("maindevbox", 7, entities)));

    assertEquals(Set.of("auth"), replica.entityIds());
    assertEquals("3-a", replica.currentRev("auth"));
    assertEquals("Auth", replica.current("auth").get("title"));
    assertNull(replica.current("absent"));
    assertNull(replica.currentRev("absent"));
    assertEquals("maindevbox", replica.id());
    assertEquals(7, replica.maxSeq());
  }

  @Test
  void aNonFetchReplyToFetchIsRejected() {
    var replica = replica(SyncWire.encode(new SyncWire.Committed("1-a", 1)));
    assertThrows(SyncTransportException.class, replica::entityIds);
  }

  @Test
  void aRefusedCommitSurfacesItsReason() {
    var replica = replica(SyncWire.encode(new SyncWire.Failed("read-only")));
    var ex = assertThrows(SyncTransportException.class, () -> replica.commit("x", Map.of(), null));
    assertTrue(ex.getMessage().contains("read-only"));
  }

  @Test
  void anUnexpectedReplyToCommitIsRejected() {
    var replica = replica(SyncWire.encode(new SyncWire.Fetched("m", 1, Map.of())));
    assertThrows(SyncTransportException.class, () -> replica.commit("x", Map.of(), null));
  }

  @Test
  void fetchFdesReturnsMainsRoster() {
    var roster = List.<Map<String, Object>>of(Map.of("handle", "ada", "role", "admin"));
    var replica = replica(SyncWire.encode(new SyncWire.Fdes(roster)));

    var pulled = replica.fetchFdes();
    assertEquals(1, pulled.size());
    assertEquals("ada", pulled.getFirst().get("handle"));
  }

  @Test
  void aNonRosterReplyToFetchFdesIsRejected() {
    var replica = replica(SyncWire.encode(new SyncWire.Committed("1-a", 1)));
    assertThrows(SyncTransportException.class, replica::fetchFdes);
  }

  @Test
  void aChannelClosedBeforeAReplyIsReported() {
    var replica = new RemoteMainReplica(new StringReader(""), new StringWriter());
    assertThrows(SyncTransportException.class, replica::entityIds);
  }

  @Test
  void closeSendsBye() {
    var out = new StringWriter();
    try (var replica = new RemoteMainReplica(new StringReader(""), out)) {
      assertNotNull(replica);
    }
    assertEquals(new SyncWire.Bye(), SyncWire.decodeRequest(out.toString().strip()));
  }

  @Test
  void aBrokenChannelOnSendRaisesUnchecked() {
    var replica = new RemoteMainReplica(new StringReader(""), brokenWriter());
    assertThrows(UncheckedIOException.class, replica::entityIds);
  }

  @Test
  void aBrokenChannelOnCloseRaisesUnchecked() {
    var replica = new RemoteMainReplica(new StringReader(""), brokenWriter());
    assertThrows(UncheckedIOException.class, replica::close);
  }

  private static Writer brokenWriter() {
    return new Writer() {
      @Override
      public void write(char[] buffer, int offset, int length) throws IOException {
        throw new IOException("channel down");
      }

      @Override
      public void flush() throws IOException {
        throw new IOException("channel down");
      }

      @Override
      public void close() {}
    };
  }
}
