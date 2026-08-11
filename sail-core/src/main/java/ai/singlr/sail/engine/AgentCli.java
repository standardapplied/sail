/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.engine;

import ai.singlr.sail.common.Strings;
import java.util.regex.Pattern;

/**
 * Known AI coding agent CLIs that can be installed inside a project container. Each constant
 * carries the metadata needed to install and invoke the agent: the YAML config name, the binary
 * name on PATH, the installation method, and the shell command to install it.
 */
public enum AgentCli {
  CLAUDE_CODE("claude-code", "claude", "curl -fsSL https://claude.ai/install.sh | bash"),
  CODEX(
      "codex",
      "codex",
      "curl -fsSL https://chatgpt.com/codex/install.sh | CODEX_NON_INTERACTIVE=1 sh");

  private final String yamlName;
  private final String binaryName;
  private final String installCommand;

  AgentCli(String yamlName, String binaryName, String installCommand) {
    this.yamlName = yamlName;
    this.binaryName = binaryName;
    this.installCommand = installCommand;
  }

  /** The name used in sail.yaml ({@code "claude-code"} or {@code "codex"}). */
  public String yamlName() {
    return yamlName;
  }

  /** The CLI binary name on PATH ({@code "claude"} or {@code "codex"}). */
  public String binaryName() {
    return binaryName;
  }

  /** The shell command to install this agent CLI. */
  public String installCommand() {
    return installCommand;
  }

  /**
   * The sail-owned context file this agent reads from the home directory, relative to {@code
   * $HOME}: {@code .claude/CLAUDE.md} for Claude Code, {@code .codex/AGENTS.md} for Codex. Both
   * agents load this home-level file alongside any project-level file the engineer keeps in the
   * workspace, so sail owns this path and overwrites it every run without touching the engineer's.
   */
  public String homeContextPath() {
    return switch (this) {
      case CLAUDE_CODE -> ".claude/CLAUDE.md";
      case CODEX -> ".codex/AGENTS.md";
    };
  }

  /**
   * The agent's home-level skills directory, relative to {@code $HOME}, with a trailing slash
   * ({@code .claude/skills/} for Claude Code, {@code .agents/skills/} for Codex). A skill lives at
   * {@code <skillsDir><name>/SKILL.md}.
   */
  public String skillsDir() {
    return switch (this) {
      case CLAUDE_CODE -> ".claude/skills/";
      case CODEX -> ".agents/skills/";
    };
  }

  /** Human-readable display name. */
  public String displayName() {
    return switch (this) {
      case CLAUDE_CODE -> "Claude Code";
      case CODEX -> "Codex CLI";
    };
  }

  /**
   * Returns the shell command fragment for headless (non-interactive) task execution. The task is
   * read from the given file path inside the container via {@code $(cat ...)}.
   *
   * @param taskFile absolute path to the task file inside the container
   * @param fullPermissions whether to auto-approve all actions
   */
  public String headlessCommand(String taskFile, boolean fullPermissions) {
    return headlessCommand(taskFile, fullPermissions, null, null, null);
  }

  public String headlessCommand(
      String taskFile, boolean fullPermissions, String model, String reasoningEffort) {
    return headlessCommand(taskFile, fullPermissions, model, reasoningEffort, null);
  }

  /**
   * Same as {@link #headlessCommand(String, boolean, String, String)} but lets the harness layer
   * inject a {@code --settings <path>} argument for Claude Code so sail-launched sessions load the
   * sail-owned hooks without polluting interactive engineer sessions. Non-Claude agents ignore
   * {@code claudeSettingsPath}.
   */
  public String headlessCommand(
      String taskFile,
      boolean fullPermissions,
      String model,
      String reasoningEffort,
      String claudeSettingsPath) {
    return headlessCommand(
        taskFile, fullPermissions, model, reasoningEffort, claudeSettingsPath, false);
  }

  /**
   * Same as {@link #headlessCommand(String, boolean, String, String, String)} but, when {@code
   * stream} is true, makes Claude Code emit newline-delimited JSON events ({@code --output-format
   * stream-json --verbose}) instead of a single final result, so {@code agent.log} fills live
   * during a long-running dispatch. This must be scoped to the background dispatch path only: the
   * review/foreground paths parse the agent's final {@code json} block and would break under
   * streaming output. Codex already streams a readable transcript, so the flag is a no-op for it.
   *
   * <p>Full-permission Codex sessions additionally pass {@code --dangerously-bypass-hook-trust}:
   * Codex silently skips untrusted hooks even in headless {@code exec}, and sail cannot pre-seed
   * trust hashes for the hooks file it owns and rewrites, so without the flag the sail hooks layer
   * would never fire (see {@code CodexHookConfig}). It is tied to {@code fullPermissions} because
   * hooks run outside the Codex sandbox — in a sandboxed session auto-trusting hooks would grant an
   * escape hatch, while an unsandboxed session gains nothing it did not already have. Every sail
   * dispatch path runs with full permissions, and interactive engineer sessions never get the flag.
   */
  public String headlessCommand(
      String taskFile,
      boolean fullPermissions,
      String model,
      String reasoningEffort,
      String claudeSettingsPath,
      boolean stream) {
    var task = "\"$(cat " + taskFile + ")\"";
    return switch (this) {
      case CLAUDE_CODE -> {
        var perm = fullPermissions ? " --dangerously-skip-permissions" : "";
        var settings =
            Strings.isBlank(claudeSettingsPath) ? "" : " --settings " + claudeSettingsPath;
        var streamFormat = stream ? " --output-format stream-json --verbose" : "";
        yield binaryName
            + " --print"
            + streamFormat
            + settings
            + perm
            + claudeModelOptions(model)
            + " -p "
            + task;
      }
      case CODEX -> {
        var perm =
            fullPermissions
                ? " --dangerously-bypass-approvals-and-sandbox --dangerously-bypass-hook-trust"
                : "";
        yield binaryName + " exec" + perm + codexModelOptions(model, reasoningEffort) + " " + task;
      }
    };
  }

  private static final Pattern SAFE_SESSION_ID =
      Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,127}");

  private static final String ROOM_TOOLS = " --tools \"Bash,Read,Grep,Glob\"";

  private static final String ROOM_ALLOWED_TOOLS =
      " --allowedTools \"Bash(spec:*)\" \"Bash(cd:*)\"";

  /**
   * Whether this CLI can run the room lane's read-only chat session with the restriction enforced
   * by the harness rather than promised by the prompt. Claude Code can: {@code --print} without
   * {@code --dangerously-skip-permissions} denies every tool call not covered by an allow rule, and
   * {@link #ROOM_TOOLS} removes the mutating tools from the set entirely. Codex cannot: its only
   * enforcement layer is the bubblewrap sandbox, which needs user namespaces — blocked inside incus
   * containers ({@code bwrap: setting up uid map: Permission denied}) — so the sole mode that
   * executes commands at all is the full bypass flag, exactly what the room contract forbids.
   */
  public boolean supportsRoomLane() {
    return this == CLAUDE_CODE;
  }

  /**
   * The room lane's headless command: like {@link #headlessCommand} but harness-restricted instead
   * of full-permission. The tool set is cut to {@code Bash,Read,Grep,Glob} — {@code Write} and
   * {@code Edit} do not exist in the session, and the {@code --tools} cut is CLI-authoritative, so
   * no on-disk settings file can re-add them. The only auto-approved commands are {@code spec} (the
   * lane's one write — posting the answer, and the room credential is viewer-role so even {@code
   * spec} cannot mutate a spec) and {@code cd} (navigation, no write). Reading the workspace is
   * {@code Read}/{@code Grep}/{@code Glob}, which have no write flag. Git is deliberately absent:
   * {@code git diff --output=<path>} writes through a prefix allow-rule, and git's external-diff
   * and pager config are command-execution surfaces — a read-only lane must not expose them.
   *
   * <p>This is the harness-enforced boundary the platform can express, not a kernel one. It is
   * exact about what it is: {@code Write}/{@code Edit} are structurally gone; the Bash allowlist is
   * two non-writing commands; the room credential is viewer-role; and a host-side content guard
   * ({@code DispatchOperations#guardRoomRun}) surfaces any worktree change as a loud guardrail
   * event. What it is not: hermetic against an ambient {@code .claude/settings.json} that merges an
   * additional {@code Bash(...)} allow-rule (Claude Code merges permission rules across settings
   * sources), nor against a kernel-level escape — both are owned by the room-lane hardening
   * follow-up spec (managed-policy settings and/or a sidecar container with a read-only disk
   * device), the boundaries incus does not give a same-container process.
   */
  public String headlessRoomCommand(
      String taskFile, String model, String claudeSettingsPath, boolean stream) {
    requireRoomLane();
    return roomInvocation(claudeSettingsPath, model, stream) + " -p \"$(cat " + taskFile + ")\"";
  }

  /**
   * The room lane's headless resume: {@link #headlessRoomCommand} semantics on a recorded
   * conversation. The session id is validated against the safe pattern before it touches the shell
   * string because it is hook-reported, replicated data.
   */
  public String headlessRoomResumeCommand(
      String sessionId, String taskFile, String model, String claudeSettingsPath, boolean stream) {
    requireRoomLane();
    if (!isSafeSessionId(sessionId)) {
      throw new IllegalArgumentException(
          "Malformed session id; refusing to build a resume command from replicated data.");
    }
    return roomInvocation(claudeSettingsPath, model, stream)
        + " --resume "
        + sessionId
        + " -p \"$(cat "
        + taskFile
        + ")\"";
  }

  private String roomInvocation(String claudeSettingsPath, String model, boolean stream) {
    var settings = Strings.isBlank(claudeSettingsPath) ? "" : " --settings " + claudeSettingsPath;
    var streamFormat = stream ? " --output-format stream-json --verbose" : "";
    return binaryName
        + " --print"
        + streamFormat
        + settings
        + ROOM_TOOLS
        + ROOM_ALLOWED_TOOLS
        + claudeModelOptions(model);
  }

  private void requireRoomLane() {
    if (!supportsRoomLane()) {
      throw new IllegalStateException(
          displayName()
              + " has no harness-enforced read-only session inside a sail container;"
              + " the room lane refuses to launch it.");
    }
  }

  /**
   * Whether a recorded session id is safe to interpolate into a shell command. Session ids arrive
   * hook-reported and replicate across boxes, so they are untrusted input at every argv seam.
   */
  public static boolean isSafeSessionId(String sessionId) {
    return sessionId != null && SAFE_SESSION_ID.matcher(sessionId).matches();
  }

  /**
   * The headless command resuming a recorded conversation with a fresh task: {@code claude --print
   * --resume <id> -p …} / {@code codex exec resume <id> …}. Same permission, model, settings, and
   * streaming semantics as {@link #headlessCommand(String, boolean, String, String, String,
   * boolean)}; the session id is validated against the safe pattern before it touches the shell
   * string because it is hook-reported, replicated data.
   */
  public String headlessResumeCommand(
      String sessionId,
      String taskFile,
      boolean fullPermissions,
      String model,
      String reasoningEffort,
      String claudeSettingsPath,
      boolean stream) {
    if (!isSafeSessionId(sessionId)) {
      throw new IllegalArgumentException(
          "Malformed session id; refusing to build a resume command from replicated data.");
    }
    var task = "\"$(cat " + taskFile + ")\"";
    return switch (this) {
      case CLAUDE_CODE -> {
        var perm = fullPermissions ? " --dangerously-skip-permissions" : "";
        var settings =
            Strings.isBlank(claudeSettingsPath) ? "" : " --settings " + claudeSettingsPath;
        var streamFormat = stream ? " --output-format stream-json --verbose" : "";
        yield binaryName
            + " --print"
            + streamFormat
            + settings
            + perm
            + claudeModelOptions(model)
            + " --resume "
            + sessionId
            + " -p "
            + task;
      }
      case CODEX -> {
        var perm =
            fullPermissions
                ? " --dangerously-bypass-approvals-and-sandbox --dangerously-bypass-hook-trust"
                : "";
        yield binaryName
            + " exec resume"
            + perm
            + codexModelOptions(model, reasoningEffort)
            + " "
            + sessionId
            + " "
            + task;
      }
    };
  }

  /**
   * Returns the shell command fragment for interactive (TTY) agent launch.
   *
   * @param fullPermissions whether to auto-approve all actions
   */
  public String interactiveCommand(boolean fullPermissions) {
    return switch (this) {
      case CLAUDE_CODE ->
          fullPermissions ? binaryName + " --dangerously-skip-permissions" : binaryName;
      case CODEX ->
          fullPermissions ? binaryName + " --dangerously-bypass-approvals-and-sandbox" : binaryName;
    };
  }

  /**
   * Looks up an {@code AgentCli} by its YAML name.
   *
   * @throws IllegalArgumentException if the name is not a known agent CLI
   */
  public static AgentCli fromYamlName(String name) {
    for (var cli : values()) {
      if (cli.yamlName.equals(name)) {
        return cli;
      }
    }
    throw new IllegalArgumentException(
        "Unknown agent CLI: '"
            + name
            + "'. Known agents: claude-code, codex."
            + "\n  Check the 'install' list in your sail.yaml agent section.");
  }

  private static String codexModelOptions(String model, String reasoningEffort) {
    var options = new StringBuilder();
    if (model != null) {
      options.append(" --model ").append(model);
    }
    if (reasoningEffort != null) {
      options.append(" --config model_reasoning_effort='\"").append(reasoningEffort).append("\"'");
    }
    return options.toString();
  }

  private static String claudeModelOptions(String model) {
    return model == null ? "" : " --model " + model;
  }
}
