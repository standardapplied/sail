/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.commands;

import ai.singlr.sail.api.OperationsFactory;
import ai.singlr.sail.engine.OperatorCommand;
import ai.singlr.sail.engine.SailPaths;
import java.nio.file.Files;
import picocli.CommandLine.Command;
import picocli.CommandLine.Help.Ansi;

@Command(name = "status", description = "Show Sail server status.", mixinStandardHelpOptions = true)
public final class ServerStatusCommand implements Runnable {

  @Override
  public void run() {
    CliCommand.run(this::execute);
  }

  private void execute() {
    var operator = OperatorCommand.here();
    var dbPath = SailPaths.controlPlaneDb();
    if (!Files.exists(dbPath)) {
      System.out.println(
          Ansi.AUTO.string(
              "  @|yellow ⚠|@ Server not initialized. Run '"
                  + operator.sail("server init")
                  + "'."));
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
