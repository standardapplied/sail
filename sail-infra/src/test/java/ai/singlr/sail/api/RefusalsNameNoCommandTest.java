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
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * The second line behind the doors' rule (only words written for a person reach one, in {@code
 * WorkspaceDoors} and {@code PtySessionHost}): no refusal a person in the app can read names a
 * command line or an HTTP route in its words, and none written for a person is built from a tool's
 * output.
 *
 * <p>What it reads: every main source of every module, less what only an operator reads, and, in a
 * file that registers routes, only the handlers of the routes the app calls ({@link #APP_ROUTES}),
 * found through their own registration. Operator-only code is computed, not listed: the CLI's
 * commands, every class referenced only from operator-only code, and every method called only from
 * it. {@code Wording} and {@code Policy}, the access refusals' sentences, are read whole.
 *
 * <p>What it catches: a {@code sail} followed by one of the CLI's own verbs (enumerated from its
 * commands), and {@code /v1/}, in the words of any refusal or exception it reads; and, in a refusal
 * written for a person, a tool's output, which is a {@code ShellExec.Result}'s {@code stderr()} or
 * {@code reason()} (its stderr), directly or through a helper in the same file that returns one.
 * What it does not catch: tool output carried further than that (through a field, another file, or
 * an exception's message re-said at run time); the doors' rule and their tests hold those.
 */
class RefusalsNameNoCommandTest {

  /** The routes the app calls, as registered: method and path expression. */
  private static final List<String> APP_ROUTES =
      List.of(
          "GET, \"/v1/whoami\"",
          "GET, \"/v1/projects\"",
          "GET, PROJECT + \"/workspaces\"",
          "POST, PROJECT + \"/workspaces\"",
          "POST, \"/v1/terminal/tickets\"",
          "POST, \"/v1/auth/login/start\"",
          "POST, \"/v1/auth/login/finish\"");

  /** Builds a refusal written for a person. */
  private static final Pattern WRITTEN_FOR_A_PERSON =
      Pattern.compile(
          "(?:new\\s+(?:ApiException|Result\\.Failure|Refusal|PtyMessage\\.Err|Err"
              + "|PtyMessage\\.TicketRefused|TicketRefused|PtyAdmission\\.Absent|Absent"
              + "|PtyAdmission\\.Unavailable|IllegalArgumentException)"
              + "|Remedied\\.(?:state|argument|io)|Remedy\\.of)\\s*[(<]");

  /** Builds a failure written for no one in particular, which a door logs. */
  private static final Pattern PLAIN =
      Pattern.compile(
          "new\\s+(?:IOException|UncheckedIOException|IllegalStateException|RuntimeException)"
              + "\\s*\\(");

  private static final Pattern TYPED = Pattern.compile("Remedy\\.(?:sail|client|program)\\s*\\(");

  private static final Pattern VERB =
      Pattern.compile("@Command\\(\\s*name\\s*=\\s*\"([a-z][a-z-]*)\"");

  private static final Pattern ROUTE = Pattern.compile("/v1/");

  private static final Pattern LITERAL = Pattern.compile("\"((?:[^\"\\\\]|\\\\.)*)\"");

  private static final Pattern CLASS_NAME = Pattern.compile("\\b([A-Z][A-Za-z0-9]+)\\b");

  private static final Pattern METHOD =
      Pattern.compile("\\b(\\w+)\\s*\\([^;{)]*\\)\\s*(?:throws[^{;]*)?\\{");

  /** A shell result held in a variable: declared so, or assigned from a run. */
  private static final Pattern RESULT_VARIABLE =
      Pattern.compile(
          "(?:\\bResult\\s+(\\w+)\\s*[=;,)])"
              + "|(?:\\bvar\\s+(\\w+)\\s*=\\s*[\\w.]*\\.(?:exec|execWithInput)\\w*\\s*\\()");

  private static final Set<String> SENTENCES = Set.of("Wording", "Policy");

  @Test
  void noRefusalTheAppReadsSpellsACommandOrARouteOrCarriesAToolsOutput() throws IOException {
    var files = sources();
    var sources = new HashMap<String, String>();
    files.forEach(
        (file, text) -> sources.merge(className(file), text, (one, other) -> one + other));
    var commandLine = commandLine(sources);
    var operator = operatorClasses(sources);
    var calls = calls(sources);
    var spelled = new ArrayList<String>();
    var sites = 0;
    var resolved = new TreeSet<String>();
    var sentences = new TreeSet<String>();
    for (var entry : files.entrySet()) {
      var name = className(entry.getKey());
      var text = entry.getValue();
      if (operator.contains(name)) {
        continue;
      }
      if (SENTENCES.contains(name)) {
        sentences.add(name);
        literals(name, text, commandLine, spelled);
      }
      if (registersRoutes(text)) {
        for (var route : APP_ROUTES) {
          var handler = handler(text, route);
          if (handler != null) {
            resolved.add(route);
            sites += scan(name, handler, text, commandLine, spelled);
          }
        }
        continue;
      }
      var reading = withoutOperatorMethods(name, text, calls, operator);
      sites += scan(name, reading, text, commandLine, spelled);
    }
    assertEquals(SENTENCES, sentences, "the access refusals' sentences were read");
    assertEquals(new TreeSet<>(APP_ROUTES), resolved, "every route the app calls was found");
    assertTrue(sites > 500, "the scan read " + sites + " refusal sites; it reads nothing");
    assertEquals(List.of(), spelled, "refusals that name a command, a route or a tool's output");
  }

  /** Reads every refusal site in {@code body}, a part of {@code file}; the count read. */
  private static int scan(
      String name, String body, String file, Pattern commandLine, List<String> spelled) {
    var raw = raw(file);
    var read = 0;
    for (var site : List.of(WRITTEN_FOR_A_PERSON, PLAIN)) {
      var found = site.matcher(body);
      while (found.find()) {
        read++;
        var arguments = typedStripped(arguments(body, body.indexOf('(', found.end() - 1)));
        literals(name, arguments, commandLine, spelled);
        if (site == WRITTEN_FOR_A_PERSON && raw.matcher(arguments).find()) {
          spelled.add(name + ": a tool's output in " + arguments.strip().replaceAll("\\s+", " "));
        }
      }
    }
    return read;
  }

  /** Adds each literal of {@code text} that names a command line or a route. */
  private static void literals(
      String name, String text, Pattern commandLine, List<String> spelled) {
    var literal = LITERAL.matcher(text);
    while (literal.find()) {
      var words = literal.group(1);
      if (commandLine.matcher(words).find() || ROUTE.matcher(words).find()) {
        spelled.add(name + ": \"" + words + "\"");
      }
    }
  }

  /**
   * A tool's output in {@code file}: a shell result's {@code stderr()} or {@code reason()}, or a
   * call to a method of the file that returns one.
   */
  private static Pattern raw(String file) {
    var results = new TreeSet<String>();
    var variable = RESULT_VARIABLE.matcher(file);
    while (variable.find()) {
      results.add(variable.group(1) != null ? variable.group(1) : variable.group(2));
    }
    var direct = new ArrayList<String>(List.of("\\.stderr\\s*\\("));
    results.forEach(
        result -> direct.add("\\b" + Pattern.quote(result) + "\\.reason\\s*\\(\\s*\\)"));
    var tool = Pattern.compile(String.join("|", direct));
    var helpers = new TreeSet<String>();
    var method = METHOD.matcher(file);
    while (method.find()) {
      var body = block(file, method.end() - 1);
      var returns = Pattern.compile("\\breturn\\b[^;]*").matcher(body);
      while (returns.find()) {
        if (tool.matcher(returns.group()).find()) {
          helpers.add(method.group(1));
        }
      }
    }
    helpers.forEach(helper -> direct.add("\\b" + Pattern.quote(helper) + "\\s*\\("));
    return Pattern.compile(String.join("|", direct));
  }

  /**
   * What only an operator reads: the CLI's commands, and every class referenced only from them or
   * from another such class. The app's side never is: the classes that register routes, and the
   * terminal door (the class that welcomes a ticket's connection).
   */
  private static Set<String> operatorClasses(Map<String, String> sources) throws IOException {
    var referrers = new HashMap<String, Set<String>>();
    var app = new HashSet<String>();
    for (var entry : sources.entrySet()) {
      if (registersRoutes(entry.getValue())
          || entry.getValue().contains("new PtyMessage.Welcome(")) {
        app.add(entry.getKey());
      }
      var names = CLASS_NAME.matcher(entry.getValue());
      while (names.find()) {
        var named = names.group(1);
        if (sources.containsKey(named) && !named.equals(entry.getKey())) {
          referrers.computeIfAbsent(named, n -> new HashSet<>()).add(entry.getKey());
        }
      }
    }
    var operator = new HashSet<String>();
    for (var file : sourceFiles()) {
      if (file.toString().contains("/commands/") && !app.contains(className(file))) {
        operator.add(className(file));
      }
    }
    var grew = true;
    while (grew) {
      grew = false;
      for (var name : sources.keySet()) {
        var users = referrers.getOrDefault(name, Set.of());
        if (!operator.contains(name)
            && !app.contains(name)
            && !users.isEmpty()
            && operator.containsAll(users)) {
          operator.add(name);
          grew = true;
        }
      }
    }
    return operator;
  }

  /** Each name called as a method, by class, with how often it is called there. */
  private static Map<String, Map<String, Integer>> calls(Map<String, String> sources) {
    var calls = new HashMap<String, Map<String, Integer>>();
    var call = Pattern.compile("\\b(\\w+)\\s*\\(");
    sources.forEach(
        (name, text) -> {
          var found = call.matcher(text);
          while (found.find()) {
            calls
                .computeIfAbsent(found.group(1), m -> new HashMap<>())
                .merge(name, 1, Integer::sum);
          }
        });
    return calls;
  }

  /** {@code text} less the methods called only from operator-only classes. */
  private static String withoutOperatorMethods(
      String name, String text, Map<String, Map<String, Integer>> calls, Set<String> operator) {
    var kept = new StringBuilder();
    var at = 0;
    var method = METHOD.matcher(text);
    while (method.find(at)) {
      var start = method.start();
      var body = block(text, method.end() - 1);
      var end = method.end() - 1 + body.length();
      kept.append(text, at, start);
      if (!calledOnlyByOperators(method.group(1), name, calls, operator)) {
        kept.append(text, start, end);
      }
      at = end;
    }
    return kept.append(text.substring(Math.min(at, text.length()))).toString();
  }

  /**
   * Whether {@code method} of {@code owner} is called, and only from operator-only classes: its own
   * declaration is not a call.
   */
  private static boolean calledOnlyByOperators(
      String method, String owner, Map<String, Map<String, Integer>> calls, Set<String> operator) {
    var callers = 0;
    for (var entry : calls.getOrDefault(method, Map.of()).entrySet()) {
      var count = entry.getValue() - (entry.getKey().equals(owner) ? 1 : 0);
      if (count > 0) {
        if (!operator.contains(entry.getKey())) {
          return false;
        }
        callers += count;
      }
    }
    return callers > 0;
  }

  private static boolean registersRoutes(String text) {
    return Pattern.compile("\\broute\\(\\s*(?:GET|POST|PUT|PATCH|DELETE|Route\\.ANY)\\b")
            .matcher(text)
            .find()
        || text.contains("new Route<>(");
  }

  /**
   * The handler {@code route} is registered with in {@code text}: its method body or its lambda.
   */
  private static String handler(String text, String route) {
    var registration =
        Pattern.compile(
                "(?:\\broute|new Route<>)\\(\\s*"
                    + String.join("\\s*", Stream.of(route.split(" ")).map(Pattern::quote).toList())
                    + "\\s*,")
            .matcher(text);
    if (!registration.find()) {
      return null;
    }
    var arguments = arguments(text, text.indexOf('(', registration.start()));
    var reference = Pattern.compile("this::(\\w+)\\s*$").matcher(arguments.strip());
    if (!reference.find()) {
      return arguments;
    }
    var declared =
        Pattern.compile("\\b" + reference.group(1) + "\\s*\\([^;{)]*\\)\\s*(?:throws[^{;]*)?\\{")
            .matcher(text);
    return declared.find() ? block(text, declared.end() - 1) : arguments;
  }

  /** A {@code sail} followed by one of the CLI's own verbs. */
  private static Pattern commandLine(Map<String, String> sources) {
    var verbs = new TreeSet<String>();
    for (var text : sources.values()) {
      var verb = VERB.matcher(text);
      while (verb.find()) {
        verbs.add(verb.group(1));
      }
    }
    assertTrue(verbs.size() > 50, "the CLI's verbs: " + verbs);
    return Pattern.compile("(?:^|[\\s'`(:\"])sail (?:" + String.join("|", verbs) + ")\\b");
  }

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

  private static String className(Path file) {
    var name = file.getFileName().toString();
    return name.substring(0, name.length() - ".java".length());
  }

  /** Every main source, comments out. */
  private static Map<Path, String> sources() throws IOException {
    var sources = new LinkedHashMap<Path, String>();
    for (var file : sourceFiles()) {
      sources.put(file, uncommented(Files.readString(file)));
    }
    assertTrue(sources.size() > 500, "the sources read: " + sources.size());
    return sources;
  }

  /** Every main Java source of every module beside this one. */
  private static List<Path> sourceFiles() throws IOException {
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
    return found;
  }
}
