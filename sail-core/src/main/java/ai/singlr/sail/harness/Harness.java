/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.harness;

import ai.singlr.sail.common.Strings;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.regex.Pattern;

/**
 * A coding harness sail drives inside a project container: everything that differs between Claude
 * Code, Codex and the next one, asked of one adapter. Sail names a harness by its {@link #yamlName}
 * in {@code sail.yaml} and resolves it through {@link Harnesses}; nothing outside this package
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

  /** The name used in {@code sail.yaml}, such as {@code claude-code}. */
  String yamlName();

  /** The CLI binary name on PATH. */
  String binaryName();

  /** Human-readable display name. */
  String displayName();

  /** The shell command that installs the CLI inside a container. */
  String installCommand();

  /**
   * The sail-owned context file this harness reads from the home directory, relative to {@code
   * $HOME}. The harness loads it alongside any project-level file the engineer keeps in the
   * workspace, so sail owns this path and overwrites it every run without touching the engineer's.
   */
  String homeContextPath();

  /**
   * The home-level skills directory, relative to {@code $HOME}, with a trailing slash. A skill
   * lives at {@code <skillsDir><name>/SKILL.md}.
   */
  String skillsDir();

  /**
   * Where a project's language rule named {@code name} lands, relative to the home directory: the
   * harness's native "load only when relevant" channel.
   */
  String languageRulePath(String name);

  /**
   * The content of the language rule named {@code name} scoped to {@code paths} (empty for an
   * unscoped rule) over the normalized {@code body}.
   */
  String languageRule(String name, List<String> paths, String body);

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
