/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.api;

import ai.singlr.sail.engine.AgentSession;
import ai.singlr.sail.store.DispatchGate;
import ai.singlr.sail.store.RunStore;
import java.util.List;
import java.util.Optional;

/** Running and stopping agents from the host, and reading the runs that gate it. */
public interface HostDispatching {
  DispatchOperations.Outcome dispatch(
      String project, DispatchOperations.Request request, Actor actor, String localHandle);

  DispatchOperations.AdhocSession startAdhoc(
      String project, DispatchOperations.AdhocRequest request, String localHandle);

  DispatchOperations.AdhocSession startAdhoc(
      String project,
      DispatchOperations.AdhocRequest request,
      String localHandle,
      DispatchOperations.AdhocPreparer preparer);

  StopOperations.Outcome stop(
      StopOperations.Target target, Actor actor, String localHandle, boolean dryRun);

  Optional<RunStore.RunRow> latestRun(String project, String node);

  Optional<RunStore.RunRow> activeRun(String project, String node);

  List<DispatchGate.RunningRun> runningRuns(String project, String node);

  AgentSession.SessionInfo projectSession(String project, String node) throws Exception;

  String reviewLog(String project, String node);
}
