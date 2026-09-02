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

/**
 * Removes a spec room's member. Idempotent — removing from an empty room reports that nobody was
 * there rather than erroring. A thin client of {@code DELETE /v1/rooms/{id}/members}.
 */
@Command(
    name = "disengage",
    description = "Remove this spec room's member.",
    mixinStandardHelpOptions = true)
public final class ApiSpecDisengageCommand implements Runnable {

  @Parameters(index = "0", description = "Spec ID.")
  private String specId;

  @Mixin private SyncOptions syncOptions;

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
    try (var client = new SailApiClient(config.serverUrl(), config.token(), syncOptions.noSync())) {
      var result = client.delete("/v1/rooms/" + specId + "/members");

      if (json) {
        System.out.println(YamlUtil.dumpJson(new LinkedHashMap<>(result)));
        return;
      }
      var agent = result.get("agent");
      if (agent == null) {
        System.out.println("  No member was in spec '" + specId + "'.");
        return;
      }
      System.out.println(
          Ansi.AUTO.string("  @|green ✓|@ Removed " + agent + " from spec '" + specId + "'."));
    }
  }
}
