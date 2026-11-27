/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.engine;

import static ai.singlr.sail.engine.LiveStream.CONTENT_CODE_POINTS;
import static ai.singlr.sail.engine.LiveStream.NAME_CODE_POINTS;
import static ai.singlr.sail.engine.LiveStream.SUMMARY_CODE_POINTS;

import ai.singlr.sail.common.Strings;
import ai.singlr.sail.harness.LoginOutcome;
import java.util.List;

/**
 * One thing a person sees in a chat with an agent, whichever harness the agent runs in: each
 * harness's stream maps onto these, so a client draws a chat with no code of any harness, and no
 * harness's own names (its tools' input keys, its suggestion objects) reach an item. An item
 * carries what its harness said and nothing it did not: an {@code under} is the id of the {@link
 * Tool} row of the subagent the item belongs to, or null for the agent itself, and a mode is the
 * harness's own name for it, which need not be one of its chat modes ({@code HarnessChat.modes}).
 *
 * <p>What the agent wrote is never markup, and is clipped where the item is made, to its bound:
 * {@link LiveStream#NAME_CODE_POINTS} for a name, {@link LiveStream#SUMMARY_CODE_POINTS} for a
 * short line, {@link LiveStream#CONTENT_CODE_POINTS} for anything long. A piece holding text that
 * was cut says so in its one {@code clipped} flag, so a client marks it as shown in part. Only what
 * is shown after the fact is ever cut: every part of a {@link Request}, and of its detail, is held
 * to the content bound, and a request any part of which is longer is never asked ({@link
 * Unreadable}), since a person can never allow what they were not shown whole.
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

    /** Whether any of its text was cut. */
    boolean clipped();

    /**
     * A command {@code line} run in the workspace's shell, in the {@code background} when it keeps
     * running after the turn that ran it.
     */
    record Command(String line, boolean background, boolean clipped) implements Detail {
      public Command {
        clipped |= cut(line, CONTENT_CODE_POINTS);
        line = clip(line, CONTENT_CODE_POINTS);
      }
    }

    /** Changes to files, one or several, in the order the harness gave them. */
    record Edit(List<Change> changes) implements Detail {
      public Edit {
        changes = List.copyOf(changes);
      }

      @Override
      public boolean clipped() {
        return changes.stream().anyMatch(Change::clipped);
      }
    }

    /** A read of the file at {@code path}. */
    record Read(String path, boolean clipped) implements Detail {
      public Read {
        clipped |= cut(path, CONTENT_CODE_POINTS);
        path = clip(path, CONTENT_CODE_POINTS);
      }
    }

    /** A search for {@code what}, in {@code where} when it says. */
    record Search(String what, String where, boolean clipped) implements Detail {
      public Search {
        clipped |= cut(what, CONTENT_CODE_POINTS) || cut(where, CONTENT_CODE_POINTS);
        what = clip(what, CONTENT_CODE_POINTS);
        where = clip(where, CONTENT_CODE_POINTS);
      }
    }

    /** A fetch of the page at {@code address}. */
    record Fetch(String address, boolean clipped) implements Detail {
      public Fetch {
        clipped |= cut(address, CONTENT_CODE_POINTS);
        address = clip(address, CONTENT_CODE_POINTS);
      }
    }

    /** Any other call: the tool's {@code name} and its {@code input} as text, shown as text. */
    record Other(String name, String input, boolean clipped) implements Detail {
      public Other {
        clipped |= cut(name, CONTENT_CODE_POINTS) || cut(input, CONTENT_CODE_POINTS);
        name = clip(name, CONTENT_CODE_POINTS);
        input = clip(input, CONTENT_CODE_POINTS);
      }
    }
  }

  /** How a file changes. */
  enum ChangeKind {
    /** The file at its path changes from {@code before} to {@code after}. */
    CHANGE,
    /** A new file at its path, holding {@code after}. */
    ADD,
    /** The file at its path is deleted; {@code before} is what it held, when said. */
    DELETE,
    /**
     * The file at {@code from} moves to its path, changing from {@code before} to {@code after}.
     */
    MOVE
  }

  /**
   * One file's change: of {@code kind}, to the file at {@code path}, moved from {@code from} (null
   * unless it moves), from the text {@code before} to {@code after}. A {@link ChangeKind#CHANGE}
   * with {@code before} null writes the whole file as {@code after}, over whatever was there; one
   * that is {@code everywhere} makes the change at every place {@code before} occurs, not once.
   */
  record Change(
      ChangeKind kind,
      String path,
      String from,
      String before,
      String after,
      boolean everywhere,
      boolean clipped) {
    public Change {
      clipped |=
          cut(path, CONTENT_CODE_POINTS)
              || cut(from, CONTENT_CODE_POINTS)
              || cut(before, CONTENT_CODE_POINTS)
              || cut(after, CONTENT_CODE_POINTS);
      path = clip(path, CONTENT_CODE_POINTS);
      from = clip(from, CONTENT_CODE_POINTS);
      before = clip(before, CONTENT_CODE_POINTS);
      after = clip(after, CONTENT_CODE_POINTS);
    }
  }

  /**
   * The conversation took a turn: the model answering it (null when the harness does not say), the
   * mode it is in, and the commands and skills a person can name in it. Said at every turn, since
   * any of them can change between turns.
   */
  record Session(
      String model, String mode, List<String> commands, List<String> skills, boolean clipped)
      implements ChatItem {
    public Session {
      clipped |=
          cut(model, NAME_CODE_POINTS)
              || cut(mode, NAME_CODE_POINTS)
              || commands.stream().anyMatch(name -> cut(name, NAME_CODE_POINTS))
              || skills.stream().anyMatch(name -> cut(name, NAME_CODE_POINTS));
      model = clip(model, NAME_CODE_POINTS);
      mode = clip(mode, NAME_CODE_POINTS);
      commands = commands.stream().map(name -> clip(name, NAME_CODE_POINTS)).toList();
      skills = skills.stream().map(name -> clip(name, NAME_CODE_POINTS)).toList();
    }
  }

  /** The agent is in {@code mode} now, whoever changed it. */
  record ModeChanged(String mode, boolean clipped) implements ChatItem {
    public ModeChanged {
      clipped |= cut(mode, NAME_CODE_POINTS);
      mode = clip(mode, NAME_CODE_POINTS);
    }
  }

  /**
   * The agent's text: a fragment of what it is saying, appended to what it said before, or, when
   * {@code newParagraph}, the start of a new paragraph, which a block said whole is. A subagent's
   * text can arrive after its turn ended, before the turn the harness starts itself.
   */
  record Text(String under, String text, boolean newParagraph, boolean clipped)
      implements ChatItem {
    public Text {
      clipped |= cut(text, CONTENT_CODE_POINTS);
      text = clip(text, CONTENT_CODE_POINTS);
    }
  }

  /** A tool call began: its row, in the plain {@code words} of what it does, and its detail. */
  record Tool(String under, String id, ToolKind kind, String words, Detail detail, boolean clipped)
      implements ChatItem {
    public Tool {
      clipped |= cut(words, SUMMARY_CODE_POINTS);
      words = clip(words, SUMMARY_CODE_POINTS);
    }
  }

  /**
   * The tool call {@code id} came back with {@code output}, which says why when it {@code failed}.
   * A clipped output keeps its beginning.
   */
  record ToolDone(String under, String id, String output, boolean failed, boolean clipped)
      implements ChatItem {
    public ToolDone {
      clipped |= cut(output, CONTENT_CODE_POINTS);
      output = clip(output, CONTENT_CODE_POINTS);
    }
  }

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
   * The agent asks to make a call, or a subagent of it does: the {@code agent} asking, null for the
   * agent itself, is the id a subagent's lines name ({@code LiveStream.Step.Subagent#agentId})
   * beside the call that started it, under whose row the request is drawn. Its {@code words} are
   * the agent's own description of the call (for a command, the model wrote them) and need not say
   * what the call does: a card always shows the {@code detail} beside them. With why when the
   * harness says ({@code reason}, else null), and the ways to allow it always the harness offers
   * ({@code always}): none when it offers none, or when any part of one is a shape that cannot be
   * said in plain words, so a person is never offered more than they read.
   */
  record Permission(
      String id,
      String call,
      String agent,
      ToolKind kind,
      String words,
      String reason,
      Detail detail,
      List<Always> always,
      boolean clipped)
      implements Request {
    public Permission {
      clipped |= cut(words, CONTENT_CODE_POINTS) || cut(reason, CONTENT_CODE_POINTS);
      words = clip(words, CONTENT_CODE_POINTS);
      reason = clip(reason, CONTENT_CODE_POINTS);
      always = List.copyOf(always);
    }
  }

  /**
   * One way to allow a call always: everything choosing it changes, one plain sentence each ({@code
   * words}), each saying what is allowed or changed and where, said whole and never clipped.
   */
  record Always(List<String> words) {
    public Always {
      words = List.copyOf(words);
    }
  }

  /** The agent asks a person to choose, one answer to each of {@code questions}. */
  record Question(String id, String call, List<Asked> questions) implements Request {
    public Question {
      questions = List.copyOf(questions);
    }
  }

  /** One question: its text, its short {@code header}, and its choices, one or several. */
  record Asked(
      String question, String header, List<Choice> choices, boolean multiSelect, boolean clipped) {
    public Asked {
      clipped |= cut(question, CONTENT_CODE_POINTS) || cut(header, CONTENT_CODE_POINTS);
      question = clip(question, CONTENT_CODE_POINTS);
      header = clip(header, CONTENT_CODE_POINTS);
      choices = List.copyOf(choices);
    }
  }

  /** One choice of a question, and what it means. */
  record Choice(String label, String about, boolean clipped) {
    public Choice {
      clipped |= cut(label, CONTENT_CODE_POINTS) || cut(about, CONTENT_CODE_POINTS);
      label = clip(label, CONTENT_CODE_POINTS);
      about = clip(about, CONTENT_CODE_POINTS);
    }
  }

  /** The agent asks a person to approve its {@code plan} before it changes anything. */
  record Plan(String id, String call, String plan, boolean clipped) implements Request {
    public Plan {
      clipped |= cut(plan, CONTENT_CODE_POINTS);
      plan = clip(plan, CONTENT_CODE_POINTS);
    }
  }

  /**
   * The request {@code id} needs no answer now: the harness withdrew it, or its process ended,
   * which the host says.
   */
  record Withdrawn(String id) implements ChatItem {}

  /** Something happened to the turn that is not the agent's own words. */
  sealed interface Notice extends ChatItem {}

  /** A request to the model failed with {@code error}; try {@code attempt} of {@code attempts}. */
  record Retrying(int attempt, int attempts, long delayMs, String error, boolean clipped)
      implements Notice {
    public Retrying {
      clipped |= cut(error, SUMMARY_CODE_POINTS);
      error = clip(error, SUMMARY_CODE_POINTS);
    }
  }

  /** The conversation was compacted from {@code tokensBefore} tokens to {@code tokensAfter}. */
  record Compacted(String trigger, long tokensBefore, long tokensAfter, boolean clipped)
      implements Notice {
    public Compacted {
      clipped |= cut(trigger, NAME_CODE_POINTS);
      trigger = clip(trigger, NAME_CODE_POINTS);
    }
  }

  /** The turn was stopped before it finished, at a person's word. */
  record Interrupted() implements Notice {}

  /**
   * The harness refused a request the host made of it, such as a change of mode, in its own {@code
   * words}: the request {@code id}, or null when the refusal names none the host could have made.
   */
  record Refused(String id, String words, boolean clipped) implements Notice {
    public Refused {
      clipped |= cut(words, SUMMARY_CODE_POINTS);
      words = clip(words, SUMMARY_CODE_POINTS);
    }
  }

  /**
   * The agent asked something that could not be read whole, or that was {@code tooLarge} to show a
   * person whole, so no person was asked: the host refused it at once, telling the agent to split a
   * request too large into smaller steps, or, when it named no request to answer, {@code stopped}
   * the turn instead.
   */
  record Unreadable(boolean tooLarge, boolean stopped) implements Notice {}

  /** The turn that ends next was begun by the agent itself, not by a person's message. */
  record SelfStarted() implements ChatItem {}

  /**
   * The turn failed: in the harness's own {@code words}, and what came of the login when that is
   * why ({@code login}, else null).
   */
  record Failed(String words, LoginOutcome login, boolean clipped) implements ChatItem {
    public Failed {
      clipped |= cut(words, CONTENT_CODE_POINTS);
      words = clip(words, CONTENT_CODE_POINTS);
    }
  }

  /**
   * A turn ended: what this turn alone cost at API prices, null when the harness does not say
   * (under a subscription it is no charge to the person), the tokens the model read ({@code
   * tokensIn}, cached or not) and wrote, and how long it took.
   */
  record TurnEnded(Double costUsd, long tokensIn, long tokensOut, long durationMs)
      implements ChatItem {}

  private static boolean cut(String text, int bound) {
    return text != null && text.codePointCount(0, text.length()) > bound;
  }

  private static String clip(String text, int bound) {
    return text == null ? null : Strings.clip(text, bound);
  }
}
