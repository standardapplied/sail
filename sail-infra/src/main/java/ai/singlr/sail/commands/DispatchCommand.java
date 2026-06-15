/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.commands;

import ai.singlr.sail.api.Event;
import ai.singlr.sail.api.SailEventPublisher;
import ai.singlr.sail.common.Strings;
import ai.singlr.sail.config.BranchPolicy;
import ai.singlr.sail.config.SailYaml;
import ai.singlr.sail.config.Spec;
import ai.singlr.sail.config.SpecDirectory;
import ai.singlr.sail.config.SpecStatus;
import ai.singlr.sail.config.YamlUtil;
import ai.singlr.sail.engine.AgentCli;
import ai.singlr.sail.engine.AgentSession;
import ai.singlr.sail.engine.AgentTaskPrompt;
import ai.singlr.sail.engine.Banner;
import ai.singlr.sail.engine.ContainerExec;
import ai.singlr.sail.engine.ContainerManager;
import ai.singlr.sail.engine.ContainerSailSetup;
import ai.singlr.sail.engine.ContainerStateGuard;
import ai.singlr.sail.engine.DispatchRepos;
import ai.singlr.sail.engine.GuardrailWatcher;
import ai.singlr.sail.engine.HostInfo;
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
import java.util.Map;
import java.util.Objects;
import picocli.CommandLine.Command;
import picocli.CommandLine.Help.Ansi;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

/**
 * Dispatches the next ready spec to an agent for autonomous execution. Reads the project's specs
 * from the control-plane database (the source of truth, replicated by sync), picks the next ready
 * one, marks it {@code in_progress}, and launches the configured agent with its body — so a stopped
 * or file-drifted container never blocks dispatch.
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
              + " passed, prompts interactively (defaults to no); skips silently in --json mode.")
  private Boolean snapshot;

  @Option(
      names = "--restart",
      description =
          "Re-dispatch a spec whose status is not 'pending'. Requires --spec. Resets status to"
              + " pending and records a 'restarted' lifecycle event before dispatching.")
  private boolean restart;

  @Option(
      names = {"-f", "--file"},
      description = "Path to sail.yaml project descriptor.",
      defaultValue = "sail.yaml")
  private String file;

  @picocli.CommandLine.Spec private CommandSpec commandSpec;

  private SailEventPublisher eventPublisher;

  @Override
  public void run() {
    CliCommand.run(commandSpec, this::execute);
  }

  private void execute() throws Exception {
    NameValidator.requireValidProjectName(name);
    if (restart && specId == null) {
      throw new IllegalArgumentException(
          "--restart requires --spec to identify which spec to restart.");
    }

    if (!json) {
      Banner.printBranding(System.out, Ansi.AUTO);
    }

    var shell = new ShellExecutor(dryRun);
    var mgr = new ContainerManager(shell);

    var state = mgr.queryState(name);
    ContainerStateGuard.requireRunning(state, name);

    var singYamlPath = SailPaths.resolveSailYaml(name, file);
    if (!Files.exists(singYamlPath)) {
      throw new IllegalStateException(
          "Project descriptor not found: " + singYamlPath.toAbsolutePath());
    }
    var config = SailYaml.fromMap(YamlUtil.parseFile(singYamlPath));

    if (config.agent() == null) {
      throw new IllegalStateException("No agent configured in sail.yaml's agent block.");
    }

    var sshUser = config.sshUser();

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

    var prepared = prepareDispatch(name, specId, restart);
    if (prepared == null) {
      printNoSpecs();
      return;
    }
    var resolution = prepared.resolution();
    var nextSpec = resolution.spec();
    var specBody = prepared.body();

    var agentType = nextSpec.agent() != null ? nextSpec.agent() : config.agent().type();
    var targetRepos = DispatchRepos.resolve(config, nextSpec, repoOverrides);
    var taskSpec = DispatchRepos.withTargetRepos(nextSpec, targetRepos);
    var branchName = BranchPolicy.branchName(config, nextSpec);

    if (resolution.restarted()) {
      publishLifecycle(
          Event.WellKnownTypes.SPEC_RESTARTED,
          nextSpec.id(),
          Map.of("note", "restarted from " + resolution.previousStatus()));
    }
    publishLifecycle(
        Event.WellKnownTypes.SPEC_DISPATCHED,
        nextSpec.id(),
        dispatchedEventData(branchName, background));

    var description = !specBody.isBlank() ? specBody : nextSpec.title();
    var task = AgentTaskPrompt.build(taskSpec, description);

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

    var agentCli = AgentCli.fromYamlName(agentType);
    var workDir = AgentSession.launchWorkDir(sshUser, targetRepos);
    var fullPermissions = true;

    if (!dryRun && SnapshotDecision.shouldSnapshot(snapshot, config, json)) {
      var snapMgr = new SnapshotManager(shell);
      var label = SnapshotManager.defaultLabel();
      SnapshotDecision.create(System.out, snapMgr, name, label, json);
      publishLifecycle(Event.WellKnownTypes.SNAPSHOT_CREATED, null, Map.of("label", label));
    }

    if (branchName != null) {
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

    ensureSailSetup(shell, name);
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
              taskSpec.reasoningEffort(),
              nextSpec.id(),
              agentType);
      if (!json) {
        System.out.println(Ansi.AUTO.string("  @|bold Launching agent in background...|@"));
        if (dryRun) {
          System.out.println(Ansi.AUTO.string("  @|faint " + String.join(" ", sshCmd) + "|@"));
        }
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
        GuardrailWatcher.launchIfConfigured(name, file, config);
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
              taskSpec.reasoningEffort(),
              nextSpec.id(),
              agentType);
      if (!json) {
        System.out.println(Ansi.AUTO.string("  @|bold Launching agent with spec...|@"));
        if (dryRun) {
          System.out.println(Ansi.AUTO.string("  @|faint " + String.join(" ", sshCmd) + "|@"));
        }
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

  /**
   * Outcome of {@link #resolveSpec}: the chosen spec, whether {@code --restart} actually reset a
   * non-pending status (so the caller can publish a {@code spec_restarted} event), and the status
   * the spec held just before the reset.
   *
   * @param spec the resolved spec, or {@code null} when there is no ready spec to dispatch
   * @param restarted {@code true} iff {@code --restart} actually reset a non-pending status
   * @param previousStatus the status the spec held before the reset; {@code null} when {@code
   *     restarted} is {@code false}
   */
  record SpecResolution(Spec spec, boolean restarted, String previousStatus) {
    static SpecResolution none() {
      return new SpecResolution(null, false, null);
    }

    static SpecResolution of(Spec spec) {
      return new SpecResolution(spec, false, null);
    }

    static SpecResolution restarted(Spec spec, String previousStatus) {
      return new SpecResolution(spec, true, previousStatus);
    }
  }

  /** What {@link #prepareDispatch} produces: the resolved spec and its body, both from the DB. */
  record Prepared(SpecResolution resolution, String body) {}

  /**
   * Reads the project's specs from the control-plane database — the source of truth — picks the one
   * to dispatch, marks it {@code in_progress}, and returns it with its body. Returns {@code null}
   * when the project has no specs or none are ready. The container is never read: a stopped or
   * file-drifted project is irrelevant because the spec lives in the DB, replicated by sync.
   */
  private Prepared prepareDispatch(String project, String specId, boolean restart) {
    try (var db = Sqlite.open(SailPaths.controlPlaneDb())) {
      var store = new SpecStore(db);
      var specs = store.projectSpecs(project);
      if (specs.isEmpty()) {
        return null;
      }
      var resolution = resolveSpec(specId, restart, specs, store);
      if (resolution.spec() == null) {
        return null;
      }
      store.updateStatus(resolution.spec().id(), SpecStatus.IN_PROGRESS);
      var body =
          store.getContent(resolution.spec().id()).map(SpecStore.SpecContent::body).orElse("");
      return new Prepared(resolution, body);
    }
  }

  /**
   * Picks the spec to dispatch. Enforces that {@code --spec} only targets pending specs unless
   * {@code --restart} is set, in which case the spec's status is reset to {@code pending} in the
   * DB. Returns a {@link SpecResolution} so the caller can distinguish "picked next pending" from
   * "reset a non-pending spec" and publish the matching lifecycle event (which is the durable audit
   * trail).
   */
  static SpecResolution resolveSpec(
      String specId, boolean restart, List<Spec> specs, SpecStore store) {
    if (specId == null) {
      var next = SpecDirectory.nextReady(specs);
      return next == null ? SpecResolution.none() : SpecResolution.of(next);
    }
    var found = SpecDirectory.findById(specs, specId);
    if (found == null) {
      throw new IllegalArgumentException("Spec '" + specId + "' not found");
    }
    if (found.status() == SpecStatus.PENDING) {
      return SpecResolution.of(found);
    }
    if (!restart) {
      throw new IllegalStateException(
          "Spec '"
              + specId
              + "' is not pending (current status: "
              + found.status().wire()
              + "). A spec is dispatched only when pending. To dispatch it again, pass --restart"
              + " (this resets status to pending and records the restart as a lifecycle event).");
    }
    store.updateStatus(specId, SpecStatus.PENDING);
    return SpecResolution.restarted(found, found.status().wire());
  }

  /**
   * Publishes a lifecycle {@link Event} to the running sail-api so SSE subscribers, webhook
   * reactors, and the audit JSONL all see the same dispatch story regardless of which surface (CLI
   * or HTTP) kicked it off. Failures are non-fatal: a sail-api outage must not block the dispatch
   * itself, since {@code spec.yaml} + {@code audit.jsonl} are the source of truth. Dry-run skips
   * publishing entirely because no real state change happened.
   */
  private void publishLifecycle(String type, String specId, Map<String, Object> data) {
    if (dryRun) {
      return;
    }
    try {
      if (eventPublisher == null) {
        eventPublisher = SailEventPublisher.localDefault();
      }
      eventPublisher.publish(
          Event.of(name, specId, type, Event.SAIL_AGENT, HostInfo.hostname(), data));
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      warnLifecyclePublishFailed(type, e);
    } catch (Exception e) {
      warnLifecyclePublishFailed(type, e);
    }
  }

  private void warnLifecyclePublishFailed(String type, Exception cause) {
    if (json) {
      return;
    }
    System.err.println(
        Banner.errorLine(
            "Could not publish "
                + type
                + " event ("
                + cause.getMessage()
                + "). sail-api may be unreachable; the dispatch itself is unaffected and"
                + " audit.jsonl is authoritative.",
            Ansi.AUTO));
  }

  /**
   * Builds the {@code spec_dispatched} data payload. Order matches the HTTP path in {@code
   * SailApiOperations#publishDispatched} so a downstream consumer sees the same field ordering
   * regardless of which surface initiated the dispatch.
   */
  static Map<String, Object> dispatchedEventData(String branchName, boolean background) {
    var data = new LinkedHashMap<String, Object>();
    if (Strings.isNotBlank(branchName)) {
      data.put("branch", branchName);
    }
    data.put("mode", background ? "background" : "foreground");
    return data;
  }

  private void ensureSailSetup(ShellExecutor shell, String container) {
    try {
      var result = ContainerSailSetup.ensureInstalled(shell, container);
      if (result == ContainerSailSetup.Result.BACKFILLED && !json) {
        System.out.println(
            Ansi.AUTO.string(
                "  @|faint Backfilled sail event helpers in "
                    + container
                    + " (container predates current sail; reinstalled).|@"));
      }
    } catch (Exception e) {
      System.err.println(
          Banner.errorLine(
              "Could not backfill sail event helpers in "
                  + container
                  + ": "
                  + e.getMessage()
                  + ". Lifecycle events may not reach the bus; run 'sail project sync "
                  + container
                  + "' to retry.",
              Ansi.AUTO));
    }
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

  record DispatchPreview(String name, String specId, String specTitle, String mode, String task) {}

  record NoDispatch(String name, boolean dispatched, String reason) {}
}
