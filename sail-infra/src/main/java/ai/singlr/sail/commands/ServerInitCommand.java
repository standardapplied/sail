/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.commands;

import ai.singlr.sail.engine.SailPaths;
import ai.singlr.sail.store.SchemaManager;
import ai.singlr.sail.store.SpecMigrator;
import ai.singlr.sail.store.SpecStore;
import ai.singlr.sail.store.Sqlite;
import ai.singlr.sail.store.TokenStore;
import java.nio.file.Files;
import java.nio.file.Path;
import picocli.CommandLine.Command;
import picocli.CommandLine.Help.Ansi;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Spec;

@Command(
    name = "init",
    description = "Initialize the Sail server: create database and first API token.",
    mixinStandardHelpOptions = true)
public final class ServerInitCommand implements Runnable {

  @Option(
      names = "--import-specs",
      description = "Import file-based specs from a local directory into the database.",
      arity = "0..1",
      fallbackValue = "")
  private String importSpecsPath;

  @Spec private CommandSpec spec;

  @Override
  public void run() {
    CliCommand.run(spec, this::execute);
  }

  private void execute() throws Exception {
    var dbPath = SailPaths.sailDir().resolve("sail.db");
    Files.createDirectories(dbPath.getParent());

    try (var db = Sqlite.open(dbPath)) {
      var schema = new SchemaManager(db);
      var before = schema.currentVersion();
      schema.migrate();
      var after = schema.currentVersion();

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

      var tokenStore = new TokenStore(db);
      var existing = tokenStore.list();
      if (existing.isEmpty()) {
        var created = tokenStore.create("admin", "admin");
        System.out.println(Ansi.AUTO.string("  @|green ✓|@ API token created:"));
        System.out.println(Ansi.AUTO.string("    @|bold " + created.token() + "|@"));
        System.out.println(
            Ansi.AUTO.string("    @|faint Save this token — it will not be shown again.|@"));
      } else {
        System.out.println(
            Ansi.AUTO.string("  @|green ✓|@ " + existing.size() + " API token(s) exist."));
      }

      if (importSpecsPath != null) {
        importFileBasedSpecs(db);
      }
    }
  }

  private void importFileBasedSpecs(Sqlite db) {
    var specsDir =
        importSpecsPath.isEmpty()
            ? Path.of(System.getProperty("user.home"), "workspace", "specs")
            : Path.of(importSpecsPath);

    if (!Files.isDirectory(specsDir)) {
      System.out.println(Ansi.AUTO.string("  @|yellow ⚠|@ Specs directory not found: " + specsDir));
      return;
    }

    var specStore = new SpecStore(db);
    var migrator = new SpecMigrator(specStore);
    var result = migrator.importFromDirectory(specsDir);

    for (var error : result.errors()) {
      System.err.println("    " + error);
    }
    System.out.println(
        Ansi.AUTO.string(
            "  @|green ✓|@ Specs imported: "
                + result.imported()
                + " new, "
                + result.skipped()
                + " skipped."));
  }
}
