/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.commands;

import ai.singlr.sail.api.SailApiClient;
import ai.singlr.sail.config.YamlUtil;
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

      System.out.println(Ansi.AUTO.string("  @|bold Agent Sessions:|@ " + project));
      System.out.println();
      for (var session : sessions) {
        var status = (String) session.get("status");
        var agent = (String) session.get("agent");
        var specId = session.get("spec_id");
        var startedAt = (String) session.get("started_at");
        var color = "running".equals(status) ? "green" : "faint";
        System.out.println(
            Ansi.AUTO.string(
                "  @|"
                    + color
                    + " "
                    + status
                    + "|@  "
                    + agent
                    + (specId != null ? "  spec=" + specId : "")
                    + "  "
                    + startedAt));
      }
    }
  }
}
