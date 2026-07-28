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
    var input =
        request instanceof SyncWire.Fetch
            ? SyncWire.encode(request) + "\n"
            : verifiedRequest("spec", request);
    new SyncRpcServer(new FakeMain(), writable).serve(new StringReader(input), out);
    return lastResponse(out);
  }

  private static String verifiedRequest(String entityType, SyncWire.Request request) {
    return SyncWire.encode(new SyncWire.Fetch(entityType)) + "\n" + SyncWire.encode(request) + "\n";
  }

  private static SyncWire.Response lastResponse(StringWriter out) {
    return SyncWire.decodeResponse(out.toString().lines().toList().getLast());
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
  void aPreFloorNodeIsRefusedBeforeMainServesData() throws Exception {
    var failure =
        assertInstanceOf(SyncWire.Failed.class, serve(true, new SyncWire.Fetch("spec", null)));

    assertTrue(failure.message().contains(SyncWire.V1_UPGRADE_FLOOR));
    assertTrue(failure.message().contains("sail upgrade"));
  }

  @Test
  void aWritableServerAcceptsACommit() throws Exception {
    var response = serve(true, new SyncWire.Commit("spec", "a", Map.of(), null));
    assertEquals("1-x", assertInstanceOf(SyncWire.Committed.class, response).rev());
  }

  @Test
  void aCommitBeforeTheUpgradeFloorHandshakeIsRefused() throws Exception {
    var response = new StringWriter();

    new SyncRpcServer(new FakeMain(), true)
        .serve(
            new StringReader(
                SyncWire.encode(new SyncWire.Commit("spec", "a", Map.of(), null)) + "\n"),
            response);

    var failure = assertInstanceOf(SyncWire.Failed.class, lastResponse(response));
    assertTrue(failure.message().contains(SyncWire.V1_UPGRADE_FLOOR));
  }

  @Test
  void theCommitBindsThePushingHandleAsSyncProvenance() throws Exception {
    var seenPeer = new java.util.concurrent.atomic.AtomicReference<String>("unset");
    MainReplica capturing =
        new FakeMain() {
          @Override
          public CommitOutcome commit(String id, Map<String, Object> snapshot, String expectedRev) {
            seenPeer.set(ai.singlr.sail.store.SyncPeer.current());
            return new CommitOutcome.Accepted("1-x");
          }
        };
    var out = new StringWriter();
    new SyncRpcServer(Map.of("spec", capturing), new SyncPrincipal("sumesh", true), FdeRoster.EMPTY)
        .serve(
            new StringReader(
                verifiedRequest("spec", new SyncWire.Commit("spec", "a", Map.of(), null))),
            out);

    assertEquals(
        "sumesh", seenPeer.get(), "the change_log written during the commit must name the pusher");
  }

  @Test
  void aReadOnlyServerRefusesACommit() throws Exception {
    assertInstanceOf(
        SyncWire.Failed.class, serve(false, new SyncWire.Commit("spec", "a", Map.of(), null)));
  }

  private static SyncWire.Response serveRun(
      String handle, Map<String, Object> mainCurrent, SyncWire.Commit commit) throws Exception {
    return serveRun(handle, mainCurrent, mainCurrent == null ? null : "1-x", commit);
  }

  private static SyncWire.Response serveRun(
      String handle, Map<String, Object> mainCurrent, String mainRev, SyncWire.Commit commit)
      throws Exception {
    var main =
        new FakeMain() {
          @Override
          public Map<String, Object> current(String entityId) {
            return mainCurrent;
          }

          @Override
          public String currentRev(String entityId) {
            return mainRev;
          }
        };
    var out = new StringWriter();
    new SyncRpcServer(Map.of("run", main), new SyncPrincipal(handle, true), FdeRoster.EMPTY)
        .serve(new StringReader(verifiedRequest("run", commit)), out);
    return lastResponse(out);
  }

  @Test
  void aSessionCommitsItsOwnRun() throws Exception {
    var commit = new SyncWire.Commit("run", "r1", Map.of("node", "ada"), null);
    assertInstanceOf(SyncWire.Committed.class, serveRun("ada", null, commit));
  }

  @Test
  void aRunStampedWithAnotherNodeIsRejectedNotFailed() throws Exception {
    var commit = new SyncWire.Commit("run", "r1", Map.of("node", "grace"), null);
    assertInstanceOf(
        SyncWire.Rejected.class,
        serveRun("ada", null, commit),
        "an un-owned run push is rejected, not failed, so one bad run cannot abort the whole sync");
  }

  @Test
  void overwritingAnotherNodesRunReturnsMainsVersionToAdopt() throws Exception {
    var commit = new SyncWire.Commit("run", "r1", Map.of("node", "ada"), "1-x");
    var rejected =
        assertInstanceOf(SyncWire.Rejected.class, serveRun("ada", Map.of("node", "grace"), commit));
    assertEquals("1-x", rejected.currentRev());
    assertEquals(
        "grace",
        rejected.currentSnapshot().get("node"),
        "the node is handed main's authoritative version to adopt, converging instead of clobbering");
  }

  @Test
  void deletingAnotherNodesRunIsRejectedNotFailed() throws Exception {
    var commit = new SyncWire.Commit("run", "r1", null, "1-x");
    var rejected =
        assertInstanceOf(SyncWire.Rejected.class, serveRun("ada", Map.of("node", "grace"), commit));
    assertEquals("grace", rejected.currentSnapshot().get("node"));
  }

  @Test
  void deletingOnesOwnRunIsAccepted() throws Exception {
    var commit = new SyncWire.Commit("run", "r1", null, "1-x");
    assertInstanceOf(SyncWire.Committed.class, serveRun("ada", Map.of("node", "ada"), commit));
  }

  @Test
  void recreatingATombstonedRunIdIsRejectedSoTheNodeAdoptsTheDeletion() throws Exception {
    var commit = new SyncWire.Commit("run", "r1", Map.of("node", "ada"), "2-x");
    assertInstanceOf(SyncWire.Rejected.class, serveRun("ada", null, "2-x", commit));
  }

  @Test
  void replayingADeleteOverATombstoneStaysAllowed() throws Exception {
    var commit = new SyncWire.Commit("run", "r1", null, "2-x");
    assertInstanceOf(SyncWire.Committed.class, serveRun("ada", null, "2-x", commit));
  }

  @Test
  void aRunWithoutANodeStampIsRejectedNotFailed() throws Exception {
    var commit = new SyncWire.Commit("run", "r1", Map.of("status", "running"), null);
    assertInstanceOf(SyncWire.Rejected.class, serveRun("ada", null, commit));
  }

  @Test
  void aPrincipalWithoutAHandleCanNeverCommitARun() throws Exception {
    var commit = new SyncWire.Commit("run", "r1", Map.of("node", "ada"), null);
    assertInstanceOf(SyncWire.Failed.class, serveRun(null, null, commit));
  }

  @Test
  void anAcceptedCommitHandsItsTransitionsToTheSink() throws Exception {
    var main =
        new FakeMain() {
          private Map<String, Object> committed;

          @Override
          public Map<String, Object> current(String entityId) {
            return committed;
          }

          @Override
          public CommitOutcome commit(
              String entityId, Map<String, Object> snapshot, String expectedRev) {
            committed = snapshot;
            return new CommitOutcome.Accepted("1-x");
          }
        };
    var seen = new java.util.ArrayList<SyncTransition>();
    var out = new StringWriter();
    var commit = new SyncWire.Commit("spec", "auth", Map.of("status", "in_progress"), null);

    new SyncRpcServer(
            Map.of("spec", main), new SyncPrincipal(null, true), FdeRoster.EMPTY, seen::add)
        .serve(new StringReader(verifiedRequest("spec", commit)), out);

    assertInstanceOf(SyncWire.Committed.class, lastResponse(out));
    assertEquals(1, seen.size());
    assertEquals("in_progress", seen.getFirst().to());
  }

  @Test
  void aRejectedCommitNeverReachesTheSink() throws Exception {
    var main =
        new FakeMain() {
          @Override
          public CommitOutcome commit(
              String entityId, Map<String, Object> snapshot, String expectedRev) {
            return new CommitOutcome.Rejected("2-y", Map.of("status", "review"));
          }
        };
    var seen = new java.util.ArrayList<SyncTransition>();
    var out = new StringWriter();
    var commit = new SyncWire.Commit("spec", "auth", Map.of("status", "in_progress"), "1-x");

    new SyncRpcServer(
            Map.of("spec", main), new SyncPrincipal(null, true), FdeRoster.EMPTY, seen::add)
        .serve(new StringReader(verifiedRequest("spec", commit)), out);

    assertInstanceOf(SyncWire.Rejected.class, lastResponse(out));
    assertTrue(seen.isEmpty());
  }

  @Test
  void aThrowingSinkNeverFailsTheCommittedReply() throws Exception {
    var main =
        new FakeMain() {
          private Map<String, Object> committed;

          @Override
          public Map<String, Object> current(String entityId) {
            return committed;
          }

          @Override
          public CommitOutcome commit(
              String entityId, Map<String, Object> snapshot, String expectedRev) {
            committed = snapshot;
            return new CommitOutcome.Accepted("1-x");
          }
        };
    var out = new StringWriter();
    var commit = new SyncWire.Commit("spec", "auth", Map.of("status", "in_progress"), null);
    var captured = new ByteArrayOutputStream();
    var originalErr = System.err;
    System.setErr(new PrintStream(captured, true, StandardCharsets.UTF_8));
    try {
      new SyncRpcServer(
              Map.of("spec", main),
              new SyncPrincipal(null, true),
              FdeRoster.EMPTY,
              transition -> {
                throw new IllegalStateException("slack is down");
              })
          .serve(new StringReader(verifiedRequest("spec", commit)), out);
    } finally {
      System.setErr(originalErr);
    }

    assertInstanceOf(SyncWire.Committed.class, lastResponse(out));
    assertTrue(captured.toString(StandardCharsets.UTF_8).contains("slack is down"));
  }

  @Test
  void fetchFdesReturnsTheInjectedRoster() throws Exception {
    var roster = List.<Map<String, Object>>of(Map.of("handle", "ada", "role", "admin"));
    var out = new StringWriter();
    new SyncRpcServer(new FakeMain(), false, () -> roster)
        .serve(new StringReader(verifiedRequest("spec", new SyncWire.FetchFdes())), out);

    var response = (SyncWire.Fdes) lastResponse(out);
    assertEquals("ada", response.fdes().getFirst().get("handle"));
  }

  @Test
  void fetchFdesDefaultsToAnEmptyRoster() throws Exception {
    assertInstanceOf(SyncWire.Fdes.class, serve(true, new SyncWire.FetchFdes()));
  }

  @Test
  void fetchFdesBeforeTheUpgradeFloorHandshakeIsRefused() throws Exception {
    var out = new StringWriter();

    new SyncRpcServer(new FakeMain(), true)
        .serve(new StringReader(SyncWire.encode(new SyncWire.FetchFdes()) + "\n"), out);

    assertInstanceOf(SyncWire.Failed.class, lastResponse(out));
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

    new SyncRpcServer(throwing, true)
        .serve(new StringReader(verifiedRequest("spec", request)), out);

    assertInstanceOf(SyncWire.Failed.class, lastResponse(out));
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

    new SyncRpcServer(throwing, true)
        .serve(new StringReader(verifiedRequest("spec", request)), out);

    var failed = assertInstanceOf(SyncWire.Failed.class, lastResponse(out));
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
          .serve(new StringReader(verifiedRequest("spec", request)), new StringWriter());
    } finally {
      System.setErr(originalErr);
    }

    assertTrue(
        captured.toString(StandardCharsets.UTF_8).contains("database is locked"),
        "a swallowed store exception must leave a server-side diagnostic");
  }
}
