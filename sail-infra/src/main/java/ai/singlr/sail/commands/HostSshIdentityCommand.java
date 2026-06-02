/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.commands;

import ai.singlr.sail.engine.SailPaths;
import ai.singlr.sail.engine.ShellExecutor;
import ai.singlr.sail.engine.SshIdentityProvisioner;
import java.nio.file.Files;
import java.util.List;
import picocli.CommandLine.Command;
import picocli.CommandLine.Help.Ansi;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Spec;

/**
 * Provisions a host for SSH-key FDE login: the locked-down {@code sail} user, the shared {@code
 * /var/lib/sail} data directory, the database moved into it, and a systemd drop-in pointing {@code
 * sail-api} there. Previews by default and only mutates with {@code --apply}, because it creates a
 * system user and relocates the control-plane database. The plan touches no global sshd config and
 * never alters the operator's existing root SSH.
 */
@Command(
    name = "ssh-identity",
    description = "Enable SSH-key login for FDEs (provision the sail user + shared data dir).",
    mixinStandardHelpOptions = true)
public final class HostSshIdentityCommand implements Runnable {

  @Option(
      names = "--apply",
      description =
          "Execute the plan. Without this flag the command only previews (requires root).")
  private boolean apply;

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
          if (!apply) {
            System.out.println();
            System.out.println(
                Ansi.AUTO.string(
                    "  @|faint Preview only. Re-run as root with --apply to execute.|@"));
            return;
          }
          requireRoot();
          execute(plan);
          System.out.println();
          System.out.println(
              Ansi.AUTO.string(
                  "  @|green ✓|@ SSH-key login enabled. Register keys with"
                      + " 'sail fde key add <handle> <pubkey>' then 'sail host keys sync'."));
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

  private static void requireRoot() {
    if (!"root".equals(ProcessHandle.current().info().user().orElse(""))) {
      throw new IllegalStateException(
          "--apply must run as root: it creates a system user, relocates the database, and edits"
              + " a systemd unit. SSH to the host and run it there as root.");
    }
  }
}
