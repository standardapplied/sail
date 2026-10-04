/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.api;

import ai.singlr.sail.common.Strings;
import ai.singlr.sail.store.Finding;
import ai.singlr.sail.store.RunStore;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * What the review pipeline says in a spec's room and on its events: the verdict of each review, the
 * notes beside it, and the counts an event carries. Words only — every method is a function of the
 * rows it is handed, so what the loop decides and how it is said never tangle.
 */
final class ReviewNarration {

  private static final int FINDINGS_SHOWN = 10;
  private static final int RESCUED_FILES_SHOWN = 5;

  private ReviewNarration() {}

  /** The room verdict of a review that failed its gate, with what it leaves open and disputed. */
  static String failed(int iteration, List<Finding> open, List<Finding> disputed) {
    return "Review failed (iteration "
        + iteration
        + "): "
        + severitySummary(open)
        + "."
        + findingLines(open)
        + disputedLines(disputed);
  }

  /** The room verdict of a review that passed, naming whatever it leaves open below its gate. */
  static String passed(int iteration, List<Finding> open, List<Finding> disputed) {
    if (open.isEmpty()) {
      return "Review passed (iteration "
          + iteration
          + ")."
          + disputedLines(disputed)
          + "\nAwaiting merge.";
    }
    return "Review passed (iteration "
        + iteration
        + ") with "
        + severitySummary(open)
        + " below the gate — worth a look before merge."
        + findingLines(open)
        + disputedLines(disputed)
        + "\nAwaiting merge.";
  }

  /**
   * The room verdict a human stage opens with: the automated stages before it passed, and every
   * disputed finding the gate excluded is surfaced — argument included — before the human rules.
   * Deliberately not the passed verdict: the pipeline has not passed, so no "Awaiting merge".
   */
  static String awaitingHuman(List<Finding> disputed) {
    return "Automated review stages passed."
        + disputedLines(disputed)
        + "\nAwaiting human approval.";
  }

  /** The room line of a review handed to a person, saying why. */
  static String escalated(String reason) {
    return "Review escalated: " + reason + ".";
  }

  /**
   * The room line of a review whose launch was refused its claim: the run it waits on, by its id,
   * its lane and the spec it works.
   */
  static String waiting(RunStore.RunRow holder) {
    return "Review is waiting for run `"
        + holder.id()
        + "` (`"
        + holder.role()
        + "` of `"
        + Objects.toString(holder.specId(), "")
        + "`) to finish.";
  }

  /** The room note for a reviewer's rulings on findings someone resolved while its stage ran. */
  static String alreadyResolved(int count) {
    return "Note: the reviewer ruled on "
        + count
        + " finding"
        + (count == 1 ? "" : "s")
        + " someone resolved while the stage ran; that resolution stands and the reviewer's ruling"
        + " on it was set aside.";
  }

  /**
   * The room note for a reviewer that ruled on a finding id no carried finding holds — the
   * mis-transcribed-id signature. The verdict was dropped fail-closed and the real finding shows
   * still-open, which reads as unresolved; this says plainly that it may in fact have been
   * addressed, so a human checks the review log rather than trusting the open count. Kept off the
   * pass/fail verdict lines so it fires only when the ambiguity is real.
   */
  static String misaddressedVerdicts(int count) {
    return "Note: the reviewer ruled on "
        + count
        + " finding id"
        + (count == 1 ? "" : "s")
        + " that match no open finding on this spec (a mis-transcribed id). A finding shown"
        + " still-open below may already be addressed — check the review log before acting on it.";
  }

  /**
   * Names what each rescue swept — {@code "api (3 files: A.java, B.java, C.java)"} — capped so a
   * large sweep stays one readable notification line while still revealing debris immediately.
   */
  static String rescues(List<ReviewLanes.Rescue> rescued) {
    return rescued.stream()
        .map(
            rescue -> {
              var files = rescue.files();
              var shown = files.stream().limit(RESCUED_FILES_SHOWN).toList();
              var suffix = files.size() > shown.size() ? ", +" + (files.size() - shown.size()) : "";
              return "%s (%d file%s: %s%s)"
                  .formatted(
                      rescue.repo(),
                      files.size(),
                      files.size() == 1 ? "" : "s",
                      String.join(", ", shown),
                      suffix);
            })
        .collect(Collectors.joining(", "));
  }

  /** Finding counts keyed by lowercase severity, omitting zero severities — for event payloads. */
  static Map<String, Object> severityCounts(List<Finding> findings) {
    var counts = new LinkedHashMap<String, Object>();
    for (var severity : Finding.Severity.values()) {
      var count = findings.stream().filter(f -> f.severity() == severity).count();
      if (count > 0) {
        counts.put(severity.name().toLowerCase(Locale.ROOT), (int) count);
      }
    }
    return counts;
  }

  /**
   * The disputed findings section of a room verdict: excluded from the gate by a reviewer-ruled
   * argument, so every one is surfaced — argument included, never truncated — for the human to
   * confirm or overrule. An agent can silence a finding only by arguing it in the open, never by
   * omission, and a dispute the human cannot see is an omission.
   */
  private static String disputedLines(List<Finding> disputed) {
    if (disputed.isEmpty()) {
      return "";
    }
    var shown =
        disputed.stream()
            .map(
                finding ->
                    findingLine(finding)
                        + (Strings.isBlank(finding.resolutionEvidence())
                            ? ""
                            : " — " + finding.resolutionEvidence()))
            .collect(Collectors.joining("\n"));
    return "\nDisputed (excluded from the gate — needs your ruling):\n" + shown;
  }

  /** {@code "2 high, 1 low"} in severity order, or {@code "no findings"}. */
  private static String severitySummary(List<Finding> findings) {
    var summary =
        severityCounts(findings).entrySet().stream()
            .map(count -> count.getValue() + " " + count.getKey())
            .collect(Collectors.joining(", "));
    return summary.isEmpty() ? "no findings" : summary;
  }

  /** One line per finding, capped so a noisy review stays one readable room message. */
  private static String findingLines(List<Finding> findings) {
    if (findings.isEmpty()) {
      return "";
    }
    var shown =
        findings.stream()
            .limit(FINDINGS_SHOWN)
            .map(ReviewNarration::findingLine)
            .collect(Collectors.joining("\n"));
    var more = findings.size() - Math.min(findings.size(), FINDINGS_SHOWN);
    return "\n" + shown + (more > 0 ? "\n- +" + more + " more" : "");
  }

  private static String findingLine(Finding finding) {
    return "- ["
        + finding.severity()
        + "] "
        + finding.title()
        + (finding.file() == null ? "" : " (" + finding.file() + ":" + finding.lineStart() + ")");
  }
}
