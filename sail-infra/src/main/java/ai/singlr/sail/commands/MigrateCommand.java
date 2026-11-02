/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.commands;

import ai.singlr.sail.engine.AuthorizedKeysSync;
import ai.singlr.sail.engine.BoxIdentity;
import ai.singlr.sail.engine.DemoSeeder;
import ai.singlr.sail.engine.HostDropIn;
import ai.singlr.sail.engine.HostPackages;
import ai.singlr.sail.engine.HostToken;
import ai.singlr.sail.engine.NeedrestartPolicy;
import ai.singlr.sail.engine.OperatorCommand;
import ai.singlr.sail.engine.PtyHostUnit;
import ai.singlr.sail.engine.SailPaths;
import ai.singlr.sail.engine.ShellExec;
import ai.singlr.sail.engine.ShellExecutor;
import ai.singlr.sail.engine.SshIdentityProvisioner;
import ai.singlr.sail.engine.SshdKeepalive;
import ai.singlr.sail.engine.SystemdServiceInstaller;
import ai.singlr.sail.identity.Actor;
import ai.singlr.sail.store.BoxDatabase;
import ai.singlr.sail.store.SchemaManager;
import ai.singlr.sail.store.Sqlite;
import ai.singlr.sail.store.TokenStore;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeoutException;
import java.util.function.BiConsumer;
import picocli.CommandLine.Command;
import picocli.CommandLine.Help.Ansi;
import picocli.CommandLine.Option;

/**
 * Brings this box to the release it runs: creates the database's schema when it is fresh, or
 * refuses one of another release ({@link BoxDatabase}), and seeds the demo project; then, on the
 * box itself, re-issues the host token, re-syncs {@code authorized_keys}, writes the host's
 * drop-ins, holds Incus and ensures the pty session host. {@code sail upgrade} runs the new
 * binary's {@code migrate} once it is installed, so this is where new-release code converges the
 * host as root. Idempotent: a second run changes nothing.
 */
@Command(
    name = "migrate",
    description = "Bring this box's database and host services to this release.",
    mixinStandardHelpOptions = true)
public final class MigrateCommand implements Runnable {

  @Option(names = "--json", description = "Output JSON instead of human-readable text.")
  private boolean json;

  @Override
  public void run() {
    CliCommand.run(this::execute);
  }

  private void execute() {
    var operator = OperatorCommand.here();
    var dbPath = SailPaths.controlPlaneDb();
    converge(
        dbPath,
        json,
        scope(
            SailPaths.dataDirOverridden(),
            dbPath,
            SailPaths.binaryPath(),
            SailPaths.findInstalledBinary()),
        (db, jsonOutput) -> convergeHost(db, jsonOutput, operator));
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
   * Prepares the database at {@code dbPath}, then, when {@code scope} is the whole box, converges
   * the host around it with {@code host}.
   */
  static void converge(
      Path dbPath, boolean jsonOutput, Scope scope, BiConsumer<Sqlite, Boolean> host) {
    if (scope instanceof Scope.Refused refused) {
      throw new IllegalStateException(refused.why());
    }
    try {
      SailPaths.ensureDataDir(dbPath.getParent());
    } catch (Exception e) {
      throw new IllegalStateException("Could not prepare " + dbPath.getParent(), e);
    }
    try (var db = Sqlite.open(dbPath)) {
      BoxDatabase.prepare(db);
      if (!jsonOutput) {
        System.out.println(
            Ansi.AUTO.string(
                "  @|green ✓|@ Database: "
                    + dbPath
                    + " @|faint (schema v"
                    + new SchemaManager(db).currentVersion()
                    + ")|@"));
      }
      Actor.run(
          Actor.system(),
          () -> {
            seedDemo(db, jsonOutput);
          });
      if (scope instanceof Scope.DatabaseOnly databaseOnly) {
        (jsonOutput ? System.err : System.out).println(Ansi.AUTO.string(databaseOnly.why()));
        return;
      }
      host.accept(db, jsonOutput);
    }
  }

  /**
   * Seeds the bundled demo project into the catalog so {@code sail project demo} is a
   * database-resident project like any other. Only when the catalog never held one, so a customised
   * or destroyed demo is never clobbered; a node, which takes main's catalog, seeds nothing.
   */
  private static void seedDemo(Sqlite db, boolean jsonOutput) {
    if (DemoSeeder.seedIfAbsent(db) && !jsonOutput) {
      System.out.println(Ansi.AUTO.string("  @|green ✓|@ demo project seeded"));
    }
  }

  private static void convergeHost(Sqlite db, boolean jsonOutput, OperatorCommand operator) {
    ensureHostToken(db, jsonOutput, operator);
    syncAuthorizedKeys(db, jsonOutput, operator);
    ensureHostDropIns(jsonOutput);
    ensureIncusHold(jsonOutput, operator);
    ensurePtyHostService(jsonOutput);
  }

  /**
   * Re-issues this box's host token when its client config holds one the database does not ({@link
   * HostToken#ensure}): a database restored from another box's copy holds that box's tokens, and
   * every host CLI call would be refused until the token is minted here.
   */
  private static void ensureHostToken(Sqlite db, boolean jsonOutput, OperatorCommand operator) {
    var configPath = SailPaths.clientConfigPath();
    try {
      var said =
          HostToken.said(
              HostToken.ensure(new TokenStore(db), configPath), configPath, BoxIdentity.config());
      if (!jsonOutput) {
        said.ifPresent(line -> System.out.println(Ansi.AUTO.string("  @|green ✓|@ " + line)));
      }
    } catch (Exception e) {
      System.err.println(
          "  API token in "
              + configPath
              + " not checked: "
              + e.getMessage()
              + ". Run '"
              + operator.sail("migrate")
              + "' again.");
    }
  }

  /**
   * Converges the {@code sail} user's {@code authorized_keys} with the SSH-key registry on
   * provisioned hosts: a main set up again from a copy of its database holds its FDEs' keys, and
   * this is what lets their boxes in. Quiet when there is nothing to do (unprovisioned host,
   * non-root) and never fatal.
   */
  private static void syncAuthorizedKeys(Sqlite db, boolean jsonOutput, OperatorCommand operator) {
    try {
      if (new AuthorizedKeysSync().sync(db) instanceof AuthorizedKeysSync.Synced synced
          && !jsonOutput) {
        System.out.println(Ansi.AUTO.string("  @|green ✓|@ " + synced.describe()));
      }
    } catch (Exception e) {
      System.err.println(
          "  authorized_keys sync failed: "
              + e.getMessage()
              + ". Converge manually with '"
              + operator.sail("host keys sync")
              + "'.");
    }
  }

  /**
   * Installs {@code sail-pty-host.service} and restarts the host onto this binary, handing its live
   * sessions over: an upgrade rewrites the binary but the old process keeps running until
   * restarted. Gated on the API service being present (a real host, not a stray {@code sail
   * migrate} in some directory) and fail-soft (a systemd hiccup warns rather than aborting).
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
        jsonOutput);
  }

  static void ensurePtyHostService(
      boolean apiInstalled,
      ShellExec shell,
      SystemdServiceInstaller.Mode mode,
      Path userHome,
      Path binary,
      boolean jsonOutput) {
    if (!apiInstalled) {
      return;
    }
    try {
      new PtyHostUnit(shell, mode, userHome, binary).install();
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

  private static final List<HostDropIn> HOST_DROP_INS =
      List.of(SshdKeepalive.DROP_IN, NeedrestartPolicy.DROP_IN);

  /**
   * Writes the host's drop-ins, {@link SshdKeepalive} and {@link NeedrestartPolicy}, and on a main
   * whose sail-api is pointed at a data directory, the pty session host's copy of that drop-in
   * ({@link SshIdentityProvisioner#ptyHostDataDir}), which {@link #ensurePtyHostService} then
   * restarts onto it. Needs root; quiet and never fatal otherwise.
   */
  private static void ensureHostDropIns(boolean jsonOutput) {
    ensureHostDropIns(SailPaths.isRoot(), new ShellExecutor(false), Path.of("/"), jsonOutput);
  }

  static void ensureHostDropIns(boolean root, ShellExec shell, Path fsRoot, boolean jsonOutput) {
    if (!root) {
      return;
    }
    for (var dropIn : HOST_DROP_INS) {
      ensureDropIn(dropIn, shell, fsRoot.resolve(dropIn.path().substring(1)), jsonOutput);
    }
    var apis = fsRoot.resolve(SshIdentityProvisioner.DROP_IN.substring(1));
    if (Files.exists(apis)) {
      try {
        var mirrored = SshIdentityProvisioner.ptyHostDataDir(Files.readString(apis));
        ensureDropIn(mirrored, shell, fsRoot.resolve(mirrored.path().substring(1)), jsonOutput);
      } catch (IOException e) {
        if (!jsonOutput) {
          System.out.println(Ansi.AUTO.string("  @|yellow ⚠|@ " + e.getMessage()));
        }
      }
    }
  }

  private static void ensureDropIn(
      HostDropIn dropIn, ShellExec shell, Path path, boolean jsonOutput) {
    try {
      if (Files.exists(path) && Files.readString(path).equals(dropIn.content())) {
        return;
      }
      dropIn.install(shell, path.toString());
      if (!jsonOutput) {
        System.out.println(Ansi.AUTO.string("  @|green ✓|@ " + dropIn.name() + " written"));
      }
    } catch (IOException | TimeoutException e) {
      if (!jsonOutput) {
        System.out.println(Ansi.AUTO.string("  @|yellow ⚠|@ " + e.getMessage()));
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  /**
   * Holds Incus ({@link HostPackages#hold}), so no unattended upgrade replaces what sail runs on.
   * Says so the first time; quiet once held, and without root.
   */
  private static void ensureIncusHold(boolean jsonOutput, OperatorCommand operator) {
    ensureIncusHold(SailPaths.isRoot(), new ShellExecutor(false), jsonOutput, operator);
  }

  static void ensureIncusHold(
      boolean root, ShellExec shell, boolean jsonOutput, OperatorCommand operator) {
    if (!root) {
      return;
    }
    try {
      if (new HostPackages(shell, detail -> {}).hold() && !jsonOutput) {
        System.out.println(Ansi.AUTO.string("  @|green ✓|@ " + operator.render(HostPackages.HELD)));
      }
    } catch (IOException | TimeoutException e) {
      if (!jsonOutput) {
        System.out.println(
            Ansi.AUTO.string(
                "  @|yellow ⚠|@ "
                    + e.getMessage()
                    + ". Run '"
                    + operator.sail("migrate")
                    + "' again to hold Incus."));
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }
}
