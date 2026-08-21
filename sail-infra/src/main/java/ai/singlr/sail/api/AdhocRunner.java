/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.api;

import ai.singlr.sail.common.DateTimeUtils;
import ai.singlr.sail.common.Strings;
import ai.singlr.sail.config.Lane;
import ai.singlr.sail.config.SailYaml;
import ai.singlr.sail.engine.AgentCli;
import ai.singlr.sail.engine.AgentUnit;
import ai.singlr.sail.store.RunStore;
import java.util.List;
import java.util.Optional;

/**
 * The ad-hoc lane — {@code sail agent run --task}: launch one operator-supplied agent session as a
 * first-class run. No spec, so no policy — the session reserves the whole container through the
 * same {@link RunReservation} the dispatch lane uses, so an ad-hoc run and a dispatched agent are
 * mutually exclusive by the one atomic mechanism. A thin lane over the three launch seams: reserve
 * through {@link RunReservation}, launch through {@link RunLauncher}, and release the reservation
 * through {@link RunReservation} if the launch fails.
 */
public final class AdhocRunner {

  private final ProjectLoader projects;
  private final RunLauncher runLauncher;
  private final RunReservation runReservation;
  private final RunStore runStore;
  private final DispatchOperations.Listener listener;

  public AdhocRunner(
      ProjectLoader projects,
      RunLauncher runLauncher,
      RunReservation runReservation,
      RunStore runStore,
      DispatchOperations.Listener listener) {
    this.projects = projects;
    this.runLauncher = runLauncher;
    this.runReservation = runReservation;
    this.runStore = runStore;
    this.listener = listener;
  }

  /**
   * Launches one ad-hoc agent session as a first-class run: a minted run id, {@code role='adhoc'}
   * with no spec, a run-scoped unit and file set, and a whole-container reservation through the
   * same transaction that gates dispatches. Background launches get the same run-addressed
   * guardrail watcher as dispatches. No spec means no policy: unlike dispatch, a blank node handle
   * is allowed — the reservation is stamped with whatever identity the box has. The {@code
   * preparer} runs strictly after the reservation is won and before anything is staged or launched,
   * so a refused launch leaves the workspace untouched. A dry run mints the id and announces the
   * launch command but reserves, prepares, writes, and executes nothing.
   */
  public DispatchOperations.AdhocSession start(
      String project,
      DispatchOperations.AdhocRequest request,
      String localHandle,
      DispatchOperations.AdhocPreparer preparer) {
    var loaded = projects.loadRunning(project);
    var config = loaded.config();
    var agentType =
        config.agent() != null ? config.agent().type() : AgentCli.CLAUDE_CODE.yamlName();
    var runId = DateTimeUtils.newId().toString();
    var unit = AgentUnit.forRun(runId);
    var background = request.background();
    var workDir = adhocWorkDir(config, request.path());
    var fullPermissions = adhocFullPermissions(config);
    if (request.dryRun()) {
      listener.launching(
          background,
          RunLauncher.launchCommand(
              new RunLauncher.LaunchSpec(
                  project,
                  config,
                  workDir,
                  fullPermissions,
                  null,
                  null,
                  "",
                  agentType,
                  request.task(),
                  request.branch(),
                  List.of(),
                  background,
                  unit,
                  runId,
                  null,
                  Lane.ADHOC.wire(),
                  null)));
      return new DispatchOperations.AdhocSession(runId, null, null, Optional.empty());
    }
    var credential =
        reserveAdhocRun(
            runId, project, localHandle, agentType, request.branch(), request.task(), unit, config);
    try {
      prepare(preparer);
      var launch =
          runLauncher.launchSession(
              new RunLauncher.LaunchSpec(
                  project,
                  config,
                  workDir,
                  fullPermissions,
                  null,
                  null,
                  "",
                  agentType,
                  request.task(),
                  request.branch(),
                  List.of(),
                  background,
                  unit,
                  runId,
                  credential,
                  Lane.ADHOC.wire(),
                  null));
      var status =
          runLauncher.finishLaunch(
              new RunLauncher.RunContext(
                  project, unit, runId, null, agentType, Lane.ADHOC.wire(), background),
              launch);
      return new DispatchOperations.AdhocSession(
          runId, status, background ? null : launch.exitCode(), launch.watcher());
    } catch (RuntimeException e) {
      runReservation.releaseIfAbsent(runId, project, unit);
      throw e;
    }
  }

  private String reserveAdhocRun(
      String runId,
      String project,
      String node,
      String agentType,
      String branch,
      String task,
      AgentUnit unit,
      SailYaml config) {
    if (runStore == null) {
      throw new ApiException(
          ErrorCode.COMMAND_FAILED,
          "This box keeps no run aggregate, so an agent session cannot be reserved or tracked.");
    }
    return runReservation.reserve(
        runId,
        project,
        "",
        node,
        node,
        Lane.ADHOC.wire(),
        List.of(),
        agentType,
        branch,
        task,
        unit,
        config);
  }

  private static String adhocWorkDir(SailYaml config, String path) {
    var workDir = "/home/" + config.sshUser() + "/workspace";
    return Strings.isBlank(path) ? workDir : workDir + "/" + path;
  }

  private static boolean adhocFullPermissions(SailYaml config) {
    return config.agent() != null
        && config.agent().config() != null
        && "full".equals(config.agent().config().get("permissions"));
  }

  private static void prepare(DispatchOperations.AdhocPreparer preparer) {
    try {
      preparer.prepare();
    } catch (Exception e) {
      throw new ApiException(
          ErrorCode.AGENT_LAUNCH_FAILED, "Failed to prepare the agent session.", e);
    }
  }
}
