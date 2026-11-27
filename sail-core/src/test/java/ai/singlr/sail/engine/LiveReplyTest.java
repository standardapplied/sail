/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import ai.singlr.sail.engine.LiveReply.Answer;
import ai.singlr.sail.engine.LiveReply.Decision;
import ai.singlr.sail.engine.LiveReply.Reply;
import ai.singlr.sail.engine.LiveStream.Step;
import ai.singlr.sail.harness.HarnessChat;
import ai.singlr.sail.harness.Harnesses;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.function.Executable;

/**
 * What a person's answer to a request writes to Claude Code, against the recordings ({@link
 * Recorded}): an allow with the request's own input, an always with its own suggestions, a denial
 * with the person's words exactly, an answer to each question as Claude Code 2.1.332 read it (probe
 * 5), and every reply a request does not take refused in plain words.
 */
@Timeout(value = 60, unit = TimeUnit.SECONDS)
class LiveReplyTest {

  private static final HarnessChat CLAUDE_CODE = Harnesses.of("claude-code").chat().orElseThrow();

  private static final Reply ALLOW = new Reply(Decision.ALLOW, null, List.of());
  private static final Reply ALWAYS = new Reply(Decision.ALLOW_ALWAYS, null, List.of());
  private static final Reply DENY = new Reply(Decision.DENY, null, List.of());

  private static Step step(String recording, int line) {
    return LiveStream.read(Recorded.line(recording, line));
  }

  private static void assertWritten(String recording, int line, String written) {
    assertEquals(Recorded.parsed(recording, line), Recorded.parsed(written));
  }

  private static String refused(Executable reply) {
    return assertThrows(IllegalArgumentException.class, reply).getMessage();
  }

  private static Reply answer(Answer... answers) {
    return new Reply(Decision.ANSWER, null, List.of(answers));
  }

  private static Answer chosen(String question, String... labels) {
    return new Answer(question, List.of(labels), null);
  }

  @Test
  void anAllowHandsBackTheRequestsOwnInput() {
    assertWritten(
        "02b-relay-stdio", 17, LiveReply.reply(step("02b-relay-stdio", 16), ALLOW, CLAUDE_CODE));
    assertWritten(
        "03b-plan-exit-plan-mode",
        59,
        LiveReply.reply(step("03b-plan-exit-plan-mode", 58), ALLOW, CLAUDE_CODE));
  }

  @Test
  void anAlwaysHandsBackTheRequestsOwnSuggestionsAsTheyCame() {
    assertWritten(
        "02b-relay-stdio", 100, LiveReply.reply(step("02b-relay-stdio", 99), ALWAYS, CLAUDE_CODE));
  }

  @Test
  void anAlwaysItsCardDidNotOfferIsRefused() {
    var unsaid = step("01-always-rule-wrappers", 108);

    assertEquals(
        "Claude Code offered no way to allow this always; allow it once instead.",
        refused(() -> LiveReply.reply(unsaid, ALWAYS, CLAUDE_CODE)));
  }

  @Test
  void aDenialCarriesThePersonsWordsExactlyOrSaysTheyGaveNone() {
    var denied = new Reply(Decision.DENY, "DENIED-tamarind-4417", List.of());
    var padded =
        new Reply(Decision.DENY, "  I would rather not choose a color today.  ", List.of());

    assertWritten(
        "05-answer-twice", 17, LiveReply.reply(step("05-answer-twice", 16), denied, CLAUDE_CODE));
    assertWritten(
        "03-question-denied-with-words",
        22,
        LiveReply.reply(step("03-question-denied-with-words", 21), padded, CLAUDE_CODE));
    assertWritten(
        "03b-plan-exit-plan-mode",
        29,
        LiveReply.reply(
            step("03b-plan-exit-plan-mode", 28),
            new Reply(Decision.DENY, "Change the plan: write the word bye instead.", List.of()),
            CLAUDE_CODE));
    assertEquals(
        LiveChat.deny("bda0b4cf-c4f6-4219-bd84-0f0617292889", LiveReply.NOT_ALLOWED),
        LiveReply.reply(step("05-answer-twice", 16), DENY, CLAUDE_CODE));
  }

  @Test
  void aDeniedQuestionsWordsComeBackAsItsCallsResultUntrimmed() {
    assertEquals(
        List.of(
            new ChatItem.ToolDone(
                null,
                "toolu_015wspCoLjyvnRb3PYapn9J2",
                "  I would rather not choose a color today.  ",
                true,
                false)),
        LiveChat.items(step("03-question-denied-with-words", 26), CLAUDE_CODE));
  }

  @Test
  void severalChoicesAreJoinedAsClaudeCodeReadsThemAndOwnWordsAreSentAsSaid() {
    var asked = step("02-question-multi-select-and-own-words", 32);

    assertWritten(
        "02-question-multi-select-and-own-words",
        34,
        LiveReply.reply(
            asked,
            answer(
                new Answer("Which size?", List.of(), "teal, but only on weekends"),
                chosen("Which colors?", "Red", "Blue")),
            CLAUDE_CODE));
    assertWritten(
        "03a-ask-user-question",
        22,
        LiveReply.reply(
            step("03a-ask-user-question", 21),
            answer(chosen("Which color?", "Blue")),
            CLAUDE_CODE));
  }

  @Test
  void aQuestionIsAnsweredAsItWasAskedOrNotAtAll() {
    var asked = step("02-question-multi-select-and-own-words", 32);
    var size = chosen("Which size?", "Small");

    assertEquals(
        "Answer every question this asks, each named by its exact text.",
        refused(() -> LiveReply.reply(asked, answer(size), CLAUDE_CODE)));
    assertEquals(
        "A question answered is not one this asks; name each by its exact text.",
        refused(
            () ->
                LiveReply.reply(
                    asked,
                    answer(size, chosen("Which colors?", "Red"), chosen("Which colour?", "Red")),
                    CLAUDE_CODE)));
    assertEquals(
        "Each question is answered once.",
        refused(
            () ->
                LiveReply.reply(
                    asked,
                    answer(size, chosen("Which colors?", "Red"), chosen("Which colors?", "Blue")),
                    CLAUDE_CODE)));
    var notAChoice =
        "Each label chosen is one of its question's own choices, once; anything else is said in"
            + " own words.";
    assertEquals(
        notAChoice,
        refused(
            () ->
                LiveReply.reply(
                    asked, answer(size, chosen("Which colors?", "Teal")), CLAUDE_CODE)));
    assertEquals(
        notAChoice,
        refused(
            () ->
                LiveReply.reply(
                    asked, answer(size, chosen("Which colors?", "Red", "Red")), CLAUDE_CODE)));
    assertEquals(
        "This question takes one choice.",
        refused(
            () ->
                LiveReply.reply(
                    asked,
                    answer(chosen("Which size?", "Small", "Large"), chosen("Which colors?", "Red")),
                    CLAUDE_CODE)));
  }

  @Test
  void aChoiceHoldingTheJoinIsChosenAlone() {
    var asked =
        new Step.Question(
            "q1",
            "toolu_1",
            Map.of(),
            List.of(
                new ChatItem.Asked(
                    "Which?",
                    "Pick",
                    List.of(
                        new ChatItem.Choice("Red, Blue", "both", false),
                        new ChatItem.Choice("Green", "one", false)),
                    true,
                    false)));

    assertEquals(
        "Claude Code reads several choices joined by ', ', so a choice holding ', ' is chosen"
            + " alone.",
        refused(
            () ->
                LiveReply.reply(
                    asked, answer(chosen("Which?", "Red, Blue", "Green")), CLAUDE_CODE)));
    assertEquals(
        LiveChat.answer("q1", Map.of(), Map.of("Which?", "Red, Blue")),
        LiveReply.reply(asked, answer(chosen("Which?", "Red, Blue")), CLAUDE_CODE));
  }

  @Test
  void aDecisionTheRequestDoesNotTakeIsRefused() {
    var permission = step("02b-relay-stdio", 16);
    var question = step("03a-ask-user-question", 21);
    var plan = step("03b-plan-exit-plan-mode", 58);
    var answered = answer(chosen("Which color?", "Blue"));
    var toPermission = "Claude Code asks to make this call: allow it, allow it always, or deny it.";
    var toQuestion = "Claude Code asks a question: answer it, or deny it.";
    var toPlan = "Claude Code asks to approve its plan: allow it, or deny it with what to change.";

    assertEquals(toPermission, refused(() -> LiveReply.reply(permission, answered, CLAUDE_CODE)));
    assertEquals(toQuestion, refused(() -> LiveReply.reply(question, ALLOW, CLAUDE_CODE)));
    assertEquals(toQuestion, refused(() -> LiveReply.reply(question, ALWAYS, CLAUDE_CODE)));
    assertEquals(toPlan, refused(() -> LiveReply.reply(plan, ALWAYS, CLAUDE_CODE)));
    assertEquals(toPlan, refused(() -> LiveReply.reply(plan, answered, CLAUDE_CODE)));
  }

  @Test
  void onlyARequestAPersonWasShownWholeIsAnswered() {
    var unreadable = new Step.Unreadable("r1", true);

    assertThrows(
        IllegalStateException.class, () -> LiveReply.reply(unreadable, ALLOW, CLAUDE_CODE));
  }

  @Test
  void aReplyIsOnlyTheShapeItsDecisionTakes() {
    var tooLong = "a".repeat(LiveStream.CONTENT_CODE_POINTS) + "😀";
    var wholly = "😀".repeat(LiveStream.CONTENT_CODE_POINTS);
    var past = "Your words are past the 65536 characters an answer holds; say it shorter.";

    assertEquals(
        "Only a denial carries words: message goes with deny.",
        refused(() -> new Reply(Decision.ALLOW, "please", List.of())));
    assertEquals(
        "answers go with answer alone, which names each question it answers.",
        refused(() -> new Reply(Decision.DENY, null, List.of(chosen("Q", "A")))));
    assertEquals(
        "answers go with answer alone, which names each question it answers.",
        refused(() -> new Reply(Decision.ANSWER, null, List.of())));
    assertEquals(
        "message is the person's own words; leave it out to say none.",
        refused(() -> new Reply(Decision.DENY, "  ", List.of())));
    assertEquals(past, refused(() -> new Reply(Decision.DENY, tooLong, List.of())));
    assertEquals(past, refused(() -> new Answer("Q", List.of(), tooLong)));
    assertEquals(wholly, new Reply(Decision.DENY, wholly, List.of()).message());
    assertEquals(
        "An answer to a question is the labels chosen or the person's own words, not both.",
        refused(() -> new Answer("Q", List.of("A"), "mine")));
    assertEquals(
        "An answer to a question is the labels chosen or the person's own words, not both.",
        refused(() -> new Answer("Q", List.of(), null)));
  }

  @Test
  void aDecisionIsOneOfFourByTheNameAClientSends() {
    assertEquals(
        List.of(Decision.ALLOW, Decision.ALLOW_ALWAYS, Decision.DENY, Decision.ANSWER),
        List.of("allow", "allow_always", "deny", "answer").stream().map(Decision::named).toList());
    assertEquals(
        "decision is allow, allow_always, deny or answer.", refused(() -> Decision.named("ALLOW")));
    assertEquals(
        "decision is allow, allow_always, deny or answer.",
        refused(() -> Decision.named("bypass")));
  }

  @Test
  void aModeIsSwitchedBetweenTurnsAsRecorded() {
    assertWritten("01-mode-plan-between-turns", 14, LiveChat.setMode("mode-plan", "plan"));
    assertEquals(
        List.of(new ChatItem.ModeChanged("plan", false)),
        LiveChat.items(step("01-mode-plan-between-turns", 16), CLAUDE_CODE));
    assertEquals(List.of(), LiveChat.items(step("01-mode-plan-between-turns", 15), CLAUDE_CODE));
    assertEquals("plan", ((Step.Init) step("01-mode-plan-between-turns", 19)).mode());
  }
}
