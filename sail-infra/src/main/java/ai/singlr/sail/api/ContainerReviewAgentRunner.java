/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.api;

import ai.singlr.sail.engine.AgentCli;
import ai.singlr.sail.engine.AgentSession;
import ai.singlr.sail.engine.AgentUnit;
import ai.singlr.sail.engine.ClaudeCodeHookConfig;
import ai.singlr.sail.engine.ContainerExec;
import ai.singlr.sail.engine.ShellExec;
import ai.singlr.sail.engine.StreamJsonResult;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Runs a review or fix agent inside the project container, sharing the dispatch agent's command
 * (streaming {@code stream-json}) but <em>not</em> its process wrapper. Dispatch launches a
 * detached {@code systemd-run --user} unit because it is fire-and-forget and watched externally; a
 * review blocks the pipeline until it has findings, so it runs as a plain foreground {@code
 * shell.exec} bounded by a generous per-invocation timeout. Blocking here needs no systemd user
 * manager or D-Bus session, so it works in any container.
 *
 * <p>Every invocation runs under its review's own {@link AgentUnit#forReview} identity: the prompt
 * and log live under {@code ~/.sail/runs/<reviewId>/}, so pipelines executing concurrently on the
 * controller's virtual-thread executor never overwrite each other's prompt or interleave output.
 * The agent's output streams to the review's {@code review.log} (appended, so one attempt's
 * reviewer↔fix negotiation accumulates in one live-followable log), and the findings are read back
 * from the bytes this run appended — parsed via {@link StreamJsonResult} so a streamed reviewer and
 * a plain one are handled uniformly.
 *
 * <p>Both lanes run the one sail-owned hooks layer every sail-launched session runs; the lane is
 * expressed entirely by the environment. Neither exports {@code SAIL_SPEC_ID}, so no completion
 * event ever re-enters the pipeline (which would recurse forever). The reviewer exports no {@code
 * SAIL_RUN_ID} either — every hook is inert, and it stays free to stop without committing. The fix
 * lane exports the run id, arming the stop gate (see {@link #runFix}), because it writes to the
 * spec branch and the commit discipline lives in the gate, not the prompt. The run credential rides
 * in as a positional shell argument — never interpolated into the script text — and lands in {@code
 * SAIL_RUN_CREDENTIAL}, so the agent's spec commands authenticate as its review run's principal.
 */
final class ContainerReviewAgentRunner implements ReviewAgentRunner {

  private static final String WORKSPACE = ContainerExec.DEV_HOME + "/workspace";

  /**
   * How long a single review or fix invocation may run before it is reaped. Generous enough for an
   * agent reasoning over a real diff; the dispatch-level guardrail ceiling is hours, so a bounded
   * per-invocation budget is the right limit here.
   */
  private static final Duration AGENT_TIMEOUT = Duration.ofMinutes(30);

  private final ShellExec shell;
  private final AgentSession session;

  ContainerReviewAgentRunner(ShellExec shell) {
    this(shell, new AgentSession(shell));
  }

  ContainerReviewAgentRunner(ShellExec shell, AgentSession session) {
    this.shell = shell;
    this.session = session;
  }

  @Override
  public String run(
      String project, String agent, String prompt, String reviewId, String runCredential)
      throws Exception {
    return run(project, agent, prompt, reviewId, runCredential, null, null);
  }

  @Override
  public String run(
      String project,
      String agent,
      String prompt,
      String reviewId,
      String runCredential,
      String model,
      String reasoningEffort)
      throws Exception {
    var cli = AgentCli.fromYamlName(agent);
    var unit = stage(project, prompt, reviewId);
    return launch(project, cli, agent, unit, runCredential, null, model, reasoningEffort);
  }

  /**
   * The fix lane launches with the stop gate armed: {@code SAIL_RUN_ID} rides in as a positional
   * argument and the session file carries the spec's branch and repos so the gate checks exactly
   * this spec's repos. Both CLIs run the same single hooks layer they always run — the run id alone
   * is what arms the gate. {@code SAIL_SPEC_ID} stays unset: the gate's own publishes no-op and the
   * pipeline can never re-enter on the fix agent's stop.
   */
  @Override
  public String runFix(
      String project,
      String agent,
      String prompt,
      String reviewId,
      String runCredential,
      String branch,
      List<String> repos,
      String model,
      String reasoningEffort)
      throws Exception {
    var cli = AgentCli.fromYamlName(agent);
    var unit = stage(project, prompt, reviewId);
    session.writeSession(project, prompt, branch, "", agent, reviewId, repos, unit);
    return launch(project, cli, agent, unit, runCredential, reviewId, model, reasoningEffort);
  }

  private AgentUnit stage(String project, String prompt, String reviewId) throws Exception {
    var unit = AgentUnit.forReview(reviewId);
    session.ensureDirectory(project);
    session.writeTaskFile(project, prompt, unit);
    return unit;
  }

  private String launch(
      String project,
      AgentCli cli,
      String agent,
      AgentUnit unit,
      String runCredential,
      String gateRunId,
      String model,
      String reasoningEffort)
      throws Exception {
    var startOffset = logSize(project, unit);

    var agentCmd =
        cli.headlessCommand(
            unit.taskPath(),
            true,
            model,
            reasoningEffort,
            ClaudeCodeHookConfig.SETTINGS_PATH,
            true);
    var gateExport = gateRunId == null ? "" : "export SAIL_RUN_ID=\"$2\"; ";
    var command =
        "export SAIL_RUN_CREDENTIAL=\"$1\"; "
            + gateExport
            + "cd "
            + WORKSPACE
            + " && "
            + agentCmd
            + " >> "
            + unit.logPath()
            + " 2>&1";
    var args =
        new ArrayList<>(
            List.of("bash", "-lc", command, "bash", Objects.toString(runCredential, "")));
    if (gateRunId != null) {
      args.add(gateRunId);
    }
    var result = shell.exec(ContainerExec.asDevUser(project, args), null, AGENT_TIMEOUT);
    if (!result.ok()) {
      throw new ReviewAgentExecutionException(
          "Review agent '"
              + agent
              + "' exited non-zero in '"
              + project
              + "' (see "
              + unit.logPath()
              + "): "
              + result.stderr(),
          result.exitCode());
    }

    return StreamJsonResult.extract(readLogSince(project, unit, startOffset));
  }

  /**
   * A repo is rescued only when it is still checked out on the spec's branch — a repo parked
   * anywhere else (another spec's branch, or main) is not this run's to commit, and auto-committing
   * there is exactly how a shared clone gets contaminated. The commit is the rescue; the push is
   * best-effort, since the branch already has an upstream from dispatch and a network blip must not
   * fail the pipeline.
   */
  @Override
  public List<Rescue> ensureCommitted(
      String project, List<String> repos, String branch, String commitMessage) throws Exception {
    if (branch == null || branch.isBlank()) {
      return List.of();
    }
    var rescued = new ArrayList<Rescue>();
    for (var repo : repos) {
      var dir = WORKSPACE + "/" + repo;
      if (!onBranch(project, dir, branch)) {
        continue;
      }
      var porcelain = git(project, dir, "status", "--porcelain");
      if (porcelain.isBlank()) {
        continue;
      }
      git(project, dir, "add", "-A");
      git(project, dir, "commit", "-m", commitMessage);
      var push = shell.exec(ContainerExec.asDevUser(project, List.of("git", "-C", dir, "push")));
      if (!push.ok()) {
        System.err.println(
            "review-pipeline: push failed for " + dir + " on " + branch + ": " + push.stderr());
      }
      rescued.add(new Rescue(repo, changedFiles(porcelain)));
    }
    return List.copyOf(rescued);
  }

  /** Paths from {@code status --porcelain} output; a rename resolves to its new path. */
  private static List<String> changedFiles(String porcelain) {
    return porcelain
        .lines()
        .filter(line -> !line.isBlank())
        .map(line -> line.length() > 3 ? line.substring(3) : line.strip())
        .map(
            path -> {
              var arrow = path.lastIndexOf(" -> ");
              return arrow < 0 ? path : path.substring(arrow + 4);
            })
        .toList();
  }

  private boolean onBranch(String project, String dir, String branch) throws Exception {
    var result =
        shell.exec(
            ContainerExec.asDevUser(
                project, List.of("git", "-C", dir, "rev-parse", "--abbrev-ref", "HEAD")));
    return result.ok() && branch.equals(result.stdout().strip());
  }

  private String git(String project, String dir, String... args) throws Exception {
    var command = new ArrayList<>(List.of("git", "-C", dir));
    command.addAll(List.of(args));
    var result = shell.exec(ContainerExec.asDevUser(project, command));
    if (!result.ok()) {
      throw new IllegalStateException(
          "git " + args[0] + " failed in " + dir + ": " + result.stderr());
    }
    return result.stdout();
  }

  /**
   * The current run's output only: the bytes appended to the review's log since {@code
   * startOffset}. The log accumulates the attempt's whole negotiation (reviewer, then fix, then any
   * later stage), so reading from the offset keeps this invocation's findings from being mistaken
   * for an earlier one's (which would stall the loop for a plain, non-stream-json agent whose
   * output carries no per-run delimiter).
   */
  private String readLogSince(String project, AgentUnit unit, long startOffset) throws Exception {
    var result =
        shell.exec(
            ContainerExec.asDevUser(
                project, List.of("tail", "-c", "+" + (startOffset + 1), unit.logPath())));
    return result.ok() ? result.stdout() : "";
  }

  private long logSize(String project, AgentUnit unit) throws Exception {
    var result =
        shell.exec(ContainerExec.asDevUser(project, List.of("stat", "-c", "%s", unit.logPath())));
    try {
      return Long.parseLong(result.stdout().trim());
    } catch (RuntimeException e) {
      return 0;
    }
  }
}
