/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.commands;

import ai.singlr.sail.config.SailYaml;
import ai.singlr.sail.config.Spec;
import ai.singlr.sail.config.SpecDirectory;
import ai.singlr.sail.config.YamlUtil;
import ai.singlr.sail.engine.AgentCli;
import ai.singlr.sail.engine.AgentSession;
import ai.singlr.sail.engine.Banner;
import ai.singlr.sail.engine.ContainerExec;
import ai.singlr.sail.engine.ContainerManager;
import ai.singlr.sail.engine.ContainerState;
import ai.singlr.sail.engine.DispatchRepos;
import ai.singlr.sail.engine.NameValidator;
import ai.singlr.sail.engine.SailPaths;
import ai.singlr.sail.engine.ShellExecutor;
import ai.singlr.sail.engine.SnapshotManager;
import ai.singlr.sail.engine.SpecWorkspace;
import java.io.IOException;
import java.nio.file.Files;
import java.util.List;
import java.util.Objects;
import picocli.CommandLine.Command;
import picocli.CommandLine.Help.Ansi;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

/**
 * Dispatches the next ready spec to an agent for autonomous execution. Reads per-spec metadata from
 * the container, finds the next pending spec, reads its {@code spec.md}, and launches the
 * configured agent.
 */
@Command(
    name = "dispatch",
    description = "Dispatch the next ready spec to an agent for autonomous execution.",
    mixinStandardHelpOptions = true)
public final class DispatchCommand implements Runnable {

  @Parameters(index = "0", description = "Project name.")
  private String name;

  @Option(
      names = "--spec",
      description = "Override auto-selection: dispatch a specific spec by ID.")
  private String specId;

  @Option(names = "--background", description = "Run agent in background.", defaultValue = "true")
  private boolean background;

  @Option(
      names = "--repo",
      split = ",",
      description = "Repository path(s) to branch for this spec.")
  private List<String> repoOverrides;

  @Option(names = "--dry-run", description = "Print commands instead of executing them.")
  private boolean dryRun;

  @Option(names = "--json", description = "Output in JSON format.")
  private boolean json;

  @Option(
      names = "--snapshot",
      negatable = true,
      description =
          "Take a container snapshot before dispatch. Use --no-snapshot to skip. If neither is"
              + " passed, prompts interactively unless agent.auto_snapshot is true in sail.yaml.")
  private Boolean snapshot;

  @Option(
      names = {"-f", "--file"},
      description = "Path to sail.yaml project descriptor.",
      defaultValue = "sail.yaml")
  private String file;

  @picocli.CommandLine.Spec private CommandSpec commandSpec;

  @Override
  public void run() {
    CliCommand.run(commandSpec, this::execute);
  }

  private void execute() throws Exception {
    NameValidator.requireValidProjectName(name);

    if (!json) {
      Banner.printBranding(System.out, Ansi.AUTO);
    }

    var shell = new ShellExecutor(dryRun);
    var mgr = new ContainerManager(shell);

    var state = mgr.queryState(name);
    switch (state) {
      case ContainerState.Running ignored -> {}
      case ContainerState.Stopped ignored ->
          throw new IllegalStateException(
              "Project '" + name + "' is stopped. Start it with: sail project start " + name);
      case ContainerState.NotCreated ignored ->
          throw new IllegalStateException(
              "Project '" + name + "' does not exist. Run 'sail project create' first.");
      case ContainerState.Error e ->
          throw new IllegalStateException("Container error: " + e.message());
    }

    var singYamlPath = SailPaths.resolveSailYaml(name, file);
    if (!Files.exists(singYamlPath)) {
      throw new IllegalStateException(
          "Project descriptor not found: " + singYamlPath.toAbsolutePath());
    }
    var config = SailYaml.fromMap(YamlUtil.parseFile(singYamlPath));

    if (config.agent() == null || config.agent().specsDir() == null) {
      throw new IllegalStateException(
          "No specs_dir configured in agent block. Add it to sail.yaml.");
    }

    var sshUser = config.sshUser();
    var specsDir = "/home/" + sshUser + "/workspace/" + config.agent().specsDir();
    var specWorkspace = new SpecWorkspace(shell, name, specsDir);

    var specs = specWorkspace.readSpecs();
    if (specs.isEmpty()) {
      printNoSpecs();
      return;
    }

    var agentSession = new AgentSession(shell);
    var existingSession = agentSession.queryStatus(name);
    if (existingSession != null && existingSession.running()) {
      throw new IllegalStateException(
          "Agent is already running on '"
              + name
              + "' (PID "
              + existingSession.pid()
              + "). Stop it first with: sail agent stop "
              + name);
    }

    var nextSpec = resolveSpec(specs);
    if (nextSpec == null) {
      printNoSpecs();
      return;
    }

    var targetRepos = DispatchRepos.resolve(config, nextSpec, repoOverrides);
    var taskSpec = withTargetRepos(nextSpec, targetRepos);
    specWorkspace.updateStatus(nextSpec.id(), "in_progress");

    var specBody = Objects.requireNonNullElse(specWorkspace.readSpecBody(nextSpec.id()), "");
    var description = !specBody.isBlank() ? specBody : nextSpec.title();
    var task = buildTaskPrompt(taskSpec, description, config.agent().specsDir());

    if (json) {
      System.out.println(
          CliJson.stringify(
              new DispatchPreview(
                  name,
                  nextSpec.id(),
                  nextSpec.title(),
                  background ? "background" : "foreground",
                  task)));
      if (dryRun) {
        return;
      }
    }

    if (!json) {
      System.out.println(Ansi.AUTO.string("  @|bold Dispatching spec:|@ " + nextSpec.id()));
      System.out.println(Ansi.AUTO.string("  @|faint " + nextSpec.title() + "|@"));
      System.out.println();
    }

    var agentType = taskSpec.agent() != null ? taskSpec.agent() : config.agent().type();
    var agentCli = AgentCli.fromYamlName(agentType);
    var workDir = AgentSession.launchWorkDir(sshUser, targetRepos);
    var fullPermissions = true;

    String branchName = null;

    if (!dryRun && SnapshotDecision.shouldSnapshot(snapshot, config, json)) {
      var snapMgr = new SnapshotManager(shell);
      var label = SnapshotManager.defaultLabel();
      SnapshotDecision.create(System.out, snapMgr, name, label, json);
    }

    if (config.agent().autoBranch()) {
      var prefix = config.agent().branchPrefix() != null ? config.agent().branchPrefix() : "sail/";
      branchName = nextSpec.branch() != null ? nextSpec.branch() : prefix + nextSpec.id();
      var created = 0;
      for (var repo : targetRepos) {
        var repoDir = branchRepoDir(workDir, targetRepos, repo);
        var repoExists =
            shell.exec(ContainerExec.asDevUser(name, List.of("test", "-d", repoDir + "/.git")));
        if (repoExists.ok()) {
          if (!json) {
            System.out.println(
                Ansi.AUTO.string(
                    "  @|bold Creating branch:|@ " + branchName + " in " + repo.path() + "..."));
          }
          var branchCmd =
              ContainerExec.asDevUser(
                  name, List.of("git", "-C", repoDir, "checkout", "-b", branchName));
          var result = shell.exec(branchCmd);
          if (!result.ok()) {
            throw new IOException(
                "Failed to create branch '" + branchName + "': " + result.stderr());
          }
          if (!json) {
            System.out.println(
                Ansi.AUTO.string("  @|green \u2713|@ Branch " + branchName + " in " + repo.path()));
            System.out.println();
          }
          created++;
        }
      }
      if (created == 0 && !json) {
        System.out.println(
            Ansi.AUTO.string("  @|faint Branch:|@ " + branchName + " (create manually in repo)"));
        System.out.println();
      }
    }

    agentSession.ensureDirectory(name);
    agentSession.writeTaskFile(name, task);
    agentSession.writeSession(name, task, Objects.requireNonNullElse(branchName, ""));

    if (background) {
      var sshCmd =
          AgentSession.buildBackgroundLaunchCommand(
              name,
              sshUser,
              workDir,
              fullPermissions,
              agentCli,
              taskSpec.model(),
              taskSpec.reasoningEffort());
      if (!json) {
        System.out.println(Ansi.AUTO.string("  @|bold Launching agent in background...|@"));
        System.out.println(Ansi.AUTO.string("  @|faint " + String.join(" ", sshCmd) + "|@"));
        System.out.println();
      }
      if (!dryRun) {
        var pb = new ProcessBuilder(sshCmd);
        pb.inheritIO();
        var process = pb.start();
        var exitCode = process.waitFor();
        if (exitCode != 0) {
          throw new IOException("Failed to launch background agent; see output above.");
        }
      }
      Banner.printAgentLaunched(name, task, branchName, System.out, Ansi.AUTO);
      if (!dryRun) {
        launchWatcherIfGuardrails(config);
      }
    } else {
      var sshCmd =
          AgentSession.buildForegroundTaskCommand(
              name,
              sshUser,
              workDir,
              fullPermissions,
              agentCli,
              taskSpec.model(),
              taskSpec.reasoningEffort());
      if (!json) {
        System.out.println(Ansi.AUTO.string("  @|bold Launching agent with spec...|@"));
        System.out.println(Ansi.AUTO.string("  @|faint " + String.join(" ", sshCmd) + "|@"));
        System.out.println();
      }
      if (!dryRun) {
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
  }

  private Spec resolveSpec(List<Spec> specs) {
    if (specId != null) {
      return specs.stream()
          .filter(s -> s.id().equals(specId))
          .findFirst()
          .orElseThrow(() -> new IllegalArgumentException("Spec '" + specId + "' not found"));
    }
    return SpecDirectory.nextReady(specs);
  }

  static String buildTaskPrompt(Spec spec, String description, String specsDir) {
    var targetRepos =
        spec.repos().isEmpty()
            ? ""
            : "\nTarget repo"
                + (spec.repos().size() == 1 ? "" : "s")
                + ": "
                + String.join(", ", spec.repos())
                + "\n";
    var targetAgent = spec.agent() == null ? "" : "\nTarget agent: " + spec.agent() + "\n";
    var targetModel = spec.model() == null ? "" : "\nTarget model: " + spec.model() + "\n";
    var targetReasoning =
        spec.reasoningEffort() == null
            ? ""
            : "\nTarget reasoning effort: " + spec.reasoningEffort() + "\n";
    return "Your current spec: \""
        + spec.title()
        + "\" (id: "
        + spec.id()
        + ")."
        + targetRepos
        + targetAgent
        + targetModel
        + targetReasoning
        + "\n"
        + description;
  }

  private static Spec withTargetRepos(Spec spec, List<SailYaml.Repo> targetRepos) {
    return new Spec(
        spec.id(),
        spec.title(),
        spec.status(),
        spec.assignee(),
        spec.dependsOn(),
        targetRepos.stream().map(SailYaml.Repo::path).toList(),
        spec.agent(),
        spec.model(),
        spec.reasoningEffort(),
        spec.branch());
  }

  private void printNoSpecs() {
    if (json) {
      System.out.println(CliJson.stringify(new NoDispatch(name, false, "no_pending_specs")));
    } else {
      System.out.println(Ansi.AUTO.string("  @|faint No pending specs found for " + name + ".|@"));
    }
  }

  static String branchRepoDir(String workDir, List<SailYaml.Repo> targetRepos, SailYaml.Repo repo) {
    return targetRepos.size() == 1 ? workDir : workDir + "/" + repo.path();
  }

  private void launchWatcherIfGuardrails(SailYaml config) {
    if (config.agent() == null || config.agent().guardrails() == null) {
      return;
    }
    try {
      var singBinary = SailPaths.binaryPath().toString();
      var singYamlPath = SailPaths.resolveSailYaml(name, file);
      var cmd =
          List.of(
              "nohup",
              singBinary,
              "agent",
              "watch",
              name,
              "-f",
              singYamlPath.toAbsolutePath().toString());
      var watchLog = SailPaths.projectDir(name).resolve("watch.log");
      Files.createDirectories(watchLog.getParent());
      var pb = new ProcessBuilder(cmd);
      pb.redirectOutput(ProcessBuilder.Redirect.to(watchLog.toFile()));
      pb.redirectErrorStream(true);
      pb.start();
      System.out.println(
          Ansi.AUTO.string("  @|green \u2713|@ Guardrail watcher started (log: " + watchLog + ")"));
    } catch (Exception e) {
      System.err.println(
          Banner.errorLine(
              "Failed to start guardrail watcher: "
                  + e.getMessage()
                  + ". Run manually: sail agent watch "
                  + name,
              Ansi.AUTO));
    }
  }

  record DispatchPreview(String name, String specId, String specTitle, String mode, String task) {}

  record NoDispatch(String name, boolean dispatched, String reason) {}
}
