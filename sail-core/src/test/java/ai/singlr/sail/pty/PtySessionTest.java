/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.pty;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

@EnabledOnOs(OS.LINUX)
class PtySessionTest {

  @TempDir Path dir;

  private static final class Collector implements PtySession.Client {
    final ConcurrentLinkedQueue<PtyMessage> messages = new ConcurrentLinkedQueue<>();
    final CountDownLatch ended = new CountDownLatch(1);
    volatile long sleepMillis;

    @Override
    public void deliver(PtyMessage message) {
      if (sleepMillis > 0) {
        try {
          Thread.sleep(sleepMillis);
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
        }
      }
      messages.add(message);
      if (message instanceof PtyMessage.SessionEnded) {
        ended.countDown();
      }
    }

    String outputText() {
      var out = new StringBuilder();
      for (var message : messages) {
        if (message instanceof PtyMessage.Output(var seq, var bytes)) {
          out.append(new String(bytes, StandardCharsets.UTF_8));
        }
      }
      return out.toString();
    }

    boolean saw(Class<? extends PtyMessage> type) {
      return messages.stream().anyMatch(type::isInstance);
    }

    void awaitOutput(String marker) throws InterruptedException {
      var deadline = System.nanoTime() + 10_000_000_000L;
      while (!outputText().contains(marker)) {
        if (System.nanoTime() > deadline) {
          throw new AssertionError("never saw '" + marker + "'; got: " + outputText());
        }
        Thread.sleep(5);
      }
    }
  }

  private PtySession session(String script) throws IOException {
    return PtySession.start(
        "t",
        "uday",
        "acme",
        PtyEvents.NONE,
        List.of("sh", "-c", script),
        Map.of("TERM", "dumb"),
        Path.of("/tmp"),
        dir.resolve("t.ring"),
        64 * 1024,
        80,
        24);
  }

  @Test
  void ownershipEventsAndWriterPrincipalAreObservable() throws Exception {
    var events = new java.util.concurrent.ConcurrentLinkedQueue<String>();
    var recorder =
        new PtyEvents() {
          @Override
          public void sessionStarted(String session, String project, String fde) {
            events.add("started:" + session + ":" + project + ":" + fde);
          }

          @Override
          public void sessionAttached(String session, String project, String fde) {
            events.add("attached:" + session + ":" + fde);
          }

          @Override
          public void sessionEnded(String session, String project, String reason) {
            events.add("ended:" + session + ":" + reason);
          }
        };
    var session =
        PtySession.start(
            "owned",
            "mady",
            "acme",
            recorder,
            List.of("sh", "-c", "read a"),
            Map.of("TERM", "dumb"),
            Path.of("/tmp"),
            dir.resolve("owned.ring"),
            64 * 1024,
            80,
            24);
    try {
      assertEquals("mady", session.ownerFde());
      assertEquals("acme", session.project());
      assertTrue(events.contains("started:owned:acme:mady"), events.toString());

      var writer = new Collector();
      session.attach(writer, true, "mady");
      assertEquals("mady", session.writerFde(), "the token records its principal");
      assertTrue(events.contains("attached:owned:mady"), events.toString());
    } finally {
      session.close();
    }
    var deadline = System.nanoTime() + 5_000_000_000L;
    while (events.stream().noneMatch(e -> e.startsWith("ended:owned:"))
        && System.nanoTime() < deadline) {
      Thread.onSpinWait();
    }
    assertTrue(
        events.stream().anyMatch(e -> e.startsWith("ended:owned:")),
        "the ending is a recorded fact: " + events);
  }

  @Test
  void theWriterConversesAndOutputCarriesItsInputSequence() throws Exception {
    try (var session = session("read a; echo got:$a; read b")) {
      var writer = new Collector();
      var id = session.attach(writer, true, "uday");

      session.input(id, 7, "hello\n".getBytes(StandardCharsets.UTF_8));
      writer.awaitOutput("got:hello");

      var seqs =
          writer.messages.stream()
              .filter(m -> m instanceof PtyMessage.Output(var s, var b) && s == 7)
              .count();
      assertTrue(seqs > 0, "output frames after input 7 carry lastInputSeq 7");
    }
  }

  @Test
  void aLateObserverGetsTheReplayBracketThenLiveOutput() throws Exception {
    try (var session = session("echo early-line; read b; echo late-line; read c")) {
      var writer = new Collector();
      var writerId = session.attach(writer, true, "uday");
      writer.awaitOutput("early-line");

      var observer = new Collector();
      session.attach(observer, false, "uday");
      observer.awaitOutput("early-line");
      assertTrue(observer.saw(PtyMessage.ReplayBegin.class), "replay is bracketed");
      assertTrue(observer.saw(PtyMessage.ReplayEnd.class));

      session.input(writerId, 1, "go\n".getBytes(StandardCharsets.UTF_8));
      observer.awaitOutput("late-line");
    }
  }

  @Test
  void onlyTheTokenHolderWritesAndTakeoverIsExplicitAndAnnounced() throws Exception {
    try (var session = session("read a; echo done:$a")) {
      var first = new Collector();
      var second = new Collector();
      var firstId = session.attach(first, true, "uday");
      var secondId = session.attach(second, false, "uday");

      assertFalse(
          session.input(secondId, 1, "nope\n".getBytes(StandardCharsets.UTF_8)),
          "a non-writer's input is refused, not fatal");

      session.takeWrite(secondId, "mady");
      var deadline = System.nanoTime() + 5_000_000_000L;
      while (!first.saw(PtyMessage.WriterChanged.class) && System.nanoTime() < deadline) {
        Thread.sleep(5);
      }
      assertTrue(first.saw(PtyMessage.WriterChanged.class), "the old writer hears the takeover");

      assertFalse(
          session.input(firstId, 2, "stale\n".getBytes(StandardCharsets.UTF_8)),
          "the demoted writer can no longer write");
      session.input(secondId, 3, "fresh\n".getBytes(StandardCharsets.UTF_8));
      second.awaitOutput("done:fresh");
    }
  }

  @Test
  void everySubscriberHearsTheEndingAndLateAttachIsRefused() throws Exception {
    var session = session("echo bye; exit 3");
    try {
      var client = new Collector();
      session.attach(client, true, "uday");
      assertTrue(client.ended.await(10, TimeUnit.SECONDS), "the ending reaches subscribers");
      assertEquals("exited(3)", session.endedReason());

      var late = new Collector();
      assertThrows(IOException.class, () -> session.attach(late, false, "uday"));
    } finally {
      session.close();
    }
  }

  @Test
  void aFloodNeverBlocksTheChildEvenWithAStalledObserver() throws Exception {
    try (var session =
        PtySession.start(
            "slow",
            "uday",
            "acme",
            PtyEvents.NONE,
            List.of(
                "sh",
                "-c",
                "read a; i=0; while [ $i -lt 400 ]; do echo line-$i; i=$((i+1)); done;"
                    + " echo flood-done; read b"),
            Map.of("TERM", "dumb"),
            Path.of("/tmp"),
            dir.resolve("slow.ring"),
            256 * 1024,
            80,
            24,
            2,
            65536)) {
      var writer = new Collector();
      var writerId = session.attach(writer, true, "uday");
      var stalled = new Collector();
      stalled.sleepMillis = 1000;
      session.attach(stalled, false, "uday");

      session.input(writerId, 1, "go\n".getBytes(StandardCharsets.UTF_8));
      var deadline = System.nanoTime() + 10_000_000_000L;
      while (session.journaledBytes() < 3500) {
        if (System.nanoTime() > deadline) {
          throw new AssertionError(
              "the flood stalled at " + session.journaledBytes() + " journaled bytes");
        }
        Thread.onSpinWait();
      }

      assertTrue(session.live(), "the child never blocked on the stalled observer");
    }
  }

  @Test
  void aFailingEndEventStillReleasesTheSessionInsteadOfWedgingClose() throws Exception {
    var brittle =
        new PtyEvents() {
          @Override
          public void sessionStarted(String session, String project, String fde) {}

          @Override
          public void sessionAttached(String session, String project, String fde) {}

          @Override
          public void sessionEnded(String session, String project, String reason) {
            throw new RuntimeException("the event sink is down");
          }
        };
    var session =
        PtySession.start(
            "brittle",
            "uday",
            "acme",
            brittle,
            List.of("sh", "-c", "exit 0"),
            Map.of("TERM", "dumb"),
            Path.of("/tmp"),
            dir.resolve("brittle.ring"),
            64 * 1024,
            80,
            24);
    assertTimeoutPreemptively(
        java.time.Duration.ofSeconds(10),
        session::close,
        "close must not hang when the end-of-session event throws");
  }
}
