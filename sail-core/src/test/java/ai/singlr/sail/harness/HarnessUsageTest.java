/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.harness;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * What each harness reports a run cost, read from its own output: Claude Code's last {@code result}
 * line of a recorded stream-json log, Codex's last {@code token_count} of a recorded rollout. The
 * fixtures are real lines of each harness, trimmed. And the efforts each takes, the rest refused
 * naming it (M15).
 */
class HarnessUsageTest {

  private static final Harness CLAUDE_CODE = Harnesses.of("claude-code");
  private static final Harness CODEX = Harnesses.of("codex");

  static String fixture(String name) {
    try (var in = HarnessUsageTest.class.getResourceAsStream(name)) {
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  static final String CLAUDE_LOG = fixture("claude-code-agent.jsonl");
  static final String CODEX_ROLLOUT = fixture("codex-rollout.jsonl");

  /** What each harness reads its report from, as a run leaves it: its log, then its transcript. */
  static Stream<Object[]> reports() {
    return Stream.of(
        new Object[] {CLAUDE_CODE, CLAUDE_LOG, null}, new Object[] {CODEX, "", CODEX_ROLLOUT});
  }

  @ParameterizedTest
  @MethodSource("reports")
  void everyHarnessAnswersUsageFromItsOwnReportAndNothingFromNone(
      Harness harness, String log, String transcript) {
    assertEquals(true, harness.usage(log, transcript).isPresent(), harness.yamlName());
    assertEquals(Optional.empty(), harness.usage(null, null), harness.yamlName());
    assertEquals(Optional.empty(), harness.usage("", ""), harness.yamlName());
    assertEquals(
        Optional.empty(), harness.usage("not json\n{\"type\":\"system\"}\n", "not json\n"));
  }

  /** Scenario 1's report: the last result's whole-tree tokens, its dollars and its model. */
  @Test
  void claudeCodeReportsTheLastResultsModelUsageAndDollars() {
    assertEquals(
        Optional.of(
            new RunUsage(
                "claude-opus-5-5",
                370L,
                138021L,
                45026873L,
                374463L,
                14.762978600000006,
                RunUsage.Source.RESULT)),
        CLAUDE_CODE.usage(CLAUDE_LOG, "a transcript Claude Code's report never comes from"));
  }

  @Test
  void claudeCodeSumsEveryModelItUsedAndNamesTheOneThatCostMost() {
    var line =
        "{\"type\":\"result\",\"subtype\":\"success\",\"total_cost_usd\":3.5,\"modelUsage\":{"
            + "\"claude-haiku-5\":{\"inputTokens\":10,\"outputTokens\":20,\"cacheReadInputTokens\":30,"
            + "\"costUSD\":0.5},"
            + "\"claude-opus-5-5\":{\"inputTokens\":1,\"outputTokens\":2,\"cacheReadInputTokens\":3,"
            + "\"cacheCreationInputTokens\":4,\"costUSD\":3.0}}}";

    assertEquals(
        Optional.of(
            new RunUsage("claude-opus-5-5", 11L, 22L, 33L, 4L, 3.5, RunUsage.Source.RESULT)),
        CLAUDE_CODE.usage(line, null));
  }

  @Test
  void aCrashedSessionsZeroedLastResultGivesWayToTheRunningTotalBeforeIt() {
    var crashed =
        CLAUDE_LOG
            + "{\"type\":\"result\",\"subtype\":\"error_during_execution\",\"is_error\":true,"
            + "\"total_cost_usd\":0,\"usage\":{\"input_tokens\":0,\"output_tokens\":0},"
            + "\"modelUsage\":{}}\n"
            + "{\"type\":\"result\",\"subtype\":\"error";

    assertEquals(CLAUDE_CODE.usage(CLAUDE_LOG, null), CLAUDE_CODE.usage(crashed, null));
  }

  @Test
  void aResultWithNoModelUsageGivesItsMainLoopUsageAndNoModel() {
    var line =
        "{\"type\":\"result\",\"total_cost_usd\":0.25,\"usage\":{\"input_tokens\":5,"
            + "\"output_tokens\":6,\"cache_read_input_tokens\":7,\"cache_creation_input_tokens\":8}}";
    var dollarsOnly = "{\"type\":\"result\",\"total_cost_usd\":0.25}";

    assertEquals(
        Optional.of(new RunUsage(null, 5L, 6L, 7L, 8L, 0.25, RunUsage.Source.RESULT)),
        CLAUDE_CODE.usage(line, null));
    assertEquals(
        Optional.of(new RunUsage(null, null, null, null, null, 0.25, RunUsage.Source.RESULT)),
        CLAUDE_CODE.usage(dollarsOnly, null),
        "a figure it did not report stays unreported, never zero");
  }

  /** Scenario 2's report: tokens from the last count, the cached part out of the input. */
  @Test
  void codexReportsItsTranscriptsLastTokenCountAndModelAndNoDollars() {
    assertEquals(
        Optional.of(
            new RunUsage(
                "gpt-6-astra",
                856314L - 768640L,
                4004L,
                768640L,
                0L,
                null,
                RunUsage.Source.TRANSCRIPT)),
        CODEX.usage("codex's agent.log is text, never its report", CODEX_ROLLOUT));
  }

  @Test
  void aTranscriptCutMidLineIsReadUpToItsLastWholeCount() {
    var cut =
        CODEX_ROLLOUT.lines().toList().subList(0, 4).stream()
                .reduce((a, b) -> a + "\n" + b)
                .orElseThrow()
            + "\n{\"timestamp\":\"x\",\"type\":\"event_msg\",\"payload\":{\"type\":\"token_count\"";
    var noModel = CODEX_ROLLOUT.lines().filter(line -> !line.contains("turn_context")).toList();

    assertEquals(21535L - 12160L, CODEX.usage(null, cut).orElseThrow().tokensIn());
    assertEquals(null, CODEX.usage(null, String.join("\n", noModel)).orElseThrow().model());
    assertEquals(
        Optional.empty(),
        CODEX.usage(
            null,
            "{\"type\":\"event_msg\",\"payload\":{\"type\":\"token_count\",\"info\":null}}\n"),
        "a count with no totals reports nothing");
  }

  @Test
  void aResumedConversationsReportLessWhatItsOtherRunsRecordedIsTheRunsOwn() {
    var session = new RunUsage("m", 100L, 50L, 1000L, 10L, 2.0, RunUsage.Source.RESULT);
    var earlier = new RunUsage(null, 60L, 20L, 400L, 10L, 1.25, RunUsage.Source.RESULT);
    var codex = new RunUsage("g", 100L, 50L, 1000L, 10L, null, RunUsage.Source.TRANSCRIPT);

    assertEquals(
        new RunUsage("m", 40L, 30L, 600L, 0L, 0.75, RunUsage.Source.RESULT),
        session.beyond(earlier));
    assertEquals(session, session.beyond(null), "nothing recorded before it");
    assertEquals(
        session,
        session.beyond(new RunUsage(null, 200L, 20L, 400L, 10L, 1.25, RunUsage.Source.RESULT)),
        "a report below what was recorded is a harness that started counting over");
    assertEquals(
        session,
        session.beyond(new RunUsage(null, 60L, 20L, 400L, 10L, 9.0, RunUsage.Source.RESULT)),
        "dollars below what was recorded too");
    assertEquals(RunUsage.NONE, RunUsage.NONE.beyond(earlier));
    assertEquals(session, session.beyond(RunUsage.NONE));
    assertEquals(
        new RunUsage("g", 40L, 30L, 600L, 0L, null, RunUsage.Source.TRANSCRIPT),
        codex.beyond(earlier));
    assertEquals(1160L, session.tokens());
    assertEquals(null, RunUsage.NONE.tokens());
    assertEquals(null, new RunUsage(null, 1L, null, 1L, 1L, null, RunUsage.Source.RESULT).tokens());
  }

  @Test
  void aSourceIsReadAsItsWireNameAndAnUnknownOneIsRefused() {
    assertEquals(
        List.of(RunUsage.Source.RESULT, RunUsage.Source.TRANSCRIPT, RunUsage.Source.NONE),
        Stream.of("result", "transcript", "none").map(RunUsage.Source::fromWire).toList());
    assertEquals(null, RunUsage.Source.fromWire(null));
    assertEquals(
        "Unknown usage source: 'guess'.",
        assertThrows(IllegalArgumentException.class, () -> RunUsage.Source.fromWire("guess"))
            .getMessage());
    assertThrows(
        NullPointerException.class, () -> new RunUsage(null, null, null, null, null, null, null));
  }

  /** Scenario 16's refusal, and the efforts each harness takes. */
  @Test
  void anEffortTheHarnessDoesNotTakeIsRefusedNamingItNeverDropped() {
    assertEquals(
        Map.of("claude-code", "high", "codex", "none"),
        Map.of(
            "claude-code",
            CLAUDE_CODE.requireEffort("high"),
            "codex",
            CODEX.requireEffort("none")));
    assertEquals(null, CLAUDE_CODE.requireEffort(null));
    assertEquals(
        "Claude Code takes no reasoning effort 'none'; it takes low, medium, high, xhigh. Choose"
            + " one of those, or clear the effort.",
        assertThrows(IllegalArgumentException.class, () -> CLAUDE_CODE.requireEffort("none"))
            .getMessage());
    var takesNone = StubHarness.loggingInThrough("nothing", OptionalInt.empty());
    assertEquals(
        "nothing takes no reasoning effort, so reasoning_effort 'high' cannot be passed to it."
            + " Clear the effort, or choose a harness that takes one.",
        assertThrows(IllegalArgumentException.class, () -> takesNone.requireEffort("high"))
            .getMessage());
  }
}
