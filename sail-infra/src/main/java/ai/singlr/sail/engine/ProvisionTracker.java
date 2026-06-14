/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.engine;

import ai.singlr.sail.common.DateTimeUtils;
import ai.singlr.sail.config.YamlUtil;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Optional;

/**
 * Tracks and persists provisioning progress for any multi-step operation. Each provisioner defines
 * its own phase enum and creates a tracker parameterized by that enum. The tracker handles
 * persistence, resume detection, and lifecycle.
 *
 * <p>State is persisted to YAML after each phase via atomic write-then-rename for crash safety. In
 * dry-run mode, state is tracked in memory only — no disk writes.
 *
 * <p>Not thread-safe. Provisioning is single-threaded by design.
 *
 * @param <P> the phase enum type (ordinal determines execution order)
 */
public final class ProvisionTracker<P extends Enum<P>> {

  private final Class<P> phaseType;
  private final Path stateFile;
  private final boolean dryRun;
  private final P[] phases;
  private ProvisionState state;

  public ProvisionTracker(Class<P> phaseType, Path stateFile, boolean dryRun) {
    this.phaseType = phaseType;
    this.stateFile = stateFile;
    this.dryRun = dryRun;
    this.phases = phaseType.getEnumConstants();
    this.state = ProvisionState.empty();
  }

  /**
   * Loads existing state from disk, or starts with empty state if no file exists. Call once before
   * the provisioning loop.
   *
   * @return this tracker for fluent chaining
   */
  public ProvisionTracker<P> load() throws IOException {
    if (Files.exists(stateFile)) {
      this.state = ProvisionState.fromMap(YamlUtil.parseFile(stateFile));
    }
    return this;
  }

  /**
   * Returns {@code true} if the given phase was already completed in a prior run. Uses ordinal
   * comparison: a phase is completed if its ordinal is {@code <=} the last completed phase's
   * ordinal.
   */
  public boolean isCompleted(P phase) {
    if (state.completedPhase() == null) {
      return false;
    }
    var completed = Enum.valueOf(phaseType, state.completedPhase());
    return phase.ordinal() <= completed.ordinal();
  }

  /**
   * Returns the first phase that has not yet been completed, or empty if all phases are complete.
   */
  public Optional<P> resumePoint() {
    if (state.completedPhase() == null) {
      return phases.length > 0 ? Optional.of(phases[0]) : Optional.empty();
    }
    var completed = Enum.valueOf(phaseType, state.completedPhase());
    var nextOrdinal = completed.ordinal() + 1;
    if (nextOrdinal >= phases.length) {
      return Optional.empty();
    }
    return Optional.of(phases[nextOrdinal]);
  }

  /**
   * Marks a phase as completed, clears any prior error, and persists state to disk. In dry-run
   * mode, updates in-memory state only.
   *
   * @throws IllegalStateException if the phase would regress the watermark (not forward-only)
   */
  public void advance(P completedPhase) throws IOException {
    if (state.completedPhase() != null) {
      var current = Enum.valueOf(phaseType, state.completedPhase());
      if (completedPhase.ordinal() <= current.ordinal()) {
        throw new IllegalStateException("Cannot regress from " + current + " to " + completedPhase);
      }
    }
    var now = DateTimeUtils.now().toString();
    this.state =
        new ProvisionState(
            completedPhase.name(), state.startedAt() != null ? state.startedAt() : now, now, null);
    persist();
  }

  /**
   * Records a failure at the given phase and persists state to disk. The {@code completedPhase}
   * field is NOT updated — it still reflects the last successfully completed phase.
   */
  public void recordFailure(P failedPhase, String message) throws IOException {
    var now = DateTimeUtils.now().toString();
    this.state =
        new ProvisionState(
            state.completedPhase(),
            state.startedAt() != null ? state.startedAt() : now,
            now,
            new ProvisionState.ProvisionError(failedPhase.name(), message, now));
    persist();
  }

  /**
   * Deletes the state file. Called when provisioning completes successfully. Absence of the state
   * file means "fully provisioned."
   */
  public void cleanup() throws IOException {
    if (dryRun) {
      return;
    }
    Files.deleteIfExists(stateFile);
  }

  /**
   * Resets the tracker to empty state and deletes the state file. Used when stale state is detected
   * (e.g. container was deleted externally but state file remains).
   */
  public void reset() throws IOException {
    this.state = ProvisionState.empty();
    if (!dryRun) {
      Files.deleteIfExists(stateFile);
    }
  }

  /**
   * Returns {@code true} if a prior incomplete run exists — the state file is present and the last
   * completed phase is not the final phase in the enum.
   */
  public boolean hasIncompleteRun() {
    if (state.completedPhase() == null) {
      return Files.exists(stateFile);
    }
    var completed = Enum.valueOf(phaseType, state.completedPhase());
    return completed.ordinal() < phases[phases.length - 1].ordinal();
  }

  /** Returns the current in-memory state snapshot. */
  public ProvisionState currentState() {
    return state;
  }

  private void persist() throws IOException {
    if (dryRun) {
      return;
    }
    Files.createDirectories(stateFile.getParent());
    var tmpFile = stateFile.resolveSibling(stateFile.getFileName() + ".tmp");
    YamlUtil.dumpToFile(state.toMap(), tmpFile);
    Files.move(
        tmpFile, stateFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
  }
}
