/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.harness;

import ai.singlr.sail.config.YamlUtil;
import ai.singlr.sail.engine.CallFields;
import ai.singlr.sail.engine.ChatItem;
import ai.singlr.sail.engine.ChatItem.Detail;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

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

  /**
   * The fields each tool's detail draws, of their types: a call holding any other is drawn whole
   * ({@link CallFields#drawnWhole}), since allowing it hands its whole input back.
   */
  private static final Map<String, Map<String, Class<?>>> DRAWN =
      Map.of(
          "Bash", Map.of("command", String.class, "run_in_background", Boolean.class),
          "Edit",
              Map.of(
                  "file_path", String.class,
                  "old_string", String.class,
                  "new_string", String.class,
                  "replace_all", Boolean.class),
          "Write", Map.of("file_path", String.class, "content", String.class),
          "Read", Map.of("file_path", String.class),
          "Grep", Map.of("pattern", String.class, "path", String.class),
          "Glob", Map.of("pattern", String.class, "path", String.class),
          "WebSearch", Map.of("query", String.class),
          "WebFetch", Map.of("url", String.class));

  /** The prefix Claude Code gives the name of every tool an MCP server serves. */
  private static final String MCP_TOOL = "mcp__";

  private final String name;

  /** The chat of the harness a person reads as {@code name}. */
  ClaudeCodeChat(String name) {
    this.name = name;
  }

  /**
   * A chat's argv: a chat mode's own flags, or {@code --permission-mode} with the mode as Claude
   * Code named it when it is none of them, since a resumed chat starts in the mode it was last in.
   */
  @Override
  public List<String> argv(Launch launch) {
    var command = new ArrayList<>(ClaudeCode.STREAMING);
    command.addAll(List.of("--settings", ClaudeCode.LIVE_SETTINGS_PATH, "--forward-subagent-text"));
    if (launch.model() != null) {
      command.addAll(List.of("--model", launch.model()));
    }
    command.addAll(List.of("--permission-prompt-tool", "stdio"));
    command.addAll(modeArgs(launch.mode()));
    launch.resume().ifPresent(id -> command.addAll(List.of("--resume", id)));
    return List.copyOf(command);
  }

  private List<String> modeArgs(String mode) {
    return modes().stream()
        .filter(offered -> offered.name().equals(mode))
        .findFirst()
        .map(offered -> split(offered.args()))
        .orElseGet(() -> List.of("--permission-mode", mode));
  }

  private static List<String> split(String args) {
    return List.of(args.strip().split(" "));
  }

  /**
   * Claude Code's own permission modes that were recorded working in a chat: its {@code default}
   * first, named at the start as {@code manual}, the name its {@code --help} lists for it, so a
   * workspace's own default never overrides the person's choice: probe 3 recorded the launch taken,
   * {@code init} then saying {@code default}, and an edit and a command each asking in a workspace
   * whose settings default to {@code acceptEdits} (01a, 01c). {@code --permission-mode default},
   * which {@code --help} does not list, was taken the same way (01d). The mode a chat is in is the
   * one Claude Code says ({@code init}, {@code status}). In plan mode the recorded turns ran on a
   * model other than the one {@code --model} named, until the plan was approved. Never asks ({@code
   * bypassPermissions}) is not offered: it was never recorded at a chat's start or as a switch.
   * Each mode's words, one line, say only what a recording shows it doing (asserted, claim by
   * claim, in {@code LiveChatTest.everyModeWordIsARecordedDecision}): Asks first asked before a
   * Write and a command, and ran {@code sleep} and {@code echo} unasked; Edits freely ran a Write
   * and {@code rm}, {@code mv}, {@code cp}, {@code sed -i} and {@code nice rm} inside the workspace
   * unasked, and asked before {@code rm} outside it. Its words claim nothing of elsewhere: its
   * documentation's {@code additionalDirectories} are not named, and no word says it is limited to
   * the workspace.
   */
  /**
   * Yes, the owner's stated default: internal staff may chat on the sign-in each made in their own
   * workspace. A ruling otherwise changes this line.
   */
  @Override
  public boolean signsInInside() {
    return true;
  }

  @Override
  public List<Harness.Mode> modes() {
    return List.of(
        new Harness.Mode(
            "default",
            "Asks first",
            "Asks before edits and commands, except safe ones.",
            " --permission-mode manual",
            false),
        ClaudeCode.permissionMode(
            "acceptEdits",
            "Edits freely",
            "Changes files in this workspace without asking.",
            false),
        ClaudeCode.permissionMode(
            "plan", "Plans first", "Changes nothing until you approve its plan.", false));
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
   * A call's detail from its input as Claude Code names it: {@code command} and {@code
   * run_in_background} (Bash, recorded), {@code file_path} with {@code old_string}, {@code
   * new_string} and {@code replace_all} (Edit) or {@code content} (Write, recorded), {@code
   * file_path} (Read), {@code pattern} and {@code path} (Grep, Glob), {@code query} (WebSearch) and
   * {@code url} (WebFetch), each as its tool's schema names it in a recorded transcript; any other
   * is its input as JSON. An edit is one change: 2.1.332 has no tool that changes several files in
   * one call (its {@code init} lists its tools).
   */
  @Override
  public Detail detail(String tool, Map<String, Object> input) {
    if (!CallFields.drawnWhole(tool, input, DRAWN.getOrDefault(tool, Map.of()))) {
      return new Detail.Other(tool, YamlUtil.dumpJsonValue(input), false);
    }
    return switch (tool) {
      case "Bash" ->
          new Detail.Command(
              field(input, "command"), Boolean.TRUE.equals(input.get("run_in_background")), false);
      case "Edit" ->
          edit(
              field(input, "file_path"),
              field(input, "old_string"),
              field(input, "new_string"),
              Boolean.TRUE.equals(input.get("replace_all")));
      case "Write" -> edit(field(input, "file_path"), null, field(input, "content"), false);
      case "Read" -> new Detail.Read(field(input, "file_path"), false);
      case "Grep", "Glob" ->
          new Detail.Search(field(input, "pattern"), field(input, "path"), false);
      case "WebSearch" -> new Detail.Search(field(input, "query"), null, false);
      case "WebFetch" -> new Detail.Fetch(field(input, "url"), false);
      default -> new Detail.Other(tool, YamlUtil.dumpJsonValue(input), false);
    };
  }

  private static Detail edit(String path, String before, String after, boolean everywhere) {
    return new Detail.Edit(
        List.of(
            new ChatItem.Change(
                ChatItem.ChangeKind.CHANGE, path, null, before, after, everywhere, false)));
  }

  /** The ways to allow a call always that Claude Code's {@code suggestions} offer. */
  @Override
  public List<ChatItem.Always> always(
      String tool, Map<String, Object> input, List<Object> suggestions) {
    return ClaudeCodeAlways.of(tool, input, suggestions, modes());
  }

  /** Why a call Claude Code's card offers no always for is not allowed always, in its name. */
  @Override
  public String noAlways(String tool, Map<String, Object> input, List<Object> suggestions) {
    return ClaudeCodeAlways.refusal(tool, input, suggestions, name);
  }

  private static String field(Map<String, Object> input, String key) {
    return input.get(key) instanceof String text ? text : null;
  }
}
