/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.commands;

import ai.singlr.sail.api.OperationsFactory;
import ai.singlr.sail.engine.AuthorizedKeysSync;
import ai.singlr.sail.engine.OperatorCommand;
import ai.singlr.sail.store.FdeSshKeyStore;
import java.util.List;
import picocli.CommandLine;
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
    CommandLine.usage(this, System.out);
  }

  @Command(
      name = "sync",
      description =
          "Repair the sail user's authorized_keys from the registry (key changes sync"
              + " automatically; this is only needed when a sync was skipped or the file was"
              + " edited by hand).",
      mixinStandardHelpOptions = true)
  static final class Sync implements Runnable {

    @Spec private CommandSpec spec;

    @Option(names = "--dry-run", description = "Print the authorized_keys instead of writing it.")
    private boolean dryRun;

    @Override
    public void run() {
      CliCommand.run(
          () -> {
            var operator = OperatorCommand.here();
            var sync = new AuthorizedKeysSync();
            if (dryRun) {
              System.out.print(sync.render(registered()));
              return;
            }
            switch (sync.sync(Sync::registered)) {
              case AuthorizedKeysSync.Synced synced ->
                  System.out.println(
                      Ansi.AUTO.string(
                          "  @|green ✓|@ Synced "
                              + synced.keyCount()
                              + " key(s) to "
                              + synced.destination()));
              case AuthorizedKeysSync.NeedsRoot _ ->
                  throw RootRequired.refusal(
                      "Writing the sail user's authorized_keys requires root (preview it with"
                          + " --dry-run).",
                      spec);
              case AuthorizedKeysSync.NotProvisioned _ ->
                  throw new IllegalStateException(notProvisioned(operator));
            }
          });
    }

    /**
     * The keys the registry admits, the database opened only now: a caller who is not root is told
     * so before the database is touched.
     */
    private static List<FdeSshKeyStore.SshKeyInfo> registered() {
      try (var operations = OperationsFactory.open()) {
        return operations.identity().sshKeys();
      }
    }

    static String notProvisioned(OperatorCommand operator) {
      return "The sail user is not provisioned. Run '"
          + operator.sail("host ssh-identity")
          + "' first.";
    }
  }
}
