/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.engine;

import ai.singlr.sail.common.Strings;
import ai.singlr.sail.engine.LiveStream.Step;
import ai.singlr.sail.harness.HarnessChat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * A person's answer to a request Claude Code made in a chat, as Claude Code takes it: what they
 * decided, the words a denial carries and the answers to a question, held to what the request
 * asked, and the one line of {@link LiveChat}'s that tells Claude Code (2.1.332, recorded in the S0
 * probe and probe 5).
 */
public final class LiveReply {

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

  private LiveReply() {}

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
      return LiveChat.deny(id, Objects.requireNonNullElse(reply.message(), NOT_ALLOWED));
    }
    return switch (request) {
      case Step.Permission asked when reply.decision() == Decision.ALLOW ->
          LiveChat.allow(id, asked.input());
      case Step.Permission asked when reply.decision() == Decision.ALLOW_ALWAYS -> {
        if (chat.always(asked.tool(), asked.input(), asked.suggestions()).isEmpty()) {
          throw new IllegalArgumentException(
              chat.noAlways(asked.tool(), asked.input(), asked.suggestions()));
        }
        yield LiveChat.allowAlways(id, asked.input(), asked.suggestions());
      }
      case Step.Permission _ ->
          throw new IllegalArgumentException(
              "Claude Code asks to make this call: allow it, allow it always, or deny it.");
      case Step.Question asked when reply.decision() == Decision.ANSWER ->
          LiveChat.answer(id, asked.input(), answers(asked, reply.answers()));
      case Step.Question _ ->
          throw new IllegalArgumentException("Claude Code asks a question: answer it, or deny it.");
      case Step.Plan asked when reply.decision() == Decision.ALLOW ->
          LiveChat.allow(id, asked.input());
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
}
