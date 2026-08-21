/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.api;

import ai.singlr.sail.common.DateTimeUtils;
import ai.singlr.sail.common.Strings;
import ai.singlr.sail.config.SailYaml;
import ai.singlr.sail.config.YamlUtil;
import ai.singlr.sail.engine.ContainerExec;
import ai.singlr.sail.engine.HostInfo;
import ai.singlr.sail.engine.ShellExec;
import ai.singlr.sail.store.RunStore;
import ai.singlr.sail.webauthn.Hashes;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * The read-only room contract's backstop, in one place apart from the launch lanes: before a room
 * turn runs, {@link #captureRoomBaseline} records each repo's HEAD and a worktree fingerprint
 * host-side (out of the guarded agent's reach); after it stops, {@link #guardRoomRun} compares
 * against that baseline and publishes a loud {@code guardrail_triggered} for any repo the chat
 * changed. Launches nothing and reserves nothing — pure detection over the run store and the
 * container's git state — so a worktree-writing chat surfaces without ever entering the review
 * loop.
 */
public final class RoomCommitGuard {

  private final RunStore runStore;
  private final ProjectLoader projects;
  private final DispatchOperations.EventSink events;
  private final ShellExec shell;

  public RoomCommitGuard(
      RunStore runStore,
      ProjectLoader projects,
      DispatchOperations.EventSink events,
      ShellExec shell) {
    this.runStore = runStore;
    this.projects = projects;
    this.events = events;
    this.shell = shell;
  }

  /**
   * Records each configured repo's launch state — HEAD and a content fingerprint of the worktree
   * (the tracked diff plus each untracked file's object hash, so editing an already-dirty file is
   * as visible as dirtying a clean one) — host-side in the run store before the chat launches, out
   * of the guarded agent's reach: a baseline the chat could edit or delete would gut the guard.
   * Best-effort bookkeeping: a failure degrades the commit guard, never the wake. A repo whose
   * worktree state cannot be read at launch records no fingerprint and is exempt from the dirty
   * check rather than misjudged by it.
   */
  public void captureRoomBaseline(String project, SailYaml config, String runId) {
    if (runStore == null) {
      return;
    }
    try {
      var baseline = new LinkedHashMap<String, Object>();
      for (var repo : config.repos()) {
        var repoDir = "/home/" + config.sshUser() + "/workspace/" + repo.path();
        var head =
            exec(
                ContainerExec.asDevUser(
                    project, List.of("git", "-C", repoDir, "rev-parse", "HEAD")));
        if (head.ok() && !head.stdout().isBlank()) {
          var entry = new LinkedHashMap<String, Object>();
          entry.put("head", head.stdout().trim());
          var state = worktreeFingerprint(project, repoDir);
          if (state != null) {
            entry.put("state", state);
          }
          baseline.put(repo.path(), entry);
        }
      }
      if (baseline.isEmpty()) {
        return;
      }
      runStore.saveRoomGuardBaseline(runId, YamlUtil.dumpJson(baseline));
    } catch (RuntimeException e) {
      System.err.println(
          "  [room-wake] Warning: could not record the guard baseline: " + e.getMessage());
    }
  }

  /**
   * A worktree content fingerprint that changes whenever any byte the room contract protects
   * changes: the tracked diff against HEAD (staged and unstaged, binary-safe) plus each untracked
   * file's path and object hash — so editing an already-modified file or an already-untracked file
   * is as visible as dirtying a clean one, which a bare {@code git status --porcelain} digest is
   * blind to. Null when the worktree cannot be read, which exempts the repo from the dirty check.
   */
  private String worktreeFingerprint(String project, String repoDir) {
    var script =
        """
        set -e
        git -C "$1" diff --binary HEAD --
        git -C "$1" ls-files --others --exclude-standard -z | while IFS= read -r -d '' path; do printf '%s\\0' "$path"; git -C "$1" hash-object -- "$path"; done
        """;
    var result =
        exec(ContainerExec.asDevUser(project, List.of("bash", "-c", script, "bash", repoDir)));
    return result.ok() ? digest(result.stdout()) : null;
  }

  /**
   * The read-only contract's backstop, run when a room run stops — defense in depth behind the
   * harness-restricted launch. Any repo whose HEAD moved or whose worktree content changed (the
   * {@link #worktreeFingerprint}, so an uncommitted edit is as loud as a commit) since the wake
   * launched — and that no working run whose execution overlapped the room run's interval reserves,
   * so a concurrent build's work is never misattributed even when that build finished before this
   * guard fired — is published as a loud {@code guardrail_triggered} event. Never a review: the
   * pipeline ignores {@code room} stops structurally, and this guard is how a worktree-writing chat
   * surfaces instead. The baseline lives host-side in the run store, where the guarded agent cannot
   * touch it, and is consumed on first read so a replayed stop checks nothing twice.
   */
  public void guardRoomRun(String project, String runId) {
    if (runStore == null) {
      return;
    }
    var recorded = runStore.consumeRoomGuardBaseline(runId).orElse(null);
    if (recorded == null || recorded.isBlank()) {
      return;
    }
    var run = runStore.findById(runId).orElse(null);
    var specId = run != null ? run.specId() : null;
    var baseline = YamlUtil.parseMap(recorded);
    var roomStarted = run != null ? parseInstant(run.startedAt()) : null;
    var roomNode = run != null ? run.node() : null;
    var guardAt = DateTimeUtils.now();
    var others =
        runStore.listForProject(project).stream()
            .filter(candidate -> !candidate.id().equals(runId))
            .filter(candidate -> !candidate.readOnlyLane())
            .filter(candidate -> sameNode(roomNode, candidate))
            .filter(candidate -> overlapsRoomInterval(candidate, roomStarted, guardAt))
            .toList();
    if (others.stream().anyMatch(candidate -> candidate.repos().isEmpty())) {
      return;
    }
    var reserved =
        others.stream()
            .flatMap(candidate -> candidate.repos().stream())
            .collect(Collectors.toSet());
    var moved = new ArrayList<String>();
    var config = projects.loadRunning(project).config();
    for (var entry : baseline.entrySet()) {
      var repo = entry.getKey();
      if (reserved.contains(repo)) {
        continue;
      }
      var violation = repoViolation(project, config, repo, entry.getValue());
      if (violation != null) {
        moved.add(violation);
      }
    }
    if (moved.isEmpty()) {
      return;
    }
    publish(
        project,
        specId,
        Event.WellKnownTypes.GUARDRAIL_TRIGGERED,
        Map.of(
            "reason",
            "room run "
                + runId
                + " modified "
                + String.join("; ", moved)
                + " — a chat session must never change code",
            "action",
            "recorded; the worktree keeps the changes — review them by hand"));
  }

  /**
   * A candidate run can only have authored changes the local guard observes if it executed in the
   * same node's shared container. Run rows replicate fleet-wide, so {@code listForProject} returns
   * foreign-node runs too; a build in another node's separate container cannot touch this
   * workspace, and letting it match would silently shield repositories from the guard. When the
   * room run's node is unknown (its row vanished before the guard fired), scope nothing — the
   * conservative posture is a guard that runs, never one a foreign run can suppress.
   */
  private static boolean sameNode(String roomNode, RunStore.RunRow candidate) {
    return roomNode == null || roomNode.equals(candidate.node());
  }

  /**
   * Whether a run's execution interval could have overlapped the room run's — from the recorded
   * baseline capture at {@code roomStarted} to this guard check at {@code guardAt} — making it a
   * possible author of a repo change the guard observes. A run still live is always a candidate; a
   * completed run is one unless it finished before the room run started, so a build that committed
   * mid-chat and finished first still shields its repos. Unparseable timestamps count as
   * overlapping: the safe failure mode is a quieter guard, never a misattributed one.
   */
  static boolean overlapsRoomInterval(
      RunStore.RunRow candidate, Instant roomStarted, Instant guardAt) {
    var started = parseInstant(candidate.startedAt());
    if (started != null && started.isAfter(guardAt)) {
      return false;
    }
    if (DispatchOperations.ownsLiveAgent(candidate)) {
      return true;
    }
    var completed = parseInstant(candidate.completedAt());
    return roomStarted == null || completed == null || !completed.isBefore(roomStarted);
  }

  static Instant parseInstant(String value) {
    if (Strings.isBlank(value)) {
      return null;
    }
    try {
      return Instant.parse(value);
    } catch (DateTimeParseException e) {
      return null;
    }
  }

  /**
   * One repo's verdict against its recorded baseline: the description of what changed, or null when
   * the repo is untouched or unreadable. A moved HEAD names the committed files; an unmoved HEAD
   * with a changed content fingerprint names the currently-dirty paths.
   */
  @SuppressWarnings("unchecked")
  private String repoViolation(String project, SailYaml config, String repo, Object recorded) {
    if (!(recorded instanceof Map<?, ?> state)) {
      return null;
    }
    var entry = (Map<String, Object>) state;
    var before = Objects.toString(entry.get("head"), "");
    var repoDir = "/home/" + config.sshUser() + "/workspace/" + repo;
    var current =
        exec(ContainerExec.asDevUser(project, List.of("git", "-C", repoDir, "rev-parse", "HEAD")));
    if (!current.ok() || current.stdout().isBlank()) {
      return null;
    }
    var head = current.stdout().trim();
    if (!head.equals(before)) {
      var files =
          exec(
              ContainerExec.asDevUser(
                  project,
                  List.of("git", "-C", repoDir, "diff", "--name-only", before, head, "--")));
      var fileList =
          files.ok() && !files.stdout().isBlank()
              ? String.join(", ", files.stdout().trim().split("\n"))
              : "unknown files";
      return repo + " (" + fileList + ")";
    }
    var recordedState = entry.get("state");
    if (recordedState == null) {
      return null;
    }
    var fingerprint = worktreeFingerprint(project, repoDir);
    if (fingerprint == null || fingerprint.equals(recordedState.toString())) {
      return null;
    }
    var status =
        exec(
            ContainerExec.asDevUser(
                project, List.of("git", "-C", repoDir, "status", "--porcelain")));
    var dirty =
        status.ok()
            ? status
                .stdout()
                .lines()
                .map(line -> line.length() > 3 ? line.substring(3) : line)
                .toList()
            : List.<String>of();
    return repo
        + " (worktree changed: "
        + (dirty.isEmpty() ? "unknown files" : String.join(", ", dirty))
        + ")";
  }

  private static String digest(String value) {
    return HexFormat.of().formatHex(Hashes.sha256(value.getBytes(StandardCharsets.UTF_8)));
  }

  private ShellExec.Result exec(List<String> command) {
    try {
      return shell.exec(command);
    } catch (Exception e) {
      throw new ApiException(ErrorCode.COMMAND_FAILED, "A sail system command failed.", e);
    }
  }

  private void publish(String project, String specId, String type, Map<String, Object> data) {
    events.publish(Event.of(project, specId, type, Event.SAIL_AGENT, HostInfo.hostname(), data));
  }
}
