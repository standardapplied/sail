/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.commands;

import ai.singlr.sail.api.SailApiClient;
import ai.singlr.sail.config.YamlUtil;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import picocli.CommandLine.Command;
import picocli.CommandLine.Help.Ansi;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Spec;

@Command(
    name = "list",
    description = "List specs from the Sail server.",
    mixinStandardHelpOptions = true)
public final class ApiSpecListCommand implements Runnable {

  static final String ACTIVE_STATUSES = "draft,pending,in_progress,review,awaiting_merge";

  @Option(
      names = {"-p", "--project"},
      description =
          "Filter by client project ('*' = all projects). Defaults to the current project,"
              + " inferred from cwd's sail.yaml or 'sail project switch'.")
  private String project;

  @Option(names = "--all-projects", description = "List specs across all projects.")
  private boolean allProjects;

  @Option(
      names = "--status",
      description =
          "Filter by status (comma-separated). Defaults to the active statuses: "
              + ACTIVE_STATUSES
              + ".")
  private String status;

  @Option(names = "--all", description = "Include every status (done and archived too).")
  private boolean allStatuses;

  @Option(names = "--assignee", description = "Filter by assignee ('me' = your own FDE).")
  private String assignee;

  @Option(names = "--repo", description = "Filter by repo.")
  private String repo;

  @Option(
      names = {"-q", "--query"},
      description = "Search by ID or title.")
  private String query;

  @Mixin private SyncOptions syncOptions;

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
    var resolvedProject = CurrentProject.scope(project, allProjects).orElse(null);
    var resolvedStatus = statusFilter(status, allStatuses);
    try (var client = new SailApiClient(config.serverUrl(), config.token(), syncOptions.noSync())) {
      var params = new StringBuilder("/v1/specs?");
      if (resolvedProject != null) params.append("project=").append(resolvedProject).append("&");
      if (resolvedStatus != null) params.append("status=").append(resolvedStatus).append("&");
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
        var scope = resolvedProject != null ? " for project '" + resolvedProject + "'" : "";
        var hint =
            ACTIVE_STATUSES.equals(resolvedStatus)
                ? " (active statuses only; pass --all to include done and archived)"
                : "";
        System.out.println(Ansi.AUTO.string("  @|faint No specs found" + scope + hint + ".|@"));
        return;
      }

      printGroupedByProjectAndStatus(specs);
    }
  }

  /**
   * The status filter {@code list} sends to the server: an explicit {@code --status} verbatim,
   * every status under {@code --all}, and the active set — which by definition includes {@code
   * awaiting_merge}, the human merge queue — by default.
   */
  static String statusFilter(String status, boolean allStatuses) {
    if (allStatuses) {
      if (status != null) {
        throw new IllegalArgumentException(
            "--all lists every status; pass either --all or --status, not both.");
      }
      return null;
    }
    return status != null ? status : ACTIVE_STATUSES;
  }

  @SuppressWarnings("unchecked")
  static void printGroupedByProjectAndStatus(List<Map<String, Object>> specs) {
    var statusOrder =
        List.of("draft", "pending", "in_progress", "review", "awaiting_merge", "done", "archived");
    var byProject = new LinkedHashMap<String, List<Map<String, Object>>>();
    for (var spec : specs) {
      var proj = (String) spec.getOrDefault("project", "unassigned");
      byProject.computeIfAbsent(proj, k -> new ArrayList<>()).add(spec);
    }
    for (var projectGroup : byProject.entrySet()) {
      System.out.println(Ansi.AUTO.string("  @|bold,cyan ▸ " + projectGroup.getKey() + "|@"));
      for (var statusName : statusOrder) {
        var matching =
            projectGroup.getValue().stream()
                .filter(s -> statusName.equals(s.get("status")))
                .toList();
        if (matching.isEmpty()) continue;
        System.out.println(
            Ansi.AUTO.string(
                "    @|bold,"
                    + statusColor(statusName)
                    + " "
                    + formatStatusLabel(statusName)
                    + " ("
                    + matching.size()
                    + ")|@"));
        for (var spec : matching) {
          var id = (String) spec.get("id");
          var title = (String) spec.get("title");
          var assignee = (String) spec.get("assignee");
          var deps = (List<String>) spec.getOrDefault("depends_on", List.of());
          var repos = (List<String>) spec.getOrDefault("repos", List.of());
          var assigneeStr =
              assignee == null || assignee.isBlank()
                  ? " @|faint (unassigned)|@"
                  : " @|magenta @" + assignee + "|@";
          var depsStr = deps.isEmpty() ? "" : " @|faint ← " + String.join(", ", deps) + "|@";
          var reposStr = repos.isEmpty() ? "" : " @|faint [" + String.join(", ", repos) + "]|@";
          System.out.println(
              Ansi.AUTO.string(
                  "    • @|bold " + id + "|@  " + title + assigneeStr + reposStr + depsStr));
        }
      }
      System.out.println();
    }
  }

  private static String formatStatusLabel(String status) {
    return switch (status) {
      case "draft" -> "Draft";
      case "pending" -> "Pending";
      case "in_progress" -> "In Progress";
      case "review" -> "Review";
      case "awaiting_merge" -> "Awaiting Merge";
      case "done" -> "Done";
      case "cancelled" -> "Cancelled";
      case "archived" -> "Archived";
      default -> status;
    };
  }

  private static String statusColor(String status) {
    return switch (status) {
      case "draft" -> "faint";
      case "pending" -> "white";
      case "in_progress" -> "blue";
      case "review" -> "yellow";
      case "awaiting_merge" -> "magenta";
      case "done" -> "green";
      case "cancelled" -> "red";
      case "archived" -> "faint";
      default -> "white";
    };
  }
}
