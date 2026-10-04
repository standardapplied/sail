/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.api;

import ai.singlr.sail.common.DateTimeUtils;
import ai.singlr.sail.common.Strings;
import ai.singlr.sail.engine.AgentUnit;
import ai.singlr.sail.engine.ContainerExec;
import ai.singlr.sail.engine.ShellExec;
import ai.singlr.sail.engine.StreamJsonResult;
import ai.singlr.sail.store.RunStore;
import java.util.ArrayList;
import java.util.List;

/**
 * The review and fix lanes: a thin lane over the launch seams every lane shares. A reviewer or a
 * fix agent is recorded as its own run that names the review it serves, launched through {@link
 * RunLauncher} as that run's systemd unit with the one hook set and environment a build runs with,
 * and watched under the project's {@code agent.review_pipeline.guardrails}. The run reserves its
 * spec's repos through the gate a dispatch passes ({@link RunReservation#reserveForReview}), so a
 * refusal — a live build of the same spec, a full chat turn over the same repos — is a launch
 * deferred before anything starts. Nothing waits on the agent: the run's stop reaches the pipeline
 * over the bus.
 */
final class ReviewLaneLauncher implements ReviewLanes {

  private final ProjectLoader projects;
  private final RunStore runStore;
  private final RunReservation runReservation;
  private final RunLauncher runLauncher;
  private final ShellExec shell;

  ReviewLaneLauncher(
      ProjectLoader projects,
      RunStore runStore,
      RunReservation runReservation,
      RunLauncher runLauncher,
      ShellExec shell) {
    this.projects = projects;
    this.runStore = runStore;
    this.runReservation = runReservation;
    this.runLauncher = runLauncher;
    this.shell = shell;
  }

  @Override
  public Launch launch(Invocation invocation, String boxHandle) {
    var project = invocation.project();
    var config = projects.loadRunning(project).config();
    var runId = DateTimeUtils.newId().toString();
    var unit = AgentUnit.forRun(runId);
    var role = invocation.lane().wire();
    String credential;
    try {
      credential =
          runReservation.reserveForReview(
              runId,
              invocation.reviewId(),
              project,
              invocation.specId(),
              boxHandle,
              invocation.lane(),
              invocation.repos(),
              invocation.agent(),
              invocation.branch(),
              invocation.task(),
              unit,
              config);
    } catch (ApiException refused) {
      if (RunReservation.heldByARun(refused)) {
        return new Launch.Deferred(refused.getMessage());
      }
      throw refused;
    }
    try {
      if (!invocation.shown().isEmpty()) {
        runStore.markDelivered(runId, invocation.shown());
      }
      var launch =
          runLauncher.launchSession(
              new RunLauncher.LaunchSpec(
                  project,
                  config,
                  ContainerExec.DEV_WORKSPACE,
                  true,
                  invocation.model(),
                  invocation.reasoningEffort(),
                  invocation.specId(),
                  invocation.agent(),
                  invocation.task(),
                  invocation.branch(),
                  invocation.repos(),
                  true,
                  runId,
                  credential,
                  role,
                  null));
      runLauncher.finishLaunch(
          new RunLauncher.RunContext(
              project, unit, runId, invocation.specId(), invocation.agent(), role, true),
          launch);
      return new Launch.Started(runId);
    } catch (RuntimeException e) {
      runReservation.releaseIfAbsent(runId, project, unit);
      throw e;
    }
  }

  @Override
  public String output(RunStore.RunRow run) throws Exception {
    var log =
        shell.exec(
            ContainerExec.asDevUser(
                run.project(), List.of("cat", AgentUnit.readableLogPath(run.id(), run.logPath()))));
    return log.ok() ? StreamJsonResult.extract(log.stdout()) : "";
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
    if (Strings.isBlank(branch)) {
      return List.of();
    }
    var rescued = new ArrayList<Rescue>();
    for (var repo : repos) {
      var dir = ContainerExec.DEV_WORKSPACE + "/" + repo;
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
}
