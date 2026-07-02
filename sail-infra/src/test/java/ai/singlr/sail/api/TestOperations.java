/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.api;

import ai.singlr.sail.store.SpecStore;
import java.util.List;

class TestOperations implements ApiOperations {

  @Override
  public Result<HealthResponse> health() {
    return Result.success(new HealthResponse("ok"));
  }

  @Override
  public Result<ProjectResponse> project(String project) {
    return Result.success(new ProjectResponse(project, "running", null));
  }

  @Override
  public Result<SpecsResponse> specs(String project) {
    return Result.success(
        new SpecsResponse(
            project,
            List.of(),
            new SpecSummaryView(0, 0, 0, 0),
            new BoardSummaryView(new SpecSummaryView(0, 0, 0, 0), 0, 0, null)));
  }

  @Override
  public Result<SpecResponse> spec(String project, String specId) {
    return Result.success(
        new SpecResponse(
            project,
            new SpecView(
                specId, "Spec", "pending", null, List.of(), List.of(), null, null, null, null, true,
                false, List.of()),
            "specs/" + specId + "/spec.md",
            true,
            "content"));
  }

  @Override
  public Result<DispatchResponse> dispatch(String project, DispatchRequest request) {
    return Result.success(
        new DispatchResponse(
            project,
            true,
            null,
            new DispatchedSpecView(
                request.specId(), "Spec", "in_progress", request.repos(), null, null, null, null),
            null,
            "",
            false));
  }

  @Override
  public Result<AgentStatusResponse> agentStatus(String project) {
    return Result.success(new AgentStatusResponse(project, false, null, null, null, null, null));
  }

  @Override
  public Result<AgentLogResponse> agentLog(String project, int tail) {
    return Result.success(new AgentLogResponse(project, List.of(), null));
  }

  @Override
  public Result<StopAgentResponse> stopAgent(String project) {
    return Result.success(new StopAgentResponse(project, false, null, null));
  }

  @Override
  public Result<AgentReportResponse> agentReport(String project) {
    return Result.success(
        new AgentReportResponse(
            project,
            "No session",
            null,
            null,
            null,
            null,
            List.of(),
            0,
            null,
            false,
            null,
            null,
            false,
            null));
  }

  @Override
  public Result<EventPublishResponse> publishEvent(Event event) {
    return Result.success(new EventPublishResponse(1L, event.toMap()));
  }

  @Override
  public Result<RecentEventsResponse> recentEvents(int limit) {
    return Result.success(new RecentEventsResponse(limit, 0, List.of()));
  }

  @Override
  public Result<EventBusStatsResponse> eventBusStats() {
    return Result.success(new EventBusStatsResponse(0L, 0L, List.of()));
  }

  @Override
  public Result<GlobalSpecsListResponse> globalSpecs(SpecStore.SpecFilter filter) {
    return Result.success(new GlobalSpecsListResponse(List.of(), 0));
  }

  @Override
  public Result<GlobalSpecDetailResponse> globalSpec(String specId) {
    return Result.success(
        new GlobalSpecDetailResponse(
            new GlobalSpecView(
                specId,
                "test-project",
                "Test",
                "pending",
                null,
                null,
                null,
                null,
                0,
                List.of(),
                List.of(),
                null,
                "",
                "",
                null),
            null,
            null,
            0));
  }

  @Override
  public Result<FollowupSpecResponse> createFollowupSpec(
      String specId, FollowupCreateRequest request) {
    return Result.success(
        new FollowupSpecResponse(
            new GlobalSpecView(
                specId + "-followup",
                "test-project",
                "Address review findings: Test",
                "draft",
                null,
                null,
                null,
                null,
                3,
                List.of(),
                List.of(),
                request.createdBy(),
                "",
                "",
                request.createdBy()),
            specId,
            "r1",
            2));
  }

  @Override
  public Result<GlobalSpecCreatedResponse> createGlobalSpec(SpecCreateRequest request) {
    return Result.success(
        new GlobalSpecCreatedResponse(
            new GlobalSpecView(
                request.id(),
                request.project(),
                request.title(),
                request.status(),
                null,
                null,
                null,
                null,
                0,
                List.of(),
                List.of(),
                null,
                "",
                "",
                null)));
  }

  @Override
  public Result<GlobalSpecUpdatedResponse> updateGlobalSpec(
      String specId, SpecUpdateRequest request) {
    return Result.success(
        new GlobalSpecUpdatedResponse(
            new GlobalSpecView(
                specId,
                "test-project",
                "Updated",
                "pending",
                null,
                null,
                null,
                null,
                0,
                List.of(),
                List.of(),
                null,
                "",
                "",
                null)));
  }

  @Override
  public Result<GlobalSpecDeletedResponse> deleteGlobalSpec(String specId) {
    return Result.success(new GlobalSpecDeletedResponse(specId));
  }

  @Override
  public Result<GlobalSpecContentResponse> globalSpecContent(String specId) {
    return Result.success(new GlobalSpecContentResponse(specId, "", ""));
  }

  @Override
  public Result<GlobalSpecContentResponse> setGlobalSpecContent(
      String specId, SpecContentRequest request) {
    return Result.success(new GlobalSpecContentResponse(specId, request.body(), request.plan()));
  }

  @Override
  public Result<GlobalSpecHistoryResponse> globalSpecHistory(String specId) {
    return Result.success(new GlobalSpecHistoryResponse(specId, java.util.List.of()));
  }

  @Override
  public Result<GlobalSpecRestoredResponse> restoreGlobalSpec(
      String specId, SpecRestoreRequest request) {
    return Result.success(
        new GlobalSpecRestoredResponse(
            GlobalSpecView.from(
                new SpecStore.SpecRow(
                    specId,
                    "proj",
                    "t",
                    ai.singlr.sail.config.SpecStatus.fromWire("pending"),
                    null,
                    null,
                    null,
                    null,
                    null,
                    0,
                    null,
                    "",
                    "",
                    null,
                    java.util.List.of(),
                    java.util.List.of())),
            request.rev()));
  }

  @Override
  public Result<GlobalBoardResponse> globalBoard(String project) {
    return Result.success(
        new GlobalBoardResponse(new SpecStore.BoardSummary(0, 0, 0, 0, 0, 0, null), 0));
  }

  @Override
  public Result<ReviewListResponse> reviewsForSpec(String specId) {
    var stage = new StageView("s1", "security", "agent", "passed", "codex", "t1", "t2", 0, null);
    var review =
        new ReviewView("r1", specId, 1, "passed", "t0", "t1", null, null, null, List.of(stage));
    return Result.success(new ReviewListResponse(specId, List.of(review)));
  }

  @Override
  public Result<ReviewDetailResponse> reviewDetail(String reviewId) {
    var stage = new StageView("s1", "security", "agent", "passed", "codex", "t1", "t2", 0, null);
    var review =
        new ReviewView(reviewId, "spec", 1, "passed", "t0", "t1", null, null, null, List.of(stage));
    return Result.success(new ReviewDetailResponse(review, List.of()));
  }

  @Override
  public Result<ReviewApproveResponse> approveReview(String reviewId, String actor) {
    return Result.success(new ReviewApproveResponse(reviewId, true));
  }

  @Override
  public Result<FindingDismissResponse> dismissFinding(String reviewId, String findingId) {
    return Result.success(new FindingDismissResponse(findingId, true));
  }

  @Override
  public Result<SessionListResponse> agentSessions(String project) {
    var session =
        new SessionView(
            "s1",
            project,
            "auth",
            "claude-code",
            "feat/auth",
            "task",
            1234,
            "running",
            "t0",
            null,
            null);
    return Result.success(new SessionListResponse(project, List.of(session)));
  }
}
