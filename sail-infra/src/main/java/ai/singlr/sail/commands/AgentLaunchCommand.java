/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.commands;

import ai.singlr.sail.common.DateTimeUtils;
import ai.singlr.sail.config.SailYaml;
import ai.singlr.sail.config.SpecDirectory;
import ai.singlr.sail.config.YamlUtil;
import ai.singlr.sail.engine.AgentCli;
import ai.singlr.sail.engine.AgentSession;
import ai.singlr.sail.engine.Banner;
import ai.singlr.sail.engine.ContainerExec;
import ai.singlr.sail.engine.ContainerManager;
import ai.singlr.sail.engine.ContainerStateGuard;
import ai.singlr.sail.engine.GuardrailWatcher;
import ai.singlr.sail.engine.NameValidator;
import ai.singlr.sail.engine.SailPaths;
import ai.singlr.sail.engine.ShellExecutor;
import ai.singlr.sail.engine.SnapshotManager;
import ai.singlr.sail.store.SpecStore;
import ai.singlr.sail.store.Sqlite;
import java.io.IOException;
import java.nio.file.Files;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;
import picocli.CommandLine.Command;
import picocli.CommandLine.Help.Ansi;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Spec;

@Command(
    name = "start",
    aliases = {"launch"},
    description = "Launch an AI coding agent inside a project container.",
    mixinStandardHelpOptions = true)
public final class AgentLaunchCommand implements Runnable {

  private static final Pattern SAFE_PATH = Pattern.compile("^[a-zA-Z0-9._/\\-]+$");

  @Parameters(index = "0", description = "Project name.")
  private String name;

  @Option(names = "--task", description = "Task description for headless mode.")
  private String task;

  @Option(names = "--background", description = "Run in background (requires --task).")
  private boolean background;

  @Option(
      names = "--path",
      description = "Subdirectory path within the workspace.",
      defaultValue = "")
  private String path;

  @Option(names = "--dry-run", description = "Print commands instead of executing them.")
  private boolean dryRun;

  @Option(names = "--json", description = "Output in JSON format.")
  private boolean json;

  @Option(
      names = "--snapshot",
      negatable = true,
      description =
          "Take a container snapshot before launch. Use --no-snapshot to skip. If neither is"
              + " passed, prompts interactively (defaults to no); skips silently in --json mode.")
  private Boolean snapshot;

  @Option(
      names = {"-f", "--file"},
      description = "Path to sail.yaml project descriptor.",
      defaultValue = "sail.yaml")
  private String file;

  @Spec private CommandSpec spec;

  @Override
  public void run() {
    CliCommand.run(spec, this::execute);
  }

  private void execute() throws Exception {
    NameValidator.requireValidProjectName(name);

    if (!json) {
      Banner.printBranding(System.out, Ansi.AUTO);
    }

    var shell = new ShellExecutor(dryRun);
    var mgr = new ContainerManager(shell);

    var state = mgr.queryState(name);
    ContainerStateGuard.requireRunning(state, name);

    if (!path.isBlank()) {
      validateSafePath(path, "--path");
    }

    var singYamlPath = SailPaths.resolveSailYaml(name, file);
    SailYaml config = null;
    if (Files.exists(singYamlPath)) {
      config = SailYaml.fromMap(YamlUtil.parseFile(singYamlPath));
    }

    if (task == null
        && config != null
        && config.agent() != null
        && config.agent().specsDir() != null) {
      try (var db = Sqlite.open(SailPaths.controlPlaneDb())) {
        var store = new SpecStore(db);
        var nextSpec = SpecDirectory.nextReady(store.projectSpecs(name));
        if (nextSpec != null) {
          var specBody =
              store.getContent(nextSpec.id()).map(SpecStore.SpecContent::body).orElse("");
          var description = !specBody.isBlank() ? specBody : nextSpec.title();
          task =
              "Your current spec: \""
                  + nextSpec.title()
                  + "\" (id: "
                  + nextSpec.id()
                  + ").\n\n"
                  + description
                  + "\n\nWhen complete, run `sail spec status "
                  + name
                  + " "
                  + nextSpec.id()
                  + " done`. Then pick up the next pending spec and continue working.";
          if (!json) {
            System.out.println(Ansi.AUTO.string("  @|bold Spec:|@ " + nextSpec.id()));
            System.out.println(Ansi.AUTO.string("  @|faint " + nextSpec.title() + "|@"));
            System.out.println();
          }
        }
      }
    }

    if (background && task == null) {
      throw new IllegalArgumentException(
          "--background requires --task or a specs_dir with pending specs in sail.yaml agent"
              + " config.");
    }

    var sshUser = config != null ? config.sshUser() : "dev";
    var workDir = "/home/" + sshUser + "/workspace";
    if (!path.isBlank()) {
      workDir = workDir + "/" + path;
    }

    var fullPermissions =
        config != null
            && config.agent() != null
            && config.agent().config() != null
            && "full".equals(config.agent().config().get("permissions"));

    var agentType =
        config != null && config.agent() != null ? config.agent().type() : "claude-code";
    var agentCli = AgentCli.fromYamlName(agentType);

    System.out.println();

    var label = SnapshotManager.defaultLabel();
    var snapshotTaken = !dryRun && SnapshotDecision.shouldSnapshot(snapshot, config, json);

    if (snapshotTaken) {
      var snapMgr = new SnapshotManager(shell);
      SnapshotDecision.create(System.out, snapMgr, name, label, json);
    }

    String branchName = null;
    if (config != null && config.agent() != null && config.agent().autoBranch()) {
      var prefix = config.agent().branchPrefix() != null ? config.agent().branchPrefix() : "sail/";
      validateSafePath(prefix, "branch_prefix");
      branchName = prefix + label;
      System.out.println(Ansi.AUTO.string("  @|bold Creating branch:|@ " + branchName + "..."));
      var branchCmd =
          ContainerExec.asDevUser(
              name, List.of("git", "-C", workDir, "checkout", "-b", branchName));
      var result = shell.exec(branchCmd);
      if (!result.ok()) {
        throw new IOException("Failed to create branch '" + branchName + "': " + result.stderr());
      }
      System.out.println(Ansi.AUTO.string("  @|green \u2713|@ Branch " + branchName));
      System.out.println();
    }

    if (!json && agentCli == AgentCli.CLAUDE_CODE) {
      Banner.printAgentAuthTunnel(name, System.out, Ansi.AUTO);
      System.out.println();
    }

    if (!json && task == null && agentCli == AgentCli.CLAUDE_CODE) {
      System.out.println(
          Ansi.AUTO.string(
              "  @|faint Tip: Type /rc inside Claude Code to connect from your phone"
                  + " via Remote Control.|@"));
      System.out.println();
    }

    var snapshotLabel = snapshotTaken ? label : null;

    if (task != null && background) {
      launchBackground(
          shell, config, sshUser, workDir, fullPermissions, branchName, agentCli, snapshotLabel);
    } else if (task != null) {
      launchForegroundTask(shell, sshUser, workDir, fullPermissions, agentCli);
    } else {
      launchInteractive(shell, sshUser, workDir, fullPermissions, agentCli);
    }
  }

  private void launchBackground(
      ShellExecutor shell,
      SailYaml config,
      String sshUser,
      String workDir,
      boolean fullPermissions,
      String branchName,
      AgentCli agentCli,
      String snapshotLabel)
      throws Exception {
    var agentSession = new AgentSession(shell);
    agentSession.ensureDirectory(name);
    agentSession.writeTaskFile(name, task);
    agentSession.writeSession(name, task, Objects.requireNonNullElse(branchName, ""));

    var sshCmd =
        AgentSession.buildBackgroundLaunchCommand(
            name, sshUser, workDir, fullPermissions, agentCli);

    if (json) {
      var map = new LinkedHashMap<String, Object>();
      map.put("name", name);
      map.put("mode", "background");
      map.put("task", task);
      map.put("branch", branchName);
      map.put("log_path", AgentSession.logPath());
      map.put("ssh_command", String.join(" ", sshCmd));
      System.out.println(YamlUtil.dumpJson(map));
      return;
    }

    System.out.println(Ansi.AUTO.string("  @|bold Launching agent in background...|@"));
    if (dryRun) {
      System.out.println(Ansi.AUTO.string("  @|faint " + String.join(" ", sshCmd) + "|@"));
    }
    System.out.println();

    if (dryRun) {
      System.out.println("[dry-run] " + String.join(" ", sshCmd));
    } else {
      var pb = new ProcessBuilder(sshCmd);
      pb.inheritIO();
      var process = pb.start();
      var exitCode = process.waitFor();
      if (exitCode != 0) {
        System.err.println(
            Banner.errorLine("Background launch exited with code " + exitCode, Ansi.AUTO));
        if (snapshotLabel != null) {
          try {
            var rollbackMap = new LinkedHashMap<String, Object>();
            rollbackMap.put("rolled_back_at", DateTimeUtils.now().toString());
            rollbackMap.put("exit_code", exitCode);
            rollbackMap.put("snapshot_restored", snapshotLabel);
            rollbackMap.put("task", task);
            var stateDir = SailPaths.projectDir(name);
            Files.createDirectories(stateDir);
            YamlUtil.dumpToFile(rollbackMap, stateDir.resolve("last-rollback.yaml"));
            var snapMgr = new SnapshotManager(shell);
            snapMgr.restore(name, snapshotLabel);
            System.err.println(
                Ansi.AUTO.string("  @|yellow Auto-rollback:|@ restored snapshot " + snapshotLabel));
          } catch (Exception rollbackEx) {
            System.err.println(
                Banner.errorLine("Auto-rollback failed: " + rollbackEx.getMessage(), Ansi.AUTO));
          }
        }
      }
    }

    Banner.printAgentLaunched(name, task, branchName, System.out, Ansi.AUTO);

    if (!dryRun) {
      GuardrailWatcher.launchIfConfigured(name, file, config);
    }
  }

  private void launchForegroundTask(
      ShellExecutor shell,
      String sshUser,
      String workDir,
      boolean fullPermissions,
      AgentCli agentCli)
      throws Exception {
    var agentSession = new AgentSession(shell);
    agentSession.ensureDirectory(name);
    agentSession.writeTaskFile(name, task);

    var sshCmd =
        AgentSession.buildForegroundTaskCommand(name, sshUser, workDir, fullPermissions, agentCli);

    if (json) {
      var map = new LinkedHashMap<String, Object>();
      map.put("name", name);
      map.put("mode", "foreground");
      map.put("task", task);
      map.put("ssh_command", String.join(" ", sshCmd));
      System.out.println(YamlUtil.dumpJson(map));
      return;
    }

    System.out.println(Ansi.AUTO.string("  @|bold Launching agent with task...|@"));
    System.out.println(Ansi.AUTO.string("  @|faint " + String.join(" ", sshCmd) + "|@"));
    System.out.println();

    if (dryRun) {
      System.out.println("[dry-run] " + String.join(" ", sshCmd));
    } else {
      var pb = new ProcessBuilder(sshCmd);
      pb.inheritIO();
      var process = pb.start();
      var exitCode = process.waitFor();
      if (exitCode != 0) {
        System.err.println(
            Banner.errorLine("Agent session exited with code " + exitCode, Ansi.AUTO));
      }
    }
  }

  /** Interactive mode: SSH into container and launch the primary agent interactively. */
  private void launchInteractive(
      ShellExecutor shell,
      String sshUser,
      String workDir,
      boolean fullPermissions,
      AgentCli agentCli)
      throws Exception {
    var agentCmd = agentCli.interactiveCommand(fullPermissions);
    var sshCmd =
        List.of("ssh", "-t", sshUser + "@" + name, "--", "cd " + workDir + " && " + agentCmd);

    if (json) {
      var map = new LinkedHashMap<String, Object>();
      map.put("name", name);
      map.put("mode", "interactive");
      map.put("ssh_command", String.join(" ", sshCmd));
      map.put("work_dir", workDir);
      System.out.println(YamlUtil.dumpJson(map));
      return;
    }

    System.out.println(Ansi.AUTO.string("  @|bold Launching agent...|@"));
    System.out.println(Ansi.AUTO.string("  @|faint " + String.join(" ", sshCmd) + "|@"));
    System.out.println();

    if (dryRun) {
      System.out.println("[dry-run] " + String.join(" ", sshCmd));
    } else {
      var pb = new ProcessBuilder(sshCmd);
      pb.inheritIO();
      var process = pb.start();
      var exitCode = process.waitFor();
      if (exitCode != 0) {
        System.err.println(
            Banner.errorLine("Agent session exited with code " + exitCode, Ansi.AUTO));
      }
    }
  }

  private static void validateSafePath(String value, String optionName) {
    if (!SAFE_PATH.matcher(value).matches()) {
      throw new IllegalArgumentException(
          "Invalid "
              + optionName
              + " value: '"
              + value
              + "'. Only alphanumeric characters, dashes, underscores, dots, and slashes are"
              + " allowed.");
    }
  }
}
