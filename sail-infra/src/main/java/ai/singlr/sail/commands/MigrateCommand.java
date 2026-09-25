/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.commands;

import ai.singlr.sail.common.Strings;
import ai.singlr.sail.config.HostYaml;
import ai.singlr.sail.config.YamlUtil;
import ai.singlr.sail.engine.AuthorizedKeysSync;
import ai.singlr.sail.engine.ContainerManager;
import ai.singlr.sail.engine.ContainerSailSetup;
import ai.singlr.sail.engine.ContainerState;
import ai.singlr.sail.engine.DemoSeeder;
import ai.singlr.sail.engine.FileImporter;
import ai.singlr.sail.engine.HostInfo;
import ai.singlr.sail.engine.IncusDeviceManager;
import ai.singlr.sail.engine.ProjectImporter;
import ai.singlr.sail.engine.PtyHostUnit;
import ai.singlr.sail.engine.SailPaths;
import ai.singlr.sail.engine.ShellExec;
import ai.singlr.sail.engine.ShellExecutor;
import ai.singlr.sail.engine.Spinner;
import ai.singlr.sail.engine.SshIdentityProvisioner;
import ai.singlr.sail.engine.SshdKeepalive;
import ai.singlr.sail.engine.SystemdServiceInstaller;
import ai.singlr.sail.identity.Actor;
import ai.singlr.sail.pty.PtyMessage;
import ai.singlr.sail.store.DataMigration;
import ai.singlr.sail.store.DataMigrations;
import ai.singlr.sail.store.DataMigrator;
import ai.singlr.sail.store.FileStore;
import ai.singlr.sail.store.MigrationRunner;
import ai.singlr.sail.store.ProjectStore;
import ai.singlr.sail.store.Sqlite;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeoutException;
import java.util.function.BiConsumer;
import java.util.function.Supplier;
import picocli.CommandLine.Command;
import picocli.CommandLine.Help.Ansi;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Spec;

/**
 * Single command that runs every pending schema + data migration on the control-plane database.
 * Idempotent: schema migrations are tracked by version, data migrations by name, so re-runs do
 * nothing. {@code sail upgrade} runs the new binary's {@code migrate} at the end, so an upgrade
 * needs no manual step.
 */
@Command(
    name = "migrate",
    description = "Apply all pending schema and data migrations.",
    mixinStandardHelpOptions = true)
public final class MigrateCommand implements Runnable {

  /** Every one-shot data migration tracked in {@code data_migrations}. Add new ones at the end. */
  public static final List<DataMigration> REGISTRY = DataMigrations.ALL;

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
   * Runs every pending migration on this box's database and converges the host around it, within
   * the {@link Scope} this binary and {@code SAIL_DATA_DIR} allow.
   */
  public static List<DataMigrator.Run> runMigrations(boolean nonInteractive, boolean jsonOutput) {
    var dbPath = SailPaths.controlPlaneDb();
    return runMigrations(
        dbPath,
        nonInteractive,
        jsonOutput,
        scope(
            SailPaths.dataDirOverridden(),
            dbPath,
            SailPaths.binaryPath(),
            SailPaths.findInstalledBinary()),
        ON_THIS_BOX);
  }

  /**
   * What one run of {@code migrate} may change. {@link Box}: the database and the host around it —
   * keys, host config, units, services, containers. {@link DatabaseOnly}: only the database it
   * opens, and it says why. {@link Refused}: nothing at all, decided before the database is opened.
   */
  sealed interface Scope permits Scope.Box, Scope.DatabaseOnly, Scope.Refused {
    record Box() implements Scope {}

    record DatabaseOnly(String why) implements Scope {}

    record Refused(String why) implements Scope {}
  }

  /**
   * The scope of a run. A {@code SAIL_DATA_DIR} override is a rehearsal against a copy: it
   * converges the copy and nothing on the box. A box with nothing installed converges its database
   * only, since host state names the installed binary. The installed binary converges the box. Any
   * other binary — a staged build — is refused before it opens the database: migrated by a newer
   * binary, the database would lock the installed one out, and host state must keep naming the
   * installed binary. Pure for testing.
   */
  static Scope scope(boolean dataDirOverridden, Path db, Path running, Optional<Path> installed) {
    if (dataDirOverridden) {
      return new Scope.DatabaseOnly(
          "  @|yellow ⚠|@ Rehearsal: migrated "
              + db
              + " only. SAIL_DATA_DIR is not the provisioned "
              + SailPaths.systemDataDir()
              + ", so no keys, units or services were touched.");
    }
    if (installed.isEmpty()) {
      return new Scope.DatabaseOnly(
          "  @|yellow ⚠|@ Migrated the database only; host state left as is. No sail is"
              + " installed at "
              + SailPaths.INSTALLED_BINARY
              + ": install it there with install.sh, then rerun to converge the box.");
    }
    if (running.equals(installed.get())) {
      return new Scope.Box();
    }
    return new Scope.Refused(
        "This is "
            + running
            + ", not the installed "
            + installed.get()
            + ". Migrating this box with it would leave the installed sail unable to open its"
            + " database. Install it first: sudo "
            + installed.get()
            + " upgrade --binary "
            + running
            + ". Or rehearse on a copy: SAIL_DATA_DIR=<copy> "
            + running
            + " migrate.");
  }

  /**
   * The steps of a migrate beyond the schema and data migrations: {@code imports} bring what lives
   * on this box's disk into the database being migrated — the project catalog and shared files —
   * and {@code host} converges the box around it.
   */
  record Convergence(BiConsumer<Sqlite, Boolean> imports, BiConsumer<Sqlite, Boolean> host) {}

  private static final Convergence ON_THIS_BOX =
      new Convergence(MigrateCommand::importAll, MigrateCommand::convergeHost);

  static List<DataMigrator.Run> runMigrations(
      Path dbPath,
      boolean nonInteractive,
      boolean jsonOutput,
      Scope scope,
      Convergence convergence) {
    if (scope instanceof Scope.Refused refused) {
      throw new IllegalStateException(refused.why());
    }
    try {
      SailPaths.ensureDataDir(dbPath.getParent());
    } catch (Exception e) {
      throw new IllegalStateException("Could not prepare " + dbPath.getParent(), e);
    }
    try (var db = Sqlite.open(dbPath)) {
      var prompter = nonInteractive ? DataMigration.Prompter.NON_INTERACTIVE : ttyPrompter();
      var animate = !jsonOutput && System.console() != null;
      var runs = applyMigrations(db, dbPath.toString(), prompter, animate, jsonOutput);
      convergence.imports().accept(db, jsonOutput);
      if (scope instanceof Scope.DatabaseOnly databaseOnly) {
        (jsonOutput ? System.err : System.out).println(Ansi.AUTO.string(databaseOnly.why()));
        return runs;
      }
      convergence.host().accept(db, jsonOutput);
      return runs;
    }
  }

  private static void importAll(Sqlite db, boolean jsonOutput) {
    Actor.run(
        Actor.system(),
        () -> {
          importProjects(db, jsonOutput);
          scrubProjectIdentity(db, jsonOutput);
          importFiles(db, jsonOutput);
          seedDemo(db, jsonOutput);
        });
  }

  private static void convergeHost(Sqlite db, boolean jsonOutput) {
    relocateHostConfig(jsonOutput);
    assignBoxId(jsonOutput);
    syncAuthorizedKeys(db, jsonOutput);
    ensureSshdKeepalive(jsonOutput);
    convergeContainers(jsonOutput);
    ensurePtyHostService(jsonOutput);
  }

  /**
   * Installs {@code sail-pty-host.service} on a provisioned box. The unit is newer than most
   * existing boxes were provisioned, and {@code sail upgrade} spawns the new binary's {@code
   * migrate} — so this is the one place that can bring the host up on an upgrade without a manual
   * step. It also restarts the host so the new binary takes effect (an upgrade rewrites the binary
   * but the old process keeps running until restarted). Gated on the API service being present (a
   * real host, not a stray {@code sail migrate} in some directory) and fail-soft (a systemd hiccup
   * warns rather than aborting the migration).
   */
  private static void ensurePtyHostService(boolean jsonOutput) {
    var shell = new ShellExecutor(false);
    var api = HostServiceInstallers.create(shell);
    ensurePtyHostService(
        api.isInstalled(),
        shell,
        api.mode(),
        HostServiceInstallers.userHome(),
        SailPaths.installedBinary(),
        SailPaths.ptySocketPath(),
        jsonOutput);
  }

  static void ensurePtyHostService(
      boolean apiInstalled,
      ShellExec shell,
      SystemdServiceInstaller.Mode mode,
      Path userHome,
      Path binary,
      Path ptySocket,
      boolean jsonOutput) {
    if (!apiInstalled) {
      return;
    }
    try {
      var unit = new PtyHostUnit(shell, mode, userHome, binary);
      var lastRestart = PtyHostUnit.predatesLiveHandoff(unit.serviceFilePath());
      if (lastRestart && !jsonOutput) {
        firstHandoffNotice(ptySocket).ifPresent(System.out::println);
      }
      unit.install();
      if (!jsonOutput) {
        System.out.println(Ansi.AUTO.string("  @|green ✓|@ pty session host service ensured"));
      }
    } catch (IOException | TimeoutException e) {
      if (!jsonOutput) {
        System.out.println(
            Ansi.AUTO.string("  @|yellow ⚠|@ pty session host not installed: " + e.getMessage()));
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  /**
   * The one restart that still ends sessions: a host installed under a unit without the descriptor
   * store never pushed a master, so nothing survives its replacement. Says so, naming the live
   * sessions it asked the host for — or that the host could not be asked. Empty when there is
   * nothing to lose.
   */
  static Optional<String> firstHandoffNotice(Path ptySocket) {
    try (var client = SessionClient.connect(ptySocket)) {
      var live = client.list().stream().filter(PtyMessage.SessionInfo::live).count();
      if (live == 0) {
        return Optional.empty();
      }
      return Optional.of(
          Ansi.AUTO.string(
              "  @|yellow ⚠|@ pty host restarted: "
                  + live
                  + " live session"
                  + (live == 1 ? "" : "s")
                  + " ended — this host predates live handoff; from the next upgrade on,"
                  + " sessions survive"));
    } catch (IOException unreachable) {
      return Optional.of(
          Ansi.AUTO.string(
              "  @|yellow ⚠|@ pty host restarted: the host could not be asked which sessions were"
                  + " live ("
                  + unreachable.getMessage()
                  + "); this host predates live handoff; from the next upgrade on, sessions"
                  + " survive"));
    }
  }

  /** Imports catalog writes missed by current best-effort project creation. */
  private static void importProjects(Sqlite db, boolean jsonOutput) {
    var report = new ProjectImporter(SailPaths.projectsDir(), new ProjectStore(db)).importAll();
    if (!jsonOutput && report.imported() > 0) {
      System.out.println(
          Ansi.AUTO.string("  @|green ✓|@ project catalog: " + report.imported() + " imported"));
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
   * Persists a sync box id for a box that took its role before ids existed: the hostname, which is
   * what every peer's checkpoints for this box are already keyed by, so nothing re-seeds. A box
   * without a sync role has no identity to keep; a box that already has an id keeps it. Same
   * upgrade-convergence rationale as {@link #relocateHostConfig}; quiet when {@code host.yaml} is
   * not writable, since an unprivileged upgrade resolves the same value at runtime.
   */
  private static void assignBoxId(boolean jsonOutput) {
    var path = SailPaths.hostConfigPath();
    if (!Files.exists(path)) {
      return;
    }
    try {
      var host = HostYaml.fromMap(YamlUtil.parseFile(path));
      var assigned = assignBoxId(host, HostInfo.hostname());
      if (assigned.isEmpty()) {
        return;
      }
      YamlUtil.dumpToFile(assigned.get().toMap(), path);
      if (!jsonOutput) {
        System.out.println(
            Ansi.AUTO.string("  @|green ✓|@ sync box id set to " + assigned.get().sync().boxId()));
      }
    } catch (Exception e) {
      System.err.println(
          "  sync box id not persisted: "
              + e.getMessage()
              + ". Run 'sudo sail migrate' to write it; sync uses the hostname until then.");
    }
  }

  static Optional<HostYaml> assignBoxId(HostYaml host, String boxId) {
    if (host.sync().role() == null || host.sync().boxId() != null) {
      return Optional.empty();
    }
    return Optional.of(host.withSync(host.sync().withBoxId(boxId)));
  }

  /**
   * Writes the {@link SshdKeepalive} drop-in on the host and reloads sshd when it changed. Lives
   * here for the same reason {@link #syncAuthorizedKeys} does: an upgrade is executed by the OLD
   * binary, which re-execs the NEW binary's {@code migrate}, so this is the one step every upgrade
   * path runs with new-binary code. Needs root; quiet and never fatal otherwise.
   */
  private static void ensureSshdKeepalive(boolean jsonOutput) {
    ensureSshdKeepalive(
        SailPaths.isRoot(),
        new ShellExecutor(false),
        Path.of(SshdKeepalive.DROP_IN_PATH),
        jsonOutput);
  }

  static void ensureSshdKeepalive(boolean root, ShellExec shell, Path dropIn, boolean jsonOutput) {
    if (!root) {
      return;
    }
    try {
      if (Files.exists(dropIn) && Files.readString(dropIn).equals(SshdKeepalive.content())) {
        return;
      }
      var result = shell.exec(SshdKeepalive.installCommand(dropIn.toString()));
      if (!result.ok()) {
        throw new IOException(result.stderr());
      }
      if (!jsonOutput) {
        System.out.println(Ansi.AUTO.string("  @|green ✓|@ sshd keepalive drop-in written"));
      }
    } catch (IOException | TimeoutException e) {
      if (!jsonOutput) {
        System.out.println(
            Ansi.AUTO.string(
                "  @|yellow ⚠|@ sshd keepalive drop-in not written: " + e.getMessage()));
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  /**
   * Brings every running Sail container's machinery — the event-socket bind mount and every
   * sail-owned file, the sshd drop-in included — up to this binary's payloads, so an upgrade
   * converges containers without waiting for the next dispatch to heal them. Only instances Sail
   * itself provisioned qualify (the same provenance gate {@code project apply --all} trusts): a
   * foreign container on the host must never be handed the API socket and box credential by an
   * upgrade. Needs root for the {@code incus} calls; a container that fails is named with the
   * command that converges it.
   *
   * <p>The socket's host directory is created (and made traversable) first, because {@code sail
   * upgrade} runs migrate <em>before</em> it restarts {@code sail-api} onto the new path — so the
   * bind-mount source must exist here, or the re-pointed mounts would strand on a missing directory
   * until the server start materializes it.
   */
  private static void convergeContainers(boolean jsonOutput) {
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
    List<String> names;
    try {
      names =
          new ContainerManager(shell)
              .listAll().stream()
                  .filter(info -> info.state() instanceof ContainerState.Running)
                  .map(ContainerManager.ContainerInfo::name)
                  .toList();
    } catch (Exception e) {
      return;
    }
    var converged = 0;
    for (var name : sailManagedContainers(names, new IncusDeviceManager(shell))) {
      try {
        if (ContainerSailSetup.ensureInstalled(shell, name) == ContainerSailSetup.Result.UPDATED) {
          converged++;
        }
      } catch (Exception e) {
        System.err.println(
            "  machinery for "
                + name
                + " not converged: "
                + e.getMessage()
                + ". Converge with 'sudo sail project apply "
                + name
                + "'.");
      }
    }
    if (!jsonOutput && converged > 0) {
      System.out.println(
          Ansi.AUTO.string(
              "  @|green ✓|@ converged sail machinery in " + converged + " container(s)"));
    }
  }

  /**
   * The instances among {@code names} that carry Sail's provenance marker. A probe that fails
   * counts as foreign: migration converges what it can prove is Sail's and claims nothing else.
   * Pure for testing.
   */
  static List<String> sailManagedContainers(List<String> names, IncusDeviceManager devices) {
    return names.stream().filter(name -> sailManaged(name, devices)).toList();
  }

  private static boolean sailManaged(String name, IncusDeviceManager devices) {
    try {
      return ProjectApplyCommand.sailManaged(name, devices);
    } catch (Exception e) {
      return false;
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
