/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.commands;

import ai.singlr.sail.config.SailYaml;
import ai.singlr.sail.config.YamlUtil;
import ai.singlr.sail.engine.Banner;
import ai.singlr.sail.engine.ContainerExec;
import ai.singlr.sail.engine.ContainerManager;
import ai.singlr.sail.engine.ContainerStateGuard;
import ai.singlr.sail.engine.NameValidator;
import ai.singlr.sail.engine.SailPaths;
import ai.singlr.sail.engine.ShellExecutor;
import java.nio.file.Files;
import java.util.LinkedHashMap;
import java.util.List;
import picocli.CommandLine.Command;
import picocli.CommandLine.Help.Ansi;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Spec;

@Command(
    name = "audit",
    description = "Run a security audit on the project's code changes.",
    mixinStandardHelpOptions = true)
public final class AgentAuditCommand implements Runnable {

  @Parameters(index = "0", description = "Project name.")
  private String name;

  @Option(
      names = {"-f", "--file"},
      description = "Path to sail.yaml project descriptor.",
      defaultValue = "sail.yaml")
  private String file;

  @Option(names = "--json", description = "Output in JSON format.")
  private boolean json;

  @Option(names = "--dry-run", description = "Print commands instead of executing them.")
  private boolean dryRun;

  @Spec private CommandSpec spec;

  @Override
  public void run() {
    CliCommand.run(spec, this::execute);
  }

  private void execute() throws Exception {
    NameValidator.requireValidProjectName(name);

    var singYamlPath = SailPaths.resolveSailYaml(name, file);
    if (!Files.exists(singYamlPath)) {
      throw new IllegalStateException(
          "Project descriptor not found: "
              + singYamlPath.toAbsolutePath()
              + "\n  Create a sail.yaml in the current directory, or specify one with --file.");
    }
    var config = SailYaml.fromMap(YamlUtil.parseFile(singYamlPath));

    var shell = new ShellExecutor(dryRun);
    var mgr = new ContainerManager(shell);
    var state = mgr.queryState(name);

    ContainerStateGuard.requireRunning(state, name);

    var sshUser = config.sshUser();
    var scriptPath = "/home/" + sshUser + "/.sail/security-audit.sh";

    var check = shell.exec(ContainerExec.asDevUser(name, List.of("test", "-f", scriptPath)));
    if (!check.ok()) {
      throw new IllegalStateException(
          "No security audit script found at "
              + scriptPath
              + "\n  Run 'sail agent context regen "
              + name
              + "' to generate it."
              + "\n  Ensure security_audit.enabled is true in sail.yaml.");
    }

    var auditCmd = ContainerExec.asDevUser(name, List.of("bash", scriptPath));
    var result = shell.exec(auditCmd);

    if (json) {
      var map = new LinkedHashMap<String, Object>();
      map.put("name", name);
      map.put("action", "audit");
      map.put("result", result.ok() ? "pass" : "fail");
      map.put("exit_code", result.exitCode());
      if (!result.ok()) {
        map.put("details_path", "/home/" + sshUser + "/security-audit.md");
      }
      if (!result.stdout().isBlank()) {
        map.put("output", result.stdout().strip());
      }
      System.out.println(YamlUtil.dumpJson(map));
      return;
    }

    Banner.printBranding(System.out, Ansi.AUTO);
    System.out.println();

    if (result.ok()) {
      System.out.println(Ansi.AUTO.string("  @|bold,green \u2713 Security audit passed.|@"));
    } else {
      System.out.println(Ansi.AUTO.string("  @|bold,red \u2717 Security audit found issues.|@"));
      System.out.println(
          Ansi.AUTO.string(
              "  @|faint Details saved to:|@ /home/" + sshUser + "/security-audit.md"));
      if (!result.stderr().isBlank()) {
        System.err.println(result.stderr().strip());
      }
    }
  }
}
