/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.engine;

import ai.singlr.sail.config.Guardrails;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.TimeoutException;

/**
 * Checks agent guardrails: a hard wall-clock ceiling ({@code max_duration}) and an idle/stall
 * window ({@code max_idle}), measured from the agent's last progress event so a long but active
 * build is not mistaken for a hang. Git activity queries are kept for informational use by {@code
 * agent status} and {@code agent report}, not as guardrail triggers.
 */
public final class GuardrailChecker {

  private final ShellExec shell;

  public GuardrailChecker(ShellExec shell) {
    this.shell = shell;
  }

  /** Result of checking guardrails — either everything is fine, or a guardrail was triggered. */
  public sealed interface GuardrailResult {
    record Ok() implements GuardrailResult {}

    record Triggered(String reason, String detail, String action) implements GuardrailResult {}
  }

  /** Git activity snapshot for a single repo. Used by status and report, not by guardrails. */
  public record GitActivity(int commitCount, long lastCommitEpoch) {}

  /**
   * Checks the wall-clock guardrail. Returns {@link GuardrailResult.Triggered} if the agent has
   * been running longer than the configured maximum duration.
   *
   * @param containerName the Incus container name
   * @param guardrails the guardrail configuration
   * @param startedAt when the agent session started
   * @param repoPaths unused, kept for API compatibility
   */
  public GuardrailResult check(
      String containerName, Guardrails guardrails, Instant startedAt, List<String> repoPaths)
      throws IOException, InterruptedException, TimeoutException {
    return checkDuration(startedAt, Instant.now(), guardrails);
  }

  /**
   * Pure wall-clock check against an explicit {@code now}, so a caller (the dispatch watcher or the
   * review await) can drive it deterministically. Triggers once the agent has run past {@code
   * max_duration}.
   */
  public static GuardrailResult checkDuration(
      Instant startedAt, Instant now, Guardrails guardrails) {
    var maxDuration = Guardrails.parseDuration(guardrails.maxDuration());
    if (maxDuration != null) {
      var elapsed = Duration.between(startedAt, now);
      if (elapsed.compareTo(maxDuration) > 0) {
        return new GuardrailResult.Triggered(
            "max_duration",
            "Agent running for "
                + formatDuration(elapsed)
                + " (limit: "
                + guardrails.maxDuration()
                + ")",
            guardrails.action());
      }
    }
    return new GuardrailResult.Ok();
  }

  /**
   * Checks the stall guardrail: triggers when no progress event has arrived for longer than {@code
   * max_idle}. {@code lastProgressAt} is the time of the agent's most recent tool call or log
   * chunk, tracked by the watcher from the event stream — so a long but active build is not
   * flagged, only a genuinely hung agent. Returns {@link GuardrailResult.Ok} when stall detection
   * is off ({@code max_idle} unset) or no progress has been observed yet.
   */
  public static GuardrailResult checkStall(Instant lastProgressAt, Guardrails guardrails) {
    return checkStall(lastProgressAt, Instant.now(), guardrails);
  }

  /** Pure stall check against an explicit {@code now}, for deterministic callers. */
  public static GuardrailResult checkStall(
      Instant lastProgressAt, Instant now, Guardrails guardrails) {
    var maxIdle = Guardrails.parseDuration(guardrails.maxIdle());
    if (maxIdle == null || lastProgressAt == null) {
      return new GuardrailResult.Ok();
    }
    var idle = Duration.between(lastProgressAt, now);
    if (idle.compareTo(maxIdle) > 0) {
      return new GuardrailResult.Triggered(
          "stall",
          "No progress for " + formatDuration(idle) + " (limit: " + guardrails.maxIdle() + ")",
          guardrails.action());
    }
    return new GuardrailResult.Ok();
  }

  /**
   * Queries git activity for a single repo inside the container. Returns commit count since the
   * given instant and the epoch timestamp of the last commit. Used by {@code agent status} and
   * {@code agent report} for informational display.
   *
   * @param containerName the Incus container name
   * @param repoPath absolute path to the repo inside the container
   * @param since count commits after this instant
   */
  public GitActivity queryGitActivity(String containerName, String repoPath, Instant since)
      throws IOException, InterruptedException, TimeoutException {

    var logCmd =
        ContainerExec.asDevUser(
            containerName, List.of("git", "-C", repoPath, "log", "-1", "--format=%ct"));
    var logResult = shell.exec(logCmd);
    var lastCommitEpoch = 0L;
    if (logResult.ok() && !logResult.stdout().isBlank()) {
      try {
        lastCommitEpoch = Long.parseLong(logResult.stdout().trim());
      } catch (NumberFormatException ignored) {
      }
    }

    var sinceEpoch = String.valueOf(since.getEpochSecond());
    var countCmd =
        ContainerExec.asDevUser(
            containerName,
            List.of("git", "-C", repoPath, "rev-list", "--count", "--after=" + sinceEpoch, "HEAD"));
    var countResult = shell.exec(countCmd);
    var commitCount = 0;
    if (countResult.ok() && !countResult.stdout().isBlank()) {
      try {
        commitCount = Integer.parseInt(countResult.stdout().trim());
      } catch (NumberFormatException ignored) {
      }
    }

    return new GitActivity(commitCount, lastCommitEpoch);
  }

  /** Formats a duration as a human-readable string like "3h 27m" or "94m". */
  public static String formatDuration(Duration duration) {
    var hours = duration.toHours();
    var minutes = duration.toMinutesPart();
    if (hours > 0) {
      return hours + "h " + minutes + "m";
    }
    return minutes + "m";
  }
}
