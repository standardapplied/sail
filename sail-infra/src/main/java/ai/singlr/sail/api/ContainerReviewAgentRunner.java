/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.api;

import ai.singlr.sail.config.Guardrails;
import ai.singlr.sail.engine.AgentCli;
import ai.singlr.sail.engine.AgentSession;
import ai.singlr.sail.engine.AgentUnit;
import ai.singlr.sail.engine.ContainerExec;
import ai.singlr.sail.engine.GuardrailChecker;
import ai.singlr.sail.engine.GuardrailChecker.GuardrailResult;
import ai.singlr.sail.engine.ShellExec;
import ai.singlr.sail.engine.StreamJsonResult;
import java.time.Instant;
import java.util.List;
import java.util.function.Supplier;

/**
 * Runs a review or fix agent inside the project container on the <em>same launch primitive as
 * dispatch</em>: a background systemd unit ({@link AgentUnit#REVIEW}) that streams stream-json to
 * {@code review.log}, so the run is live-followable ({@code sail agent log --review}) and watched,
 * instead of the old blind, non-streaming {@code shell.exec}. It blocks until the unit exits,
 * guarding it against the same {@code max_duration} + stall limits as a dispatched agent — the
 * reviewer runs clean (no hooks), so growth of its streamed log is the liveness signal — then
 * returns the agent's findings, parsed from the stream's terminal result via {@link
 * StreamJsonResult}.
 *
 * <p>Launched with an <em>empty</em> {@code SAIL_SPEC_ID}, which is what stops the reviewer's own
 * completion from re-entering the pipeline (which would recurse forever): the in-container hooks
 * self-gate on the empty id and emit nothing, and any stray event would carry a blank spec that
 * {@link ReviewPipelineController}'s filter already drops. The clock and sleeper are seams so the
 * poll loop runs deterministically and instantly under test.
 */
final class ContainerReviewAgentRunner implements ReviewAgentRunner {

  private static final String WORKSPACE = ContainerExec.DEV_HOME + "/workspace";

  /**
   * A review pass is bounded: a hard wall-clock ceiling and a stall window on log growth. Generous
   * enough for an agent reasoning over a real diff, tight enough that a hung reviewer is reaped.
   */
  private static final Guardrails REVIEW_GUARDRAILS = new Guardrails("30m", "10m", "stop");

  private static final long POLL_MILLIS = 2000;

  /** Sleep seam: production sleeps between polls; tests pass a no-op so the loop runs instantly. */
  interface Sleeper {
    void sleep(long millis) throws InterruptedException;
  }

  private final ShellExec shell;
  private final AgentSession session;
  private final Supplier<Instant> clock;
  private final Sleeper sleeper;
  private final long pollMillis;

  ContainerReviewAgentRunner(ShellExec shell) {
    this(shell, new AgentSession(shell), Instant::now, Thread::sleep, POLL_MILLIS);
  }

  ContainerReviewAgentRunner(
      ShellExec shell,
      AgentSession session,
      Supplier<Instant> clock,
      Sleeper sleeper,
      long pollMillis) {
    this.shell = shell;
    this.session = session;
    this.clock = clock;
    this.sleeper = sleeper;
    this.pollMillis = pollMillis;
  }

  @Override
  public String run(String project, String agent, String prompt) throws Exception {
    var cli = AgentCli.fromYamlName(agent);
    session.ensureDirectory(project);
    session.writeTaskFile(project, prompt, AgentUnit.REVIEW);
    session.writeSession(project, prompt, "", "", agent, AgentUnit.REVIEW);

    var launch =
        AgentSession.buildBackgroundLaunchCommand(
            project, "dev", WORKSPACE, true, cli, null, null, "", agent, AgentUnit.REVIEW);
    var launched = shell.exec(launch);
    if (!launched.ok()) {
      throw new IllegalStateException(
          "Failed to launch review agent '"
              + agent
              + "' in '"
              + project
              + "': "
              + launched.stderr());
    }

    return awaitFindings(project, agent);
  }

  private String awaitFindings(String project, String agent) throws Exception {
    var startedAt = clock.get();
    var lastProgressAt = startedAt;
    var lastSize = -1L;
    while (true) {
      var exit = session.queryExitStatus(project, AgentUnit.REVIEW);
      if (!exit.active()) {
        if (exit.exitCode() != 0) {
          throw new IllegalStateException(
              "Review agent '" + agent + "' exited " + exit.exitCode() + " in '" + project + "'");
        }
        return StreamJsonResult.extract(readLog(project));
      }

      var now = clock.get();
      var size = logSize(project);
      if (size > lastSize) {
        lastSize = size;
        lastProgressAt = now;
      }

      var trip =
          firstTrip(
              GuardrailChecker.checkDuration(startedAt, now, REVIEW_GUARDRAILS),
              GuardrailChecker.checkStall(lastProgressAt, now, REVIEW_GUARDRAILS));
      if (trip != null) {
        session.killAgent(project, AgentUnit.REVIEW);
        throw new IllegalStateException(
            "Review agent '" + agent + "' " + trip.reason() + ": " + trip.detail());
      }

      sleeper.sleep(pollMillis);
    }
  }

  private static GuardrailResult.Triggered firstTrip(GuardrailResult... results) {
    for (var r : results) {
      if (r instanceof GuardrailResult.Triggered t) {
        return t;
      }
    }
    return null;
  }

  private String readLog(String project) throws Exception {
    var result =
        shell.exec(ContainerExec.asDevUser(project, List.of("cat", AgentUnit.REVIEW.logPath())));
    return result.ok() ? result.stdout() : "";
  }

  private long logSize(String project) throws Exception {
    var result =
        shell.exec(
            ContainerExec.asDevUser(
                project, List.of("stat", "-c", "%s", AgentUnit.REVIEW.logPath())));
    try {
      return Long.parseLong(result.stdout().trim());
    } catch (RuntimeException e) {
      return 0;
    }
  }
}
