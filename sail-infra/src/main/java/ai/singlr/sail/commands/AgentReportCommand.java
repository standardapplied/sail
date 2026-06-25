/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.commands;

import ai.singlr.sail.config.SailYaml;
import ai.singlr.sail.config.Spec;
import ai.singlr.sail.config.YamlUtil;
import ai.singlr.sail.engine.AgentReporter;
import ai.singlr.sail.engine.Banner;
import ai.singlr.sail.engine.ContainerManager;
import ai.singlr.sail.engine.ContainerState;
import ai.singlr.sail.engine.ControlPlaneDb;
import ai.singlr.sail.engine.NameValidator;
import ai.singlr.sail.engine.SailPaths;
import ai.singlr.sail.engine.ShellExecutor;
import ai.singlr.sail.store.SessionStore;
import ai.singlr.sail.store.SpecStore;
import java.nio.file.Files;
import java.util.List;
import picocli.CommandLine.Command;
import picocli.CommandLine.Help.Ansi;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

@Command(
    name = "report",
    description = "Generate a morning-after summary of an agent session.",
    mixinStandardHelpOptions = true)
public final class AgentReportCommand implements Runnable {

  @Parameters(index = "0", description = "Project name.")
  private String name;

  @Option(names = "--json", description = "Output in JSON format.")
  private boolean json;

  @Option(
      names = {"-f", "--file"},
      description = "Path to sail.yaml project descriptor.",
      defaultValue = "sail.yaml")
  private String file;

  @picocli.CommandLine.Spec private CommandSpec spec;

  private final ControlPlaneDb controlPlaneDb;

  public AgentReportCommand() {
    this(ControlPlaneDb.DEFAULT);
  }

  AgentReportCommand(ControlPlaneDb controlPlaneDb) {
    this.controlPlaneDb = controlPlaneDb;
  }

  @Override
  public void run() {
    CliCommand.run(spec, this::execute);
  }

  private void execute() throws Exception {
    NameValidator.requireValidProjectName(name);
    var shell = new ShellExecutor(false);
    var mgr = new ContainerManager(shell);

    var state = mgr.queryState(name);
    switch (state) {
      case ContainerState.Running ignored -> {}
      case ContainerState.Stopped ignored -> {}
      case ContainerState.NotCreated ignored ->
          throw new IllegalStateException("Project '" + name + "' does not exist.");
      case ContainerState.Error e ->
          throw new IllegalStateException("Container error: " + e.message());
    }

    var singYamlPath = SailPaths.resolveSailYaml(name, file);
    if (!Files.exists(singYamlPath)) {
      throw new IllegalStateException("No sail.yaml found at " + file);
    }
    var config = SailYaml.fromMap(YamlUtil.parseFile(singYamlPath));

    var reporter = new AgentReporter(shell);
    var report = reporter.generate(name, config, projectSpecs(name), latestSession(name));

    if (json) {
      System.out.println(YamlUtil.dumpJson(report.toMap()));
      return;
    }

    Banner.printAgentReport(name, report, System.out, Ansi.AUTO);
  }

  List<Spec> projectSpecs(String project) {
    try (var db = controlPlaneDb.open()) {
      return new SpecStore(db).projectSpecs(project);
    } catch (Exception ignored) {
      return List.of();
    }
  }

  SessionStore.SessionRow latestSession(String project) {
    try (var db = controlPlaneDb.open()) {
      return new SessionStore(db).latestForProject(project).orElse(null);
    } catch (Exception ignored) {
      return null;
    }
  }
}
