/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * Every refusal the workspace routes, the terminal ticket route and the terminal door can answer
 * names a command only as a typed part of its remedy, and a route never: a person reads the reason,
 * and the app says a typed fix in words or not at all. The refusals are enumerated from the source
 * of each class on these paths, every site that builds one, so a refusal added later is read too.
 */
class TerminalRefusalsNameNoCommandTest {

  /** The classes these paths refuse in, each read whole unless a method filter is named. */
  private static final Map<String, Pattern> PATHS =
      Map.ofEntries(
          Map.entry("api/TerminalTickets.java", Pattern.compile(".*")),
          Map.entry("api/WorkspaceDoors.java", Pattern.compile(".*")),
          Map.entry("api/HostLanes.java", Pattern.compile(".*")),
          Map.entry("api/SailOperations.java", Pattern.compile(".*")),
          Map.entry("api/ApiRouter.java", Pattern.compile(".*(?:[Ww]orkspace|[Tt]erminal).*")),
          Map.entry("engine/Workspaces.java", Pattern.compile(".*")),
          Map.entry("engine/GitCredentials.java", Pattern.compile(".*")),
          Map.entry("engine/WorkspaceOwner.java", Pattern.compile(".*")),
          Map.entry("../../sail-harness/pty/PtySessionHost.java", Pattern.compile(".*")),
          Map.entry("../../sail-harness/pty/PtyTickets.java", Pattern.compile(".*")),
          Map.entry("../../sail-harness/pty/PtyTcpDoor.java", Pattern.compile(".*")),
          Map.entry("../../sail-core/pty/PtyAdmission.java", Pattern.compile(".*")),
          Map.entry("../../sail-core/pty/PtyMessage.java", Pattern.compile(".*")),
          Map.entry("../../sail-core/engine/NameValidator.java", Pattern.compile(".*")));

  /** What builds a refusal a person may read. */
  private static final Pattern REFUSAL =
      Pattern.compile(
          "(?:new\\s+(?:ApiException|PtyMessage\\.Err|PtyMessage\\.TicketRefused|Err|TicketRefused"
              + "|PtyAdmission\\.Absent|Absent|IllegalStateException|IllegalArgumentException"
              + "|IOException|Refusal)|Remedied\\.(?:state|argument|io)|ApiException\\.of"
              + "|Remedy\\.of)\\s*\\(");

  /** A typed command: its arguments are the command's, never words. */
  private static final Pattern TYPED = Pattern.compile("Remedy\\.(?:sail|client|program)\\s*\\(");

  private static final Pattern COMMAND_LINE =
      Pattern.compile("(?:^|[\\s'`(:\"])sail (?!system\\b)[a-z][a-z-]*");

  private static final Pattern ROUTE = Pattern.compile("/v1/");

  private static final Pattern LITERAL = Pattern.compile("\"((?:[^\"\\\\]|\\\\.)*)\"");

  @Test
  void noRefusalOnTheTerminalPathsSpellsACommandOrARouteInWords() throws IOException {
    var spelled = new ArrayList<String>();
    var read = 0;
    for (var path : PATHS.entrySet()) {
      var file = source(path.getKey());
      var text = uncommented(Files.readString(file));
      for (var body : bodies(text, path.getValue())) {
        var site = REFUSAL.matcher(body);
        while (site.find()) {
          read++;
          var arguments = arguments(body, site.end() - 1);
          for (var words : words(arguments)) {
            if (COMMAND_LINE.matcher(words).find() || ROUTE.matcher(words).find()) {
              spelled.add(file.getFileName() + ": \"" + words + "\"");
            }
          }
        }
      }
    }
    assertTrue(read > 100, "the scan found " + read + " refusal sites; it reads nothing");
    assertEquals(List.of(), spelled, "refusals that spell a command or a route in words");
  }

  /** The literals of {@code arguments} that are words: not the arguments of a typed command. */
  private static List<String> words(String arguments) {
    var typed = TYPED.matcher(arguments);
    var stripped = new StringBuilder();
    var at = 0;
    while (typed.find(at)) {
      stripped.append(arguments, at, typed.start());
      at = typed.end() - 1 + arguments(arguments, typed.end() - 1).length() + 2;
    }
    stripped.append(arguments.substring(Math.min(at, arguments.length())));
    var found = new ArrayList<String>();
    Matcher literal = LITERAL.matcher(stripped);
    while (literal.find()) {
      found.add(literal.group(1));
    }
    return found;
  }

  /** What is between the parenthesis at {@code open} and the one that closes it. */
  private static String arguments(String text, int open) {
    var depth = 0;
    var quoted = false;
    for (var i = open; i < text.length(); i++) {
      var c = text.charAt(i);
      if (c == '\\' && quoted) {
        i++;
      } else if (c == '"') {
        quoted = !quoted;
      } else if (!quoted && c == '(') {
        depth++;
      } else if (!quoted && c == ')' && --depth == 0) {
        return text.substring(open + 1, i);
      }
    }
    return text.substring(open + 1);
  }

  /** The bodies of the methods whose names {@code named} matches; the whole text for any name. */
  private static List<String> bodies(String text, Pattern named) {
    if (named.pattern().equals(".*")) {
      return List.of(text);
    }
    var found = new ArrayList<String>();
    var method =
        Pattern.compile("\\b(\\w+)\\s*\\([^;{)]*\\)\\s*(?:throws[^{;]*)?\\{").matcher(text);
    while (method.find()) {
      if (named.matcher(method.group(1)).matches()) {
        found.add(block(text, method.end() - 1));
      }
    }
    return found;
  }

  private static String block(String text, int open) {
    var depth = 0;
    for (var i = open; i < text.length(); i++) {
      if (text.charAt(i) == '{') {
        depth++;
      } else if (text.charAt(i) == '}' && --depth == 0) {
        return text.substring(open, i + 1);
      }
    }
    return text.substring(open);
  }

  private static String uncommented(String text) {
    return text.replaceAll("(?s)/\\*.*?\\*/", "").replaceAll("(?m)^\\s*//.*$", "");
  }

  private static Path source(String relative) {
    if (relative.startsWith("../../")) {
      var rest = relative.substring("../../".length());
      var module = rest.substring(0, rest.indexOf('/'));
      return Path.of(
          "..", module, "src/main/java/ai/singlr/sail", rest.substring(module.length() + 1));
    }
    return Path.of("src/main/java/ai/singlr/sail", relative);
  }
}
