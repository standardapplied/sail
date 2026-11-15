/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.commands;

import ai.singlr.sail.api.SailApiClient;
import ai.singlr.sail.config.YamlUtil;
import ai.singlr.sail.engine.NameValidator;
import ai.singlr.sail.store.LoginStore;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Help.Ansi;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

/** {@code sail git}: what a person keeps on this runner for their workspaces' git. */
@Command(
    name = "git",
    description = "Keep the git tokens your workspaces fetch, push and open pull requests with.",
    mixinStandardHelpOptions = true,
    subcommands = {GitCommand.Login.class})
public final class GitCommand implements Runnable {

  @Override
  public void run() {
    CommandLine.usage(this, System.out);
  }

  /**
   * {@code sail git login <host>}: stores a git token for one forge host, read from stdin, never
   * from the command line, and echoed nowhere. Every workspace made or re-owned for you after it
   * fetches, pushes and logs {@code gh} in with it. On the host it is root's and acts as the box's
   * person; anyone else reaches the same door through the gateway. No answer ever holds a value.
   */
  @Command(
      name = "login",
      description = "Store a git token for a forge host, read from stdin; list or remove tokens.",
      mixinStandardHelpOptions = true,
      subcommands = {Login.ListTokens.class, Login.Remove.class})
  static final class Login implements Runnable {

    @Parameters(index = "0", arity = "0..1", description = "The forge host, e.g. github.com.")
    private String host;

    @Option(names = "--json", description = "Output in JSON format.")
    private boolean json;

    @Mixin private ConnectionOptions connection;

    @Override
    public void run() {
      if (host == null) {
        CommandLine.usage(this, System.out);
        return;
      }
      CliCommand.run(this::execute);
    }

    private void execute() throws Exception {
      var named = LoginStore.requireHost(host);
      var value = read(named);
      var config = connection.resolve();
      try (var client = new SailApiClient(config.serverUrl(), config.token())) {
        var result = client.put("/v1/git-logins/" + named, Map.of("value", value));
        if (json) {
          System.out.println(YamlUtil.dumpJson(new LinkedHashMap<>(result)));
          return;
        }
        System.out.println(
            Ansi.AUTO.string(
                "  @|green ✓|@ Stored "
                    + result.get("fde")
                    + "'s git token for "
                    + result.get("host")
                    + ", fingerprint "
                    + result.get("fingerprint")
                    + ". Workspaces made or re-owned for "
                    + result.get("fde")
                    + " from now on use it."));
      }
    }

    /** The token: typed at a terminal with echo off, or the whole of stdin, its newline dropped. */
    private static String read(String host) throws IOException {
      if (ConsoleHelper.hasConsole()) {
        return ConsoleHelper.readPassword("  " + host + " token (input hidden): ");
      }
      System.err.println("  Reading the " + host + " token from stdin.");
      return AgentLoginCommand.trimmed(System.in);
    }

    private static String person(String fde) {
      if (fde == null) {
        return "";
      }
      NameValidator.requireValidFdeHandle(fde);
      return "?fde=" + fde;
    }

    /** {@code sail git login list}: your git tokens, or {@code --fde}'s, never a value. */
    @Command(
        name = "list",
        description = "List your git tokens by host, or another person's (--fde, admins).",
        mixinStandardHelpOptions = true)
    static final class ListTokens implements Runnable {

      @Option(names = "--fde", description = "Whose tokens to list (admins).")
      private String fde;

      @Option(names = "--json", description = "Output in JSON format.")
      private boolean json;

      @Mixin private ConnectionOptions connection;

      @Override
      public void run() {
        CliCommand.run(this::execute);
      }

      @SuppressWarnings("unchecked")
      private void execute() throws Exception {
        var config = connection.resolve();
        try (var client = new SailApiClient(config.serverUrl(), config.token())) {
          var result = client.get("/v1/git-logins" + person(fde));
          if (json) {
            System.out.println(YamlUtil.dumpJson(new LinkedHashMap<>(result)));
            return;
          }
          var tokens = (List<Map<String, Object>>) result.get("tokens");
          if (tokens.isEmpty()) {
            System.out.println(
                Ansi.AUTO.string(
                    "  @|faint No git tokens stored for " + result.get("fde") + ".|@"));
            return;
          }
          System.out.printf("  %-32s %-25s %s%n", "HOST", "STORED", "FINGERPRINT");
          for (var token : tokens) {
            System.out.printf(
                "  %-32s %-25s %s%n",
                token.get("host"),
                Objects.toString(token.get("stored_at"), ""),
                token.get("fingerprint"));
          }
        }
      }
    }

    /** {@code sail git login remove <host>}: yours, or {@code --fde}'s for an admin. */
    @Command(
        name = "remove",
        description = "Remove your git token for a host, or another person's (--fde, admins).",
        mixinStandardHelpOptions = true)
    static final class Remove implements Runnable {

      @Parameters(index = "0", description = "The forge host, e.g. github.com.")
      private String host;

      @Option(names = "--fde", description = "Whose token to remove (admins).")
      private String fde;

      @Option(names = "--json", description = "Output in JSON format.")
      private boolean json;

      @Mixin private ConnectionOptions connection;

      @Override
      public void run() {
        CliCommand.run(this::execute);
      }

      private void execute() throws Exception {
        var path = "/v1/git-logins/" + LoginStore.requireHost(host) + person(fde);
        var config = connection.resolve();
        try (var client = new SailApiClient(config.serverUrl(), config.token())) {
          var result = client.delete(path);
          if (json) {
            System.out.println(YamlUtil.dumpJson(new LinkedHashMap<>(result)));
            return;
          }
          System.out.println("  " + result.get("note"));
        }
      }
    }
  }
}
