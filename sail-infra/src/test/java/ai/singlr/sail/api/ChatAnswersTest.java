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

import ai.singlr.sail.common.Ids;
import ai.singlr.sail.engine.LiveChat;
import ai.singlr.sail.engine.LiveStream;
import ai.singlr.sail.engine.Recorded;
import ai.singlr.sail.store.ChatStore;
import ai.singlr.sail.store.SqliteException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.api.io.TempDir;

/**
 * What a chat's person answers its agent with, stops and switches, through the host's routes: an
 * answer reaches its request exactly once, logged before the agent is told; every other answer
 * (twice, racing, after a withdrawal, a turn's end, a process's end, a stop or a restart) is
 * refused and writes nothing; only the opener reaches a route, none starts a process, and nothing
 * but the person's explicit answer ever allows anything. Each process stands in, fed what Claude
 * Code 2.1.332 wrote (probes 1, 2 and 5).
 */
@Timeout(value = 60, unit = TimeUnit.SECONDS)
class ChatAnswersTest {

  private static final String INIT = line("04b-set-permission-mode-and-background-task", 148);
  private static final String ASKS = line("02b-relay-stdio", 16);
  private static final String REQUEST = "a871f652-0656-42bf-8cc8-860ad0a34b79";
  private static final String ALWAYS = line("02b-relay-stdio", 99);
  private static final String ALWAYS_REQUEST = "d3b09c29-b79d-4151-b490-d70fdf7adb18";
  private static final String TWICE = line("05-answer-twice", 16);
  private static final String TWICE_REQUEST = "bda0b4cf-c4f6-4219-bd84-0f0617292889";
  private static final String CANCELLABLE = line("04a-cancel-request-by-interrupt", 16);
  private static final String CANCELLED = "dc0b76a9-43c6-4484-a47b-1b7c1e5a4b10";
  private static final String CANCELS = line("04a-cancel-request-by-interrupt", 20);
  private static final List<String> STOPS =
      List.of(
          CANCELS,
          line("04a-cancel-request-by-interrupt", 21),
          line("04a-cancel-request-by-interrupt", 23),
          line("04a-cancel-request-by-interrupt", 24));
  private static final String ENDS = line("02b-relay-stdio", 138);
  private static final Map<String, Object> ALLOW = Map.of("decision", "allow");
  private static final String WITHDRAWN =
      "Claude Code no longer waits for this answer, so it was not sent.";

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

  private Map<String, Object> answer(String session, String request, Map<String, Object> body) {
    return as("sam", () -> bench.host.answers().answer(session, request, body)).toMap();
  }

  private Map<String, Object> interrupt(String session) {
    return as("sam", () -> bench.host.answers().interrupt(session)).toMap();
  }

  private Map<String, Object> mode(String session, String mode) {
    return as("sam", () -> bench.host.answers().mode(session, Map.of("mode", mode))).toMap();
  }

  private static ApiException refused(Executable route) {
    return assertThrows(ApiException.class, route);
  }

  private static String invalid(Executable route) {
    return assertThrows(IllegalArgumentException.class, route).getMessage();
  }

  /** Sam's chat, its process having said {@code lines}. */
  private FakeAgentProcess.Fake asked(String session, String... lines) {
    bench.send(session, "hi");
    var said = new ArrayList<String>(List.of(INIT));
    said.addAll(List.of(lines));
    return bench.says(said.toArray(String[]::new));
  }

  /** The answers {@code fake} was written. */
  private static List<Map<String, Object>> responses(FakeAgentProcess.Fake fake) {
    return fake.written.stream()
        .map(Recorded::parsed)
        .filter(line -> "control_response".equals(line.get("type")))
        .toList();
  }

  private Map<String, Object> last(String session) {
    return bench.items(session).getLast();
  }

  /** A1, I2, I3, I6: an allow is logged, then written once, with the request's own input. */
  @Test
  void anAllowIsLoggedThenWrittenWithTheRequestsOwnInput() {
    var session = bench.samsChat();
    var fake = asked(session, ASKS);
    var seen = new CopyOnWriteArrayList<List<String>>();
    fake.onWrite(written -> seen.add(bench.kinds(session)));

    var answered = answer(session, REQUEST, ALLOW);

    assertEquals(List.of(List.of("message", "session", "permission", "answered")), seen);
    assertEquals(List.of(Recorded.parsed("02b-relay-stdio", 17)), responses(fake));
    var item = last(session);
    assertEquals(Map.of("seq", item.get("seq")), answered);
    assertEquals(
        Map.of(
            "seq", 4L,
            "at", "2026-11-27T09:00:00.000Z",
            "type", "answered",
            "request", REQUEST,
            "decision", "allow",
            "by", "sam"),
        item);
    assertEquals(1, bench.processes.starts.size(), "the answer started nothing");
  }

  /** A2, I7: always hands back the harness's own suggestions, and no rule of a client's. */
  @Test
  void alwaysHandsBackTheHarnessesOwnSuggestionsAndTakesNoRuleOfAClients() {
    var session = bench.samsChat();
    var fake = asked(session, ALWAYS);
    var rule =
        Map.<String, Object>of(
            "decision", "allow_always", "rules", List.of(Map.of("toolName", "Bash")));

    var extra = invalid(() -> answer(session, ALWAYS_REQUEST, rule));
    answer(session, ALWAYS_REQUEST, Map.of("decision", "allow_always"));

    assertEquals("An answer names only decision, message and answers.", extra);
    assertEquals(List.of(Recorded.parsed("02b-relay-stdio", 100)), responses(fake));
    assertEquals("allow_always", last(session).get("decision"));
  }

  /** I7: always is taken only when the card offered it; the request stays to be answered. */
  @Test
  void anAlwaysTheCardDidNotOfferIsRefusedAndTheRequestKept() {
    var session = bench.samsChat();
    var fake = asked(session, line("01-always-rule-wrappers", 108));
    var request = (String) Recorded.parsed("01-always-rule-wrappers", 108).get("request_id");

    var refusal = invalid(() -> answer(session, request, Map.of("decision", "allow_always")));
    answer(session, request, ALLOW);

    assertEquals(
        "Claude Code offered no way to allow this always; allow it once instead.", refusal);
    assertEquals(1, responses(fake).size());
    assertEquals("allow", last(session).get("decision"));
  }

  /** A3, I8: a denial's words reach the agent exactly, and come back as the call's result. */
  @Test
  void aDenialsWordsReachTheAgentExactly() {
    var session = bench.samsChat();
    var fake = asked(session, TWICE);

    answer(session, TWICE_REQUEST, Map.of("decision", "deny", "message", "DENIED-tamarind-4417"));
    bench.says(line("05-answer-twice", 21));

    assertEquals(List.of(Recorded.parsed("05-answer-twice", 17)), responses(fake));
    var items = bench.items(session);
    assertEquals("DENIED-tamarind-4417", items.get(3).get("message"));
    assertEquals(List.of("tool_done", "DENIED-tamarind-4417", true), done(items.getLast()));
  }

  private static List<Object> done(Map<String, Object> item) {
    return List.of(item.get("type"), item.get("output"), item.get("failed"));
  }

  /** A4, I2: two answers at once: one reaches the request; the other is refused, saying so. */
  @Test
  void twoAnswersAtOnceReachTheRequestOnce() throws Exception {
    var session = bench.samsChat();
    var fake = asked(session, ASKS);
    var writing = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    fake.onWrite(
        written -> {
          writing.countDown();
          await(release);
        });
    var first = new AtomicReference<Object>();
    var firstAnswer = Thread.ofPlatform().start(() -> first.set(answer(session, REQUEST, ALLOW)));
    assertTrue(writing.await(20, TimeUnit.SECONDS), "the first answer was never written");
    var second = new AtomicReference<Throwable>();

    var racing =
        bench.heldInTheHost(
            "sail-test-second-answer",
            () -> {
              try {
                answer(session, REQUEST, Map.of("decision", "deny"));
              } catch (ApiException refused) {
                second.set(refused);
              }
            });
    release.countDown();
    firstAnswer.join(Duration.ofSeconds(20));
    racing.join(Duration.ofSeconds(20));

    assertEquals(Map.of("seq", 4L), first.get());
    var refusal = (ApiException) second.get();
    assertEquals(
        List.of("request_answered", "You answered this at 09:00:00 UTC: allow."),
        List.of(refusal.code(), refusal.reason()));
    assertEquals(1, responses(fake).size());
    assertEquals(List.of("message", "session", "permission", "answered"), bench.kinds(session));
  }

  /** I2: a second answer while the first is still being logged is refused; one reaches it. */
  @Test
  void twoAnswersAtOnceWhileTheFirstIsLoggedReachTheRequestOnce() throws Exception {
    var session = bench.samsChat();
    var fake = asked(session, ASKS);
    var logging = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    var inTheLog = new AtomicReference<Boolean>();
    bench.clock.onRead(
        1,
        () -> {
          inTheLog.set(
              StackWalker.getInstance()
                  .walk(
                      frames ->
                          frames.anyMatch(
                              frame ->
                                  frame.getClassName().equals(ChatLog.class.getName())
                                      && frame.getMethodName().equals("log"))));
          logging.countDown();
          await(release);
        });
    var first = new AtomicReference<Object>();
    var firstAnswer = Thread.ofPlatform().start(() -> first.set(answer(session, REQUEST, ALLOW)));
    assertTrue(logging.await(20, TimeUnit.SECONDS), "the first answer was never logged");
    var second = new AtomicReference<Throwable>();

    var racing =
        bench.heldInTheHost(
            "sail-test-second-answer",
            () -> {
              try {
                answer(session, REQUEST, Map.of("decision", "deny"));
              } catch (ApiException refused) {
                second.set(refused);
              }
            });
    var whileLogged = bench.kinds(session);
    release.countDown();
    firstAnswer.join(Duration.ofSeconds(20));
    racing.join(Duration.ofSeconds(20));

    assertEquals(true, inTheLog.get(), "the first answer was held in its log write");
    assertEquals(List.of("message", "session", "permission"), whileLogged);
    assertEquals(Map.of("seq", 4L), first.get());
    assertEquals("request_answered", ((ApiException) second.get()).code());
    assertEquals(List.of(Recorded.parsed("02b-relay-stdio", 17)), responses(fake));
    assertEquals(List.of("message", "session", "permission", "answered"), bench.kinds(session));
  }

  /** I3: an answer whose logging fails is neither logged nor sent, and its retry reaches it. */
  @Test
  void anAnswerWhoseLogWriteFailsIsKeptSoItsRetryReachesTheAgentOnce() {
    var session = bench.samsChat();
    var fake = asked(session, ASKS);
    bench.db.execute(
        "CREATE TRIGGER no_answer BEFORE INSERT ON chat_items WHEN NEW.kind = 'answered'"
            + " BEGIN SELECT RAISE(ABORT, 'database is locked'); END");

    assertThrows(SqliteException.class, () -> answer(session, REQUEST, ALLOW));
    var failed = List.copyOf(responses(fake));
    var logged = bench.kinds(session);
    bench.db.execute("DROP TRIGGER no_answer");
    var retried = answer(session, REQUEST, ALLOW);

    assertEquals(List.of(), failed);
    assertEquals(List.of("message", "session", "permission"), logged);
    assertEquals(Map.of("seq", 4L), retried);
    assertEquals(List.of(Recorded.parsed("02b-relay-stdio", 17)), responses(fake));
    assertEquals(List.of("message", "session", "permission", "answered"), bench.kinds(session));
  }

  /**
   * I2, I3: once a refused write began its process's end, the other request it holds takes no
   * answer, though the process is not yet gone: one answer is logged, and the other withdrawn.
   */
  @Test
  void anAnswerWhileItsProcessEndsIsRefusedAndNeverLogged() {
    var session = bench.samsChat();
    var fake = asked(session, ASKS, ALWAYS);
    fake.holdsItsEnd();
    fake.breaksStdin();

    var first = refused(() -> answer(session, REQUEST, ALLOW));
    var second = refused(() -> answer(session, ALWAYS_REQUEST, ALLOW));
    var logged = bench.kinds(session);
    fake.releasesItsEnd();
    over(fake);

    assertEquals(
        List.of("request_withdrawn", "request_withdrawn", WITHDRAWN),
        List.of(first.code(), second.code(), second.reason()));
    assertEquals(List.of("message", "session", "permission", "permission", "answered"), logged);
    assertEquals(
        List.of(
            "message",
            "session",
            "permission",
            "permission",
            "answered",
            "told",
            "withdrawn",
            "interrupted"),
        bench.kinds(session));
    assertEquals(List.of(), responses(fake));
  }

  /** A request no longer waited for is said in the name of the chat's own harness. */
  @Test
  void aRequestNoLongerWaitedForIsSaidInItsHarnessesName() {
    var session = Ids.newId().toString();
    var at = bench.clock.instant();
    bench.store.open(
        new ChatStore.Chat(
            session,
            bench.samId,
            "beta",
            "dock",
            "codex",
            "default",
            false,
            null,
            false,
            false,
            0,
            at,
            null));
    bench.store.append(session, "permission", "{\"id\":\"" + REQUEST + "\"}", at);

    var refusal = refused(() -> answer(session, REQUEST, ALLOW));

    assertEquals(
        List.of(
            "request_withdrawn", "Codex CLI no longer waits for this answer, so it was not sent."),
        List.of(refusal.code(), refusal.reason()));
  }

  private static void await(CountDownLatch latch) {
    try {
      if (!latch.await(20, TimeUnit.SECONDS)) {
        throw new AssertionError("the write was never released");
      }
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new AssertionError(interrupted);
    }
  }

  /** I2, I3: the harness withdrew the request: an answer is refused, and nothing is written. */
  @Test
  void anAnswerAfterTheHarnessWithdrewItIsRefusedAndNeverWritten() {
    var session = bench.samsChat();
    var fake = asked(session, CANCELLABLE, CANCELS);

    var refusal = refused(() -> answer(session, CANCELLED, ALLOW));

    assertEquals(
        List.of("request_withdrawn", WITHDRAWN, "Answer what it asks next, or send a message."),
        List.of(refusal.code(), refusal.reason(), refusal.remedy().plain()));
    assertEquals(List.of(), responses(fake));
  }

  /** I3: an answer that beat the harness's withdrawal is never also withdrawn. */
  @Test
  void anAnswerThatBeatsTheWithdrawalIsNeverAlsoWithdrawn() {
    var session = bench.samsChat();
    asked(session, CANCELLABLE);

    answer(session, CANCELLED, ALLOW);
    bench.says(CANCELS, ENDS);

    assertEquals(
        List.of("message", "session", "permission", "answered", "turn_ended"),
        bench.kinds(session));
  }

  /** I2, I5: once the turn ended, the request is withdrawn, and no answer reaches it. */
  @Test
  void anAnswerAfterTheTurnEndedIsRefused() {
    var session = bench.samsChat();
    var fake = asked(session, ASKS, ENDS);

    var refusal = refused(() -> answer(session, REQUEST, ALLOW));

    assertEquals("request_withdrawn", refusal.code());
    assertEquals(List.of(), responses(fake));
    assertEquals(
        List.of("message", "session", "permission", "withdrawn", "turn_ended"),
        bench.kinds(session));
  }

  /** I2, I5: a request dies with its process: it is withdrawn, and no answer reaches it. */
  @Test
  void anAnswerAfterTheProcessEndedIsRefusedAndStartsNothing() {
    var session = bench.samsChat();
    var fake = asked(session, ASKS);
    fake.exits(1);
    over(fake);

    var refusal = refused(() -> answer(session, REQUEST, ALLOW));

    assertEquals("request_withdrawn", refusal.code());
    assertEquals(List.of(), responses(fake));
    assertEquals(1, bench.processes.starts.size());
    assertEquals(
        List.of("message", "session", "permission", "told", "withdrawn", "interrupted"),
        bench.kinds(session));
  }

  /** A5, I2: once the person stopped the turn, its request takes no answer. */
  @Test
  void anAnswerAfterAStopIsRefused() {
    var session = bench.samsChat();
    var fake = asked(session, ASKS);

    interrupt(session);
    var refusal = refused(() -> answer(session, REQUEST, ALLOW));

    assertEquals("request_withdrawn", refusal.code());
    assertEquals(List.of(), responses(fake));
  }

  /** A6, I5: a restart withdraws what was pending; nothing is allowed and nothing starts. */
  @Test
  void anAnswerAfterARestartIsRefusedAndNothingIsAllowed() {
    var session = bench.samsChat();
    var old = asked(session, ASKS);
    var restarted = bench.restarted();
    restarted.reclaim();

    var refusal =
        refused(() -> as("sam", () -> restarted.answers().answer(session, REQUEST, ALLOW)).toMap());

    assertEquals("request_withdrawn", refusal.code());
    assertEquals(List.of(), responses(old));
    assertEquals(1, bench.processes.starts.size(), "the answer started nothing");
    assertEquals(
        List.of("message", "session", "permission", "told", "withdrawn", "interrupted"),
        bench.kinds(session));
  }

  /** I3: an answer the process does not take is said, and its turn cut, never withdrawn. */
  @Test
  void anAnswerTheProcessDoesNotTakeIsSaidAndItsTurnCut() {
    var session = bench.samsChat();
    var fake = asked(session, ASKS);
    fake.breaksStdin();

    var refusal = refused(() -> answer(session, REQUEST, ALLOW));
    over(fake);

    assertEquals("request_withdrawn", refusal.code());
    assertEquals(
        List.of("message", "session", "permission", "answered", "told", "interrupted"),
        bench.kinds(session));
    assertEquals(
        "Claude Code did not get your answer: its process stopped taking what Sail wrote to it,"
            + " so Sail ended it; your next message starts it again.",
        bench.items(session).get(4).get("words"));
  }

  /** I6: a request never asked, or one not shown whole, is no request to answer. */
  @Test
  void aRequestNoneAskedOrNotShownWholeIsNotFound() {
    var session = bench.samsChat();
    var tooLarge =
        "{\"type\":\"control_request\",\"request_id\":\"big-1\",\"request\":{\"subtype\":"
            + "\"can_use_tool\",\"tool_name\":\"Bash\",\"display_name\":\"Bash\",\"input\":"
            + "{\"command\":\""
            + "a".repeat(LiveStream.CONTENT_CODE_POINTS + 1)
            + "\"},\"tool_use_id\":\"toolu_big\"}}";
    var fake = asked(session, tooLarge);

    var invented = refused(() -> answer(session, "nope", ALLOW));
    var unreadable = refused(() -> answer(session, "big-1", ALLOW));

    assertEquals(
        List.of("not_found", "There is no request nope in this chat."),
        List.of(invented.code(), invented.reason()));
    assertEquals(
        List.of("not_found", "There is no request big-1 in this chat."),
        List.of(unreadable.code(), unreadable.reason()));
    assertEquals(
        List.of(
            Recorded.parsed(
                LiveChat.refuseUnreadable(new LiveStream.Step.Unreadable("big-1", true)))),
        responses(fake),
        "only the refusal Sail wrote at once");
  }

  /** I4: only the chat's opener reaches its routes; anyone else is told there is no such chat. */
  @Test
  void onlyTheOpenerReachesTheRoutes() {
    var session = bench.samsChat();
    var fake = asked(session, ASKS);
    var none = "There is no chat " + session + " of yours here.";

    var routes =
        List.<Executable>of(
            () -> as("tom", () -> bench.host.answers().answer(session, REQUEST, ALLOW)),
            () -> as("tom", () -> bench.host.answers().interrupt(session)),
            () -> as("tom", () -> bench.host.answers().mode(session, Map.of("mode", "plan"))));

    for (var route : routes) {
      var refusal = refused(route);
      assertEquals(List.of("not_found", none), List.of(refusal.code(), refusal.reason()));
    }
    assertEquals(1, fake.written.size(), "nothing was written for tom");
  }

  /** I13: a body is only what its route names, of its shape; nothing else is written. */
  @Test
  void aBodyOfAnotherShapeOrAnUnsafeIdIsRefusedAndNothingWritten() {
    var session = bench.samsChat();
    var fake = asked(session, ASKS);

    assertEquals(
        "A request's id is the one its item in the chat names.",
        invalid(() -> answer(session, "a b", ALLOW)));
    assertEquals(
        "decision is allow, allow_always, deny or answer.",
        invalid(() -> answer(session, REQUEST, Map.of("decision", "bypass"))));
    assertEquals(
        "decision is allow, allow_always, deny or answer.",
        invalid(() -> answer(session, REQUEST, Map.of())));
    assertEquals(
        "message is the person's own words, as text.",
        invalid(() -> answer(session, REQUEST, Map.of("decision", "deny", "message", 7))));
    assertEquals(
        "answers name each question by its text, each with what was chosen or said.",
        invalid(() -> answer(session, REQUEST, Map.of("decision", "answer", "answers", "Blue"))));
    assertEquals(
        "An answer to a question names only chosen, its labels, and own, the person's words.",
        invalid(
            () ->
                answer(
                    session,
                    REQUEST,
                    Map.of("decision", "answer", "answers", Map.of("Q", Map.of("label", "A"))))));
    assertEquals(
        "Each label chosen is text, as its choice names it.",
        invalid(
            () ->
                answer(
                    session,
                    REQUEST,
                    Map.of(
                        "decision",
                        "answer",
                        "answers",
                        Map.of("Q", Map.of("chosen", List.of(1)))))));
    assertEquals(
        "Claude Code asks to make this call: allow it, allow it always, or deny it.",
        invalid(
            () ->
                answer(
                    session,
                    REQUEST,
                    Map.of("decision", "answer", "answers", Map.of("Q", Map.of("own", "teal"))))));
    assertEquals(
        "A change of mode names only its mode.",
        invalid(
            () ->
                as(
                    "sam",
                    () ->
                        bench
                            .host
                            .answers()
                            .mode(session, Map.of("mode", "plan", "rules", List.of())))));
    assertEquals(
        "mode is the name of one of the chat's modes.",
        invalid(() -> as("sam", () -> bench.host.answers().mode(session, Map.of("mode", 1)))));
    assertEquals(1, fake.written.size());
    answer(session, REQUEST, ALLOW);
    assertEquals(1, responses(fake).size(), "the request was kept for the person's answer");
  }

  /** An ended chat takes no answer and no change of mode. */
  @Test
  void anEndedChatTakesNoAnswerAndNoChangeOfMode() {
    var session = bench.samsChat();
    var fake = asked(session, ASKS);
    as(
        "sam",
        () -> {
          bench.host.end(session);
          return null;
        });

    var answered = refused(() -> answer(session, REQUEST, ALLOW));
    var moded = refused(() -> mode(session, "plan"));

    assertEquals(
        List.of("conflict", "This chat has ended, so it takes no answer."),
        List.of(answered.code(), answered.reason()));
    assertEquals(
        List.of("conflict", "This chat has ended, so it takes no change of mode."),
        List.of(moded.code(), moded.reason()));
    assertEquals(List.of(), responses(fake));
  }

  /** A8, A9, I10: a stop is written once while in flight; a chat with no turn has none. */
  @Test
  void aStopIsWrittenOnceAndAChatWithNoTurnHasNoneToStop() {
    var session = bench.samsChat();

    var idle = interrupt(session);
    var fake = asked(session, CANCELLABLE);
    var stopping = interrupt(session);
    var again = interrupt(session);
    bench.says(STOPS.toArray(String[]::new));
    var stopped = interrupt(session);

    assertEquals(
        List.of(false, true, true, false),
        List.of(idle, stopping, again, stopped).stream().map(m -> m.get("stopping")).toList());
    assertEquals(
        1,
        fake.written.stream().filter(written -> written.contains("\"interrupt\"")).count(),
        "one interrupt");
    assertEquals(1, bench.processes.starts.size(), "the idle stop started nothing");
    assertEquals(
        List.of("message", "session", "permission", "withdrawn", "interrupted", "turn_ended"),
        bench.kinds(session));
  }

  /** A10, I11: between turns the mode is logged, then asked of the harness, which says it. */
  @Test
  void aModeIsSetBetweenTurnsAndSaidByTheHarness() {
    var session = bench.samsChat();
    var fake = asked(session, ENDS);

    var set = mode(session, "plan");
    var written = Recorded.parsed(fake.written.getLast());
    var logged = last(session);
    bench.says(line("01-mode-plan-between-turns", 15), line("01-mode-plan-between-turns", 16));

    assertEquals(Map.of("seq", logged.get("seq")), set);
    assertEquals(
        List.of("mode_set", "plan", "sam"),
        List.of(logged.get("type"), logged.get("mode"), logged.get("by")));
    assertEquals(
        Recorded.parsed(LiveChat.setMode((String) logged.get("request"), "plan")), written);
    assertEquals("mode_changed", last(session).get("type"));
    assertEquals("plan", bench.store.find(session).orElseThrow().mode());
  }

  /** A12, I11: no change of mode mid-turn, and none to a mode a chat does not take. */
  @Test
  void aModeIsNeverChangedMidTurnNorToOneAChatDoesNotTake() {
    var session = bench.samsChat();
    var fake = asked(session, ASKS);

    var midTurn = refused(() -> mode(session, "acceptEdits"));
    var bypass = invalid(() -> mode(session, "bypassPermissions"));

    assertEquals(
        List.of(
            "turn_open",
            "Claude Code is working on a turn; change its mode when the turn ends, or stop it"
                + " first."),
        List.of(midTurn.code(), midTurn.reason()));
    assertEquals(
        "Claude Code takes no mode 'bypassPermissions' in a workspace; it takes default,"
            + " acceptEdits, plan.",
        bypass);
    assertEquals(1, fake.written.size());
    assertEquals("default", bench.store.find(session).orElseThrow().mode());
  }

  /** A11, I11, I12: with no process, the mode is kept, and the next message launches in it. */
  @Test
  void aModeSetWithNoProcessIsTheOneTheNextLaunchesIn() {
    var session = bench.samsChat();

    var set = mode(session, "acceptEdits");
    var starts = bench.processes.starts.size();
    bench.send(session, "hi");

    var logged = bench.items(session).getFirst();
    assertEquals(0, starts, "the change of mode started nothing");
    assertEquals(Map.of("seq", 1L), set);
    assertEquals(
        List.of("mode_set", "acceptEdits", "sam"),
        List.of(logged.get("type"), logged.get("mode"), logged.get("by")));
    assertFalse(logged.containsKey("request"));
    var argv = bench.processes.starts.getFirst().command();
    assertEquals(
        List.of("--permission-mode", "acceptEdits"),
        argv.subList(argv.indexOf("--permission-mode"), argv.indexOf("--permission-mode") + 2));
  }

  /** I11: a change of mode its process did not take is kept for the next process. */
  @Test
  void aModeItsProcessDidNotTakeIsKeptForTheNext() {
    var session = bench.samsChat();
    var fake = asked(session, ENDS);
    fake.breaksStdin();

    mode(session, "plan");
    over(fake);

    assertEquals("plan", bench.store.find(session).orElseThrow().mode());
  }

  /** I11: a process that is ending no longer sets the mode in force; the person's choice stays. */
  @Test
  void anEndingProcessNoLongerSetsTheModeInForce() {
    var session = bench.samsChat();
    var fake = asked(session, ENDS);
    fake.onClose(() -> {});
    bench.clock.advance(Duration.ofDays(1));
    bench.host.sweep();
    fake.awaitStdinClosed();

    mode(session, "plan");
    bench.says(line("01-mode-plan-between-turns", 42));
    fake.exits(0);
    over(fake);

    assertEquals("plan", bench.store.find(session).orElseThrow().mode());
    assertEquals(1, fake.written.size(), "nothing was written to a process that is ending");
  }
}
