/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.sync;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.sail.identity.ActingAs;
import ai.singlr.sail.identity.Actor;
import ai.singlr.sail.identity.Role;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/** The server loop in isolation: the handshake, paging, batching, the write gate, and clean EOF. */
@ActingAs
class SyncRpcServerTest {

  private static final SyncWire.Hello HELLO = SyncWire.Hello.of("0.44.0", "node-box");

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
    public State state(String entityId) {
      return new State(current(entityId), currentRev(entityId));
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

  private static SyncRpcServer server(MainReplica main, String type, Actor principal) {
    return new SyncRpcServer(Map.of(type, main), principal, FdeRoster.EMPTY);
  }

  private static List<SyncWire.Response> serveLines(
      SyncRpcServer server, int frame, List<String> lines) throws Exception {
    var out = new ai.singlr.sail.sync.ByteStreams.Output();
    server.serve(
        new ai.singlr.sail.sync.ByteStreams.Input(String.join("\n", lines) + "\n"), out, frame);
    return out.toString().lines().map(SyncWire::decodeResponse).toList();
  }

  private static List<SyncWire.Response> serve(SyncRpcServer server, SyncWire.Request... requests)
      throws Exception {
    var lines = new ArrayList<String>();
    for (var request : requests) {
      lines.add(SyncWire.encode(request));
    }
    return serveLines(server, SyncWire.MAX_FRAME, lines);
  }

  private static SyncWire.Response after(SyncRpcServer server, SyncWire.Request request)
      throws Exception {
    return serve(server, HELLO, request).getLast();
  }

  private static SyncWire.Push push(String type, MainReplica.Offer... offers) {
    return new SyncWire.Push(type, List.of(offers));
  }

  private static SyncWire.Result only(SyncWire.Response response) {
    var results = assertInstanceOf(SyncWire.Results.class, response).results();
    assertEquals(1, results.size());
    return results.getFirst();
  }

  @Test
  void anUploadInventoryThatCannotFitAFrameIsRefusedBeforeStoringChunks() throws Exception {
    try (var main = new SyncBox("main")) {
      var requests = new ArrayList<String>();
      requests.add(SyncWire.encode(HELLO));
      var blobs =
          List.of(
              ai.singlr.sail.store.BlobStore.hash(new byte[] {1}),
              ai.singlr.sail.store.BlobStore.hash(new byte[] {2}));
      requests.add(SyncWire.encode(new SyncWire.Announce(blobs)));
      for (var index = 0; index < blobs.size(); index++) {
        var chunks = new ArrayList<String>();
        for (var chunk = 0; chunk < 7; chunk++)
          chunks.add(ai.singlr.sail.store.BlobStore.hash(new byte[] {(byte) index, (byte) chunk}));
        requests.add(
            SyncWire.encode(
                new SyncWire.Manifest(
                    new ai.singlr.sail.store.BlobStore.Manifest(
                        blobs.get(index), 7L * ai.singlr.sail.store.FastCdc.MIN, chunks))));
      }
      requests.add(SyncWire.encode(new SyncWire.Done()));
      var response =
          serveLines(main.server(Actor.sync("node", Role.MEMBER)), 900, requests).getLast();
      var failure = assertInstanceOf(SyncWire.Failed.class, response);
      assertEquals("protocol", failure.kind());
      assertTrue(failure.message().contains("announce fewer blobs"));
      assertEquals(
          0L, main.db.queryOne("SELECT COUNT(*) FROM chunks", row -> row.integer(0)).orElseThrow());
    }
  }

  @Test
  void contentRequiresAHandshakeAStoreAndTheCorrectExchangePhase() throws Exception {
    var fetch = new SyncWire.Fetch(List.of());
    var withoutStore = new SyncRpcServer(new FakeMain(), true);
    assertInstanceOf(SyncWire.Refuse.class, serve(withoutStore, fetch).getFirst());
    assertEquals(
        "protocol", assertInstanceOf(SyncWire.Failed.class, after(withoutStore, fetch)).kind());
    try (var main = new SyncBox("main")) {
      var failure =
          assertInstanceOf(
              SyncWire.Failed.class,
              after(main.server(Actor.sync("node", Role.MEMBER)), new SyncWire.Done()));
      assertEquals("protocol", failure.kind());
      assertTrue(failure.message().contains("Unexpected content operation"));
    }
  }

  @Test
  void closingDuringAnUploadManifestRunIsUnreachableAndStoresNothing() throws Exception {
    try (var main = new SyncBox("main")) {
      var failure =
          assertInstanceOf(
              SyncWire.Failed.class,
              after(
                  main.server(Actor.sync("node", Role.MEMBER)),
                  new SyncWire.Announce(
                      List.of(ai.singlr.sail.store.BlobStore.hash(new byte[] {1})))));
      assertEquals("unreachable", failure.kind());
      assertEquals(
          0L, main.db.queryOne("SELECT COUNT(*) FROM chunks", row -> row.integer(0)).orElseThrow());
    }
  }

  @Test
  void unheldContentCannotBeCommitted() throws Exception {
    try (var main = new SyncBox("main")) {
      var hash = ai.singlr.sail.store.BlobStore.hash(new byte[] {1});
      var response =
          after(
              main.server(Actor.sync("node", Role.MEMBER)),
              new SyncWire.Push(
                  "file",
                  List.of(
                      new MainReplica.Offer(
                          "project/file",
                          Map.of("content_hash", hash, "mode", 0644, "kind", "binary"),
                          null))));
      var results = assertInstanceOf(SyncWire.Results.class, response);
      assertEquals(
          "blob " + hash + " not held",
          assertInstanceOf(SyncWire.Refused.class, results.results().getFirst()).reason());
      assertTrue(new ai.singlr.sail.store.FileStore(main.db).list("project").isEmpty());
    }
  }

  @Test
  void tamperedAndTruncatedUploadChunksAreNeverStoredAndNameTheirFailure() throws Exception {
    var bytes = new byte[] {1, 2, 3};
    var hash = ai.singlr.sail.store.BlobStore.hash(bytes);
    for (var truncated : List.of(false, true)) {
      try (var main = new SyncBox("main")) {
        var request = new java.io.ByteArrayOutputStream();
        for (var content :
            List.of(
                SyncWire.encode(HELLO),
                SyncWire.encode(new SyncWire.Announce(List.of(hash))),
                SyncWire.encode(
                    new SyncWire.Manifest(
                        new ai.singlr.sail.store.BlobStore.Manifest(hash, 3, List.of(hash)))),
                SyncWire.encode(new SyncWire.Done()),
                SyncWire.encode(new SyncWire.Chunk(hash, 3))))
          request.writeBytes((content + "\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
        request.writeBytes(truncated ? new byte[] {1, 2} : new byte[] {3, 2, 1});
        var output = new ByteStreams.Output();
        main.server(Actor.sync("node", Role.MEMBER))
            .serve(new java.io.ByteArrayInputStream(request.toByteArray()), output);
        var failed =
            assertInstanceOf(
                SyncWire.Failed.class,
                SyncWire.decodeResponse(output.toString().lines().toList().getLast()));
        assertEquals(truncated ? "unreachable" : "protocol", failed.kind());
        if (!truncated) assertTrue(failed.message().contains(hash));
        assertEquals(
            0,
            main.db.queryOne("SELECT COUNT(*) FROM chunks", row -> row.integer(0)).orElseThrow());
      }
    }
  }

  @Test
  void helloIsRequiredBeforeAnythingElse() throws Exception {
    for (var first :
        List.<SyncWire.Request>of(
            new SyncWire.Heads(),
            new SyncWire.Pull("spec", 0, 10),
            new SyncWire.Need("spec", List.of("a")),
            push("spec", new MainReplica.Offer("a", Map.of(), null)),
            new SyncWire.FetchFdes())) {
      var refusal =
          assertInstanceOf(
              SyncWire.Refuse.class,
              serve(new SyncRpcServer(new FakeMain(), true), first).getFirst());
      assertEquals("hello required", refusal.reason());
    }
  }

  @Test
  void anUnknownOpBeforeHelloIsRefusedNamingTheRemedy() throws Exception {
    var fetch = "{\"op\": \"fetch\", \"entityType\": \"spec\", \"upgradeFloor\": \"0.34.0\"}";
    var out = new ai.singlr.sail.sync.ByteStreams.Output();
    new SyncRpcServer(new FakeMain(), true)
        .serve(new ai.singlr.sail.sync.ByteStreams.Input(fetch + "\n"), out);
    var line = out.toString().strip();
    var refusal = assertInstanceOf(SyncWire.Refuse.class, SyncWire.decodeResponse(line));
    assertEquals("upgrade to " + SyncWire.UPGRADE_FLOOR + ": sail upgrade", refusal.reason());
  }

  @Test
  void aFloorBelowMainsIsRefusedWithTheRemedy() throws Exception {
    var refusal =
        assertInstanceOf(
            SyncWire.Refuse.class,
            serve(
                    new SyncRpcServer(new FakeMain(), true),
                    new SyncWire.Hello(4, "0.43.3", "0.34.0", "b"))
                .getFirst());
    assertEquals("upgrade to " + SyncWire.UPGRADE_FLOOR + ": sail upgrade", refusal.reason());
  }

  @Test
  void aFloorAboveMainsIsRefusedNamingTheOrder() throws Exception {
    var server =
        new SyncRpcServer(
            Map.of("spec", new FakeMain()),
            Actor.sync("node", Role.MEMBER),
            FdeRoster.EMPTY,
            SyncTransitionSink.NONE,
            SyncRpcServer.ChangeHeads.NONE,
            "0.44.2");
    var refusal =
        assertInstanceOf(
            SyncWire.Refuse.class,
            serve(server, new SyncWire.Hello(4, "0.51.0", "0.50.0", "b")).getFirst());
    assertEquals("main is 0.44.2, this node is 0.51.0: upgrade main first", refusal.reason());
  }

  @Test
  void theSameFloorAtANewerPatchIsWelcomedWithMainsIdentity() throws Exception {
    var welcome =
        assertInstanceOf(
            SyncWire.Welcome.class,
            serve(
                    new SyncRpcServer(new FakeMain(), true),
                    new SyncWire.Hello(4, "0.44.9", SyncWire.UPGRADE_FLOOR, "b"))
                .getFirst());
    assertEquals(new SyncWire.Welcome(SyncWire.PROTOCOL, SyncWire.UPGRADE_FLOOR, "main"), welcome);
  }

  @Test
  void aWrongProtocolAMalformedFloorOrAMissingBoxIsRefused() throws Exception {
    for (var hello :
        List.of(
            new SyncWire.Hello(3, "0.44.0", SyncWire.UPGRADE_FLOOR, "b"),
            new SyncWire.Hello(4, "0.44.0", "latest", "b"),
            new SyncWire.Hello(4, "0.44.0", null, "b"),
            new SyncWire.Hello(4, "0.44.0", SyncWire.UPGRADE_FLOOR, null),
            new SyncWire.Hello(4, "0.44.0", SyncWire.UPGRADE_FLOOR, " "))) {
      var refusal =
          assertInstanceOf(
              SyncWire.Refuse.class,
              serve(new SyncRpcServer(new FakeMain(), true), hello).getFirst());
      assertTrue(refusal.reason().contains("sail upgrade"), refusal.reason());
    }
  }

  @Test
  void aSecondHelloIsAProtocolFailureAndTheSessionStaysWelcomed() throws Exception {
    var replies =
        serve(new SyncRpcServer(new FakeMain(), true), HELLO, HELLO, new SyncWire.Heads());
    assertInstanceOf(SyncWire.Welcome.class, replies.get(0));
    assertEquals("protocol", assertInstanceOf(SyncWire.Failed.class, replies.get(1)).kind());
    assertInstanceOf(SyncWire.Tips.class, replies.get(2));
  }

  @Test
  void headsAnswersEveryRegisteredTypeInRegistryOrder() throws Exception {
    try (var main = new SyncBox("main")) {
      main.specs.create(SyncBox.spec("auth", "Auth", "pending"));
      var tips =
          assertInstanceOf(
              SyncWire.Tips.class,
              after(main.server(Actor.sync("n", Role.MEMBER)), new SyncWire.Heads()));
      assertEquals(
          SyncedEntities.all().stream().map(SyncedEntities.Entity::type).toList(),
          List.copyOf(tips.tips().keySet()));
      assertEquals(main.replica.maxSeq(), tips.tips().get("spec"));
      assertEquals(0L, tips.tips().get("file"));
    }
  }

  @Test
  void tenThousandSpecEntriesPageUnderTheFrame() throws Exception {
    try (var main = new SyncBox("main")) {
      main.db.transaction(
          () -> {
            for (var i = 0; i < 10_000; i++) {
              main.specs.create(SyncBox.spec("spec-" + i, "Spec " + i, "pending"));
            }
          });
      var page =
          assertInstanceOf(
              SyncWire.Page.class,
              after(
                  main.server(Actor.sync("n", Role.MEMBER)), new SyncWire.Pull("spec", 0, 20_000)));
      assertEquals(10_000, page.entries().size());
      assertTrue(page.done());
      assertEquals(main.replica.maxSeq(), page.next());
      assertTrue(SyncWire.encode(page).length() <= SyncWire.MAX_FRAME);
    }
  }

  @Test
  void anEntryNearTheBoundIsAPageOfOneAndTheNextPullContinuesFromIt() throws Exception {
    try (var main = new SyncBox("main")) {
      main.specs.create(SyncBox.spec("a", "x".repeat(600), "pending"));
      main.specs.create(SyncBox.spec("b", "y".repeat(600), "pending"));
      var server = main.server(Actor.sync("n", Role.MEMBER));
      var first =
          assertInstanceOf(
              SyncWire.Page.class,
              serveLines(
                      server,
                      1_500,
                      List.of(
                          SyncWire.encode(HELLO),
                          SyncWire.encode(new SyncWire.Pull("spec", 0, 100))))
                  .getLast());
      assertEquals(List.of("a"), first.entries().stream().map(SyncWire.Entry::id).toList());
      assertFalse(first.done());
      assertTrue(first.next() < first.maxSeq());
      var second =
          assertInstanceOf(
              SyncWire.Page.class,
              serveLines(
                      server,
                      1_500,
                      List.of(
                          SyncWire.encode(HELLO),
                          SyncWire.encode(new SyncWire.Pull("spec", first.next(), 100))))
                  .getLast());
      assertEquals(List.of("b"), second.entries().stream().map(SyncWire.Entry::id).toList());
      assertTrue(second.done());
      assertEquals(second.maxSeq(), second.next());
    }
  }

  @Test
  void anEntryOverTheBoundIsRefusedNamingTheTypeIdAndSize() throws Exception {
    try (var main = new SyncBox("main")) {
      main.specs.create(SyncBox.spec("huge", "x".repeat(700), "pending"));
      var server = main.server(Actor.sync("n", Role.MEMBER));
      for (var request :
          List.<SyncWire.Request>of(
              new SyncWire.Pull("spec", 0, 100), new SyncWire.Need("spec", List.of("huge")))) {
        var failed =
            assertInstanceOf(
                SyncWire.Failed.class,
                serveLines(server, 700, List.of(SyncWire.encode(HELLO), SyncWire.encode(request)))
                    .getLast());
        assertEquals("protocol", failed.kind());
        assertTrue(failed.message().startsWith("spec: huge:"), failed.message());
        assertTrue(failed.message().contains("bytes"), failed.message());
      }
    }
  }

  @Test
  void sinceAtMaxSeqReturnsAnEmptyDonePage() throws Exception {
    try (var main = new SyncBox("main")) {
      main.specs.create(SyncBox.spec("a", "A", "pending"));
      var high = main.replica.maxSeq();
      var page =
          assertInstanceOf(
              SyncWire.Page.class,
              after(
                  main.server(Actor.sync("n", Role.MEMBER)), new SyncWire.Pull("spec", high, 100)));
      assertTrue(page.entries().isEmpty());
      assertTrue(page.done());
      assertEquals(high, page.next());
      assertEquals(high, page.maxSeq());
    }
  }

  @Test
  void tombstonesPageAsEntriesWithoutASnapshot() throws Exception {
    try (var main = new SyncBox("main")) {
      main.specs.create(SyncBox.spec("gone", "Gone", "pending"));
      main.specs.delete("gone");
      main.specs.create(SyncBox.spec("kept", "Kept", "pending"));
      var page =
          assertInstanceOf(
              SyncWire.Page.class,
              after(main.server(Actor.sync("n", Role.MEMBER)), new SyncWire.Pull("spec", 0, 100)));
      assertEquals(
          List.of("gone", "kept"), page.entries().stream().map(SyncWire.Entry::id).toList());
      var tombstone = page.entries().getFirst();
      assertTrue(tombstone.deleted());
      assertNull(tombstone.snapshot());
      assertEquals(main.replica.currentRev("gone"), tombstone.rev());
      assertEquals("Kept", page.entries().get(1).snapshot().get("title"));
    }
  }

  @Test
  void needAnswersKnownIdsInOrderConsumesUnknownOnesAndCutsAtTheFrame() throws Exception {
    try (var main = new SyncBox("main")) {
      main.specs.create(SyncBox.spec("a", "x".repeat(600), "pending"));
      main.specs.create(SyncBox.spec("b", "y".repeat(600), "pending"));
      var server = main.server(Actor.sync("n", Role.MEMBER));
      var whole =
          assertInstanceOf(
              SyncWire.Page.class,
              after(server, new SyncWire.Need("spec", List.of("ghost", "b", "a"))));
      assertEquals(List.of("b", "a"), whole.entries().stream().map(SyncWire.Entry::id).toList());
      assertEquals(3, whole.next());
      assertTrue(whole.done());
      assertEquals(0, whole.entries().getFirst().seq());

      var cut =
          assertInstanceOf(
              SyncWire.Page.class,
              serveLines(
                      server,
                      1_500,
                      List.of(
                          SyncWire.encode(HELLO),
                          SyncWire.encode(new SyncWire.Need("spec", List.of("ghost", "a", "b")))))
                  .getLast());
      assertEquals(List.of("a"), cut.entries().stream().map(SyncWire.Entry::id).toList());
      assertEquals(2, cut.next());
      assertFalse(cut.done());
    }
  }

  @Test
  void aPushBatchAnswersOneResultPerOfferInOrder() throws Exception {
    var results =
        assertInstanceOf(
            SyncWire.Results.class,
            after(
                new SyncRpcServer(new FakeMain(), true),
                push(
                    "spec",
                    new MainReplica.Offer("a", Map.of(), null),
                    new MainReplica.Offer("b", Map.of(), "1-x"),
                    new MainReplica.Offer("c", null, "2-y"))));
    assertEquals(
        List.of("a", "b", "c"), results.results().stream().map(SyncWire.Result::id).toList());
    results
        .results()
        .forEach(
            result -> assertEquals("1-x", assertInstanceOf(SyncWire.Accepted.class, result).rev()));
    var empty =
        assertInstanceOf(
            SyncWire.Results.class, after(new SyncRpcServer(new FakeMain(), true), push("spec")));
    assertTrue(empty.results().isEmpty());
  }

  @Test
  void aReadOnlySessionMayPullAndEachOfItsOffersIsDeniedWithMainsVersion() throws Exception {
    try (var main = new SyncBox("main")) {
      main.specs.create(SyncBox.spec("auth", "Auth", "pending"));
      var server = main.server(Actor.sync(null, Role.VIEWER));
      var mainsRev = main.specs.latestRev("auth");
      var replies =
          serve(
              server,
              HELLO,
              new SyncWire.Pull("spec", 0, 10),
              push(
                  "spec",
                  new MainReplica.Offer("auth", Map.of("title", "Viewer edit"), mainsRev),
                  new MainReplica.Offer("born", Map.of("title", "Viewer spec"), null)),
              push("run", new MainReplica.Offer("r1", Map.of("node", "ada"), null)));
      assertInstanceOf(SyncWire.Page.class, replies.get(1));
      var results = assertInstanceOf(SyncWire.Results.class, replies.get(2)).results();
      var edit = assertInstanceOf(SyncWire.Denied.class, results.get(0));
      assertTrue(edit.reason().contains("read-only"), edit.reason());
      assertEquals(mainsRev, edit.rev());
      assertEquals("Auth", edit.snapshot().get("title"));
      var born = assertInstanceOf(SyncWire.Denied.class, results.get(1));
      assertNull(born.rev(), "main holds none of a spec it never took");
      assertNull(born.snapshot());
      assertInstanceOf(
          SyncWire.Denied.class,
          only(replies.get(3)),
          "a viewer's run is decided on authority before it is checked for a handle");
      assertEquals("Auth", main.specs.findById("auth").orElseThrow().title());
      assertTrue(main.specs.findById("born").isEmpty());
    }
  }

  @Test
  void theAuthoritysDenialReachesTheNodeAsDeniedForThatOfferAlone() throws Exception {
    var deciding =
        new FakeMain() {
          @Override
          public CommitOutcome commit(String id, Map<String, Object> snapshot, String expected) {
            return "forged".equals(id)
                ? new CommitOutcome.Denied("not yours", "3-m", Map.of("title", "main's"))
                : new CommitOutcome.Accepted("1-x");
          }
        };
    var results =
        assertInstanceOf(
                SyncWire.Results.class,
                after(
                    server(deciding, "spec", Actor.sync("ada", Role.MEMBER)),
                    push(
                        "spec",
                        new MainReplica.Offer("forged", Map.of(), null),
                        new MainReplica.Offer("honest", Map.of(), null))))
            .results();
    assertEquals(
        new SyncWire.Denied("forged", "not yours", "3-m", Map.of("title", "main's")),
        results.get(0));
    assertInstanceOf(SyncWire.Accepted.class, results.get(1));
  }

  @Test
  void aDenialWhoseVersionWouldOverflowTheFrameIsAnsweredWithoutIt() throws Exception {
    var large = Map.<String, Object>of("body", "x".repeat(700));
    var deciding =
        new FakeMain() {
          @Override
          public CommitOutcome commit(String id, Map<String, Object> snapshot, String expected) {
            return new CommitOutcome.Denied("not yours", "2-m", large);
          }
        };
    var replies =
        serveLines(
            server(deciding, "spec", Actor.sync("ada", Role.MEMBER)),
            1200,
            List.of(
                SyncWire.encode(HELLO),
                SyncWire.encode(
                    push(
                        "spec",
                        new MainReplica.Offer("a", Map.of(), null),
                        new MainReplica.Offer("b", Map.of(), null)))));
    var results = assertInstanceOf(SyncWire.Results.class, replies.get(1)).results();
    assertEquals(new SyncWire.Denied("a", "not yours", "2-m", large), results.get(0));
    assertEquals(new SyncWire.Denied("b", "not yours", null, null, false), results.get(1));
  }

  @Test
  void anUnknownEntityTypeIsRefusedForPullNeedAndPushNamingIt() throws Exception {
    for (var request :
        List.<SyncWire.Request>of(
            new SyncWire.Pull("bogus", 0, 10),
            new SyncWire.Need("bogus", List.of("a")),
            push("bogus", new MainReplica.Offer("a", Map.of(), null)))) {
      var failed =
          assertInstanceOf(
              SyncWire.Failed.class, after(new SyncRpcServer(new FakeMain(), true), request));
      assertTrue(failed.message().startsWith("bogus:"), failed.message());
      assertTrue(failed.message().contains("Unknown entity type"));
    }
    var limit =
        assertInstanceOf(
            SyncWire.Failed.class,
            after(new SyncRpcServer(new FakeMain(), true), new SyncWire.Pull("spec", 0, 0)));
    assertEquals("protocol", limit.kind());
  }

  @Test
  void malformedAndOversizedFramesAfterHelloNameTheLastContextAndCloseTheSession()
      throws Exception {
    var pull = SyncWire.encode(new SyncWire.Pull("message", 0, 10));
    for (var bad : List.of("{", "x".repeat(600))) {
      var replies =
          serveLines(
              server(new FakeMain(), "message", Actor.sync("node", Role.MEMBER)),
              512,
              List.of(SyncWire.encode(HELLO), pull, bad, pull));
      assertEquals(3, replies.size());
      assertInstanceOf(SyncWire.Welcome.class, replies.get(0));
      assertInstanceOf(SyncWire.Page.class, replies.get(1));
      var failed = assertInstanceOf(SyncWire.Failed.class, replies.get(2));
      assertEquals("protocol", failed.kind());
      assertTrue(failed.message().startsWith("message:"), failed.message());
    }
  }

  @Test
  void anEmptyStreamOrAByeEndsTheSessionCleanly() throws Exception {
    new SyncRpcServer(new FakeMain(), true)
        .serve(
            new ai.singlr.sail.sync.ByteStreams.Input(""),
            new ai.singlr.sail.sync.ByteStreams.Output());
    var replies =
        serve(
            new SyncRpcServer(new FakeMain(), true),
            HELLO,
            new SyncWire.Bye(),
            new SyncWire.Heads());
    assertEquals(1, replies.size());
  }

  @Test
  void fetchFdesReturnsTheInjectedRosterAndDefaultsToEmpty() throws Exception {
    var roster = List.<Map<String, Object>>of(Map.of("handle", "ada", "role", "admin"));
    var fdes =
        assertInstanceOf(
            SyncWire.Fdes.class,
            after(
                new SyncRpcServer(new FakeMain(), false, () -> roster), new SyncWire.FetchFdes()));
    assertEquals("ada", fdes.fdes().getFirst().get("handle"));
    assertTrue(
        assertInstanceOf(
                SyncWire.Fdes.class,
                after(new SyncRpcServer(new FakeMain(), true), new SyncWire.FetchFdes()))
            .fdes()
            .isEmpty());
  }

  @Test
  void aStoreExceptionAnywhereBecomesAFailedResponseNamingTheTypeAndTheRootCause()
      throws Exception {
    var failing =
        new FakeMain() {
          @Override
          public CommitOutcome commit(String id, Map<String, Object> snapshot, String expectedRev) {
            throw new IllegalStateException("commit failed", new RuntimeException("disk full"));
          }

          @Override
          public long maxSeq() {
            throw new IllegalStateException("database is locked");
          }
        };
    var server = server(failing, "file", Actor.sync("node", Role.MEMBER));
    var captured = new ByteArrayOutputStream();
    var originalErr = System.err;
    System.setErr(new PrintStream(captured, true, StandardCharsets.UTF_8));
    List<SyncWire.Response> replies;
    try {
      replies =
          serve(
              server,
              HELLO,
              push("file", new MainReplica.Offer("acme/data.bin", Map.of(), null)),
              new SyncWire.Pull("file", 0, 10),
              new SyncWire.Heads());
    } finally {
      System.setErr(originalErr);
    }
    var pushFailure = assertInstanceOf(SyncWire.Failed.class, replies.get(1));
    assertEquals("store", pushFailure.kind());
    assertTrue(pushFailure.message().startsWith("file:"), pushFailure.message());
    assertTrue(pushFailure.message().contains("disk full"), pushFailure.message());
    assertTrue(
        assertInstanceOf(SyncWire.Failed.class, replies.get(2))
            .message()
            .contains("database is locked"));
    assertTrue(
        assertInstanceOf(SyncWire.Failed.class, replies.get(3)).message().startsWith("heads:"));
    assertTrue(
        captured.toString(StandardCharsets.UTF_8).contains("disk full"),
        "a swallowed store exception must leave a server-side diagnostic");
  }

  @Test
  void anOfferBindsThePushingHandleAsSyncProvenance() throws Exception {
    var seenPeer = new AtomicReference<>("unset");
    var capturing =
        new FakeMain() {
          @Override
          public CommitOutcome commit(String id, Map<String, Object> snapshot, String expectedRev) {
            seenPeer.set(Actor.current().peer());
            return new CommitOutcome.Accepted("1-x");
          }
        };
    after(
        server(capturing, "spec", Actor.sync("sumesh", Role.MEMBER)),
        push("spec", new MainReplica.Offer("a", Map.of(), null)));
    assertEquals(
        "sumesh", seenPeer.get(), "the change_log written during the commit must name the pusher");
  }

  private static SyncWire.Result serveRun(String handle, MainReplica.Offer offer) throws Exception {
    return only(
        after(server(new FakeMain(), "run", Actor.sync(handle, Role.MEMBER)), push("run", offer)));
  }

  @Test
  void aPageEntryIsOneAtomicReadOfSnapshotAndRev() throws Exception {
    var torn =
        new FakeMain() {
          private int reads;

          @Override
          public Set<String> entityIds() {
            return Set.of("auth");
          }

          @Override
          public Map<String, Object> current(String entityId) {
            reads++;
            return Map.of("status", "pending");
          }

          @Override
          public String currentRev(String entityId) {
            return reads == 0 ? "1-x" : "2-y";
          }

          @Override
          public State state(String entityId) {
            return new State(Map.of("status", "pending"), "1-x");
          }
        };
    var page =
        assertInstanceOf(
            SyncWire.Page.class,
            after(
                server(torn, "spec", Actor.sync(null, Role.MEMBER)),
                new SyncWire.Need("spec", List.of("auth"))));
    var entry = page.entries().getFirst();
    assertEquals("1-x", entry.rev(), "the rev must belong to the snapshot beside it");
    assertEquals(Map.of("status", "pending"), entry.snapshot());
  }

  @Test
  void aBatchOfStaleResultsFitsTheFrameWhateverMainsVersionsWeigh() throws Exception {
    var huge = Map.<String, Object>of("body", "x".repeat(6 << 20));
    var moved =
        new FakeMain() {
          @Override
          public Map<String, Object> current(String entityId) {
            return huge;
          }

          @Override
          public String currentRev(String entityId) {
            return "9-z";
          }

          @Override
          public CommitOutcome commit(
              String entityId, Map<String, Object> snapshot, String expectedRev) {
            return new CommitOutcome.Rejected("9-z", huge);
          }
        };
    var response =
        after(
            server(moved, "spec", Actor.sync(null, Role.MEMBER)),
            push(
                "spec",
                new MainReplica.Offer("a", null, "1-x"),
                new MainReplica.Offer("b", null, "1-x"),
                new MainReplica.Offer("c", null, "1-x")));
    var results = assertInstanceOf(SyncWire.Results.class, response).results();
    assertEquals(3, results.size());
    results.forEach(result -> assertInstanceOf(SyncWire.Stale.class, result));
    assertTrue(
        SyncWire.encode(response).length() < 1024,
        "three 6 MiB versions must not ride the results frame");
  }

  @Test
  void aPrincipalWithoutAHandleHasItsRunOfferRefusedInsideTheBatch() throws Exception {
    var refused =
        assertInstanceOf(
            SyncWire.Refused.class,
            serveRun(null, new MainReplica.Offer("r1", Map.of("node", "ada"), null)));
    assertEquals("r1", refused.id());
    assertTrue(refused.reason().contains("handle"), refused.reason());
  }

  private static FakeMain remembering() {
    return new FakeMain() {
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
  }

  @Test
  void anAcceptedOfferHandsItsTransitionsToTheSinkAndARejectedOneNever() throws Exception {
    var seen = new ArrayList<SyncTransition>();
    var accepted =
        after(
            new SyncRpcServer(
                Map.of("spec", remembering()),
                Actor.sync(null, Role.MEMBER),
                FdeRoster.EMPTY,
                seen::add),
            push("spec", new MainReplica.Offer("auth", Map.of("status", "in_progress"), null)));
    assertInstanceOf(SyncWire.Accepted.class, only(accepted));
    assertEquals(1, seen.size());
    assertEquals("in_progress", seen.getFirst().to());

    var rejecting =
        new FakeMain() {
          @Override
          public CommitOutcome commit(
              String entityId, Map<String, Object> snapshot, String expectedRev) {
            return new CommitOutcome.Rejected("2-y", Map.of("status", "review"));
          }
        };
    var quiet = new ArrayList<SyncTransition>();
    var rejected =
        after(
            new SyncRpcServer(
                Map.of("spec", rejecting),
                Actor.sync(null, Role.MEMBER),
                FdeRoster.EMPTY,
                quiet::add),
            push("spec", new MainReplica.Offer("auth", Map.of("status", "in_progress"), "1-x")));
    assertInstanceOf(SyncWire.Stale.class, only(rejected));
    assertTrue(quiet.isEmpty());
  }

  @Test
  void aThrowingSinkNeverFailsTheAcceptedResult() throws Exception {
    var captured = new ByteArrayOutputStream();
    var originalErr = System.err;
    System.setErr(new PrintStream(captured, true, StandardCharsets.UTF_8));
    SyncWire.Response response;
    try {
      response =
          after(
              new SyncRpcServer(
                  Map.of("spec", remembering()),
                  Actor.sync(null, Role.MEMBER),
                  FdeRoster.EMPTY,
                  transition -> {
                    throw new IllegalStateException("slack is down");
                  }),
              push("spec", new MainReplica.Offer("auth", Map.of("status", "in_progress"), null)));
    } finally {
      System.setErr(originalErr);
    }
    assertInstanceOf(SyncWire.Accepted.class, only(response));
    assertTrue(captured.toString(StandardCharsets.UTF_8).contains("slack is down"));
  }
}
