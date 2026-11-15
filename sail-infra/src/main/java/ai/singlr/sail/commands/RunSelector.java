/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.commands;

import ai.singlr.sail.api.HostDispatching;
import ai.singlr.sail.api.StopOperations;
import ai.singlr.sail.store.RunStore;
import java.util.Optional;
import java.util.function.Predicate;
import picocli.CommandLine.Option;

/**
 * Which of a project's runs a verb that reads, attaches to or stops a run means, when specs run at
 * once, each in its own workspace: spec {@code --spec}'s, run {@code --run} (a run of no spec, such
 * as an ad-hoc session), or, with neither, the project's newest. One selector for {@code agent
 * stop}, {@code log}, {@code status}, {@code report} and {@code attach}, and the one form every
 * banner and remedy that names one of them for a run prints ({@link #of}).
 */
public final class RunSelector {

  @Option(
      names = "--spec",
      description =
          "This spec's run: needed when the project has several live runs, as specs run at once,"
              + " each in its own workspace.")
  String specId;

  @Option(
      names = "--run",
      description =
          "This run, by its id: how a run of no spec, such as an ad-hoc session, is named.")
  String runId;

  /**
   * The run of {@code project} this selector names, of those the box {@code node} executed that
   * {@code also} picks: the newest, when whoever reads may operate it ({@link
   * HostDispatching#latestOperable}); none when there is no such run. A spec with several live runs
   * (a room turn beside its build) names no one of them, so {@code --spec} is refused naming each
   * run's {@code --run} for {@code verb} ({@link StopOperations#severalLive}).
   */
  Optional<RunStore.RunRow> resolve(
      HostDispatching dispatching,
      String verb,
      String project,
      String node,
      Predicate<RunStore.RunRow> also) {
    requireOne();
    Predicate<RunStore.RunRow> named =
        run ->
            (runId != null ? runId.equals(run.id()) : specId == null || specId.equals(run.specId()))
                && also.test(run);
    if (specId != null) {
      var live =
          dispatching.liveRuns(node).stream()
              .filter(run -> project.equals(run.project()) && named.test(run))
              .toList();
      if (live.size() > 1) {
        throw StopOperations.severalLive(verb, project, live);
      }
    }
    return dispatching.latestOperable(project, node, named);
  }

  /**
   * What a stop of {@code project} names: a run by its id, or the project's run, a spec's when
   * given.
   */
  StopOperations.Target target(String project) {
    requireOne();
    return runId != null
        ? new StopOperations.RunTarget(runId, project)
        : new StopOperations.ProjectTarget(project, specId);
  }

  /**
   * The selector that names {@code run}, as a verb's arguments after its project: {@code --spec
   * <id>} for a spec's, {@code --run <id>} for a run of no spec, so a printed command reaches it
   * however many runs are live.
   */
  public static String of(RunStore.RunRow run) {
    return run.specId() != null && !run.specId().isBlank()
        ? " --spec " + run.specId()
        : " --run " + run.id();
  }

  private void requireOne() {
    if (runId != null && specId != null) {
      throw new CliCommand.Refused("Name a run with --run or a spec with --spec, not both.");
    }
  }
}
