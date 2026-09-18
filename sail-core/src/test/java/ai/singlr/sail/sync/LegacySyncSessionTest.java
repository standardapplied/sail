/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.sync;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.PipedReader;
import java.io.PipedWriter;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The one release of mixed-fleet fallback: a protocol-4 node meeting a protocol-3 main and back.
 */
class LegacySyncSessionTest {

  private SyncBox main;
  private SyncBox node;

  @BeforeEach
  void setUp() {
    main = new SyncBox("main");
    node = new SyncBox("node");
  }

  @AfterEach
  void tearDown() {
    node.close();
    main.close();
  }

  /** What a 0.43 main does on its stdio: whole tables, one commit at a time, no hello. */
  private void serveProtocol3(Reader in, Writer out) throws IOException {
    while (true) {
      var line = SyncWire.readFramed(in);
      if (line == null) return;
      LegacySyncSession.Request request;
      try {
        request = LegacySyncSession.decodeRequest(line);
      } catch (RuntimeException e) {
        reply(out, new LegacySyncSession.Failed("session: " + e.getMessage(), "protocol"));
        continue;
      }
      switch (request) {
        case LegacySyncSession.Bye ignored -> {
          return;
        }
        case LegacySyncSession.Fetch fetch -> {
          var entities = new LinkedHashMap<String, LegacySyncSession.Snapshot>();
          for (var id : main.replica.entityIds()) {
            entities.put(
                id,
                new LegacySyncSession.Snapshot(
                    main.replica.currentRev(id), main.replica.current(id)));
          }
          reply(
              out,
              new LegacySyncSession.Fetched(
                  "maindevbox", main.replica.maxSeq(), entities, LegacySyncSession.FLOOR));
        }
        case LegacySyncSession.Commit commit -> {
          var outcome =
              main.replica.commit(commit.entityId(), commit.snapshot(), commit.expectedRev());
          reply(
              out,
              switch (outcome) {
                case CommitOutcome.Accepted a ->
                    new LegacySyncSession.Committed(a.rev(), main.replica.maxSeq());
                case CommitOutcome.Rejected r ->
                    new LegacySyncSession.Rejected(r.currentRev(), r.currentSnapshot());
              });
        }
        case LegacySyncSession.FetchFdes ignored ->
            reply(out, new LegacySyncSession.Fdes(List.of()));
      }
    }
  }

  private static void reply(Writer out, LegacySyncSession.Response response) throws IOException {
    out.write(LegacySyncSession.encode(response));
    out.write('\n');
    out.flush();
  }

  private record Pipe(BufferedReader clientIn, PipedWriter toServer, Thread server) {}

  private Pipe protocol3Main() throws IOException {
    var toServer = new PipedWriter();
    var serverIn = new BufferedReader(new PipedReader(toServer));
    var toClient = new PipedWriter();
    var clientIn = new BufferedReader(new PipedReader(toClient));
    var thread =
        Thread.ofVirtual()
            .start(
                () -> {
                  try (toClient) {
                    serveProtocol3(serverIn, toClient);
                  } catch (IOException e) {
                    throw new UncheckedIOException(e);
                  }
                });
    return new Pipe(clientIn, toServer, thread);
  }

  @Test
  void aProtocol4NodeAgainstAProtocol3MainSyncsThroughTheLegacySession() throws Exception {
    node.specs.create(SyncBox.spec("auth", "Auth", "pending"));
    main.specs.create(SyncBox.spec("board", "Board", "pending"));
    var pipe = protocol3Main();
    var notices = new ArrayList<String>();
    SyncSession.TypeReport report;
    try (var session =
        SyncSession.open(
            pipe.clientIn(),
            pipe.toServer(),
            SyncWire.Hello.of("0.44.0", "node-box"),
            notices::add)) {
      assertInstanceOf(LegacySyncSession.class, session);
      report = session.reconcile("spec", node.replica);
      assertTrue(session.fetchFdes().isEmpty());
    } finally {
      pipe.server().join();
    }
    assertEquals(1, report.report().pushed());
    assertEquals(1, report.report().pulled());
    assertEquals(1, report.pages());
    assertEquals("Auth", main.specs.findById("auth").orElseThrow().title());
    assertEquals("Board", node.specs.findById("board").orElseThrow().title());
    assertEquals(1, notices.size());
    assertTrue(notices.getFirst().contains("v3"));
  }

  @Test
  void theLegacyCheckpointIsTheFetchedHighWaterNotTheOneAfterOwnCommits() throws Exception {
    main.specs.create(SyncBox.spec("board", "Board", "pending"));
    var fetchedHighWater = main.replica.maxSeq();
    node.specs.create(SyncBox.spec("auth", "Auth", "pending"));
    var pipe = protocol3Main();
    try (var session =
        SyncSession.open(
            pipe.clientIn(), pipe.toServer(), SyncWire.Hello.of("0.44.0", "node-box"), n -> {})) {
      session.reconcile("spec", node.replica);
    } finally {
      pipe.server().join();
    }
    assertEquals(fetchedHighWater, node.syncState.checkpoint("maindevbox", "spec"));
    assertTrue(main.replica.maxSeq() > fetchedHighWater, "the commit moved main past the fetch");
  }

  @Test
  void aProtocol3ClientAgainstAProtocol4ServerIsRefusedNamingTheVersion() throws Exception {
    node.specs.create(SyncBox.spec("auth", "Auth", "pending"));
    var toServer = new PipedWriter();
    var serverIn = new BufferedReader(new PipedReader(toServer));
    var toClient = new PipedWriter();
    var clientIn = new BufferedReader(new PipedReader(toClient));
    var server = main.server(new SyncPrincipal("node", true));
    var thread =
        Thread.ofVirtual()
            .start(
                () -> {
                  try (toClient) {
                    server.serve(serverIn, toClient);
                  } catch (IOException e) {
                    throw new UncheckedIOException(e);
                  }
                });
    SyncTransportException failure;
    try (var legacy = new LegacySyncSession(clientIn, toServer)) {
      failure =
          assertThrows(SyncTransportException.class, () -> legacy.reconcile("spec", node.replica));
    } finally {
      thread.join();
    }
    assertEquals("refused", failure.kind());
    assertTrue(
        failure.getMessage().contains("upgrade to " + SyncWire.UPGRADE_FLOOR),
        failure.getMessage());
    assertTrue(failure.getMessage().contains("sail upgrade"), failure.getMessage());
    assertTrue(main.specs.findById("auth").isEmpty(), "nothing was served or taken");
  }

  @Test
  void onlyTheExactProtocol3AnswerToHelloIsRecognized() {
    assertTrue(
        LegacySyncSession.answeredHello(
            LegacySyncSession.encode(
                new LegacySyncSession.Failed("session: Unknown sync op: hello", "protocol"))));
    assertFalse(
        LegacySyncSession.answeredHello(
            LegacySyncSession.encode(new LegacySyncSession.Failed("session: disk full", "store"))));
    assertFalse(
        LegacySyncSession.answeredHello(
            LegacySyncSession.encode(
                new LegacySyncSession.Failed("Unknown sync op: hello", "refused"))));
    assertFalse(
        LegacySyncSession.answeredHello(SyncWire.encode(new SyncWire.Refuse("hello required"))));
    assertFalse(LegacySyncSession.answeredHello("not json"));
    assertFalse(LegacySyncSession.answeredHello("{\"op\": \"welcome\"}"));
  }

  @Test
  void theLegacyCodecRoundTripsEveryProtocol3Message() {
    var requests =
        List.<LegacySyncSession.Request>of(
            new LegacySyncSession.Fetch("spec", LegacySyncSession.FLOOR),
            new LegacySyncSession.Commit("spec", "auth", Map.of("title", "Auth"), "1-x"),
            new LegacySyncSession.Commit("file", "acme/x", null, null),
            new LegacySyncSession.FetchFdes(),
            new LegacySyncSession.Bye());
    for (var request : requests) {
      assertEquals(request, LegacySyncSession.decodeRequest(LegacySyncSession.encode(request)));
    }
    var entities = new LinkedHashMap<String, LegacySyncSession.Snapshot>();
    entities.put("auth", new LegacySyncSession.Snapshot("3-abc", Map.of("title", "Auth")));
    entities.put("gone", new LegacySyncSession.Snapshot("4-def", null));
    var responses =
        List.<LegacySyncSession.Response>of(
            new LegacySyncSession.Fetched("maindevbox", 42, entities, LegacySyncSession.FLOOR),
            new LegacySyncSession.Committed("7-feed", 99),
            new LegacySyncSession.Rejected("9-feed", Map.of("title", "Main")),
            new LegacySyncSession.Rejected("9-gone", null),
            new LegacySyncSession.Failed("read-only", "refused"),
            new LegacySyncSession.Fdes(List.of(Map.of("handle", "ada"))));
    for (var response : responses) {
      assertEquals(response, LegacySyncSession.decodeResponse(LegacySyncSession.encode(response)));
    }
    assertThrows(
        IllegalArgumentException.class,
        () -> LegacySyncSession.decodeRequest("{\"op\": \"dance\"}"));
    assertThrows(
        IllegalArgumentException.class, () -> LegacySyncSession.decodeResponse("{\"hi\": 1}"));
    assertEquals(
        new LegacySyncSession.Failed("x", "refused"),
        LegacySyncSession.decodeResponse("{\"error\": \"x\"}"));
    assertTrue(
        ((LegacySyncSession.Fetched)
                LegacySyncSession.decodeResponse("{\"id\": \"m\", \"entities\": null}"))
            .entities()
            .isEmpty());
    assertTrue(
        ((LegacySyncSession.Fdes) LegacySyncSession.decodeResponse("{\"fdes\": null}"))
            .fdes()
            .isEmpty());
  }

  @Test
  void aPreFloorOrUnexpectedProtocol3AnswerFailsNamingTheEntityType() throws Exception {
    var preFloor =
        LegacySyncSession.encode(new LegacySyncSession.Fetched("old", 1, Map.of(), null)) + "\n";
    try (var legacy =
        new LegacySyncSession(new java.io.StringReader(preFloor), new java.io.StringWriter())) {
      var failure =
          assertThrows(SyncTransportException.class, () -> legacy.reconcile("spec", node.replica));
      assertTrue(failure.getMessage().contains("spec:"));
      assertTrue(failure.getMessage().contains("sail upgrade"));
    }
    var wrong = LegacySyncSession.encode(new LegacySyncSession.Committed("1-a", 1)) + "\n";
    try (var legacy =
        new LegacySyncSession(new java.io.StringReader(wrong), new java.io.StringWriter())) {
      assertThrows(SyncTransportException.class, () -> legacy.reconcile("spec", node.replica));
      assertThrows(SyncTransportException.class, legacy::fetchFdes);
    }
    var refused =
        LegacySyncSession.encode(
                new LegacySyncSession.Failed("Unknown entity type: message", "refused"))
            + "\n";
    try (var legacy =
        new LegacySyncSession(new java.io.StringReader(refused), new java.io.StringWriter())) {
      var failure =
          assertThrows(
              SyncTransportException.class, () -> legacy.reconcile("message", node.replica));
      assertTrue(failure.getMessage().contains("message"));
      assertTrue(failure.getMessage().contains("Unknown entity type"));
    }
  }
}
