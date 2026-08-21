/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.store;

import ai.singlr.sail.config.Lane;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * The pure dispatch-concurrency decision: a new dispatch is refused when its spec already has a
 * running local run, or when its target repo set overlaps a repo some already-running local run of
 * the same project is working in. Disjoint repo sets of distinct specs share the container safely —
 * every run has its own systemd unit, files, log, and watcher — so a sail-repo agent no longer
 * blocks mast-repo work for hours.
 *
 * <p>An empty repo set means the run (or the target) works the whole container — a project with no
 * {@code repos} block resolves to no specific repo — and therefore overlaps everything, which keeps
 * single-repo and repo-less projects refusing concurrent dispatches exactly as before.
 *
 * <p>The decision consults run rows only: liveness is the reconciler's job, and a row whose unit
 * already died is healed within a sweep interval, so the gate never consults systemd. The binding
 * evaluation runs inside {@link RunStore#reserveDispatch}'s {@code BEGIN IMMEDIATE} transaction,
 * which checks and inserts atomically so two concurrent dispatches — even from separate processes —
 * can never both pass; callers outside that transaction use it only for advisory pre-checks.
 */
public final class DispatchGate {

  private DispatchGate() {}

  /** The chat lane: a run that reserves no repos and conflicts only with runs of its own spec. */
  public static final String ROOM_ROLE = Lane.ROOM.wire();

  /**
   * The read-only invite lane: a consultant a human explicitly invited into the room. It reserves
   * no repos and holds no write authority, so it conflicts with nothing at all — not even a live
   * run of its own spec, because "a read-only consultant alongside the live build" is exactly the
   * lane's purpose. The same-spec backstop stays for {@link #ROOM_ROLE} wakes, which fire
   * automatically and must never race a live run.
   */
  public static final String READ_ONLY_INVITE_ROLE = Lane.INVITE.wire();

  /** The full invite lane: reserves like a build, so one writer per repo always holds. */
  public static final String FULL_INVITE_ROLE = Lane.INVITE_FULL.wire();

  /**
   * The full chat lane: an engaged agent's turn with write access. It reserves the spec's repos
   * (the whole container when the spec names none), so one writer per repo holds for conversations
   * exactly as for builds — a full turn defers on a live build through the ordinary repo rule and
   * frees when the build's stop re-evaluates the room.
   */
  public static final String ROOM_FULL_ROLE = Lane.ROOM_FULL.wire();

  /**
   * One running local run of the project: its id, the spec it works, its role, and its reserved
   * repos.
   */
  public record RunningRun(String runId, String specId, String role, List<String> repos) {}

  /**
   * The blocking run and the repos both sides claim; empty {@code overlap} means one side works the
   * whole container.
   */
  public record Conflict(RunningRun run, List<String> overlap) {}

  /**
   * The first running run that blocks the dispatch, or empty to allow. A {@link
   * #READ_ONLY_INVITE_ROLE} run on either side conflicts with nothing — a read-only consultant runs
   * alongside anything, including its own spec's live build. A run of {@code targetSpecId} itself
   * blocks when both sides are working lanes (a spec has one lifecycle and one review pipeline, so
   * a second live execution — reachable via restart with a repo override — would race the first
   * over shared spec state) and when both sides are chat lanes (one conversational turn at a time).
   * A mixed same-spec pair falls through to the repo rules: a read-only chat turn answers the room
   * while the build works, and a full chat turn defers on the build through its repo claim. Any
   * other run blocks only on repo overlap, and a {@link #ROOM_ROLE} run on either side never
   * overlaps anything: a read-only chat reserves no repos, so its empty repo set must not carry the
   * whole-container meaning the working lanes give it. {@link #ROOM_FULL_ROLE} carries real repo
   * claims and gets no such exemption.
   */
  public static Optional<Conflict> decide(
      String targetSpecId, String targetRole, List<String> targetRepos, List<RunningRun> running) {
    if (READ_ONLY_INVITE_ROLE.equals(targetRole)) {
      return Optional.empty();
    }
    return running.stream()
        .filter(run -> !READ_ONLY_INVITE_ROLE.equals(run.role()))
        .map(
            run ->
                sameSpec(run.specId(), targetSpecId) && chatLane(targetRole) == chatLane(run.role())
                    ? Optional.of(new Conflict(run, List.of()))
                    : roomLane(targetRole, run.role())
                        ? Optional.<Conflict>empty()
                        : conflictWith(targetRepos, run))
        .flatMap(Optional::stream)
        .findFirst();
  }

  private static boolean chatLane(String role) {
    return ROOM_ROLE.equals(role) || ROOM_FULL_ROLE.equals(role);
  }

  private static boolean roomLane(String targetRole, String runRole) {
    return ROOM_ROLE.equals(targetRole) || ROOM_ROLE.equals(runRole);
  }

  /**
   * Whether the running run works the very spec being dispatched. A blank id names no spec — an
   * ad-hoc session or a legacy row — so two blank ids are never "the same spec"; their conflict is
   * decided by the repo overlap rule alone (which an empty repo set makes total anyway).
   */
  private static boolean sameSpec(String runSpecId, String targetSpecId) {
    return runSpecId != null && !runSpecId.isBlank() && Objects.equals(runSpecId, targetSpecId);
  }

  private static Optional<Conflict> conflictWith(List<String> targetRepos, RunningRun run) {
    if (targetRepos.isEmpty() || run.repos().isEmpty()) {
      return Optional.of(new Conflict(run, List.of()));
    }
    var overlap = targetRepos.stream().filter(run.repos()::contains).toList();
    return overlap.isEmpty() ? Optional.empty() : Optional.of(new Conflict(run, overlap));
  }
}
