/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.commands;

import ai.singlr.sail.api.OperationsFactory;
import ai.singlr.sail.engine.SailPaths;
import java.nio.file.Files;
import picocli.CommandLine.Command;
import picocli.CommandLine.Help.Ansi;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Spec;

@Command(name = "status", description = "Show Sail server status.", mixinStandardHelpOptions = true)
public final class ServerStatusCommand implements Runnable {

  @Spec private CommandSpec spec;

  @Override
  public void run() {
    CliCommand.run(spec, this::execute);
  }

  private void execute() {
    var dbPath = SailPaths.controlPlaneDb();
    if (!Files.exists(dbPath)) {
      System.out.println(
          Ansi.AUTO.string("  @|yellow ⚠|@ Server not initialized. Run 'sail server init'."));
      return;
    }

    try (var operations = OperationsFactory.open(dbPath)) {
      var tokens = operations.identity().tokens();

      System.out.println(Ansi.AUTO.string("  @|bold Sail Server|@"));
      System.out.println("    Database:       " + dbPath);
      System.out.println("    Schema version: " + operations.schema().version());
      System.out.println("    API tokens:     " + tokens.size());
    }
  }
}
