/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.api;

import static ai.singlr.sail.api.ChatBench.as;
import static ai.singlr.sail.api.ChatBench.line;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import ai.singlr.sail.engine.ChatWire;
import ai.singlr.sail.engine.SessionLogin;
import ai.singlr.sail.identity.Actor;
import ai.singlr.sail.identity.Role;
import ai.singlr.sail.pty.PtyMessage;
import ai.singlr.sail.store.ChatStore;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/**
 * A workspace's chats as its sessions listing says them: each state read from the chat's log alone,
 * as Claude Code 2.1.332's recorded lines drive it through a stand-in process, with no process
 * asked or started by the listing; only the caller's own, an admin's too; open ones and those ended
 * within ten minutes, newest first, at most 200.
 */
class ChatListingTest {

  private static final String INIT = line("04b-set-permission-mode-and-background-task", 148);
  private static final String ASKS = line("02b-relay-stdio", 16);
  private static final String ENDS = line("02b-relay-stdio", 138);
  private static final String ASKS_THEN_STOPPED = line("04a-cancel-request-by-interrupt", 16);
  private static final String WITHDRAWN = line("04a-cancel-request-by-interrupt", 20);
  private static final List<String> STOPPED =
      List.of(
          line("04a-cancel-request-by-interrupt", 21),
          line("04a-cancel-request-by-interrupt", 23),
          line("04a-cancel-request-by-interrupt", 24));
  private static final String KEY_REFUSED_SAYS = line("06a-invalid-key", 5);
  private static final String KEY_REFUSED = line("06a-invalid-key", 6);

  @TempDir Path dir;
  private ChatBench bench;
  private ChatTiming timing;
  private ChatListing listing;

  @BeforeEach
  void setUp() {
    bench = new ChatBench(dir);
    timing =
        new ChatTiming(
            bench.clock::instant,
            window -> {},
            (arrivals, within) -> {},
            Duration.ZERO,
            Duration.ZERO,
            ProcessEnd.Grace.DEFAULT);
    listing = new ChatListing(bench.store, bench.fdes, timing);
  }

  @AfterEach
  void tearDown() {
    bench.close();
  }

  /** L1-L5: each state as the log says it, read with nothing asked of a process. */
  @Test
  @Timeout(value = 60, unit = TimeUnit.SECONDS)
  void eachStateIsReadFromTheLogAloneAsTheRecordedLinesDriveIt() {
    var session = bench.samsChat();
    assertEquals("idle", stateOf(session), "no turn yet");

    bench.send(session, "hi");
    bench.says(INIT);
    assertEquals("working", stateOf(session), "a turn is open");

    bench.says(ASKS);
    assertEquals("needs_you", stateOf(session), "a request nobody answered");

    bench.says(ENDS);
    assertEquals("your_turn", stateOf(session), "the turn ended, its process still held");

    bench.send(session, "again");
    bench.says(INIT, KEY_REFUSED_SAYS, KEY_REFUSED);
    assertEquals("error", stateOf(session), "the turn failed: the key was refused");

    bench.send(session, "and again");
    bench.says(INIT, ENDS);
    assertEquals("your_turn", stateOf(session), "a failed turn is behind a turn that ended");
  }

  /** L3: a request the harness withdrew leaves the turn working; a stopped turn is your turn. */
  @Test
  @Timeout(value = 60, unit = TimeUnit.SECONDS)
  void aWithdrawnRequestNeedsNoOneAndAStoppedTurnIsYourTurn() {
    var session = bench.samsChat();
    bench.send(session, "hi");
    bench.says(INIT, ASKS_THEN_STOPPED);
    assertEquals("needs_you", stateOf(session));

    bench.says(WITHDRAWN);
    assertEquals("working", stateOf(session), "only an open request needs the person");

    bench.says(STOPPED.toArray(String[]::new));
    assertEquals("your_turn", stateOf(session), "stopped by a person, the turn is theirs");
  }

  /**
   * A request the person answered needs them no more: the listing reads it working from the moment
   * {@code answered} is logged, before Claude Code says anything further.
   */
  @Test
  @Timeout(value = 60, unit = TimeUnit.SECONDS)
  void anAnsweredRequestNeedsThePersonNoMore() {
    var session = bench.samsChat();
    bench.send(session, "hi");
    bench.says(INIT, ASKS);
    assertEquals("needs_you", stateOf(session));

    as(
        "sam",
        () ->
            bench
                .host
                .answers()
                .answer(
                    session, "a871f652-0656-42bf-8cc8-860ad0a34b79", Map.of("decision", "allow")));

    assertEquals("working", stateOf(session), "answered, the turn goes on without the person");
  }

  /** C12: whether a process holds the chat is never a state: an idle end and a cut are not seen. */
  @Test
  @Timeout(value = 60, unit = TimeUnit.SECONDS)
  void anIdleEndLeavesTheStateAndACutTurnIsYourTurn() {
    var idle = bench.samsChat();
    bench.send(idle, "hi");
    var fake = bench.says(INIT, ENDS);
    bench.clock.advance(Duration.ofMinutes(20));
    bench.host.sweep();
    ChatBench.over(fake);
    withdrawn(1);
    assertFalse(bench.store.find(idle).orElseThrow().launched(), "no process holds it");
    assertEquals("your_turn", stateOf(idle));

    var cut = bench.samsChat();
    bench.send(cut, "hi");
    bench.says(INIT, ASKS);
    bench.host.close();
    withdrawn(2);
    assertEquals(List.of("your_turn", "your_turn"), List.of(stateOf(idle), stateOf(cut)));
  }

  /** L6: ended, and not live, for ten minutes after its end; then unlisted, still read by id. */
  @Test
  @Timeout(value = 60, unit = TimeUnit.SECONDS)
  void anEndedChatIsListedForTenMinutesThenOnlyReadByItsId() {
    var session = bench.samsChat();
    bench.send(session, "hi");
    bench.says(INIT, ENDS);
    as(
        "sam",
        () -> {
          bench.host.end(session);
          return null;
        });

    bench.clock.advance(ChatListing.ENDED_KEPT);
    var row = only(listed("sam", "try"));
    assertEquals(List.of("ended", false), List.of(row.get("state"), row.get("live")));

    bench.clock.advance(Duration.ofMillis(1));
    assertEquals(List.of(), listed("sam", "try"));
    assertFalse(bench.read(session, 0).items().isEmpty(), "its log is still read by its id");
  }

  /**
   * L7, I2: only the caller's own chats in that workspace of that project, never another person's,
   * an admin's view included.
   */
  @Test
  @Timeout(value = 60, unit = TimeUnit.SECONDS)
  void aChatIsListedToItsOpenerAloneInItsOwnWorkspace() {
    var sams = bench.samsChat();
    var toms =
        as("tom", () -> bench.host.open("acme", "tom", "claude-code", "default", true)).session();
    var tomId = bench.fdes.byHandle("tom").orElseThrow().id();
    var at = Instant.parse("2026-11-27T09:00:00Z");
    bench.store.open(chat("toms-in-try", "acme", tomId, "try", at));
    bench.store.open(chat("sams-elsewhere", "acme", bench.samId, "pier", at));
    bench.store.open(chat("sams-in-beta", "beta", bench.samId, "try", at));

    assertEquals(List.of(sams), names(listed("sam", "try")));
    assertEquals(List.of(toms), names(listed("tom", "tom")));
    assertEquals(List.of("toms-in-try"), names(listed("tom", "try")));
    assertEquals(List.of(), listed("ada", "try"), "an admin lists none of another's chats");
    assertEquals(List.of(), listed("sam", "tom"));
    assertEquals(
        List.of("sams-in-beta"),
        as("sam", () -> listing.of("beta", "try")).stream().map(ChatListing.Row::name).toList(),
        "a workspace of the same name in another project lists its own chats alone");
    for (var lane : List.of(Actor.Lane.AGENT, Actor.Lane.CONTAINER)) {
      assertEquals(
          List.of(),
          Actor.call(new Actor("sam", Role.MEMBER, lane), () -> listing.of("acme", "try")),
          "a credential that acts for no person lists no chat: " + lane);
    }

    bench.store.eraseWorkspace("acme", "try");
    assertEquals(List.of(), listed("sam", "try"), "an erased chat is gone");
  }

  /** I3: newest first, then by id, at most 200; an older one is still read by its id. */
  @Test
  @Timeout(value = 60, unit = TimeUnit.SECONDS)
  void atMostTwoHundredAreListedNewestFirstThenById() {
    var at = Instant.parse("2026-11-27T08:00:00Z");
    var opened = new ArrayList<String>();
    for (var i = 0; i < ChatListing.MOST + 1; i++) {
      var id = "chat-%03d".formatted(i);
      bench.store.open(chat(id, "acme", bench.samId, "try", at.plusSeconds(i)));
      opened.add(id);
    }
    bench.store.open(
        chat("chat-tie", "acme", bench.samId, "try", at.plusSeconds(ChatListing.MOST)));

    var names = names(listed("sam", "try"));

    var expected = new ArrayList<>(List.of("chat-200", "chat-tie"));
    expected.addAll(opened.reversed().subList(1, ChatListing.MOST - 1));
    assertEquals(expected, names);
    assertEquals(ChatListing.MOST, names.size());
    assertEquals(
        "chat-000",
        bench.store.find("chat-000").orElseThrow().id(),
        "the oldest is past the listing, not gone");
  }

  /** I1: terminals and chats are one list, newest first, then by name, whatever the form. */
  @Test
  @Timeout(value = 60, unit = TimeUnit.SECONDS)
  void terminalsAndChatsAreOneListNewestFirstThenByName() {
    var session = bench.samsChat();
    var at = bench.store.find(session).orElseThrow().createdAt();

    var listed =
        new WorkspaceSessionsResponse(
                List.of(
                    terminal("app-older", at.minusSeconds(60)),
                    terminal("zz-tied", at),
                    terminal("00-tied", at)),
                as("sam", () -> listing.of("acme", "try")))
            .toMap();

    assertEquals(
        List.of("00-tied", session, "zz-tied", "app-older"),
        ((List<?>) listed.get("sessions"))
            .stream().map(row -> ((Map<?, ?>) row).get("name")).toList());
  }

  /** The row a client reads: its id as its name, its form, state, opener, agent and start. */
  @Test
  @Timeout(value = 60, unit = TimeUnit.SECONDS)
  void aRowSaysItsChatAsTheAppReadsIt() {
    var session = bench.samsChat();

    assertEquals(
        Map.of(
            "name",
            session,
            "form",
            "chat",
            "live",
            true,
            "state",
            "idle",
            "opener",
            "sam",
            "agent",
            Map.of(
                "harness",
                "claude-code",
                "display",
                "Claude Code",
                "mode",
                "default",
                "login",
                "stored"),
            "created_at",
            "2026-11-27T09:00:00.000Z"),
        only(listed("sam", "try")));
  }

  /**
   * T1, T2: a chat is titled by the first line of the person's first message, and by nothing after
   * it; one with no message yet has no title.
   */
  @Test
  @Timeout(value = 60, unit = TimeUnit.SECONDS)
  void aChatIsTitledByTheFirstLineOfThePersonsFirstMessageAlone() {
    var session = bench.samsChat();
    assertFalse(only(listed("sam", "try")).containsKey("title"), "no message yet, no title");

    bench.send(session, "Fix the upload limit wording\nIt says 10 MB; the limit is 25 MB.");
    assertEquals("Fix the upload limit wording", only(listed("sam", "try")).get("title"));

    bench.says(INIT, ENDS);
    bench.send(session, "And the error page too");
    assertEquals(
        "Fix the upload limit wording",
        only(listed("sam", "try")).get("title"),
        "a later message is no title");
  }

  /** T3: a first message that waited for its turn titles its chat as one handed at once does. */
  @Test
  @Timeout(value = 60, unit = TimeUnit.SECONDS)
  void aQueuedFirstMessageTitlesItsChat() {
    assertEquals("Tidy the welcome page copy", titleOf("Tidy the welcome page copy", true));
  }

  /** T4: one line is the title as it was said. */
  @Test
  @Timeout(value = 60, unit = TimeUnit.SECONDS)
  void oneLineIsItsTitleWhole() {
    assertEquals("Why does PierTest time out on CI?", titleOf("Why does PierTest time out on CI?"));
  }

  /** T5: of many lines, only the first is the title, whichever line break ends it. */
  @Test
  @Timeout(value = 60, unit = TimeUnit.SECONDS)
  void ofManyLinesOnlyTheFirstIsTheTitle() {
    for (var end : List.of("\n", "\r\n", "\r", "\u2028", "\u2029", "\u0085", "\u000B", "\f")) {
      assertEquals(
          "Add the new berths", titleOf("Add the new berths" + end + "at pier 4\nand more"), end);
    }
  }

  /**
   * T6: a first line past {@link ChatListing#TITLE_MOST} code points is cut on the last whole word
   * that fits, or at the bound in code points when one word is longer, never between the two halves
   * of a surrogate pair.
   */
  @Test
  @Timeout(value = 60, unit = TimeUnit.SECONDS)
  void aLongFirstLineIsCutOnAWholeWordWithinTheBound() {
    var eighty = "word ".repeat(15) + "a".repeat(5);
    assertEquals(80, eighty.codePointCount(0, eighty.length()));
    assertEquals(eighty, titleOf(eighty), "eighty code points are whole");
    assertEquals(eighty, titleOf(eighty + " more"), "a word ending at the bound is whole");
    assertEquals(
        "word ".repeat(15).strip(), titleOf(eighty + "b more"), "a word past the bound goes");
    var clef = "\uD834\uDD1E";
    assertEquals(
        clef.repeat(ChatListing.TITLE_MOST),
        titleOf(clef.repeat(ChatListing.TITLE_MOST + 20)),
        "one long word is cut at eighty code points, each pair whole");
    assertEquals(
        "a".repeat(79) + "\uD83D\uDE00",
        titleOf("a".repeat(79) + "\uD83D\uDE00" + "b".repeat(10)),
        "a pair at the bound is kept whole");
  }

  /** T7: a first message of only whitespace or control characters has no title. */
  @Test
  @Timeout(value = 60, unit = TimeUnit.SECONDS)
  void onlyWhitespaceOrControlCharactersIsNoTitle() {
    for (var said :
        List.of(
            " \t\n\r\n\u00A0\u2003 ",
            "\u0007\u001B\u0000",
            "\u0085\u009B ",
            "\u200B\u2060\uFEFF\u200D",
            "\u202E\u2066\u2069")) {
      assertFalse(
          only(listed(said)).containsKey("title"),
          () -> said.codePoints().mapToObj(Integer::toHexString).toList().toString());
    }
  }

  /** T10: a line of only invisible characters is no line with words: the next one is the title. */
  @Test
  @Timeout(value = 60, unit = TimeUnit.SECONDS)
  void aLineOfOnlyInvisibleCharactersIsPassedOver() {
    assertEquals("Fix the bug", titleOf("\u200B\nFix the bug"));
    assertEquals("Fix the bug", titleOf("\uFEFF\u200D\u2060\nFix the bug"));
  }

  /**
   * T11: no bidi embedding, override or isolate reaches a title, so none can turn what the app
   * writes after it, balanced or left open, and no cut at the bound leaves one open; the marks and
   * joiners that make words and emoji are kept.
   */
  @Test
  @Timeout(value = 60, unit = TimeUnit.SECONDS)
  void noBidiEmbeddingOverrideOrIsolateReachesATitle() {
    assertEquals("upload limit", titleOf("\u202Eupload limit"));
    assertEquals("upload limit", titleOf("\u202Eupload limit\u202C"));
    assertEquals(
        "a b c d e f g h i j",
        titleOf("a\u202Ab\u202Bc\u202Cd\u202De\u2066f\u2067g\u2068h\u2069i j"));
    assertEquals(
        "word ".repeat(16).strip(),
        titleOf("\u2067" + "word ".repeat(20) + "\u2069"),
        "an isolate the cut would leave open");
    var kept =
        "\uD83D\uDC69\u200D\uD83D\uDC67 \u2764\uFE0F e\u200Cf \u200Fok \u200Eok"
            + " \uD83C\uDFF4\uDB40\uDC67\uDB40\uDC62\uDB40\uDC73\uDB40\uDC63\uDB40\uDC74\uDB40\uDC7F";
    assertEquals(kept, titleOf(kept), "joiners, selectors, marks and tags are kept");
  }

  /**
   * T12: one word past the bound is cut on the last whole grapheme within it, never inside a family
   * joined by ZWJ or a flag; a single grapheme past the bound is cut at the bound, never to
   * nothing.
   */
  @Test
  @Timeout(value = 60, unit = TimeUnit.SECONDS)
  void oneLongWordIsCutOnAWholeGrapheme() {
    var family = "\uD83D\uDC69\u200D\uD83D\uDC69\u200D\uD83D\uDC67\u200D\uD83D\uDC66";
    assertEquals(7, family.codePointCount(0, family.length()));
    assertEquals("x" + family.repeat(11), titleOf("x" + family.repeat(12)), "a family whole");
    var flag = "\uD83C\uDDFA\uD83C\uDDF8";
    assertEquals("x" + flag.repeat(39), titleOf("x" + flag.repeat(40)), "a flag whole");
    var zalgo = "e" + "\u0301".repeat(100);
    assertEquals(
        zalgo.substring(0, ChatListing.TITLE_MOST), titleOf(zalgo), "one grapheme past the bound");
  }

  /** T13: only a message is a title: a mode set before the first message is passed over. */
  @Test
  @Timeout(value = 60, unit = TimeUnit.SECONDS)
  void aModeSetBeforeTheFirstMessageIsNoTitle() {
    var session = bench.samsChat();
    as("sam", () -> bench.host.answers().mode(session, Map.of("mode", "plan")));
    assertEquals(List.of("mode_set"), bench.kinds(session));

    bench.send(session, "Plan the berths");

    assertEquals("Plan the berths", only(listed("sam", "try")).get("title"));
  }

  /** T14: another chat's message, the person's own or another's, never titles a chat. */
  @Test
  @Timeout(value = 60, unit = TimeUnit.SECONDS)
  void anotherChatsMessageNeverTitlesMine() {
    var untitled = bench.samsChat();
    var at = Instant.parse("2026-11-27T08:00:00Z");
    var tomId = bench.fdes.byHandle("tom").orElseThrow().id();
    for (var other :
        List.of(
            chat("toms", "acme", tomId, "try", at), chat("mine", "acme", bench.samId, "try", at))) {
      bench.store.open(other);
      new ChatLog(bench.store, other.id(), timing, () -> {})
          .log(new ChatWire.Message("Said in " + other.id(), false));
    }

    var rows = listed("sam", "try");

    assertEquals(List.of(untitled, "mine"), names(rows));
    assertFalse(rows.getFirst().containsKey("title"), rows.getFirst().toString());
    assertEquals("Said in mine", rows.get(1).get("title"));
  }

  /**
   * T9: a title is made from the first {@link ChatListing#TITLE_READ} characters of the message
   * alone, so no listing reads a message's whole 64 KiB.
   */
  @Test
  @Timeout(value = 60, unit = TimeUnit.SECONDS)
  void aTitleIsMadeFromTheMessagesOpeningCharactersAlone() {
    var blank = " ".repeat(ChatListing.TITLE_READ - 4);
    assertEquals("late", titleOf(blank + "late words"));
    assertFalse(only(listed(blank + "    late words")).containsKey("title"));
  }

  /**
   * T8: no control character or line break reaches a title: each, and each run of spaces, is one
   * space, and the first line with words in it is the first line; a NUL ends what SQLite reads.
   */
  @Test
  @Timeout(value = 60, unit = TimeUnit.SECONDS)
  void noControlCharacterOrLineBreakReachesATitle() {
    assertEquals(
        "Fix the bug [31m in red",
        titleOf(" \n\t\n  Fix\tthe\u0007bug \u001B[31m in\u00A0\u00A0red  \nsecond line"));
    assertEquals("Fix the", titleOf("Fix the\u0000bug"));
  }

  private String titleOf(String said) {
    return titleOf(said, false);
  }

  private String titleOf(String said, boolean queued) {
    return (String) only(listed(said, queued)).get("title");
  }

  private List<Map<String, Object>> listed(String said) {
    return listed(said, false);
  }

  /** Sam's one chat in try whose first message, logged as the host logs one, is {@code said}. */
  private List<Map<String, Object>> listed(String said, boolean queued) {
    var id = "said-" + said.hashCode() + "-" + queued;
    bench.store.open(chat(id, "acme", bench.samId, "try", Instant.parse("2026-11-27T09:00:00Z")));
    new ChatLog(bench.store, id, timing, () -> {}).log(new ChatWire.Message(said, queued));
    var rows = listed("sam", "try");
    bench.store.eraseWorkspace("acme", "try");
    return rows;
  }

  /**
   * Returns once {@code logins} login files were withdrawn from the workspace, each by the end of
   * its process on a thread of its own, so no withdrawal is counted as the listing's; past {@link
   * ChatBench#PATIENCE}, the test fails.
   */
  private void withdrawn(long logins) {
    var deadline = System.nanoTime() + ChatBench.PATIENCE.toNanos();
    while (withdrawals() < logins) {
      if (System.nanoTime() > deadline) {
        throw new AssertionError(logins + " login files were not withdrawn");
      }
      Thread.onSpinWait();
    }
  }

  private long withdrawals() {
    synchronized (bench.incus) {
      return bench.incus.invocations().stream()
          .filter(command -> command.contains(SessionLogin.REMOVE))
          .count();
    }
  }

  private String stateOf(String session) {
    var starts = bench.processes.starts.size();
    var commands = bench.incus.invocations().size();
    var row =
        listed("sam", "try").stream()
            .filter(each -> each.get("name").equals(session))
            .findFirst()
            .orElseThrow();
    assertEquals(starts, bench.processes.starts.size(), "the listing starts no process");
    assertEquals(commands, bench.incus.invocations().size(), "the listing asks no workspace");
    return (String) row.get("state");
  }

  private List<Map<String, Object>> listed(String handle, String workspace) {
    return as(handle, () -> listing.of("acme", workspace)).stream()
        .map(ChatListing.Row::toMap)
        .toList();
  }

  private static List<Object> names(List<Map<String, Object>> rows) {
    return rows.stream().map(row -> row.get("name")).toList();
  }

  private static Map<String, Object> only(List<Map<String, Object>> rows) {
    assertEquals(1, rows.size(), rows.toString());
    return rows.getFirst();
  }

  private static WorkspaceSessionsResponse.Session terminal(String name, Instant at) {
    return new WorkspaceSessionsResponse.Session(
        name, true, 0, "", "sam", List.of(), PtyMessage.Agent.NONE, at, null);
  }

  private static ChatStore.Chat chat(
      String id, String project, String opener, String workspace, Instant at) {
    return new ChatStore.Chat(
        id,
        opener,
        project,
        workspace,
        "claude-code",
        "default",
        false,
        null,
        false,
        false,
        0,
        at,
        null);
  }
}
