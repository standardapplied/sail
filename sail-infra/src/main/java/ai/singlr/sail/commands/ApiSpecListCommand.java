/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.commands;

import ai.singlr.sail.api.SailApiClient;
import ai.singlr.sail.api.ServerConnectionConfig;
import ai.singlr.sail.config.YamlUtil;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import picocli.CommandLine.Command;
import picocli.CommandLine.Help.Ansi;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Spec;

@Command(
    name = "list",
    description = "List specs from the Sail server.",
    mixinStandardHelpOptions = true)
public final class ApiSpecListCommand implements Runnable {

  @Option(names = "--status", description = "Filter by status (comma-separated).")
  private String status;

  @Option(names = "--assignee", description = "Filter by assignee.")
  private String assignee;

  @Option(names = "--repo", description = "Filter by repo.")
  private String repo;

  @Option(
      names = {"-q", "--query"},
      description = "Search by ID or title.")
  private String query;

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

  @SuppressWarnings("unchecked")
  private void execute() throws Exception {
    var config = ServerConnectionConfig.resolve(server, token);
    try (var client = new SailApiClient(config.serverUrl(), config.token())) {
      var params = new StringBuilder("/v1/specs?");
      if (status != null) params.append("status=").append(status).append("&");
      if (assignee != null) params.append("assignee=").append(assignee).append("&");
      if (repo != null) params.append("repo=").append(repo).append("&");
      if (query != null) params.append("q=").append(query).append("&");
      var path = params.toString();
      if (path.endsWith("&") || path.endsWith("?")) {
        path = path.substring(0, path.length() - 1);
      }

      var result = client.get(path);
      if (json) {
        System.out.println(YamlUtil.dumpJson(new LinkedHashMap<>(result)));
        return;
      }

      var specs = (List<Map<String, Object>>) result.get("specs");
      if (specs == null || specs.isEmpty()) {
        System.out.println(Ansi.AUTO.string("  @|faint No specs found.|@"));
        return;
      }

      var statusOrder = List.of("draft", "pending", "in_progress", "review", "done");
      for (var statusName : statusOrder) {
        var matching = specs.stream().filter(s -> statusName.equals(s.get("status"))).toList();
        if (matching.isEmpty()) continue;

        System.out.println(
            Ansi.AUTO.string(
                "  @|bold,"
                    + statusColor(statusName)
                    + " "
                    + formatStatusLabel(statusName)
                    + " ("
                    + matching.size()
                    + ")|@"));
        for (var spec : matching) {
          var id = (String) spec.get("id");
          var title = (String) spec.get("title");
          var deps = (List<String>) spec.getOrDefault("depends_on", List.of());
          var repos = (List<String>) spec.getOrDefault("repos", List.of());
          var depsStr = deps.isEmpty() ? "" : " @|faint ← " + String.join(", ", deps) + "|@";
          var reposStr = repos.isEmpty() ? "" : " @|faint [" + String.join(", ", repos) + "]|@";
          System.out.println(
              Ansi.AUTO.string("  • @|bold " + id + "|@  " + title + reposStr + depsStr));
        }
        System.out.println();
      }
    }
  }

  private static String formatStatusLabel(String status) {
    return switch (status) {
      case "draft" -> "Draft";
      case "pending" -> "Pending";
      case "in_progress" -> "In Progress";
      case "review" -> "Review";
      case "done" -> "Done";
      default -> status;
    };
  }

  private static String statusColor(String status) {
    return switch (status) {
      case "draft" -> "faint";
      case "pending" -> "white";
      case "in_progress" -> "blue";
      case "review" -> "yellow";
      case "done" -> "green";
      default -> "white";
    };
  }
}
