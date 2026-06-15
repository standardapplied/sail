/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.commands;

import ai.singlr.sail.common.Strings;
import ai.singlr.sail.engine.AuthorizedKeysSync;
import ai.singlr.sail.engine.FileImporter;
import ai.singlr.sail.engine.ProjectImporter;
import ai.singlr.sail.engine.SailPaths;
import ai.singlr.sail.engine.Spinner;
import ai.singlr.sail.engine.SshIdentityProvisioner;
import ai.singlr.sail.store.DataMigration;
import ai.singlr.sail.store.DataMigrator;
import ai.singlr.sail.store.FileStore;
import ai.singlr.sail.store.MigrationRunner;
import ai.singlr.sail.store.ProjectStore;
import ai.singlr.sail.store.RebucketSpecsMigration;
import ai.singlr.sail.store.Sqlite;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;
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

  /**
   * Every one-shot data migration tracked in {@code data_migrations}. Add new ones at the bottom.
   */
  public static final List<DataMigration> REGISTRY = List.of(new RebucketSpecsMigration());

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
    var dbPath = SailPaths.controlPlaneDb();
    try {
      SailPaths.ensureDataDir(dbPath.getParent());
    } catch (Exception e) {
      throw new IllegalStateException("Could not prepare " + dbPath.getParent(), e);
    }
    try (var db = Sqlite.open(dbPath)) {
      var prompter = nonInteractive ? DataMigration.Prompter.NON_INTERACTIVE : ttyPrompter();
      var animate = !jsonOutput && System.console() != null;
      var runs = applyMigrations(db, dbPath.toString(), prompter, animate, jsonOutput);
      importProjects(db, jsonOutput);
      importFiles(db, jsonOutput);
      relocateHostConfig(jsonOutput);
      syncAuthorizedKeys(db, jsonOutput);
      return runs;
    }
  }

  /**
   * Backfills the project catalog from on-disk descriptors, so projects created before the catalog
   * existed appear in the DB (the shared, replicated source of truth). Repeatable and idempotent;
   * quiet when nothing was imported.
   */
  private static void importProjects(Sqlite db, boolean jsonOutput) {
    var report = new ProjectImporter(SailPaths.projectsDir(), new ProjectStore(db)).importAll();
    if (!jsonOutput && report.imported() > 0) {
      System.out.println(
          Ansi.AUTO.string("  @|green ✓|@ project catalog: " + report.imported() + " imported"));
    }
  }

  /**
   * Imports each project's on-disk {@code files/} tree into the synced {@link FileStore}, so the
   * shared workspace files an FDE already has become replicated the moment they upgrade.
   * Idempotent; quiet when nothing changed.
   */
  private static void importFiles(Sqlite db, boolean jsonOutput) {
    var report = new FileImporter(SailPaths.projectsDir(), new FileStore(db)).importAll();
    if (!jsonOutput && report.imported() > 0) {
      System.out.println(
          Ansi.AUTO.string("  @|green ✓|@ project files: " + report.imported() + " imported"));
    }
  }

  /**
   * Moves {@code host.yaml} into the shared data directory on provisioned hosts, so commands
   * arriving through the {@code sail} user's SSH gateway can read host configuration (e.g. the
   * webauthn origin printed by {@code fde enroll}). Same upgrade-convergence rationale as {@link
   * #syncAuthorizedKeys}: this is the step guaranteed to run new-binary code during an upgrade.
   * No-op unless this host is provisioned (shared dir exists), the process is root, a legacy file
   * exists, and the shared copy does not.
   */
  private static void relocateHostConfig(boolean jsonOutput) {
    var legacy = SailPaths.sailDir().resolve("host.yaml");
    var shared = Path.of(SshIdentityProvisioner.DEFAULT_DATA_DIR).resolve("host.yaml");
    if (!SailPaths.isRoot()
        || !Files.isDirectory(shared.getParent())
        || Files.exists(shared)
        || !Files.exists(legacy)) {
      return;
    }
    try {
      Files.move(legacy, shared);
      var view = Files.getFileAttributeView(shared, PosixFileAttributeView.class);
      view.setGroup(
          shared
              .getFileSystem()
              .getUserPrincipalLookupService()
              .lookupPrincipalByGroupName(SshIdentityProvisioner.SAIL_USER));
      Files.setPosixFilePermissions(shared, PosixFilePermissions.fromString("rw-r-----"));
      if (!jsonOutput) {
        System.out.println(
            Ansi.AUTO.string("  @|green ✓|@ host.yaml moved to " + shared.getParent()));
      }
    } catch (Exception e) {
      System.err.println(
          "  host.yaml relocation failed: "
              + e.getMessage()
              + ". Converge manually with 'sudo sail host ssh-identity'.");
    }
  }

  /**
   * Converges the {@code sail} user's {@code authorized_keys} with the SSH-key registry on
   * provisioned hosts. Living here (not in {@code upgrade}) is load-bearing: an upgrade is executed
   * by the OLD binary, which re-execs the NEW binary's {@code migrate} — so this is the one step
   * guaranteed to run new-binary code on every upgrade path. Quiet when there is nothing to do
   * (unprovisioned host, non-root) and never fatal.
   */
  private static void syncAuthorizedKeys(Sqlite db, boolean jsonOutput) {
    try {
      if (new AuthorizedKeysSync().sync(db) instanceof AuthorizedKeysSync.Synced synced
          && !jsonOutput) {
        System.out.println(Ansi.AUTO.string("  @|green ✓|@ " + synced.describe()));
      }
    } catch (Exception e) {
      System.err.println(
          "  authorized_keys sync failed: "
              + e.getMessage()
              + ". Converge manually with 'sudo sail host keys sync'.");
    }
  }

  /**
   * Applies all pending schema + data migrations. Specs are no longer scanned from project
   * containers here: the database is the source of truth and agents write to it directly through
   * the in-container {@code spec} CLI, so an upgrade never probes a container. Visible for tests.
   */
  static List<DataMigrator.Run> applyMigrations(
      Sqlite db,
      String dbPath,
      DataMigration.Prompter prompter,
      boolean animate,
      boolean jsonOutput) {
    var result =
        phase(
            animate,
            "Migrating database schema",
            () -> MigrationRunner.applyAll(db, REGISTRY, prompter));
    if (!jsonOutput) {
      printSummary(dbPath, result.schemaBefore(), result.schemaAfter(), result.dataRuns());
    }
    return result.dataRuns();
  }

  private static <T> T phase(boolean animate, String message, Supplier<T> work) {
    if (!animate) {
      return work.get();
    }
    try (var ignored = Spinner.start(System.out, message)) {
      return work.get();
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
        if (Strings.isBlank(line)) {
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
