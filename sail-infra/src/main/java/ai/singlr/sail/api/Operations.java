/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.api;

import ai.singlr.sail.store.RunStore;
import ai.singlr.sail.store.SpecStore;
import java.util.Optional;

public interface Operations {

  /**
   * Resolves a run credential — the bearer the in-container agent lane presents over the local
   * socket — to its live run row. Empty for an unknown, revoked, or expired credential, and on
   * boxes that keep no run aggregate.
   */
  default Optional<RunStore.RunRow> runForCredential(String credential) {
    return Optional.empty();
  }

  Result<ReviewListResponse> reviewsForSpec(String specId);

  Result<ReviewDetailResponse> reviewDetail(String reviewId);

  Result<ReviewApproveResponse> approveReview(String reviewId, Actor actor);

  Result<FindingDismissResponse> dismissFinding(String reviewId, String findingId, Actor actor);

  Result<HealthResponse> health();

  /** Lists every project on this node with its container status. */
  Result<ProjectListResponse> projects();

  /** The org-wide FDE roster, sorted by handle — a member-tier read backed by the synced roster. */
  Result<FdesResponse> fdes();

  Result<ProjectResponse> project(String project);

  /** Returns the two-hop SSH target (server jump + container) for a running project. */
  Result<ConnectResponse> connect(String project);

  Result<SpecsResponse> specs(String project);

  Result<SpecResponse> spec(String project, String specId);

  Result<DispatchResponse> dispatch(
      String project, DispatchRequest request, Actor actor, String localHandle);

  /**
   * The project's agent status: every local running run (scoped by {@code localHandle} so a synced
   * foreign run is never probed on this box) plus the single-session fields the one-run common case
   * has always shown.
   */
  Result<AgentStatusResponse> agentStatus(String project, String localHandle);

  /**
   * The morning-after report for {@code project}'s agent on this box. {@code localHandle} scopes
   * the "latest run" it summarizes to a run that executed here, so a run adopted from another box
   * via sync never stands in for this box's own work.
   */
  Result<AgentReportResponse> agentReport(String project, String localHandle);

  /** Lists runs, org-wide (synced) and optionally scoped to a project and/or spec. */
  Result<RunListResponse> runs(String project, String spec);

  /** One run's metadata, including the {@code node} that executed it. */
  Result<RunDetailResponse> run(String runId);

  /**
   * Tails a run's log, but only when the run executed on this box: {@code localHandle} is compared
   * to the run's {@code node} and a mismatch returns a structured {@code run_on_other_node} refusal
   * rather than a foreign box's local file. Never tails the wrong execution's bytes.
   */
  Result<RunLogResponse> runLog(String runId, int tail, String localHandle, Actor actor);

  /** Stops a run, but only when it is executing on this box (same provenance guard as the log). */
  Result<StopRunResponse> stopRun(String runId, String localHandle, Actor actor);

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

  Result<GlobalSpecUpdatedResponse> updateGlobalSpec(
      String specId, SpecUpdateRequest request, Actor actor);

  Result<GlobalSpecDeletedResponse> deleteGlobalSpec(String specId, Actor actor);

  Result<GlobalSpecContentResponse> globalSpecContent(String specId);

  Result<GlobalSpecContentResponse> setGlobalSpecContent(
      String specId, SpecContentRequest request, Actor actor);

  Result<GlobalSpecHistoryResponse> globalSpecHistory(String specId);

  Result<GlobalSpecRestoredResponse> restoreGlobalSpec(
      String specId, SpecRestoreRequest request, Actor actor);

  Result<GlobalBoardResponse> globalBoard(String project);
}
