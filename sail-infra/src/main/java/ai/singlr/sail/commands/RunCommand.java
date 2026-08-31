/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.commands;

import ai.singlr.sail.api.ApiException;
import ai.singlr.sail.api.DispatchOperations;
import ai.singlr.sail.api.ErrorCode;
import ai.singlr.sail.api.Event;
import ai.singlr.sail.api.SailEventPublisher;
import ai.singlr.sail.common.DateTimeUtils;
import ai.singlr.sail.common.Strings;
import ai.singlr.sail.config.SailYaml;
import ai.singlr.sail.config.Spec;
import ai.singlr.sail.config.SpecCatalog;
import ai.singlr.sail.config.YamlUtil;
import ai.singlr.sail.engine.AgentCli;
import ai.singlr.sail.engine.AgentContextInstaller;
import ai.singlr.sail.engine.AgentUnit;
import ai.singlr.sail.engine.Banner;
import ai.singlr.sail.engine.ContainerExec;
import ai.singlr.sail.engine.ContainerManager;
import ai.singlr.sail.engine.ContainerStateGuard;
import ai.singlr.sail.engine.GuardrailWatcher;
import ai.singlr.sail.engine.NameValidator;
import ai.singlr.sail.engine.SailPaths;
import ai.singlr.sail.engine.ShellExecutor;
import ai.singlr.sail.engine.SnapshotManager;
import ai.singlr.sail.engine.WatcherSpawner;
import ai.singlr.sail.gen.AgentContextGenerator;
import ai.singlr.sail.store.FdeStore;
import ai.singlr.sail.store.ReviewStore;
import ai.singlr.sail.store.RunStore;
import ai.singlr.sail.store.SpecStore;
import ai.singlr.sail.store.Sqlite;
import java.io.IOException;
import java.nio.file.Files;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;
import picocli.CommandLine.Command;
import picocli.CommandLine.Help.Ansi;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

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

  @Parameters(
      index = "0",
      arity = "0..1",
      description = "Project name (default: the current project).")
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

  @picocli.CommandLine.Spec private CommandSpec spec;

  private SailEventPublisher eventPublisher;

  @Override
  public void run() {
    CliCommand.run(spec, this::execute);
  }

  private void execute() throws Exception {
    name = CurrentProject.require(name);
    NameValidator.requireValidProjectName(name);

    if (!json) {
      Banner.printBranding(System.out, Ansi.AUTO);
    }

    var sailYamlPath = SailPaths.resolveSailYaml(name, file);
    if (!Files.exists(sailYamlPath)) {
      throw new IllegalStateException(
          "Project descriptor not found: "
              + sailYamlPath.toAbsolutePath()
              + "\n  Create a sail.yaml in the current directory, or specify one with --file.");
    }
    var config = SailYaml.fromMap(YamlUtil.parseFile(sailYamlPath));

    var shell = new ShellExecutor(dryRun);
    var mgr = new ContainerManager(shell);
    var state = mgr.queryState(name);

    ContainerStateGuard.requireRunning(state, name);

    if (!path.isBlank()) {
      validateSafePath(path, "--path");
    }

    launchAgent(shell, config);
  }

  private void regenContext(ShellExecutor shell, SailYaml config) throws Exception {
    var contextFiles = AgentContextGenerator.generateFiles(config);
    if (contextFiles.isEmpty()) {
      return;
    }

    if (dryRun) {
      for (var f : contextFiles) {
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

    var result = AgentContextInstaller.install(shell, name, config);

    if (!json) {
      var msg = "Context regenerated (" + result.pushed().size() + " files)";
      System.out.println(Ansi.AUTO.string("  @|green \u2713|@ " + msg));
      System.out.println();
    }
  }

  private void launchAgent(ShellExecutor shell, SailYaml config) throws Exception {
    if (task == null && config.agent() != null) {
      try (var db = Sqlite.open(SailPaths.controlPlaneDb())) {
        var store = new SpecStore(db);
        var nextSpec = SpecCatalog.nextReady(store.projectSpecs(name));
        if (nextSpec != null) {
          var specBody =
              store.getContent(nextSpec.id()).map(SpecStore.SpecContent::body).orElse("");
          task = specTask(name, nextSpec, specBody);
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
          "--background requires --task or a pending spec in the Sail database.");
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
    var branchName = branchName(config, label);

    if (task == null) {
      if (!noRegen) {
        regenContext(shell, config);
      }
      prepareContainer(shell, workDir, snapshotTaken, label, branchName);
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

    if (task != null) {
      launchTaskSession(shell, config, workDir, branchName, snapshotTaken, label);
    } else {
      launchInteractive(sshUser, workDir, fullPermissions, agentCli);
    }
  }

  private String branchName(SailYaml config, String label) {
    if (config.agent() == null || !config.agent().autoBranch()) {
      return null;
    }
    var prefix = Objects.requireNonNullElse(config.agent().branchPrefix(), "sail/");
    validateSafePath(prefix, "branch_prefix");
    return prefix + label;
  }

  /**
   * The pre-launch container mutations — the snapshot and the work-branch checkout. The task lane
   * runs this (and the context regeneration, which overwrites the shared home-level agent context)
   * only through {@link DispatchOperations.AdhocPreparer}, strictly after the whole-container
   * reservation is won, so a refused launch never disturbs the workspace — or the lazily loaded
   * instructions and skills — of the agent that owns it; the interactive lane, which reserves
   * nothing, prepares inline.
   */
  private void prepareContainer(
      ShellExecutor shell, String workDir, boolean snapshotTaken, String label, String branchName)
      throws Exception {
    if (snapshotTaken) {
      SnapshotDecision.create(System.out, new SnapshotManager(shell), name, label, json);
    }
    if (branchName == null) {
      return;
    }
    System.out.println(Ansi.AUTO.string("  @|bold Creating branch:|@ " + branchName + "..."));
    var branchCmd =
        ContainerExec.asDevUser(name, List.of("git", "-C", workDir, "checkout", "-b", branchName));
    var result = shell.exec(branchCmd);
    if (!result.ok()) {
      throw new IOException("Failed to create branch '" + branchName + "': " + result.stderr());
    }
    System.out.println(Ansi.AUTO.string("  @|green ✓|@ Branch " + branchName));
    System.out.println();
  }

  /**
   * Launches the headless task session as a first-class ad-hoc run through the shared {@link
   * DispatchOperations} launch machinery: a minted run id, a whole-container reservation, a
   * run-scoped unit and log, and — in the background mode — the same run-addressed guardrail
   * watcher a dispatch gets. The context regeneration, snapshot, and branch checkout ride the
   * preparer, so they happen only once the reservation is won. {@code --json} and {@code --dry-run}
   * describe the launch (the command and run-scoped paths) without reserving, preparing, or
   * executing anything.
   */
  private void launchTaskSession(
      ShellExecutor shell,
      SailYaml config,
      String workDir,
      String branchName,
      boolean snapshotTaken,
      String label)
      throws Exception {
    var snapshotLabel = snapshotTaken ? label : null;
    var handle = Objects.toString(HostSync.handle(), "");
    var describeOnly = json || dryRun;
    var launchCommand = new AtomicReference<List<String>>();
    try (var db = Sqlite.open(SailPaths.controlPlaneDb())) {
      var operations = operations(shell, db, launchCommand);
      var request =
          new DispatchOperations.AdhocRequest(task, branchName, path, background, describeOnly);
      DispatchOperations.AdhocSession session;
      try {
        session =
            operations.startAdhoc(
                name,
                request,
                handle,
                () -> {
                  if (!noRegen) {
                    regenContext(shell, config);
                  }
                  prepareContainer(shell, workDir, snapshotTaken, label, branchName);
                });
      } catch (ApiException e) {
        if (background
            && snapshotLabel != null
            && rollbackSafe(
                e, new RunStore(db).runningForProjectOnNode(name, handle).isPresent())) {
          System.err.println(Banner.errorLine(e.getMessage(), Ansi.AUTO));
          autoRollback(shell, snapshotLabel, 1);
        }
        var action = e.failure().action();
        throw new IllegalStateException(
            Strings.isBlank(action) ? e.getMessage() : e.getMessage() + " " + action, e);
      }
      render(session, launchCommand.get(), branchName);
    }
  }

  /**
   * A snapshot restore is only safe when the refused launch left nothing behind: an actual launch
   * failure with no run still active on this box. A reservation refusal means another agent owns
   * the container — restoring would yank the workspace out from under it — and a post-launch
   * supervision failure deliberately keeps the run reserved with a live agent underneath.
   */
  static boolean rollbackSafe(ApiException e, boolean activeSession) {
    return e.failure().errorCode() == ErrorCode.AGENT_LAUNCH_FAILED && !activeSession;
  }

  private DispatchOperations operations(
      ShellExecutor shell, Sqlite db, AtomicReference<List<String>> launchCommand) {
    var listener =
        new DispatchOperations.Listener() {
          @Override
          public void launching(boolean bg, List<String> command) {
            launchCommand.set(command);
            if (json) {
              return;
            }
            System.out.println(
                Ansi.AUTO.string(
                    bg
                        ? "  @|bold Launching agent in background...|@"
                        : "  @|bold Launching agent with task...|@"));
            System.out.println(Ansi.AUTO.string("  @|faint " + String.join(" ", command) + "|@"));
            System.out.println();
          }

          @Override
          public void runsPruned(int count) {
            if (count > 0 && !json) {
              System.out.println(
                  Ansi.AUTO.string(
                      "  @|faint Pruned " + count + " old run log(s) in " + name + ".|@"));
            }
          }

          @Override
          public void sailSetupUpdated(boolean updated) {
            if (updated && !json) {
              System.out.println(
                  Ansi.AUTO.string(
                      "  @|faint Updated sail event helpers in "
                          + name
                          + " (installed files were stale or incomplete).|@"));
            }
          }
        };
    return new DispatchOperations(
        shell,
        file,
        new SpecStore(db),
        new ReviewStore(db),
        new RunStore(db),
        new FdeStore(db),
        this::publishLifecycle,
        new WatcherSpawner(shell, WatcherSpawner::spawnProcess),
        (project, config) -> "",
        DispatchOperations.terminalLauncher(),
        listener,
        new PtyHostYield());
  }

  private void render(
      DispatchOperations.AdhocSession session, List<String> command, String branchName) {
    if (json) {
      var map = new LinkedHashMap<String, Object>();
      map.put("name", name);
      map.put("mode", background ? "background" : "foreground");
      map.put("task", task);
      map.put("branch", branchName);
      map.put("run_id", session.runId());
      map.put("log_path", AgentUnit.forRun(session.runId()).logPath());
      map.put("ssh_command", command == null ? "" : String.join(" ", command));
      System.out.println(YamlUtil.dumpJson(map));
      return;
    }
    if (dryRun) {
      System.out.println("[dry-run] " + (command == null ? "" : String.join(" ", command)));
      return;
    }
    if (background) {
      Banner.printAgentLaunched(name, task, branchName, System.out, Ansi.AUTO);
      session
          .watcher()
          .ifPresent(
              spawned ->
                  System.out.println(
                      Ansi.AUTO.string(
                          "  @|green ✓|@ "
                              + GuardrailWatcher.describe(
                                  spawned, WatcherSpawner.watchLogForRun(name, session.runId())))));
      return;
    }
    if (session.exitCode() != null && session.exitCode() != 0) {
      System.err.println(
          Banner.errorLine("Agent session exited with code " + session.exitCode(), Ansi.AUTO));
    }
  }

  /**
   * Publishes a lifecycle {@link Event} to the running sail-api best-effort, so SSE subscribers see
   * the session start regardless of surface; the control-plane database already holds the run, so
   * an unreachable sail-api never fails the launch.
   */
  private void publishLifecycle(Event event) {
    try {
      if (eventPublisher == null) {
        eventPublisher = SailEventPublisher.localDefault();
      }
      eventPublisher.publish(event);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    } catch (Exception e) {
      if (!json) {
        System.err.println(
            Banner.errorLine(
                "Could not publish "
                    + event.type()
                    + " event ("
                    + e.getMessage()
                    + "). sail-api may be unreachable; the launch itself is unaffected.",
                Ansi.AUTO));
      }
    }
  }

  /**
   * Builds the agent task prompt for a pending spec: its title and id, the body (falling back to
   * the title when empty), and the instruction to mark it done and pick up the next one.
   */
  static String specTask(String project, Spec spec, String body) {
    var description = !body.isBlank() ? body : spec.title();
    return "Your current spec: \""
        + spec.title()
        + "\" (id: "
        + spec.id()
        + ").\n\n"
        + description
        + "\n\nWhen complete, run `sail spec status "
        + project
        + " "
        + spec.id()
        + " done`. Then pick up the next pending spec and continue working.";
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
