/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.api;

import static ai.singlr.sail.api.ChatBench.as;
import static ai.singlr.sail.api.ChatBench.line;
import static ai.singlr.sail.api.ChatBench.over;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.sail.config.YamlUtil;
import ai.singlr.sail.engine.LiveStream;
import ai.singlr.sail.engine.Recorded;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/**
 * A harness that stops reading its stdin holds up nothing of its chat: a send, an answer, a stop
 * and the chat's end each return while a line waits on its pipe, no lock of the chat's or its
 * process's held, and the lines go in the order they were handed; a line its harness has not taken
 * a {@link ChatTiming#stopBound} after it was handed ends the process, its turn cut in the words of
 * that line. Each process stands in, fed what Claude Code 2.1.332 wrote.
 */
@Timeout(value = 60, unit = TimeUnit.SECONDS)
class ChatStdinTest {

  private static final String INIT = line("04b-set-permission-mode-and-background-task", 148);
  private static final String ASKS = line("02b-relay-stdio", 16);
  private static final String REQUEST = "a871f652-0656-42bf-8cc8-860ad0a34b79";
  private static final String ALWAYS = line("02b-relay-stdio", 99);
  private static final String ALWAYS_REQUEST = "d3b09c29-b79d-4151-b490-d70fdf7adb18";
  private static final Map<String, Object> ALLOW = Map.of("decision", "allow");
  private static final Duration RETURNS = Duration.ofSeconds(30);
  private static final Duration BRIEF = Duration.ofMillis(20);

  @TempDir Path dir;
  private ChatBench bench;
  private ChatHost host;

  @BeforeEach
  void setUp() {
    bench = new ChatBench(dir);
    host = bench.restarted(new ProcessEnd.Grace(BRIEF, BRIEF, BRIEF, BRIEF));
  }

  @AfterEach
  void tearDown() {
    assertTimeoutPreemptively(
        RETURNS, host::close, "a harness that stopped reading holds up no end");
    bench.close();
  }

  /** Sam's chat on this host, its process having said {@code lines} to the first message. */
  private FakeAgentProcess.Fake asked(String session, String... lines) {
    as("sam", () -> host.send(session, "hi"));
    var fake = bench.processes.latest();
    fake.says(lines);
    fake.settle();
    return fake;
  }

  private String chat() {
    return as("sam", () -> host.open("acme", "try", "claude-code", "default", false)).session();
  }

  private Map<String, Object> answer(String session, String request, Map<String, Object> body) {
    return assertTimeoutPreemptively(
        RETURNS, () -> as("sam", () -> host.answers().answer(session, request, body)).toMap());
  }

  @Test
  void aHarnessThatStopsReadingHoldsUpNoRouteAndIsEndedOnceItsLineWaitedItsBound() {
    var session = chat();
    var fake = asked(session, INIT, ASKS);
    fake.stopsReading();
    fake.onSignal(signal -> fake.exits(143));

    var answered = answer(session, REQUEST, ALLOW);
    var stopping =
        assertTimeoutPreemptively(
            RETURNS, () -> as("sam", () -> host.answers().interrupt(session)).toMap());
    var next =
        assertTimeoutPreemptively(RETURNS, () -> as("sam", () -> host.send(session, "next")));
    fake.awaitBlockedWrite();
    bench.clock.advance(Duration.ofMinutes(5));
    assertTimeoutPreemptively(RETURNS, host::sweep);
    over(fake);

    assertEquals(Map.of("seq", 4L), answered);
    assertEquals(Map.of("stopping", true), stopping);
    assertEquals(new ChatHost.Sent(5, true), next);
    assertEquals(List.of(LiveStream.userMessage("hi")), fake.written);
    assertEquals(
        List.of("message", "session", "permission", "answered", "message", "told", "interrupted"),
        bench.kinds(session).subList(0, 7));
    assertEquals(
        "Claude Code did not get your answer: its process stopped taking what Sail wrote to it,"
            + " so Sail ended it; your next message starts it again.",
        bench.items(session).get(5).get("words"));
    assertEquals(2, bench.processes.starts.size(), "the waiting message starts the next");
    assertEquals(List.of(LiveStream.userMessage("next")), bench.processes.latest().written);
  }

  @Test
  void aHarnessThatStopsReadingIsEndedWhenItsPersonEndsTheChat() {
    var session = chat();
    var fake = asked(session, INIT, ASKS);
    fake.stopsReading();
    fake.onSignal(signal -> fake.exits(143));
    answer(session, REQUEST, ALLOW);
    fake.awaitBlockedWrite();

    assertTimeoutPreemptively(
        RETURNS,
        () ->
            as(
                "sam",
                () -> {
                  host.end(session);
                  return null;
                }));
    over(fake);

    assertTrue(as("sam", () -> host.read(session, 0)).ended());
    assertEquals(List.of(LiveStream.userMessage("hi")), fake.written);
    assertEquals(1, bench.processes.starts.size());
  }

  @Test
  void linesAreWrittenInTheOrderTheyWereHanded() throws Exception {
    var session = chat();
    var fake = asked(session, INIT, ASKS, ALWAYS);
    var release = new CountDownLatch(1);
    var written = new CountDownLatch(3);
    var first = new AtomicBoolean(true);
    fake.onWrite(
        line -> {
          if (first.getAndSet(false)) {
            awaitQuietly(release);
          }
          written.countDown();
        });

    answer(session, REQUEST, ALLOW);
    answer(session, ALWAYS_REQUEST, Map.of("decision", "allow_always"));
    assertTimeoutPreemptively(
        RETURNS, () -> as("sam", () -> host.answers().interrupt(session)).toMap());
    release.countDown();

    assertTrue(written.await(20, TimeUnit.SECONDS), "every line handed was written");
    assertEquals(
        List.of(
            LiveStream.userMessage("hi"),
            taken("02b-relay-stdio", 17),
            taken("02b-relay-stdio", 100),
            "interrupt"),
        fake.written.stream()
            .map(said -> said.contains("\"subtype\": \"interrupt\"") ? "interrupt" : said)
            .toList());
  }

  /**
   * A turn the harness ends before its message is marked handed (its answer read while the line
   * that handed it is still being written) hands that message no second time, and is not said open
   * again once its message is marked handed.
   */
  @Test
  void aTurnThatEndsBeforeItsMessageIsMarkedHandedIsNotHandedItAgain() throws Exception {
    var answered = new CountDownLatch(1);
    var first = new AtomicBoolean(true);
    bench.processes.onStart(
        fake -> {
          fake.onClose(fake::exitsIfRunning);
          fake.onWrite(
              line -> {
                if (first.getAndSet(false)) {
                  fake.says(INIT, line("02b-relay-stdio", 138));
                  fake.settle();
                  answered.countDown();
                }
              });
        });
    var session = chat();

    as("sam", () -> host.send(session, "hi"));
    assertTrue(answered.await(20, TimeUnit.SECONDS), "the turn was never answered");
    var fake = bench.processes.latest();
    assertTimeoutPreemptively(
        RETURNS,
        () ->
            as(
                "sam",
                () -> {
                  host.end(session);
                  return null;
                }));
    over(fake);

    assertEquals(List.of(LiveStream.userMessage("hi")), fake.written);
    assertFalse(bench.store.find(session).orElseThrow().turnOpen(), "its turn is over");
    assertEquals(List.of("message", "session", "turn_ended"), bench.kinds(session));
  }

  /** Line {@code number} of {@code recording}, which Claude Code took, as Sail writes it. */
  private static String taken(String recording, int number) {
    return YamlUtil.dumpJson(Recorded.parsed(recording, number));
  }

  private static void awaitQuietly(CountDownLatch latch) {
    try {
      if (!latch.await(20, TimeUnit.SECONDS)) {
        throw new AssertionError("the test never let the first line go");
      }
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new AssertionError("interrupted while the first line was held", interrupted);
    }
  }
}
