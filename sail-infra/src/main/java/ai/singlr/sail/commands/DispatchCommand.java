/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.commands;

import ai.singlr.sail.api.Actor;
import ai.singlr.sail.api.ApiException;
import ai.singlr.sail.api.DispatchOperations;
import ai.singlr.sail.api.Event;
import ai.singlr.sail.api.SailEventPublisher;
import ai.singlr.sail.api.SyncScheduler;
import ai.singlr.sail.common.Strings;
import ai.singlr.sail.config.Spec;
import ai.singlr.sail.engine.Banner;
import ai.singlr.sail.engine.GuardrailWatcher;
import ai.singlr.sail.engine.NameValidator;
import ai.singlr.sail.engine.SailPaths;
import ai.singlr.sail.engine.ShellExec;
import ai.singlr.sail.engine.ShellExecutor;
import ai.singlr.sail.engine.SnapshotManager;
import ai.singlr.sail.engine.WatcherSpawner;
import ai.singlr.sail.store.FdeStore;
import ai.singlr.sail.store.ReviewStore;
import ai.singlr.sail.store.RunStore;
import ai.singlr.sail.store.SpecStore;
import ai.singlr.sail.store.Sqlite;
import java.util.List;
import java.util.Objects;
import picocli.CommandLine.Command;
import picocli.CommandLine.Help.Ansi;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;

/**
 * Dispatches the next ready spec to an agent for autonomous execution. Pure presentation: parses
 * flags, invokes the shared {@link DispatchOperations} executor in-process against the
 * control-plane database (the same executor the HTTP API delegates to), and renders the outcome —
 * banners, progress lines, {@code --json}. The procedure itself — resolve, policy, claim, branch,
 * launch, run record, watcher, events — lives in one place for every lane.
 */
@Command(
    name = "dispatch",
    description = "Dispatch the next ready spec to an agent for autonomous execution.",
    mixinStandardHelpOptions = true)
public final class DispatchCommand implements Runnable {

  @Option(
      names = {"-p", "--project"},
      description =
          "Project whose container runs the agent (default: the current project, inferred from"
              + " cwd's sail.yaml or 'sail project switch').")
  private String project;

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

  @picocli.CommandLine.Mixin private SyncOptions syncOptions;

  @picocli.CommandLine.Spec private CommandSpec commandSpec;

  private SailEventPublisher eventPublisher;

  @Override
  public void run() {
    CliCommand.run(commandSpec, this::execute);
  }

  private void execute() throws Exception {
    name = CurrentProject.require(project);
    NameValidator.requireValidProjectName(name);
    try (var sync = NodeSync.scheduler(syncOptions.noSync())) {
      execute(sync);
    }
  }

  private void execute(SyncScheduler sync) throws Exception {
    sync.freshenRead();
    if (!json) {
      Banner.printBranding(System.out, Ansi.AUTO);
    }

    var shell = new ShellExecutor(dryRun);
    var handle = Objects.toString(HostSync.handle(), "");
    var request =
        new DispatchOperations.Request(
            specId, background ? "background" : "foreground", dryRun, repoOverrides, restart);
    try (var db = Sqlite.open(SailPaths.controlPlaneDb())) {
      var operations =
          operations(
              db,
              shell,
              file,
              this::publishLifecycle,
              new WatcherSpawner(shell, WatcherSpawner::spawnProcess),
              snapshotter(shell),
              DispatchOperations.terminalLauncher(),
              renderer(sync));
      render(dispatch(operations, request, handle));
    }
  }

  /**
   * The CLI lane's wiring of the shared dispatch executor: every store comes from the one
   * control-plane database — spec claims, run rows, and the FDE roster guard included — so an
   * in-process dispatch records exactly what a server-lane dispatch would.
   */
  static DispatchOperations operations(
      Sqlite db,
      ShellExec shell,
      String file,
      DispatchOperations.EventSink events,
      WatcherSpawner watcherSpawner,
      DispatchOperations.Snapshotter snapshotter,
      DispatchOperations.AgentLauncher launcher,
      DispatchOperations.Listener listener) {
    return new DispatchOperations(
        shell,
        file,
        new SpecStore(db),
        new ReviewStore(db),
        new RunStore(db),
        new FdeStore(db),
        events,
        watcherSpawner,
        snapshotter,
        launcher,
        listener);
  }

  private DispatchOperations.Outcome dispatch(
      DispatchOperations operations, DispatchOperations.Request request, String handle) {
    try {
      return operations.dispatch(name, request, Actor.cliOperator(handle), handle);
    } catch (ApiException e) {
      throw new IllegalStateException(errorText(e), e);
    }
  }

  /** A structured refusal rendered for the terminal: the reason and, when known, the fix. */
  private static String errorText(ApiException e) {
    var action = e.failure().action();
    return Strings.isBlank(action) ? e.getMessage() : e.getMessage() + " " + action;
  }

  private void render(DispatchOperations.Outcome outcome) {
    switch (outcome) {
      case DispatchOperations.NoSpecs ignored -> printNoSpecs();
      case DispatchOperations.Dispatched dispatched -> {
        if (!background) {
          if (dispatched.exitCode() != null && dispatched.exitCode() != 0) {
            System.err.println(
                Banner.errorLine(
                    "Agent session exited with code " + dispatched.exitCode(), Ansi.AUTO));
          }
          return;
        }
        Banner.printAgentLaunched(
            name, dispatched.task(), dispatched.branch(), System.out, Ansi.AUTO);
        dispatched
            .watcher()
            .ifPresent(
                spawned ->
                    System.out.println(
                        Ansi.AUTO.string(
                            "  @|green ✓|@ "
                                + GuardrailWatcher.describe(
                                    spawned,
                                    WatcherSpawner.watchLogForRun(name, dispatched.runId())))));
      }
    }
  }

  /**
   * The CLI lane's snapshot decision: fully per-dispatch opt-in via {@code --snapshot} / {@code
   * --no-snapshot}, prompting interactively when neither is passed, and never on a dry run.
   */
  private DispatchOperations.Snapshotter snapshotter(ShellExecutor shell) {
    return (project, config) -> {
      if (dryRun || !SnapshotDecision.shouldSnapshot(snapshot, config, json)) {
        return "";
      }
      var snapMgr = new SnapshotManager(shell);
      var label = SnapshotManager.defaultLabel();
      try {
        SnapshotDecision.create(System.out, snapMgr, name, label, json);
      } catch (Exception e) {
        throw new IllegalStateException("Failed to create snapshot: " + e.getMessage(), e);
      }
      return label;
    };
  }

  /**
   * Renders the executor's progress for the terminal, and — the claim being the moment the spec
   * flips {@code in_progress} — pushes the claim to main synchronously so a short-lived CLI process
   * never exits before its most important write propagates.
   */
  private DispatchOperations.Listener renderer(SyncScheduler sync) {
    return new DispatchOperations.Listener() {
      @Override
      public void claimed(Spec taskSpec, String task) {
        sync.syncNow();
        if (json) {
          System.out.println(
              CliJson.stringify(
                  new DispatchPreview(
                      name,
                      taskSpec.id(),
                      taskSpec.title(),
                      background ? "background" : "foreground",
                      task)));
          return;
        }
        System.out.println(Ansi.AUTO.string("  @|bold Dispatching spec:|@ " + taskSpec.id()));
        System.out.println(Ansi.AUTO.string("  @|faint " + taskSpec.title() + "|@"));
        System.out.println();
      }

      @Override
      public void branchReady(String branch, String repoPath, boolean reused) {
        if (json) {
          return;
        }
        var verb = reused ? "Reusing branch:" : "Creating branch:";
        System.out.println(
            Ansi.AUTO.string("  @|bold " + verb + "|@ " + branch + " in " + repoPath));
        System.out.println(Ansi.AUTO.string("  @|green ✓|@ Branch " + branch + " in " + repoPath));
        System.out.println();
      }

      @Override
      public void branchUnavailable(String branch) {
        if (json) {
          return;
        }
        System.out.println(
            Ansi.AUTO.string("  @|faint Branch:|@ " + branch + " (create manually in repo)"));
        System.out.println();
      }

      @Override
      public void launching(boolean bg, List<String> command) {
        if (json) {
          return;
        }
        System.out.println(
            Ansi.AUTO.string(
                bg
                    ? "  @|bold Launching agent in background...|@"
                    : "  @|bold Launching agent with spec...|@"));
        System.out.println();
      }

      @Override
      public void runsPruned(int count) {
        if (count > 0 && !json) {
          System.out.println(
              Ansi.AUTO.string("  @|faint Pruned " + count + " old run log(s) in " + name + ".|@"));
        }
      }

      @Override
      public void sailSetupBackfilled(boolean backfilled) {
        if (backfilled && !json) {
          System.out.println(
              Ansi.AUTO.string(
                  "  @|faint Backfilled sail event helpers in "
                      + name
                      + " (container predates current sail; reinstalled).|@"));
        }
      }
    };
  }

  /**
   * Publishes a lifecycle {@link Event} to the running sail-api so SSE subscribers, webhook
   * reactors, and the audit JSONL all see the same dispatch story regardless of which surface (CLI
   * or HTTP) kicked it off. Failures are non-fatal: a sail-api outage must not block the dispatch
   * itself, since the control-plane database (the spec's status is already persisted there) is the
   * source of truth. Dry-run skips publishing entirely because no real state change happened.
   */
  private void publishLifecycle(Event event) {
    if (dryRun) {
      return;
    }
    try {
      if (eventPublisher == null) {
        eventPublisher = SailEventPublisher.localDefault();
      }
      eventPublisher.publish(event);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      warnLifecyclePublishFailed(event.type(), e);
    } catch (Exception e) {
      warnLifecyclePublishFailed(event.type(), e);
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

  private void printNoSpecs() {
    if (json) {
      System.out.println(CliJson.stringify(new NoDispatch(name, false, "no_pending_specs")));
    } else {
      System.out.println(Ansi.AUTO.string("  @|faint No pending specs found for " + name + ".|@"));
    }
  }

  record DispatchPreview(String name, String specId, String specTitle, String mode, String task) {}

  record NoDispatch(String name, boolean dispatched, String reason) {}
}
