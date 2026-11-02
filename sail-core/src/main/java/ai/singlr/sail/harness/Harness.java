/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.harness;

import ai.singlr.sail.common.Strings;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.SequencedMap;
import java.util.regex.Pattern;

/**
 * A coding harness sail drives inside a project container: everything that differs between Claude
 * Code, Codex and the next one, asked of one adapter. Sail names a harness by its {@link #yamlName}
 * in a project's definition and resolves it through {@link Harnesses}; nothing outside this package
 * compares a harness with a name or branches on one. Each adapter builds its own launch commands,
 * declares the file of hooks it runs sail's scripts from, and answers for its own capabilities, so
 * adding a harness is adding one adapter.
 */
public interface Harness {

  /**
   * One headless launch: the task file the prompt is read from, whether every action is
   * auto-approved, an optional model and reasoning effort, the recorded conversation to resume
   * ({@code null} for a fresh session) and whether the harness should stream its output as it runs.
   * A background dispatch streams; the foreground paths parse the harness's final output and do
   * not. A launch with no task file to read is refused.
   */
  record Launch(
      String taskFile,
      boolean fullPermissions,
      String model,
      String reasoningEffort,
      String resumeSessionId,
      boolean stream) {

    private static final Pattern SAFE_SESSION_ID =
        Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,127}");

    public Launch {
      Strings.requireNonBlank(taskFile, "taskFile");
    }

    /**
     * The recorded session this launch resumes, or empty for a fresh session.
     *
     * @throws IllegalArgumentException when the id is not {@link #requireSafeSessionId safe}
     */
    public Optional<String> resume() {
      return Optional.ofNullable(resumeSessionId).map(Harness::requireSafeSessionId);
    }

    /** The prompt argument: the task read from its file inside the container. */
    public String task() {
      return "\"$(cat " + taskFile + ")\"";
    }
  }

  /**
   * One live member's launch: whether every action is auto-approved, an optional model and the
   * recorded conversation to resume ({@code null} for a fresh session). There is no task: a live
   * process is prompted over its stdin, one message at a time.
   */
  record LiveLaunch(boolean fullPermissions, String model, String resumeSessionId) {

    /**
     * The recorded session this launch resumes, or empty for a fresh session.
     *
     * @throws IllegalArgumentException when the id is not {@link #requireSafeSessionId safe}
     */
    public Optional<String> resume() {
      return Optional.ofNullable(resumeSessionId).map(Harness::requireSafeSessionId);
    }
  }

  /**
   * The settings file a live member runs under: where it lives inside the container and its
   * entries, in order. It names no hook: sail reads a live process, nothing in it reports back.
   */
  record LiveSettings(String path, SequencedMap<String, Object> entries) {

    public LiveSettings {
      Strings.requireNonBlank(path, "path");
      entries = Collections.unmodifiableSequencedMap(new LinkedHashMap<>(entries));
    }

    /** The directory the file lives in. */
    public String directory() {
      return path.substring(0, path.lastIndexOf('/'));
    }
  }

  /** The name a definition uses, such as {@code claude-code}. */
  String yamlName();

  /** The CLI binary name on PATH. */
  String binaryName();

  /** Human-readable display name. */
  String displayName();

  /** The shell command that installs the CLI inside a container. */
  String installCommand();

  /**
   * The home-level skills directory, relative to {@code $HOME}, with a trailing slash. A skill
   * lives at {@code <skillsDir><name>/SKILL.md}.
   */
  String skillsDir();

  /** The folder of the skill named {@code name}, relative to {@code $HOME}, without a slash. */
  default String skillFolder(String name) {
    return skillsDir() + name;
  }

  /** The headless command for a build, a reviewer, a fix agent or a full chat turn. */
  String headless(Launch launch);

  /**
   * The headless command for the room lane's read-only chat turn, which the harness itself keeps
   * from mutating anything. {@code fullPermissions} and {@code reasoningEffort} of the launch are
   * ignored. A harness with a {@link #readOnlyRefusal} has no such session and refuses to build
   * one.
   *
   * @throws IllegalStateException when this harness has no read-only session
   */
  default String readOnly(Launch launch) {
    throw new IllegalStateException(
        displayName()
            + " has no harness-enforced read-only session inside a sail container;"
            + " the room lane refuses to launch it.");
  }

  /**
   * Why the read-only member mode is unavailable for this harness, or empty when the harness
   * enforces one. Declared at the harness seam so the API reports the same reason the launch gate
   * refuses with, and clients can grey the option out honestly. Every harness supports the full
   * mode; it buys its authority with the per-turn repo reservation, not a sandbox.
   */
  Optional<String> readOnlyRefusal();

  /**
   * The argv of a live member: one process that stays up, takes each prompt as a JSON line on its
   * stdin and writes what it does as JSON lines on its stdout. Empty for a harness with no such
   * mode, whose members are woken one process per turn instead.
   *
   * @throws IllegalArgumentException when the launch resumes an id that is not {@link
   *     #requireSafeSessionId safe}
   */
  default Optional<List<String>> live(LiveLaunch launch) {
    return Optional.empty();
  }

  /** The settings file {@link #live} runs under, or empty for a harness with no live mode. */
  default Optional<LiveSettings> liveSettings() {
    return Optional.empty();
  }

  /** The command for an interactive (TTY) session. */
  String interactive(boolean fullPermissions);

  /**
   * The interactive command resuming the recorded session {@code sessionId} exactly by id, or a
   * fresh conversation when it is {@code null}: never an interactive picker.
   *
   * @throws IllegalArgumentException when the id is not {@link #requireSafeSessionId safe}
   */
  String attach(String sessionId);

  /** Whether the harness has a reasoning effort setting a launch's {@code reasoningEffort} sets. */
  boolean honoursReasoningEffort();

  /**
   * The local port an interactive session's login flow listens on, which the engineer forwards over
   * SSH, or empty when the harness logs in without one.
   */
  OptionalInt loginTunnelPort();

  /** A line of advice printed before an interactive session, when the harness has one. */
  Optional<String> interactiveTip();

  /** The file of hooks that runs sail's scripts at this harness's events. */
  HookFile hooks();

  /**
   * Whether a recorded session id is safe to interpolate into a shell command. Session ids arrive
   * hook-reported and replicate across boxes, so they are untrusted input at every argv seam. The
   * first character must be alphanumeric: an id starting with {@code -} would be parsed by the
   * harness as an option, not a session id.
   */
  static boolean isSafeSessionId(String sessionId) {
    return sessionId != null && Launch.SAFE_SESSION_ID.matcher(sessionId).matches();
  }

  /**
   * {@code sessionId}, once it is known {@link #isSafeSessionId safe}: the one check every adapter
   * makes before a session id touches a command it builds.
   *
   * @throws IllegalArgumentException when the id fails the safe pattern
   */
  static String requireSafeSessionId(String sessionId) {
    if (!isSafeSessionId(sessionId)) {
      throw new IllegalArgumentException(
          "Malformed session id; refusing to build a resume command from replicated data.");
    }
    return sessionId;
  }
}
