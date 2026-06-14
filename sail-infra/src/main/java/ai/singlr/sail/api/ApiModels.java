/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.api;

import ai.singlr.sail.common.Strings;
import ai.singlr.sail.store.ChangeLog;
import ai.singlr.sail.store.ReviewStore;
import ai.singlr.sail.store.SessionStore;
import ai.singlr.sail.store.SpecStore;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

record HealthResponse(String status) implements Mappable {
  @Override
  public Map<String, Object> toMap() {
    var m = new LinkedHashMap<String, Object>();
    m.put("status", status);
    return m;
  }
}

record ProjectResponse(String name, String containerStatus, AgentConfigView agent)
    implements Mappable {
  @Override
  public Map<String, Object> toMap() {
    var m = new LinkedHashMap<String, Object>();
    m.put("name", name);
    m.put("container_status", containerStatus);
    m.put("agent", agent);
    return m;
  }
}

record AgentConfigView(String type, boolean autoSnapshot, boolean autoBranch, String specsDir)
    implements Mappable {
  @Override
  public Map<String, Object> toMap() {
    var m = new LinkedHashMap<String, Object>();
    m.put("type", type);
    m.put("auto_snapshot", autoSnapshot);
    m.put("auto_branch", autoBranch);
    m.put("specs_dir", specsDir);
    return m;
  }
}

record SpecsResponse(
    String name, List<SpecView> specs, SpecSummaryView counts, BoardSummaryView summary)
    implements Mappable {
  @Override
  public Map<String, Object> toMap() {
    var m = new LinkedHashMap<String, Object>();
    m.put("name", name);
    m.put("specs", specs);
    m.put("counts", counts);
    m.put("summary", summary);
    return m;
  }
}

record SpecResponse(
    String name, SpecView spec, String specPath, boolean contentAvailable, String content)
    implements Mappable {
  @Override
  public Map<String, Object> toMap() {
    var m = new LinkedHashMap<String, Object>();
    m.put("name", name);
    m.put("spec", spec);
    m.put("spec_path", specPath);
    m.put("content_available", contentAvailable);
    m.put("content", content);
    return m;
  }
}

record DispatchRequest(String specId, String mode, boolean dryRun, List<String> repos) {
  DispatchRequest(String specId, String mode, boolean dryRun) {
    this(specId, mode, dryRun, List.of());
  }

  DispatchRequest {
    mode = Strings.isBlank(mode) ? "background" : mode;
    repos = repos == null ? List.of() : List.copyOf(repos);
  }
}

record DispatchResponse(
    String name,
    boolean dispatched,
    String reason,
    DispatchedSpecView spec,
    AgentStatusView agent,
    String snapshot,
    boolean branchCreated)
    implements Mappable {
  @Override
  public Map<String, Object> toMap() {
    var m = new LinkedHashMap<String, Object>();
    m.put("name", name);
    m.put("dispatched", dispatched);
    m.put("reason", reason);
    m.put("spec", spec);
    m.put("agent", agent);
    m.put("snapshot", snapshot);
    m.put("branch_created", branchCreated);
    return m;
  }
}

record AgentStatusResponse(
    String name,
    boolean agentRunning,
    Integer pid,
    String task,
    String startedAt,
    String branch,
    String logPath)
    implements Mappable {
  @Override
  public Map<String, Object> toMap() {
    var m = new LinkedHashMap<String, Object>();
    m.put("name", name);
    m.put("agent_running", agentRunning);
    m.put("pid", pid);
    m.put("task", task);
    m.put("started_at", startedAt);
    m.put("branch", branch);
    m.put("log_path", logPath);
    return m;
  }
}

record AgentLogResponse(String name, List<String> lines, String error) implements Mappable {
  @Override
  public Map<String, Object> toMap() {
    var m = new LinkedHashMap<String, Object>();
    m.put("name", name);
    m.put("lines", lines);
    m.put("error", error);
    return m;
  }
}

record StopAgentResponse(String name, boolean stopped, String reason, Integer pid)
    implements Mappable {
  @Override
  public Map<String, Object> toMap() {
    var m = new LinkedHashMap<String, Object>();
    m.put("name", name);
    m.put("stopped", stopped);
    m.put("reason", reason);
    m.put("pid", pid);
    return m;
  }
}

record AgentReportResponse(
    String name,
    String sessionStatus,
    String startedAt,
    String endedAt,
    String duration,
    String branch,
    List<SpecView> specs,
    int commitsSinceLaunch,
    Long lastCommitMinutesAgo,
    boolean guardrailTriggered,
    String guardrailReason,
    String guardrailAction,
    boolean rolledBack,
    String rollbackSnapshot)
    implements Mappable {
  @Override
  public Map<String, Object> toMap() {
    var m = new LinkedHashMap<String, Object>();
    m.put("name", name);
    m.put("session_status", sessionStatus);
    m.put("started_at", startedAt);
    m.put("ended_at", endedAt);
    m.put("duration", duration);
    m.put("branch", branch);
    m.put("specs", specs);
    m.put("commits_since_launch", commitsSinceLaunch);
    m.put("last_commit_minutes_ago", lastCommitMinutesAgo);
    m.put("guardrail_triggered", guardrailTriggered);
    m.put("guardrail_reason", guardrailReason);
    m.put("guardrail_action", guardrailAction);
    m.put("rolled_back", rolledBack);
    m.put("rollback_snapshot", rollbackSnapshot);
    return m;
  }
}

record SpecView(
    String id,
    String title,
    String status,
    String assignee,
    List<String> dependsOn,
    List<String> repos,
    String agent,
    String model,
    String reasoningEffort,
    String branch,
    boolean ready,
    boolean blocked,
    List<String> unmetDependencies)
    implements Mappable {
  public SpecView {
    dependsOn = dependsOn == null ? List.of() : List.copyOf(dependsOn);
    repos = repos == null ? List.of() : List.copyOf(repos);
    unmetDependencies = unmetDependencies == null ? List.of() : List.copyOf(unmetDependencies);
  }

  @Override
  public Map<String, Object> toMap() {
    var m = new LinkedHashMap<String, Object>();
    m.put("id", id);
    m.put("title", title);
    m.put("status", status);
    m.put("assignee", assignee);
    m.put("depends_on", dependsOn);
    m.put("repos", repos);
    m.put("agent", agent);
    m.put("model", model);
    m.put("reasoning_effort", reasoningEffort);
    m.put("branch", branch);
    m.put("ready", ready);
    m.put("blocked", blocked);
    m.put("unmet_dependencies", unmetDependencies);
    return m;
  }
}

record DispatchedSpecView(
    String id,
    String title,
    String status,
    List<String> repos,
    String agent,
    String model,
    String reasoningEffort,
    String branch)
    implements Mappable {
  public DispatchedSpecView {
    repos = repos == null ? List.of() : List.copyOf(repos);
  }

  @Override
  public Map<String, Object> toMap() {
    var m = new LinkedHashMap<String, Object>();
    m.put("id", id);
    m.put("title", title);
    m.put("status", status);
    m.put("repos", repos);
    m.put("agent", agent);
    m.put("model", model);
    m.put("reasoning_effort", reasoningEffort);
    m.put("branch", branch);
    return m;
  }
}

record AgentStatusView(
    String type,
    String mode,
    boolean running,
    Integer pid,
    String task,
    String startedAt,
    String branch,
    String logPath)
    implements Mappable {
  @Override
  public Map<String, Object> toMap() {
    var m = new LinkedHashMap<String, Object>();
    m.put("type", type);
    m.put("mode", mode);
    m.put("running", running);
    m.put("pid", pid);
    m.put("task", task);
    m.put("started_at", startedAt);
    m.put("branch", branch);
    m.put("log_path", logPath);
    return m;
  }
}

record SpecSummaryView(int pending, int inProgress, int review, int done) implements Mappable {
  @Override
  public Map<String, Object> toMap() {
    var m = new LinkedHashMap<String, Object>();
    m.put("pending", pending);
    m.put("in_progress", inProgress);
    m.put("review", review);
    m.put("done", done);
    return m;
  }
}

record BoardSummaryView(
    SpecSummaryView counts, int readyCount, int blockedCount, String nextReadyId)
    implements Mappable {
  @Override
  public Map<String, Object> toMap() {
    var m = new LinkedHashMap<String, Object>();
    m.put("counts", counts);
    m.put("ready_count", readyCount);
    m.put("blocked_count", blockedCount);
    m.put("next_ready_id", nextReadyId);
    return m;
  }
}

record ErrorResponse(ApiError error) implements Mappable {
  @Override
  public Map<String, Object> toMap() {
    var m = new LinkedHashMap<String, Object>();
    m.put("error", error);
    return m;
  }
}

record EventPublishResponse(long id, Map<String, Object> event) implements Mappable {
  @Override
  public Map<String, Object> toMap() {
    var m = new LinkedHashMap<String, Object>();
    m.put("id", id);
    m.put("event", event);
    return m;
  }
}

record RecentEventsResponse(int limit, int returned, List<Map<String, Object>> events)
    implements Mappable {
  @Override
  public Map<String, Object> toMap() {
    var m = new LinkedHashMap<String, Object>();
    m.put("limit", limit);
    m.put("returned", returned);
    m.put("events", events);
    return m;
  }
}

record EventBusStatsResponse(
    long published, long rejectedSubscribers, List<SubscriberStatsView> subscribers)
    implements Mappable {
  @Override
  public Map<String, Object> toMap() {
    var m = new LinkedHashMap<String, Object>();
    m.put("published", published);
    m.put("rejected_subscribers", rejectedSubscribers);
    m.put("subscribers", subscribers);
    return m;
  }
}

record SubscriberStatsView(String name, int capacity, int depth, long dropped) implements Mappable {
  @Override
  public Map<String, Object> toMap() {
    var m = new LinkedHashMap<String, Object>();
    m.put("name", name);
    m.put("capacity", capacity);
    m.put("depth", depth);
    m.put("dropped", dropped);
    return m;
  }
}

record SpecCreateRequest(
    String id,
    String project,
    String title,
    String status,
    String assignee,
    String agent,
    String model,
    String reasoningEffort,
    String branch,
    int priority,
    List<String> dependsOn,
    List<String> repos,
    String body,
    String plan,
    String createdBy) {

  @SuppressWarnings("unchecked")
  static SpecCreateRequest fromMap(Map<String, Object> map) {
    return new SpecCreateRequest(
        (String) map.get("id"),
        (String) map.get("project"),
        (String) map.get("title"),
        (String) map.getOrDefault("status", "draft"),
        (String) map.get("assignee"),
        (String) map.get("agent"),
        (String) map.get("model"),
        (String) map.get("reasoning_effort"),
        (String) map.get("branch"),
        map.containsKey("priority") ? ((Number) map.get("priority")).intValue() : 0,
        map.containsKey("depends_on") ? (List<String>) map.get("depends_on") : List.of(),
        map.containsKey("repos") ? (List<String>) map.get("repos") : List.of(),
        (String) map.get("body"),
        (String) map.get("plan"),
        null);
  }

  /**
   * Returns a copy attributed to {@code actor}. {@code created_by} is set by the server from the
   * authenticated principal, never from the request body, so a client cannot forge authorship.
   */
  SpecCreateRequest withCreatedBy(String actor) {
    return new SpecCreateRequest(
        id,
        project,
        title,
        status,
        assignee,
        agent,
        model,
        reasoningEffort,
        branch,
        priority,
        dependsOn,
        repos,
        body,
        plan,
        actor);
  }
}

record SpecUpdateRequest(
    String project,
    String title,
    String status,
    String assignee,
    String agent,
    String model,
    String reasoningEffort,
    String branch,
    Integer priority,
    List<String> dependsOn,
    List<String> repos,
    String updatedBy) {

  @SuppressWarnings("unchecked")
  static SpecUpdateRequest fromMap(Map<String, Object> map) {
    return new SpecUpdateRequest(
        (String) map.get("project"),
        (String) map.get("title"),
        (String) map.get("status"),
        (String) map.get("assignee"),
        (String) map.get("agent"),
        (String) map.get("model"),
        (String) map.get("reasoning_effort"),
        (String) map.get("branch"),
        map.containsKey("priority") ? ((Number) map.get("priority")).intValue() : null,
        map.containsKey("depends_on") ? (List<String>) map.get("depends_on") : null,
        map.containsKey("repos") ? (List<String>) map.get("repos") : null,
        null);
  }

  /**
   * Returns a copy attributed to {@code actor}. {@code updated_by} is set by the server from the
   * authenticated principal, never from the request body.
   */
  SpecUpdateRequest withUpdatedBy(String actor) {
    return new SpecUpdateRequest(
        project,
        title,
        status,
        assignee,
        agent,
        model,
        reasoningEffort,
        branch,
        priority,
        dependsOn,
        repos,
        actor);
  }
}

record SpecContentRequest(String body, String plan) {
  static SpecContentRequest fromMap(Map<String, Object> map) {
    return new SpecContentRequest((String) map.get("body"), (String) map.get("plan"));
  }
}

record SpecRestoreRequest(String rev) {
  static SpecRestoreRequest fromMap(Map<String, Object> map) {
    return new SpecRestoreRequest((String) map.get("rev"));
  }
}

record SpecRevisionView(String rev, String actor, String recordedAt, String origin, boolean deleted)
    implements Mappable {
  static SpecRevisionView from(ChangeLog.Entry e) {
    return new SpecRevisionView(e.rev(), e.actor(), e.recordedAt(), e.origin(), e.deleted());
  }

  @Override
  public Map<String, Object> toMap() {
    var m = new LinkedHashMap<String, Object>();
    m.put("rev", rev);
    if (actor != null) m.put("actor", actor);
    m.put("recorded_at", recordedAt);
    m.put("origin", origin);
    m.put("deleted", deleted);
    return m;
  }
}

record GlobalSpecHistoryResponse(String specId, List<SpecRevisionView> revisions)
    implements Mappable {
  static GlobalSpecHistoryResponse from(String specId, List<ChangeLog.Entry> entries) {
    return new GlobalSpecHistoryResponse(
        specId, entries.stream().map(SpecRevisionView::from).toList());
  }

  @Override
  public Map<String, Object> toMap() {
    var m = new LinkedHashMap<String, Object>();
    m.put("spec_id", specId);
    m.put("revisions", revisions);
    m.put("total", revisions.size());
    return m;
  }
}

record GlobalSpecRestoredResponse(GlobalSpecView spec, String fromRev) implements Mappable {
  @Override
  public Map<String, Object> toMap() {
    var m = new LinkedHashMap<String, Object>();
    m.put("spec", spec.toMap());
    m.put("from_rev", fromRev);
    return m;
  }
}

record GlobalSpecView(
    String id,
    String project,
    String title,
    String status,
    String assignee,
    String agent,
    String model,
    String branch,
    int priority,
    List<String> dependsOn,
    List<String> repos,
    String createdBy,
    String createdAt,
    String updatedAt,
    String updatedBy)
    implements Mappable {
  static GlobalSpecView from(SpecStore.SpecRow row) {
    return new GlobalSpecView(
        row.id(),
        row.project(),
        row.title(),
        row.status().wire(),
        row.assignee(),
        row.agent(),
        row.model(),
        row.branch(),
        row.priority(),
        row.dependsOn(),
        row.repos(),
        row.createdBy(),
        row.createdAt(),
        row.updatedAt(),
        row.updatedBy());
  }

  @Override
  public Map<String, Object> toMap() {
    var m = new LinkedHashMap<String, Object>();
    m.put("id", id);
    m.put("project", project);
    m.put("title", title);
    m.put("status", status);
    if (assignee != null) m.put("assignee", assignee);
    if (agent != null) m.put("agent", agent);
    if (model != null) m.put("model", model);
    if (branch != null) m.put("branch", branch);
    m.put("priority", priority);
    if (!dependsOn.isEmpty()) m.put("depends_on", dependsOn);
    if (!repos.isEmpty()) m.put("repos", repos);
    if (createdBy != null) m.put("created_by", createdBy);
    m.put("created_at", createdAt);
    m.put("updated_at", updatedAt);
    if (updatedBy != null) m.put("updated_by", updatedBy);
    return m;
  }
}

record GlobalSpecsListResponse(List<GlobalSpecView> specs, int total) implements Mappable {
  @Override
  public Map<String, Object> toMap() {
    var m = new LinkedHashMap<String, Object>();
    m.put("specs", specs);
    m.put("total", total);
    return m;
  }
}

record GlobalSpecDetailResponse(GlobalSpecView spec, String body, String plan) implements Mappable {
  @Override
  public Map<String, Object> toMap() {
    var m = new LinkedHashMap<String, Object>();
    m.put("spec", spec.toMap());
    if (body != null) m.put("body", body);
    if (plan != null) m.put("plan", plan);
    return m;
  }
}

record GlobalSpecCreatedResponse(GlobalSpecView spec) implements Mappable {
  @Override
  public Map<String, Object> toMap() {
    var m = new LinkedHashMap<String, Object>();
    m.put("spec", spec.toMap());
    return m;
  }
}

record GlobalSpecUpdatedResponse(GlobalSpecView spec) implements Mappable {
  @Override
  public Map<String, Object> toMap() {
    var m = new LinkedHashMap<String, Object>();
    m.put("spec", spec.toMap());
    return m;
  }
}

record GlobalSpecDeletedResponse(String id) implements Mappable {
  @Override
  public Map<String, Object> toMap() {
    var m = new LinkedHashMap<String, Object>();
    m.put("id", id);
    m.put("deleted", true);
    return m;
  }
}

record GlobalSpecContentResponse(String specId, String body, String plan) implements Mappable {
  @Override
  public Map<String, Object> toMap() {
    var m = new LinkedHashMap<String, Object>();
    m.put("spec_id", specId);
    m.put("body", body);
    m.put("plan", plan);
    return m;
  }
}

record GlobalBoardResponse(SpecStore.BoardSummary board) implements Mappable {
  @Override
  public Map<String, Object> toMap() {
    var m = new LinkedHashMap<String, Object>();
    m.put("draft", board.draft());
    m.put("pending", board.pending());
    m.put("in_progress", board.inProgress());
    m.put("review", board.review());
    m.put("done", board.done());
    m.put("archived", board.archived());
    m.put("next_ready_id", board.nextReadyId());
    return m;
  }
}

record ReviewListResponse(String specId, List<ReviewView> reviews) implements Mappable {
  @Override
  public Map<String, Object> toMap() {
    var m = new LinkedHashMap<String, Object>();
    m.put("spec_id", specId);
    m.put("reviews", reviews);
    return m;
  }
}

record ReviewView(
    String id,
    String specId,
    int iteration,
    String status,
    String createdAt,
    String completedAt,
    String decidedBy,
    List<StageView> stages)
    implements Mappable {
  static ReviewView from(ReviewStore.ReviewRow row, List<StageView> stages) {
    return new ReviewView(
        row.id(),
        row.specId(),
        row.iteration(),
        row.status(),
        row.createdAt(),
        row.completedAt(),
        row.decidedBy(),
        stages);
  }

  @Override
  public Map<String, Object> toMap() {
    var m = new LinkedHashMap<String, Object>();
    m.put("id", id);
    m.put("spec_id", specId);
    m.put("iteration", iteration);
    m.put("status", status);
    m.put("created_at", createdAt);
    if (completedAt != null) m.put("completed_at", completedAt);
    if (decidedBy != null) m.put("decided_by", decidedBy);
    m.put("stages", stages);
    return m;
  }
}

record StageView(
    String id,
    String name,
    String stageType,
    String status,
    String reviewer,
    String startedAt,
    String completedAt,
    int findingCount)
    implements Mappable {
  static StageView from(ReviewStore.StageRow row, int findingCount) {
    return new StageView(
        row.id(),
        row.name(),
        row.stageType(),
        row.status(),
        row.reviewer(),
        row.startedAt(),
        row.completedAt(),
        findingCount);
  }

  @Override
  public Map<String, Object> toMap() {
    var m = new LinkedHashMap<String, Object>();
    m.put("id", id);
    m.put("name", name);
    m.put("stage_type", stageType);
    m.put("status", status);
    if (reviewer != null) m.put("reviewer", reviewer);
    if (startedAt != null) m.put("started_at", startedAt);
    if (completedAt != null) m.put("completed_at", completedAt);
    m.put("finding_count", findingCount);
    return m;
  }
}

record ReviewDetailResponse(ReviewView review, List<Map<String, Object>> findings)
    implements Mappable {
  @Override
  public Map<String, Object> toMap() {
    var m = new LinkedHashMap<String, Object>();
    m.put("review", review.toMap());
    m.put("findings", findings);
    return m;
  }
}

record ReviewApproveResponse(String reviewId, boolean approved) implements Mappable {
  @Override
  public Map<String, Object> toMap() {
    var m = new LinkedHashMap<String, Object>();
    m.put("review_id", reviewId);
    m.put("approved", approved);
    return m;
  }
}

record FindingDismissResponse(String findingId, boolean dismissed) implements Mappable {
  @Override
  public Map<String, Object> toMap() {
    var m = new LinkedHashMap<String, Object>();
    m.put("finding_id", findingId);
    m.put("dismissed", dismissed);
    return m;
  }
}

record SessionView(
    String id,
    String project,
    String specId,
    String agent,
    String branch,
    String task,
    Integer pid,
    String status,
    String startedAt,
    String completedAt)
    implements Mappable {
  static SessionView from(SessionStore.SessionRow row) {
    return new SessionView(
        row.id(),
        row.project(),
        row.specId(),
        row.agent(),
        row.branch(),
        row.task(),
        row.pid(),
        row.status(),
        row.startedAt(),
        row.completedAt());
  }

  @Override
  public Map<String, Object> toMap() {
    var m = new LinkedHashMap<String, Object>();
    m.put("id", id);
    m.put("project", project);
    if (specId != null) m.put("spec_id", specId);
    m.put("agent", agent);
    if (branch != null) m.put("branch", branch);
    if (task != null) m.put("task", task);
    if (pid != null) m.put("pid", pid);
    m.put("status", status);
    m.put("started_at", startedAt);
    if (completedAt != null) m.put("completed_at", completedAt);
    return m;
  }
}

record SessionListResponse(String project, List<SessionView> sessions) implements Mappable {
  @Override
  public Map<String, Object> toMap() {
    var m = new LinkedHashMap<String, Object>();
    m.put("project", project);
    m.put("sessions", sessions);
    return m;
  }
}
