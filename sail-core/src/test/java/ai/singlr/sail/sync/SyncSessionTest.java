/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.sync;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.io.StringReader;
import java.io.StringWriter;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** The session that wraps a channel: typed replicas, the roster pull, and the closing bye. */
class SyncSessionTest {

  @Test
  void fetchFdesReturnsMainsRoster() {
    var roster = List.<Map<String, Object>>of(Map.of("handle", "ada", "role", "admin"));
    try (var session =
        new SyncSession(
            new StringReader(SyncWire.encode(new SyncWire.Fdes(roster)) + "\n"),
            new StringWriter())) {
      var pulled = session.fetchFdes();
      assertEquals(1, pulled.size());
      assertEquals("ada", pulled.getFirst().get("handle"));
    }
  }

  @Test
  void aNonRosterReplyToFetchFdesIsRejected() {
    try (var session =
        new SyncSession(
            new StringReader(SyncWire.encode(new SyncWire.Committed("1-a", 1)) + "\n"),
            new StringWriter())) {
      assertThrows(SyncTransportException.class, session::fetchFdes);
    }
  }

  @Test
  void closeSendsBye() {
    var out = new StringWriter();
    try (var session = new SyncSession(new StringReader(""), out)) {
      assertNotNull(session.replica("spec"));
    }
    assertEquals(new SyncWire.Bye(), SyncWire.decodeRequest(out.toString().strip()));
  }

  @Test
  void aBrokenChannelOnCloseRaisesUnchecked() {
    var session = new SyncSession(new StringReader(""), brokenWriter());
    assertThrows(UncheckedIOException.class, session::close);
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
