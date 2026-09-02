/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.api;

import ai.singlr.sail.common.DateTimeUtils;
import ai.singlr.sail.common.Strings;
import ai.singlr.sail.config.Roster;
import ai.singlr.sail.config.SailYaml;
import ai.singlr.sail.config.Spec;
import ai.singlr.sail.config.SpecCatalog;
import ai.singlr.sail.config.SpecStatus;
import ai.singlr.sail.engine.AgentReporter;
import ai.singlr.sail.engine.AgentSession;
import ai.singlr.sail.store.ChangeLog;
import ai.singlr.sail.store.MessageStore;
import ai.singlr.sail.store.ReviewStore;
import ai.singlr.sail.store.RoomStore;
import ai.singlr.sail.store.RunStore;
import ai.singlr.sail.store.SpecStore;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

record HealthResponse(String status) implements Mappable {
  @Override
  public Map<String, Object> toMap() {
    var m = new LinkedHashMap<String, Object>();
    m.put("status", status);
    return m;
  }
}

record WhoamiResponse(
    String fde,
    String name,
    String displayName,
    String email,
    Role role,
    List<Capability> capabilities)
    implements Mappable {
  @Override
  public Map<String, Object> toMap() {
    var m = new LinkedHashMap<String, Object>();
    m.put("fde", fde);
    m.put("name", name);
    m.put("display_name", displayName);
    m.put("email", email);
    m.put("role", role);
    m.put("capabilities", capabilities);
    return m;
  }
}

record FdeSummaryView(String handle, String displayName, String email, String role)
    implements Mappable {
  @Override
  public Map<String, Object> toMap() {
    var m = new LinkedHashMap<String, Object>();
    m.put("handle", handle);
    m.put("display_name", displayName);
    m.put("email", email);
    m.put("role", role);
    return m;
  }
}

record FdesResponse(List<FdeSummaryView> fdes) implements Mappable {
  @Override
  public Map<String, Object> toMap() {
    var m = new LinkedHashMap<String, Object>();
    m.put("fdes", fdes);
    return m;
  }
}

record ProjectListItemView(String name, String containerStatus) implements Mappable {
  @Override
  public Map<String, Object> toMap() {
    var m = new LinkedHashMap<String, Object>();
    m.put("name", name);
    m.put("container_status", containerStatus);
    return m;
  }
}

record ProjectListResponse(List<ProjectListItemView> projects) implements Mappable {
  @Override
  public Map<String, Object> toMap() {
    var m = new LinkedHashMap<String, Object>();
    m.put("projects", projects);
    m.put("total", projects.size());
    return m;
  }
}

record ConnectResponse(
    String project,
    String serverIp,
    String serverUser,
    String containerIp,
    String containerUser,
    boolean workstationKeySet)
    implements Mappable {
  @Override
  public Map<String, Object> toMap() {
    var m = new LinkedHashMap<String, Object>();
    m.put("project", project);
    m.put("server_ip", serverIp);
    m.put("server_user", serverUser);
    m.put("container_ip", containerIp);
    m.put("container_user", containerUser);
    m.put("workstation_key_set", workstationKeySet);
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

record AgentConfigView(String type, boolean autoSnapshot, boolean autoBranch) implements Mappable {
  static AgentConfigView from(SailYaml config) {
    var agent = config.agent();
    return new AgentConfigView(agent.type(), agent.autoSnapshot(), agent.autoBranch());
  }

  @Override
  public Map<String, Object> toMap() {
    var m = new LinkedHashMap<String, Object>();
    m.put("type", type);
    m.put("auto_snapshot", autoSnapshot);
    m.put("auto_branch", autoBranch);
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

record DispatchRequest(
    String specId, String mode, boolean dryRun, List<String> repos, boolean restart) {
  DispatchRequest(String specId, String mode, boolean dryRun) {
    this(specId, mode, dryRun, List.of());
  }

  DispatchRequest(String specId, String mode, boolean dryRun, List<String> repos) {
    this(specId, mode, dryRun, repos, false);
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
    boolean branchCreated,
    boolean restarted)
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
    m.put("restarted", restarted);
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
    String logPath,
    List<AgentRunView> runs)
    implements Mappable {
  static AgentStatusResponse from(
      String name, AgentSession.SessionInfo info, List<AgentRunView> runs) {
    return new AgentStatusResponse(
        name,
        info != null && info.running() || runs.stream().anyMatch(AgentRunView::running),
        info != null ? info.pid() : null,
        info != null ? info.task() : null,
        info != null ? info.startedAt() : null,
        info != null ? info.branch() : null,
        info != null ? info.logPath() : null,
        runs);
  }

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
    m.put("runs", runs.stream().map(AgentRunView::toMap).toList());
    return m;
  }
}

/** One running run of the project, probed live on its own recorded unit. */
record AgentRunView(
    String runId,
    String specId,
    String branch,
    boolean running,
    Integer pid,
    String startedAt,
    String logPath)
    implements Mappable {
  static AgentRunView from(RunStore.RunRow run, AgentSession.SessionInfo info) {
    return new AgentRunView(
        run.id(),
        run.specId(),
        run.branch(),
        info != null && info.running(),
        info != null ? info.pid() : null,
        run.startedAt(),
        run.logPath());
  }

  @Override
  public Map<String, Object> toMap() {
    var m = new LinkedHashMap<String, Object>();
    m.put("run_id", runId);
    m.put("spec_id", specId);
    m.put("branch", branch);
    m.put("running", running);
    m.put("pid", pid);
    m.put("started_at", startedAt);
    m.put("log_path", logPath);
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
  static AgentReportResponse from(AgentReporter.Report report) {
    return new AgentReportResponse(
        report.name(),
        report.sessionStatus(),
        report.startedAt(),
        report.endedAt(),
        report.duration(),
        report.branch(),
        report.specs().stream().map(spec -> SpecView.from(report.specs(), spec)).toList(),
        report.commitCount(),
        report.lastCommitMinutesAgo() >= 0 ? report.lastCommitMinutesAgo() : null,
        report.guardrailTriggered(),
        report.guardrailReason(),
        report.guardrailAction(),
        report.rolledBack(),
        report.rollbackSnapshot());
  }

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
  static SpecView from(List<Spec> specs, Spec spec) {
    return new SpecView(
        spec.id(),
        spec.title(),
        spec.status().wire(),
        spec.assignee(),
        spec.dependsOn(),
        spec.repos(),
        spec.agent(),
        spec.model(),
        spec.reasoningEffort(),
        spec.branch(),
        SpecCatalog.isReady(specs, spec),
        SpecCatalog.isBlocked(specs, spec),
        SpecCatalog.unmetDependencies(specs, spec));
  }

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
  static DispatchedSpecView from(Spec spec, String branch) {
    return new DispatchedSpecView(
        spec.id(),
        spec.title(),
        SpecStatus.IN_PROGRESS.wire(),
        spec.repos(),
        spec.agent(),
        spec.model(),
        spec.reasoningEffort(),
        Strings.isNotBlank(branch) ? branch : null);
  }

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
  static AgentStatusView from(String type, String mode, AgentSession.SessionInfo info) {
    return new AgentStatusView(
        type,
        mode,
        info != null && info.running(),
        info != null ? info.pid() : null,
        info != null ? info.task() : null,
        info != null ? info.startedAt() : null,
        info != null ? info.branch() : null,
        info != null ? info.logPath() : null);
  }

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

record SpecSummaryView(int pending, int inProgress, int review, int awaitingMerge, int done)
    implements Mappable {
  static SpecSummaryView from(Map<String, Integer> counts) {
    return new SpecSummaryView(
        counts.getOrDefault(SpecStatus.PENDING.wire(), 0),
        counts.getOrDefault(SpecStatus.IN_PROGRESS.wire(), 0),
        counts.getOrDefault(SpecStatus.REVIEW.wire(), 0),
        counts.getOrDefault(SpecStatus.AWAITING_MERGE.wire(), 0),
        counts.getOrDefault(SpecStatus.DONE.wire(), 0));
  }

  @Override
  public Map<String, Object> toMap() {
    var m = new LinkedHashMap<String, Object>();
    m.put("pending", pending);
    m.put("in_progress", inProgress);
    m.put("review", review);
    m.put("awaiting_merge", awaitingMerge);
    m.put("done", done);
    return m;
  }
}

record BoardSummaryView(
    SpecSummaryView counts, int readyCount, int blockedCount, String nextReadyId)
    implements Mappable {
  static BoardSummaryView from(SpecCatalog.Summary summary) {
    return new BoardSummaryView(
        SpecSummaryView.from(summary.counts()),
        summary.readyCount(),
        summary.blockedCount(),
        summary.nextReadyId());
  }

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

record SpecEventsResponse(
    String spec, Long since, int limit, int returned, List<Map<String, Object>> events)
    implements Mappable {
  @Override
  public Map<String, Object> toMap() {
    var m = new LinkedHashMap<String, Object>();
    m.put("spec", spec);
    if (since != null) {
      m.put("since", since);
    }
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
    String createdBy,
    String roomId) {

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
        null,
        (String) map.get("room_id"));
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
        actor,
        roomId);
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
    String wake,
    String updatedBy,
    boolean force) {

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
        (String) map.get("wake"),
        null,
        Boolean.TRUE.equals(map.get("force")));
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
        wake,
        actor,
        force);
  }
}

record FollowupCreateRequest(String id, String createdBy) {
  static FollowupCreateRequest fromMap(Map<String, Object> map) {
    return new FollowupCreateRequest((String) map.get("id"), null);
  }

  /**
   * Returns a copy attributed to {@code actor}. {@code created_by} is set by the server from the
   * authenticated principal, never from the request body, so a client cannot forge authorship.
   */
  FollowupCreateRequest withCreatedBy(String actor) {
    return new FollowupCreateRequest(id, actor);
  }
}

record FollowupSpecResponse(
    GlobalSpecView spec, String sourceSpecId, String reviewId, int findingCount)
    implements Mappable {
  @Override
  public Map<String, Object> toMap() {
    var m = new LinkedHashMap<String, Object>();
    m.put("spec", spec.toMap());
    m.put("source_spec_id", sourceSpecId);
    m.put("review_id", reviewId);
    m.put("finding_count", findingCount);
    return m;
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

/** Body of {@code POST /v1/rooms/{id}/members}: the agent to seat and the one mode choice. */
record EngageRequest(String agent, String mode, String model, boolean snapshot) {
  static EngageRequest fromMap(Map<String, Object> map) {
    return new EngageRequest(
        (String) map.get("agent"),
        (String) map.get("mode"),
        (String) map.get("model"),
        Boolean.TRUE.equals(map.get("snapshot")));
  }
}

/** Response of {@code POST /v1/rooms/{id}/members}: the recorded (or pending) membership. */
record EngageResponse(String agent, String mode, String snapshot) implements Mappable {
  @Override
  public Map<String, Object> toMap() {
    var m = new LinkedHashMap<String, Object>();
    m.put("agent", agent);
    m.put("mode", mode);
    if (snapshot != null && !snapshot.isBlank()) m.put("snapshot", snapshot);
    return m;
  }
}

/** Response of {@code POST /v1/specs/{id}/disengage}: who left, or nothing if nobody was there. */
record DisengageResponse(String agent) implements Mappable {
  @Override
  public Map<String, Object> toMap() {
    var m = new LinkedHashMap<String, Object>();
    if (agent != null) m.put("agent", agent);
    m.put("disengaged", agent != null);
    return m;
  }
}

record RoomCreateRequest(String id, String project, String title, String wake, String createdBy) {
  static RoomCreateRequest fromMap(Map<String, Object> map) {
    return new RoomCreateRequest(
        (String) map.get("id"),
        (String) map.get("project"),
        (String) map.get("title"),
        (String) map.get("wake"),
        null);
  }

  RoomCreateRequest withCreatedBy(String actor) {
    return new RoomCreateRequest(id, project, title, wake, actor);
  }
}

/** One room as the API renders it: identity, conversation state, and its attached specs. */
record RoomView(
    String id,
    String project,
    String title,
    String assignee,
    String wake,
    String effectiveWake,
    List<ai.singlr.sail.config.Engagement> members,
    List<String> specIds,
    String createdBy,
    String createdAt,
    String updatedAt,
    String updatedBy)
    implements Mappable {

  static RoomView from(RoomStore.RoomRow row, List<String> specIds) {
    var members = ai.singlr.sail.config.Roster.fromJson(row.roster()).members();
    return new RoomView(
        row.id(),
        row.project(),
        row.title(),
        row.assignee(),
        row.wake(),
        RoomWakePolicy.effectiveMode(row.wake(), members.size()),
        members,
        specIds,
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
    if (assignee != null) m.put("assignee", assignee);
    if (wake != null) m.put("wake", wake);
    m.put("effective_wake", effectiveWake);
    m.put(
        "members",
        members.stream()
            .map(
                member -> {
                  var one = new LinkedHashMap<String, Object>();
                  one.put("agent", member.agent());
                  one.put("mode", member.mode());
                  if (member.model() != null) one.put("model", member.model());
                  one.put("engaged_at", member.engagedAt());
                  return one;
                })
            .toList());
    m.put("spec_ids", specIds);
    if (createdBy != null) m.put("created_by", createdBy);
    m.put("created_at", createdAt);
    m.put("updated_at", updatedAt);
    if (updatedBy != null) m.put("updated_by", updatedBy);
    return m;
  }
}

/** Response of {@code GET /v1/rooms}: every room, decorated with conversation activity. */
record RoomsListResponse(
    List<RoomView> rooms, Map<String, String> latestByRoom, Map<String, String> openQuestions)
    implements Mappable {
  RoomsListResponse {
    latestByRoom = latestByRoom == null ? Map.of() : latestByRoom;
    openQuestions = openQuestions == null ? Map.of() : openQuestions;
  }

  @Override
  public Map<String, Object> toMap() {
    var m = new LinkedHashMap<String, Object>();
    m.put(
        "rooms",
        rooms.stream()
            .map(
                view -> {
                  var one = view.toMap();
                  var latest = latestByRoom.get(view.id());
                  if (latest != null) one.put("last_activity_at", latest);
                  var question = openQuestions.get(view.id());
                  one.put("needs_reply", question != null);
                  if (question != null) one.put("question_message_id", question);
                  return one;
                })
            .toList());
    m.put("count", rooms.size());
    return m;
  }
}

/** Response of {@code POST /v1/rooms} and {@code GET /v1/rooms/{id}}: one decorated room. */
record RoomDetailResponse(RoomView room, String lastActivityAt, String questionMessageId)
    implements Mappable {
  @Override
  public Map<String, Object> toMap() {
    var m = room.toMap();
    if (lastActivityAt != null) m.put("last_activity_at", lastActivityAt);
    m.put("needs_reply", questionMessageId != null);
    if (questionMessageId != null) m.put("question_message_id", questionMessageId);
    return m;
  }
}

/** Response of {@code DELETE /v1/rooms/{id}}: the tombstoned room. */
record RoomDeletedResponse(String id) implements Mappable {
  @Override
  public Map<String, Object> toMap() {
    var m = new LinkedHashMap<String, Object>();
    m.put("id", id);
    m.put("deleted", true);
    return m;
  }
}

/** Response of {@code GET /v1/rooms/{id}/members}: the room's roster, room-first. */
record RoomMembersResponse(List<ai.singlr.sail.config.Engagement> members) implements Mappable {
  @Override
  public Map<String, Object> toMap() {
    var m = new LinkedHashMap<String, Object>();
    m.put(
        "members",
        members.stream()
            .map(
                member -> {
                  var one = new LinkedHashMap<String, Object>();
                  one.put("agent", member.agent());
                  one.put("mode", member.mode());
                  if (member.model() != null) {
                    one.put("model", member.model());
                  }
                  one.put("engaged_at", member.engagedAt());
                  return one;
                })
            .toList());
    m.put("count", members.size());
    return m;
  }
}

/** One member mode an agent does or does not support, with the seam-declared reason when not. */
record AgentModeView(String mode, boolean supported, String reason) implements Mappable {
  @Override
  public Map<String, Object> toMap() {
    var m = new LinkedHashMap<String, Object>();
    m.put("mode", mode);
    m.put("supported", supported);
    if (reason != null) {
      m.put("reason", reason);
    }
    return m;
  }
}

/** One installable agent CLI and its member-mode support, for {@code GET /v1/agents}. */
record AgentView(String name, String displayName, List<AgentModeView> modes) implements Mappable {
  @Override
  public Map<String, Object> toMap() {
    var m = new LinkedHashMap<String, Object>();
    m.put("name", name);
    m.put("display_name", displayName);
    m.put("modes", modes);
    return m;
  }
}

record AgentsResponse(List<AgentView> agents) implements Mappable {
  @Override
  public Map<String, Object> toMap() {
    var m = new LinkedHashMap<String, Object>();
    m.put("agents", agents);
    return m;
  }
}

record SpecRevisionView(
    String rev, String actor, String recordedAt, String origin, boolean deleted, String peer)
    implements Mappable {
  static SpecRevisionView from(ChangeLog.Entry e) {
    return new SpecRevisionView(
        e.rev(), e.actor(), e.recordedAt(), e.origin(), e.deleted(), e.peer());
  }

  @Override
  public Map<String, Object> toMap() {
    var m = new LinkedHashMap<String, Object>();
    m.put("rev", rev);
    if (actor != null) m.put("actor", actor);
    m.put("recorded_at", recordedAt);
    m.put("origin", origin);
    m.put("deleted", deleted);
    if (peer != null) m.put("peer", peer);
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
    String reasoningEffort,
    String branch,
    int priority,
    List<String> dependsOn,
    List<String> repos,
    String wake,
    Map<String, Object> engagement,
    String createdBy,
    String createdAt,
    String updatedAt,
    String updatedBy,
    String roomId)
    implements Mappable {
  static GlobalSpecView from(SpecStore.SpecRow row) {
    return from(row, null);
  }

  /**
   * The view with its conversation-side fields — {@code wake} and {@code engagement} — decorated
   * from the spec's room row, their only home since the legacy spec columns retired. A null room (a
   * box without the aggregate, or a synced spec whose room has not arrived) reads as default wake
   * with nobody seated.
   */
  static GlobalSpecView from(SpecStore.SpecRow row, RoomStore.RoomRow room) {
    var spec = row.toSpec();
    return new GlobalSpecView(
        spec.id(),
        spec.project(),
        spec.title(),
        spec.status().wire(),
        spec.assignee(),
        spec.agent(),
        spec.model(),
        spec.reasoningEffort(),
        spec.branch(),
        spec.priority(),
        spec.dependsOn(),
        spec.repos(),
        room == null ? null : room.wake(),
        engagementMap(room == null ? null : Roster.fromJson(room.roster()).standing()),
        spec.createdBy(),
        spec.createdAt(),
        spec.updatedAt(),
        spec.updatedBy(),
        spec.roomId());
  }

  private static Map<String, Object> engagementMap(ai.singlr.sail.config.Engagement engagement) {
    if (engagement == null) {
      return null;
    }
    var m = new LinkedHashMap<String, Object>();
    m.put("agent", engagement.agent());
    m.put("mode", engagement.mode());
    if (engagement.model() != null) m.put("model", engagement.model());
    m.put("engaged_at", engagement.engagedAt());
    return m;
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
    if (reasoningEffort != null) m.put("reasoning_effort", reasoningEffort);
    if (branch != null) m.put("branch", branch);
    m.put("priority", priority);
    if (!dependsOn.isEmpty()) m.put("depends_on", dependsOn);
    if (!repos.isEmpty()) m.put("repos", repos);
    if (wake != null) m.put("wake", wake);
    if (engagement != null) m.put("engagement", engagement);
    if (createdBy != null) m.put("created_by", createdBy);
    m.put("created_at", createdAt);
    m.put("updated_at", updatedAt);
    if (updatedBy != null) m.put("updated_by", updatedBy);
    m.put("room_id", roomId);
    return m;
  }
}

record GlobalSpecsListResponse(
    List<GlobalSpecView> specs,
    int total,
    Map<String, String> latestMessageAt,
    Map<String, String> openQuestions)
    implements Mappable {

  GlobalSpecsListResponse {
    openQuestions = openQuestions == null ? Map.of() : openQuestions;
  }

  GlobalSpecsListResponse(List<GlobalSpecView> specs, int total) {
    this(specs, total, null, null);
  }

  /**
   * Each spec's {@code last_activity_at} = max(updated_at, its room's newest message) — the one
   * activity source {@code updated_at} cannot see. A spec whose latest agent question is still
   * unanswered additionally carries {@code needs_reply} plus that question's message id. Both are
   * emitted only when the serving box has the message store, so a skewed client can detect absence
   * and fall back.
   */
  @Override
  public Map<String, Object> toMap() {
    var m = new LinkedHashMap<String, Object>();
    if (latestMessageAt == null) {
      m.put("specs", specs);
    } else {
      m.put(
          "specs",
          specs.stream()
              .map(
                  view -> {
                    var spec = view.toMap();
                    var message = latestMessageAt.get(view.id());
                    var updated = view.updatedAt();
                    spec.put(
                        "last_activity_at",
                        message != null && message.compareTo(updated) > 0 ? message : updated);
                    var question = openQuestions.get(view.roomId());
                    if (question != null) {
                      spec.put("needs_reply", true);
                      spec.put("question_message_id", question);
                    }
                    return spec;
                  })
              .toList());
    }
    m.put("total", total);
    return m;
  }
}

record GlobalSpecDetailResponse(
    GlobalSpecView spec,
    String body,
    String plan,
    int openFindings,
    RunSummary latestRun,
    String questionMessageId)
    implements Mappable {

  GlobalSpecDetailResponse(
      GlobalSpecView spec, String body, String plan, int openFindings, RunSummary latestRun) {
    this(spec, body, plan, openFindings, latestRun, null);
  }

  @Override
  public Map<String, Object> toMap() {
    var m = new LinkedHashMap<String, Object>();
    var specMap = spec.toMap();
    if (questionMessageId != null) {
      specMap.put("needs_reply", true);
      specMap.put("question_message_id", questionMessageId);
    }
    m.put("spec", specMap);
    if (body != null) m.put("body", body);
    if (plan != null) m.put("plan", plan);
    if (openFindings > 0) m.put("open_findings", openFindings);
    if (latestRun != null) m.put("latest_run", latestRun.toMap());
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

record SpecMessageRequest(String body, String replyTo, boolean question) {
  static SpecMessageRequest fromMap(Map<String, Object> map) {
    return new SpecMessageRequest(
        map.get("body") == null ? null : map.get("body").toString(),
        map.get("reply_to") == null ? null : map.get("reply_to").toString(),
        Boolean.parseBoolean(Objects.toString(map.get("question"), "false")));
  }
}

record SpecMessageView(
    String id,
    String specId,
    String author,
    String body,
    String replyTo,
    String createdAt,
    boolean question)
    implements Mappable {
  static SpecMessageView from(MessageStore.MessageRow row) {
    return new SpecMessageView(
        row.id(),
        row.roomId(),
        row.author(),
        row.body(),
        row.replyTo(),
        row.createdAt(),
        row.question());
  }

  @Override
  public Map<String, Object> toMap() {
    var map = new LinkedHashMap<String, Object>();
    map.put("id", id);
    map.put("spec_id", specId);
    map.put("author", author);
    map.put("body", body);
    if (replyTo != null) map.put("reply_to", replyTo);
    map.put("created_at", createdAt);
    if (question) map.put("question", true);
    return map;
  }
}

record SpecMessageResponse(SpecMessageView message) implements Mappable {
  @Override
  public Map<String, Object> toMap() {
    return Map.of("message", message.toMap());
  }
}

record SpecMessagesResponse(String specId, List<SpecMessageView> messages) implements Mappable {
  @Override
  public Map<String, Object> toMap() {
    var map = new LinkedHashMap<String, Object>();
    map.put("spec_id", specId);
    map.put("messages", messages);
    map.put("total", messages.size());
    return map;
  }
}

record RunInboxResponse(
    String runId, String specId, List<SpecMessageView> messages, boolean hasMore)
    implements Mappable {
  @Override
  public Map<String, Object> toMap() {
    var map = new LinkedHashMap<String, Object>();
    map.put("run_id", runId);
    if (specId != null) {
      map.put("spec_id", specId);
    }
    map.put("messages", messages);
    map.put("has_more", hasMore);
    return map;
  }
}

record RunAckResponse(String runId, int acked) implements Mappable {
  @Override
  public Map<String, Object> toMap() {
    var map = new LinkedHashMap<String, Object>();
    map.put("run_id", runId);
    map.put("acked", acked);
    return map;
  }
}

record RoomConversationResponse(String roomId, String sessionId, String agent) implements Mappable {
  @Override
  public Map<String, Object> toMap() {
    var map = new LinkedHashMap<String, Object>();
    map.put("room_id", roomId);
    map.put("session_id", sessionId);
    if (agent != null) {
      map.put("agent", agent);
    }
    return map;
  }
}

record RunSessionResponse(String runId, String sessionId, String sessionSource)
    implements Mappable {
  @Override
  public Map<String, Object> toMap() {
    var map = new LinkedHashMap<String, Object>();
    map.put("run_id", runId);
    map.put("session_id", sessionId);
    if (sessionSource != null) {
      map.put("session_source", sessionSource);
    }
    return map;
  }
}

record GlobalBoardResponse(SpecStore.BoardSummary board, int doneOpenFindings, int needsReply)
    implements Mappable {

  GlobalBoardResponse(SpecStore.BoardSummary board, int doneOpenFindings) {
    this(board, doneOpenFindings, 0);
  }

  @Override
  public Map<String, Object> toMap() {
    var m = new LinkedHashMap<String, Object>();
    m.put(SpecStatus.DRAFT.wire(), board.draft());
    m.put(SpecStatus.PENDING.wire(), board.pending());
    m.put(SpecStatus.IN_PROGRESS.wire(), board.inProgress());
    m.put(SpecStatus.REVIEW.wire(), board.review());
    m.put(SpecStatus.AWAITING_MERGE.wire(), board.awaitingMerge());
    m.put(SpecStatus.DONE.wire(), board.done());
    m.put(SpecStatus.CANCELLED.wire(), board.cancelled());
    m.put(SpecStatus.ARCHIVED.wire(), board.archived());
    m.put("next_ready_id", board.nextReadyId());
    m.put("done_open_findings", doneOpenFindings);
    m.put("needs_reply", needsReply);
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
    String supersededAt,
    String error,
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
        row.supersededAt(),
        row.error(),
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
    if (supersededAt != null) m.put("superseded_at", supersededAt);
    if (error != null) m.put("error", error);
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
    int findingCount,
    String error)
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
        findingCount,
        row.error());
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
    if (error != null) m.put("error", error);
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

record RunView(
    String id,
    String project,
    String specId,
    String node,
    String role,
    String agent,
    String branch,
    Integer pid,
    String status,
    String startedAt,
    String completedAt,
    Integer exitCode,
    String logPath,
    String principal,
    String owner,
    String sessionId,
    String sessionSource,
    String lastActivityAt,
    String presence)
    implements Mappable {
  static RunView from(RunStore.RunRow row) {
    return new RunView(
        row.id(),
        row.project(),
        row.specId(),
        row.node(),
        row.role(),
        row.agent(),
        row.branch(),
        row.pid(),
        row.status(),
        row.startedAt(),
        row.completedAt(),
        row.exitCode(),
        row.logPath(),
        row.principal(),
        row.owner(),
        row.sessionId(),
        row.sessionSource(),
        row.lastActivityAt(),
        RunPresence.of(row.status(), row.lastActivityAt(), DateTimeUtils.now()));
  }

  @Override
  public Map<String, Object> toMap() {
    var m = new LinkedHashMap<String, Object>();
    m.put("id", id);
    m.put("project", project);
    if (specId != null) m.put("spec_id", specId);
    m.put("node", node);
    m.put("role", role);
    m.put("agent", agent);
    if (branch != null) m.put("branch", branch);
    if (pid != null) m.put("pid", pid);
    m.put("status", status);
    m.put("started_at", startedAt);
    if (completedAt != null) m.put("completed_at", completedAt);
    if (exitCode != null) m.put("exit_code", exitCode);
    if (logPath != null) m.put("log_path", logPath);
    if (principal != null) m.put("principal", principal);
    if (owner != null) m.put("owner", owner);
    if (sessionId != null) m.put("session_id", sessionId);
    if (sessionSource != null) m.put("session_source", sessionSource);
    if (lastActivityAt != null) m.put("last_activity_at", lastActivityAt);
    if (presence != null) m.put("presence", presence);
    return m;
  }
}

record RunListResponse(String project, String spec, List<RunView> runs) implements Mappable {
  @Override
  public Map<String, Object> toMap() {
    var m = new LinkedHashMap<String, Object>();
    if (project != null) m.put("project", project);
    if (spec != null) m.put("spec", spec);
    m.put("runs", runs);
    return m;
  }
}

record RunDetailResponse(RunView run) implements Mappable {
  @Override
  public Map<String, Object> toMap() {
    return run.toMap();
  }
}

record RunLogResponse(String runId, List<String> lines, String error) implements Mappable {
  @Override
  public Map<String, Object> toMap() {
    var m = new LinkedHashMap<String, Object>();
    m.put("run_id", runId);
    m.put("lines", lines);
    if (error != null) m.put("error", error);
    return m;
  }
}

record SnapshotView(String name, String createdAt, String source) implements Mappable {
  @Override
  public Map<String, Object> toMap() {
    var m = new LinkedHashMap<String, Object>();
    m.put("name", name);
    m.put("created_at", createdAt);
    m.put("source", source);
    return m;
  }
}

record SnapshotListResponse(List<SnapshotView> snapshots) implements Mappable {
  @Override
  public Map<String, Object> toMap() {
    var m = new LinkedHashMap<String, Object>();
    m.put("snapshots", snapshots);
    m.put("total", snapshots.size());
    return m;
  }
}

/**
 * The accepted receipt for an async snapshot mutation: the request returned before the mutation
 * ran, and its completion arrives as the matching {@code snapshot_restored} / {@code
 * snapshot_deleted} event carrying this {@code name}.
 */
record SnapshotActionResponse(String project, String name, String action, String status)
    implements Mappable {
  @Override
  public Map<String, Object> toMap() {
    var m = new LinkedHashMap<String, Object>();
    m.put("project", project);
    m.put("name", name);
    m.put("action", action);
    m.put("status", status);
    return m;
  }
}

record StopRunResponse(
    String runId, boolean stopped, String reason, Integer pid, boolean specCancelled)
    implements Mappable {
  @Override
  public Map<String, Object> toMap() {
    var m = new LinkedHashMap<String, Object>();
    m.put("run_id", runId);
    m.put("stopped", stopped);
    if (reason != null) m.put("reason", reason);
    if (pid != null) m.put("pid", pid);
    m.put("spec_cancelled", specCancelled);
    return m;
  }
}

/**
 * The latest run of a spec, embedded in board and spec-detail responses so a client can gate its
 * "open logs" button on provenance ({@code node}) without a second call.
 */
record RunSummary(
    String id,
    String node,
    String status,
    Integer exitCode,
    String principal,
    String owner,
    String sessionId,
    String sessionSource,
    String lastActivityAt,
    String presence)
    implements Mappable {
  static RunSummary from(RunStore.RunRow row) {
    return new RunSummary(
        row.id(),
        row.node(),
        row.status(),
        row.exitCode(),
        row.principal(),
        row.owner(),
        row.sessionId(),
        row.sessionSource(),
        row.lastActivityAt(),
        RunPresence.of(row.status(), row.lastActivityAt(), DateTimeUtils.now()));
  }

  @Override
  public Map<String, Object> toMap() {
    var m = new LinkedHashMap<String, Object>();
    m.put("id", id);
    m.put("node", node);
    m.put("status", status);
    if (exitCode != null) m.put("exit_code", exitCode);
    if (principal != null) m.put("principal", principal);
    if (owner != null) m.put("owner", owner);
    if (sessionId != null) m.put("session_id", sessionId);
    if (sessionSource != null) m.put("session_source", sessionSource);
    if (lastActivityAt != null) m.put("last_activity_at", lastActivityAt);
    if (presence != null) m.put("presence", presence);
    return m;
  }
}
