/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.engine;

import ai.singlr.sail.common.Strings;
import ai.singlr.sail.config.YamlUtil;
import ai.singlr.sail.engine.LiveStream.Step;
import ai.singlr.sail.harness.HarnessChat;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * A chat's side of Claude Code's live stream ({@link LiveStream}): what a person is shown of each
 * step it reads, as {@link ChatItem}s, and the lines a chat writes on Claude Code's stdin besides a
 * message: its answers to Claude Code's requests and its own requests to Claude Code. Each line is
 * one Claude Code 2.1.332 was written and acted on (the S0 probe's recordings), and each request
 * names an id the caller chooses, which Claude Code's answer names again.
 */
public final class LiveChat {

  /** What a denial says to the agent when the person gave no words of their own. */
  public static final String NOT_ALLOWED = "The person did not allow this.";

  /**
   * How Claude Code reads several choices of one question: their labels joined by this (probe 5,
   * 02:34, read back whole at 02:37).
   */
  static final String JOINED = ", ";

  /** What a person decides on a request their agent made, by the name a client sends. */
  public enum Decision {
    ALLOW,
    ALLOW_ALWAYS,
    DENY,
    ANSWER;

    /**
     * The decision a client names {@code name}.
     *
     * @throws IllegalArgumentException for any other name
     */
    public static Decision named(String name) {
      for (var decision : values()) {
        if (decision.name().toLowerCase(Locale.ROOT).equals(name)) {
          return decision;
        }
      }
      throw new IllegalArgumentException("decision is allow, allow_always, deny or answer.");
    }
  }

  /**
   * A person's answer to one of a request's questions, named by its exact text: labels from its own
   * choices ({@code chosen}), or the person's {@code own} words, never both.
   */
  public record Answer(String question, List<String> chosen, String own) {
    public Answer {
      Objects.requireNonNull(question, "question");
      chosen = List.copyOf(chosen);
      if (chosen.isEmpty() == (own == null)) {
        throw new IllegalArgumentException(
            "An answer to a question is the labels chosen or the person's own words, not both.");
      }
      if (own != null) {
        words(Strings.requireNonBlank(own, "own"));
      }
    }
  }

  /**
   * What a person answers a request with: the {@code decision}, the words a denial carries ({@code
   * message}, null for none) and the answers to a question's questions, the only decision that has
   * any.
   */
  public record Reply(Decision decision, String message, List<Answer> answers) {
    public Reply {
      Objects.requireNonNull(decision, "decision");
      answers = List.copyOf(answers);
      if (message != null && decision != Decision.DENY) {
        throw new IllegalArgumentException("Only a denial carries words: message goes with deny.");
      }
      if (answers.isEmpty() == (decision == Decision.ANSWER)) {
        throw new IllegalArgumentException(
            "answers go with answer alone, which names each question it answers.");
      }
      if (message != null && words(message).isBlank()) {
        throw new IllegalArgumentException(
            "message is the person's own words; leave it out to say none.");
      }
    }
  }

  private LiveChat() {}

  /**
   * What a person is shown of {@code step}, of a process of the harness {@code chat} speaks for;
   * none for many. The host keeps the step itself: what it answers a request with, the call's input
   * and the harness's own suggestions, is the step's, never an item's.
   */
  public static List<ChatItem> items(Step step, HarnessChat chat) {
    return items(step, chat, null);
  }

  private static List<ChatItem> items(Step step, HarnessChat chat, String under) {
    return switch (step) {
      case Step.Init init ->
          List.of(
              new ChatItem.Session(
                  init.model(), init.mode(), init.commands(), init.skills(), false));
      case Step.TextStarted _ -> List.of(new ChatItem.Text(under, "", true, false));
      case Step.Text text -> List.of(new ChatItem.Text(under, text.text(), false, false));
      case Step.Said said -> List.of(new ChatItem.Text(under, said.text(), true, false));
      case Step.ToolCalls called ->
          called.calls().stream().<ChatItem>map(call -> row(call, chat, under)).toList();
      case Step.ToolResults returned ->
          returned.results().stream()
              .<ChatItem>map(
                  result ->
                      new ChatItem.ToolDone(
                          under, result.callId(), result.output(), result.error(), false))
              .toList();
      case Step.Subagent subagent -> items(subagent.step(), chat, subagent.callId());
      case Step.Permission asked -> List.of(permission(asked, chat));
      case Step.Question asked ->
          List.of(new ChatItem.Question(asked.requestId(), asked.callId(), asked.questions()));
      case Step.Plan asked ->
          List.of(new ChatItem.Plan(asked.requestId(), asked.callId(), asked.plan(), false));
      case Step.Shown shown -> List.of(shown.item());
      case Step.Unreadable unreadable ->
          List.of(new ChatItem.Unreadable(unreadable.tooLarge(), unreadable.requestId() == null));
      case Step.Status status ->
          status.mode() == null
              ? List.of()
              : List.of(new ChatItem.ModeChanged(status.mode(), false));
      case Step.Result result -> ended(result);
      case Step.Working _, Step.Other _ -> List.of();
    };
  }

  /**
   * A call's row: in the words Claude Code gives it ({@code description}), else its first string
   * argument.
   */
  private static ChatItem row(LiveStream.ToolCall call, HarnessChat chat, String under) {
    var words =
        call.input().get("description") instanceof String described && !described.isBlank()
            ? described
            : LiveStream.first(call.input());
    return new ChatItem.Tool(
        under,
        call.id(),
        chat.kind(call.tool()),
        words,
        chat.detail(call.tool(), call.input()),
        false);
  }

  private static ChatItem permission(Step.Permission asked, HarnessChat chat) {
    return new ChatItem.Permission(
        asked.requestId(),
        asked.callId(),
        asked.agentId(),
        chat.kind(asked.tool()),
        Strings.isNotBlank(asked.description()) ? asked.description() : asked.displayName(),
        asked.reason(),
        chat.detail(asked.tool(), asked.input()),
        chat.always(asked.suggestions()),
        false);
  }

  /**
   * How a turn ended: begun by Claude Code itself, stopped by a person or failed, then what it
   * cost. A stopped turn is flagged an error by Claude Code, and is no failure.
   */
  private static List<ChatItem> ended(Step.Result result) {
    var items = new ArrayList<ChatItem>();
    if (result.selfStarted()) {
      items.add(new ChatItem.SelfStarted());
    }
    if (result.interrupted()) {
      items.add(new ChatItem.Interrupted());
    } else if (result.error()) {
      items.add(
          new ChatItem.Failed(
              result.text().isBlank()
                  ? Objects.requireNonNullElse(result.reason(), "")
                  : result.text(),
              result.login(),
              false));
    }
    items.add(result.turn());
    return items;
  }

  /** Allows the call Claude Code asked about, run with {@code input}: its own, or a changed one. */
  public static String allow(String requestId, Map<String, Object> input) {
    return respond(
        requestId,
        LiveStream.ordered("behavior", "allow", "updatedInput", Objects.requireNonNull(input)));
  }

  /**
   * Allows the call and every one like it from now on: with {@code permissions}, the request's own
   * suggestions handed back as they came, which its item's one {@link ChatItem.Always} said; a host
   * offers this only when the item has one. Claude Code keeps a rule it suggests for {@code
   * localSettings} in {@code ~/workspace/.claude/settings.local.json}, a file it makes inside the
   * workspace, and a suggested mode switches the session's mode at once (2.1.332, recorded).
   *
   * @throws IllegalArgumentException when Claude Code suggested nothing to keep
   */
  public static String allowAlways(
      String requestId, Map<String, Object> input, List<Object> permissions) {
    if (permissions.isEmpty()) {
      throw new IllegalArgumentException(
          "Claude Code offered no way to allow this always; allow it once instead.");
    }
    return respond(
        requestId,
        LiveStream.ordered(
            "behavior",
            "allow",
            "updatedInput",
            Objects.requireNonNull(input),
            "updatedPermissions",
            permissions));
  }

  /**
   * Answers a question: its {@code input}, the questions as asked, with {@code answers}, each
   * question's text to the label chosen, exactly as the step holds them ({@link
   * Step.Question#questions}, whole, since a question any part of which would be cut is never
   * asked).
   */
  public static String answer(
      String requestId, Map<String, Object> input, Map<String, String> answers) {
    var answered = new LinkedHashMap<>(input);
    answered.put("answers", Objects.requireNonNull(answers));
    return allow(requestId, answered);
  }

  /**
   * The line that answers {@code request}, a step of {@code chat}'s harness that asks a person,
   * with {@code reply}: an allow hands back the step's own input, and always its own suggestions,
   * as they came; a denial carries the person's words exactly, untrimmed, or {@link #NOT_ALLOWED};
   * an answer names every question by its text, each answered with labels of its own choices,
   * several joined as Claude Code reads them ({@link #JOINED}), or the person's own words.
   *
   * @throws IllegalArgumentException for a decision the request's kind does not take, an always its
   *     card did not offer, or answers that are not one to each of its questions
   */
  public static String reply(Step request, Reply reply, HarnessChat chat) {
    var id =
        switch (request) {
          case Step.Permission asked -> asked.requestId();
          case Step.Question asked -> asked.requestId();
          case Step.Plan asked -> asked.requestId();
          default ->
              throw new IllegalStateException("Only a permission, a question or a plan is asked.");
        };
    if (reply.decision() == Decision.DENY) {
      return deny(id, Objects.requireNonNullElse(reply.message(), NOT_ALLOWED));
    }
    return switch (request) {
      case Step.Permission asked when reply.decision() == Decision.ALLOW ->
          allow(id, asked.input());
      case Step.Permission asked when reply.decision() == Decision.ALLOW_ALWAYS -> {
        if (chat.always(asked.suggestions()).isEmpty()) {
          throw new IllegalArgumentException(
              "Claude Code offered no way to allow this always; allow it once instead.");
        }
        yield allowAlways(id, asked.input(), asked.suggestions());
      }
      case Step.Permission _ ->
          throw new IllegalArgumentException(
              "Claude Code asks to make this call: allow it, allow it always, or deny it.");
      case Step.Question asked when reply.decision() == Decision.ANSWER ->
          answer(id, asked.input(), answers(asked, reply.answers()));
      case Step.Question _ ->
          throw new IllegalArgumentException("Claude Code asks a question: answer it, or deny it.");
      case Step.Plan asked when reply.decision() == Decision.ALLOW -> allow(id, asked.input());
      default ->
          throw new IllegalArgumentException(
              "Claude Code asks to approve its plan: allow it, or deny it with what to change.");
    };
  }

  /** Each of {@code asked}'s questions, by its text, to what {@code given} answers it. */
  private static Map<String, String> answers(Step.Question asked, List<Answer> given) {
    var named = new LinkedHashMap<String, Answer>();
    for (var answer : given) {
      if (named.put(answer.question(), answer) != null) {
        throw new IllegalArgumentException("Each question is answered once.");
      }
    }
    var answers = new LinkedHashMap<String, String>();
    for (var question : asked.questions()) {
      var answer = named.remove(question.question());
      if (answer == null) {
        throw new IllegalArgumentException(
            "Answer every question this asks, each named by its exact text.");
      }
      answers.put(question.question(), said(question, answer));
    }
    if (!named.isEmpty()) {
      throw new IllegalArgumentException(
          "A question answered is not one this asks; name each by its exact text.");
    }
    return answers;
  }

  /** What {@code answer} tells Claude Code of {@code question}. */
  private static String said(ChatItem.Asked question, Answer answer) {
    if (answer.own() != null) {
      return answer.own();
    }
    var chosen = answer.chosen();
    var labels = question.choices().stream().map(ChatItem.Choice::label).toList();
    if (!labels.containsAll(chosen) || chosen.stream().distinct().count() < chosen.size()) {
      throw new IllegalArgumentException(
          "Each label chosen is one of its question's own choices, once; anything else is said"
              + " in own words.");
    }
    if (chosen.size() > 1 && !question.multiSelect()) {
      throw new IllegalArgumentException("This question takes one choice.");
    }
    if (chosen.size() > 1 && chosen.stream().anyMatch(label -> label.contains(JOINED))) {
      throw new IllegalArgumentException(
          "Claude Code reads several choices joined by ', ', so a choice holding ', ' is chosen"
              + " alone.");
    }
    return String.join(JOINED, chosen);
  }

  /**
   * {@code words}, a person's own, within the bound of what an answer holds.
   *
   * @throws IllegalArgumentException past {@link LiveStream#CONTENT_CODE_POINTS}
   */
  private static String words(String words) {
    if (words.codePointCount(0, words.length()) > LiveStream.CONTENT_CODE_POINTS) {
      throw new IllegalArgumentException(
          "Your words are past the "
              + LiveStream.CONTENT_CODE_POINTS
              + " characters an answer holds; say it shorter.");
    }
    return words;
  }

  /**
   * Refuses a request that could not be read whole, or that is too large to show a person whole
   * ({@link Step.Unreadable}), at once, so its turn goes on: the agent is told why in plain words,
   * and to split one too large into smaller steps.
   *
   * @throws IllegalArgumentException for one that names no request id: stop its turn instead
   */
  public static String refuseUnreadable(Step.Unreadable unreadable) {
    return deny(
        unreadable.requestId(),
        unreadable.tooLarge()
            ? "This is too large to show a person whole, so no person was asked and it is refused."
                + " Split it into smaller steps, such as a shorter command or a large file written"
                + " in parts."
            : "Sail could not read this request whole, so no person was asked and it is refused."
                + " Ask again in another way if it is still needed.");
  }

  /** Refuses the call, a question or a plan, saying why to the agent in {@code message}. */
  public static String deny(String requestId, String message) {
    return respond(
        requestId,
        LiveStream.ordered(
            "behavior", "deny", "message", Strings.requireNonBlank(message, "message")));
  }

  /**
   * Stops the turn in progress. Claude Code withdraws a request it is waiting on ({@link
   * ChatItem.Withdrawn}), and the turn ends interrupted; the process stays, and takes the next
   * message as a new turn (2.1.332, recorded). A process that ends instead takes every request it
   * held with it, and a resume replays none (recorded): the host withdraws each itself.
   */
  public static String interrupt(String requestId) {
    return ask(requestId, LiveStream.ordered("subtype", "interrupt"));
  }

  /**
   * Puts the session in {@code mode}, one of the harness's chat modes by name; Claude Code says so
   * in a {@link Step.Status} (2.1.332, recorded), and a switch it refuses is a {@link
   * ChatItem.Refused} naming {@code requestId}.
   */
  public static String setMode(String requestId, String mode) {
    return ask(
        requestId,
        LiveStream.ordered(
            "subtype", "set_permission_mode", "mode", Strings.requireNonBlank(mode, "mode")));
  }

  private static String respond(String requestId, Map<String, Object> response) {
    return YamlUtil.dumpJson(
        LiveStream.ordered(
            "type",
            "control_response",
            "response",
            LiveStream.ordered(
                "subtype",
                "success",
                "request_id",
                Strings.requireNonBlank(requestId, "requestId"),
                "response",
                response)));
  }

  private static String ask(String requestId, Map<String, Object> request) {
    return YamlUtil.dumpJson(
        LiveStream.ordered(
            "type",
            "control_request",
            "request_id",
            Strings.requireNonBlank(requestId, "requestId"),
            "request",
            request));
  }
}
