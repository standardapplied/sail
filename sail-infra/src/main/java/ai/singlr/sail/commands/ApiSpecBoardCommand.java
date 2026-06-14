/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.commands;

import ai.singlr.sail.api.SailApiClient;
import ai.singlr.sail.config.YamlUtil;
import java.util.LinkedHashMap;
import picocli.CommandLine.Command;
import picocli.CommandLine.Help.Ansi;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Spec;

@Command(name = "board", description = "Show spec board summary.", mixinStandardHelpOptions = true)
public final class ApiSpecBoardCommand implements Runnable {

  @Option(
      names = "--project",
      description = "Scope the board to one client project. Inferred from cwd's sail.yaml.")
  private String project;

  @Mixin private ConnectionOptions connection;

  @Option(names = "--json", description = "Output in JSON format.")
  private boolean json;

  @Spec private CommandSpec commandSpec;

  @Override
  public void run() {
    CliCommand.run(commandSpec, this::execute);
  }

  private void execute() throws Exception {
    var config = connection.resolve();
    var resolvedProject = project != null ? project : ApiSpecCreateCommand.projectFromCwd();
    var path =
        resolvedProject != null ? "/v1/specs/board?project=" + resolvedProject : "/v1/specs/board";
    try (var client = new SailApiClient(config.serverUrl(), config.token())) {
      var result = client.get(path);

      if (json) {
        System.out.println(YamlUtil.dumpJson(new LinkedHashMap<>(result)));
        return;
      }

      var scope = resolvedProject != null ? " — " + resolvedProject : "";
      System.out.println(Ansi.AUTO.string("  @|bold Spec Board" + scope + "|@"));
      System.out.println(Ansi.AUTO.string("    @|faint Draft:|@       " + result.get("draft")));
      System.out.println(Ansi.AUTO.string("    @|white Pending:|@     " + result.get("pending")));
      System.out.println(
          Ansi.AUTO.string("    @|blue In Progress:|@ " + result.get("in_progress")));
      System.out.println(Ansi.AUTO.string("    @|yellow Review:|@      " + result.get("review")));
      System.out.println(Ansi.AUTO.string("    @|green Done:|@        " + result.get("done")));

      var nextReady = result.get("next_ready_id");
      if (nextReady != null) {
        System.out.println();
        System.out.println(Ansi.AUTO.string("  @|bold Next ready:|@ " + nextReady));
      }
    }
  }
}
