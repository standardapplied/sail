/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.commands;

import ai.singlr.sail.api.HostIdentity;
import ai.singlr.sail.api.OperationsFactory;
import ai.singlr.sail.engine.HostToken;
import ai.singlr.sail.engine.SailPaths;
import java.nio.file.Files;
import java.nio.file.Path;
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
      var migration = operations.schema().initialize();
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
      var identity = operations.identity();
      var existing = identity.tokens();
      var existingAdmin = existing.stream().anyMatch(t -> HostToken.NAME.equals(t.name()));
      var configMissing = !Files.exists(configPath);
      if (existing.isEmpty()) {
        announce(identity.mintHostToken(configPath), identity, configPath);
      } else if (configMissing) {
        if (existingAdmin) {
          identity.revokeToken(HostToken.NAME);
          System.out.println(
              Ansi.AUTO.string(
                  "  @|yellow ↻|@ Config missing — rotating admin token (old plaintext is"
                      + " unrecoverable)."));
        }
        announce(identity.mintHostToken(configPath), identity, configPath);
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

  private static void announce(HostToken.Minted minted, HostIdentity identity, Path configPath) {
    System.out.println(
        Ansi.AUTO.string(
            "  @|green ✓|@ API token created and saved to "
                + configPath
                + "; "
                + HostToken.describe(minted, identity.box())));
  }
}
