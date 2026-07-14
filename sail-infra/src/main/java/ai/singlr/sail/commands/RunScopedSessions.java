/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.commands;

import ai.singlr.sail.common.Strings;
import ai.singlr.sail.engine.AgentSession;
import ai.singlr.sail.engine.AgentUnit;
import ai.singlr.sail.engine.NodeIdentity;
import ai.singlr.sail.engine.SailPaths;
import ai.singlr.sail.engine.ShellExec;
import ai.singlr.sail.store.RunStore;
import ai.singlr.sail.store.Sqlite;

/**
 * Resolves the agent session a CLI command should talk about or act on, now that a dispatched agent
 * lives under its own run-scoped identity: the newest local {@code running} run's recorded unit is
 * probed first, and the fixed ad-hoc identity ({@code sail agent start}) second, so {@code sail
 * agent status}/{@code stop} keep working across both lanes. A run with no recorded unit (a
 * pre-upgrade agent) still lives on the fixed identity, which the fallback covers.
 */
final class RunScopedSessions {

  /** The resolved session and the identity it was found on, so a stop can address it. */
  record Resolved(AgentSession.SessionInfo info, AgentUnit unit) {}

  private RunScopedSessions() {}

  static Resolved resolve(ShellExec shell, String project) throws Exception {
    var agentSession = new AgentSession(shell);
    Resolved runScoped = null;
    var run = runningLocalRun(project);
    if (run != null && Strings.isNotBlank(run.unit())) {
      var unit = AgentUnit.recorded(run.id(), run.unit());
      runScoped = new Resolved(agentSession.queryStatus(project, unit), unit);
      if (runScoped.info() != null && runScoped.info().running()) {
        return runScoped;
      }
    }
    var adHoc = new Resolved(agentSession.queryStatus(project), AgentUnit.BUILD);
    if (adHoc.info() != null || runScoped == null) {
      return adHoc;
    }
    return runScoped;
  }

  private static RunStore.RunRow runningLocalRun(String project) {
    try (var db = Sqlite.open(SailPaths.controlPlaneDb())) {
      return new RunStore(db).runningForProjectOnNode(project, NodeIdentity.handle()).orElse(null);
    } catch (Exception e) {
      return null;
    }
  }
}
