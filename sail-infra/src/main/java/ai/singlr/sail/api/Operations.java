/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.api;

import ai.singlr.sail.store.RunStore;
import ai.singlr.sail.store.SpecStore;
import java.util.List;
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

  /**
   * Resolves the box's ambient credential to the FDE actor it stands for, or empty when the
   * credential is unknown or its handle has left the roster. Serves the local socket's interactive
   * lane; run credentials are resolved first and never reach this.
   */
  default Optional<Actor> boxActorForCredential(String credential) {
    return Optional.empty();
  }

  Result<SpecMessageResponse> postSpecMessage(
      String specId, SpecMessageRequest request, Actor actor, String author);

  /**
   * A page of a spec room: {@code before} pages backward from the newest (the default), {@code
   * after} reads forward past a known message id. The two are exclusive.
   */
  Result<SpecMessagesResponse> specMessages(String specId, String before, String after, int limit);

  /**
   * The run's undelivered room messages: everything on the run's spec absent from the run's
   * delivery ledger, minus what the run's own principal authored — a run is never told its own
   * story. Tracked by exact message identity, so a message that synchronized in late is still owed
   * a delivery no matter how its id sorts. {@code hasMore} reports that the batch was capped and
   * another read is due. A run with no spec (ad-hoc) has an empty inbox.
   */
  Result<RunInboxResponse> runInbox(String runId);

  /**
   * Acknowledges exactly {@code delivered} — message ids the caller actually showed the run, each
   * of which must name a message on the run's own spec. The credential names the run and the run
   * names the spec, so a caller can never mark another run's ledger or point it off-spec.
   * Idempotent: a replayed acknowledgement is a no-op.
   */
  Result<RunAckResponse> ackRunMessages(String runId, List<String> delivered);

  /**
   * Records the hook-reported identity of the run's agent conversation: the session id (required),
   * the start source, and the container-side transcript path (both optional, stored null when
   * blank). Last write wins — a resume, clear, or compact restart re-reports and overwrites, so the
   * row always names the conversation a human would attach to. The run credential is the write
   * gate: revocation at run completion is what ends a run's ability to report, so there is no
   * separate status check. A blank session id is rejected without touching a prior report.
   */
  Result<RunSessionResponse> recordRunSession(
      String runId, String sessionId, String source, String transcriptPath);

  Result<GlobalSpecHistoryResponse> globalSpecHistory(String specId);

  Result<GlobalSpecRestoredResponse> restoreGlobalSpec(
      String specId, SpecRestoreRequest request, Actor actor);

  Result<InviteResponse> inviteToSpec(
      String specId, InviteRequest request, Actor actor, String localHandle);

  Result<GlobalBoardResponse> globalBoard(String project);
}
