/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.engine;

import ai.singlr.sail.harness.LoginOutcome;
import java.util.List;
import java.util.Map;

/**
 * One thing a person sees in a chat with an agent, whichever harness the agent runs in: each
 * harness's stream maps onto these, so a client draws a chat with no code of any harness. An item
 * carries what its harness said and nothing it did not: an {@code under} is the id of the {@link
 * Tool} row of the subagent the item belongs to, or null for the agent itself, and a mode is one of
 * the harness's chat modes by name ({@code Harness.chatModes}).
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
   * The conversation took a turn: the model answering it, the mode it is in, and the commands and
   * skills a person can name in it. Said at every turn, since any of them can change between turns.
   */
  record Session(String model, String mode, List<String> commands, List<String> skills)
      implements ChatItem {}

  /** The agent is in {@code mode} now, whoever changed it. */
  record ModeChanged(String mode) implements ChatItem {}

  /**
   * The agent's text: a fragment of what it is saying, appended to what it said before, or, when
   * {@code newParagraph}, the start of a new paragraph, which a block said whole is.
   */
  record Text(String under, String text, boolean newParagraph) implements ChatItem {}

  /**
   * A tool call began: its row, in the plain {@code words} of what it does, and its {@code input}
   * as the harness named it, for a person who opens the row.
   */
  record Tool(String under, String id, ToolKind kind, String words, Map<String, Object> input)
      implements ChatItem {}

  /**
   * The tool call {@code id} came back with {@code output}, which says why when it {@code failed}.
   */
  record ToolDone(String under, String id, String output, boolean failed) implements ChatItem {}

  /**
   * The agent waits for a person: its {@code id} is the one an answer names, and {@code call} the
   * {@link Tool} row it is about.
   */
  sealed interface Request extends ChatItem {

    String id();

    String call();
  }

  /**
   * The agent asks to make a call: in {@code words}, with why when the harness says ({@code
   * reason}, else null), and whether the harness offers to allow it always ({@code alwaysOffered}).
   */
  record Permission(
      String id,
      String call,
      ToolKind kind,
      String words,
      String reason,
      Map<String, Object> input,
      boolean alwaysOffered)
      implements Request {}

  /** The agent asks a person to choose, one answer to each of {@code questions}. */
  record Question(String id, String call, List<Asked> questions) implements Request {}

  /** One question: its text, its short {@code header}, and its choices, one or several. */
  record Asked(String question, String header, List<Choice> choices, boolean multiSelect) {}

  /** One choice of a question, and what it means. */
  record Choice(String label, String about) {}

  /** The agent asks a person to approve its {@code plan} before it changes anything. */
  record Plan(String id, String call, String plan) implements Request {}

  /** The harness withdrew the request {@code id}: it needs no answer now. */
  record Withdrawn(String id) implements ChatItem {}

  /** Something happened to the turn that is not the agent's own words. */
  sealed interface Notice extends ChatItem {}

  /** A request to the model failed with {@code error}; try {@code attempt} of {@code attempts}. */
  record Retrying(int attempt, int attempts, long delayMs, String error) implements Notice {}

  /** The conversation was compacted from {@code tokensBefore} tokens to {@code tokensAfter}. */
  record Compacted(String trigger, long tokensBefore, long tokensAfter) implements Notice {}

  /** The turn was stopped before it finished, at a person's word. */
  record Interrupted() implements Notice {}

  /** The turn that ends next was begun by the agent itself, not by a person's message. */
  record SelfStarted() implements ChatItem {}

  /**
   * The turn failed: in the harness's own {@code words}, and what came of the login when that is
   * why ({@code login}, else null).
   */
  record Failed(String words, LoginOutcome login) implements ChatItem {}

  /**
   * A turn ended: what this turn alone cost, the tokens the model read ({@code tokensIn}, cached or
   * not) and wrote, and how long it took.
   */
  record TurnEnded(double costUsd, long tokensIn, long tokensOut, long durationMs)
      implements ChatItem {}
}
