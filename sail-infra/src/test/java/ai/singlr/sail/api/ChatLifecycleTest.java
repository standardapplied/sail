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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.sail.engine.AgentProcess;
import ai.singlr.sail.engine.AgentUnit;
import ai.singlr.sail.engine.LiveStream;
import ai.singlr.sail.harness.Harnesses;
import ai.singlr.sail.identity.Acting;
import ai.singlr.sail.store.ChatStore;
import ai.singlr.sail.store.ProjectStore;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
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

  /**
   * I2: a halt the workspace does not answer proves nothing, at the restart and at a start: the
   * chat stays held, its pid file kept and the message refused, until a halt it answers.
   */
  @Test
  @Timeout(value = 60, unit = TimeUnit.SECONDS)
  void aHaltTheWorkspaceDoesNotAnswerKeepsTheChatHeldUntilOneItAnswers() {
    var session = bench.samsChat();
    bench.send(session, "hi");
    var old = bench.says(INIT);
    var container = bench.processes.container();
    container.unreachable(true);
    var restarted = bench.restarted();

    var unanswered = restarted.reclaim();
    var refused =
        assertThrows(ApiException.class, () -> as("sam", () -> restarted.send(session, "again")));
    var held =
        List.of(
            bench.store.find(session).orElseThrow().launched(),
            container.file(AgentUnit.chat(session).pidPath()) != null,
            container.alive(old.runId));
    container.unreachable(false);
    var answered = restarted.reclaim();
    as("sam", () -> restarted.send(session, "again"));

    assertEquals(List.of(0, 1), List.of(unanswered, answered));
    assertEquals("agent_already_running", refused.code());
    assertEquals(List.of(true, true, true), held, "launched, its pid file kept, its process left");
    assertFalse(container.alive(old.runId), "the answered halt ended it");
    assertEquals(2, bench.processes.starts.size());
    assertEquals(
        List.of("message", "session", "told", "interrupted", "message"), bench.kinds(session));
  }

  /**
   * I2: a client that exits while its agent lives on in the workspace is no proof the agent is
   * gone: the next start halts it first, so two never run.
   */
  @Test
  @Timeout(value = 60, unit = TimeUnit.SECONDS)
  void anAgentThatOutlivesItsClientIsHaltedBeforeTheNextStarts() {
    var session = bench.samsChat();
    bench.send(session, "hi");
    var first = bench.says(INIT);
    first.clientExits(1);
    over(first);

    bench.send(session, "again");

    assertEquals(2, bench.processes.starts.size());
    assertEquals(
        List.of(bench.processes.latest().runId),
        bench.processes.container().live(),
        "the first was halted before the second started");
  }

  /**
   * I2: a turn left open that the log cannot cut starts no process, so none runs that the host does
   * not hold; the next message, once the log takes it, starts the one.
   */
  @Test
  @Timeout(value = 60, unit = TimeUnit.SECONDS)
  void aCutThatFailsStartsNoProcess() {
    var session = bench.samsChat();
    bench.send(session, "hi");
    bench.says(INIT);
    var restarted = bench.restarted();
    bench.db.execute(
        "CREATE TRIGGER no_cut BEFORE INSERT ON chat_items WHEN NEW.kind = 'interrupted'"
            + " BEGIN SELECT RAISE(ABORT, 'disk full'); END");

    var refused =
        assertThrows(ApiException.class, () -> as("sam", () -> restarted.send(session, "again")));
    var started = bench.processes.starts.size();
    bench.db.execute("DROP TRIGGER no_cut");
    as("sam", () -> restarted.send(session, "again"));

    assertEquals("agent_launch_failed", refused.code());
    assertEquals(List.of(1, 2), List.of(started, bench.processes.starts.size()));
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
    fake.awaitStdinClosed();

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

  /**
   * I5, I8: a message waiting when sail restarted is delivered as its opener's, by the next, once
   * the restart's cleanup is done.
   */
  @Test
  @Timeout(value = 60, unit = TimeUnit.SECONDS)
  void aMessageWaitingWhenSailRestartedIsDelivered() throws Exception {
    var session = bench.samsChat();
    bench.send(session, "hi");
    bench.says(INIT);
    bench.send(session, "second");
    var restarted = bench.restarted();

    restarted.reclaim();
    assertTrue(restarted.deliverWaiting().join(ChatBench.PATIENCE));

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
  void aWaitingMessageThatCannotBeDeliveredNowIsSaidAndGoesFirstLater() throws Exception {
    var session = bench.samsChat();
    bench.send(session, "hi");
    bench.says(INIT);
    bench.send(session, "second");
    var claude = Harnesses.of("claude-code");
    bench.fdes.logins().remove(bench.samId, claude, "work");

    var restarted = bench.restarted();
    restarted.reclaim();
    assertTrue(restarted.deliverWaiting().join(ChatBench.PATIENCE));
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
   * I5: a process that ended before it took the message it was started for is not started again for
   * it, which would start one after another; the person is told, and it waits.
   */
  @Test
  @Timeout(value = 60, unit = TimeUnit.SECONDS)
  void aProcessThatTookNoMessageIsNotStartedAgainForIt() {
    var session = bench.samsChat();
    bench.processes.onStart(
        fake -> {
          fake.onClose(fake::exitsIfRunning);
          fake.breaksStdin();
        });

    var sent = bench.send(session, "hi");
    over(bench.processes.latest());

    assertFalse(sent.queued());
    assertEquals(1, bench.processes.starts.size());
    assertEquals(List.of("message", "told"), bench.kinds(session));
    assertEquals(
        "Claude Code's process ended before it took your message; it waits for your next one.",
        bench.items(session).getLast().get("words"));
  }

  /** I5, I10: a waiting message whose opener may no longer act here is said so, and kept. */
  @Test
  @Timeout(value = 60, unit = TimeUnit.SECONDS)
  void aWaitingMessageWhoseOpenerMayNoLongerActIsSaidAndKept() throws Exception {
    var session = bench.samsChat();
    bench.send(session, "hi");
    bench.says(INIT);
    bench.send(session, "second");
    bench.db.execute("UPDATE fdes SET status = 'disabled' WHERE id = ?", bench.samId);
    var restarted = bench.restarted();

    restarted.reclaim();
    assertTrue(restarted.deliverWaiting().join(ChatBench.PATIENCE));

    assertEquals(1, bench.processes.starts.size());
    assertEquals(
        "sam may no longer act on this box. Your waiting message was not sent; it goes with your"
            + " next one.",
        ChatLog.fields(bench.store.after(session, 0, 100).getLast()).get("words"));
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
                process.send("late", new ApiException(ErrorCode.CONFLICT, "This chat has ended.")));

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

  /**
   * I4: a reader waiting as a turn ends is answered with every entry the end wrote together, and
   * the write never waits on the reader: a reader holds nothing while it reads the log.
   */
  @Test
  @Timeout(value = 60, unit = TimeUnit.SECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
  void aReaderWaitingAsATurnEndsIsAnsweredAndHoldsUpNoWrite() throws Exception {
    for (var round = 0; round < 20; round++) {
      var session = bench.samsChat();
      bench.send(session, "hi");
      var fake = bench.says(INIT, CALLS);
      var last = bench.kinds(session).size();
      bench.readsWait();
      var reading = CompletableFuture.supplyAsync(() -> bench.read(session, last));
      bench.held.acquire();

      fake.says(ENDS);
      fake.settle();

      assertEquals(
          List.of("tool_done", "turn_ended"),
          reading.get(30, TimeUnit.SECONDS).items().stream()
              .map(item -> item.get("type"))
              .toList());
    }
  }

  /** I4: an entry logged while a reader reads is never missed: it is answered, never waited for. */
  @Test
  @Timeout(value = 60, unit = TimeUnit.SECONDS)
  void anEntryLoggedAsAReaderReadsIsNeverMissed() {
    var session = bench.samsChat();
    bench.readsWait();
    bench.clock.onRead(2, () -> bench.send(session, "hi"));

    var read = bench.read(session, 0);

    assertEquals(List.of("message"), read.items().stream().map(item -> item.get("type")).toList());
    assertEquals(0, bench.held.availablePermits(), "it never waited");
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

  /**
   * I2: a restart of the box took a chat's process with it and gave its pid to another process of
   * the person, which is never signalled: the process the pid file was written for is gone, the
   * file is cleaned, the turn it held is cut, and the next message starts another.
   */
  @Test
  @Timeout(value = 60, unit = TimeUnit.SECONDS)
  void aPidTheBoxGaveAnotherProcessIsNeverSignalled() {
    var session = bench.samsChat();
    bench.send(session, "hi");
    var old = bench.says(INIT);
    var container = bench.processes.container();
    var other = container.pidReused(old.runId);
    var restarted = bench.restarted();

    var cut = restarted.reclaim();
    var pidFile = container.file(AgentUnit.chat(session).pidPath());
    as("sam", () -> restarted.send(session, "again"));

    assertEquals(1, cut);
    assertTrue(container.alive(other), "the process that holds the pid now is not signalled");
    assertEquals(List.of(), container.commandsContaining("kill \"$1\""), "nothing is signalled");
    assertNull(pidFile, "the pid file of a process gone is cleaned");
    assertEquals(2, bench.processes.starts.size());
    assertEquals(
        List.of("message", "session", "told", "interrupted", "message"), bench.kinds(session));
  }

  /**
   * I6: an end said as a waiting message's process starts waits for that start, and then ends the
   * process: none runs on for a chat its person ended.
   */
  @Test
  @Timeout(value = 60, unit = TimeUnit.SECONDS)
  void anEndSaidAsAWaitingMessagesProcessStartsEndsIt() throws Exception {
    var session = bench.samsChat();
    bench.send(session, "hi");
    var first = bench.says(INIT);
    bench.send(session, "second");
    var ending = new AtomicReference<Thread>();
    bench.processes.onStart(
        started -> {
          started.onClose(started::exitsIfRunning);
          ending.set(bench.endsAsItStarts(session));
        });

    first.exits(0);
    over(first);
    var next = bench.processes.latest();
    next.awaitStdinClosed();
    over(next);

    assertTrue(ending.get().join(ChatBench.PATIENCE), "the end was done");
    assertEquals(List.of(LiveStream.userMessage("second")), next.written);
    assertTrue(bench.read(session, 0).ended());
    assertFalse(bench.store.find(session).orElseThrow().launched());
  }

  /**
   * I2: once a process started, nothing is left to fail its start: it is held and marked even when
   * its project is gone as it starts, so the next message never starts a second beside it.
   */
  @Test
  @Timeout(value = 60, unit = TimeUnit.SECONDS)
  void aProcessThatStartedIsHeldWhateverChangesAsItStarts() {
    var session = bench.samsChat();
    bench.processes.onStart(
        started -> {
          started.onClose(started::exitsIfRunning);
          Acting.system(() -> new ProjectStore(bench.db).delete("acme"));
        });

    var sent = bench.send(session, "hi");
    var held = bench.processes.latest();
    bench.host.close();

    assertFalse(sent.queued());
    assertEquals(List.of(LiveStream.userMessage("hi")), held.written);
    assertTrue(held.stdinClosed(), "the host held it, and ended it as sail stopped");
  }

  /**
   * I5: a message sent once the reader of its process stopped is never handed to that process,
   * whose answer nobody would read: it waits, and the next process is handed it.
   */
  @Test
  @Timeout(value = 60, unit = TimeUnit.SECONDS)
  void aMessageSentAsItsProcessIsNoLongerReadIsHandedToTheNext() {
    var session = bench.samsChat();
    bench.send(session, "hi");
    var fake = bench.says(INIT, ENDS);
    var sent = new AtomicReference<ChatHost.Sent>();
    fake.onClose(() -> {});
    fake.onSignal(
        signal -> {
          if (signal == AgentProcess.Signal.INT) {
            sent.set(bench.send(session, "second"));
            fake.exits(130);
          }
        });

    fake.failsItsReader(new IllegalStateException("database is locked"));
    over(fake);

    assertTrue(sent.get().queued());
    assertEquals(List.of(LiveStream.userMessage("hi")), fake.written, "never handed to it");
    assertEquals(2, bench.processes.starts.size());
    assertEquals(List.of(LiveStream.userMessage("second")), bench.processes.latest().written);
  }

  /**
   * I11: the sweep ends each process on a thread of its own: two whose ends are held are both
   * closed, neither waiting on the other's.
   */
  @Test
  @Timeout(value = 60, unit = TimeUnit.SECONDS)
  void theSweepEndsEachProcessWithoutWaitingOnAnother() throws Exception {
    var minute = Duration.ofMinutes(1);
    var host = bench.restarted(new ProcessEnd.Grace(minute, minute, minute, minute));
    var one = bench.samsChat();
    var two = bench.samsChat();
    as("sam", () -> host.send(one, "hi"));
    var first = bench.processes.latest().holdsItsEnd();
    as("sam", () -> host.send(two, "hi"));
    var second = bench.processes.latest().holdsItsEnd();
    Acting.system(() -> bench.fdes.remove(bench.samId));

    var sweeping = Thread.ofVirtual().start(host::sweep);
    first.awaitStdinClosed();
    second.awaitStdinClosed();
    first.releasesItsEnd();
    second.releasesItsEnd();

    assertTrue(sweeping.join(ChatBench.PATIENCE), "the sweep was done");
  }

  /**
   * I2, I5: a send that reaches a chat before the restart's delivery does starts its process; the
   * delivery then leaves that process alone, and the waiting message goes before the new one.
   */
  @Test
  @Timeout(value = 60, unit = TimeUnit.SECONDS)
  void aSendBeforeTheRestartsDeliveryIsTheOneProcessAndKeepsTheOrder() throws Exception {
    var session = bench.samsChat();
    bench.send(session, "hi");
    bench.says(INIT);
    bench.send(session, "second");
    var restarted = bench.restarted();
    restarted.reclaim();

    var sent = as("sam", () -> restarted.send(session, "third"));
    var started = bench.processes.latest();
    assertTrue(restarted.deliverWaiting().join(ChatBench.PATIENCE));
    started.says(INIT, ENDS);
    started.settle();

    assertTrue(sent.queued());
    assertEquals(2, bench.processes.starts.size(), "one start since the restart");
    assertSame(started, bench.processes.latest());
    assertEquals(List.of(), started.signals);
    assertTrue(bench.processes.container().alive(started.runId), "never halted");
    assertEquals(
        List.of(LiveStream.userMessage("second"), LiveStream.userMessage("third")),
        started.written);
    assertEquals(
        1,
        bench.kinds(session).stream().filter("interrupted"::equals).count(),
        "only the turn the restart left is cut");
  }

  /** The restart's delivery runs on a thread of its own: whoever starts it is not held by it. */
  @Test
  @Timeout(value = 60, unit = TimeUnit.SECONDS)
  void theRestartsDeliveryHoldsUpNoneWhoStartIt() throws Exception {
    var session = bench.samsChat();
    bench.send(session, "hi");
    bench.says(INIT);
    bench.send(session, "second");
    var restarted = bench.restarted();
    restarted.reclaim();
    var release = new CountDownLatch(1);
    bench.processes.onStart(
        started -> {
          started.onClose(started::exitsIfRunning);
          try {
            release.await(ChatBench.PATIENCE.toSeconds(), TimeUnit.SECONDS);
          } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
          }
        });

    var delivering = restarted.deliverWaiting();
    var heldWhileDelivering = delivering.isAlive();
    release.countDown();

    assertTrue(heldWhileDelivering, "the call returned while its delivery waited");
    assertTrue(delivering.join(ChatBench.PATIENCE));
    assertEquals(List.of(LiveStream.userMessage("second")), bench.processes.latest().written);
  }
}
