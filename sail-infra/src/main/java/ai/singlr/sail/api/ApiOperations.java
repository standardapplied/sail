/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.api;

import ai.singlr.sail.store.SpecStore;

public interface ApiOperations {

  Result<ReviewListResponse> reviewsForSpec(String specId);

  Result<ReviewDetailResponse> reviewDetail(String reviewId);

  Result<ReviewApproveResponse> approveReview(String reviewId);

  Result<FindingDismissResponse> dismissFinding(String reviewId, String findingId);

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

  Result<GlobalSpecsListResponse> globalSpecs(SpecStore.SpecFilter filter);

  Result<GlobalSpecDetailResponse> globalSpec(String specId);

  Result<GlobalSpecCreatedResponse> createGlobalSpec(SpecCreateRequest request);

  Result<GlobalSpecUpdatedResponse> updateGlobalSpec(String specId, SpecUpdateRequest request);

  Result<GlobalSpecDeletedResponse> deleteGlobalSpec(String specId);

  Result<GlobalSpecContentResponse> globalSpecContent(String specId);

  Result<GlobalSpecContentResponse> setGlobalSpecContent(String specId, SpecContentRequest request);

  Result<GlobalBoardResponse> globalBoard();
}
