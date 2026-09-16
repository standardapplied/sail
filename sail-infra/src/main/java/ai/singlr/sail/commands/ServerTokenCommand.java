/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.commands;

import ai.singlr.sail.api.Operations;
import ai.singlr.sail.api.OperationsFactory;
import ai.singlr.sail.common.Strings;
import ai.singlr.sail.store.FdeStore;
import ai.singlr.sail.store.TokenStore;
import java.time.Duration;
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

    @picocli.CommandLine.Option(names = "--fde", description = "Owning FDE handle.")
    private String fde;

    @picocli.CommandLine.Option(
        names = "--ttl-days",
        description = "Token lifetime in days (default 90).")
    private Integer ttlDays;

    @picocli.CommandLine.Option(names = "--no-expiry", description = "Token never expires.")
    private boolean noExpiry;

    @Spec private CommandSpec spec;

    @Override
    public void run() {
      CliCommand.run(
          spec,
          () -> {
            var ttl = resolveTtl(noExpiry, ttlDays);
            try (var operations = OperationsFactory.open()) {
              var fdeId = resolveFdeId(operations);
              var created = operations.createToken(name, role, fdeId, ttl);
              System.out.println(
                  Ansi.AUTO.string("  @|green ✓|@ Token created: " + created.name()));
              System.out.println(Ansi.AUTO.string("    @|bold " + created.token() + "|@"));
              System.out.println(
                  Ansi.AUTO.string(
                      "    @|faint "
                          + (created.expiresAt() == null
                              ? "Never expires."
                              : "Expires " + created.expiresAt() + ".")
                          + "|@"));
              System.out.println(
                  Ansi.AUTO.string("    @|faint Save this token — it will not be shown again.|@"));
            }
          });
    }

    /** Resolves the requested lifetime; {@code null} never expires. Visible for tests. */
    static Duration resolveTtl(boolean noExpiry, Integer ttlDays) {
      if (noExpiry && ttlDays != null) {
        throw new IllegalArgumentException("Pass --ttl-days or --no-expiry, not both.");
      }
      if (noExpiry) {
        return null;
      }
      if (ttlDays == null) {
        return TokenStore.DEFAULT_TTL;
      }
      if (ttlDays <= 0) {
        throw new IllegalArgumentException("--ttl-days must be a positive number of days.");
      }
      return Duration.ofDays(ttlDays);
    }

    private String resolveFdeId(Operations operations) {
      if (Strings.isBlank(fde)) {
        return null;
      }
      return operations
          .fde(fde)
          .map(FdeStore.Fde::id)
          .orElseThrow(
              () ->
                  new IllegalArgumentException(
                      "No FDE with handle '" + fde + "'. Add it with 'sail fde add " + fde + "'."));
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
            try (var operations = OperationsFactory.open()) {
              var tokens = operations.tokens();
              if (tokens.isEmpty()) {
                System.out.println("  No tokens. Run 'sail server init' to create one.");
                return;
              }
              for (var token : tokens) {
                var expiry =
                    token.expiresAt() == null ? "never expires" : "expires " + token.expiresAt();
                System.out.printf(
                    "  %-20s  %-8s  created %s  (%s)%n",
                    token.name(), token.role(), token.createdAt(), expiry);
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
            try (var operations = OperationsFactory.open()) {
              var revoked = operations.revokeToken(name);
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
