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
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

@Command(name = "delete", description = "Delete a spec.", mixinStandardHelpOptions = true)
public final class ApiSpecDeleteCommand implements Runnable {

  @Parameters(index = "0", description = "Spec ID.")
  private String specId;

  @Option(names = "--force", description = "Skip confirmation.")
  private boolean force;

  @Mixin private SyncOptions syncOptions;

  @Mixin private ConnectionOptions connection;

  @Option(names = "--json", description = "Output in JSON format.")
  private boolean json;

  @Override
  public void run() {
    CliCommand.run(this::execute);
  }

  private void execute() throws Exception {
    NameValidator.requireValidSpecId(specId);

    if (json && !force) {
      throw new IllegalArgumentException(
          "--json requires --force (nobody answers a prompt through JSON).");
    }
    if (!force
        && !ConsoleHelper.confirmDestructive(
            "Delete spec '" + specId + "'?", "Not deleted", "--force")) {
      System.out.println(Ansi.AUTO.string("  @|faint Cancelled.|@"));
      return;
    }

    var config = connection.resolve();
    try (var client = new SailApiClient(config.serverUrl(), config.token(), syncOptions.noSync())) {
      var result = client.delete("/v1/specs/" + specId);

      if (json) {
        System.out.println(YamlUtil.dumpJson(new LinkedHashMap<>(result)));
      } else {
        System.out.println(Ansi.AUTO.string("  @|green ✓|@ Spec deleted: " + specId));
      }
    }
  }
}
