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
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * No refusal names a command line or an HTTP route in words, and none written for a person carries
 * a tool's raw output. Driven from the server's own sources, not a list of files: every main source
 * file of every module is read, each site that builds a refusal or an exception is found in it,
 * {@code Wording} and {@code Policy} (the access refusals' sentences) are read whole, and a command
 * line is a {@code sail} followed by one of the CLI's own verbs, enumerated from its commands. A
 * command is named only as a typed part of a remedy, which the app says in words or not at all.
 *
 * <p>A failure written for no person (a plain exception) may carry a tool's output: the doors pass
 * only words written for a person ({@code Remedied}) and log the rest ({@code WorkspaceDoors},
 * {@code PtySessionHost}), so only a refusal written for a person is held to that rule.
 */
class RefusalsNameNoCommandTest {

  /** Builds a refusal a person reads. */
  private static final Pattern WRITTEN_FOR_A_PERSON =
      Pattern.compile(
          "(?:new\\s+(?:ApiException|Result\\.Failure|Refusal|PtyMessage\\.Err|Err"
              + "|PtyMessage\\.TicketRefused|TicketRefused|PtyAdmission\\.Absent|Absent"
              + "|PtyAdmission\\.Unavailable|IllegalArgumentException)"
              + "|Remedied\\.(?:state|argument|io)|Remedy\\.of)\\s*[(<]");

  /** Builds a failure for no one in particular, which a door logs. */
  private static final Pattern PLAIN =
      Pattern.compile(
          "new\\s+(?:IOException|UncheckedIOException|IllegalStateException|RuntimeException)"
              + "\\s*\\(");

  private static final Pattern TYPED = Pattern.compile("Remedy\\.(?:sail|client|program)\\s*\\(");

  private static final Pattern VERB =
      Pattern.compile("@Command\\(\\s*name\\s*=\\s*\"([a-z][a-z-]*)\"");

  private static final Pattern ROUTE = Pattern.compile("/v1/");

  private static final Pattern RAW = Pattern.compile("\\.stderr\\s*\\(");

  private static final Pattern LITERAL = Pattern.compile("\"((?:[^\"\\\\]|\\\\.)*)\"");

  private static final Set<String> SENTENCES = Set.of("Wording.java", "Policy.java");

  @Test
  void noRefusalSpellsACommandOrARouteAndNonePutsAToolsOutputBeforeAPerson() throws IOException {
    var sources = sources();
    var commandLine = commandLine(sources);
    var spelled = new ArrayList<String>();
    var sites = 0;
    var sentenceFiles = new TreeSet<String>();
    for (var file : sources) {
      var name = file.getFileName().toString();
      var text = uncommented(Files.readString(file));
      if (SENTENCES.contains(name)) {
        sentenceFiles.add(name);
        sites += spelled(name, text, commandLine, spelled);
      }
      sites += scan(name, text, WRITTEN_FOR_A_PERSON, commandLine, true, spelled);
      sites += scan(name, text, PLAIN, commandLine, false, spelled);
    }
    assertEquals(SENTENCES, sentenceFiles, "the access refusals' sentences were read");
    assertTrue(sites > 1500, "the scan found " + sites + " refusal sites; it reads nothing");
    assertEquals(List.of(), spelled, "refusals that name a command, a route or a tool's output");
  }

  /** Reads every refusal {@code site} builds in {@code text}; the count of sites read. */
  private static int scan(
      String name,
      String text,
      Pattern site,
      Pattern commandLine,
      boolean forAPerson,
      List<String> spelled) {
    var found = site.matcher(text);
    var read = 0;
    while (found.find()) {
      read++;
      var open = text.indexOf('(', found.end() - 1);
      var arguments = typedStripped(arguments(text, open));
      spelled(name, arguments, commandLine, spelled);
      if (forAPerson && RAW.matcher(arguments).find()) {
        spelled.add(name + ": a tool's output in " + arguments.strip().replaceAll("\\s+", " "));
      }
    }
    return read;
  }

  /** Adds each literal of {@code text} that names a command or a route; the count of literals. */
  private static int spelled(String name, String text, Pattern commandLine, List<String> spelled) {
    var literal = LITERAL.matcher(text);
    var read = 0;
    while (literal.find()) {
      read++;
      var words = literal.group(1);
      if (commandLine.matcher(words).find() || ROUTE.matcher(words).find()) {
        spelled.add(name + ": \"" + words + "\"");
      }
    }
    return read;
  }

  /** A {@code sail} followed by one of the CLI's own verbs. */
  private static Pattern commandLine(List<Path> sources) throws IOException {
    var verbs = new TreeSet<String>();
    for (var file : sources) {
      var verb = VERB.matcher(Files.readString(file));
      while (verb.find()) {
        verbs.add(verb.group(1));
      }
    }
    assertTrue(verbs.size() > 50, "the CLI's verbs: " + verbs);
    return Pattern.compile("(?:^|[\\s'`(:\"])sail (?:" + String.join("|", verbs) + ")\\b");
  }

  /** {@code arguments} without the arguments of a typed command, which are the command's. */
  private static String typedStripped(String arguments) {
    var typed = TYPED.matcher(arguments);
    var stripped = new StringBuilder();
    var at = 0;
    while (typed.find(at)) {
      stripped.append(arguments, at, typed.start());
      at = typed.end() - 1 + arguments(arguments, typed.end() - 1).length() + 2;
    }
    return stripped.append(arguments.substring(Math.min(at, arguments.length()))).toString();
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

  private static String uncommented(String text) {
    return text.replaceAll("(?s)/\\*.*?\\*/", "").replaceAll("(?m)^\\s*//.*$", "");
  }

  /** Every main Java source of every module beside this one. */
  private static List<Path> sources() throws IOException {
    var found = new ArrayList<Path>();
    try (var modules = Files.list(Path.of(".."))) {
      for (var module : modules.toList()) {
        var main = module.resolve("src/main/java");
        if (Files.isDirectory(main)) {
          try (Stream<Path> files = Files.walk(main)) {
            files.filter(file -> file.toString().endsWith(".java")).forEach(found::add);
          }
        }
      }
    }
    assertTrue(found.size() > 500, "the sources read: " + found.size());
    return found;
  }
}
