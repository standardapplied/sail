/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.api;

import static ai.singlr.sail.api.ChatBench.as;
import static ai.singlr.sail.api.ChatBench.line;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.sail.common.Ids;
import ai.singlr.sail.common.Remedied;
import ai.singlr.sail.engine.AgentProcess;
import ai.singlr.sail.engine.AgentUnit;
import ai.singlr.sail.engine.LiveChat;
import ai.singlr.sail.engine.LiveStream;
import ai.singlr.sail.engine.Recorded;
import ai.singlr.sail.engine.SessionLogin;
import ai.singlr.sail.harness.HarnessChat;
import ai.singlr.sail.harness.Harnesses;
import ai.singlr.sail.identity.Actor;
import ai.singlr.sail.identity.Role;
import ai.singlr.sail.pty.PtyIdentity;
import ai.singlr.sail.pty.PtyMessage;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/**
 * A chat as its host holds it: opened as a pty agent starts, one process a conversation started
 * only by a message, every item logged before it is read and read from where a reader left off, a
 * message held while a turn is open, a request held and withdrawn in plain words at its bound, the
 * process ended when idle, when its chat ends and when sail stops, and the turn a restart cut
 * closed as cut. Each process stands in, fed what Claude Code 2.1.332 wrote.
 */
class ChatHostTest {

  private static final String INIT = line("04b-set-permission-mode-and-background-task", 148);
  private static final String CONVERSATION = "32c4139c-5288-4783-b817-4f6b504f696d";
  private static final String TEXT_STARTS = line("01-baseline-live-flags", 20);
  private static final String TEXT = line("01-baseline-live-flags", 21);
  private static final String CALLS = line("01-baseline-live-flags", 13);
  private static final String RETURNS = line("01-baseline-live-flags", 17);
  private static final String ENDS = line("02b-relay-stdio", 138);
  private static final String ASKS = line("02b-relay-stdio", 16);
  private static final String REQUEST = "a871f652-0656-42bf-8cc8-860ad0a34b79";
  private static final String ACCEPTS_EDITS = line("02b-relay-stdio", 103);
  private static final String CALL = "toolu_011VvCwXcvkemp321CMEyur6";

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

  @Test
  @Timeout(value = 60, unit = TimeUnit.SECONDS)
  void aChatOpensInItsOpenersOwnWorkspaceAndNothingStartsUntilAMessage() {
    var session = bench.samsChat();

    var chat = bench.store.find(session).orElseThrow();
    assertEquals(
        List.of(bench.samId, "acme", "try", "claude-code", "default", false),
        List.of(
            chat.opener(), chat.project(), chat.workspace(), chat.harness(), chat.mode(), false));
    assertEquals(List.of(), bench.processes.starts, "no process until a message");
    assertEquals(List.of(), bench.kinds(session));
  }

  /** I1: the same refusals a pty agent start gets, in its order, word for word. */
  @Test
  @Timeout(value = 60, unit = TimeUnit.SECONDS)
  void aChatIsRefusedAsAPtyAgentStartIsWordForWord() {
    var refused =
        List.of(
            List.of("tom", "acme", "try"),
            List.of("tom", "acme", "tom"),
            List.of("sam", "beta", "dock"));
    var codes = new java.util.ArrayList<String>();
    for (var start : refused) {
      var who = start.get(0);
      var project = start.get(1);
      var workspace = start.get(2);
      var api =
          assertThrows(
              ApiException.class,
              () ->
                  as(
                      who,
                      () -> bench.host.open(project, workspace, "claude-code", "default", false)));
      var pty =
          assertThrows(
              Remedied.Io.class,
              () -> {
                var person = new PtyIdentity.Person(new Actor(who, Role.MEMBER, Actor.Lane.API));
                bench.door.admitWorkspace(project, workspace, person);
                bench.door.admitAgent(
                    project,
                    workspace,
                    new PtyMessage.Agent("claude-code", "default", false),
                    person);
              });
      assertEquals(List.of(pty.reason(), pty.remedy()), List.of(api.reason(), api.remedy()));
      codes.add(api.code());
    }
    assertEquals(List.of("forbidden", "login_not_stored", "harness_not_installed"), codes);
    assertEquals(0, bench.chats(), "nothing is recorded of a refused chat");
  }

  @Test
  @Timeout(value = 60, unit = TimeUnit.SECONDS)
  void aPersonWhoSignsInInsideOpensAChatWithNoStoredLogin() {
    var session =
        as("tom", () -> bench.host.open("acme", "tom", "claude-code", "default", true)).session();

    assertTrue(bench.store.find(session).orElseThrow().inside());
  }

  @Test
  @Timeout(value = 60, unit = TimeUnit.SECONDS)
  void onlyAHarnessWithAChatFormStartsAndOnlyInAModeItsChatOffers() {
    var codex =
        assertThrows(
            IllegalArgumentException.class,
            () -> as("sam", () -> bench.host.open("acme", "try", "codex", "full-access", false)));
    var risky =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                as(
                    "sam",
                    () ->
                        bench.host.open("acme", "try", "claude-code", "bypassPermissions", false)));

    assertEquals("Codex CLI has no chat form; start it in a terminal.", codex.getMessage());
    assertEquals(
        "Claude Code takes no mode 'bypassPermissions' in a workspace; it takes default,"
            + " acceptEdits, plan.",
        risky.getMessage());
    assertEquals(0, bench.chats());
  }

  /** I2, I10: one process, signed in through the login file, its argv a list, never a shell. */
  @Test
  @Timeout(value = 60, unit = TimeUnit.SECONDS)
  void aMessageStartsTheProcessSignedInAndHandsItTheMessageAsALine() {
    var session = bench.samsChat();

    var sent = bench.send(session, "hi; $(rm -rf ~)");

    var started = bench.processes.starts.getFirst();
    assertEquals(new ChatHost.Sent(1, false), sent);
    assertEquals(
        Harnesses.of("claude-code")
            .chat()
            .orElseThrow()
            .argv(new HarnessChat.Launch("default", null, null)),
        started.command());
    assertEquals(
        List.of("acme-try", "/home/dev/workspace"),
        List.of(started.container(), started.workDir()));
    assertEquals(Map.of(), started.env(), "nothing of the person's message is in its environment");
    assertEquals(
        List.of(AgentUnit.chat(session).pidPath(), AgentUnit.chat(session).logPath()),
        List.of(started.pidFile(), started.logFile()),
        "one place for every process of the chat, which a later sail finds");
    var handed = bench.incus.inputs().stream().filter(in -> in.contains("fake-key-sam")).toList();
    assertEquals(1, handed.size(), "the login reaches the workspace once, as the file's input");
    assertTrue(
        bench.incus.invocations().stream()
            .noneMatch(
                command -> command.contains("fake-key-sam") || command.contains("hi; $(rm -rf ~)")),
        "no login and no message on a command line");
    assertEquals(
        List.of(LiveStream.userMessage("hi; $(rm -rf ~)")), bench.processes.latest().written);
  }

  /** I3: logged in order before it is read; one block's text is one item. */
  @Test
  @Timeout(value = 60, unit = TimeUnit.SECONDS)
  void everyLineIsLoggedInOrderAndOneBlocksTextIsOneItem() {
    var session = bench.samsChat();
    bench.send(session, "hi");

    bench.says(INIT, TEXT_STARTS, TEXT, CALLS, RETURNS, ENDS);

    var items = bench.items(session);
    assertEquals(
        List.of("message", "session", "text", "tool", "tool_done", "turn_ended"),
        items.stream().map(item -> item.get("type")).toList());
    assertEquals(
        List.of(1L, 2L, 3L, 4L, 5L, 6L),
        items.stream().map(item -> ((Number) item.get("seq")).longValue()).toList());
    assertEquals(
        Map.of(
            "seq",
            3L,
            "at",
            "2026-11-27T09:00:00.000Z",
            "type",
            "text",
            "text",
            "done",
            "new_paragraph",
            true,
            "clipped",
            false),
        longs(items.get(2)));
    assertEquals(
        Map.of(
            "seq",
            6L,
            "at",
            "2026-11-27T09:00:00.000Z",
            "type",
            "turn_ended",
            "cost_usd",
            0.000301485,
            "tokens_in",
            23708L,
            "tokens_out",
            86L,
            "duration_ms",
            940L),
        longs(items.get(5)));
    assertEquals(CONVERSATION, bench.store.find(session).orElseThrow().conversation());
    assertFalse(bench.store.find(session).orElseThrow().turnOpen());
  }

  @Test
  @Timeout(value = 60, unit = TimeUnit.SECONDS)
  void textAfterTheWindowIsAnItemOfItsOwn() {
    var session = bench.samsChat();
    bench.send(session, "hi");
    bench.says(TEXT_STARTS, TEXT);

    bench.clock.advance(LiveMembers.DELTA_WINDOW);
    bench.says(TEXT, ENDS);

    assertEquals(List.of("message", "text", "text", "turn_ended"), bench.kinds(session));
  }

  /** I4: a reader that reconnects with where it left off sees nothing twice and nothing lost. */
  @Test
  @Timeout(value = 60, unit = TimeUnit.SECONDS)
  void aReaderFromWhereItLeftOffSeesEachItemOnce() {
    var session = bench.samsChat();
    bench.send(session, "hi");
    bench.says(INIT);
    var first = bench.read(session, 0);

    bench.says(TEXT_STARTS, TEXT, ENDS);
    var next = bench.read(session, 2);

    assertEquals(List.of(1L, 2L), seqs(first));
    assertEquals(List.of(3L, 4L), seqs(next));
    assertFalse(next.ended());
  }

  @Test
  @Timeout(value = 60, unit = TimeUnit.SECONDS)
  void aReadHoldsUntilAnItemIsLogged() throws Exception {
    var session = bench.samsChat();
    bench.send(session, "hi");
    bench.readsWait();

    var read = CompletableFuture.supplyAsync(() -> bench.read(session, 1));
    assertTrue(bench.held.tryAcquire(30, TimeUnit.SECONDS), "the read never waited");
    bench.says(INIT);

    assertEquals(List.of(2L), seqs(read.get(30, TimeUnit.SECONDS)));
  }

  @Test
  @Timeout(value = 60, unit = TimeUnit.SECONDS)
  void aReadWithNothingNewAnswersEmptyOnceItsBoundPasses() {
    var session = bench.samsChat();

    var read = bench.read(session, 0);

    assertEquals(new ChatHost.Read(List.of(), false), read);
    assertEquals(ChatBench.START.plusSeconds(25), bench.clock.instant(), "the bound is 25 s");
    assertEquals(List.of(), bench.processes.starts, "reading never starts a process");
  }

  /** I5: a message said mid-turn is held, logged so, and given when the turn ends. */
  @Test
  @Timeout(value = 60, unit = TimeUnit.SECONDS)
  void aMessageSentWhileATurnIsOpenIsQueuedAndHandedWhenTheTurnEnds() {
    var session = bench.samsChat();
    bench.send(session, "first");
    bench.says(INIT);

    var queued = bench.send(session, "second");
    var fake = bench.says(ENDS);

    assertEquals(new ChatHost.Sent(3, true), queued);
    assertEquals(
        List.of(LiveStream.userMessage("first"), LiveStream.userMessage("second")), fake.written);
    assertEquals(
        List.of("message", "session", "message", "turn_ended", "delivered"), bench.kinds(session));
    assertEquals(3L, ((Number) bench.items(session).get(4).get("message")).longValue());
    assertEquals(true, bench.items(session).get(2).get("queued"));
  }

  /** I2: idle past max_idle, the process ends; the next message resumes by id in the mode said. */
  @Test
  @Timeout(value = 60, unit = TimeUnit.SECONDS)
  void anIdleProcessEndsAndTheNextMessageResumesItsConversationInTheModeInForce() {
    var session = bench.samsChat();
    bench.send(session, "hi");
    var fake = bench.says(INIT, ACCEPTS_EDITS, ENDS);

    bench.clock.advance(Duration.ofMinutes(19));
    bench.host.sweep();
    assertFalse(fake.stdinClosed(), "not idle yet");
    bench.clock.advance(Duration.ofMinutes(1));
    bench.host.sweep();
    ChatBench.over(fake);
    bench.send(session, "again");

    assertTrue(fake.stdinClosed());
    assertEquals(2, bench.processes.starts.size());
    assertEquals(
        Harnesses.of("claude-code")
            .chat()
            .orElseThrow()
            .argv(new HarnessChat.Launch("acceptEdits", null, CONVERSATION)),
        bench.processes.starts.getLast().command());
  }

  @Test
  @Timeout(value = 60, unit = TimeUnit.SECONDS)
  void aProcessWithATurnOpenIsNeverIdle() {
    var session = bench.samsChat();
    bench.send(session, "hi");
    var fake = bench.says(INIT);

    bench.clock.advance(Duration.ofHours(1));
    bench.host.sweep();

    assertFalse(fake.stdinClosed());
  }

  @Test
  @Timeout(value = 60, unit = TimeUnit.SECONDS)
  void twoMessagesAtOnceStartOneProcess() throws Exception {
    var session = bench.samsChat();

    var one = CompletableFuture.supplyAsync(() -> bench.send(session, "one"));
    var two = CompletableFuture.supplyAsync(() -> bench.send(session, "two"));

    assertEquals(
        List.of(false, true),
        List.of(one.get(), two.get()).stream()
            .sorted((a, b) -> Long.compare(a.seq(), b.seq()))
            .map(ChatHost.Sent::queued)
            .toList());
    assertEquals(1, bench.processes.starts.size());
  }

  /** I7: what the harness does on its own, said truthfully. */
  @Test
  @Timeout(value = 60, unit = TimeUnit.SECONDS)
  void aTurnTheAgentBeginsItselfIsATurnAndAMessageWaitsForItsEnd() {
    var session = bench.samsChat();
    bench.send(session, "hi");
    bench.says(INIT, ENDS);

    bench.says(INIT);
    var sent = bench.send(session, "later");
    bench.says(line("04b-set-permission-mode-and-background-task", 159));

    assertTrue(sent.queued());
    assertEquals(
        List.of(
            "message",
            "session",
            "turn_ended",
            "message",
            "self_started",
            "turn_ended",
            "delivered"),
        bench.kinds(session));
  }

  @Test
  @Timeout(value = 60, unit = TimeUnit.SECONDS)
  void openToolRowsAreClosedWhenTheTurnEnds() {
    var session = bench.samsChat();
    bench.send(session, "hi");

    bench.says(INIT, CALLS, ENDS);

    var closed = bench.items(session).get(3);
    assertEquals(
        Map.of("type", "tool_done", "id", CALL, "output", ChatLog.UNREAD, "failed", false),
        Map.of(
            "type", closed.get("type"),
            "id", closed.get("id"),
            "output", closed.get("output"),
            "failed", closed.get("failed")));
  }

  @Test
  @Timeout(value = 60, unit = TimeUnit.SECONDS)
  void aProcessThatDiesMidTurnIsSaidItsTurnCutAndItsRequestWithdrawn() {
    var session = bench.samsChat();
    bench.send(session, "hi");
    var fake = bench.says(INIT, CALLS, ASKS);

    fake.exits(1);
    fake.settle();

    var items = bench.items(session);
    assertEquals(
        List.of(
            "message",
            "session",
            "tool",
            "permission",
            "told",
            "tool_done",
            "withdrawn",
            "interrupted"),
        items.stream().map(item -> item.get("type")).toList());
    assertEquals(
        "Claude Code's process ended before its turn did (exit code 1); your next message starts"
            + " it again.",
        items.get(4).get("words"));
    assertEquals(REQUEST, items.get(6).get("id"));
    assertFalse(bench.store.find(session).orElseThrow().turnOpen());
  }

  /** I6: an end ends the process and the chat; the log stays, and takes no message. */
  @Test
  @Timeout(value = 60, unit = TimeUnit.SECONDS)
  void endingAChatEndsItsProcessKeepsItsLogAndRefusesAMessage() {
    var session = bench.samsChat();
    bench.send(session, "hi");
    var fake = bench.says(INIT, ENDS);

    as(
        "sam",
        () -> {
          bench.host.end(session);
          return null;
        });
    fake.settle();
    var refused = assertThrows(ApiException.class, () -> bench.send(session, "more"));

    assertTrue(fake.stdinClosed());
    assertTrue(bench.read(session, 0).ended());
    assertEquals(List.of("message", "session", "turn_ended"), bench.kinds(session));
    assertEquals(
        List.of("conflict", "This chat has ended, so it takes no message."),
        List.of(refused.code(), refused.reason()));
  }

  @Test
  @Timeout(value = 60, unit = TimeUnit.SECONDS)
  void anEndedChatsLogIsErasedOnceItsRetentionPasses() {
    var session = bench.samsChat();
    as(
        "sam",
        () -> {
          bench.host.end(session);
          return null;
        });

    bench.clock.advance(Duration.ofDays(30).minusSeconds(1));
    bench.host.sweep();
    assertTrue(bench.store.find(session).isPresent());
    bench.clock.advance(Duration.ofSeconds(2));
    bench.host.sweep();

    assertTrue(bench.store.find(session).isEmpty());
  }

  /**
   * I8: a restart cut the open turn: it is told so, its open row closed and the request it held
   * withdrawn, since they died with its process, and the next message resumes it by id.
   */
  @Test
  @Timeout(value = 60, unit = TimeUnit.SECONDS)
  void aRestartClosesTheTurnsItCutAndTheNextMessageResumesThem() {
    var session = bench.samsChat();
    bench.send(session, "hi");
    bench.says(INIT, CALLS, ASKS);
    var restarted = bench.restarted();

    var cut = restarted.reclaim();
    var again = bench.restarted().reclaim();
    as("sam", () -> restarted.send(session, "again"));

    assertEquals(1, cut);
    assertEquals(
        List.of(
            "message",
            "session",
            "tool",
            "permission",
            "told",
            "tool_done",
            "withdrawn",
            "interrupted",
            "message"),
        bench.kinds(session));
    var items = bench.items(session);
    assertEquals(ChatHost.RESTARTED, items.get(4).get("words"));
    assertEquals(
        List.of(CALL, ChatLog.UNREAD), List.of(items.get(5).get("id"), items.get(5).get("output")));
    assertEquals(REQUEST, items.get(6).get("id"));
    assertEquals(
        Harnesses.of("claude-code")
            .chat()
            .orElseThrow()
            .argv(new HarnessChat.Launch("default", null, CONVERSATION)),
        bench.processes.starts.getLast().command());
    assertEquals(0, again, "a turn is closed once");
  }

  /** I10: a chat is its opener's alone; any other caller finds no chat at all. */
  @Test
  @Timeout(value = 60, unit = TimeUnit.SECONDS)
  void aChatIsItsOpenersAloneAndAnIdSaysNothingOfAnotherPersonsChat() {
    var session = bench.samsChat();
    var none = "00000000-0000-4000-8000-000000000000";

    var tom = assertThrows(ApiException.class, () -> as("tom", () -> bench.host.read(session, 0)));
    var ada = assertThrows(ApiException.class, () -> as("ada", () -> bench.host.read(session, 0)));
    var absent = assertThrows(ApiException.class, () -> as("sam", () -> bench.host.read(none, 0)));
    var malformed = assertThrows(IllegalArgumentException.class, () -> bench.read("../../etc", 0));

    assertEquals(
        List.of("not_found", "There is no chat " + session + " of yours here."),
        List.of(tom.code(), tom.reason()));
    assertEquals(tom.reason(), ada.reason());
    assertEquals("There is no chat " + none + " of yours here.", absent.reason());
    assertTrue(malformed.getMessage().contains("../../etc"), malformed.getMessage());
  }

  /** Sail stopping ends every process; their chats stay open, to resume. */
  @Test
  @Timeout(value = 60, unit = TimeUnit.SECONDS)
  void sailStoppingEndsEveryProcessAndLeavesItsChatOpen() {
    var session = bench.samsChat();
    bench.send(session, "hi");
    var fake = bench.says(INIT, ENDS);

    bench.host.close();
    fake.settle();

    assertTrue(fake.stdinClosed());
    assertFalse(bench.store.find(session).orElseThrow().ended());
  }

  @Test
  @Timeout(value = 60, unit = TimeUnit.SECONDS)
  void aProcessThatWillNotStartIsRefusedAndItsLoginWithdrawn() {
    var session = bench.samsChat();
    bench.processes.refusing(new IOException("no incus"));

    var refused = assertThrows(ApiException.class, () -> bench.send(session, "hi"));

    assertEquals(
        List.of("agent_launch_failed", "Claude Code did not start in workspace try."),
        List.of(refused.code(), refused.reason()));
    assertFalse(
        bench.store.find(session).orElseThrow().launched(), "nothing started, so none is left");
    assertTrue(
        bench.incus.invocations().stream()
            .anyMatch(
                command ->
                    command.contains(SessionLogin.REMOVE)
                        && command.contains(SessionLogin.DIR + "/")),
        String.valueOf(bench.incus.invocations()));
  }

  @Test
  @Timeout(value = 60, unit = TimeUnit.SECONDS)
  void aProcessThatEndsOnlyWhenSignalledIsInterruptedThenTerminated() {
    var session = bench.samsChat();
    bench.send(session, "hi");
    var fake = bench.says(INIT, ENDS);
    fake.onClose(() -> {});
    fake.onSignal(
        signal -> {
          if (signal == AgentProcess.Signal.TERM) {
            fake.exits(143);
          }
        });

    bench.host.close();

    assertEquals(List.of(AgentProcess.Signal.INT, AgentProcess.Signal.TERM), fake.signals);
  }

  /** I7: a request that cannot be read whole is refused at once, by its id; the turn goes on. */
  @Test
  @Timeout(value = 60, unit = TimeUnit.SECONDS)
  void aRequestThatCannotBeReadWholeIsRefusedAtOnceAndSaidSo() {
    var session = bench.samsChat();
    bench.send(session, "hi");

    var fake = bench.says(INIT, ASKS.replace("\"tool_name\":\"Bash\",", ""));

    assertEquals(
        List.of(
            LiveStream.userMessage("hi"),
            LiveChat.refuseUnreadable(new LiveStream.Step.Unreadable(REQUEST, false))),
        fake.written);
    assertEquals(List.of("message", "session", "unreadable"), bench.kinds(session));
    assertEquals(false, bench.items(session).get(2).get("too_large"));
    assertTrue(bench.store.find(session).orElseThrow().turnOpen(), "its turn goes on");
  }

  /**
   * I7: a request a person could not be shown whole is never held for them: it is refused at once,
   * the agent told to split it, and the person told why.
   */
  @Test
  @Timeout(value = 60, unit = TimeUnit.SECONDS)
  void aRequestTooLargeToShowWholeIsRefusedAtOnceAndNeverHeld() {
    var session = bench.samsChat();
    bench.send(session, "hi");

    var fake = bench.says(INIT, ASKS.replace("date > stamp.txt", "echo " + "a".repeat(70_000)));

    assertEquals(
        LiveChat.refuseUnreadable(new LiveStream.Step.Unreadable(REQUEST, true)),
        fake.written.getLast());
    assertEquals(List.of("message", "session", "unreadable"), bench.kinds(session));
    assertEquals(true, bench.items(session).get(2).get("too_large"));
    bench.clock.advance(Duration.ofMinutes(10));
    bench.host.sweep();
    assertFalse(fake.stdinClosed(), "nothing is held, so no bound ends it");
  }

  /** I7: one naming no request to answer stops the turn, which would otherwise wait on it. */
  @Test
  @Timeout(value = 60, unit = TimeUnit.SECONDS)
  void aRequestThatNamesNoIdToAnswerStopsTheTurn() {
    var session = bench.samsChat();
    bench.send(session, "hi");

    var fake =
        bench.says(INIT, ASKS.replace(REQUEST, "../x").replace("\"tool_name\":\"Bash\",", ""));

    var stop = Recorded.parsed(fake.written.getLast());
    assertEquals(
        List.of("control_request", Map.of("subtype", "interrupt")),
        List.of(stop.get("type"), stop.get("request")));
    assertEquals(
        stop.get("request_id"),
        Ids.requireUuid((String) stop.get("request_id")),
        "its own id, one Claude Code hands back");
    assertEquals(List.of("message", "session", "unreadable"), bench.kinds(session));
  }

  /**
   * I2: a mode the harness said that a launch may not pass on (cut short, or an option) resumes in
   * the chat's first mode.
   */
  @Test
  @Timeout(value = 60, unit = TimeUnit.SECONDS)
  void aModeSaidThatALaunchMayNotPassOnResumesInTheFirstChatMode() {
    var session =
        as("sam", () -> bench.host.open("acme", "try", "claude-code", "acceptEdits", false))
            .session();
    bench.send(session, "hi");
    var fake =
        bench.says(
            INIT.replace("\"permissionMode\":\"default\"", "\"permissionMode\":\"-p\""), ENDS);
    fake.exits(0);
    fake.settle();

    bench.send(session, "again");

    assertEquals(
        Harnesses.of("claude-code")
            .chat()
            .orElseThrow()
            .argv(new HarnessChat.Launch("default", null, CONVERSATION)),
        bench.processes.starts.getLast().command());
  }

  /** I6: a chat ended while its message started a process takes no message, and ends it. */
  @Test
  @Timeout(value = 60, unit = TimeUnit.SECONDS)
  void aChatEndedWhileItsProcessStartsTakesNoMessageAndEndsTheProcess() {
    var session = bench.samsChat();
    bench.processes.onStart(
        fake -> {
          fake.onClose(fake::exitsIfRunning);
          bench.store.ended(session, ChatBench.START);
        });

    var refused = assertThrows(ApiException.class, () -> bench.send(session, "hi"));
    bench.processes.latest().awaitStdinClosed();

    assertEquals(
        List.of("conflict", "This chat has ended, so it takes no message."),
        List.of(refused.code(), refused.reason()));
    assertEquals(List.of(), bench.kinds(session), "nothing of it is logged");
  }

  /** Sail stopping cuts a turn open, saying so; the chat stays open, to resume. */
  @Test
  @Timeout(value = 60, unit = TimeUnit.SECONDS)
  void sailStoppingMidTurnSaysItCutTheTurn() {
    var session = bench.samsChat();
    bench.send(session, "hi");
    var fake = bench.says(INIT);

    bench.host.close();
    fake.settle();

    assertEquals(List.of("message", "session", "told", "interrupted"), bench.kinds(session));
    assertEquals(ChatHost.STOPPED, bench.items(session).get(2).get("words"));
    assertFalse(bench.store.find(session).orElseThrow().turnOpen());
  }

  private static List<Long> seqs(ChatHost.Read read) {
    return read.items().stream().map(item -> ((Number) item.get("seq")).longValue()).toList();
  }

  private static Map<String, Object> longs(Map<String, Object> item) {
    var copy = new java.util.LinkedHashMap<String, Object>();
    item.forEach(
        (key, value) ->
            copy.put(key, value instanceof Integer whole ? Long.valueOf(whole) : value));
    return copy;
  }
}
