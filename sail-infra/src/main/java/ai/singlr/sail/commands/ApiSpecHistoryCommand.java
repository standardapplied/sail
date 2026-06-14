/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.commands;

import ai.singlr.sail.api.SailApiClient;
import ai.singlr.sail.config.YamlUtil;
import ai.singlr.sail.engine.NameValidator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import picocli.CommandLine.Command;
import picocli.CommandLine.Help.Ansi;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Spec;

@Command(
    name = "history",
    description = "Show a spec's revision history (every saved version).",
    mixinStandardHelpOptions = true)
public final class ApiSpecHistoryCommand implements Runnable {

  @Parameters(index = "0", description = "Spec ID.")
  private String specId;

  @Mixin private ConnectionOptions connection;

  @Option(names = "--json", description = "Output in JSON format.")
  private boolean json;

  @Spec private CommandSpec commandSpec;

  @Override
  public void run() {
    CliCommand.run(commandSpec, this::execute);
  }

  @SuppressWarnings("unchecked")
  private void execute() throws Exception {
    NameValidator.requireValidSpecId(specId);
    var config = connection.resolve();
    try (var client = new SailApiClient(config.serverUrl(), config.token())) {
      var result = client.get("/v1/specs/" + specId + "/history");

      if (json) {
        System.out.println(YamlUtil.dumpJson(new LinkedHashMap<>(result)));
        return;
      }

      var revisions = (List<Map<String, Object>>) result.getOrDefault("revisions", List.of());
      if (revisions.isEmpty()) {
        System.out.println(
            Ansi.AUTO.string("  @|faint No recorded history for spec '" + specId + "'.|@"));
        return;
      }
      System.out.println(Ansi.AUTO.string("  @|bold History:|@ " + specId));
      for (var rev : revisions) {
        var marker = Boolean.TRUE.equals(rev.get("deleted")) ? " @|red (deleted)|@" : "";
        System.out.println(
            Ansi.AUTO.string(
                "  @|cyan "
                    + rev.get("rev")
                    + "|@  "
                    + rev.get("recorded_at")
                    + "  @|faint "
                    + rev.getOrDefault("origin", "")
                    + (rev.get("actor") != null ? " by " + rev.get("actor") : "")
                    + "|@"
                    + marker));
      }
      System.out.println();
      System.out.println(
          Ansi.AUTO.string("  @|faint Restore one with: sail spec restore " + specId + " <rev>|@"));
    }
  }
}
