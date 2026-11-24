/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.commands;

import ai.singlr.sail.api.SailApiClient;
import ai.singlr.sail.config.YamlUtil;
import ai.singlr.sail.engine.NameValidator;
import ai.singlr.sail.harness.Harnesses;
import ai.singlr.sail.store.LoginStore;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Predicate;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Help.Ansi;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

/**
 * {@code sail agent login <harness>}: stores a harness login every run of a spec of yours, and
 * every agent you start in a workspace of yours, starts with, read from stdin, never from the
 * command line, and echoed nowhere. On the host it is root's and acts as the box's person; anyone
 * else reaches the same door through the gateway. Its subcommands list, choose the default of and
 * remove logins. No answer ever holds a value.
 */
@Command(
    name = "login",
    description =
        "Store a harness login your spec runs and your agents in your workspaces start with, read"
            + " from stdin; list, use or remove logins.",
    mixinStandardHelpOptions = true,
    subcommands = {
      AgentLoginCommand.ListLogins.class,
      AgentLoginCommand.Use.class,
      AgentLoginCommand.Remove.class
    })
public final class AgentLoginCommand implements Runnable {

  /** One more byte than a login may hold, and room for the newline a pipe ends it with. */
  private static final int MAX_READ = 16 * 1024 + 3;

  @Parameters(index = "0", arity = "0..1", description = "The harness: claude-code or codex.")
  private String harness;

  @Option(names = "--name", description = "The login's name (default: default).")
  private String name;

  @Option(
      names = "--kind",
      description = "The login's kind: api-key or glm for claude-code, api-key for codex.")
  private String kind;

  @Option(names = "--json", description = "Output in JSON format.")
  private boolean json;

  @Option(
      names = "--dry-run",
      description = "Say what would be stored (harness, name, kind, default) and store nothing.")
  private boolean dryRun;

  @Mixin private ConnectionOptions connection;

  @Override
  public void run() {
    if (harness == null) {
      CommandLine.usage(this, System.out);
      return;
    }
    CliCommand.run(json, this::execute);
  }

  private void execute() throws Exception {
    var known = Harnesses.of(harness);
    var named = LoginStore.requireName(name);
    var declared = known.loginKind(kind);
    if (dryRun) {
      rehearse(known.yamlName(), named, declared.kind());
      return;
    }
    var value = read(known.displayName() + " " + declared.kind(), declared.howToMake().plain());
    change(
        connection,
        json,
        "Stored",
        client ->
            client.put(
                "/v1/logins/" + known.yamlName() + "/" + named,
                Map.of("kind", declared.kind(), "value", value)));
  }

  @FunctionalInterface
  interface Call {
    Map<String, Object> on(SailApiClient client) throws Exception;
  }

  /** One change at the login doors, its answer printed, or as JSON under {@code json}. */
  static void change(ConnectionOptions connection, boolean json, String what, Call call)
      throws Exception {
    var config = connection.resolve();
    try (var client = new SailApiClient(config.serverUrl(), config.token())) {
      var result = call.on(client);
      if (json) {
        System.out.println(YamlUtil.dumpJson(new LinkedHashMap<>(result)));
        return;
      }
      printChange(what, result);
    }
  }

  /**
   * What storing would do, read from the logins stored now, reading no value and storing nothing:
   * the login becomes the default when its harness has none, or when it is the default already, and
   * replaces the one stored under its name, when there is one.
   */
  @SuppressWarnings("unchecked")
  private void rehearse(String harness, String name, String kind) throws Exception {
    var config = connection.resolve();
    try (var client = new SailApiClient(config.serverUrl(), config.token())) {
      var logins = (List<Map<String, Object>>) client.get("/v1/logins").get("logins");
      var current =
          logins.stream()
              .filter(login -> harness.equals(login.get("harness")))
              .filter(login -> Boolean.TRUE.equals(login.get("default")))
              .map(login -> login.get("name"))
              .findFirst();
      var becomesDefault = current.isEmpty() || current.get().equals(name);
      var replaces =
          logins.stream()
              .anyMatch(
                  login -> harness.equals(login.get("harness")) && name.equals(login.get("name")));
      if (json) {
        var shown = new LinkedHashMap<String, Object>();
        shown.put("dry_run", true);
        shown.put("harness", harness);
        shown.put("name", name);
        shown.put("kind", kind);
        shown.put("default", becomesDefault);
        shown.put("replaces", replaces);
        System.out.println(YamlUtil.dumpJson(shown));
        return;
      }
      System.out.println(
          "  Would store "
              + harness
              + " login '"
              + name
              + "' ("
              + kind
              + (becomesDefault ? ", the default" : ", not the default")
              + ")"
              + (replaces ? ", replacing the one stored under that name" : "")
              + "; nothing was stored.");
    }
  }

  /**
   * The login's value: typed at a terminal with echo off, after saying how to make one, or the
   * whole of stdin, its one trailing newline dropped. More than a login may hold is refused unread.
   */
  static String read(String what, String howToMake) throws IOException {
    if (ConsoleHelper.hasConsole()) {
      if (howToMake != null) {
        System.out.println("  " + howToMake + ", then paste it here.");
      }
      return ConsoleHelper.readPassword("  " + what + " (input hidden): ");
    }
    System.err.println("  Reading the " + what + " from stdin.");
    return trimmed(System.in);
  }

  static String trimmed(InputStream in) throws IOException {
    var bytes = in.readNBytes(MAX_READ);
    if (bytes.length == MAX_READ) {
      throw new CliCommand.Refused("A login's value is at most 16 KiB.");
    }
    var text = new String(bytes, StandardCharsets.UTF_8);
    if (text.endsWith("\r\n")) {
      return text.substring(0, text.length() - 2);
    }
    return text.endsWith("\n") ? text.substring(0, text.length() - 1) : text;
  }

  @SuppressWarnings("unchecked")
  static void printChange(String what, Map<String, Object> result) {
    var login = (Map<String, Object>) result.get("login");
    if (login == null) {
      System.out.println("  " + result.get("note"));
      return;
    }
    System.out.println(
        Ansi.AUTO.string(
            "  @|green ✓|@ "
                + what
                + " "
                + login.get("harness")
                + " login '"
                + login.get("name")
                + "' ("
                + login.get("kind")
                + (Boolean.TRUE.equals(login.get("default")) ? ", the default" : "")
                + "), fingerprint "
                + login.get("fingerprint")
                + "."));
    if (result.get("note") != null) {
      System.out.println("  " + result.get("note"));
    }
  }

  static String person(String fde) {
    if (fde == null) {
      return "";
    }
    NameValidator.requireValidFdeHandle(fde);
    return "?fde=" + fde;
  }

  /** {@code sail agent login list}: every login of yours, or of {@code --fde}, never a value. */
  @Command(
      name = "list",
      description = "List your harness logins, or another person's (--fde, admins).",
      mixinStandardHelpOptions = true)
  static final class ListLogins implements Runnable {

    @Option(names = "--fde", description = "Whose logins to list (admins).")
    private String fde;

    @Option(names = "--json", description = "Output in JSON format.")
    private boolean json;

    @Mixin private ConnectionOptions connection;

    @Override
    public void run() {
      CliCommand.run(json, this::execute);
    }

    private void execute() throws Exception {
      list(connection, fde, json, login -> !LoginStore.GIT.equals(login.get("harness")), "logins");
    }
  }

  /**
   * Lists the logins of {@code fde}, the caller when blank, that {@code shown} keeps: the one list
   * {@code sail agent login list} and {@code sail git login list} both print, as JSON or as a table
   * a person reads, saying "No {@code noun} stored" when there are none.
   */
  @SuppressWarnings("unchecked")
  static void list(
      ConnectionOptions connection,
      String fde,
      boolean json,
      Predicate<Map<String, Object>> shown,
      String noun)
      throws Exception {
    var config = connection.resolve();
    try (var client = new SailApiClient(config.serverUrl(), config.token())) {
      var result = new LinkedHashMap<>(client.get("/v1/logins" + person(fde)));
      var logins =
          ((List<Map<String, Object>>) result.get("logins")).stream().filter(shown).toList();
      result.put("logins", logins);
      if (json) {
        System.out.println(YamlUtil.dumpJson(result));
        return;
      }
      if (logins.isEmpty()) {
        System.out.println(
            Ansi.AUTO.string("  @|faint No " + noun + " stored for " + result.get("fde") + ".|@"));
        return;
      }
      System.out.printf(
          "  %-12s %-24s %-8s %-8s %-25s %s%n",
          "HARNESS", "NAME", "KIND", "DEFAULT", "STORED", "FINGERPRINT");
      for (var login : logins) {
        System.out.printf(
            "  %-12s %-24s %-8s %-8s %-25s %s%n",
            login.get("harness"),
            login.get("name"),
            login.get("kind"),
            Boolean.TRUE.equals(login.get("default")) ? "yes" : "",
            Objects.toString(login.get("stored_at"), ""),
            login.get("fingerprint"));
      }
    }
  }

  /** {@code sail agent login use <harness> <name>}: the default for launches after this one. */
  @Command(
      name = "use",
      description = "Make one of your logins the default a spec run starts with.",
      mixinStandardHelpOptions = true)
  static final class Use implements Runnable {

    @Parameters(index = "0", description = "The harness: claude-code or codex.")
    private String harness;

    @Parameters(index = "1", description = "The login's name.")
    private String name;

    @Option(names = "--json", description = "Output in JSON format.")
    private boolean json;

    @Mixin private ConnectionOptions connection;

    @Override
    public void run() {
      CliCommand.run(json, this::execute);
    }

    private void execute() throws Exception {
      var path =
          "/v1/logins/"
              + Harnesses.of(harness).yamlName()
              + "/"
              + LoginStore.requireName(name)
              + ":use";
      var config = connection.resolve();
      try (var client = new SailApiClient(config.serverUrl(), config.token())) {
        var result = client.post(path, Map.of());
        if (json) {
          System.out.println(YamlUtil.dumpJson(new LinkedHashMap<>(result)));
          return;
        }
        printChange("Using", result);
      }
    }
  }

  /** {@code sail agent login remove <harness> <name>}: yours, or {@code --fde}'s for an admin. */
  @Command(
      name = "remove",
      description = "Remove one of your logins, or another person's (--fde, admins).",
      mixinStandardHelpOptions = true)
  static final class Remove implements Runnable {

    @Parameters(index = "0", description = "The harness: claude-code or codex.")
    private String harness;

    @Parameters(index = "1", description = "The login's name.")
    private String name;

    @Option(names = "--fde", description = "Whose login to remove (admins).")
    private String fde;

    @Option(names = "--json", description = "Output in JSON format.")
    private boolean json;

    @Mixin private ConnectionOptions connection;

    @Override
    public void run() {
      CliCommand.run(json, this::execute);
    }

    private void execute() throws Exception {
      var path =
          "/v1/logins/"
              + Harnesses.of(harness).yamlName()
              + "/"
              + LoginStore.requireName(name)
              + person(fde);
      change(connection, json, "Removed", client -> client.delete(path));
    }
  }
}
