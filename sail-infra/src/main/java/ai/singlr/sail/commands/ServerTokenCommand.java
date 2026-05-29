/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.commands;

import ai.singlr.sail.engine.SailPaths;
import ai.singlr.sail.store.Sqlite;
import ai.singlr.sail.store.TokenStore;
import picocli.CommandLine.Command;
import picocli.CommandLine.Help.Ansi;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Spec;

@Command(
    name = "token",
    description = "Manage API tokens.",
    mixinStandardHelpOptions = true,
    subcommands = {
      ServerTokenCommand.Create.class,
      ServerTokenCommand.ListTokens.class,
      ServerTokenCommand.Revoke.class
    })
public final class ServerTokenCommand implements Runnable {

  @Override
  public void run() {
    new picocli.CommandLine(this).usage(System.out);
  }

  @Command(
      name = "create",
      description = "Create a new API token.",
      mixinStandardHelpOptions = true)
  static final class Create implements Runnable {

    @Parameters(index = "0", description = "Token name.")
    private String name;

    @Parameters(
        index = "1",
        description = "Role: admin, member, or viewer.",
        defaultValue = "member")
    private String role;

    @Spec private CommandSpec spec;

    @Override
    public void run() {
      CliCommand.run(
          spec,
          () -> {
            try (var db = Sqlite.open(SailPaths.sailDir().resolve("sail.db"))) {
              var created = new TokenStore(db).create(name, role);
              System.out.println(
                  Ansi.AUTO.string("  @|green ✓|@ Token created: " + created.name()));
              System.out.println(Ansi.AUTO.string("    @|bold " + created.token() + "|@"));
              System.out.println(
                  Ansi.AUTO.string("    @|faint Save this token — it will not be shown again.|@"));
            }
          });
    }
  }

  @Command(name = "list", description = "List API tokens.", mixinStandardHelpOptions = true)
  static final class ListTokens implements Runnable {

    @Spec private CommandSpec spec;

    @Override
    public void run() {
      CliCommand.run(
          spec,
          () -> {
            try (var db = Sqlite.open(SailPaths.sailDir().resolve("sail.db"))) {
              var tokens = new TokenStore(db).list();
              if (tokens.isEmpty()) {
                System.out.println("  No tokens. Run 'sail server init' to create one.");
                return;
              }
              for (var token : tokens) {
                System.out.printf(
                    "  %-20s  %-8s  created %s%n", token.name(), token.role(), token.createdAt());
              }
            }
          });
    }
  }

  @Command(name = "revoke", description = "Revoke an API token.", mixinStandardHelpOptions = true)
  static final class Revoke implements Runnable {

    @Parameters(index = "0", description = "Token name to revoke.")
    private String name;

    @Spec private CommandSpec spec;

    @Override
    public void run() {
      CliCommand.run(
          spec,
          () -> {
            try (var db = Sqlite.open(SailPaths.sailDir().resolve("sail.db"))) {
              var revoked = new TokenStore(db).revoke(name);
              if (revoked) {
                System.out.println(Ansi.AUTO.string("  @|green ✓|@ Token '" + name + "' revoked."));
              } else {
                System.out.println(
                    Ansi.AUTO.string("  @|yellow ⚠|@ No token named '" + name + "'."));
              }
            }
          });
    }
  }
}
