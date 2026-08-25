/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.api;

import ai.singlr.sail.store.RunStore;
import ai.singlr.sail.store.SpecStore;
import java.util.List;
import java.util.Optional;

/**
 * The operations reachable over the in-container local unix socket: the {@code spec} CLI's
 * global-spec surface and board, spec-room messaging, and a run's own conversation and credential
 * lane. {@link LocalApiRouter} depends on exactly this narrow surface — never the web lane's
 * project lifecycle, dispatch, snapshot, or run-control operations — so the in-container caller
 * cannot reach them and the lane's contract is documented in one place. {@link Operations} composes
 * this role with the full web surface.
 */
public interface LocalLaneOperations {

  /**
   * Resolves a run credential — the bearer the in-container agent lane presents over the local
   * socket — to its live run row. Empty for an unknown, revoked, or expired credential, and on
   * boxes that keep no run aggregate.
   */
  default Optional<RunStore.RunRow> runForCredential(String credential) {
    return Optional.empty();
  }

  /**
   * Resolves the box's ambient credential to the FDE actor it stands for, or empty when the
   * credential is unknown or its handle has left the roster. Serves the local socket's interactive
   * lane; run credentials are resolved first and never reach this.
   */
  default Optional<Actor> boxActorForCredential(String credential) {
    return Optional.empty();
  }

  Result<GlobalSpecsListResponse> globalSpecs(SpecStore.SpecFilter filter);

  Result<GlobalSpecDetailResponse> globalSpec(String specId);

  Result<GlobalSpecCreatedResponse> createGlobalSpec(SpecCreateRequest request);

  Result<GlobalSpecUpdatedResponse> updateGlobalSpec(
      String specId, SpecUpdateRequest request, Actor actor);

  Result<GlobalSpecDeletedResponse> deleteGlobalSpec(String specId, Actor actor);

  Result<GlobalSpecContentResponse> globalSpecContent(String specId);

  Result<GlobalSpecContentResponse> setGlobalSpecContent(
      String specId, SpecContentRequest request, Actor actor);

  Result<GlobalBoardResponse> globalBoard(String project);

  Result<SpecMessageResponse> postRoomMessage(
      String roomId, SpecMessageRequest request, Actor actor, String author);

  /**
   * A page of a room's conversation: {@code before} pages backward from the newest (the default),
   * {@code after} reads forward past a known message id. The two are exclusive. The id resolves
   * spec-first, so a spec id reads its room.
   */
  Result<SpecMessagesResponse> roomMessages(String roomId, String before, String after, int limit);

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
}
