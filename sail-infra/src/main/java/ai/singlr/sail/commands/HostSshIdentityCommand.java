/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.commands;

import ai.singlr.sail.engine.AuthorizedKeysSync;
import ai.singlr.sail.engine.SailPaths;
import ai.singlr.sail.engine.ShellExecutor;
import ai.singlr.sail.engine.SshIdentityProvisioner;
import ai.singlr.sail.store.Sqlite;
import java.nio.file.Files;
import java.util.List;
import picocli.CommandLine.Command;
import picocli.CommandLine.Help.Ansi;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Spec;

/**
 * Provisions a host for SSH-key FDE login: the locked-down {@code sail} user, the shared {@code
 * /var/lib/sail} data directory, the database moved into it, a systemd drop-in pointing {@code
 * sail-api} there, and the registered keys synced into the {@code sail} user's {@code
 * authorized_keys}. Every step is idempotent, so re-running converges a partially provisioned host.
 * The plan touches no global sshd config and never alters the operator's existing root SSH.
 */
@Command(
    name = "ssh-identity",
    description = "Enable SSH-key login for FDEs (provision the sail user + shared data dir).",
    mixinStandardHelpOptions = true)
public final class HostSshIdentityCommand implements Runnable {

  @Option(names = "--dry-run", description = "Print the plan instead of executing it.")
  private boolean dryRun;

  @Spec private CommandSpec spec;

  @Override
  public void run() {
    CliCommand.run(
        spec,
        () -> {
          var plan =
              SshIdentityProvisioner.plan(
                  SailPaths.sailDir(), SshIdentityProvisioner.DEFAULT_DATA_DIR);
          printPlan(plan);
          if (dryRun) {
            System.out.println();
            System.out.println(
                Ansi.AUTO.string("  @|faint Dry run only. Re-run as root to execute.|@"));
            return;
          }
          requireRoot();
          System.out.println();
          execute(plan);
          syncKeys();
          System.out.println();
          System.out.println(
              Ansi.AUTO.string(
                  "  @|green ✓|@ SSH-key login enabled. Register engineers with"
                      + " 'sail fde add <handle> --key \"<pubkey>\"'."));
        });
  }

  private static void printPlan(List<SshIdentityProvisioner.Step> plan) {
    System.out.println("  Plan to enable SSH-key FDE login:");
    System.out.println();
    var number = 1;
    for (var step : plan) {
      System.out.println(Ansi.AUTO.string("  @|bold " + number++ + ".|@ " + step.description()));
      switch (step) {
        case SshIdentityProvisioner.Run run ->
            System.out.println(
                Ansi.AUTO.string("     @|faint $ " + String.join(" ", run.command()) + "|@"));
        case SshIdentityProvisioner.WriteFile write -> {
          System.out.println(Ansi.AUTO.string("     @|faint write " + write.path() + ":|@"));
          for (var line : write.content().stripTrailing().split("\n")) {
            System.out.println(Ansi.AUTO.string("       @|faint " + line + "|@"));
          }
        }
      }
    }
  }

  private static void execute(List<SshIdentityProvisioner.Step> plan) throws Exception {
    var shell = new ShellExecutor(false);
    for (var step : plan) {
      switch (step) {
        case SshIdentityProvisioner.Run run -> {
          var result = shell.exec(run.command());
          if (!result.ok()) {
            throw new IllegalStateException(
                "Step failed: " + step.description() + "\n" + result.stderr());
          }
        }
        case SshIdentityProvisioner.WriteFile write ->
            Files.writeString(write.path(), write.content());
      }
      System.out.println(Ansi.AUTO.string("  @|green ✓|@ " + step.description()));
    }
  }

  private static void syncKeys() throws Exception {
    try (var db = Sqlite.open(SailPaths.controlPlaneDb())) {
      if (new AuthorizedKeysSync().sync(db) instanceof AuthorizedKeysSync.Synced synced) {
        System.out.println(
            Ansi.AUTO.string(
                "  @|green ✓|@ authorized_keys synced (" + synced.keyCount() + " key(s))"));
      }
    }
  }

  private static void requireRoot() {
    if (!"root".equals(ProcessHandle.current().info().user().orElse(""))) {
      throw new IllegalStateException(
          "This command must run as root: it creates a system user, relocates the database, and"
              + " edits a systemd unit. Preview with --dry-run, or re-run with sudo.");
    }
  }
}
