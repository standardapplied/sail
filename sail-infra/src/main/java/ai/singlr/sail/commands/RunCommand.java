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
import ai.singlr.sail.gen.AgentAuditFiles;
import ai.singlr.sail.gen.AgentContextGenerator;
import ai.singlr.sail.gen.GeneratedFile;
import ai.singlr.sail.store.SpecStore;
import ai.singlr.sail.store.Sqlite;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;
import picocli.CommandLine.Command;
import picocli.CommandLine.Help.Ansi;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Spec;

/**
 * Unified harness entry point. Regenerates agent context from sail.yaml, then launches the
 * configured agent. Equivalent to {@code sail agent context regen} followed by {@code sail agent
 * launch}, but in a single command.
 */
@Command(
    name = "run",
    description =
        "Regenerate agent context and launch the AI coding agent. The harness entry point.",
    mixinStandardHelpOptions = true)
public final class RunCommand implements Runnable {

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

  @Option(
      names = "--no-regen",
      description = "Skip context regeneration (use existing context files).")
  private boolean noRegen;

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

    if (!path.isBlank()) {
      validateSafePath(path, "--path");
    }

    if (!noRegen) {
      regenContext(shell, config);
    }

    launchAgent(shell, config);
  }

  private void regenContext(ShellExecutor shell, SailYaml config) throws Exception {
    var contextFiles = AgentContextGenerator.generateFiles(config);
    if (contextFiles.isEmpty()) {
      return;
    }

    var auditFiles = AgentAuditFiles.assemble(config);

    var allFiles = new ArrayList<GeneratedFile>();
    allFiles.addAll(contextFiles);
    allFiles.addAll(auditFiles);

    if (dryRun) {
      for (var f : allFiles) {
        System.out.println(
            "[dry-run] Would push "
                + f.remotePath()
                + " ("
                + f.content().length()
                + " bytes"
                + (f.executable() ? ", executable" : "")
                + ")");
      }
      return;
    }

    var sshUser = config.sshUser();
    var workspacePath = "/home/" + sshUser + "/workspace";
    shell.exec(ContainerExec.asDevUser(name, List.of("mkdir", "-p", workspacePath)));

    var existingFiles = listWorkspaceFiles(shell, name, workspacePath);
    var pushedCount = 0;
    var skipped = new ArrayList<String>();

    for (var f : contextFiles) {
      var fileName = f.remotePath().substring(f.remotePath().lastIndexOf('/') + 1);
      if (f.skipIfExists() && containsCaseInsensitive(existingFiles, fileName)) {
        skipped.add(fileName);
        continue;
      }
      pushFile(shell, name, f.remotePath(), f.content(), sshUser);
      pushedCount++;
    }
    for (var f : auditFiles) {
      var parentDir = f.remotePath().substring(0, f.remotePath().lastIndexOf('/'));
      shell.exec(ContainerExec.asDevUser(name, List.of("mkdir", "-p", parentDir)));
      pushFile(shell, name, f.remotePath(), f.content(), sshUser);
      if (f.executable()) {
        shell.exec(ContainerExec.asDevUser(name, List.of("chmod", "+x", f.remotePath())));
      }
      pushedCount++;
    }

    if (!json) {
      var msg = "Context regenerated (" + pushedCount + " files)";
      if (!skipped.isEmpty()) {
        msg += " — kept existing: " + String.join(", ", skipped);
      }
      System.out.println(Ansi.AUTO.string("  @|green \u2713|@ " + msg));
      System.out.println();
    }
  }

  private void launchAgent(ShellExecutor shell, SailYaml config) throws Exception {
    if (task == null && config.agent() != null && config.agent().specsDir() != null) {
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

    var sshUser = config.sshUser();
    var workDir = "/home/" + sshUser + "/workspace";
    if (!path.isBlank()) {
      workDir = workDir + "/" + path;
    }

    var fullPermissions =
        config.agent() != null
            && config.agent().config() != null
            && "full".equals(config.agent().config().get("permissions"));

    var agentType = config.agent() != null ? config.agent().type() : "claude-code";
    var agentCli = AgentCli.fromYamlName(agentType);

    var label = SnapshotManager.defaultLabel();
    var snapshotTaken = !dryRun && SnapshotDecision.shouldSnapshot(snapshot, config, json);

    if (snapshotTaken) {
      var snapMgr = new SnapshotManager(shell);
      SnapshotDecision.create(System.out, snapMgr, name, label, json);
    }

    String branchName = null;
    if (config.agent() != null && config.agent().autoBranch()) {
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
      launchInteractive(sshUser, workDir, fullPermissions, agentCli);
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
          autoRollback(shell, snapshotLabel, exitCode);
        }
      }
    }

    Banner.printAgentLaunched(name, task, branchName, System.out, Ansi.AUTO);

    if (!dryRun) {
      GuardrailWatcher.launchIfConfigured(name, file, config);
    }
  }

  private void autoRollback(ShellExecutor shell, String snapshotLabel, int exitCode) {
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

  private void launchInteractive(
      String sshUser, String workDir, boolean fullPermissions, AgentCli agentCli) throws Exception {
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

  private static void pushFile(
      ShellExecutor shell, String containerName, String remotePath, String content, String sshUser)
      throws Exception {
    var tmpFile = Files.createTempFile("sail-push-", ".tmp");
    try {
      Files.writeString(tmpFile, content);
      var result =
          shell.exec(
              List.of("incus", "file", "push", tmpFile.toString(), containerName + remotePath));
      if (!result.ok()) {
        throw new IOException("Failed to push file " + remotePath + ": " + result.stderr());
      }
    } finally {
      Files.deleteIfExists(tmpFile);
    }
    shell.exec(
        ContainerExec.asDevUser(
            containerName, List.of("chown", sshUser + ":" + sshUser, remotePath)));
  }

  private static Set<String> listWorkspaceFiles(
      ShellExecutor shell, String containerName, String dirPath) {
    try {
      var result = shell.exec(ContainerExec.asDevUser(containerName, List.of("ls", "-1", dirPath)));
      if (!result.ok() || result.stdout().isBlank()) {
        return Set.of();
      }
      return Set.copyOf(List.of(result.stdout().strip().split("\n")));
    } catch (Exception e) {
      return Set.of();
    }
  }

  private static boolean containsCaseInsensitive(Set<String> files, String target) {
    var lowerTarget = target.toLowerCase();
    return files.stream().anyMatch(f -> f.toLowerCase().equals(lowerTarget));
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
