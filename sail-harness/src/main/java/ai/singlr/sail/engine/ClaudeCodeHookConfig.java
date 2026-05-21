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
 * Writes a sail-owned Claude Code settings file at {@link #SETTINGS_PATH} inside the container.
 * Passed to {@code claude} via {@code --settings} only when sail launches the agent — engineer SSH
 * sessions that run bare {@code claude} never see these hooks, so their {@code Stop} events do not
 * leak into the spec event bus.
 *
 * <p>Hooks wired:
 *
 * <ul>
 *   <li>{@code SessionStart} ({@code matcher: startup}) → {@code agent_session_started}
 *   <li>{@code Stop} → {@code agent_session_stopped}
 *   <li>{@code SessionEnd} → {@code agent_session_completed}
 * </ul>
 *
 * <p>Spec attribution flows in via the {@code SAIL_SPEC_ID} env var that sail sets at launch — no
 * spec id is baked into the hook commands, so the file is install-once at provision/sync time
 * rather than rewritten on every dispatch.
 */
public final class ClaudeCodeHookConfig {

  /** Container-side directory holding the settings file. */
  public static final String SETTINGS_DIR = "/home/dev/.sail";

  /** Settings filename. */
  public static final String SETTINGS_FILE = "claude-settings.json";

  /** Container-side absolute path to the settings file. Used with {@code claude --settings}. */
  public static final String SETTINGS_PATH = SETTINGS_DIR + "/" + SETTINGS_FILE;

  private final ShellExec shell;

  public ClaudeCodeHookConfig(ShellExec shell) {
    this.shell = Objects.requireNonNull(shell, "shell");
  }

  /**
   * Returns the JSON content that {@link #install} writes. Pure function — no I/O. Public for tests
   * and {@code sail spec dispatch --show-hooks}.
   */
  public static String render() {
    var sessionStart = hookCommand(SailEventHelper.SCRIPT_PATH, "agent_session_started");
    var stop = hookCommand(SailEventHelper.SCRIPT_PATH, "agent_session_stopped");
    var sessionEnd = hookCommand(SailEventHelper.SCRIPT_PATH, "agent_session_completed");

    var hooks = new LinkedHashMap<String, Object>();
    hooks.put("SessionStart", List.of(matcherGroup("startup", sessionStart)));
    hooks.put("Stop", List.of(matcherGroup(null, stop)));
    hooks.put("SessionEnd", List.of(matcherGroup(null, sessionEnd)));

    var root = new LinkedHashMap<String, Object>();
    root.put("hooks", hooks);
    return YamlUtil.dumpJson(root);
  }

  /**
   * Idempotently writes {@link #SETTINGS_PATH} inside the container. Install-once at provision or
   * {@code sail project sync}; not rewritten per dispatch.
   */
  public void install(String container) throws IOException, InterruptedException, TimeoutException {
    NameValidator.requireValidProjectName(container);

    var mkdir =
        shell.exec(ContainerExec.asDevUser(container, List.of("mkdir", "-p", SETTINGS_DIR)));
    if (!mkdir.ok()) {
      throw new IOException(
          "Failed to create " + SETTINGS_DIR + " in " + container + ": " + mkdir.stderr());
    }

    var write =
        shell.exec(
            ContainerExec.asDevUser(
                container,
                List.of(
                    "bash", "-c", "printf '%s' \"$1\" > \"$2\"", "bash", render(), SETTINGS_PATH)));
    if (!write.ok()) {
      throw new IOException(
          "Failed to write " + SETTINGS_PATH + " in " + container + ": " + write.stderr());
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

  private static Map<String, Object> hookCommand(String script, String eventType) {
    var hook = new LinkedHashMap<String, Object>();
    hook.put("type", "command");
    hook.put("command", script + " " + eventType);
    hook.put("timeout", 10);
    return hook;
  }
}
