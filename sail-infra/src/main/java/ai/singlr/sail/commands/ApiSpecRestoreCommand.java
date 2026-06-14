/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.commands;

import ai.singlr.sail.api.SailApiClient;
import ai.singlr.sail.config.YamlUtil;
import ai.singlr.sail.engine.NameValidator;
import java.util.LinkedHashMap;
import java.util.Map;
import picocli.CommandLine.Command;
import picocli.CommandLine.Help.Ansi;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Spec;

@Command(
    name = "restore",
    description = "Restore a spec to a prior revision (recorded as a new version — reversible).",
    mixinStandardHelpOptions = true)
public final class ApiSpecRestoreCommand implements Runnable {

  @Parameters(index = "0", description = "Spec ID.")
  private String specId;

  @Parameters(index = "1", description = "Revision to restore (from 'sail spec history').")
  private String rev;

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
    var config = connection.resolve();
    try (var client = new SailApiClient(config.serverUrl(), config.token())) {
      var result = client.post("/v1/specs/" + specId + "/restore", Map.of("rev", rev));

      if (json) {
        System.out.println(YamlUtil.dumpJson(new LinkedHashMap<>(result)));
        return;
      }
      System.out.println(
          Ansi.AUTO.string(
              "  @|green ✓|@ Restored spec '" + specId + "' from revision " + rev + "."));
    }
  }
}
