/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.api;

import ai.singlr.sail.config.Lane;
import ai.singlr.sail.engine.AgentSession;
import ai.singlr.sail.identity.Actor;
import ai.singlr.sail.store.DispatchGate;
import ai.singlr.sail.store.RunStore;
import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;

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
   * Holds the place spec run {@code run} lived in for a terminal resuming its conversation there,
   * on the box whose handle is {@code node}: a run row of the full chat lane over the whole place,
   * reserved through the dispatch gate and carrying the conversation's session, so that a dispatch,
   * a reviewer, a fix and a room turn of the spec wait on it as on any holder (and are resumed by
   * its stop), and a live member never starts a second process on that session. Refused, naming the
   * run that holds it, while another run of the spec lives there. Returns the hold's run id.
   */
  String holdForTerminal(RunStore.RunRow run, String node);

  /** Ends hold {@code holdId} once its terminal's process exited with {@code exitCode}. */
  void releaseTerminal(String holdId, Integer exitCode);

  /**
   * The newest run of {@code project} the box whose handle is {@code node} executed, in any lane,
   * when whoever reads ({@link Actor#reader}) may operate it — read its log, attach to it — as
   * every log door admits it ({@link LogAdmission}): a project outside its scope is not in the
   * catalog, and a run it may not operate is refused. Empty when there is no such run.
   */
  Optional<RunStore.RunRow> latestOperable(String project, String node);

  /** {@link #latestOperable(String, String)}, of the runs in {@code lane} alone. */
  Optional<RunStore.RunRow> latestOperable(String project, String node, Lane lane);

  /** {@link #latestOperable(String, String)}, of the runs {@code which} picks alone. */
  Optional<RunStore.RunRow> latestOperable(
      String project, String node, Predicate<RunStore.RunRow> which);
}
