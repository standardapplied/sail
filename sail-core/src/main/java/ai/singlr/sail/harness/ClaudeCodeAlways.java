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
import java.util.Set;
import java.util.regex.Pattern;

/**
 * What allowing a call always would change, in words a person reads correctly, from Claude Code's
 * own {@code permission_suggestions}: only the shapes the S0 probe recorded are said, read
 * strictly, and a request with any other is offered no "always" at all, so a person is never
 * offered more than they read.
 */
final class ClaudeCodeAlways {

  /**
   * Each suggestion's type with every field it was recorded with (02b): a suggestion with any other
   * field is not said, since a field Sail does not know may change what is granted.
   */
  private static final Map<String, Set<String>> FIELDS =
      Map.of(
          "addRules", Set.of("type", "rules", "behavior", "destination"),
          "addDirectories", Set.of("type", "directories", "destination"),
          "setMode", Set.of("type", "mode", "destination"));

  private static final Set<String> RULE_FIELDS = Set.of("toolName", "ruleContent");

  /** Each suggestion's type with the one destination it was recorded keeping it in (02b). */
  private static final Map<String, String> RECORDED =
      Map.of("addRules", "localSettings", "addDirectories", "session", "setMode", "session");

  /**
   * Where a rule is kept ({@code localSettings}): the file Claude Code makes inside the workspace
   * (02b), which every Claude Code started there reads.
   */
  private static final String KEPT =
      "from now on, for every Claude Code chat and terminal in this workspace (kept in the"
          + " workspace's .claude/settings.local.json).";

  /**
   * What an exact Bash rule also covered in probe 2 (01): the command behind {@code timeout},
   * {@code nice} or {@code nohup}, and inside a longer line whose other commands asked nothing
   * ({@code && echo done}). "Such as" stays true of the wrappers its documentation names and no
   * probe ran ({@code time}, {@code stdbuf}).
   */
  private static final String EXACT_ALSO =
      "also when another command such as timeout, nice or nohup starts it, and as part of a longer"
          + " line whose other commands need no asking";

  /**
   * What a prefix Bash rule also covered in probe 2 (01b): the exact rule's reach, and the command
   * behind {@code xargs}, with whatever {@code xargs} is fed added to it; and what it did not: a
   * command it starts that writes to a file through {@code >}, which Claude Code 2.1.332 asked for
   * again with the rule kept, in its own process and in one that read it at start (probe 7, 03:115,
   * 04:49).
   */
  private static final String PREFIX_ALSO =
      "except one that writes to a file with `>`, also when another command such as xargs,"
          + " timeout, nice or nohup starts it, xargs adding whatever it is fed, and as part of a"
          + " longer line whose other commands need no asking";

  /**
   * What a folder kept for the session reaches: the process's life, which ends sooner than the
   * chat, so it says no less than it grants.
   */
  private static final String SESSION = "for this session only.";

  /**
   * What makes a rule compound, {@code &} (and so {@code &&}), {@code ;}, a pipe ({@code |}, and so
   * {@code ||}) or a command substitution ({@code $(}), or a line break, which {@link #quoted}
   * refuses: Claude Code matches each command of one alone, so a kept one may never match again.
   */
  private static final List<String> COMPOUND = List.of("&", ";", "|", "$(");

  /**
   * What makes a command or a rule's command redirect, {@code >} (and so {@code >>}, {@code 2>}) or
   * {@code <} (and so a here-document), read as written, inside quotes too: Claude Code 2.1.332
   * asked again for a command that writes through {@code >} with a kept rule that allowed it, exact
   * (probe 6) or a prefix it suggested itself with no {@code >} in it (probe 7, 01:46, 01:105,
   * 01:144), in its own process and in one that read it at start; what one of the other shapes
   * covers was never recorded.
   */
  private static final List<String> REDIRECTS = List.of(">", "<");

  /**
   * A rule that runs {@code xargs} anywhere a word of it starts, after a wrapper ({@code timeout 5
   * xargs}, {@code nice xargs}, {@code env xargs}) or by its path ({@code /usr/bin/xargs}): what a
   * kept one covers, with whatever {@code xargs} is fed, was never recorded.
   */
  private static final Pattern XARGS = Pattern.compile("(^|[\\s/])xargs(\\s|$)");

  private ClaudeCodeAlways() {}

  /**
   * The one way to allow the call of {@code tool} with {@code input} always that Claude Code
   * offers: all its suggestions, kept together, none when their sentences together are longer than
   * a person is shown whole ({@link LiveStream#CONTENT_CODE_POINTS}), as 02b recorded each of them:
   * a rule that allows a command ({@code addRules}), a folder it may work in ({@code
   * addDirectories}) and a chat mode ({@code setMode}), each kept for the session or in the
   * workspace's own settings file. None when any one is of another shape, since what Sail cannot
   * say a person cannot consent to, and none for a command that {@link #redirects}, whatever rule
   * is suggested for it.
   */
  static List<ChatItem.Always> of(
      String tool, Map<String, Object> input, List<Object> suggestions, List<Harness.Mode> modes) {
    if (redirects(tool, input)) {
      return List.of();
    }
    var words = new ArrayList<String>();
    for (var suggestion : suggestions) {
      var said = sentences(suggestion, modes);
      if (said.isEmpty()) {
        return List.of();
      }
      words.addAll(said);
    }
    return words.isEmpty()
            || words.stream().mapToInt(said -> said.codePointCount(0, said.length())).sum()
                > LiveStream.CONTENT_CODE_POINTS
        ? List.of()
        : List.of(new ChatItem.Always(words));
  }

  /**
   * Why a person asking to allow always the call of {@code tool} with {@code input}, which {@link
   * #of} offers no always, is refused, naming the harness as a person reads it ({@code harness}):
   * it suggested nothing; or the call is a command that {@link #redirects}; or Sail cannot say
   * whole, in plain words, what its suggestions would keep.
   */
  static String refusal(
      String tool, Map<String, Object> input, List<Object> suggestions, String harness) {
    if (redirects(tool, input)) {
      return "Sail does not offer always for a command that redirects with > or <: "
          + harness
          + " asks again for one that writes to a file with >, even with its rule kept; allow it"
          + " once instead.";
    }
    return suggestions.isEmpty()
        ? harness + " offered no way to allow this always; allow it once instead."
        : "Sail does not offer always for this call: it cannot say whole, in plain words, what "
            + harness
            + " would keep; allow it once instead.";
  }

  /**
   * Whether the call of {@code tool} with {@code input} is a Bash command that holds a redirect
   * ({@link #REDIRECTS}), or one whose command is not text: Claude Code may suggest a prefix rule
   * with no {@code >} for a command that writes through one (02b:72, probe 5's 04:17 and 05:16,
   * probe 7's 01:46), and asked for such a command again with that rule kept (probe 7).
   */
  private static boolean redirects(String tool, Map<String, Object> input) {
    return "Bash".equals(tool)
        && !(input.get("command") instanceof String command
            && REDIRECTS.stream().noneMatch(command::contains));
  }

  /**
   * A suggestion's sentences, one for each thing it allows; none for one that cannot be said. Only
   * a type with exactly its recorded fields, kept where it was recorded kept, is said, so past that
   * guard a suggestion is a rule, a folder or a mode ({@code setMode}).
   */
  private static List<String> sentences(Object suggestion, List<Harness.Mode> modes) {
    var said = suggestion instanceof Map<?, ?> map ? map : Map.of();
    var type = String.valueOf(said.get("type"));
    if (!said.keySet().equals(FIELDS.get(type))
        || !RECORDED.get(type).equals(said.get("destination"))) {
      return List.of();
    }
    var sentences =
        switch (type) {
          case "addRules" ->
              "allow".equals(said.get("behavior"))
                  ? listOf(said.get("rules")).stream()
                      .map(rule -> command(rule).map(allowed -> "Allow " + allowed + ", " + KEPT))
                      .toList()
                  : List.<Optional<String>>of();
          case "addDirectories" ->
              listOf(said.get("directories")).stream()
                  .map(
                      folder ->
                          quoted(folder)
                              .map(path -> "Let it work in the folder " + path + ", " + SESSION))
                  .toList();
          default -> List.of(mode(said.get("mode"), modes));
        };
    return sentences.isEmpty() || !sentences.stream().allMatch(Optional::isPresent)
        ? List.of()
        : sentences.stream().map(Optional::orElseThrow).toList();
  }

  /**
   * A Bash rule in plain words, as Claude Code's permission rules read, with what else probe 2
   * recorded it covering: one with no {@code *} allows that exact command; one whose only {@code *}
   * ends it (written {@code " *"}, {@code ":*"} or {@code "*"}) allows every command that starts
   * with what comes before. A compound command is not said: Claude Code matches each of its
   * commands alone, so it may never match again; nor is one that redirects, which Claude Code asked
   * for again with it kept (probes 6 and 7). Nor is a rule {@code xargs} starts, which Claude Code
   * suggests for a pipeline into it (01:108, 01:260): what such a kept rule covers, with whatever
   * it is fed, was never recorded.
   */
  private static Optional<String> command(Object rule) {
    var said = rule instanceof Map<?, ?> map ? map : Map.of();
    if (!said.keySet().equals(RULE_FIELDS)
        || !"Bash".equals(said.get("toolName"))
        || !(said.get("ruleContent") instanceof String content)
        || COMPOUND.stream().anyMatch(content::contains)
        || REDIRECTS.stream().anyMatch(content::contains)
        || XARGS.matcher(content).find()) {
      return Optional.empty();
    }
    var star = content.indexOf('*');
    if (star < 0) {
      return quoted(content).map(exact -> "this exact command: " + exact + ", " + EXACT_ALSO);
    }
    var prefix = content.substring(0, star);
    var stem = (prefix.endsWith(":") ? prefix.substring(0, star - 1) : prefix).stripTrailing();
    return star == content.length() - 1
        ? quoted(stem).map(start -> "any command that starts with " + start + ", " + PREFIX_ALSO)
        : Optional.empty();
  }

  /**
   * {@code text} between backticks, so nothing in it reads as the sentence's own words; empty for
   * what cannot be quoted so: a blank, a backtick that would end the quote, a character that is not
   * drawn as itself (a control or format character, such as a line break or a right-to-left
   * override, a line or paragraph separator, a combining mark that draws over its neighbour, a
   * private-use, unassigned or lone surrogate character), or a {@code ..} that climbs out of a
   * folder.
   */
  private static Optional<String> quoted(Object text) {
    return text instanceof String said
            && !said.isBlank()
            && !said.contains("`")
            && !said.contains("..")
            && said.codePoints().allMatch(ClaudeCodeAlways::drawn)
        ? Optional.of("`" + said + "`")
        : Optional.empty();
  }

  private static boolean drawn(int codePoint) {
    return switch (Character.getType(codePoint)) {
      case Character.CONTROL,
          Character.FORMAT,
          Character.LINE_SEPARATOR,
          Character.PARAGRAPH_SEPARATOR,
          Character.NON_SPACING_MARK,
          Character.ENCLOSING_MARK,
          Character.PRIVATE_USE,
          Character.SURROGATE,
          Character.UNASSIGNED ->
          false;
      default -> true;
    };
  }

  /**
   * A switch to a chat mode, in the words the chat declares for it, and how long it lasts: a
   * resumed chat starts in the mode in force, so it stays for the rest of the chat.
   */
  private static Optional<String> mode(Object mode, List<Harness.Mode> modes) {
    return modes.stream()
        .filter(offered -> offered.name().equals(mode))
        .map(
            offered ->
                "Switch this chat to "
                    + offered.label()
                    + ": "
                    + offered.about()
                    + " It stays for the rest of this chat.")
        .findFirst();
  }

  private static List<?> listOf(Object value) {
    return value instanceof List<?> list ? list : List.of();
  }
}
