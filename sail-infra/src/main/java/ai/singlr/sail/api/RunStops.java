/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.api;

import ai.singlr.sail.common.Strings;
import ai.singlr.sail.engine.HostInfo;
import java.util.LinkedHashMap;

/**
 * The authoritative stop of a run: the one {@code agent_session_stopped} that ends it, whoever saw
 * it end. The watcher publishes it when the run's unit exits or when it kills the unit for a
 * guardrail; the missed-stop reconciler publishes it for a run that ended unobserved. Both build it
 * here, so the run tracker that finishes the row and the review pipeline that routes by lane read
 * one shape.
 */
public final class RunStops {

  private RunStops() {}

  /**
   * The stop of run {@code runId}, of lane {@code role}, working {@code specId} (blank for none).
   *
   * @param source who observed the end: {@link Event.WellKnownData#SOURCE_WATCHER} or {@link
   *     Event.WellKnownData#SOURCE_RECONCILE}
   * @param exitCode the code the run's process exited with, null when nobody observed one
   * @param reason why the watcher ended the run, null when it ended on its own; a stop that carries
   *     one carries no exit code, since a run that was killed has none of its own
   */
  public static Event of(
      String source,
      String project,
      String specId,
      String agent,
      String runId,
      String role,
      Integer exitCode,
      String reason) {
    var data = new LinkedHashMap<String, Object>();
    data.put(Event.WellKnownData.SOURCE, source);
    if (Strings.isNotBlank(reason)) {
      data.put(Event.WellKnownData.REASON, reason);
    } else if (exitCode != null) {
      data.put(Event.WellKnownData.EXIT_CODE, exitCode);
    }
    if (Strings.isNotBlank(runId)) {
      data.put(Event.WellKnownData.RUN_ID, runId);
    }
    if (Strings.isNotBlank(role)) {
      data.put(Event.WellKnownData.RUN_ROLE, role);
    }
    return Event.of(
        project,
        Strings.isBlank(specId) ? null : specId,
        Event.WellKnownTypes.AGENT_SESSION_STOPPED,
        Strings.isBlank(agent) ? Event.SAIL_AGENT : agent,
        HostInfo.hostname(),
        data);
  }
}
