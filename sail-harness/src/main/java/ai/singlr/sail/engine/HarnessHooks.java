/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.engine;

import ai.singlr.sail.config.YamlUtil;
import ai.singlr.sail.harness.Harness;
import ai.singlr.sail.harness.HookFile;
import ai.singlr.sail.harness.SailHook;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeoutException;

/**
 * Writes a harness's sail-owned file of hooks inside the container, from the {@link HookFile} the
 * harness declares: which of its events run which {@link SailHook}, each rendered here as the
 * script sail installs for it. The file is the one hooks layer for every sail-launched session of
 * that harness, whichever lane launched it: a build, a reviewer, a fix agent, a chat turn each
 * export the same {@code SAIL_SPEC_ID}, {@code SAIL_RUN_ID}, {@code SAIL_RUN_ROLE} and {@code
 * SAIL_RUN_CREDENTIAL}, never a second settings file, so every lane's tool calls reset its
 * watcher's stall timer the same way. An engineer's own session exports none of them and every hook
 * is inert. The scripts self-gate on those variables, which keeps the file install-once at
 * provision or {@code sail project apply} rather than rewritten per dispatch.
 *
 * <p>The tool hooks are the dispatch watcher's liveness signal: {@code AgentWatchCommand} resets
 * its stall timer on {@code agent_tool_started}/{@code agent_tool_finished}, so a working agent
 * pushes the {@code max_idle} deadline out on every tool call. Without them the stall timer counts
 * from launch and kills even a busy agent at {@code max_idle}.
 */
public final class HarnessHooks {

  private record Script(String command, int timeout) {}

  private final ShellExec shell;

  public HarnessHooks(ShellExec shell) {
    this.shell = Objects.requireNonNull(shell, "shell");
  }

  /**
   * Returns the JSON content that {@link #install} writes for {@code harness}. Pure function, no
   * I/O: the file's settings, then {@code hooks}, one key per event in the order the groups first
   * name it.
   */
  public static String render(Harness harness) {
    return render(harness.hooks(), harness);
  }

  private static String render(HookFile file, Harness harness) {
    var hooks = new LinkedHashMap<String, List<Map<String, Object>>>();
    for (var group : file.groups()) {
      hooks
          .computeIfAbsent(group.event(), event -> new ArrayList<>())
          .add(renderGroup(group, harness));
    }
    var root = new LinkedHashMap<String, Object>(file.settings());
    root.put("hooks", hooks);
    return YamlUtil.dumpJson(root);
  }

  /** Idempotently writes the hook file of {@code harness} at its declared path in the container. */
  public void install(String container, Harness harness)
      throws IOException, InterruptedException, TimeoutException {
    NameValidator.requireValidProjectName(container);
    var file = harness.hooks();
    var path = file.path();
    var dir = file.directory();

    var mkdir = shell.exec(ContainerExec.asDevUser(container, List.of("mkdir", "-p", dir)));
    if (!mkdir.ok()) {
      throw new IOException("Failed to create " + dir + " in " + container + ": " + mkdir.stderr());
    }

    var write =
        shell.exec(
            ContainerExec.asDevUser(
                container,
                List.of(
                    "bash",
                    "-c",
                    "printf '%s' \"$1\" > \"$2\"",
                    "bash",
                    render(file, harness),
                    path)));
    if (!write.ok()) {
      throw new IOException("Failed to write " + path + " in " + container + ": " + write.stderr());
    }
  }

  private static Map<String, Object> renderGroup(HookFile.Group group, Harness harness) {
    var rendered = new LinkedHashMap<String, Object>();
    if (group.matcher() != null) {
      rendered.put("matcher", group.matcher());
    }
    rendered.put("hooks", group.hooks().stream().map(hook -> renderHook(hook, harness)).toList());
    return rendered;
  }

  private static Map<String, Object> renderHook(SailHook hook, Harness harness) {
    var script = script(hook, harness);
    var rendered = new LinkedHashMap<String, Object>();
    rendered.put("type", "command");
    rendered.put("command", script.command());
    rendered.put("timeout", script.timeout());
    return rendered;
  }

  private static Script script(SailHook hook, Harness harness) {
    return switch (hook) {
      case SESSION_STARTED -> event("agent_session_started");
      case SESSION_REPORT ->
          new Script(
              SailSessionReport.SCRIPT_PATH + " " + harness.yamlName(),
              SailSessionReport.HOOK_TIMEOUT_SECONDS);
      case TOOL_STARTED -> event("agent_tool_started");
      case TOOL_FINISHED -> event("agent_tool_finished");
      case ROOM_RELAY -> new Script(SailRoomRelay.SCRIPT_PATH, SailRoomRelay.HOOK_TIMEOUT_SECONDS);
      case STOP_GATE -> new Script(SailStopGate.SCRIPT_PATH, SailStopGate.HOOK_TIMEOUT_SECONDS);
      case BATCH_RESOLVED -> event(SailEventHelper.BATCH_RESOLVED);
      case SESSION_ENDED -> event("agent_session_completed");
    };
  }

  private static Script event(String eventType) {
    return new Script(SailEventHelper.SCRIPT_PATH + " " + eventType, 10);
  }
}
