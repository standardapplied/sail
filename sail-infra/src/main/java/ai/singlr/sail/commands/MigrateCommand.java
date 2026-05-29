/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.commands;

import ai.singlr.sail.config.ProjectRegistry;
import ai.singlr.sail.engine.SailPaths;
import ai.singlr.sail.store.DataMigration;
import ai.singlr.sail.store.DataMigrator;
import ai.singlr.sail.store.RebucketSpecsMigration;
import ai.singlr.sail.store.SchemaManager;
import ai.singlr.sail.store.Sqlite;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.Optional;
import picocli.CommandLine.Command;
import picocli.CommandLine.Help.Ansi;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Spec;

/**
 * Single command that runs every pending schema + data migration on the control-plane database.
 * Idempotent: schema migrations are tracked by version, data migrations by name, so re-runs do
 * nothing. {@code sail upgrade} calls this at the end so future upgrades need no manual step.
 */
@Command(
    name = "migrate",
    description = "Apply all pending schema and data migrations.",
    mixinStandardHelpOptions = true)
public final class MigrateCommand implements Runnable {

  /** Every data migration in registration order. Add new ones at the bottom. */
  public static final List<ai.singlr.sail.store.DataMigration> REGISTRY =
      List.of(new RebucketSpecsMigration());

  @Option(
      names = "--non-interactive",
      description = "Skip prompts; leave ambiguous rows for manual follow-up.")
  private boolean nonInteractive;

  @Option(names = "--json", description = "Output JSON instead of human-readable text.")
  private boolean json;

  @Spec private CommandSpec spec;

  @Override
  public void run() {
    CliCommand.run(spec, this::execute);
  }

  private void execute() throws Exception {
    runMigrations(nonInteractive, json);
  }

  /**
   * Reusable entry point: opens the DB, applies schema + data migrations, returns the data-runs for
   * the caller (UpgradeCommand wires this in at the end of the upgrade flow).
   */
  public static List<DataMigrator.Run> runMigrations(boolean nonInteractive, boolean jsonOutput) {
    var dbPath = SailPaths.sailDir().resolve("sail.db");
    try {
      Files.createDirectories(dbPath.getParent());
    } catch (Exception e) {
      throw new IllegalStateException("Could not prepare " + dbPath.getParent(), e);
    }
    try (var db = Sqlite.open(dbPath)) {
      var schema = new SchemaManager(db);
      var before = schema.currentVersion();
      schema.migrate();
      var after = schema.currentVersion();
      var prompter = nonInteractive ? DataMigration.Prompter.NON_INTERACTIVE : ttyPrompter();
      var projects = ProjectRegistry.loadFromDisk();
      var runs = new DataMigrator(db, REGISTRY).run(projects, prompter);
      if (!jsonOutput) {
        printSummary(dbPath.toString(), before, after, runs);
      }
      return runs;
    }
  }

  private static void printSummary(
      String dbPath, int beforeSchema, int afterSchema, List<DataMigrator.Run> runs) {
    System.out.println(Ansi.AUTO.string("  @|green ✓|@ Database: " + dbPath));
    if (afterSchema > beforeSchema) {
      System.out.println(
          Ansi.AUTO.string(
              "    @|faint Schema migrated: " + beforeSchema + " → " + afterSchema + "|@"));
    } else {
      System.out.println(
          Ansi.AUTO.string("    @|faint Schema up to date (version " + afterSchema + ")|@"));
    }
    for (var run : runs) {
      if (run.alreadyApplied()) {
        System.out.println(Ansi.AUTO.string("    @|faint " + run.name() + ": already applied|@"));
        continue;
      }
      var report = run.report();
      System.out.println(
          Ansi.AUTO.string(
              "  @|green ✓|@ "
                  + run.name()
                  + ": "
                  + report.applied()
                  + " applied, "
                  + report.ambiguous()
                  + " ambiguous, "
                  + report.skipped()
                  + " skipped"));
      for (var note : report.notes()) {
        System.out.println(note);
      }
    }
  }

  private static DataMigration.Prompter ttyPrompter() {
    if (System.console() == null) {
      return DataMigration.Prompter.NON_INTERACTIVE;
    }
    var reader = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
    return (context, candidates) -> {
      System.out.println();
      System.out.println("  " + context + " could belong to:");
      for (var i = 0; i < candidates.size(); i++) {
        System.out.println("    " + (i + 1) + ") " + candidates.get(i));
      }
      System.out.print("  Pick 1-" + candidates.size() + " (Enter to skip): ");
      try {
        var line = reader.readLine();
        if (line == null || line.isBlank()) {
          return Optional.empty();
        }
        var idx = Integer.parseInt(line.trim()) - 1;
        if (idx < 0 || idx >= candidates.size()) {
          return Optional.empty();
        }
        return Optional.of(candidates.get(idx));
      } catch (Exception e) {
        return Optional.empty();
      }
    };
  }
}
