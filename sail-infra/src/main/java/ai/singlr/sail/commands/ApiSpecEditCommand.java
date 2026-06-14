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
import picocli.CommandLine.Command;
import picocli.CommandLine.Help.Ansi;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Spec;

@Command(name = "edit", description = "Update a spec's metadata.", mixinStandardHelpOptions = true)
public final class ApiSpecEditCommand implements Runnable {

  @Parameters(index = "0", description = "Spec ID.")
  private String specId;

  @Option(names = "--project", description = "Move the spec to a different client project.")
  private String project;

  @Option(names = "--title", description = "New title.")
  private String title;

  @Option(names = "--status", description = "New status.")
  private String status;

  @Option(names = "--assignee", description = "New assignee.")
  private String assignee;

  @Option(names = "--agent", description = "Agent override.")
  private String agent;

  @Option(names = "--branch", description = "Git branch.")
  private String branch;

  @Option(names = "--priority", description = "Priority (higher = first).")
  private Integer priority;

  @Option(names = "--depends-on", description = "Comma-separated dependency spec IDs.")
  private String dependsOn;

  @Option(names = "--repos", description = "Comma-separated repo names.")
  private String repos;

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

    var body = new LinkedHashMap<String, Object>();
    if (project != null) body.put("project", project);
    if (title != null) body.put("title", title);
    if (status != null) body.put("status", status);
    if (assignee != null) body.put("assignee", assignee);
    if (agent != null) body.put("agent", agent);
    if (branch != null) body.put("branch", branch);
    if (priority != null) body.put("priority", priority);
    if (dependsOn != null) body.put("depends_on", List.of(dependsOn.split(",")));
    if (repos != null) body.put("repos", List.of(repos.split(",")));

    if (body.isEmpty()) {
      System.out.println(
          Ansi.AUTO.string("  @|yellow ⚠|@ Nothing to update. Use --title, --status, etc."));
      return;
    }

    try (var client = new SailApiClient(config.serverUrl(), config.token())) {
      var result = client.put("/v1/specs/" + specId, body);

      if (json) {
        System.out.println(YamlUtil.dumpJson(new LinkedHashMap<>(result)));
      } else {
        System.out.println(Ansi.AUTO.string("  @|green ✓|@ Spec updated: " + specId));
      }
    }
  }
}
