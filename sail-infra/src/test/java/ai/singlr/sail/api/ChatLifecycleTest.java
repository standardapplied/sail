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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.sail.engine.AgentProcess;
import ai.singlr.sail.engine.AgentUnit;
import ai.singlr.sail.engine.LiveStream;
import ai.singlr.sail.harness.Harnesses;
import ai.singlr.sail.identity.Acting;
import ai.singlr.sail.store.ChatStore;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/**
 * A chat's process from its start to its end and past a restart: one process a conversation, a
 * process left from before a restart or by a reader that stopped found and proved gone before
 * another starts, the restart's cleanup never cutting a live turn, an end read as ended only with
 * its last item, a message left waiting delivered by the next process, an idle end that cuts no
 * message, a request's bound that stops the turn, and every turn closed once.
 */
class ChatLifecycleTest {

  private static final String INIT = line("04b-set-permission-mode-and-background-task", 148);
  private static final String CALLS = line("01-baseline-live-flags", 13);
  private static final String ENDS = line("02b-relay-stdio", 138);
  private static final String ASKS = line("04a-cancel-request-by-interrupt", 16);
  private static final List<String> STOPS =
      List.of(
          line("04a-cancel-request-by-interrupt", 20),
          line("04a-cancel-request-by-interrupt", 21),
          line("04a-cancel-request-by-interrupt", 23),
          line("04a-cancel-request-by-interrupt", 24));

  @TempDir Path dir;
  private ChatBench bench;

  @BeforeEach
  void setUp() {
    bench = new ChatBench(dir);
  }

  @AfterEach
  void tearDown() {
    bench.close();
  }

  /**
   * I2, I8: a process a restart left is found by its chat's pid file, signalled and proved gone
   * before the turn it held is cut, and only then does the next message start one, resuming the
   * conversation: never two beside each other.
   */
  @Test
  @Timeout(value = 60, unit = TimeUnit.SECONDS)
  void aRestartHaltsTheProcessItLeftBeforeAnotherStarts() {
    var session = bench.samsChat();
    bench.send(session, "hi");
    var old = bench.says(INIT);
    var restarted = bench.restarted();

    var cut = restarted.reclaim();
    as("sam", () -> restarted.send(session, "again"));

    assertEquals(1, cut);
    assertFalse(bench.processes.container().alive(old.runId), "the process left is gone");
    assertFalse(
        bench.processes.container().commandsContaining(AgentUnit.chat(session).pidPath()).isEmpty(),
        "found by its chat's own pid file");
    assertEquals(2, bench.processes.starts.size());
    assertEquals(
        List.of("message", "session", "told", "interrupted", "message"), bench.kinds(session));
    assertEquals(ChatHost.RESTARTED, bench.items(session).get(2).get("words"));
  }

  /** I2: while the process left is not proved gone, no message starts another, and it is said. */
  @Test
  @Timeout(value = 60, unit = TimeUnit.SECONDS)
  void aProcessLeftThatWillNotEndKeepsItsChatFromStartingAnother() {
    var session = bench.samsChat();
    bench.send(session, "hi");
    bench.says(INIT);
    bench.processes.container().survivesKill(true);
    var restarted = bench.restarted();

    var cut = restarted.reclaim();
    var refused =
        assertThrows(ApiException.class, () -> as("sam", () -> restarted.send(session, "again")));
    bench.processes.container().survivesKill(false);
    as("sam", () -> restarted.send(session, "again"));

    assertEquals(0, cut, "a turn whose process is not proved gone is not cut yet");
    assertEquals(
        List.of(
            "agent_already_running",
            "Claude Code's earlier process for this chat in workspace try has not ended yet, so the"
                + " message was not sent."),
        List.of(refused.code(), refused.reason()));
    assertEquals(2, bench.processes.starts.size(), "the second only once the first is gone");
    assertEquals(
        List.of("message", "session", "told", "interrupted", "message"), bench.kinds(session));
  }

  /** I2: a reader that stops, for any reason, ends its process: none runs on unread. */
  @Test
  @Timeout(value = 60, unit = TimeUnit.SECONDS)
  void aReaderThatStopsEndsItsProcess() {
    var session = bench.samsChat();
    bench.send(session, "hi");
    var fake = bench.says(INIT);
    fake.onClose(() -> {});
    fake.onSignal(
        signal -> {
          if (signal == AgentProcess.Signal.INT) {
            fake.exits(130);
          }
        });

    fake.failsItsReader(new IllegalStateException("database is locked"));
    over(fake);

    assertTrue(fake.stdinClosed());
    assertEquals(List.of(AgentProcess.Signal.INT), fake.signals);
    assertFalse(bench.store.find(session).orElseThrow().launched(), "known gone");
    assertEquals(
        "Claude Code's process ended before its turn did (exit code 130); your next message starts"
            + " it again.",
        bench.items(session).get(2).get("words"));
  }

  /** I8: the restart's cleanup never cuts a turn a process of this sail holds. */
  @Test
  @Timeout(value = 60, unit = TimeUnit.SECONDS)
  void theRestartsCleanupLeavesALiveTurnAlone() {
    var session = bench.samsChat();
    bench.send(session, "hi");
    var fake = bench.says(INIT);

    var cut = bench.host.reclaim();
    fake.says(ENDS);
    fake.settle();

    assertEquals(0, cut);
    assertEquals(List.of("message", "session", "turn_ended"), bench.kinds(session));
  }

  /**
   * I4, I6: an ended chat is said to be only with its last item, once its process is gone and the
   * end's own items are logged: a reader that stops at it has everything.
   */
  @Test
  @Timeout(value = 60, unit = TimeUnit.SECONDS)
  void anEndIsReadAsEndedOnlyWithItsLastItem() throws Exception {
    var session = bench.samsChat();
    bench.send(session, "hi");
    var fake = bench.says(INIT, CALLS);
    fake.onClose(() -> {});
    var last = bench.kinds(session).size();
    var ending =
        Thread.ofVirtual()
            .start(
                () ->
                    as(
                        "sam",
                        () -> {
                          bench.host.end(session);
                          return null;
                        }));
    fake.awaitStdinClosed();

    var during = bench.read(session, last);
    fake.exits(0);
    over(fake);
    ending.join(Duration.ofSeconds(30));
    var after = bench.read(session, last);

    assertEquals(new ChatHost.Read(List.of(), false), during);
    assertTrue(after.ended());
    assertEquals(
        List.of("tool_done", "interrupted"),
        after.items().stream().map(item -> item.get("type")).toList());
  }

  /** I4: a page cut short, by count or by size, never says the chat ended. */
  @Test
  @Timeout(value = 60, unit = TimeUnit.SECONDS)
  void aPageCutShortNeverSaysTheChatEnded() {
    var session = bench.samsChat();
    for (var i = 0; i < ChatHost.PAGE + 1; i++) {
      bench.store.append(session, "told", "{\"words\": \"" + i + "\"}", bench.clock.instant());
    }
    var large = "{\"words\": \"" + "a".repeat(ChatHost.PAGE_CHARS / 2 + 1) + "\"}";
    bench.store.append(session, "told", large, bench.clock.instant());
    bench.store.append(session, "told", large, bench.clock.instant());
    as(
        "sam",
        () -> {
          bench.host.end(session);
          return null;
        });

    var first = bench.read(session, 0);
    var sized = bench.read(session, ChatHost.PAGE + 1);
    var rest = bench.read(session, ChatHost.PAGE + 2);

    assertEquals(
        List.of(ChatHost.PAGE, false, 1, false, 1, true),
        List.of(
            first.items().size(),
            first.ended(),
            sized.items().size(),
            sized.ended(),
            rest.items().size(),
            rest.ended()));
  }

  /**
   * I5: a message said while the process ends is answered queued, and the next process is started
   * for it and handed it as it ends: the person need not send again.
   */
  @Test
  @Timeout(value = 60, unit = TimeUnit.SECONDS)
  void aMessageSentAsTheIdleEndClosesTheProcessIsDeliveredByTheNext() {
    var session = bench.samsChat();
    bench.send(session, "hi");
    var fake = bench.says(INIT, ENDS);
    var sent = new AtomicReference<ChatHost.Sent>();
    fake.onClose(
        () -> {
          sent.set(bench.send(session, "second"));
          fake.exitsIfRunning();
        });
    bench.clock.advance(Duration.ofMinutes(20));

    bench.host.sweep();
    over(fake);

    assertTrue(sent.get().queued());
    assertEquals(List.of(LiveStream.userMessage("hi")), fake.written, "never handed to the ending");
    assertEquals(2, bench.processes.starts.size());
    assertEquals(List.of(LiveStream.userMessage("second")), bench.processes.latest().written);
    assertEquals(
        List.of("message", "session", "turn_ended", "message", "delivered"), bench.kinds(session));
  }

  /** I5, I8: a message waiting when sail restarted is delivered as its opener's, by the next. */
  @Test
  @Timeout(value = 60, unit = TimeUnit.SECONDS)
  void aMessageWaitingWhenSailRestartedIsDelivered() {
    var session = bench.samsChat();
    bench.send(session, "hi");
    bench.says(INIT);
    bench.send(session, "second");

    bench.restarted().reclaim();

    assertEquals(2, bench.processes.starts.size());
    assertEquals(List.of(LiveStream.userMessage("second")), bench.processes.latest().written);
    assertEquals(
        List.of("message", "session", "message", "told", "interrupted", "delivered"),
        bench.kinds(session));
  }

  /**
   * I5: a waiting message whose chat may not start now is said so in the refusal's words and keeps
   * waiting, never dropped: it goes first with the next message, which waits behind it.
   */
  @Test
  @Timeout(value = 60, unit = TimeUnit.SECONDS)
  void aWaitingMessageThatCannotBeDeliveredNowIsSaidAndGoesFirstLater() {
    var session = bench.samsChat();
    bench.send(session, "hi");
    bench.says(INIT);
    bench.send(session, "second");
    var claude = Harnesses.of("claude-code");
    bench.fdes.logins().remove(bench.samId, claude, "work");

    var restarted = bench.restarted();
    restarted.reclaim();
    var said = bench.items(session).getLast();
    bench.fdes.logins().store(bench.samId, claude, "work", "api-key", "fake-key-sam");
    var third = as("sam", () -> restarted.send(session, "third"));

    assertEquals("told", said.get("type"));
    assertTrue(
        ((String) said.get("words"))
            .endsWith(" Your waiting message was not sent; it goes with your next one."),
        String.valueOf(said.get("words")));
    assertTrue(third.queued(), "an earlier message still waits");
    assertEquals(2, bench.processes.starts.size());
    assertEquals(List.of(LiveStream.userMessage("second")), bench.processes.latest().written);
  }

  /**
   * I9: a request unanswered at its bound has its turn stopped, said in plain words; the process
   * stays, and the harness withdraws the request as it stops.
   */
  @Test
  @Timeout(value = 60, unit = TimeUnit.SECONDS)
  void aRequestUnansweredAtItsBoundHasItsTurnStoppedAndTheProcessKept() {
    var session = bench.samsChat();
    bench.send(session, "hi");
    var fake = bench.says(INIT, ASKS);
    fake.onWrite(
        written -> {
          if (written.contains("\"interrupt\"")) {
            fake.says(STOPS.toArray(String[]::new));
          }
        });

    bench.clock.advance(Duration.ofMinutes(4));
    bench.host.sweep();
    var within = bench.kinds(session);
    bench.clock.advance(Duration.ofMinutes(1));
    bench.host.sweep();
    fake.settle();

    assertEquals(List.of("message", "session", "permission"), within);
    assertEquals(
        List.of(
            "message", "session", "permission", "told", "withdrawn", "interrupted", "turn_ended"),
        bench.kinds(session));
    assertEquals(
        "Nobody can answer the agent's request in this version of Sail, so after 5 minutes Sail"
            + " stopped its turn and withdrew the request.",
        bench.items(session).get(3).get("words"));
    assertFalse(fake.stdinClosed(), "the process is kept");
  }

  /** I9: a turn that does not stop a bound after it was asked to has its process ended. */
  @Test
  @Timeout(value = 60, unit = TimeUnit.SECONDS)
  void aTurnThatDoesNotStopHasItsProcessEnded() {
    var session = bench.samsChat();
    bench.send(session, "hi");
    var fake = bench.says(INIT, ASKS);
    bench.clock.advance(Duration.ofMinutes(5));
    bench.host.sweep();

    bench.clock.advance(Duration.ofMinutes(5));
    bench.host.sweep();
    over(fake);

    assertTrue(fake.stdinClosed());
    assertEquals(
        List.of("message", "session", "permission", "told", "told", "withdrawn", "interrupted"),
        bench.kinds(session));
    assertEquals(
        "Claude Code's turn could not be stopped, so Sail ended its process; your next message"
            + " starts it again.",
        bench.items(session).get(4).get("words"));
  }

  /** I7: a request still open when its turn ends is withdrawn: nothing can answer it then. */
  @Test
  @Timeout(value = 60, unit = TimeUnit.SECONDS)
  void aRequestStillOpenWhenItsTurnEndsIsWithdrawn() {
    var session = bench.samsChat();
    bench.send(session, "hi");

    bench.says(INIT, ASKS, ENDS);

    assertEquals(
        List.of("message", "session", "permission", "withdrawn", "turn_ended"),
        bench.kinds(session));
  }

  /** I8: a turn is cut once, however many cut it: its notice is never said twice. */
  @Test
  @Timeout(value = 60, unit = TimeUnit.SECONDS)
  void aTurnIsCutOnce() {
    var session = bench.samsChat();
    bench.store.turn(session, true);
    var log = new ChatLog(bench.store, session, ChatTiming.real(), () -> {});

    log.cut("first");
    log.cut("second");

    assertEquals(List.of("told", "interrupted"), bench.kinds(session));
  }

  /** I6: a process its person ended takes no message, whatever the host found a moment before. */
  @Test
  @Timeout(value = 60, unit = TimeUnit.SECONDS)
  void aProcessWhoseChatEndedTakesNoMessage() throws Exception {
    var session = bench.samsChat();
    var unit = AgentUnit.chat(session);
    var fake =
        (FakeAgentProcess.Fake)
            bench.processes.start(
                "acme-try",
                ChatLaunch.WORKSPACE,
                Map.of(),
                List.of("claude"),
                unit.pidPath(),
                unit.logPath(),
                unit.launchEnvPath());
    var process =
        new ChatProcess(
            session,
            "Claude Code",
            new ChatLog(bench.store, session, ChatTiming.real(), () -> {}),
            Harnesses.of("claude-code").chat().orElseThrow(),
            fake,
            ChatTiming.real(),
            Duration.ofMinutes(20),
            ended -> {});
    process.read();
    process.end().endChat(Duration.ZERO);

    var refused =
        assertThrows(
            ApiException.class,
            () ->
                process.send(
                    "late", () -> new ApiException(ErrorCode.CONFLICT, "This chat has ended.")));

    assertEquals("This chat has ended.", refused.reason());
    assertEquals(List.of(), bench.kinds(session), "nothing of it is logged");
  }

  /** I11: the process of a chat erased with its person is ended at the next pass. */
  @Test
  @Timeout(value = 60, unit = TimeUnit.SECONDS)
  void aProcessWhoseChatWasErasedIsEnded() {
    var session = bench.samsChat();
    bench.send(session, "hi");
    var fake = bench.says(INIT);

    Acting.system(() -> bench.fdes.remove(bench.samId));
    bench.host.sweep();
    over(fake);

    assertTrue(fake.stdinClosed());
    assertTrue(bench.store.find(session).isEmpty());
  }

  /** An ended chat's folder in its workspace goes once its process is gone. */
  @Test
  @Timeout(value = 60, unit = TimeUnit.SECONDS)
  void anEndedChatsFolderGoesWithItsProcess() {
    var session = bench.samsChat();
    bench.send(session, "hi");
    var fake = bench.says(INIT, ENDS);

    as(
        "sam",
        () -> {
          bench.host.end(session);
          return null;
        });
    over(fake);

    assertEquals(
        1,
        bench
            .processes
            .container()
            .commandsContaining("rm -rf -- " + AgentUnit.chatDir(session))
            .size(),
        String.valueOf(bench.processes.container().commands()));
    assertTrue(bench.read(session, 0).ended());
  }

  /** I3: an item as large as an item may be is read back whole. */
  @Test
  @Timeout(value = 60, unit = TimeUnit.SECONDS)
  void anItemAsLargeAsAnyIsReadBack() {
    var session = bench.samsChat();
    var words = "a".repeat(ChatStore.MAX_PAYLOAD_CHARS - "{\"words\": \"\"}".length());
    bench.store.append(session, "told", "{\"words\": \"" + words + "\"}", bench.clock.instant());

    var read = bench.read(session, 0);

    assertEquals(words, read.items().getFirst().get("words"));
  }

  /** I4: a log entry wakes only its own chat's readers. */
  @Test
  @Timeout(value = 60, unit = TimeUnit.SECONDS)
  void anEntryWakesOnlyItsOwnChatsReaders() throws Exception {
    var mine = bench.samsChat();
    var other = bench.samsChat();
    bench.readsWait();
    var reading = Thread.ofVirtual().start(() -> bench.read(mine, 0));
    var waiting = Thread.ofVirtual().start(() -> bench.read(other, 0));
    bench.held.acquire(2);

    bench.send(mine, "hi");
    reading.join(Duration.ofSeconds(30));
    bench.send(other, "hi");
    waiting.join(Duration.ofSeconds(30));

    assertEquals(0, bench.held.availablePermits(), "no reader of the other chat was woken");
  }
}
