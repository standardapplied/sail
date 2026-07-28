/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.commands;

import ai.singlr.sail.common.Strings;
import ai.singlr.sail.config.YamlUtil;
import ai.singlr.sail.engine.AgentLogRenderer;
import ai.singlr.sail.engine.AgentUnit;
import ai.singlr.sail.engine.ContainerExec;
import ai.singlr.sail.engine.ContainerManager;
import ai.singlr.sail.engine.ContainerStateGuard;
import ai.singlr.sail.engine.NameValidator;
import ai.singlr.sail.engine.NodeIdentity;
import ai.singlr.sail.engine.SailPaths;
import ai.singlr.sail.engine.ShellExecutor;
import ai.singlr.sail.store.ReviewStore;
import ai.singlr.sail.store.RunStore;
import ai.singlr.sail.store.Sqlite;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import picocli.CommandLine.Command;
import picocli.CommandLine.Help.Ansi;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Spec;

@Command(
    name = "logs",
    aliases = {"log"},
    description = "View agent session output.",
    mixinStandardHelpOptions = true)
public final class AgentLogCommand implements Runnable {

  @Parameters(
      index = "0",
      arity = "0..1",
      description = "Project name (default: the current project).")
  private String name;

  @Option(
      names = {"-f", "--follow"},
      description = "Follow log output.")
  private boolean follow;

  @Option(names = "--tail", description = "Number of lines to show.", defaultValue = "50")
  private int tail;

  @Option(
      names = "--review",
      description = "Show the review/fix negotiation log instead of the build log.")
  private boolean review;

  @Option(names = "--json", description = "Output in JSON format.")
  private boolean json;

  @Spec private CommandSpec spec;

  @Override
  public void run() {
    CliCommand.run(spec, this::execute);
  }

  private void execute() throws Exception {
    name = CurrentProject.require(name);
    NameValidator.requireValidProjectName(name);
    var shell = new ShellExecutor(false);
    var mgr = new ContainerManager(shell);

    var state = mgr.queryState(name);
    ContainerStateGuard.requireRunning(state, name);

    var logPath = resolveLogPath(name, review);
    if (logPath == null) {
      printNoLog();
      return;
    }

    if (follow) {
      var tailCmd = ContainerExec.asDevUser(name, List.of("tail", "-f", logPath));
      var pb = new ProcessBuilder(tailCmd);
      pb.redirectErrorStream(true);
      var process = pb.start();
      try (var reader =
          new BufferedReader(
              new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
        String line;
        while ((line = reader.readLine()) != null) {
          var out = renderForLog(line, json);
          if (!out.isEmpty()) {
            System.out.println(out);
          }
        }
        process.waitFor();
      } finally {
        process.destroyForcibly();
      }
    } else {
      var cmd = ContainerExec.asDevUser(name, List.of("tail", "-n", String.valueOf(tail), logPath));
      var result = shell.exec(cmd);
      if (!result.ok()) {
        if (result.stderr().contains("No such file")) {
          printNoLog();
          return;
        }
        throw new IOException("Failed to read agent log: " + result.stderr());
      }

      if (json) {
        var lines = Arrays.stream(result.stdout().split("\n")).filter(l -> !l.isEmpty()).toList();
        var map = new LinkedHashMap<String, Object>();
        map.put("name", name);
        map.put("lines", lines);
        System.out.println(YamlUtil.dumpJson(map));
        return;
      }

      System.out.print(renderLines(result.stdout()));
    }
  }

  /**
   * The log file to tail, or null when the project has no session to show. With {@code --review},
   * the latest review's own negotiation log ({@code ~/.sail/runs/<reviewId>/review.log}) — each
   * review owns its files so concurrent pipelines never interleave — resolved through the latest
   * <em>build</em> run, since only a spec's dispatch has reviews. Otherwise the latest session
   * run's own run-scoped log ({@code ~/.sail/runs/<id>/agent.log}), dispatched and ad-hoc alike:
   * every agent session is a run, so no run row means no log.
   */
  private String resolveLogPath(String project, boolean review) {
    try (var db = Sqlite.open(SailPaths.controlPlaneDb())) {
      var runs = new RunStore(db);
      if (review) {
        return reviewLogPathFrom(latestBuildRun(runs, project), new ReviewStore(db));
      }
      return logPathFrom(runs.latestForProjectOnNode(project, NodeIdentity.handle()));
    } catch (RuntimeException e) {
      return null;
    }
  }

  private static Optional<RunStore.RunRow> latestBuildRun(RunStore runs, String project) {
    return runs.listForProject(project).stream()
        .filter(RunStore.RunRow::buildRole)
        .filter(
            run ->
                Objects.toString(run.node(), "")
                    .equals(Objects.toString(NodeIdentity.handle(), "")))
        .findFirst();
  }

  /** The latest local session run's run-scoped log path, or null when there is none. */
  static String logPathFrom(Optional<RunStore.RunRow> latestRun) {
    return latestRun.map(RunStore.RunRow::logPath).filter(Strings::isNotBlank).orElse(null);
  }

  /**
   * The latest build run's spec's latest review log, per-review under the review's own directory,
   * or the fixed foreground-review log when the current dispatch attempt has no review yet.
   */
  static String reviewLogPathFrom(Optional<RunStore.RunRow> latestRun, ReviewStore reviews) {
    return latestRun
        .map(RunStore.RunRow::specId)
        .flatMap(reviews::latestReviewForSpec)
        .map(review -> AgentUnit.forReview(review.id()).logPath())
        .orElseGet(AgentUnit.REVIEW::logPath);
  }

  private void printNoLog() {
    if (json) {
      var map = new LinkedHashMap<String, Object>();
      map.put("name", name);
      map.put("lines", List.of());
      map.put("error", "No agent log found");
      System.out.println(YamlUtil.dumpJson(map));
      return;
    }
    System.out.println(
        Ansi.AUTO.string(
            "  @|faint No agent log found. Launch an agent with: sail agent run "
                + name
                + " --task \"...\" --background|@"));
  }

  /**
   * Output form for one log line: the raw line under {@code --json} so machine consumers (and the
   * GUI's live stream) get the structured event verbatim, otherwise the human-rendered form.
   */
  static String renderForLog(String line, boolean json) {
    return json ? line : AgentLogRenderer.render(line);
  }

  private static String renderLines(String raw) {
    var out = new StringBuilder();
    for (var line : raw.split("\n", -1)) {
      var rendered = AgentLogRenderer.render(line);
      if (!rendered.isEmpty()) {
        out.append(rendered).append('\n');
      }
    }
    return out.toString();
  }
}
