/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.commands;

import ai.singlr.sail.config.YamlUtil;
import ai.singlr.sail.engine.Banner;
import ai.singlr.sail.engine.ContainerExec;
import ai.singlr.sail.engine.ContainerManager;
import ai.singlr.sail.engine.ContainerStateGuard;
import ai.singlr.sail.engine.NameValidator;
import ai.singlr.sail.engine.ShellExecutor;
import java.io.IOException;
import java.util.List;
import picocli.CommandLine.Command;
import picocli.CommandLine.Help.Ansi;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Spec;

@Command(
    name = "containers",
    aliases = {"ps"},
    description = "Show Podman container status inside a project.",
    mixinStandardHelpOptions = true)
public final class PsCommand implements Runnable {

  @Parameters(index = "0", description = "Project name.")
  private String name;

  @Option(names = "--json", description = "Output in JSON format.")
  private boolean json;

  @Spec private CommandSpec spec;

  @Override
  public void run() {
    CliCommand.run(spec, this::execute);
  }

  private void execute() throws Exception {
    NameValidator.requireValidProjectName(name);
    var shell = new ShellExecutor(false);
    var mgr = new ContainerManager(shell);

    var state = mgr.queryState(name);
    ContainerStateGuard.requireRunning(state, name);

    var cmd = ContainerExec.asDevUser(name, List.of("podman", "ps", "--format", "json"));
    var result = shell.exec(cmd);
    if (!result.ok()) {
      throw new IOException("Failed to query Podman: " + result.stderr());
    }

    var containers = YamlUtil.parseList(result.stdout());

    if (json) {
      System.out.println(result.stdout().strip());
      return;
    }

    Banner.printBranding(System.out, Ansi.AUTO);
    Banner.printContainerStatus(name, state, System.out, Ansi.AUTO);
    System.out.println();

    if (containers.isEmpty()) {
      System.out.println(Ansi.AUTO.string("  @|faint No Podman containers running.|@"));
      return;
    }

    Banner.printPodmanTable(name, containers, System.out, Ansi.AUTO);
  }
}
