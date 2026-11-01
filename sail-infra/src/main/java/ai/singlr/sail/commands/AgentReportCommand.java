/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.commands;

import ai.singlr.sail.api.HostOperations;
import ai.singlr.sail.api.OperationsFactory;
import ai.singlr.sail.config.ProjectDefinition;
import ai.singlr.sail.config.Spec;
import ai.singlr.sail.config.YamlUtil;
import ai.singlr.sail.engine.AgentReporter;
import ai.singlr.sail.engine.Banner;
import ai.singlr.sail.engine.ContainerManager;
import ai.singlr.sail.engine.ContainerStateGuard;
import ai.singlr.sail.engine.NameValidator;
import ai.singlr.sail.engine.NodeIdentity;
import ai.singlr.sail.engine.OperatorCommand;
import ai.singlr.sail.engine.ShellExecutor;
import ai.singlr.sail.store.RunStore;
import java.util.List;
import java.util.function.Function;
import picocli.CommandLine.Command;
import picocli.CommandLine.Help.Ansi;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

@Command(
    name = "report",
    description = "Generate a morning-after summary of an agent session.",
    mixinStandardHelpOptions = true)
public final class AgentReportCommand implements Runnable {

  @Parameters(
      index = "0",
      arity = "0..1",
      description = "Project name (default: the current project).")
  private String name;

  @Option(names = "--json", description = "Output in JSON format.")
  private boolean json;

  private final Function<OperatorCommand, HostOperations> operations;

  public AgentReportCommand() {
    this(operator -> OperationsFactory.open(operator::warn));
  }

  AgentReportCommand(Function<OperatorCommand, HostOperations> operations) {
    this.operations = operations;
  }

  @Override
  public void run() {
    CliCommand.run(this::execute);
  }

  /** The project's definition from the catalog, which a report needs. */
  ProjectDefinition definition(OperatorCommand operator) {
    try (var operations = this.operations.apply(operator)) {
      return operations.catalog().definitions().require(name);
    }
  }

  private void execute() throws Exception {
    var operator = OperatorCommand.here();
    name = CurrentProject.require(name);
    NameValidator.requireValidProjectName(name);
    var shell = new ShellExecutor(false);
    var mgr = new ContainerManager(shell);

    ContainerStateGuard.requireCreated(mgr.queryState(name), name);

    var reporter = new AgentReporter(shell);
    var report =
        reporter.generate(
            name,
            definition(operator),
            projectSpecs(name, operator),
            latestSession(name, operator));

    if (json) {
      System.out.println(YamlUtil.dumpJson(report.toMap()));
      return;
    }

    Banner.printAgentReport(name, report, operator, System.out, Ansi.AUTO);
  }

  List<Spec> projectSpecs(String project, OperatorCommand operator) {
    try (var operations = this.operations.apply(operator)) {
      return operations.catalog().projectSpecs(project);
    } catch (Exception ignored) {
      return List.of();
    }
  }

  RunStore.RunRow latestSession(String project, OperatorCommand operator) {
    try (var operations = this.operations.apply(operator)) {
      return operations.dispatching().latestRun(project, NodeIdentity.handle()).orElse(null);
    } catch (Exception ignored) {
      return null;
    }
  }
}
