/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.api;

public interface ApiOperations {
  Result<HealthResponse> health();

  Result<ProjectResponse> project(String project);

  Result<SpecsResponse> specs(String project);

  Result<SpecResponse> spec(String project, String specId);

  Result<SpecSyncResponse> specSyncStatus(String project);

  Result<SpecSyncResponse> specSync(String project, SpecSyncRequest request);

  Result<DispatchResponse> dispatch(String project, DispatchRequest request);

  Result<AgentStatusResponse> agentStatus(String project);

  Result<AgentLogResponse> agentLog(String project, int tail);

  Result<StopAgentResponse> stopAgent(String project);

  Result<AgentReportResponse> agentReport(String project);

  /** Publishes an event onto the bus and returns the stamped copy. */
  Result<EventPublishResponse> publishEvent(Event event);

  /** Returns up to {@code limit} most-recent events (oldest first). */
  Result<RecentEventsResponse> recentEvents(int limit);

  /** Returns per-subscriber + bus stats for {@code /v1/events/stats}. */
  Result<EventBusStatsResponse> eventBusStats();
}
