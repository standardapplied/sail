/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.api;

import ai.singlr.sail.store.MessageStore;
import ai.singlr.sail.store.RunStore;
import ai.singlr.sail.store.SpecStore;
import java.util.List;
import java.util.Optional;

class TestOperations implements Operations {

  static final String RUN_CREDENTIAL = "sailrun_test";
  static final String PRINCIPAL = "claude/abc123";
  static final String OWNER = "uday";
  static final String BOX_CREDENTIAL = "sailbox_test";
  static final String BOX_HANDLE = "uday";

  @Override
  public Optional<Actor> boxActorForCredential(String credential) {
    if (!BOX_CREDENTIAL.equals(credential)) {
      return Optional.empty();
    }
    return Optional.of(new Actor(BOX_HANDLE, Role.MEMBER, Actor.Lane.CLI));
  }

  @Override
  public Optional<RunStore.RunRow> runForCredential(String credential) {
    if (!RUN_CREDENTIAL.equals(credential)) {
      return Optional.empty();
    }
    return Optional.of(
        new RunStore.RunRow(
            "run-1",
            "acme",
            "auth",
            "node-a",
            "build",
            "claude-code",
            "feat/auth",
            "task",
            null,
            null,
            "running",
            null,
            null,
            null,
            "t0",
            null,
            List.of(),
            null,
            PRINCIPAL,
            OWNER));
  }

  @Override
  public Result<HealthResponse> health() {
    return Result.success(new HealthResponse("ok"));
  }

  @Override
  public Result<ProjectListResponse> projects() {
    return Result.success(
        new ProjectListResponse(List.of(new ProjectListItemView("acme", "running"))));
  }

  @Override
  public Result<FdesResponse> fdes() {
    return Result.success(new FdesResponse(List.of()));
  }

  @Override
  public Result<ProjectResponse> project(String project) {
    return Result.success(new ProjectResponse(project, "running", null));
  }

  @Override
  public Result<ConnectResponse> connect(String project) {
    return Result.success(
        new ConnectResponse(project, "203.0.113.7", "uday", "10.171.87.10", "dev", true));
  }

  @Override
  public Result<SpecsResponse> specs(String project) {
    return Result.success(
        new SpecsResponse(
            project,
            List.of(),
            new SpecSummaryView(0, 0, 0, 0, 0),
            new BoardSummaryView(new SpecSummaryView(0, 0, 0, 0, 0), 0, 0, null)));
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
  public Result<DispatchResponse> dispatch(
      String project, DispatchRequest request, Actor actor, String localHandle) {
    return Result.success(
        new DispatchResponse(
            project,
            true,
            null,
            new DispatchedSpecView(
                request.specId(), "Spec", "in_progress", request.repos(), null, null, null, null),
            null,
            "",
            false,
            request.restart()));
  }

  @Override
  public Result<AgentStatusResponse> agentStatus(String project, String localHandle) {
    return Result.success(
        new AgentStatusResponse(project, false, null, null, null, null, null, java.util.List.of()));
  }

  @Override
  public Result<RunListResponse> runs(String project, String spec) {
    return Result.success(new RunListResponse(project, spec, List.of()));
  }

  @Override
  public Result<RunDetailResponse> run(String runId) {
    return Result.success(
        new RunDetailResponse(
            new RunView(
                runId,
                "acme",
                "auth",
                "node-a",
                "build",
                "claude-code",
                "feat/auth",
                null,
                "running",
                "t0",
                null,
                null,
                "/home/dev/.sail/runs/" + runId + "/agent.log",
                null,
                null,
                null,
                null)));
  }

  @Override
  public Result<RunLogResponse> runLog(String runId, int tail, String localHandle, Actor actor) {
    return Result.success(new RunLogResponse(runId, List.of(), null));
  }

  @Override
  public Result<StopRunResponse> stopRun(String runId, String localHandle, Actor actor) {
    return Result.success(new StopRunResponse(runId, false, null, null, false));
  }

  @Override
  public Result<AgentReportResponse> agentReport(String project, String localHandle) {
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
                null,
                0,
                List.of(),
                List.of(),
                null,
                null,
                "",
                "",
                null),
            null,
            null,
            0,
            null));
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
                null,
                3,
                List.of(),
                List.of(),
                null,
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
                null,
                0,
                List.of(),
                List.of(),
                null,
                null,
                "",
                "",
                null)));
  }

  @Override
  public Result<GlobalSpecUpdatedResponse> updateGlobalSpec(
      String specId, SpecUpdateRequest request, Actor actor) {
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
                null,
                0,
                List.of(),
                List.of(),
                null,
                null,
                "",
                "",
                null)));
  }

  @Override
  public Result<GlobalSpecDeletedResponse> deleteGlobalSpec(String specId, Actor actor) {
    return Result.success(new GlobalSpecDeletedResponse(specId));
  }

  @Override
  public Result<GlobalSpecContentResponse> globalSpecContent(String specId) {
    return Result.success(new GlobalSpecContentResponse(specId, "", ""));
  }

  @Override
  public Result<GlobalSpecContentResponse> setGlobalSpecContent(
      String specId, SpecContentRequest request, Actor actor) {
    return Result.success(new GlobalSpecContentResponse(specId, request.body(), request.plan()));
  }

  @Override
  public Result<SpecMessageResponse> postSpecMessage(
      String specId, SpecMessageRequest request, Actor actor, String author) {
    return Result.success(
        new SpecMessageResponse(
            SpecMessageView.from(
                new MessageStore.MessageRow(
                    "01900000-0000-7000-8000-000000000001",
                    specId,
                    author,
                    request.body(),
                    request.replyTo(),
                    "2026-07-28T00:00:00Z",
                    "1-a",
                    null))));
  }

  @Override
  public Result<SpecMessagesResponse> specMessages(
      String specId, String before, String after, int limit) {
    return Result.success(
        new SpecMessagesResponse(
            specId,
            List.of(
                new SpecMessageView(
                    "01900000-0000-7000-8000-000000000001",
                    specId,
                    PRINCIPAL,
                    "hello",
                    null,
                    "2026-07-28T00:00:00Z"))));
  }

  @Override
  public Result<RunInboxResponse> runInbox(String runId) {
    return Result.success(
        new RunInboxResponse(
            runId,
            "auth",
            List.of(
                new SpecMessageView(
                    "01900000-0000-7000-8000-000000000002",
                    "auth",
                    "uday",
                    "please also update the docs",
                    null,
                    "2026-07-28T00:01:00Z")),
            false));
  }

  @Override
  public Result<RunAckResponse> ackRunMessages(String runId, List<String> delivered) {
    return Result.success(new RunAckResponse(runId, delivered.size()));
  }

  @Override
  public Result<RunSessionResponse> recordRunSession(
      String runId, String sessionId, String source, String transcriptPath) {
    return Result.success(new RunSessionResponse(runId, sessionId, source));
  }

  @Override
  public Result<GlobalSpecHistoryResponse> globalSpecHistory(String specId) {
    return Result.success(new GlobalSpecHistoryResponse(specId, java.util.List.of()));
  }

  @Override
  public Result<GlobalSpecRestoredResponse> restoreGlobalSpec(
      String specId, SpecRestoreRequest request, Actor actor) {
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
        new GlobalBoardResponse(new SpecStore.BoardSummary(0, 0, 0, 0, 0, 0, 0, 0, null), 0));
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
  public Result<ReviewApproveResponse> approveReview(String reviewId, Actor actor) {
    return Result.success(new ReviewApproveResponse(reviewId, true));
  }

  @Override
  public Result<FindingDismissResponse> dismissFinding(
      String reviewId, String findingId, Actor actor) {
    return Result.success(new FindingDismissResponse(findingId, true));
  }
}
