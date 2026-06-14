/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.sync;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.StringReader;
import java.io.StringWriter;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * The client side in isolation: a canned server reply drives a typed {@link RemoteMainReplica} so
 * its caching and every protocol-violation guard are exercised without a real channel.
 */
class RemoteMainReplicaTest {

  private static RemoteMainReplica replica(String serverLine) {
    return new RemoteMainReplica(new StringReader(serverLine + "\n"), new StringWriter(), "spec");
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
  void aChannelClosedBeforeAReplyIsReported() {
    var replica = new RemoteMainReplica(new StringReader(""), new StringWriter(), "spec");
    assertThrows(SyncTransportException.class, replica::entityIds);
  }

  @Test
  void aBrokenChannelOnSendRaisesUnchecked() {
    var replica = new RemoteMainReplica(new StringReader(""), brokenWriter(), "spec");
    assertThrows(UncheckedIOException.class, replica::entityIds);
  }

  @Test
  void aBrokenChannelOnReadRaisesUnchecked() {
    var replica = new RemoteMainReplica(brokenReader(), new StringWriter(), "spec");
    assertThrows(UncheckedIOException.class, replica::entityIds);
  }

  private static java.io.Reader brokenReader() {
    return new java.io.Reader() {
      @Override
      public int read(char[] buffer, int offset, int length) throws IOException {
        throw new IOException("channel down");
      }

      @Override
      public void close() {}
    };
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
