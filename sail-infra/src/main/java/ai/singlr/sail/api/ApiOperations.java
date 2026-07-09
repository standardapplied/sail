/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.api;

import ai.singlr.sail.store.SpecStore;

public interface ApiOperations {

  Result<ReviewListResponse> reviewsForSpec(String specId);

  Result<ReviewDetailResponse> reviewDetail(String reviewId);

  Result<ReviewApproveResponse> approveReview(String reviewId, String actor);

  Result<FindingDismissResponse> dismissFinding(String reviewId, String findingId);

  Result<HealthResponse> health();

  /** Lists every project on this node with its container status. */
  Result<ProjectListResponse> projects();

  Result<ProjectResponse> project(String project);

  /** Returns the two-hop SSH target (server jump + container) for a running project. */
  Result<ConnectResponse> connect(String project);

  Result<SpecsResponse> specs(String project);

  Result<SpecResponse> spec(String project, String specId);

  Result<DispatchResponse> dispatch(
      String project, DispatchRequest request, Actor actor, String localHandle);

  Result<AgentStatusResponse> agentStatus(String project);

  Result<AgentReportResponse> agentReport(String project);

  /** Lists runs, org-wide (synced) and optionally scoped to a project and/or spec. */
  Result<RunListResponse> runs(String project, String spec);

  /** One run's metadata, including the {@code node} that executed it. */
  Result<RunDetailResponse> run(String runId);

  /**
   * Tails a run's log, but only when the run executed on this box: {@code localHandle} is compared
   * to the run's {@code node} and a mismatch returns a structured {@code run_on_other_node} refusal
   * rather than a foreign box's local file. Never tails the wrong execution's bytes.
   */
  Result<RunLogResponse> runLog(String runId, int tail, String localHandle);

  /** Stops a run, but only when it is executing on this box (same provenance guard as the log). */
  Result<StopRunResponse> stopRun(String runId, String localHandle);

  /** Publishes an event onto the bus and returns the stamped copy. */
  Result<EventPublishResponse> publishEvent(Event event);

  /** Returns up to {@code limit} most-recent events (oldest first). */
  Result<RecentEventsResponse> recentEvents(int limit);

  /** Returns per-subscriber + bus stats for {@code /v1/events/stats}. */
  Result<EventBusStatsResponse> eventBusStats();

  Result<GlobalSpecsListResponse> globalSpecs(SpecStore.SpecFilter filter);

  Result<GlobalSpecDetailResponse> globalSpec(String specId);

  Result<GlobalSpecCreatedResponse> createGlobalSpec(SpecCreateRequest request);

  /** Drafts a follow-up spec from the open findings of a spec's latest non-superseded review. */
  Result<FollowupSpecResponse> createFollowupSpec(String specId, FollowupCreateRequest request);

  Result<GlobalSpecUpdatedResponse> updateGlobalSpec(String specId, SpecUpdateRequest request);

  Result<GlobalSpecDeletedResponse> deleteGlobalSpec(String specId);

  Result<GlobalSpecContentResponse> globalSpecContent(String specId);

  Result<GlobalSpecContentResponse> setGlobalSpecContent(String specId, SpecContentRequest request);

  Result<GlobalSpecHistoryResponse> globalSpecHistory(String specId);

  Result<GlobalSpecRestoredResponse> restoreGlobalSpec(String specId, SpecRestoreRequest request);

  Result<GlobalBoardResponse> globalBoard(String project);
}
