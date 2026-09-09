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
    volatile CountDownLatch gate;

    @Override
    public void deliver(PtyMessage message) {
      var g = gate;
      if (g != null) {
        try {
          g.await();
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
        }
      }
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

    /** The screen a client would show: a replay bracket replaces everything before it. */
    String outputText() {
      var out = new StringBuilder();
      for (var message : messages) {
        if (message instanceof PtyMessage.ReplayBegin) {
          out.setLength(0);
        } else if (message instanceof PtyMessage.Output(var seq, var bytes)) {
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

  private static PtySession.Origin origin(String name, String owner, String project) {
    return new PtySession.Origin(name, "inst-" + name, owner, project, "", List.of("sh"));
  }

  private PtySession session(String script) throws IOException {
    return PtySession.start(
        origin("t", "uday", "acme"),
        PtyEvents.NONE,
        List.of("sh", "-c", script),
        Map.of("TERM", "dumb"),
        Path.of("/tmp"),
        dir.resolve("t.ring"),
        64 * 1024,
        80,
        24);
  }

  /** A journal whose disk fills the moment {@code trigger} is appended (empty: at once). */
  private static final class FailingJournal implements Journal {
    private final String trigger;

    FailingJournal() {
      this("");
    }

    FailingJournal(String trigger) {
      this.trigger = trigger;
    }

    @Override
    public void append(byte[] buf, int len) throws IOException {
      if (new String(buf, 0, len, StandardCharsets.UTF_8).contains(trigger)) {
        throw new IOException("disk full");
      }
    }

    @Override
    public void markSafe() {}

    @Override
    public Tail tail(int maxBytes) {
      return new Tail(0, true, new byte[0]);
    }

    @Override
    public long capacity() {
      return 64 * 1024;
    }

    @Override
    public long totalWritten() {
      return 0;
    }

    @Override
    public void close() {}
  }

  @Test
  void anIoErrorEndingReapsAChildThatIgnoresTermination() throws Exception {
    var session =
        PtySession.start(
            origin("stubborn", "uday", "acme"),
            PtyEvents.NONE,
            List.of(
                "sh",
                "-c",
                "trap '' HUP TERM; echo \"pid=$$;\"; read x; echo boom; while :; do sleep 1; done"),
            Map.of("TERM", "dumb"),
            Path.of("/tmp"),
            new FailingJournal("boom"),
            80,
            24,
            4096);
    try {
      var client = new Collector();
      var id = session.attach(client, true, "uday");
      client.awaitOutput(";");
      var pid = Long.parseLong(client.outputText().replaceAll("(?s).*pid=(\\d+);.*", "$1"));
      session.input(id, 1, "\n".getBytes(StandardCharsets.UTF_8));
      assertTrue(client.ended.await(10, TimeUnit.SECONDS), "the failure ends the session");

      assertFalse(
          ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false),
          "a child that shrugs off SIGHUP and SIGTERM is dead before the ending is published");
    } finally {
      session.close();
    }
  }

  @Test
  void anInjectedJournalFailureEndsTheSessionInsteadOfWedging() throws Exception {
    var session =
        PtySession.start(
            origin("wedge", "uday", "acme"),
            PtyEvents.NONE,
            List.of("sh", "-c", "read x; echo boom"),
            Map.of("TERM", "dumb"),
            Path.of("/tmp"),
            new FailingJournal(),
            80,
            24,
            4096);
    try {
      var client = new Collector();
      var id = session.attach(client, true, "uday");
      session.input(id, 1, "\n".getBytes(StandardCharsets.UTF_8));

      assertTrue(
          client.ended.await(10, TimeUnit.SECONDS),
          "a journal that throws must end the session loudly, never wedge it");
      assertFalse(session.live(), "the failed session is no longer live");
      assertTrue(
          client.messages.stream()
              .anyMatch(
                  m ->
                      m instanceof PtyMessage.SessionEnded(var reason)
                          && reason.contains("io-error")),
          "every subscriber hears an io-error ending: " + client.messages);
    } finally {
      assertTimeoutPreemptively(
          java.time.Duration.ofSeconds(10),
          session::close,
          "close must not hang after a journal failure ended the session");
    }
  }

  @Test
  void endDeliversTheNoticeAndTheReasonEvenToASubscriberStillDraining() throws Exception {
    var endings = new java.util.concurrent.ConcurrentLinkedQueue<String>();
    var recorder =
        new PtyEvents() {
          @Override
          public void sessionStarted(PtySession.Origin origin) {}

          @Override
          public void sessionAttached(PtySession.Origin origin, String fde) {}

          @Override
          public void sessionEnded(PtySession.Origin origin, String reason) {
            endings.add(reason);
          }
        };
    var session =
        PtySession.start(
            origin("yielded", "uday", "acme"),
            recorder,
            List.of("sh", "-c", "echo up; read a"),
            Map.of("TERM", "dumb"),
            Path.of("/tmp"),
            dir.resolve("yielded.ring"),
            64 * 1024,
            80,
            24);
    var client = new Collector();
    var gate = new CountDownLatch(1);
    client.gate = gate;
    session.attach(client, true, "uday");
    Thread.sleep(200);

    session.end("yielded to dispatch 2");
    gate.countDown();

    assertTrue(client.ended.await(10, TimeUnit.SECONDS), "the ending must arrive");
    assertTrue(
        client.outputText().contains("[sail: session ended \u2014 yielded to dispatch 2]"),
        "the notice line reaches a subscriber that was still draining: " + client.messages);
    assertTrue(
        client.messages.stream()
            .anyMatch(
                m ->
                    m instanceof PtyMessage.SessionEnded(var reason)
                        && reason.equals("yielded to dispatch 2")),
        "the ending carries the displacing reason: " + client.messages);
    assertFalse(session.live());
    assertEquals(List.of("yielded to dispatch 2"), List.copyOf(endings));
    assertEquals("yielded to dispatch 2", session.endedReason());
  }

  @Test
  void ownershipEventsAndWriterPrincipalAreObservable() throws Exception {
    var events = new java.util.concurrent.ConcurrentLinkedQueue<String>();
    var recorder =
        new PtyEvents() {
          @Override
          public void sessionStarted(PtySession.Origin origin) {
            events.add(
                "started:"
                    + origin.name()
                    + ":"
                    + origin.project()
                    + ":"
                    + origin.ownerFde()
                    + ":"
                    + origin.room()
                    + ":"
                    + String.join(" ", origin.command()));
          }

          @Override
          public void sessionAttached(PtySession.Origin origin, String fde) {
            events.add("attached:" + origin.name() + ":" + fde + ":" + origin.room());
          }

          @Override
          public void sessionEnded(PtySession.Origin origin, String reason) {
            events.add("ended:" + origin.name() + ":" + reason + ":" + origin.room());
          }
        };
    var session =
        PtySession.start(
            new PtySession.Origin(
                "owned", "inst-owned", "mady", "acme", "lounge", List.of("claude")),
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
      assertEquals("lounge", session.origin().room());
      assertTrue(session.origin().roomBound());
      assertTrue(
          events.contains("started:owned:acme:mady:lounge:claude"),
          "the start fact names the room and the command: " + events);

      var writer = new Collector();
      session.attach(writer, true, "mady");
      assertEquals("mady", session.writerFde(), "the token records its principal");
      assertTrue(events.contains("attached:owned:mady:lounge"), events.toString());
    } finally {
      session.close();
    }
    var deadline = System.nanoTime() + 5_000_000_000L;
    while (events.stream().noneMatch(e -> e.startsWith("ended:owned:"))
        && System.nanoTime() < deadline) {
      Thread.onSpinWait();
    }
    assertTrue(
        events.stream().anyMatch(e -> e.startsWith("ended:owned:") && e.endsWith(":lounge")),
        "the ending is a recorded fact that still names the room: " + events);
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
  void aLateAttacherReceivesTheWholeJournalInChunkedFrames() throws Exception {
    var payload = 1_572_864; // 1.5 MiB: past the wire's 1 MiB frame cap
    try (var session =
        PtySession.start(
            origin("big", "uday", "acme"),
            PtyEvents.NONE,
            List.of(
                "sh",
                "-c",
                "head -c " + payload + " /dev/zero | tr '\\0' x; echo; echo BIG-DONE; read a"),
            Map.of("TERM", "dumb"),
            Path.of("/tmp"),
            dir.resolve("big.ring"),
            4L * 1024 * 1024,
            80,
            24)) {
      var writer = new Collector();
      session.attach(writer, true, "uday");
      writer.awaitOutput("BIG-DONE");

      var late = new Collector();
      session.attach(late, false, "uday");
      late.awaitOutput("BIG-DONE");

      var text = late.outputText();
      assertTrue(text.chars().filter(c -> c == 'x').count() >= payload, "every byte replayed");
      var frames =
          late.messages.stream()
              .filter(m -> m instanceof PtyMessage.Output)
              .map(m -> (PtyMessage.Output) m)
              .toList();
      assertTrue(frames.size() > 1, "the tail crossed as several frames, not one oversized frame");
      assertTrue(
          frames.stream().allMatch(f -> f.bytes().length <= PtySession.REPLAY_CHUNK),
          "every replay frame fits the chunk");
      var kinds = late.messages.stream().map(m -> m.getClass().getSimpleName()).toList();
      assertEquals("ReplayBegin", kinds.get(1), "still bracketed, behind the geometry: " + kinds);
      assertTrue(
          kinds.indexOf("ReplayEnd") > kinds.lastIndexOf("Output") - 1,
          "ends the bracket after the tail");
    }
  }

  @Test
  void onlyTheTokenHolderWritesAndTakeoverIsExplicitAndAnnounced() throws Exception {
    try (var session = session("read a; echo done:$a")) {
      var first = new Collector();
      var second = new Collector();
      var firstId = session.attach(first, true, "uday");
      var secondId = session.attach(second, false, "uday");

      assertEquals(
          PtySession.WriteOutcome.NOT_WRITER,
          session.input(secondId, 1, "nope\n".getBytes(StandardCharsets.UTF_8)),
          "a non-writer's input is refused, not fatal");

      session.takeWrite(secondId, "mady");
      var deadline = System.nanoTime() + 5_000_000_000L;
      while (!first.saw(PtyMessage.WriterChanged.class) && System.nanoTime() < deadline) {
        Thread.sleep(5);
      }
      assertTrue(first.saw(PtyMessage.WriterChanged.class), "the old writer hears the takeover");

      assertEquals(
          PtySession.WriteOutcome.NOT_WRITER,
          session.input(firstId, 2, "stale\n".getBytes(StandardCharsets.UTF_8)),
          "the demoted writer can no longer write");
      session.input(secondId, 3, "fresh\n".getBytes(StandardCharsets.UTF_8));
      second.awaitOutput("done:fresh");
    }
  }

  @Test
  void attachAnswersWithTheGeometryThenTheReplayThenTheWriter() throws Exception {
    try (var session = session("echo wide-line; read a")) {
      var writer = new Collector();
      var writerId = session.attach(writer, true, "uday");
      writer.awaitOutput("wide-line");
      assertTrue(session.resize(writerId, 126, 40), "the writer sizes the pty");

      var observer = new Collector();
      session.attach(observer, false, "uday");
      observer.awaitOutput("wide-line");
      awaitFrame(observer, PtyMessage.WriterChanged.class);

      var frames = List.copyOf(observer.messages);
      assertEquals(
          new PtyMessage.Resized(126, 40),
          frames.getFirst(),
          "the pty's live geometry comes before anything else: " + frames);
      var kinds = frames.stream().map(m -> m.getClass().getSimpleName()).toList();
      assertEquals("ReplayBegin", kinds.get(1), "then the replay: " + kinds);
      assertEquals(
          new PtyMessage.WriterChanged("uday"),
          frames.get(kinds.indexOf("ReplayEnd") + 1),
          "and the token holder right after the replay: " + frames);
    }
  }

  @Test
  void aResizeRacingTheAttachCannotStripTheGeometryAheadOfTheReplay() throws Exception {
    var onAttached = new java.util.concurrent.atomic.AtomicReference<Runnable>(() -> {});
    var events =
        new PtyEvents() {
          @Override
          public void sessionStarted(PtySession.Origin origin) {}

          @Override
          public void sessionAttached(PtySession.Origin origin, String fde) {
            onAttached.get().run();
          }

          @Override
          public void sessionEnded(PtySession.Origin origin, String reason) {}
        };
    try (var session =
        PtySession.start(
            origin("race", "uday", "acme"),
            events,
            List.of("sh", "-c", "echo wide-line; read a"),
            Map.of("TERM", "dumb"),
            Path.of("/tmp"),
            dir.resolve("race.ring"),
            64 * 1024,
            80,
            24)) {
      var writer = new Collector();
      var writerId = session.attach(writer, true, "uday");
      writer.awaitOutput("wide-line");
      assertTrue(session.resize(writerId, 126, 40));

      // The attach event fires after the observer's queue is seeded and before its sender runs:
      // the writer resizing in that window is the race.
      onAttached.set(
          () -> {
            try {
              session.resize(writerId, 100, 30);
            } catch (IOException e) {
              throw new RuntimeException(e);
            }
          });
      var observer = new Collector();
      session.attach(observer, false, "mady");
      awaitFrame(observer, PtyMessage.WriterChanged.class);

      var frames = List.copyOf(observer.messages);
      assertEquals(
          new PtyMessage.Resized(126, 40),
          frames.getFirst(),
          "the replay was produced at 126 columns, so that geometry precedes it: " + frames);
      var kinds = frames.stream().map(m -> m.getClass().getSimpleName()).toList();
      assertEquals("ReplayBegin", kinds.get(1), "then the replay: " + kinds);
      assertTrue(
          frames.indexOf(new PtyMessage.Resized(100, 30)) > kinds.indexOf("ReplayEnd"),
          "and the racing resize lands after it, where the stream it governs begins: " + frames);
    }
  }

  @Test
  void theSameFdeReclaimsTheTokenFromItsGhostWhileAnotherFdeWaits() throws Exception {
    try (var session = session("read a; echo got:$a; read b")) {
      var ghost = new Collector();
      var ghostId = session.attach(ghost, true, "uday");
      var returning = new Collector();
      var returningId = session.attach(returning, true, "uday");
      awaitFrame(ghost, PtyMessage.WriterChanged.class);

      assertEquals(
          returningId, session.writerId(), "the same FDE's new connection holds the token");
      assertEquals(
          PtySession.WriteOutcome.NOT_WRITER,
          session.input(ghostId, 1, "stale\n".getBytes(StandardCharsets.UTF_8)),
          "the ghost's next keystroke is refused");
      session.input(returningId, 2, "fresh\n".getBytes(StandardCharsets.UTF_8));
      returning.awaitOutput("got:fresh");

      var other = new Collector();
      var otherId = session.attach(other, true, "mady");
      awaitFrame(other, PtyMessage.WriterChanged.class);
      assertEquals(
          returningId, session.writerId(), "a different FDE never takes the token by attaching");
      assertEquals(
          PtySession.WriteOutcome.NOT_WRITER,
          session.input(otherId, 3, "nope\n".getBytes(StandardCharsets.UTF_8)));
      var kinds = other.messages.stream().map(m -> m.getClass().getSimpleName()).toList();
      assertEquals(
          new PtyMessage.WriterChanged("uday"),
          List.copyOf(other.messages).get(kinds.indexOf("ReplayEnd") + 1),
          "and is told who holds it right after the replay: " + other.messages);
    }
  }

  private static void awaitFrame(Collector client, Class<? extends PtyMessage> type)
      throws InterruptedException {
    var deadline = System.nanoTime() + 10_000_000_000L;
    while (!client.saw(type)) {
      if (System.nanoTime() > deadline) {
        throw new AssertionError("never saw " + type.getSimpleName() + "; got: " + client.messages);
      }
      Thread.sleep(5);
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
            origin("slow", "uday", "acme"),
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
            2)) {
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
  void aChildThatStoppedReadingBoundsQueuedInputByBytesNotJustFrames() throws Exception {
    try (var session = session("stty raw -echo; echo raw-ready; sleep 60")) {
      var writer = new Collector();
      var writerId = session.attach(writer, true, "uday");
      writer.awaitOutput("raw-ready");

      var chunk = new byte[256 * 1024];
      var accepted = 0;
      var refused = false;
      for (var i = 0; i < 16 && !refused; i++) {
        switch (session.input(writerId, i, chunk)) {
          case ACCEPTED -> accepted++;
          case BACKLOG -> refused = true;
          case NOT_WRITER -> throw new AssertionError("the writer holds the token");
        }
      }

      assertTrue(refused, "a child that stopped reading must back input up to BACKLOG");
      assertTrue(
          (long) accepted * chunk.length <= PtySession.MAX_INPUT_BACKLOG_BYTES,
          "queued plus in-flight input exceeded the byte budget: " + accepted + " chunks");
      assertTrue(session.live(), "backlog is refusable, never fatal");
    }
  }

  @Test
  void aFailingEndEventStillReleasesTheSessionInsteadOfWedgingClose() throws Exception {
    var brittle =
        new PtyEvents() {
          @Override
          public void sessionStarted(PtySession.Origin origin) {}

          @Override
          public void sessionAttached(PtySession.Origin origin, String fde) {}

          @Override
          public void sessionEnded(PtySession.Origin origin, String reason) {
            throw new RuntimeException("the event sink is down");
          }
        };
    var session =
        PtySession.start(
            origin("brittle", "uday", "acme"),
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

  @Test
  void anEventsImplThatAlwaysThrowsNeverKillsOrStallsTheSession() throws Exception {
    var hostile =
        new PtyEvents() {
          @Override
          public void sessionStarted(PtySession.Origin origin) {
            throw new RuntimeException("the event sink is down");
          }

          @Override
          public void sessionAttached(PtySession.Origin origin, String fde) {
            throw new RuntimeException("the event sink is down");
          }

          @Override
          public void sessionEnded(PtySession.Origin origin, String reason) {
            throw new RuntimeException("the event sink is down");
          }
        };
    var session =
        PtySession.start(
            origin("hostile", "uday", "acme"),
            hostile,
            List.of("sh", "-c", "echo up; read a"),
            Map.of("TERM", "dumb"),
            Path.of("/tmp"),
            dir.resolve("hostile.ring"),
            64 * 1024,
            80,
            24);
    try {
      var client = new Collector();
      session.attach(client, true, "uday");
      client.awaitOutput("up");
      session.end("displaced");
      assertTrue(
          client.ended.await(10, TimeUnit.SECONDS),
          "the ending must reach the subscriber despite the throwing sink");
    } finally {
      assertTimeoutPreemptively(java.time.Duration.ofSeconds(10), session::close);
    }
  }

  @Test
  void aPausedSubscriberIsResyncedFromTheJournalNotLeftCorrupt() throws Exception {
    var session =
        PtySession.start(
            origin("resync", "uday", "acme"),
            PtyEvents.NONE,
            List.of(
                "sh",
                "-c",
                "read _; i=0; while [ $i -lt 300 ]; do printf '%01000d' $i; i=$((i+1)); done;"
                    + " printf BURST-END; read _; printf PHASE2; read _"),
            Map.of("TERM", "dumb"),
            Path.of("/tmp"),
            dir.resolve("resync.ring"),
            1024 * 1024,
            80,
            24,
            1);
    try {
      var client = new Collector();
      var gate = new CountDownLatch(1);
      client.gate = gate;
      var id = session.attach(client, true, "uday");

      session.input(id, 1, "\n".getBytes(StandardCharsets.UTF_8));
      var deadline = System.nanoTime() + 10_000_000_000L;
      while (session.journaledBytes() < 300_000) {
        if (System.nanoTime() > deadline) {
          throw new AssertionError("burst never landed; journaled=" + session.journaledBytes());
        }
        Thread.onSpinWait();
      }

      client.gate = null;
      gate.countDown();

      client.awaitOutput("BURST-END");
      assertTrue(client.saw(PtyMessage.Paused.class), "the overflow paused the subscriber");
      assertTrue(client.saw(PtyMessage.Continued.class), "the drain resumed it");
      var kinds = client.messages.stream().map(m -> m.getClass().getSimpleName()).toList();
      var continuedAt = kinds.indexOf("Continued");
      assertTrue(
          kinds.subList(continuedAt, kinds.size()).contains("ReplayBegin"),
          "the resync is bracketed as a replay after Continued, got: " + kinds);

      session.input(id, 2, "\n".getBytes(StandardCharsets.UTF_8));
      client.awaitOutput("PHASE2");
      var text = client.outputText();
      assertEquals(text.indexOf("PHASE2"), text.lastIndexOf("PHASE2"), "no duplicated bytes");
      var kindsAfter = client.messages.stream().map(m -> m.getClass().getSimpleName()).toList();
      assertEquals(
          text.indexOf("BURST-END"),
          text.lastIndexOf("BURST-END"),
          "no duplicated resync; messages: " + kindsAfter);
    } finally {
      session.close();
    }
  }
}
