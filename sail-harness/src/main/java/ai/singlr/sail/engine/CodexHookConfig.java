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
 * Writes a sail-owned Codex CLI hooks file at {@link #SETTINGS_PATH} inside the container. Codex
 * does not (yet) expose a {@code --settings <path>} flag the way Claude Code does — its discovery
 * is fixed to {@code ~/.codex/hooks.json} (plus {@code config.toml} and project-scoped variants).
 * To keep engineer interactive {@code codex} sessions from polluting the spec event bus, the
 * in-container {@link SailEventHelper} script self-gates on the {@code SAIL_SPEC_ID} env var: when
 * the engineer runs {@code codex} themselves the hook still fires but the script exits 0 silently
 * because sail did not set that variable.
 *
 * <p>Hooks wired:
 *
 * <ul>
 *   <li>{@code SessionStart} → {@code agent_session_started}
 *   <li>{@code Stop} → {@code agent_session_stopped}
 * </ul>
 *
 * <p>Codex has no analogue of Claude Code's {@code SessionEnd}, so we do not emit {@code
 * agent_session_completed} for Codex agents. {@link ai.singlr.sail.api.SpecLifecycleReactor}
 * already transitions the spec to {@code review} on {@code stopped}, so the back half of the
 * lifecycle still works.
 */
public final class CodexHookConfig {

  /** Container-side directory holding the hooks file. */
  public static final String SETTINGS_DIR = "/home/dev/.codex";

  /** Hooks filename. */
  public static final String SETTINGS_FILE = "hooks.json";

  /** Container-side absolute path to the hooks file. */
  public static final String SETTINGS_PATH = SETTINGS_DIR + "/" + SETTINGS_FILE;

  private final ShellExec shell;

  public CodexHookConfig(ShellExec shell) {
    this.shell = Objects.requireNonNull(shell, "shell");
  }

  /**
   * Returns the JSON content that {@link #install} writes. Pure function — no I/O. Public for
   * tests.
   */
  public static String render() {
    var sessionStart = hookCommand(SailEventHelper.SCRIPT_PATH, "agent_session_started");
    var stop = hookCommand(SailEventHelper.SCRIPT_PATH, "agent_session_stopped");

    var hooks = new LinkedHashMap<String, Object>();
    hooks.put("SessionStart", List.of(matcherGroup(sessionStart)));
    hooks.put("Stop", List.of(matcherGroup(stop)));

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

  private static Map<String, Object> matcherGroup(Map<String, Object> hook) {
    var group = new LinkedHashMap<String, Object>();
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
