/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.sync;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class SyncSessionTest {

  private static final SyncWire.Hello HELLO = SyncWire.Hello.of("0.44.0", "node-box");
  private static final String WELCOME =
      SyncWire.encode(new SyncWire.Welcome(SyncWire.PROTOCOL, "0.44.0", "main-box")) + "\n";

  private static SyncSession open(
      String serverLines, ai.singlr.sail.sync.ByteStreams.Output out, List<String> notices) {
    return SyncSession.open(
        new ai.singlr.sail.sync.ByteStreams.Input(serverLines), out, HELLO, notices::add);
  }

  @Test
  void openSendsHelloAndReturnsThePagedSessionOnWelcome() {
    var out = new ai.singlr.sail.sync.ByteStreams.Output();
    try (var session = open(WELCOME, out, new ArrayList<>())) {
      assertInstanceOf(PagedSyncSession.class, session);
    }
    var sent = out.toString().lines().map(SyncWire::decodeRequest).toList();
    assertEquals(List.of(HELLO, new SyncWire.Bye()), sent);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "{\"error\": \"session: Unknown sync op: hello\", \"error_kind\": \"protocol\"}",
        "{\"rev\": \"3-abc\", \"maxSeq\": 9}",
        "  {}"
      })
  void anAnswerThatNamesNoOpIsAnOlderMainAndFailsNamingTheRemedy(String olderProtocol) {
    var v3 = olderProtocol + "\n";
    var notices = new ArrayList<String>();
    var failure =
        assertThrows(
            SyncTransportException.class,
            () -> open(v3, new ai.singlr.sail.sync.ByteStreams.Output(), notices));
    assertEquals("refused", failure.kind());
    assertTrue(
        failure
            .getMessage()
            .contains("main is on a sync protocol this node cannot speak: upgrade main"),
        failure.getMessage());
    assertTrue(notices.isEmpty());
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "Warning: Permanently added 'main' to the list of known hosts.",
        "bash: sail: command not found",
        "{\"op\": \"welcome\", \"protocol\": ",
        "[1, 2]"
      })
  void whatIsNotASyncMessageIsReportedAsThatAndNeverBlamedOnMainsVersion(String noise) {
    var failure =
        assertThrows(
            SyncTransportException.class,
            () ->
                open(
                    noise + "\n", new ai.singlr.sail.sync.ByteStreams.Output(), new ArrayList<>()));

    assertEquals("protocol", failure.kind());
    assertTrue(
        failure.getMessage().contains("main did not answer with a sync message"),
        failure.getMessage());
    assertFalse(failure.getMessage().contains("upgrade main"), failure.getMessage());
  }

  @Test
  void aRefusalFailsNamingTheReason() {
    var refuse = SyncWire.encode(new SyncWire.Refuse("upgrade to 0.44.0: sail upgrade")) + "\n";
    var failure =
        assertThrows(
            SyncTransportException.class,
            () -> open(refuse, new ai.singlr.sail.sync.ByteStreams.Output(), new ArrayList<>()));
    assertEquals("refused", failure.kind());
    assertTrue(failure.getMessage().contains("sail upgrade"), failure.getMessage());
  }

  @Test
  void aFailureAndAnyOtherFirstAnswerFailNamingIt() {
    var failed = SyncWire.encode(new SyncWire.Failed("session: boom", "store")) + "\n";
    var store =
        assertThrows(
            SyncTransportException.class,
            () -> open(failed, new ai.singlr.sail.sync.ByteStreams.Output(), new ArrayList<>()));
    assertEquals("store", store.kind());
    assertTrue(store.getMessage().contains("boom"));

    var tips = SyncWire.encode(new SyncWire.Tips(Map.of("spec", 1L))) + "\n";
    var other =
        assertThrows(
            SyncTransportException.class,
            () -> open(tips, new ai.singlr.sail.sync.ByteStreams.Output(), new ArrayList<>()));
    assertTrue(other.getMessage().contains("Tips"), other.getMessage());

    var unknownOp =
        assertThrows(
            SyncTransportException.class,
            () ->
                open(
                    "{\"op\": \"bogus\"}\n",
                    new ai.singlr.sail.sync.ByteStreams.Output(),
                    new ArrayList<>()));
    assertEquals("protocol", unknownOp.kind());
    assertTrue(unknownOp.getMessage().contains("Unknown sync op"), unknownOp.getMessage());
  }

  @Test
  void aWelcomeFromAnotherProtocolOrWithoutAMainIdIsRefused() {
    var other = SyncWire.encode(new SyncWire.Welcome(5, "0.50.0", "main")) + "\n";
    var failure =
        assertThrows(
            SyncTransportException.class,
            () -> open(other, new ai.singlr.sail.sync.ByteStreams.Output(), new ArrayList<>()));
    assertTrue(failure.getMessage().contains("upgrade main first"), failure.getMessage());
    var anonymous = SyncWire.encode(new SyncWire.Welcome(SyncWire.PROTOCOL, "0.44.0", "")) + "\n";
    assertThrows(
        SyncTransportException.class,
        () -> open(anonymous, new ai.singlr.sail.sync.ByteStreams.Output(), new ArrayList<>()));
  }

  @Test
  void aNodeAheadOfMainIsToldTheUpgradeOrderOncePerSession() {
    var older = SyncWire.encode(new SyncWire.Welcome(SyncWire.PROTOCOL, "0.44.0", "main")) + "\n";
    var notices = new ArrayList<String>();
    try (var session =
        SyncSession.open(
            new ai.singlr.sail.sync.ByteStreams.Input(older),
            new ai.singlr.sail.sync.ByteStreams.Output(),
            SyncWire.Hello.of("0.44.2", "node"),
            notices::add)) {
      assertInstanceOf(PagedSyncSession.class, session);
    }
    assertEquals(1, notices.size());
    assertTrue(notices.getFirst().contains("0.44.2"));
    assertTrue(notices.getFirst().contains("0.44.0"));
    assertTrue(notices.getFirst().contains("upgrade main first"));
  }

  @Test
  void aDevBuildOnEitherSideNeverProducesAVersionNotice() {
    var dev = SyncWire.encode(new SyncWire.Welcome(SyncWire.PROTOCOL, "dev", "main")) + "\n";
    var notices = new ArrayList<String>();
    try (var session =
        SyncSession.open(
            new ai.singlr.sail.sync.ByteStreams.Input(dev),
            new ai.singlr.sail.sync.ByteStreams.Output(),
            SyncWire.Hello.of("0.44.2", "node"),
            notices::add)) {
      assertInstanceOf(PagedSyncSession.class, session);
    }
    assertTrue(notices.isEmpty());
  }

  @Test
  void aChannelClosedBeforeTheWelcomeIsUnreachable() {
    var failure =
        assertThrows(
            SyncTransportException.class,
            () -> open("", new ai.singlr.sail.sync.ByteStreams.Output(), new ArrayList<>()));
    assertEquals("unreachable", failure.kind());
  }

  @Test
  void aBrokenChannelOnHelloRaisesUnchecked() {
    assertThrows(
        UncheckedIOException.class,
        () ->
            SyncSession.open(
                new ai.singlr.sail.sync.ByteStreams.Input(""), brokenWriter(), HELLO, n -> {}));
  }

  @Test
  void fetchFdesReturnsMainsRosterAndRejectsAnythingElse() {
    var roster = List.<Map<String, Object>>of(Map.of("handle", "ada", "role", "admin"));
    try (var session =
        open(
            WELCOME + SyncWire.encode(new SyncWire.Fdes(roster)) + "\n",
            new ai.singlr.sail.sync.ByteStreams.Output(),
            new ArrayList<>())) {
      var pulled = session.fetchFdes();
      assertEquals("ada", pulled.getFirst().get("handle"));
    }
    try (var session =
        open(
            WELCOME + SyncWire.encode(new SyncWire.Tips(Map.of())) + "\n",
            new ai.singlr.sail.sync.ByteStreams.Output(),
            new ArrayList<>())) {
      assertThrows(SyncTransportException.class, session::fetchFdes);
    }
    try (var session =
        open(
            WELCOME + SyncWire.encode(new SyncWire.Failed("fde: nope", "store")) + "\n",
            new ai.singlr.sail.sync.ByteStreams.Output(),
            new ArrayList<>())) {
      assertEquals("store", assertThrows(SyncTransportException.class, session::fetchFdes).kind());
    }
  }

  @Test
  void aPageThatAdvancesNothingYetIsNotDoneIsAProtocolFailureNotALoop() {
    var stuck =
        WELCOME
            + SyncWire.encode(new SyncWire.Tips(Map.of("spec", 5L)))
            + "\n"
            + SyncWire.encode(new SyncWire.Page(List.of(), 0, false, 5))
            + "\n";
    try (var node = new SyncBox("node");
        var session =
            open(stuck, new ai.singlr.sail.sync.ByteStreams.Output(), new ArrayList<>())) {
      var failure =
          assertThrows(SyncTransportException.class, () -> session.reconcile("spec", node.replica));
      assertEquals("protocol", failure.kind());
      assertTrue(failure.getMessage().contains("seq 0"), failure.getMessage());
    }
  }

  @Test
  void aTypeMainDoesNotServeIsRefusedNamingIt() {
    try (var main = new SyncBox("main");
        var node = new SyncBox("node");
        var session =
            open(
                WELCOME + SyncWire.encode(new SyncWire.Tips(Map.of("spec", 0L))) + "\n",
                new ai.singlr.sail.sync.ByteStreams.Output(),
                new ArrayList<>())) {
      var failure =
          assertThrows(SyncTransportException.class, () -> session.reconcile("file", node.replica));
      assertTrue(failure.getMessage().startsWith("file:"), failure.getMessage());
    }
  }

  @Test
  void closeFailureIsSuppressedOnTheOriginalRoundFailure() {
    var original = new IllegalStateException("message: refused");
    var thrown =
        assertThrows(
            IllegalStateException.class,
            () -> {
              try (var session =
                  new PagedSyncSession(
                      new ai.singlr.sail.sync.ByteStreams.Input(""), brokenWriter(), "main", 64)) {
                throw original;
              }
            });
    assertSame(original, thrown);
    assertEquals(1, thrown.getSuppressed().length);
    assertInstanceOf(UncheckedIOException.class, thrown.getSuppressed()[0]);
  }

  private static OutputStream brokenWriter() {
    return new OutputStream() {
      @Override
      public void write(int value) throws IOException {
        throw new IOException("channel down");
      }

      @Override
      public void write(byte[] buffer, int offset, int length) throws IOException {
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
