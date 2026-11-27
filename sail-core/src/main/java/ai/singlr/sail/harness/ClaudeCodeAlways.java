/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.harness;

import ai.singlr.sail.engine.ChatItem;
import ai.singlr.sail.engine.LiveStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * What allowing a call always would change, in words a person reads correctly, from Claude Code's
 * own {@code permission_suggestions}: only the shapes the S0 probe recorded are said, and a request
 * with any other is offered no "always" at all, so a person is never offered more than they read.
 */
final class ClaudeCodeAlways {

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

  private ClaudeCodeAlways() {}

  /**
   * The one way to allow a call always that Claude Code offers: all its suggestions, kept together,
   * as 02b recorded each of them: a rule that allows a command ({@code addRules}), a folder it may
   * work in ({@code addDirectories}) and a chat mode ({@code setMode}), each kept for the session
   * or in the workspace's own settings file. None when any one is of another shape, since what Sail
   * cannot say a person cannot consent to.
   */
  static List<ChatItem.Always> of(List<Object> suggestions, List<Harness.Mode> modes) {
    var words = new ArrayList<String>();
    for (var suggestion : suggestions) {
      var said = sentences(suggestion, modes);
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
  private static List<String> sentences(Object suggestion, List<Harness.Mode> modes) {
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
              List.of(
                  label(said.get("mode"), modes).map(mode -> "Switch to " + mode + ", " + where));
        };
    return sentences.isEmpty() || !sentences.stream().allMatch(ClaudeCodeAlways::sayable)
        ? List.of()
        : sentences.stream().map(Optional::orElseThrow).toList();
  }

  private static boolean sayable(Optional<String> sentence) {
    return sentence
        .filter(said -> said.codePointCount(0, said.length()) <= LiveStream.CONTENT_CODE_POINTS)
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

  private static Optional<String> label(Object mode, List<Harness.Mode> modes) {
    return modes.stream()
        .filter(offered -> offered.name().equals(mode))
        .map(Harness.Mode::label)
        .findFirst();
  }

  private static List<?> listOf(Object value) {
    return value instanceof List<?> list ? list : List.of();
  }
}
