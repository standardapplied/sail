/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.engine;

import ai.singlr.sail.config.YamlUtil;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeoutException;

/**
 * Writes a project-scoped {@code .claude/settings.local.json} into the workspace dir so Claude
 * Code's hook system invokes {@link SailEventHelper} on session lifecycle transitions. The spec id
 * is baked into the hook commands so events are correctly attributed even if the agent runs
 * detached from any sail process.
 *
 * <p>Hooks wired:
 *
 * <ul>
 *   <li>{@code SessionStart} ({@code matcher: startup}) → {@code agent_session_started}
 *   <li>{@code Stop} → {@code agent_session_stopped}
 *   <li>{@code SessionEnd} → {@code agent_session_completed}
 * </ul>
 *
 * <p>The file is written to {@code <workDir>/.claude/settings.local.json}. Claude Code merges this
 * "local" project-scoped settings file with the user's global {@code ~/.claude/settings.json}, so
 * we don't tamper with anything outside the project workspace.
 */
public final class ClaudeCodeHookConfig {

  /** Subdirectory holding the file relative to the agent workdir. */
  public static final String CONFIG_DIR = ".claude";

  /** Filename. */
  public static final String CONFIG_FILE = "settings.local.json";

  private final ShellExec shell;

  public ClaudeCodeHookConfig(ShellExec shell) {
    this.shell = Objects.requireNonNull(shell, "shell");
  }

  /**
   * Returns the JSON content that {@link #install} would write for the given spec id. Pure function
   * — no I/O. Public for tests and {@code sail spec dispatch --show-hooks}.
   */
  public static String render(String specId) {
    if (specId == null || specId.isBlank()) {
      throw new IllegalArgumentException("specId is required");
    }
    var sessionStart = hookCommand(SailEventHelper.SCRIPT_PATH, "agent_session_started", specId);
    var stop = hookCommand(SailEventHelper.SCRIPT_PATH, "agent_session_stopped", specId);
    var sessionEnd = hookCommand(SailEventHelper.SCRIPT_PATH, "agent_session_completed", specId);

    var hooks = new LinkedHashMap<String, Object>();
    hooks.put("SessionStart", List.of(matcherGroup("startup", sessionStart)));
    hooks.put("Stop", List.of(matcherGroup(null, stop)));
    hooks.put("SessionEnd", List.of(matcherGroup(null, sessionEnd)));

    var root = new LinkedHashMap<String, Object>();
    root.put("hooks", hooks);
    return YamlUtil.dumpJson(root);
  }

  /**
   * Idempotently writes {@code <workDir>/.claude/settings.local.json} inside the container. The
   * agent picks it up the next time Claude Code starts in that directory.
   */
  public void install(String container, String workDir, String specId)
      throws IOException, InterruptedException, TimeoutException {
    NameValidator.requireValidProjectName(container);
    Objects.requireNonNull(workDir, "workDir");
    if (workDir.isBlank()) {
      throw new IllegalArgumentException("workDir must not be blank");
    }

    var content = render(specId);
    var configDir = workDir + "/" + CONFIG_DIR;
    var configPath = configDir + "/" + CONFIG_FILE;

    var mkdir = shell.exec(ContainerExec.asDevUser(container, List.of("mkdir", "-p", configDir)));
    if (!mkdir.ok()) {
      throw new IOException(
          "Failed to create " + configDir + " in " + container + ": " + mkdir.stderr());
    }

    var write =
        shell.exec(
            ContainerExec.asDevUser(
                container,
                List.of("bash", "-c", "printf '%s' \"$1\" > \"$2\"", "bash", content, configPath)));
    if (!write.ok()) {
      throw new IOException(
          "Failed to write " + configPath + " in " + container + ": " + write.stderr());
    }
  }

  private static Map<String, Object> matcherGroup(String matcher, Map<String, Object> hook) {
    var group = new LinkedHashMap<String, Object>();
    if (matcher != null) {
      group.put("matcher", matcher);
    }
    group.put("hooks", List.of(hook));
    return group;
  }

  private static Map<String, Object> hookCommand(String script, String eventType, String specId) {
    var hook = new LinkedHashMap<String, Object>();
    hook.put("type", "command");
    hook.put("command", script + " " + eventType + " " + specId);
    hook.put("timeout", 10);
    return hook;
  }
}
