/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.commands;

import ai.singlr.sail.api.SailApiClient;
import ai.singlr.sail.config.YamlUtil;
import ai.singlr.sail.engine.NameValidator;
import java.util.LinkedHashMap;
import picocli.CommandLine.Command;
import picocli.CommandLine.Help.Ansi;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Spec;

@Command(name = "delete", description = "Delete a spec.", mixinStandardHelpOptions = true)
public final class ApiSpecDeleteCommand implements Runnable {

  @Parameters(index = "0", description = "Spec ID.")
  private String specId;

  @Option(names = "--force", description = "Skip confirmation.")
  private boolean force;

  @Mixin private ConnectionOptions connection;

  @Option(names = "--json", description = "Output in JSON format.")
  private boolean json;

  @Spec private CommandSpec commandSpec;

  @Override
  public void run() {
    CliCommand.run(commandSpec, this::execute);
  }

  private void execute() throws Exception {
    NameValidator.requireValidSpecId(specId);

    if (!force && !json) {
      System.out.print("  Delete spec '" + specId + "'? [y/N] ");
      var answer = System.console() != null ? System.console().readLine() : "y";
      if (answer == null || !answer.strip().equalsIgnoreCase("y")) {
        System.out.println(Ansi.AUTO.string("  @|faint Cancelled.|@"));
        return;
      }
    }

    var config = connection.resolve();
    try (var client = new SailApiClient(config.serverUrl(), config.token())) {
      var result = client.delete("/v1/specs/" + specId);

      if (json) {
        System.out.println(YamlUtil.dumpJson(new LinkedHashMap<>(result)));
      } else {
        System.out.println(Ansi.AUTO.string("  @|green ✓|@ Spec deleted: " + specId));
      }
    }
  }
}
