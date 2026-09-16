/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.commands;

import ai.singlr.sail.api.OperationsFactory;
import ai.singlr.sail.engine.AuthorizedKeysRenderer;
import ai.singlr.sail.engine.AuthorizedKeysSync;
import ai.singlr.sail.engine.SailPaths;
import picocli.CommandLine.Command;
import picocli.CommandLine.Help.Ansi;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Spec;

/**
 * Repairs the {@code sail} user's {@code authorized_keys} from the SSH-key registry. Key mutations
 * ({@code sail fde add --key}, {@code sail fde key add/rm}) sync automatically; this command exists
 * to converge the file by hand when a mutation could not (it ran without root) or when the file was
 * edited out from under sail.
 */
@Command(
    name = "keys",
    description = "Manage the sail user's SSH authorized_keys.",
    mixinStandardHelpOptions = true,
    subcommands = {HostKeysCommand.Sync.class})
public final class HostKeysCommand implements Runnable {

  @Override
  public void run() {
    new picocli.CommandLine(this).usage(System.out);
  }

  @Command(
      name = "sync",
      description =
          "Repair the sail user's authorized_keys from the registry (key changes sync"
              + " automatically; this is only needed when a sync was skipped or the file was"
              + " edited by hand).",
      mixinStandardHelpOptions = true)
  static final class Sync implements Runnable {

    @Option(names = "--dry-run", description = "Print the authorized_keys instead of writing it.")
    private boolean dryRun;

    @Spec private CommandSpec spec;

    @Override
    public void run() {
      CliCommand.run(
          spec,
          () -> {
            try (var operations = OperationsFactory.open()) {
              var sync = new AuthorizedKeysSync();
              if (dryRun) {
                System.out.print(
                    AuthorizedKeysRenderer.render(
                        operations.identity().sshKeys(), SailPaths.binaryPath().toString()));
                return;
              }
              switch (sync.sync(operations.identity()::sshKeys)) {
                case AuthorizedKeysSync.Synced synced ->
                    System.out.println(
                        Ansi.AUTO.string(
                            "  @|green ✓|@ Synced "
                                + synced.keyCount()
                                + " key(s) to "
                                + synced.destination()));
                case AuthorizedKeysSync.NeedsRoot _ ->
                    throw new IllegalStateException(
                        "Writing the sail user's authorized_keys requires root. Run it on the host"
                            + " as root, or preview with --dry-run.");
                case AuthorizedKeysSync.NotProvisioned _ ->
                    throw new IllegalStateException(
                        "The sail user is not provisioned. Run 'sudo sail host ssh-identity'"
                            + " first.");
              }
            }
          });
    }
  }
}
