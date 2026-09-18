/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.sync;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.StringReader;
import java.io.StringWriter;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SyncSessionTest {

  private static final SyncWire.Hello HELLO = SyncWire.Hello.of("0.44.0", "node-box");
  private static final String WELCOME =
      SyncWire.encode(new SyncWire.Welcome(SyncWire.PROTOCOL, "0.44.0", "main-box")) + "\n";

  private static SyncSession open(String serverLines, StringWriter out, List<String> notices) {
    return SyncSession.open(new StringReader(serverLines), out, HELLO, notices::add);
  }

  @Test
  void openSendsHelloAndReturnsThePagedSessionOnWelcome() {
    var out = new StringWriter();
    try (var session = open(WELCOME, out, new ArrayList<>())) {
      assertInstanceOf(PagedSyncSession.class, session);
    }
    var sent = out.toString().lines().map(SyncWire::decodeRequest).toList();
    assertEquals(List.of(HELLO, new SyncWire.Bye()), sent);
  }

  @Test
  void openReturnsTheLegacySessionOnTheExactProtocol3AnswerAndSaysSo() {
    var v3 =
        LegacySyncSession.encode(
                new LegacySyncSession.Failed("session: Unknown sync op: hello", "protocol"))
            + "\n";
    var notices = new ArrayList<String>();
    try (var session = open(v3, new StringWriter(), notices)) {
      assertInstanceOf(LegacySyncSession.class, session);
    }
    assertEquals(1, notices.size());
    assertTrue(notices.getFirst().contains("v3"), notices.getFirst());
    assertTrue(notices.getFirst().contains(SyncWire.UPGRADE_FLOOR), notices.getFirst());
  }

  @Test
  void aRefusalFailsNamingTheReason() {
    var refuse = SyncWire.encode(new SyncWire.Refuse("upgrade to 0.44.0: sail upgrade")) + "\n";
    var failure =
        assertThrows(
            SyncTransportException.class,
            () -> open(refuse, new StringWriter(), new ArrayList<>()));
    assertEquals("refused", failure.kind());
    assertTrue(failure.getMessage().contains("sail upgrade"), failure.getMessage());
  }

  @Test
  void aFailureAndAnyOtherFirstAnswerFailNamingIt() {
    var failed = SyncWire.encode(new SyncWire.Failed("session: boom", "store")) + "\n";
    var store =
        assertThrows(
            SyncTransportException.class,
            () -> open(failed, new StringWriter(), new ArrayList<>()));
    assertEquals("store", store.kind());
    assertTrue(store.getMessage().contains("boom"));

    var tips = SyncWire.encode(new SyncWire.Tips(Map.of("spec", 1L))) + "\n";
    var other =
        assertThrows(
            SyncTransportException.class, () -> open(tips, new StringWriter(), new ArrayList<>()));
    assertTrue(other.getMessage().contains("Tips"), other.getMessage());

    var garbage =
        assertThrows(
            SyncTransportException.class,
            () -> open("{\"rev\": 1}\n", new StringWriter(), new ArrayList<>()));
    assertTrue(garbage.getMessage().contains("Unknown sync op"), garbage.getMessage());
  }

  @Test
  void aWelcomeFromAnotherProtocolOrWithoutAMainIdIsRefused() {
    var other = SyncWire.encode(new SyncWire.Welcome(5, "0.50.0", "main")) + "\n";
    var failure =
        assertThrows(
            SyncTransportException.class, () -> open(other, new StringWriter(), new ArrayList<>()));
    assertTrue(failure.getMessage().contains("upgrade main first"), failure.getMessage());
    var anonymous = SyncWire.encode(new SyncWire.Welcome(SyncWire.PROTOCOL, "0.44.0", "")) + "\n";
    assertThrows(
        SyncTransportException.class, () -> open(anonymous, new StringWriter(), new ArrayList<>()));
  }

  @Test
  void aNodeAheadOfMainIsToldTheUpgradeOrderOncePerSession() {
    var older = SyncWire.encode(new SyncWire.Welcome(SyncWire.PROTOCOL, "0.44.0", "main")) + "\n";
    var notices = new ArrayList<String>();
    try (var session =
        SyncSession.open(
            new StringReader(older),
            new StringWriter(),
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
            new StringReader(dev),
            new StringWriter(),
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
            SyncTransportException.class, () -> open("", new StringWriter(), new ArrayList<>()));
    assertEquals("unreachable", failure.kind());
  }

  @Test
  void aBrokenChannelOnHelloRaisesUnchecked() {
    assertThrows(
        UncheckedIOException.class,
        () -> SyncSession.open(new StringReader(""), brokenWriter(), HELLO, n -> {}));
  }

  @Test
  void fetchFdesReturnsMainsRosterAndRejectsAnythingElse() {
    var roster = List.<Map<String, Object>>of(Map.of("handle", "ada", "role", "admin"));
    try (var session =
        open(
            WELCOME + SyncWire.encode(new SyncWire.Fdes(roster)) + "\n",
            new StringWriter(),
            new ArrayList<>())) {
      var pulled = session.fetchFdes();
      assertEquals("ada", pulled.getFirst().get("handle"));
    }
    try (var session =
        open(
            WELCOME + SyncWire.encode(new SyncWire.Tips(Map.of())) + "\n",
            new StringWriter(),
            new ArrayList<>())) {
      assertThrows(SyncTransportException.class, session::fetchFdes);
    }
    try (var session =
        open(
            WELCOME + SyncWire.encode(new SyncWire.Failed("fde: nope", "store")) + "\n",
            new StringWriter(),
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
        var session = open(stuck, new StringWriter(), new ArrayList<>())) {
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
                new StringWriter(),
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
                  new PagedSyncSession(new StringReader(""), brokenWriter(), "main", 64)) {
                throw original;
              }
            });
    assertSame(original, thrown);
    assertEquals(1, thrown.getSuppressed().length);
    assertInstanceOf(UncheckedIOException.class, thrown.getSuppressed()[0]);
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
