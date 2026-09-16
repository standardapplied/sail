/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.api;

/**
 * The full control-plane surface served over the web API by {@link ApiRouter}. Extends {@link
 * LocalLaneOperations} so the in-container local-socket surface is one shared contract, and adds
 * the web-only lanes: project lifecycle, dispatch, snapshots, agent status, run control, events,
 * and reviews.
 */
public interface Operations extends LocalLaneOperations {


  DispatchOperations.Outcome dispatch(String project, DispatchOperations.Request request, Actor actor, String localHandle);

  DispatchOperations.AdhocSession startAdhoc(String project, DispatchOperations.AdhocRequest request, String localHandle);

  DispatchOperations.AdhocSession startAdhoc(String project, DispatchOperations.AdhocRequest request, String localHandle, DispatchOperations.AdhocPreparer preparer);

  StopOperations.Outcome stop(StopOperations.Target target, Actor actor, String localHandle, boolean dryRun);

  java.util.List<ai.singlr.sail.config.Spec> projectSpecs(String project);

  java.util.Optional<ai.singlr.sail.store.SpecStore.SpecContent> specContent(String id);

  java.util.Optional<ai.singlr.sail.store.ProjectStore.ProjectRow> catalogProject(String project);

  java.util.List<ai.singlr.sail.store.ProjectStore.ProjectRow> catalogProjects();

  java.util.Optional<ai.singlr.sail.store.RunStore.RunRow> latestRun(String project, String node);

  java.util.List<ai.singlr.sail.store.DispatchGate.RunningRun> runningRuns(String project, String node);

  ai.singlr.sail.engine.AgentSession.SessionInfo projectSession(String project, String node) throws Exception;

  boolean roomKnown(String room);

  String reviewLog(String project, String node);

  String demoDefinition();

  java.util.List<ai.singlr.sail.store.TokenStore.TokenInfo> tokens();

  ai.singlr.sail.store.TokenStore.CreatedToken createToken(String name, String role, String fdeId, java.time.Duration ttl);

  boolean revokeToken(String name);

  java.util.Optional<ai.singlr.sail.store.FdeStore.Fde> fde(String handle);

  int schemaVersion();

  int schemaBeforeOpen();

  ai.singlr.sail.ssh.SshGateway.Decision authorizeGateway(String command, String handle);

  ai.singlr.sail.pty.PtyIdentity ptyIdentity(String token, String boxHandle) throws java.io.IOException;

  void admitPtyRoom(String room, String project, ai.singlr.sail.pty.PtyIdentity identity) throws java.io.IOException;

  void recordHostEvent(ai.singlr.sail.store.EventStore.EventRow event);

  java.util.List<ai.singlr.sail.store.FdeSshKeyStore.SshKeyInfo> sshKeys();

  SyncReport sync(SyncRequest request) throws Exception;

  ai.singlr.sail.store.SyncConflicts.Conflict conflict(String id);

  ai.singlr.sail.store.SyncConflicts.Conflict resolveConflict(String id, Resolution resolution);

  ProjectFiles projectFiles(String project);

  java.util.List<String> projectsWithFiles();

  ProjectDestroyed projectDestroy(String name, boolean purge);

  ProjectRenamed projectRename(String from, String to);

  record ProjectDestroyed(String name, boolean purged) {}

  record ProjectRenamed(String from, String to, String previousDefinition) {}

  void undoProjectRename(ProjectRenamed renamed);

  Result<ReviewListResponse> reviewsForSpec(String specId);

  Result<ReviewDetailResponse> reviewDetail(String reviewId);

  Result<ReviewApproveResponse> approveReview(String reviewId, Actor actor);

  Result<FindingDismissResponse> dismissFinding(String reviewId, String findingId, Actor actor);

  Result<HealthResponse> health();

  /** Lists every project on this node with its container status. */
  Result<ProjectListResponse> projects();

  /** The org-wide FDE roster, sorted by handle — a member-tier read backed by the synced roster. */
  Result<FdesResponse> fdes();

  Result<AgentsResponse> agents();

  Result<ProjectResponse> project(String project);

  /** Returns the two-hop SSH target (server jump + container) for a running project. */
  Result<ConnectResponse> connect(String project);

  Result<SpecsResponse> specs(String project);

  Result<SpecResponse> spec(String project, String specId);

  Result<DispatchResponse> dispatch(
      String project, DispatchRequest request, Actor actor, String localHandle);

  /** Lists the project's container snapshots with the source each name's prefix encodes. */
  Result<SnapshotListResponse> snapshots(String project);

  /**
   * Accepts an async restore of the container to {@code label}: validation and the live-run gate
   * run inline, the mutation completes in the background and reports via {@code snapshot_restored}.
   * Refused while any run is live on this box's container — a restore discards its work.
   */
  Result<SnapshotActionResponse> restoreSnapshot(String project, String label, String localHandle);

  /** Accepts an async snapshot delete; completion is reported via {@code snapshot_deleted}. */
  Result<SnapshotActionResponse> deleteSnapshot(String project, String label);

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

  /**
   * The spec's durable event history from this box's audit store, oldest first: RECORD-class events
   * only, at most {@code limit} rows, and — when {@code since} is non-null — only rows with an
   * event id strictly greater than it (the gap-fill cursor after an SSE reconnect). {@code since}
   * null means the newest {@code limit} rows. A spec with no events answers an empty list, never
   * 404. The record is node-local: events published on another box never land in this store, so the
   * fleet-consistent room content remains the synced stores (messages, reviews, runs).
   */
  Result<SpecEventsResponse> specEvents(String specId, Long since, int limit);

  /** Returns per-subscriber + bus stats for {@code /v1/events/stats}. */
  Result<EventBusStatsResponse> eventBusStats();

  /** Drafts a follow-up spec from the open findings of a spec's latest non-superseded review. */
  Result<FollowupSpecResponse> createFollowupSpec(String specId, FollowupCreateRequest request);

  Result<GlobalSpecHistoryResponse> globalSpecHistory(String specId);

  Result<GlobalSpecRestoredResponse> restoreGlobalSpec(
      String specId, SpecRestoreRequest request, Actor actor);

  Result<RoomMembersResponse> roomMembers(String roomId);

  Result<SpecMessagesResponse> roomMessages(String roomId, String before, String after, int limit);

  Result<SpecMessageResponse> postRoomMessage(
      String roomId, SpecMessageRequest request, Actor principal, String authorHandle);

  Result<EngageResponse> addRoomMember(
      String roomId, EngageRequest request, Actor actor, String localHandle);

  Result<DisengageResponse> removeRoomMember(String roomId, Actor actor, String localHandle);

  Result<RoomDetailResponse> createRoom(RoomCreateRequest request, Actor actor);

  Result<RoomsListResponse> rooms(String project, Actor actor);

  Result<RoomDetailResponse> room(String roomId);

  Result<RoomDeletedResponse> deleteRoom(String roomId, Actor actor);
}
