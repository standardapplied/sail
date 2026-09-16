/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.commands;

import ai.singlr.sail.api.OperationsFactory;
import ai.singlr.sail.api.ServerConnectionConfig;
import ai.singlr.sail.engine.SailPaths;
import ai.singlr.sail.store.TokenStore;
import java.nio.file.Files;
import picocli.CommandLine.Command;
import picocli.CommandLine.Help.Ansi;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Spec;

@Command(
    name = "init",
    description = "Initialize the Sail server: create database and first API token.",
    mixinStandardHelpOptions = true)
public final class ServerInitCommand implements Runnable {

  @Spec private CommandSpec spec;

  @Override
  public void run() {
    CliCommand.run(spec, this::execute);
  }

  private void execute() throws Exception {
    var dbPath = SailPaths.controlPlaneDb();
    SailPaths.ensureDataDir(dbPath.getParent());

    try (var operations = OperationsFactory.open(dbPath)) {
      var migration = operations.initialize();
      var before = migration.before();
      var after = migration.after();

      System.out.println(Ansi.AUTO.string("  @|green ✓|@ Database: " + dbPath));
      if (before == 0) {
        System.out.println(
            Ansi.AUTO.string("    @|faint Schema created (version " + after + ")|@"));
      } else if (after > before) {
        System.out.println(
            Ansi.AUTO.string("    @|faint Schema migrated: " + before + " → " + after + "|@"));
      } else {
        System.out.println(
            Ansi.AUTO.string("    @|faint Schema up to date (version " + after + ")|@"));
      }

      var configPath = SailPaths.clientConfigPath();
      var existing = operations.tokens();
      var existingAdmin = existing.stream().anyMatch(t -> "admin".equals(t.name()));
      var configMissing = !Files.exists(configPath);
      if (existing.isEmpty()) {
        var created = operations.createToken("admin", "admin", null, TokenStore.DEFAULT_TTL);
        ServerConnectionConfig.saveLocalToken(created.token(), configPath);
        System.out.println(
            Ansi.AUTO.string("  @|green ✓|@ API token created and saved to " + configPath));
      } else if (configMissing) {
        if (existingAdmin) {
          operations.revokeToken("admin");
          System.out.println(
              Ansi.AUTO.string(
                  "  @|yellow ↻|@ Config missing — rotating admin token (old plaintext is"
                      + " unrecoverable)."));
        }
        var created = operations.createToken("admin", "admin", null, TokenStore.DEFAULT_TTL);
        ServerConnectionConfig.saveLocalToken(created.token(), configPath);
        System.out.println(
            Ansi.AUTO.string("  @|green ✓|@ API token created and saved to " + configPath));
      } else {
        System.out.println(
            Ansi.AUTO.string(
                "  @|green ✓|@ "
                    + existing.size()
                    + " API token(s) exist; config at "
                    + configPath));
      }
    }
  }
}
