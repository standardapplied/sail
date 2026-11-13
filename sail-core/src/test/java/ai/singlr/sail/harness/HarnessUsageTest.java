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

  /** Each harness's report, as a run leaves it in the file that harness reports in. */
  static Stream<Object[]> reports() {
    return Stream.of(
        new Object[] {CLAUDE_CODE, Harness.ReportFile.AGENT_LOG, CLAUDE_LOG},
        new Object[] {CODEX, Harness.ReportFile.TRANSCRIPT, CODEX_ROLLOUT});
  }

  @ParameterizedTest
  @MethodSource("reports")
  void everyHarnessAnswersUsageFromItsOwnReportAndNothingFromNone(
      Harness harness, Harness.ReportFile file, String report) {
    assertEquals(file, harness.reportFile(), harness.yamlName());
    assertEquals(true, harness.usage(report).reported(), harness.yamlName());
    assertEquals(RunUsage.NONE, harness.usage(null), harness.yamlName());
    assertEquals(RunUsage.NONE, harness.usage(""), harness.yamlName());
    assertEquals(RunUsage.NONE, harness.usage("not json\n{\"type\":\"system\"}\n"));
  }

  /** Scenario 1's report: the last result's whole-tree tokens, its dollars and its model. */
  @Test
  void claudeCodeReportsTheLastResultsModelUsageAndDollars() {
    assertEquals(
        new RunUsage(
            "claude-opus-5-5",
            370L,
            138021L,
            45026873L,
            374463L,
            14.762978600000006,
            RunUsage.Source.RESULT),
        CLAUDE_CODE.usage(CLAUDE_LOG));
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
        new RunUsage("claude-opus-5-5", 11L, 22L, 33L, 4L, 3.5, RunUsage.Source.RESULT),
        CLAUDE_CODE.usage(line));
  }

  @Test
  void aCrashedSessionsZeroedLastResultGivesWayToTheRunningTotalBeforeIt() {
    var crashed =
        CLAUDE_LOG
            + "{\"type\":\"result\",\"subtype\":\"error_during_execution\",\"is_error\":true,"
            + "\"total_cost_usd\":0,\"usage\":{\"input_tokens\":0,\"output_tokens\":0},"
            + "\"modelUsage\":{}}\n";

    assertEquals(CLAUDE_CODE.usage(CLAUDE_LOG), CLAUDE_CODE.usage(crashed));
  }

  /**
   * Minor 1 of the first review: a last result line that does not parse (cut mid-write, or holding
   * a raw C1 control or U+FFFF the parser refuses) leaves the report unread, never the running
   * total of a result before it.
   */
  @Test
  void aLastResultLineThatDoesNotParseLeavesTheReportUnreadNotAnEarlierTotal() {
    var last = CLAUDE_LOG.strip().lines().toList().getLast();
    var before = CLAUDE_LOG.strip().substring(0, CLAUDE_LOG.strip().length() - last.length());
    var opened = last.indexOf("\"result\":\"") + "\"result\":\"".length();

    for (var unparseable :
        List.of(
            last.substring(0, last.length() / 2),
            last.substring(0, opened) + "\u0080" + last.substring(opened),
            last.substring(0, opened) + "\uffff" + last.substring(opened))) {
      assertEquals(null, CLAUDE_CODE.usage(before + unparseable + "\n"));
      assertEquals(null, CLAUDE_CODE.usage(unparseable));
    }
    assertEquals(
        CLAUDE_CODE.usage(CLAUDE_LOG),
        CLAUDE_CODE.usage(before + last.substring(0, opened) + "\u007f" + last.substring(opened)),
        "a character the parser takes is read as it is");
  }

  @Test
  void aResultWithNoModelUsageGivesItsMainLoopUsageAndNoModel() {
    var line =
        "{\"type\":\"result\",\"total_cost_usd\":0.25,\"usage\":{\"input_tokens\":5,"
            + "\"output_tokens\":6,\"cache_read_input_tokens\":7,\"cache_creation_input_tokens\":8}}";
    var dollarsOnly = "{\"type\":\"result\",\"total_cost_usd\":0.25}";

    assertEquals(
        new RunUsage(null, 5L, 6L, 7L, 8L, 0.25, RunUsage.Source.RESULT), CLAUDE_CODE.usage(line));
    assertEquals(
        new RunUsage(null, null, null, null, null, 0.25, RunUsage.Source.RESULT),
        CLAUDE_CODE.usage(dollarsOnly),
        "a figure it did not report stays unreported, never zero");
  }

  /** Scenario 2's report: tokens from the last count, the cached part out of the input. */
  @Test
  void codexReportsItsTranscriptsLastTokenCountAndModelAndNoDollars() {
    assertEquals(
        new RunUsage(
            "gpt-6-astra", 856314L - 768640L, 4004L, 768640L, 0L, null, RunUsage.Source.TRANSCRIPT),
        CODEX.usage(CODEX_ROLLOUT));
  }

  @Test
  void aTranscriptsLastCountCutMidLineLeavesItUnreadAndACountWithNoTotalsIsPassedOver() {
    var whole =
        CODEX_ROLLOUT.lines().toList().subList(0, 4).stream()
            .reduce((a, b) -> a + "\n" + b)
            .orElseThrow();
    var cut =
        whole
            + "\n{\"timestamp\":\"x\",\"type\":\"event_msg\",\"payload\":{\"type\":\"token_count\"";
    var noModel = CODEX_ROLLOUT.lines().filter(line -> !line.contains("turn_context")).toList();

    assertEquals(21535L - 12160L, CODEX.usage(whole).tokensIn());
    assertEquals(null, CODEX.usage(cut), "a cut last count is not read");
    assertEquals(null, CODEX.usage(String.join("\n", noModel)).model());
    assertEquals(
        21535L - 12160L,
        CODEX
            .usage(
                whole
                    + "\n{\"type\":\"event_msg\",\"payload\":{\"type\":\"token_count\",\"info\":null}}")
            .tokensIn(),
        "a count with no totals reports nothing, and the one before it is the last");
    assertEquals(
        RunUsage.NONE,
        CODEX.usage(
            "{\"type\":\"event_msg\",\"payload\":{\"type\":\"token_count\",\"info\":null}}\n"));
  }

  private static RunUsage report(long tokens, double usd) {
    return new RunUsage("m", tokens, tokens, tokens, tokens, usd, RunUsage.Source.RESULT);
  }

  private static RunUsage total(long tokens, double usd) {
    return new RunUsage(null, tokens, tokens, tokens, tokens, usd, RunUsage.Source.RESULT);
  }

  private static RunUsage.Attributed after(RunUsage.Session session, RunUsage... reports) {
    var attributed = new RunUsage.Attributed(null, session);
    for (var reported : reports) {
      attributed = reported.attributedIn(attributed.session(), attributed.own());
    }
    return attributed;
  }

  /**
   * A run's share of its session is its report less what the session's other runs were given: a
   * session given 100 that reports 300 gives its run 200, and the session then holds 300 as both
   * its highest and what was given.
   */
  @Test
  void aRunsShareIsItsReportLessWhatItsSessionsOtherRunsWereGiven() {
    var given = new RunUsage.Session(report(100, 1.0), report(100, 1.0));

    var attributed = report(300, 3.0).attributedIn(given, null);

    assertEquals(report(200, 2.0), attributed.own());
    assertEquals(new RunUsage.Session(total(300, 3.0), total(300, 3.0)), attributed.session());
    assertEquals(
        report(300, 3.0),
        report(300, 3.0).attributedIn(RunUsage.Session.NEW, null).own(),
        "a session nothing was reported of gives the whole report");
  }

  /** A live run's turns: each running total sets its share, never adds to it. */
  @Test
  void aLiveRunsTurnsEachSetItsShareOfTheSession() {
    var sibling = new RunUsage.Session(report(100, 1.0), report(100, 1.0));

    var attributed = after(sibling, report(110, 1.1), report(130, 1.3), report(160, 1.6));

    assertEquals(60L, attributed.own().tokensIn());
    assertEquals(0.6, attributed.own().costUsd(), 1e-9);
    assertEquals(160L, attributed.session().given().tokensIn());
  }

  /**
   * A report below the session's highest in any figure tells no share: the run is unattributable,
   * gives back what it was given, stays so whatever it reports next, and the session's highest
   * stands.
   */
  @Test
  void aReportBelowTheSessionsHighestIsUnattributableForGood() {
    var session = new RunUsage.Session(report(150, 1.5), report(150, 1.5));

    var late = report(100, 1.0).attributedIn(session, null);
    var cheaper =
        new RunUsage("m", 200L, 200L, 200L, 200L, 1.0, RunUsage.Source.RESULT)
            .attributedIn(session, null);
    var turned = after(session, report(170, 1.7), report(20, 0.2), report(400, 4.0));

    assertEquals(
        new RunUsage("m", null, null, null, null, null, RunUsage.Source.UNATTRIBUTABLE),
        late.own());
    assertEquals(session, late.session());
    assertEquals(RunUsage.Source.UNATTRIBUTABLE, cheaper.own().source(), "dollars below too");
    assertEquals(RunUsage.Source.UNATTRIBUTABLE, turned.own().source());
    assertEquals(
        List.of(170L, 150L),
        List.of(turned.session().highest().tokensIn(), turned.session().given().tokensIn()),
        "the share it was given goes back; the highest seen stands");
    assertEquals(false, late.own().reported());
  }

  /** Nothing reported changes nothing; a figure a harness does not report stays unreported. */
  @Test
  void aReportOfNothingChangesNothingAndAnUnreportedFigureStaysSo() {
    var session = new RunUsage.Session(report(100, 1.0), report(100, 1.0));
    var turns = report(130, 1.3);
    var codex = new RunUsage("g", 300L, 300L, 300L, 300L, null, RunUsage.Source.TRANSCRIPT);

    assertEquals(
        new RunUsage.Attributed(RunUsage.NONE, session), RunUsage.NONE.attributedIn(session, null));
    assertEquals(
        new RunUsage.Attributed(turns, session), RunUsage.NONE.attributedIn(session, turns));
    assertEquals(
        new RunUsage("g", 200L, 200L, 200L, 200L, null, RunUsage.Source.TRANSCRIPT),
        codex.attributedIn(session, null).own());
    assertEquals(1.0, codex.attributedIn(session, null).session().given().costUsd());
    assertEquals(
        RunUsage.Source.UNATTRIBUTABLE,
        RunUsage.NONE
            .attributedIn(
                session,
                new RunUsage(null, null, null, null, null, null, RunUsage.Source.UNATTRIBUTABLE))
            .own()
            .source());
    assertEquals(
        1160L, new RunUsage("m", 100L, 50L, 1000L, 10L, 2.0, RunUsage.Source.RESULT).tokens());
    assertEquals(null, RunUsage.NONE.tokens());
    assertEquals(null, new RunUsage(null, 1L, null, 1L, 1L, null, RunUsage.Source.RESULT).tokens());
  }

  @Test
  void aSourceIsReadAsItsWireNameAndAnUnknownOneIsRefused() {
    assertEquals(
        List.of(
            RunUsage.Source.RESULT,
            RunUsage.Source.TRANSCRIPT,
            RunUsage.Source.NONE,
            RunUsage.Source.UNATTRIBUTABLE),
        Stream.of("result", "transcript", "none", "unattributable")
            .map(RunUsage.Source::fromWire)
            .toList());
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
