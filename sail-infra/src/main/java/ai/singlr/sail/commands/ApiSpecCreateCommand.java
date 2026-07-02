/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.commands;

import ai.singlr.sail.api.SailApiClient;
import ai.singlr.sail.common.Strings;
import ai.singlr.sail.config.YamlUtil;
import ai.singlr.sail.engine.NameValidator;
import ai.singlr.sail.engine.SailPaths;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import picocli.CommandLine.Command;
import picocli.CommandLine.Help.Ansi;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Spec;

@Command(name = "create", description = "Create a new spec.", mixinStandardHelpOptions = true)
public final class ApiSpecCreateCommand implements Runnable {

  @Option(names = "--id", description = "Spec ID. Required unless --from-review is used.")
  private String id;

  @Option(names = "--title", description = "Spec title. Required unless --from-review is used.")
  private String title;

  @Option(
      names = "--from-review",
      paramLabel = "<spec-id>",
      description =
          "Draft a follow-up spec from the open findings of the given spec's latest review."
              + " Title, body, priority, project, and repos are derived; the draft stays in"
              + " 'draft' status until you promote it.")
  private String fromReview;

  @Option(
      names = "--project",
      description =
          "Client project this spec belongs to. Inferred from sail.yaml in the current"
              + " directory tree when omitted.")
  private String project;

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
    SpecSync.freshenIfNode(syncOptions.noSync());
    if (fromReview != null) {
      executeFromReview();
      return;
    }
    if (id == null || title == null) {
      throw new IllegalArgumentException(
          "--id and --title are required. To draft a follow-up spec from review findings"
              + " instead, use --from-review <spec-id>.");
    }
    NameValidator.requireValidSpecId(id);
    var config = connection.resolve();
    var resolvedProject = project != null ? project : projectFromCwd();
    if (resolvedProject == null) {
      throw new IllegalStateException(
          "--project is required: no sail.yaml found in the current directory tree.");
    }

    var body = new LinkedHashMap<String, Object>();
    body.put("id", id);
    body.put("project", resolvedProject);
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
        System.out.println(
            Ansi.AUTO.string(
                "  @|green ✓|@ Spec created: " + id + " @|faint (" + resolvedProject + ")|@"));
      }
    }
  }

  @SuppressWarnings("unchecked")
  private void executeFromReview() throws Exception {
    NameValidator.requireValidSpecId(fromReview);
    requireNoDerivedOptions();
    var config = connection.resolve();
    var body = new LinkedHashMap<String, Object>();
    if (id != null) {
      NameValidator.requireValidSpecId(id);
      body.put("id", id);
    }
    try (var client = new SailApiClient(config.serverUrl(), config.token())) {
      var result = client.post("/v1/specs/" + fromReview + "/followup", body);

      if (json) {
        System.out.println(YamlUtil.dumpJson(new LinkedHashMap<>(result)));
        return;
      }
      var spec = (Map<String, Object>) result.get("spec");
      System.out.println(
          Ansi.AUTO.string(
              "  @|green ✓|@ Follow-up spec drafted: "
                  + spec.get("id")
                  + " @|faint ("
                  + result.get("finding_count")
                  + " open findings from the latest review of "
                  + fromReview
                  + ")|@"));
      System.out.println(
          Ansi.AUTO.string(
              "  @|faint Review and edit it, then promote: sail spec edit "
                  + spec.get("id")
                  + " --status pending|@"));
    }
  }

  private void requireNoDerivedOptions() {
    var derived =
        title != null
            || bodyFile != null
            || planFile != null
            || assignee != null
            || agent != null
            || branch != null
            || dependsOn != null
            || repos != null
            || !"draft".equals(status);
    if (derived) {
      throw new IllegalArgumentException(
          "--from-review derives title, body, priority, project, and repos from the review;"
              + " only --id may be combined with it.");
    }
  }

  static String projectFromCwd() {
    var yaml = SailPaths.findSailYamlUpward(Path.of(".")).orElse(null);
    if (yaml == null) {
      return null;
    }
    try {
      var name = (String) YamlUtil.parseFile(yaml).get("name");
      return Strings.isBlank(name) ? null : name;
    } catch (Exception e) {
      return null;
    }
  }
}
