/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.engine;

import ai.singlr.sail.common.Strings;

/**
 * Known AI coding agent CLIs that can be installed inside a project container. Each constant
 * carries the metadata needed to install and invoke the agent: the YAML config name, the binary
 * name on PATH, the installation method, and the shell command to install it.
 */
public enum AgentCli {
  CLAUDE_CODE(
      "claude-code", "claude", "curl -fsSL https://claude.ai/install.sh | bash", "CLAUDE.md"),
  CODEX(
      "codex",
      "codex",
      "curl -fsSL https://chatgpt.com/codex/install.sh | CODEX_NON_INTERACTIVE=1 sh",
      "AGENTS.md");

  private final String yamlName;
  private final String binaryName;
  private final String installCommand;
  private final String contextFileName;

  AgentCli(String yamlName, String binaryName, String installCommand, String contextFileName) {
    this.yamlName = yamlName;
    this.binaryName = binaryName;
    this.installCommand = installCommand;
    this.contextFileName = contextFileName;
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

  /** The context file name for this agent (e.g., "CLAUDE.md", "AGENTS.md"). */
  public String contextFileName() {
    return contextFileName;
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
    var task = "\"$(cat " + taskFile + ")\"";
    return switch (this) {
      case CLAUDE_CODE -> {
        requireNoModelOptions(model, reasoningEffort);
        var perm = fullPermissions ? " --dangerously-skip-permissions" : "";
        var settings =
            Strings.isBlank(claudeSettingsPath) ? "" : " --settings " + claudeSettingsPath;
        yield binaryName + " --print" + settings + perm + " -p " + task;
      }
      case CODEX -> {
        var perm = fullPermissions ? " --dangerously-bypass-approvals-and-sandbox" : "";
        yield binaryName + " exec" + perm + codexModelOptions(model, reasoningEffort) + " " + task;
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

  private void requireNoModelOptions(String model, String reasoningEffort) {
    if (model != null || reasoningEffort != null) {
      throw new IllegalArgumentException(
          yamlName + " does not support spec-level model or reasoning_effort options yet.");
    }
  }
}
