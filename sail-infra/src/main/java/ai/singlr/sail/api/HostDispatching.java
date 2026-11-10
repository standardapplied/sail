/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.api;

import ai.singlr.sail.config.Lane;
import ai.singlr.sail.engine.AgentSession;
import ai.singlr.sail.store.DispatchGate;
import ai.singlr.sail.store.RunStore;
import java.util.List;
import java.util.Optional;

/** Running and stopping agents from the host, and reading the runs that gate it. */
public interface HostDispatching {
  DispatchOperations.Outcome dispatch(
      String project, DispatchOperations.Request request, String localHandle);

  DispatchOperations.AdhocSession startAdhoc(
      String project, DispatchOperations.AdhocRequest request, String localHandle);

  DispatchOperations.AdhocSession startAdhoc(
      String project,
      DispatchOperations.AdhocRequest request,
      String localHandle,
      DispatchOperations.AdhocPreparer preparer);

  StopOperations.Outcome stop(StopOperations.Target target, String localHandle, boolean dryRun);

  Optional<RunStore.RunRow> latestRun(String project, String node);

  Optional<RunStore.RunRow> activeRun(String project, String node);

  List<DispatchGate.RunningRun> runningRuns(String project, String node);

  /** Every run the box whose handle is {@code node} executes that has not finished. */
  List<RunStore.RunRow> liveRuns(String node);

  AgentSession.SessionInfo projectSession(String project, String node) throws Exception;

  /**
   * Ends the live member whose run is {@code runId}, so a terminal can take its conversation.
   * Returns whether the run is over; a run that is not a live member's answers {@code false}.
   */
  boolean endLiveMember(String runId, String why);

  /**
   * The newest run of {@code project} the box whose handle is {@code node} executed, in {@code
   * lane} when one is asked, when the bound actor may read its log as every log door admits it
   * ({@link LogAdmission}): a project outside its scope is not in the catalog, and a run it may not
   * operate is refused. Empty when there is no such run.
   */
  Optional<RunStore.RunRow> latestLog(String project, String node, Optional<Lane> lane);
}
