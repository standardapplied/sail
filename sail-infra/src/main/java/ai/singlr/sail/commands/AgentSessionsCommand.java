/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.commands;

import ai.singlr.sail.api.SailApiClient;
import ai.singlr.sail.config.YamlUtil;
import ai.singlr.sail.engine.Banner;
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
    name = "sessions",
    description = "List past agent sessions for a project.",
    mixinStandardHelpOptions = true)
public final class AgentSessionsCommand implements Runnable {

  @Parameters(index = "0", description = "Project name.")
  private String project;

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
    var config = connection.resolve();
    try (var client = new SailApiClient(config.serverUrl(), config.token())) {
      var result = client.get("/v1/projects/" + project + "/agent/sessions");

      if (json) {
        System.out.println(YamlUtil.dumpJson(new LinkedHashMap<>(result)));
        return;
      }

      var sessions = (List<Map<String, Object>>) result.get("sessions");
      if (sessions == null || sessions.isEmpty()) {
        System.out.println(Ansi.AUTO.string("  @|faint No sessions found for " + project + ".|@"));
        return;
      }

      Banner.printAgentSessionsTable(sessions, project, System.out, Ansi.AUTO);
    }
  }
}
