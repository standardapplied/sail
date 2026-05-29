/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.commands;

import ai.singlr.sail.config.YamlUtil;
import ai.singlr.sail.engine.ContainerExec;
import ai.singlr.sail.engine.ContainerManager;
import ai.singlr.sail.engine.ContainerStateGuard;
import ai.singlr.sail.engine.NameValidator;
import ai.singlr.sail.engine.ShellExecutor;
import java.util.LinkedHashMap;
import java.util.List;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Spec;

@Command(
    name = "exec",
    description = "Run a command inside a project container as the dev user.",
    mixinStandardHelpOptions = true)
public final class ExecCommand implements Runnable {

  @Parameters(index = "0", description = "Project name.")
  private String name;

  @Parameters(index = "1..*", arity = "1..*", description = "Command and arguments to execute.")
  private List<String> command;

  @Option(names = "--dry-run", description = "Print commands instead of executing them.")
  private boolean dryRun;

  @Option(
      names = "--json",
      description = "Output in JSON format (captures output, not interactive).")
  private boolean json;

  @Spec private CommandSpec spec;

  @Override
  public void run() {
    CliCommand.run(spec, this::execute);
  }

  private void execute() throws Exception {
    NameValidator.requireValidProjectName(name);
    var shell = new ShellExecutor(dryRun);
    var mgr = new ContainerManager(shell);

    var state = mgr.queryState(name);
    ContainerStateGuard.requireRunning(state, name);

    var fullCmd = ContainerExec.asDevUser(name, command);

    if (dryRun) {
      shell.exec(fullCmd);
      return;
    }

    if (json) {
      var result = shell.exec(fullCmd);
      var map = new LinkedHashMap<String, Object>();
      map.put("project", name);
      map.put("command", command);
      map.put("exit_code", result.exitCode());
      map.put("stdout", result.stdout());
      map.put("stderr", result.stderr());
      System.out.println(YamlUtil.dumpJson(map));
      return;
    }

    var pb = new ProcessBuilder(fullCmd);
    pb.inheritIO();
    var process = pb.start();
    var exitCode = process.waitFor();
    System.exit(exitCode);
  }
}
