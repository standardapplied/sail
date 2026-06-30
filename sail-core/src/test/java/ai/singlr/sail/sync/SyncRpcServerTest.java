/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.sync;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.io.StringReader;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** The server loop in isolation over a fake authority: framing, the write gate, and clean EOF. */
class SyncRpcServerTest {

  private static class FakeMain implements MainReplica {
    @Override
    public String id() {
      return "main";
    }

    @Override
    public Set<String> entityIds() {
      return Set.of();
    }

    @Override
    public Map<String, Object> current(String entityId) {
      return null;
    }

    @Override
    public String currentRev(String entityId) {
      return null;
    }

    @Override
    public CommitOutcome commit(String entityId, Map<String, Object> snapshot, String expectedRev) {
      return new CommitOutcome.Accepted("1-x");
    }

    @Override
    public long maxSeq() {
      return 0;
    }
  }

  private static SyncWire.Response serve(boolean writable, SyncWire.Request request)
      throws Exception {
    var out = new StringWriter();
    new SyncRpcServer(new FakeMain(), writable)
        .serve(new StringReader(SyncWire.encode(request) + "\n"), out);
    return SyncWire.decodeResponse(out.toString().strip());
  }

  @Test
  void anEmptyStreamEndsTheSessionCleanly() throws Exception {
    new SyncRpcServer(new FakeMain(), true).serve(new StringReader(""), new StringWriter());
  }

  @Test
  void fetchIsAnswered() throws Exception {
    assertInstanceOf(SyncWire.Fetched.class, serve(true, new SyncWire.Fetch("spec")));
  }

  @Test
  void aWritableServerAcceptsACommit() throws Exception {
    var response = serve(true, new SyncWire.Commit("spec", "a", Map.of(), null));
    assertEquals("1-x", assertInstanceOf(SyncWire.Committed.class, response).rev());
  }

  @Test
  void aReadOnlyServerRefusesACommit() throws Exception {
    assertInstanceOf(
        SyncWire.Failed.class, serve(false, new SyncWire.Commit("spec", "a", Map.of(), null)));
  }

  @Test
  void fetchFdesReturnsTheInjectedRoster() throws Exception {
    var roster = List.<Map<String, Object>>of(Map.of("handle", "ada", "role", "admin"));
    var out = new StringWriter();
    new SyncRpcServer(new FakeMain(), false, () -> roster)
        .serve(new StringReader(SyncWire.encode(new SyncWire.FetchFdes()) + "\n"), out);

    var response = (SyncWire.Fdes) SyncWire.decodeResponse(out.toString().strip());
    assertEquals("ada", response.fdes().getFirst().get("handle"));
  }

  @Test
  void fetchFdesDefaultsToAnEmptyRoster() throws Exception {
    assertInstanceOf(SyncWire.Fdes.class, serve(true, new SyncWire.FetchFdes()));
  }

  @Test
  void anUnknownEntityTypeFetchIsRefused() throws Exception {
    assertInstanceOf(SyncWire.Failed.class, serve(true, new SyncWire.Fetch("bogus")));
  }

  @Test
  void anUnknownEntityTypeCommitIsRefused() throws Exception {
    assertInstanceOf(
        SyncWire.Failed.class, serve(true, new SyncWire.Commit("bogus", "a", Map.of(), null)));
  }

  @Test
  void aStoreExceptionOnCommitBecomesAFailedResponseNotADroppedSession() throws Exception {
    var throwing =
        new FakeMain() {
          @Override
          public CommitOutcome commit(
              String entityId, Map<String, Object> snapshot, String expectedRev) {
            throw new IllegalStateException("database is locked");
          }
        };
    var out = new StringWriter();
    var request = new SyncWire.Commit("spec", "a", Map.of(), null);

    new SyncRpcServer(throwing, true).serve(new StringReader(SyncWire.encode(request) + "\n"), out);

    assertInstanceOf(SyncWire.Failed.class, SyncWire.decodeResponse(out.toString().strip()));
  }

  @Test
  void aStoreExceptionOnFetchBecomesAFailedResponse() throws Exception {
    var throwing =
        new FakeMain() {
          @Override
          public Set<String> entityIds() {
            throw new IllegalStateException("database is locked");
          }
        };
    var out = new StringWriter();

    new SyncRpcServer(throwing, true)
        .serve(new StringReader(SyncWire.encode(new SyncWire.Fetch("spec")) + "\n"), out);

    assertInstanceOf(SyncWire.Failed.class, SyncWire.decodeResponse(out.toString().strip()));
  }

  @Test
  void aWrappedStoreExceptionSurfacesTheRootCauseInTheFailedMessage() throws Exception {
    var throwing =
        new FakeMain() {
          @Override
          public CommitOutcome commit(
              String entityId, Map<String, Object> snapshot, String expectedRev) {
            throw new IllegalStateException(
                "commit failed", new RuntimeException("database is locked"));
          }
        };
    var out = new StringWriter();
    var request = new SyncWire.Commit("spec", "a", Map.of(), null);

    new SyncRpcServer(throwing, true).serve(new StringReader(SyncWire.encode(request) + "\n"), out);

    var failed =
        assertInstanceOf(SyncWire.Failed.class, SyncWire.decodeResponse(out.toString().strip()));
    assertTrue(
        failed.message().contains("database is locked"),
        "the actionable root cause must surface, not the wrapper: " + failed.message());
  }

  @Test
  void aSwallowedStoreExceptionIsLoggedServerSide() throws Exception {
    var throwing =
        new FakeMain() {
          @Override
          public CommitOutcome commit(
              String entityId, Map<String, Object> snapshot, String expectedRev) {
            throw new IllegalStateException("database is locked");
          }
        };
    var request = new SyncWire.Commit("spec", "a", Map.of(), null);
    var captured = new ByteArrayOutputStream();
    var originalErr = System.err;
    System.setErr(new PrintStream(captured, true, StandardCharsets.UTF_8));
    try {
      new SyncRpcServer(throwing, true)
          .serve(new StringReader(SyncWire.encode(request) + "\n"), new StringWriter());
    } finally {
      System.setErr(originalErr);
    }

    assertTrue(
        captured.toString(StandardCharsets.UTF_8).contains("database is locked"),
        "a swallowed store exception must leave a server-side diagnostic");
  }
}
