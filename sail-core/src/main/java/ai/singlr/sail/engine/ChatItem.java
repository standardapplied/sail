/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.engine;

import ai.singlr.sail.harness.LoginOutcome;
import java.util.List;

/**
 * One thing a person sees in a chat with an agent, whichever harness the agent runs in: each
 * harness's stream maps onto these, so a client draws a chat with no code of any harness, and no
 * harness's own names (its tools' input keys, its suggestion objects) reach an item. An item
 * carries what its harness said and nothing it did not: an {@code under} is the id of the {@link
 * Tool} row of the subagent the item belongs to, or null for the agent itself, and a mode is one of
 * the harness's chat modes by name ({@code HarnessChat.modes}). What the agent wrote is clipped
 * ({@link LiveStream#CONTENT_CODE_POINTS}) and is never markup.
 */
public sealed interface ChatItem {

  /** What a tool call does, as a person reads its row. */
  enum ToolKind {
    COMMAND,
    EDIT,
    READ,
    SEARCH,
    WEB,
    MCP,
    AGENT,
    OTHER
  }

  /**
   * What a tool call works on, for a person who opens its row or decides on it, as its harness maps
   * the call's input: a field it did not say is null.
   */
  sealed interface Detail {

    /** A command {@code line} run in the workspace's shell. */
    record Command(String line) implements Detail {}

    /**
     * A change to the file at {@code path}, from the text {@code before} to {@code after}; {@code
     * before} is null when the harness writes the whole file, new or over what was there.
     */
    record Edit(String path, String before, String after) implements Detail {}

    /** A read of the file at {@code path}. */
    record Read(String path) implements Detail {}

    /** A search for {@code what}, in {@code where} when it says. */
    record Search(String what, String where) implements Detail {}

    /** A fetch of the page at {@code address}. */
    record Fetch(String address) implements Detail {}

    /** Any other call: the tool's {@code name} and its {@code input} as text, shown as text. */
    record Other(String name, String input) implements Detail {}
  }

  /**
   * The conversation took a turn: the model answering it (null when the harness does not say), the
   * mode it is in, and the commands and skills a person can name in it. Said at every turn, since
   * any of them can change between turns.
   */
  record Session(String model, String mode, List<String> commands, List<String> skills)
      implements ChatItem {}

  /** The agent is in {@code mode} now, whoever changed it. */
  record ModeChanged(String mode) implements ChatItem {}

  /**
   * The agent's text: a fragment of what it is saying, appended to what it said before, or, when
   * {@code newParagraph}, the start of a new paragraph, which a block said whole is. A subagent's
   * text can arrive after its turn ended, before the turn the harness starts itself.
   */
  record Text(String under, String text, boolean newParagraph) implements ChatItem {}

  /** A tool call began: its row, in the plain {@code words} of what it does, and its detail. */
  record Tool(String under, String id, ToolKind kind, String words, Detail detail)
      implements ChatItem {}

  /**
   * The tool call {@code id} came back with {@code output}, which says why when it {@code failed}.
   */
  record ToolDone(String under, String id, String output, boolean failed) implements ChatItem {}

  /**
   * The agent waits for a person: its {@code id} is the one an answer names, and {@code call} the
   * {@link Tool} row it is about. A request dies with the process that made it: nothing of it is
   * replayed on a resume.
   */
  sealed interface Request extends ChatItem {

    String id();

    String call();
  }

  /**
   * The agent asks to make a call: in {@code words}, with why when the harness says ({@code
   * reason}, else null), and {@code always}, one sentence for each thing allowing it always would
   * change, from now on and where, in the harness's words: none when it offers no such choice.
   */
  record Permission(
      String id,
      String call,
      ToolKind kind,
      String words,
      String reason,
      Detail detail,
      List<String> always)
      implements Request {}

  /** The agent asks a person to choose, one answer to each of {@code questions}. */
  record Question(String id, String call, List<Asked> questions) implements Request {}

  /** One question: its text, its short {@code header}, and its choices, one or several. */
  record Asked(String question, String header, List<Choice> choices, boolean multiSelect) {}

  /** One choice of a question, and what it means. */
  record Choice(String label, String about) {}

  /** The agent asks a person to approve its {@code plan} before it changes anything. */
  record Plan(String id, String call, String plan) implements Request {}

  /**
   * The request {@code id} needs no answer now: the harness withdrew it, or its process ended,
   * which the host says.
   */
  record Withdrawn(String id) implements ChatItem {}

  /** Something happened to the turn that is not the agent's own words. */
  sealed interface Notice extends ChatItem {}

  /** A request to the model failed with {@code error}; try {@code attempt} of {@code attempts}. */
  record Retrying(int attempt, int attempts, long delayMs, String error) implements Notice {}

  /** The conversation was compacted from {@code tokensBefore} tokens to {@code tokensAfter}. */
  record Compacted(String trigger, long tokensBefore, long tokensAfter) implements Notice {}

  /**
   * The turn was stopped before it finished: at a person's word, or because the process running it
   * ended, which the host says.
   */
  record Interrupted() implements Notice {}

  /**
   * The harness refused the request {@code id} the host made of it, such as a change of mode, in
   * its own {@code words}.
   */
  record Refused(String id, String words) implements Notice {}

  /** The turn that ends next was begun by the agent itself, not by a person's message. */
  record SelfStarted() implements ChatItem {}

  /**
   * The turn failed: in the harness's own {@code words}, and what came of the login when that is
   * why ({@code login}, else null).
   */
  record Failed(String words, LoginOutcome login) implements ChatItem {}

  /**
   * A turn ended: what this turn alone cost at API prices, null when the harness does not say
   * (under a subscription it is no charge to the person), the tokens the model read ({@code
   * tokensIn}, cached or not) and wrote, and how long it took.
   */
  record TurnEnded(Double costUsd, long tokensIn, long tokensOut, long durationMs)
      implements ChatItem {}

  /**
   * What the person said: {@code text}, with the workspace files at {@code attachments} shown to
   * the agent, held for the agent ({@code queued}) when it was said while a turn was open. The host
   * logs it; no harness says it.
   */
  record Message(String text, List<String> attachments, boolean queued) implements ChatItem {}

  /**
   * The person's message logged as {@code message}, held while a turn was open, went to the agent.
   */
  record Delivered(long message) implements ChatItem {}

  /** What Sail itself tells the person of the chat, in its own {@code words}. */
  record Told(String words) implements Notice {}
}
