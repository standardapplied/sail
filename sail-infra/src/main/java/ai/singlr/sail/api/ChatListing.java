/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.api;

import ai.singlr.sail.access.Access;
import ai.singlr.sail.common.DateTimeUtils;
import ai.singlr.sail.engine.ChatItem;
import ai.singlr.sail.engine.ChatWire;
import ai.singlr.sail.harness.SessionForm;
import ai.singlr.sail.identity.Actor;
import ai.singlr.sail.store.ChatStore;
import ai.singlr.sail.store.FdeStore;
import java.text.BreakIterator;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * The caller's own chats in a workspace, as its sessions listing says them beside its terminals:
 * open ones, and ended ones for {@link #ENDED_KEPT}, newest first, at most {@link #MOST}; an older
 * one's log is still read by its id. Each one's state is read from its log alone ({@link #state}):
 * no process is asked or started and no lock taken, so whether a process holds it is never a state;
 * its title from the first line of the person's first message ({@link #title}), read in the query
 * that lists it.
 */
final class ChatListing {

  /** How long an ended chat stays listed, as an ended terminal does. */
  static final Duration ENDED_KEPT = Duration.ofMinutes(10);

  /** The most chats one listing says. */
  static final int MOST = 200;

  /**
   * The most code points a title holds: a tab shows about 20 to 30 of them and an overview card
   * about twice that, so 80 is a whole thought that every place shows the start of.
   */
  static final int TITLE_MOST = 80;

  /**
   * The most characters of a first message a title is made from: past the first line's bound with
   * room for the whitespace and control characters it drops, so a message's whole 64 KiB is never
   * read for a listing of 200 chats.
   */
  static final int TITLE_READ = 1024;

  private static final Pattern LINE = Pattern.compile("\\R");

  /**
   * What a title holds as one space: whitespace, control characters, and the bidi embeddings,
   * overrides and isolates, which would turn what the app writes after a title, and which a cut at
   * the bound could leave open. The joiners, selectors, tags and marks of words and emoji stay.
   */
  private static final Pattern BLANKS =
      Pattern.compile("[\\p{Cc}\\p{Z}\\u202A-\\u202E\\u2066-\\u2069]+");

  /** What a line with words holds: a letter, number, punctuation or symbol. */
  private static final Pattern WORDS = Pattern.compile("[\\p{L}\\p{N}\\p{P}\\p{S}]");

  private static final String MESSAGE = ChatWire.kind(ChatWire.Message.class);
  private static final String FAILED = ChatWire.kind(ChatItem.Failed.class);
  private static final String TURN_ENDED = ChatWire.kind(ChatItem.TurnEnded.class);
  private static final List<String> ENDINGS =
      List.of(FAILED, ChatWire.kind(ChatItem.Interrupted.class), TURN_ENDED);

  /** Where a chat stands, as a person is shown it. */
  enum State {
    ENDED,
    NEEDS_YOU,
    WORKING,
    ERROR,
    YOUR_TURN,
    IDLE;

    String wire() {
      return name().toLowerCase(Locale.ROOT);
    }
  }

  /** One chat as its workspace's sessions listing says it; {@code title} null for none. */
  record Row(ChatStore.Chat chat, String title, String opener, State state)
      implements WorkspaceSessionsResponse.Listed {

    @Override
    public String name() {
      return chat.id();
    }

    @Override
    public Instant createdAt() {
      return chat.createdAt();
    }

    @Override
    public Map<String, Object> toMap() {
      var m = new LinkedHashMap<String, Object>();
      m.put("name", chat.id());
      m.put("form", SessionForm.CHAT.wire());
      if (title != null) m.put("title", title);
      m.put("live", !chat.ended());
      m.put("state", state.wire());
      m.put("opener", opener);
      m.put("agent", WorkspaceSessionsResponse.agent(chat.harness(), chat.mode(), chat.inside()));
      m.put("created_at", DateTimeUtils.format(chat.createdAt()));
      return m;
    }
  }

  private final ChatStore store;
  private final FdeStore fdes;
  private final ChatTiming timing;

  ChatListing(ChatStore store, FdeStore fdes, ChatTiming timing) {
    this.store = Objects.requireNonNull(store, "store");
    this.fdes = Objects.requireNonNull(fdes, "fdes");
    this.timing = Objects.requireNonNull(timing, "timing");
  }

  /**
   * The bound caller's chats in workspace {@code workspace} of {@code project}: only a person's
   * own, an admin's too, and none for a credential that acts for no person.
   */
  List<Row> of(String project, String workspace) {
    var person = Access.attendee(Actor.current()).flatMap(fdes::byHandle);
    if (person.isEmpty()) {
      return List.of();
    }
    var since = timing.clock().get().minus(ENDED_KEPT);
    return store
        .listed(person.get().id(), project, workspace, since, MOST, MESSAGE, TITLE_READ)
        .stream()
        .map(
            listed ->
                new Row(
                    listed.chat(),
                    title(listed.opening()),
                    person.get().handle(),
                    state(listed.chat())))
        .toList();
  }

  /**
   * The title of a chat whose first message opens with {@code said}: its first line with words in
   * it ({@link #WORDS}), each run of {@link #BLANKS} one space, cut to {@link #TITLE_MOST} code
   * points on the last whole word that fits, or on the last whole grapheme when one word is longer;
   * null when no line has words or there is no message.
   */
  private static String title(String said) {
    if (said == null) {
      return null;
    }
    var line =
        LINE.splitAsStream(said)
            .map(each -> BLANKS.matcher(each).replaceAll(" ").strip())
            .filter(each -> WORDS.matcher(each).find())
            .findFirst()
            .orElse(null);
    if (line == null || line.codePointCount(0, line.length()) <= TITLE_MOST) {
      return line;
    }
    var bound = line.offsetByCodePoints(0, TITLE_MOST);
    var space = line.lastIndexOf(' ', bound);
    return line.substring(0, space < 0 ? grapheme(line, bound) : space);
  }

  /**
   * Where a cut of {@code line} at or before {@code bound} leaves its last grapheme whole: a ZWJ
   * family, a flag or a letter with its marks; {@code bound} itself when one grapheme is longer.
   */
  private static int grapheme(String line, int bound) {
    var graphemes = BreakIterator.getCharacterInstance();
    graphemes.setText(line);
    var whole = graphemes.isBoundary(bound) ? bound : graphemes.preceding(bound);
    return whole > 0 ? whole : bound;
  }

  /**
   * Where {@code chat} stands, by the first that holds: ended; a turn open with a request {@link
   * ChatLog#open} holds, or with none; its last turn failed ({@code failed} just before its {@code
   * turn_ended}); a turn ended otherwise, stopped or cut; no turn yet.
   */
  private State state(ChatStore.Chat chat) {
    if (chat.ended()) {
      return State.ENDED;
    }
    if (chat.turnOpen()) {
      return ChatLog.open(store, chat.id()).requests().isEmpty() ? State.WORKING : State.NEEDS_YOU;
    }
    var last = store.lastKinds(chat.id(), ENDINGS, 2);
    if (last.isEmpty()) {
      return State.IDLE;
    }
    return last.equals(List.of(TURN_ENDED, FAILED)) ? State.ERROR : State.YOUR_TURN;
  }
}
