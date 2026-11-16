/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.commands;

import ai.singlr.sail.api.SailApiClient;
import ai.singlr.sail.config.YamlUtil;
import ai.singlr.sail.store.LoginStore;
import java.util.LinkedHashMap;
import java.util.Map;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

/**
 * {@code sail git login}: a git token is a login of {@value LoginStore#GIT} named by its host, kept
 * and served by the login doors; these verbs are {@code sail agent login}'s calls under the name a
 * person looks for.
 */
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

  /** {@code sail git login <host>}: stores a token, read from stdin and echoed nowhere. */
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
      CliCommand.run(json, this::execute);
    }

    private void execute() throws Exception {
      var named = LoginStore.requireHost(host);
      var value = AgentLoginCommand.read(named + " token", null);
      change(
          connection,
          json,
          "Stored",
          client ->
              client.put(
                  "/v1/logins/" + LoginStore.GIT + "/" + named,
                  Map.of("kind", "token", "value", value)));
    }

    @FunctionalInterface
    private interface Call {
      Map<String, Object> on(SailApiClient client) throws Exception;
    }

    private static void change(ConnectionOptions connection, boolean json, String what, Call call)
        throws Exception {
      var config = connection.resolve();
      try (var client = new SailApiClient(config.serverUrl(), config.token())) {
        var result = call.on(client);
        if (json) {
          System.out.println(YamlUtil.dumpJson(new LinkedHashMap<>(result)));
          return;
        }
        AgentLoginCommand.printChange(what, result);
      }
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
        CliCommand.run(
            json,
            () ->
                AgentLoginCommand.list(
                    connection,
                    fde,
                    json,
                    login -> LoginStore.GIT.equals(login.get("harness")),
                    "git tokens"));
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
        CliCommand.run(
            json,
            () ->
                change(
                    connection,
                    json,
                    "Removed",
                    client ->
                        client.delete(
                            "/v1/logins/"
                                + LoginStore.GIT
                                + "/"
                                + LoginStore.requireHost(host)
                                + AgentLoginCommand.person(fde))));
      }
    }
  }
}
