/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.commands;

import ai.singlr.sail.api.SailApiClient;
import ai.singlr.sail.api.ServerConnectionConfig;
import ai.singlr.sail.config.YamlUtil;
import ai.singlr.sail.engine.NameValidator;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import picocli.CommandLine.Command;
import picocli.CommandLine.Help.Ansi;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Spec;

@Command(name = "create", description = "Create a new spec.", mixinStandardHelpOptions = true)
public final class ApiSpecCreateCommand implements Runnable {

  @Option(names = "--id", required = true, description = "Spec ID.")
  private String id;

  @Option(names = "--title", required = true, description = "Spec title.")
  private String title;

  @Option(names = "--status", description = "Initial status.", defaultValue = "draft")
  private String status;

  @Option(names = "--assignee", description = "Assignee.")
  private String assignee;

  @Option(names = "--agent", description = "Agent override.")
  private String agent;

  @Option(names = "--branch", description = "Git branch.")
  private String branch;

  @Option(names = "--body-file", description = "Path to spec body markdown file.")
  private Path bodyFile;

  @Option(names = "--plan-file", description = "Path to plan markdown file.")
  private Path planFile;

  @Option(names = "--depends-on", description = "Comma-separated dependency spec IDs.")
  private String dependsOn;

  @Option(names = "--repos", description = "Comma-separated repo names.")
  private String repos;

  @Option(names = "--server", description = "Server URL.")
  private String server;

  @Option(names = "--token", description = "API token.")
  private String token;

  @Option(names = "--json", description = "Output in JSON format.")
  private boolean json;

  @Spec private CommandSpec commandSpec;

  @Override
  public void run() {
    CliCommand.run(commandSpec, this::execute);
  }

  private void execute() throws Exception {
    NameValidator.requireValidSpecId(id);
    var config = ServerConnectionConfig.resolve(server, token);

    var body = new LinkedHashMap<String, Object>();
    body.put("id", id);
    body.put("title", title);
    body.put("status", status);
    if (assignee != null) body.put("assignee", assignee);
    if (agent != null) body.put("agent", agent);
    if (branch != null) body.put("branch", branch);
    if (dependsOn != null) body.put("depends_on", List.of(dependsOn.split(",")));
    if (repos != null) body.put("repos", List.of(repos.split(",")));
    if (bodyFile != null) body.put("body", Files.readString(bodyFile));
    if (planFile != null) body.put("plan", Files.readString(planFile));

    try (var client = new SailApiClient(config.serverUrl(), config.token())) {
      var result = client.post("/v1/specs", body);

      if (json) {
        System.out.println(YamlUtil.dumpJson(new LinkedHashMap<>(result)));
      } else {
        System.out.println(Ansi.AUTO.string("  @|green ✓|@ Spec created: " + id));
      }
    }
  }
}
