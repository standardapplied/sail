/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.harness;

import ai.singlr.sail.common.Strings;
import ai.singlr.sail.config.YamlUtil;
import ai.singlr.sail.engine.ChatItem;
import ai.singlr.sail.engine.ChatItem.Detail;
import ai.singlr.sail.engine.LiveStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Claude Code in a chat, as the S0 probe recorded 2.1.332: its streaming mode ({@link
 * ClaudeCode#STREAMING}) with every request a person answers relayed on its stdout ({@code
 * --permission-prompt-tool stdio}: without it nothing is asked, and each call its mode does not
 * allow is refused), and with no {@code initialize} sent first, which it does not need. It runs
 * under the live member's settings: the read-deny rules, and no hook to race the person's answer.
 * Never {@code --bare}, which ignores a sign-in the workspace stored, drops {@code CLAUDE.md} and
 * the person's skills and cuts the tools. A subagent's text comes whole ({@code
 * --forward-subagent-text}), and can come after the turn that started it ended, before the turn
 * Claude Code then begins itself. Its process outlives a closed stdin while a background task it
 * started runs, and begins a turn of its own when that task ends.
 */
final class ClaudeCodeChat implements HarnessChat {

  /** The prefix Claude Code gives the name of every tool an MCP server serves. */
  private static final String MCP_TOOL = "mcp__";

  /** Where Claude Code keeps a rule it suggests for {@code localSettings} (recorded). */
  private static final String HERE =
      "in this workspace from now on (kept in .claude/settings.local.json)";

  @Override
  public List<String> argv(Launch launch) {
    var mode = Harness.Mode.named(modes(), launch.mode(), "A chat with Claude Code");
    var command = new ArrayList<>(ClaudeCode.STREAMING);
    command.addAll(List.of("--settings", ClaudeCode.LIVE_SETTINGS_PATH, "--forward-subagent-text"));
    if (launch.model() != null) {
      command.addAll(List.of("--model", launch.model()));
    }
    command.addAll(List.of("--permission-prompt-tool", "stdio"));
    if (!mode.args().isBlank()) {
      command.addAll(List.of(mode.args().strip().split(" ")));
    }
    launch.resume().ifPresent(id -> command.addAll(List.of("--resume", id)));
    return List.copyOf(command);
  }

  /**
   * Claude Code's own permission modes that were recorded working in a chat: its {@code default},
   * which a start with no mode is in, first. A workspace's settings may name another default, so
   * the mode a chat is in is the one Claude Code says ({@code init}, {@code status}). In plan mode
   * the recorded turns ran on a model other than the one {@code --model} named, until the plan was
   * approved. Never asks ({@code bypassPermissions}) is not offered: it was never recorded at a
   * chat's start or as a switch.
   */
  @Override
  public List<Harness.Mode> modes() {
    return List.of(
        new Harness.Mode(
            "default",
            "Asks first",
            "Asks before edits, shell commands and network actions.",
            "",
            false),
        ClaudeCode.permissionMode(
            "acceptEdits",
            "Edits freely",
            "Makes file edits and common filesystem commands without asking, and asks before the"
                + " rest.",
            false),
        ClaudeCode.permissionMode(
            "plan",
            "Plans first",
            "Researches and proposes a plan, and changes nothing until you approve it.",
            false));
  }

  /** What each of Claude Code's tools does, by its name; a tool an MCP server serves is MCP's. */
  @Override
  public ChatItem.ToolKind kind(String tool) {
    return switch (tool) {
      case "Bash" -> ChatItem.ToolKind.COMMAND;
      case "Edit", "Write", "NotebookEdit" -> ChatItem.ToolKind.EDIT;
      case "Read" -> ChatItem.ToolKind.READ;
      case "Grep", "Glob" -> ChatItem.ToolKind.SEARCH;
      case "WebFetch", "WebSearch" -> ChatItem.ToolKind.WEB;
      case "Agent", "Task" -> ChatItem.ToolKind.AGENT;
      default -> tool.startsWith(MCP_TOOL) ? ChatItem.ToolKind.MCP : ChatItem.ToolKind.OTHER;
    };
  }

  /**
   * A call's detail from its input as Claude Code names it: {@code command} (Bash, recorded),
   * {@code file_path} with {@code old_string} and {@code new_string} (Edit) or {@code content}
   * (Write, recorded), {@code file_path} (Read), {@code pattern} and {@code path} (Grep, Glob),
   * {@code query} (WebSearch) and {@code url} (WebFetch), each as its tool's schema names it in a
   * recorded transcript; any other is its input as JSON.
   */
  @Override
  public Detail detail(String tool, Map<String, Object> input) {
    return switch (tool) {
      case "Bash" -> new Detail.Command(field(input, "command"));
      case "Edit" ->
          new Detail.Edit(
              field(input, "file_path"), field(input, "old_string"), field(input, "new_string"));
      case "Write" -> new Detail.Edit(field(input, "file_path"), null, field(input, "content"));
      case "Read" -> new Detail.Read(field(input, "file_path"));
      case "Grep", "Glob" -> new Detail.Search(field(input, "pattern"), field(input, "path"));
      case "WebSearch" -> new Detail.Search(field(input, "query"), null);
      case "WebFetch" -> new Detail.Fetch(field(input, "url"));
      default -> new Detail.Other(tool, clip(YamlUtil.dumpJsonValue(input)));
    };
  }

  /**
   * The words of each of Claude Code's suggestions: a rule that allows a call ({@code addRules}), a
   * directory it may work in ({@code addDirectories}) and a mode ({@code setMode}), each kept for
   * the session or in the workspace's own settings file, as one "always" on {@code touch} in 02b
   * suggested all three. Any other is named by its type.
   */
  @Override
  public List<String> always(List<Object> suggestions) {
    return suggestions.stream().map(this::words).toList();
  }

  private String words(Object suggestion) {
    var said = suggestion instanceof Map<?, ?> map ? map : Map.of();
    var type = String.valueOf(said.get("type"));
    var where = where(said.get("destination"));
    if ("addRules".equals(type) && "allow".equals(said.get("behavior"))) {
      return "Allow " + rules(said.get("rules")) + " " + where;
    }
    return switch (type) {
      case "addDirectories" -> "Let it work in " + listed(said.get("directories")) + " " + where;
      case "setMode" -> "Switch to " + label(said.get("mode")) + " " + where;
      default -> "Change Claude Code's permissions (" + brief(type) + ") " + where;
    };
  }

  private static String where(Object destination) {
    return switch (String.valueOf(destination)) {
      case "session" -> "for this session";
      case "localSettings" -> HERE;
      default -> "from now on (in Claude Code's " + brief(String.valueOf(destination)) + ")";
    };
  }

  private static String rules(Object rules) {
    return listOf(rules).stream()
        .map(rule -> rule instanceof Map<?, ?> map ? map : Map.of())
        .map(
            rule ->
                rule.get("ruleContent") instanceof String content
                    ? ruled(String.valueOf(rule.get("toolName")), brief(content))
                    : "every " + brief(String.valueOf(rule.get("toolName"))) + " call")
        .collect(Collectors.joining(", "));
  }

  private static String ruled(String tool, String content) {
    return "Bash".equals(tool) ? "`" + content + "`" : brief(tool) + "(" + content + ")";
  }

  private static String listed(Object values) {
    return listOf(values).stream()
        .map(value -> brief(String.valueOf(value)))
        .collect(Collectors.joining(", "));
  }

  private String label(Object mode) {
    return modes().stream()
        .filter(offered -> offered.name().equals(mode))
        .map(Harness.Mode::label)
        .findFirst()
        .orElseGet(() -> brief(String.valueOf(mode)));
  }

  private static List<?> listOf(Object value) {
    return value instanceof List<?> list ? list : List.of();
  }

  private static String field(Map<String, Object> input, String key) {
    return input.get(key) instanceof String text ? clip(text) : null;
  }

  private static String clip(String text) {
    return Strings.clip(text, LiveStream.CONTENT_CODE_POINTS);
  }

  private static String brief(String text) {
    return Strings.clip(text, LiveStream.SUMMARY_CODE_POINTS);
  }
}
