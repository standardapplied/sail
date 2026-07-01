/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.commands;

import ai.singlr.sail.api.SailApiClient;
import ai.singlr.sail.config.YamlUtil;
import ai.singlr.sail.engine.NameValidator;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import picocli.CommandLine.Command;
import picocli.CommandLine.Help.Ansi;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Spec;

/**
 * Shows the review pipeline's state for a project's specs: every attempt's iterations, stage
 * outcomes, and findings, read from the control plane. This is the one command that answers "did my
 * review run, and if not, why" — a spec parked in {@code review} with no current-attempt reviews
 * means the pipeline never launched one, and the output says so instead of leaving the FDE to
 * forensics.
 */
@Command(
    name = "review",
    description = "Show review pipeline results for a project's specs.",
    mixinStandardHelpOptions = true)
public final class AgentReviewCommand implements Runnable {

  @Parameters(index = "0", description = "Project name.")
  private String project;

  @Option(names = "--spec", description = "Only this spec (also prints its findings).")
  private String specId;

  @Option(names = "--json", description = "Output in JSON format.")
  private boolean json;

  @Mixin private ConnectionOptions connection;

  @Spec private CommandSpec commandSpec;

  @Override
  public void run() {
    CliCommand.run(commandSpec, this::execute);
  }

  @SuppressWarnings("unchecked")
  private void execute() throws Exception {
    NameValidator.requireValidProjectName(project);
    var config = connection.resolve();
    try (var client = new SailApiClient(config.serverUrl(), config.token())) {
      var specs =
          (List<Map<String, Object>>)
              client.get("/v1/specs?project=" + project).getOrDefault("specs", List.of());
      if (specId != null) {
        specs = specs.stream().filter(s -> specId.equals(s.get("id"))).toList();
        if (specs.isEmpty()) {
          throw new IllegalArgumentException(
              "Spec '" + specId + "' not found in project '" + project + "'.");
        }
      }

      var report = new LinkedHashMap<String, Object>();
      for (var spec : specs) {
        var id = Objects.toString(spec.get("id"), "");
        var reviews =
            (List<Map<String, Object>>)
                client.get("/v1/specs/" + id + "/reviews").getOrDefault("reviews", List.of());
        if (reviews.isEmpty() && specId == null) {
          continue;
        }
        report.put(id, Map.of("status", spec.get("status"), "reviews", reviews));

        if (json) {
          continue;
        }
        for (var line : render(id, Objects.toString(spec.get("status"), ""), reviews)) {
          System.out.println(Ansi.AUTO.string(line));
        }
        if (specId != null) {
          printFindings(client, reviews);
        }
      }

      if (json) {
        System.out.println(YamlUtil.dumpJson(report));
        return;
      }
      if (report.isEmpty()) {
        System.out.println(
            Ansi.AUTO.string("  @|faint No specs with reviews in " + project + ".|@"));
      }
    }
  }

  @SuppressWarnings("unchecked")
  private void printFindings(SailApiClient client, List<Map<String, Object>> reviews)
      throws Exception {
    for (var review : reviews) {
      var findings =
          (List<Map<String, Object>>)
              client.get("/v1/reviews/" + review.get("id")).getOrDefault("findings", List.of());
      for (var finding : findings) {
        System.out.println(Ansi.AUTO.string(renderFinding(finding)));
      }
    }
  }

  /** Human rendering of one spec's review history; pure for tests. */
  static List<String> render(String specId, String specStatus, List<Map<String, Object>> reviews) {
    var lines = new ArrayList<String>();
    lines.add("  @|bold " + specId + "|@  @|faint (" + specStatus + ")|@");
    if (reviews.isEmpty()) {
      lines.add(
          "    @|yellow ⚠|@ No reviews have run for this attempt. The pipeline reviews when the"
              + " agent stops; follow it with: sail agent log <project> --review");
      return lines;
    }
    for (var review : reviews) {
      var superseded = review.get("superseded_at") != null ? " @|faint superseded|@" : "";
      lines.add(
          "    iteration "
              + review.get("iteration")
              + " — "
              + statusMarkup(Objects.toString(review.get("status"), ""))
              + superseded);
      for (var stage : stagesOf(review)) {
        var reviewer = Objects.toString(stage.get("reviewer"), "-");
        var count = Objects.toString(stage.get("finding_count"), "0");
        lines.add(
            "      "
                + stage.get("name")
                + " ["
                + reviewer
                + "] — "
                + statusMarkup(Objects.toString(stage.get("status"), ""))
                + ", "
                + count
                + " finding(s)");
      }
    }
    return lines;
  }

  /** One finding as a single scannable line; pure for tests. */
  static String renderFinding(Map<String, Object> finding) {
    return "        "
        + finding.get("severity")
        + " "
        + finding.get("category")
        + " "
        + finding.get("file")
        + ":"
        + finding.get("line_start")
        + " "
        + finding.get("title");
  }

  @SuppressWarnings("unchecked")
  private static List<Map<String, Object>> stagesOf(Map<String, Object> review) {
    return (List<Map<String, Object>>) review.getOrDefault("stages", List.of());
  }

  private static String statusMarkup(String status) {
    return switch (status) {
      case "passed" -> "@|green passed|@";
      case "failed" -> "@|red failed|@";
      case "escalated" -> "@|yellow escalated|@";
      default -> status;
    };
  }
}
