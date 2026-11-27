/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.api;

import static ai.singlr.sail.api.ChatBench.as;
import static ai.singlr.sail.api.ChatBench.line;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

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
  private ChatListing listing;

  @BeforeEach
  void setUp() {
    bench = new ChatBench(dir);
    listing =
        new ChatListing(
            bench.store,
            bench.fdes,
            new ChatTiming(
                bench.clock::instant,
                window -> {},
                (arrivals, within) -> {},
                Duration.ZERO,
                Duration.ZERO,
                ProcessEnd.Grace.DEFAULT));
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
    assertFalse(bench.store.find(idle).orElseThrow().launched(), "no process holds it");
    assertEquals("your_turn", stateOf(idle));

    var cut = bench.samsChat();
    bench.send(cut, "hi");
    bench.says(INIT, ASKS);
    bench.host.close();
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

  /** L7, I2: only the caller's own chats, never another person's, an admin's view included. */
  @Test
  @Timeout(value = 60, unit = TimeUnit.SECONDS)
  void aChatIsListedToItsOpenerAloneInItsOwnWorkspace() {
    var sams = bench.samsChat();
    var toms =
        as("tom", () -> bench.host.open("acme", "tom", "claude-code", "default", true)).session();
    var tomId = bench.fdes.byHandle("tom").orElseThrow().id();
    bench.store.open(chat("toms-in-try", tomId, "try", Instant.parse("2026-11-27T09:00:00Z")));
    bench.store.open(
        chat("sams-elsewhere", bench.samId, "pier", Instant.parse("2026-11-27T09:00:00Z")));

    assertEquals(List.of(sams), names(listed("sam", "try")));
    assertEquals(List.of(toms), names(listed("tom", "tom")));
    assertEquals(List.of("toms-in-try"), names(listed("tom", "try")));
    assertEquals(List.of(), listed("ada", "try"), "an admin lists none of another's chats");
    assertEquals(List.of(), listed("sam", "tom"));
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
      bench.store.open(chat(id, bench.samId, "try", at.plusSeconds(i)));
      opened.add(id);
    }
    bench.store.open(chat("chat-tie", bench.samId, "try", at.plusSeconds(ChatListing.MOST)));

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

  private static ChatStore.Chat chat(String id, String opener, String workspace, Instant at) {
    return new ChatStore.Chat(
        id,
        opener,
        "acme",
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
