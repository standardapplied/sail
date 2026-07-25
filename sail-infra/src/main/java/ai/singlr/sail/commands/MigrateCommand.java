/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.commands;

import ai.singlr.sail.common.Strings;
import ai.singlr.sail.engine.AuthorizedKeysSync;
import ai.singlr.sail.engine.ContainerManager;
import ai.singlr.sail.engine.ContainerSailSetup;
import ai.singlr.sail.engine.DemoSeeder;
import ai.singlr.sail.engine.FileImporter;
import ai.singlr.sail.engine.IncusDeviceManager;
import ai.singlr.sail.engine.NodeIdentity;
import ai.singlr.sail.engine.ProjectImporter;
import ai.singlr.sail.engine.SailPaths;
import ai.singlr.sail.engine.ShellExecutor;
import ai.singlr.sail.engine.Spinner;
import ai.singlr.sail.engine.SshIdentityProvisioner;
import ai.singlr.sail.store.DataMigration;
import ai.singlr.sail.store.DataMigrator;
import ai.singlr.sail.store.FileStore;
import ai.singlr.sail.store.LegacyDataMigration;
import ai.singlr.sail.store.MigrationRunner;
import ai.singlr.sail.store.ProjectStore;
import ai.singlr.sail.store.RunStore;
import ai.singlr.sail.store.SpecStore;
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
import java.util.function.UnaryOperator;
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

  /** Every one-shot data migration tracked in {@code data_migrations}. Add new ones at the end. */
  public static final List<DataMigration> REGISTRY = List.of(new LegacyDataMigration());

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
      applyDataBackfills(db, jsonOutput);
      relocateHostConfig(jsonOutput);
      syncAuthorizedKeys(db, jsonOutput);
      relocateContainerSockets(jsonOutput);
      return runs;
    }
  }

  /**
   * The database-only data backfills — every one idempotent and quiet when nothing is needed — that
   * make pre-migration rows visible to sync and seed the bundled demo. Shared with {@link
   * ServerStartCommand} so a daemon start is a genuine second chance: if the post-upgrade {@code
   * migrate} sub-process ever fails, the next service start still converges the data. Host-level
   * steps (relocating {@code host.yaml}, syncing {@code authorized_keys}) are not here — they need
   * root and run only in the full {@link #runMigrations} path.
   */
  public static void applyDataBackfills(Sqlite db, boolean jsonOutput) {
    backfillSpecRevisions(db, jsonOutput);
    importProjects(db, jsonOutput);
    backfillProjectRevisions(db, jsonOutput);
    scrubProjectIdentity(db, jsonOutput);
    importFiles(db, jsonOutput);
    backfillRuns(db, jsonOutput);
    seedDemo(db, jsonOutput);
  }

  /**
   * Carries pre-Run rows forward: stamps each with this box's FDE handle as its {@code node} (every
   * legacy run executed here) so it stops reading as foreign under the provenance guard, and
   * journals a baseline revision so it replicates. Quiet when nothing needed it; a box with no
   * handle yet stamps nothing and retries on the next migrate/start.
   */
  private static void backfillRuns(Sqlite db, boolean jsonOutput) {
    var runs = new RunStore(db);
    var stamped = runs.backfillNode(NodeIdentity.handle());
    var journaled = runs.backfillRevisions();
    if (!jsonOutput && stamped + journaled > 0) {
      System.out.println(
          Ansi.AUTO.string(
              "  @|green ✓|@ runs: "
                  + stamped
                  + " stamped with this node, "
                  + journaled
                  + " made syncable"));
    }
  }

  /**
   * Journals a baseline revision for specs that predate the sync machinery, so a spec sitting in
   * the database without a change-log entry becomes visible to {@code sail sync}. The analog of
   * {@link #backfillProjectRevisions} for specs. Quiet when nothing needed it.
   */
  private static void backfillSpecRevisions(Sqlite db, boolean jsonOutput) {
    var backfilled = new SpecStore(db).backfillRevisions();
    if (!jsonOutput && backfilled > 0) {
      System.out.println(Ansi.AUTO.string("  @|green ✓|@ specs: " + backfilled + " made syncable"));
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
   * Journals a baseline revision for catalogued projects that predate the sync machinery, so a
   * project sitting in the catalog without a change-log entry becomes visible to {@code sail sync}.
   * Quiet when nothing needed it.
   */
  private static void backfillProjectRevisions(Sqlite db, boolean jsonOutput) {
    var backfilled = new ProjectStore(db).backfillRevisions();
    if (!jsonOutput && backfilled > 0) {
      System.out.println(
          Ansi.AUTO.string("  @|green ✓|@ project catalog: " + backfilled + " made syncable"));
    }
  }

  /**
   * Scrubs each catalogued definition of the per-developer git identity and SSH keys a pre-brick
   * catalog stored concretely, rewriting them to placeholders so one box's identity stops riding
   * the synced state onto everyone else's. Idempotent; quiet when every definition is already
   * clean.
   */
  private static void scrubProjectIdentity(Sqlite db, boolean jsonOutput) {
    var scrubbed = new ProjectStore(db).canonicalizeDefinitions();
    if (!jsonOutput && scrubbed > 0) {
      System.out.println(
          Ansi.AUTO.string(
              "  @|green ✓|@ project catalog: "
                  + scrubbed
                  + " scrubbed of per-developer identity"));
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
   * Seeds the bundled demo project into the catalog so {@code sail project demo} is a
   * database-resident project like any other — no GitHub. Idempotent: only inserts when no {@code
   * demo} project exists, so a customised or destroyed demo is never clobbered.
   */
  private static void seedDemo(Sqlite db, boolean jsonOutput) {
    if (DemoSeeder.seedIfAbsent(db) && !jsonOutput) {
      System.out.println(Ansi.AUTO.string("  @|green ✓|@ demo project seeded"));
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
   * Re-points every project container's event-socket bind mount from the legacy {@code /run/sail}
   * location to the current {@link SailPaths#apiSocketHostDir()} after the socket moved off the
   * volatile {@code /run} tmpfs. Idempotent and quiet: a container already on the new source is
   * skipped, one without the device is left untouched (provisioning owns that). Needs root for the
   * {@code incus} calls; {@link ContainerSailSetup#ensureInstalled} re-adds the device live and
   * rewrites the in-container scripts to the new path.
   *
   * <p>The new host directory is created (and made traversable) first, because {@code sail upgrade}
   * runs migrate <em>before</em> it restarts {@code sail-api} onto the new path — so the bind-mount
   * source must exist here, or the re-pointed mounts would strand on a missing directory until the
   * server start materializes it. The directory bind mount then surfaces the socket the moment the
   * restart binds it.
   */
  private static void relocateContainerSockets(boolean jsonOutput) {
    if (!SailPaths.isRoot()) {
      return;
    }
    var hostDir = SailPaths.apiSocketHostDir();
    try {
      Files.createDirectories(hostDir);
      Files.setPosixFilePermissions(hostDir, PosixFilePermissions.fromString("rwxr-xr-x"));
    } catch (Exception e) {
      return;
    }
    var shell = new ShellExecutor(false);
    var devices = new IncusDeviceManager(shell);
    var desired = hostDir.toString();
    List<String> names;
    try {
      names =
          new ContainerManager(shell)
              .listAll().stream().map(ContainerManager.ContainerInfo::name).toList();
    } catch (Exception e) {
      return;
    }
    var moved = 0;
    for (var name : containersToRelocate(names, n -> currentSocketSource(devices, n), desired)) {
      try {
        ContainerSailSetup.ensureInstalled(shell, name);
        moved++;
      } catch (Exception e) {
        System.err.println(
            "  socket relocation for "
                + name
                + " failed: "
                + e.getMessage()
                + ". Converge with 'sudo sail project reconfigure "
                + name
                + "'.");
      }
    }
    if (!jsonOutput && moved > 0) {
      System.out.println(
          Ansi.AUTO.string(
              "  @|green ✓|@ re-pointed " + moved + " container socket(s) to " + desired));
    }
  }

  /**
   * The containers whose event-socket device exists but still points at a source other than {@code
   * desired}. A {@code null} source (no device) is skipped — provisioning, not migration, owns
   * that. Pure for testing.
   */
  static List<String> containersToRelocate(
      List<String> names, UnaryOperator<String> sourceOf, String desired) {
    return names.stream()
        .filter(
            name -> {
              var source = sourceOf.apply(name);
              return source != null && !desired.equals(source);
            })
        .toList();
  }

  private static String currentSocketSource(IncusDeviceManager devices, String container) {
    try {
      return devices.currentEventSocketSource(container);
    } catch (Exception e) {
      return null;
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
