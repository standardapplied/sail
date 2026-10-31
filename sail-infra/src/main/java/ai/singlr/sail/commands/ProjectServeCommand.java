/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.commands;

import ai.singlr.sail.api.HostOperations;
import ai.singlr.sail.api.OperationsFactory;
import ai.singlr.sail.api.StackOperations;
import ai.singlr.sail.config.YamlUtil;
import ai.singlr.sail.engine.NameValidator;
import ai.singlr.sail.engine.OperatorCommand;
import ai.singlr.sail.engine.ProjectServer;
import ai.singlr.sail.identity.Actor;
import java.time.Duration;
import java.util.concurrent.Callable;
import java.util.function.Function;
import picocli.CommandLine.Command;
import picocli.CommandLine.Help.Ansi;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

/**
 * Brings a project's stack up in its container and says what listens where: the one verb that
 * starts a project's processes. It runs the {@link StackOperations} verb the API and the
 * container's {@code portal} run, and exits 0 only when every declared port listens.
 */
@Command(
    name = "serve",
    description =
        "Bring the project's services and processes up and say what listens where (--stop stops"
            + " the processes).",
    mixinStandardHelpOptions = true)
public final class ProjectServeCommand implements Callable<Integer> {

  @Parameters(index = "0", arity = "0..1", description = "Project (default: the current project).")
  private String project;

  @Option(
      names = "--stop",
      description = "Stop the project's processes; its services keep running.")
  private boolean stop;

  @Option(
      names = "--timeout",
      description = "Seconds to wait for every port to listen (default: ${DEFAULT-VALUE}).",
      defaultValue = "60")
  private long timeout;

  @Option(names = "--json", description = "Output as JSON.")
  private boolean json;

  @Option(names = "--dry-run", description = "Say what would be brought up, and start nothing.")
  private boolean dryRun;

  private final Function<OperatorCommand, HostOperations> operations;

  ProjectServeCommand() {
    this(operator -> OperationsFactory.open(operator::warn));
  }

  ProjectServeCommand(Function<OperatorCommand, HostOperations> operations) {
    this.operations = operations;
  }

  @Override
  public Integer call() throws Exception {
    var operator = OperatorCommand.here();
    project = CurrentProject.require(project);
    NameValidator.requireValidProjectName(project);
    try (var operations = this.operations.apply(operator)) {
      var stack = operations.stack();
      var ports =
          Actor.call(
              operations.identity().operator(),
              () ->
                  dryRun
                      ? stack.ports(project)
                      : stop
                          ? stack.stop(project)
                          : stack.serve(project, Duration.ofSeconds(timeout)));
      if (json) {
        System.out.println(YamlUtil.dumpJson(ports.toMap()));
      } else {
        print(ports);
      }
      return dryRun || stop || ports.allListening() ? 0 : 1;
    }
  }

  private void print(StackOperations.Ports ports) {
    var heading = dryRun ? "Would bring up " : stop ? "Stopped the processes of " : "Brought up ";
    System.out.println(Ansi.AUTO.string("  @|bold " + heading + ports.project() + "|@"));
    if (ports.ports().isEmpty()) {
      System.out.println(
          Ansi.AUTO.string(
              "  @|faint No service or process declares a port: add `port` to a process in its"
                  + " definition.|@"));
      return;
    }
    ProjectServer.Report.of(ports.ports()).lines().forEach(line -> System.out.println("  " + line));
    ports
        .restarted()
        .forEach(
            name ->
                System.out.println(
                    Ansi.AUTO.string(
                        "  @|faint Restarted "
                            + name
                            + ": its command or environment changed.|@")));
  }
}
