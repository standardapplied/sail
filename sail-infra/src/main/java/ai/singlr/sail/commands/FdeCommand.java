/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.commands;

import ai.singlr.sail.engine.SailPaths;
import ai.singlr.sail.store.FdeStore;
import ai.singlr.sail.store.Sqlite;
import picocli.CommandLine.Command;
import picocli.CommandLine.Help.Ansi;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Spec;

/**
 * Manages Forward Deployed Engineers — the human principals that own API tokens and are attributed
 * on the specs they act on. Runs against the control-plane database on the host.
 */
@Command(
    name = "fde",
    description = "Manage Forward Deployed Engineers (FDEs).",
    mixinStandardHelpOptions = true,
    subcommands = {FdeCommand.Add.class, FdeCommand.ListFdes.class})
public final class FdeCommand implements Runnable {

  @Override
  public void run() {
    new picocli.CommandLine(this).usage(System.out);
  }

  private static java.nio.file.Path dbPath() {
    return SailPaths.sailDir().resolve("sail.db");
  }

  @Command(name = "add", description = "Add an FDE.", mixinStandardHelpOptions = true)
  static final class Add implements Runnable {

    @Parameters(index = "0", description = "Unique handle (e.g. uday).")
    private String handle;

    @Option(names = "--name", description = "Display name.")
    private String displayName;

    @Option(names = "--email", description = "Email address.")
    private String email;

    @Spec private CommandSpec spec;

    @Override
    public void run() {
      CliCommand.run(
          spec,
          () -> {
            try (var db = Sqlite.open(dbPath())) {
              var fde = new FdeStore(db).add(handle, displayName, email);
              System.out.println(Ansi.AUTO.string("  @|green ✓|@ FDE added: " + fde.handle()));
            }
          });
    }
  }

  @Command(name = "list", description = "List FDEs.", mixinStandardHelpOptions = true)
  static final class ListFdes implements Runnable {

    @Spec private CommandSpec spec;

    @Override
    public void run() {
      CliCommand.run(
          spec,
          () -> {
            try (var db = Sqlite.open(dbPath())) {
              var fdes = new FdeStore(db).list();
              if (fdes.isEmpty()) {
                System.out.println("  No FDEs. Add one with 'sail fde add <handle>'.");
                return;
              }
              for (var fde : fdes) {
                System.out.printf(
                    "  %-16s  %-20s  %s%n",
                    fde.handle(), fde.displayName() == null ? "" : fde.displayName(), fde.status());
              }
            }
          });
    }
  }
}
