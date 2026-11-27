/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.harness;

import ai.singlr.sail.config.YamlUtil;
import ai.singlr.sail.engine.ChatItem;
import ai.singlr.sail.engine.ChatItem.Detail;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

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

  /**
   * Where a suggestion keeps what it allows, by each destination recorded: {@code session} for the
   * process's life, and {@code localSettings} in the file Claude Code makes inside the workspace
   * (02b). Any other destination is not said, so its suggestion is not offered.
   */
  private static final Map<String, String> WHERE =
      Map.of(
          "session",
          "for this session only.",
          "localSettings",
          "in this workspace from now on, kept in its folder (.claude/settings.local.json).");

  /** Each suggestion's type with the one destination it was recorded keeping it in (02b). */
  private static final Map<String, String> RECORDED =
      Map.of("addRules", "localSettings", "addDirectories", "session", "setMode", "session");

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
        .map(offered -> offered.args().isBlank() ? List.<String>of() : split(offered.args()))
        .orElseGet(() -> List.of("--permission-mode", mode));
  }

  private static List<String> split(String args) {
    return List.of(args.strip().split(" "));
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
   * {@code file_path} with {@code old_string}, {@code new_string} and {@code replace_all} (Edit) or
   * {@code content} (Write, recorded), {@code file_path} (Read), {@code pattern} and {@code path}
   * (Grep, Glob), {@code query} (WebSearch) and {@code url} (WebFetch), each as its tool's schema
   * names it in a recorded transcript; any other is its input as JSON. An edit is one change:
   * 2.1.332 has no tool that changes several files in one call (its {@code init} lists its tools).
   */
  @Override
  public Detail detail(String tool, Map<String, Object> input) {
    return switch (tool) {
      case "Bash" -> new Detail.Command(field(input, "command"), false);
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

  /**
   * The one way to allow a call always that Claude Code offers: all its suggestions, kept together,
   * as 02b recorded each of them: a rule that allows a command ({@code addRules}), a folder it may
   * work in ({@code addDirectories}) and a chat mode ({@code setMode}), each kept for the session
   * or in the workspace's own settings file. None when any one is of another shape, since what Sail
   * cannot say a person cannot consent to.
   */
  @Override
  public List<ChatItem.Always> always(List<Object> suggestions) {
    var words = new ArrayList<String>();
    for (var suggestion : suggestions) {
      var said = sentences(suggestion);
      if (said.isEmpty()) {
        return List.of();
      }
      words.addAll(said);
    }
    return words.isEmpty() ? List.of() : List.of(new ChatItem.Always(words));
  }

  /**
   * A suggestion's sentences, one for each thing it allows; none for one that cannot be said. Only
   * a type kept where it was recorded kept is said, so past that guard a suggestion is a rule, a
   * folder or a mode ({@code setMode}).
   */
  private List<String> sentences(Object suggestion) {
    var said = suggestion instanceof Map<?, ?> map ? map : Map.of();
    var type = String.valueOf(said.get("type"));
    var destination = String.valueOf(said.get("destination"));
    if (!destination.equals(RECORDED.get(type))) {
      return List.of();
    }
    var where = WHERE.get(destination);
    var sentences =
        switch (type) {
          case "addRules" ->
              "allow".equals(said.get("behavior"))
                  ? listOf(said.get("rules")).stream()
                      .map(rule -> command(rule).map(allowed -> "Allow " + allowed + ", " + where))
                      .toList()
                  : List.<Optional<String>>of();
          case "addDirectories" ->
              listOf(said.get("directories")).stream()
                  .map(
                      folder ->
                          folder(folder)
                              .map(path -> "Let it work in the folder " + path + ", " + where))
                  .toList();
          default ->
              List.of(label(said.get("mode")).map(mode -> "Switch to " + mode + ", " + where));
        };
    return sentences.isEmpty() || !sentences.stream().allMatch(ClaudeCodeChat::sayable)
        ? List.of()
        : sentences.stream().map(Optional::orElseThrow).toList();
  }

  private static boolean sayable(Optional<String> sentence) {
    return sentence
        .filter(said -> said.codePointCount(0, said.length()) <= ChatItem.CONTENT_CODE_POINTS)
        .isPresent();
  }

  /**
   * A Bash rule in plain words, as Claude Code's permission rules read: one with no {@code *}
   * allows that exact command; one whose only {@code *} ends it (written {@code " *"}, {@code ":*"}
   * or {@code "*"}) allows every command that starts with what comes before. Any other is not said.
   */
  private static Optional<String> command(Object rule) {
    var said = rule instanceof Map<?, ?> map ? map : Map.of();
    if (!"Bash".equals(said.get("toolName"))
        || !(said.get("ruleContent") instanceof String content)
        || content.isBlank()) {
      return Optional.empty();
    }
    var star = content.indexOf('*');
    if (star < 0) {
      return Optional.of("this exact command: `" + content + "`");
    }
    var prefix = content.substring(0, star);
    var stem = (prefix.endsWith(":") ? prefix.substring(0, star - 1) : prefix).stripTrailing();
    return star == content.length() - 1 && !stem.isBlank()
        ? Optional.of("any command that starts with `" + stem + "`")
        : Optional.empty();
  }

  private static Optional<String> folder(Object folder) {
    return folder instanceof String path && !path.isBlank() ? Optional.of(path) : Optional.empty();
  }

  private Optional<String> label(Object mode) {
    return modes().stream()
        .filter(offered -> offered.name().equals(mode))
        .map(Harness.Mode::label)
        .findFirst();
  }

  private static List<?> listOf(Object value) {
    return value instanceof List<?> list ? list : List.of();
  }

  private static String field(Map<String, Object> input, String key) {
    return input.get(key) instanceof String text ? text : null;
  }
}
