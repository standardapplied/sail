/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.api;

import ai.singlr.sail.config.ReviewPipelineConfig.StageConfig;
import ai.singlr.sail.engine.FindingParser;
import ai.singlr.sail.store.Finding;
import ai.singlr.sail.store.ReviewStore;
import ai.singlr.sail.store.RunStore;
import java.util.List;

/** Reads a reviewer's verdict on the stage it ran, and writes what it ruled. */
final class StageVerdicts {

  private final ReviewStore reviewStore;
  private final ReviewLanes lanes;
  private final LoopNarrator narrator;

  StageVerdicts(ReviewStore reviewStore, ReviewLanes lanes, LoopNarrator narrator) {
    this.reviewStore = reviewStore;
    this.lanes = lanes;
    this.narrator = narrator;
  }

  /** How an agent stage ended: gate verdicts are review outcomes; errors are infrastructure. */
  sealed interface StageOutcome {
    record Passed() implements StageOutcome {}

    record GateFailed() implements StageOutcome {}

    record Errored(String message) implements StageOutcome {}
  }

  /** Closes {@code stage} failed for {@code message}: an infrastructure error, not a verdict. */
  StageOutcome errored(ReviewStore.StageRow stage, String message) {
    reviewStore.completeStage(stage.id(), "failed", message);
    return new StageOutcome.Errored(message);
  }

  /**
   * Judges a stage on what its reviewer said: the findings parsed from that run's own log, applied
   * with the reviewer's rulings on the findings the stage carries, then weighed against the stage's
   * gate.
   */
  StageOutcome read(ReviewStore.StageRow stage, StageConfig stageConfig, RunStore.RunRow run) {
    try {
      var parseResult = FindingParser.parse(lanes.output(run));
      if (parseResult instanceof FindingParser.ParseResult.Unparseable unparseable) {
        return errored(
            stage, "reviewer output unparseable: " + String.join("; ", unparseable.warnings()));
      }
      var parsed = (FindingParser.ParseResult.Parsed) parseResult;
      var carried = reviewStore.carryForwardFindings(run.specId(), stage.reviewId(), stage.name());
      applyStageResult(run.specId(), stage.id(), carried, parsed);

      var findings = reviewStore.findingsForStage(stage.id());
      var passed = stageConfig.gate().passes(findings);

      reviewStore.completeStage(stage.id(), passed ? "passed" : "failed");
      narrator.publishEvent(
          run.project(),
          run.specId(),
          passed ? "review_stage_passed" : "review_stage_failed",
          stage.name(),
          ReviewNarration.severityCounts(findings));

      return passed ? new StageOutcome.Passed() : new StageOutcome.GateFailed();
    } catch (Exception e) {
      System.err.println(
          "review-pipeline: agent stage '" + stage.name() + "' errored: " + e.getMessage());
      return errored(stage, e.getMessage());
    }
  }

  /**
   * Applies the reviewer's complete result — verdicts and new findings — as one atomic write,
   * fail-closed on both axes. Verdicts: {@code fixed} and {@code disputed} (both evidence-backed,
   * enforced by {@link FindingParser#reconcile}) resolve the predecessor row with the evidence
   * recorded; everything else — {@code still_open}, a missing verdict, a ruling without evidence —
   * re-attaches the finding to this stage as a carried row, so it keeps aging and keeps facing the
   * gate. A reviewer that stops mentioning last iteration's finding launders nothing. A finding a
   * human resolved while the stage ran keeps that resolution, and the room is told the ruling on it
   * was set aside — whether it was resolved before the reviewer's run ended, and so is no longer
   * among the findings this stage carries, or in the moment between. Atomicity: if any new finding
   * fails to insert, the resolutions roll back too, so an errored stage retries with every carried
   * finding still {@code OPEN} instead of silently retired.
   */
  private void applyStageResult(
      String specId,
      String stageId,
      List<Finding> carried,
      FindingParser.ParseResult.Parsed parsed) {
    var reconciled = FindingParser.reconcile(carried, parsed.verdicts());
    warn(reconciled.warnings());
    var resolvedMeanwhile =
        reconciled.unmatchedVerdictIds().stream().filter(this::resolvedFinding).count();
    var misaddressed = reconciled.unmatchedVerdictIds().size() - resolvedMeanwhile;
    if (misaddressed > 0) {
      narrator.postRoom(specId, ReviewNarration.misaddressedVerdicts((int) misaddressed));
    }
    var rulings =
        carried.stream()
            .map(
                finding -> {
                  var verdict = reconciled.rulings().get(finding.id());
                  return new ReviewStore.StageRuling(
                      finding, resolutionOf(verdict.ruling()), verdict.evidence());
                })
            .toList();
    var setAside =
        reviewStore.applyStageResult(stageId, rulings, parsed.findings()).size()
            + (int) resolvedMeanwhile;
    if (setAside > 0) {
      narrator.postRoom(specId, ReviewNarration.alreadyResolved(setAside));
    }
  }

  /**
   * Whether {@code findingId} names a finding someone resolved: the reviewer was asked to rule on
   * it when its stage began, and it left the carried set before the reviewer answered.
   */
  private boolean resolvedFinding(String findingId) {
    return reviewStore
        .findFinding(findingId)
        .filter(finding -> finding.resolution() != Finding.Resolution.OPEN)
        .isPresent();
  }

  private static Finding.Resolution resolutionOf(FindingParser.Ruling ruling) {
    return switch (ruling) {
      case FIXED -> Finding.Resolution.FIXED;
      case DISPUTED -> Finding.Resolution.DISPUTED;
      case STILL_OPEN -> Finding.Resolution.OPEN;
    };
  }

  private static void warn(List<String> warnings) {
    warnings.forEach(warning -> System.err.println("review-pipeline: " + warning));
  }
}
