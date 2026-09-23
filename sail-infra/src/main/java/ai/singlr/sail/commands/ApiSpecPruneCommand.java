/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.commands;

import ai.singlr.sail.api.SailApiClient;
import ai.singlr.sail.common.DateTimeUtils;
import ai.singlr.sail.config.YamlUtil;
import ai.singlr.sail.engine.NameValidator;
import java.time.Duration;
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

/**
 * {@code sail spec prune}: erase specs everywhere, for good, with their rooms, runs, reviews,
 * events, history and content. Always a dry run first — the report is printed before anything is
 * erased — and nothing is erased without {@code --apply}.
 */
@Command(
    name = "prune",
    description =
        "Erase specs everywhere with their rooms, messages, runs, reviews, events and history."
            + " Irreversible. Reports first; --apply erases.",
    mixinStandardHelpOptions = true)
public final class ApiSpecPruneCommand implements Runnable {

  static final String ROUTE = "/v1/specs:prune";

  @Parameters(arity = "0..*", paramLabel = "ID", description = "Specs to prune, by id.")
  private List<String> ids = List.of();

  @Option(
      names = "--status",
      split = ",",
      paramLabel = "STATUS",
      description = "Prune by policy: specs in these statuses (archived, cancelled). Admin only.")
  private List<String> statuses = List.of();

  @Option(
      names = "--older-than",
      paramLabel = "AGE",
      description = "With --status: in that status for longer than this many days, e.g. 90d.")
  private String olderThan;

  @Option(
      names = {"-p", "--project"},
      description = "With --status: only specs of this project.")
  private String project;

  @Option(names = "--apply", description = "Erase what the report lists. Without it, only report.")
  private boolean apply;

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
    var body = body();
    var config = connection.resolve();
    try (var client = new SailApiClient(config.serverUrl(), config.token(), syncOptions.noSync())) {
      var rehearsal = client.post(ROUTE, withDryRun(body, true));
      if (!apply) {
        print(rehearsal);
        return;
      }
      if (!json) {
        print(rehearsal);
      }
      print(client.post(ROUTE, withDryRun(body, false)));
    }
  }

  private Map<String, Object> body() {
    if (!ids.isEmpty() && !statuses.isEmpty()) {
      throw new IllegalArgumentException(
          "Name specs by id or choose them with --status, not both.");
    }
    if (ids.isEmpty() && statuses.isEmpty()) {
      throw new IllegalArgumentException(
          "Name the specs to prune, or choose them: --status archived,cancelled --older-than 90d.");
    }
    var body = new LinkedHashMap<String, Object>();
    if (!ids.isEmpty()) {
      if (olderThan != null || project != null) {
        throw new IllegalArgumentException("--older-than and --project go with --status.");
      }
      ids.forEach(NameValidator::requireValidSpecId);
      body.put("ids", ids);
      return body;
    }
    if (olderThan == null) {
      throw new IllegalArgumentException("--status needs --older-than, e.g. --older-than 90d.");
    }
    var policy = new LinkedHashMap<String, Object>();
    policy.put("statuses", statuses.stream().map(String::strip).toList());
    policy.put("older_than_days", days(olderThan));
    if (project != null) {
      NameValidator.requireValidProjectName(project);
      policy.put("project", project);
    }
    body.put("policy", policy);
    return body;
  }

  private static long days(String age) {
    var duration = DateTimeUtils.parseAge(age);
    if (!duration.equals(Duration.ofDays(duration.toDays()))) {
      throw new IllegalArgumentException(
          "--older-than counts whole days, e.g. 90d; '" + age + "' is not a number of days.");
    }
    return duration.toDays();
  }

  private static Map<String, Object> withDryRun(Map<String, Object> body, boolean dryRun) {
    var request = new LinkedHashMap<>(body);
    request.put("dry_run", dryRun);
    return request;
  }

  private void print(Map<String, Object> report) {
    if (json) {
      System.out.println(YamlUtil.dumpJson(new LinkedHashMap<>(report)));
      return;
    }
    System.out.println(render(report));
  }

  /** The report as the terminal shows it: what goes, then what happened or what to do next. */
  static String render(Map<String, Object> report) {
    var counts =
        List.of("specs", "rooms", "messages", "runs", "reviews", "files", "projects", "events")
            .stream()
            .map(key -> number(report, key) + " " + key)
            .toList();
    var summary =
        String.join(", ", counts) + ", " + number(report, "blob_bytes") + " bytes of content";
    if (Boolean.TRUE.equals(report.get("dry_run"))) {
      return Ansi.AUTO.string(
          """
            @|bold Would erase|@ %s.
            @|faint Nothing is erased yet; re-run with --apply to erase it everywhere.|@"""
              .formatted(summary));
    }
    if (Boolean.TRUE.equals(report.get("requested"))) {
      return Ansi.AUTO.string(
          """
            @|green ✓|@ Asked main to erase %s.
            @|faint Main erases it on this box's next sync, and every box follows.|@"""
              .formatted(summary));
    }
    return Ansi.AUTO.string(
        """
          @|green ✓|@ Erased %s.
          @|faint Other boxes erase it on their next sync.|@"""
            .formatted(summary));
  }

  private static long number(Map<String, Object> report, String key) {
    return report.get(key) instanceof Number n ? n.longValue() : 0;
  }
}
