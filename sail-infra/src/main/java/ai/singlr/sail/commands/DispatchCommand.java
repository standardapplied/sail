/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.commands;

import ai.singlr.sail.api.ApiException;
import ai.singlr.sail.api.DispatchOperations;
import ai.singlr.sail.api.Event;
import ai.singlr.sail.api.HostOperations;
import ai.singlr.sail.api.OperationHooks;
import ai.singlr.sail.api.OperationsFactory;
import ai.singlr.sail.api.SailEventPublisher;
import ai.singlr.sail.api.SailOperations;
import ai.singlr.sail.api.SessionYield;
import ai.singlr.sail.api.StopOperations;
import ai.singlr.sail.api.SyncScheduler;
import ai.singlr.sail.common.Strings;
import ai.singlr.sail.config.Spec;
import ai.singlr.sail.engine.Banner;
import ai.singlr.sail.engine.GuardrailWatcher;
import ai.singlr.sail.engine.NameValidator;
import ai.singlr.sail.engine.OperatorCommand;
import ai.singlr.sail.engine.RunPlace;
import ai.singlr.sail.engine.ShellExec;
import ai.singlr.sail.engine.ShellExecutor;
import ai.singlr.sail.engine.WatcherSpawner;
import ai.singlr.sail.engine.WorkspaceTemplate;
import ai.singlr.sail.engine.Workspaces;
import ai.singlr.sail.identity.Actor;
import ai.singlr.sail.store.Sqlite;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Supplier;
import picocli.CommandLine.Command;
import picocli.CommandLine.Help.Ansi;
import picocli.CommandLine.Mixin;
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
              + " the one set by project switch).")
  private String project;

  private String name;

  @Option(
      names = "--spec",
      description = "Override auto-selection: dispatch a specific spec by ID.")
  private String specId;

  @Option(
      names = "--background",
      negatable = true,
      defaultValue = "true",
      fallbackValue = "true",
      description =
          "Run the agent in the background (the default). Use --no-background to run it in this"
              + " terminal.")
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
      names = "--restart",
      description =
          "Re-dispatch a spec onto its work branch when that already exists. Requires --spec. A"
              + " spec whose status is not 'pending' is reset to pending, recording a 'restarted'"
              + " lifecycle event, before dispatching.")
  private boolean restart;

  @Mixin private SyncOptions syncOptions;

  private final Function<Boolean, ShellExec> shells;
  private final BiFunction<ShellExec, OperationHooks, HostOperations> operations;
  private final Supplier<String> handle;
  private SailEventPublisher eventPublisher;

  public DispatchCommand() {
    this(
        ShellExecutor::new,
        (shell, hooks) -> OperationsFactory.open(shell, hooks, new PtyHostYield()),
        HostSync::handle);
  }

  /**
   * @param shells the host shell, by whether this is a dry run
   * @param operations this box's operations over a shell, running a dispatch's hooks
   * @param handle this box's FDE handle, or null when unset
   */
  DispatchCommand(
      Function<Boolean, ShellExec> shells,
      BiFunction<ShellExec, OperationHooks, HostOperations> operations,
      Supplier<String> handle) {
    this.shells = shells;
    this.operations = operations;
    this.handle = handle;
  }

  @Override
  public void run() {
    CliCommand.run(this::execute);
  }

  private void execute() throws Exception {
    var operator = OperatorCommand.here();
    name = CurrentProject.require(project);
    NameValidator.requireValidProjectName(name);
    try (var sync = NodeSync.scheduler(syncOptions.noSync(), operator)) {
      execute(sync, operator);
    }
  }

  private void execute(SyncScheduler sync, OperatorCommand operator) throws Exception {
    sync.freshenRead();
    if (!json) {
      Banner.printBranding(System.out, Ansi.AUTO);
    }

    var shell = shells.apply(dryRun);
    var localHandle = Objects.toString(handle.get(), "");
    var request =
        new DispatchOperations.Request(
            specId, background ? "background" : "foreground", dryRun, repoOverrides, restart);
    try (var operations =
        this.operations.apply(
            shell,
            new OperationHooks(
                this::publishLifecycle,
                new WatcherSpawner(shell, WatcherSpawner::spawnProcess),
                Workspaces.onThisBox(shell),
                DispatchOperations.terminalLauncher(),
                renderer(sync, operator),
                StopOperations.Listener.NONE))) {
      render(dispatchAsOperator(operations, name, request, localHandle), shell, operator);
    }
  }

  /**
   * The CLI lane's wiring of the shared dispatch executor: every store comes from the one
   * control-plane database — spec claims, run rows, and the FDE roster guard included — so an
   * in-process dispatch records exactly what a server-lane dispatch would.
   */
  static SailOperations operations(
      Sqlite db,
      ShellExec shell,
      DispatchOperations.EventSink events,
      WatcherSpawner watcherSpawner,
      Workspaces workspaces,
      DispatchOperations.AgentLauncher launcher,
      DispatchOperations.Listener listener,
      SessionYield sessionYield,
      OperatorCommand operator) {
    return OperationsFactory.create(
        db,
        shell,
        new OperationHooks(
            events, watcherSpawner, workspaces, launcher, listener, StopOperations.Listener.NONE),
        sessionYield);
  }

  /**
   * Dispatches {@code project}'s spec as this box's operator, rendering a refusal, the operator's
   * own included, with its fix for the terminal.
   */
  static DispatchOperations.Outcome dispatchAsOperator(
      HostOperations operations,
      String project,
      DispatchOperations.Request request,
      String handle) {
    try {
      return Actor.call(
          operations.identity().operator(),
          () -> operations.dispatching().dispatch(project, request, handle));
    } catch (ApiException e) {
      throw new IllegalStateException(CliCommand.describe(e), e);
    }
  }

  private void render(
      DispatchOperations.Outcome outcome, ShellExec shell, OperatorCommand operator) {
    switch (outcome) {
      case DispatchOperations.NoSpecs ignored -> printNoSpecs();
      case DispatchOperations.Dispatched dispatched when dryRun -> printDryRun(dispatched);
      case DispatchOperations.Dispatched dispatched -> {
        if (!background) {
          if (dispatched.exitCode() != null && dispatched.exitCode() != 0) {
            throw AgentLogCommand.agentExited(
                shell,
                RunPlace.of(name, dispatched.taskSpec().id()),
                dispatched.runId(),
                dispatched.exitCode());
          }
          return;
        }
        Banner.printAgentLaunched(
            name, dispatched.task(), dispatched.branch(), operator, System.out, Ansi.AUTO);
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
   * What a real dispatch would do, said in the conditional, and that nothing was changed: the spec,
   * the agent and lane, the workspace it would make or reuse, the branch and whether it would be
   * created, and whether the spec would be restarted. No launch banner and no command for a run
   * that does not exist.
   */
  private void printDryRun(DispatchOperations.Dispatched dispatched) {
    var spec = dispatched.taskSpec();
    var mode = background ? "background" : "foreground";
    if (json) {
      System.out.println(
          CliJson.stringify(
              new DryDispatch(
                  true,
                  name,
                  spec.id(),
                  spec.title(),
                  mode,
                  dispatched.agentType(),
                  dispatched.branch(),
                  dispatched.branchCreated(),
                  dispatched.restarted(),
                  instanceOf(dispatched.workspace()),
                  dispatched.workspace() != null && dispatched.workspace().reused(),
                  dispatched.task())));
      return;
    }
    var branch =
        Strings.isBlank(dispatched.branch())
            ? "with no work branch"
            : "on branch "
                + dispatched.branch()
                + (dispatched.branchCreated()
                    ? ", which it would create"
                    : ", which it would check out");
    System.out.println(Ansi.AUTO.string("  @|bold Dry run:|@ nothing was changed."));
    System.out.println(
        "  Would dispatch spec "
            + spec.id()
            + " ("
            + spec.title()
            + ") to "
            + dispatched.agentType()
            + " in the "
            + mode
            + ", "
            + branch
            + ".");
    if (dispatched.workspace() != null) {
      var workspace = dispatched.workspace();
      System.out.println(
          "  Would "
              + (workspace.reused() ? "reuse" : "make")
              + " workspace "
              + workspace.workspace()
              + " ("
              + workspace.instance()
              + ")"
              + (workspace.stale() ? ", from a template older than the definition." : "."));
    }
    if (dispatched.restarted()) {
      System.out.println("  Would restart the spec first: its status would go back to pending.");
    }
  }

  private static String instanceOf(DispatchOperations.WorkspaceUse workspace) {
    return workspace == null ? null : workspace.instance();
  }

  /** What a dispatch says of the workspace its run went into. */
  static String workspaceLine(DispatchOperations.WorkspaceUse workspace) {
    return workspace.reused()
        ? "Workspace " + workspace.workspace() + " reused"
        : "Workspace "
            + workspace.workspace()
            + " made in "
            + String.format(Locale.ROOT, "%.1f", workspace.timings().totalMs() / 1000.0)
            + " s";
  }

  /**
   * Renders the executor's progress for the terminal, and — the claim being the moment the spec
   * flips {@code in_progress} — pushes the claim to main synchronously so a short-lived CLI process
   * never exits before its most important write propagates. A dry run claimed nothing: it pushes
   * nothing and says what it would do once it is done ({@link #printDryRun}).
   */
  private DispatchOperations.Listener renderer(SyncScheduler sync, OperatorCommand operator) {
    var entered = new AtomicReference<DispatchOperations.WorkspaceUse>();
    return new DispatchOperations.Listener() {
      @Override
      public void workspaceEntered(DispatchOperations.WorkspaceUse workspace) {
        entered.set(workspace);
        if (json) {
          return;
        }
        System.out.println(Ansi.AUTO.string("  @|green ✓|@ " + workspaceLine(workspace)));
        if (workspace.stale()) {
          System.out.println(
              Ansi.AUTO.string(
                  "  @|yellow !|@ " + operator.render(new WorkspaceTemplate.Stale(name))));
        }
      }

      @Override
      public void claimed(Spec taskSpec, String task) {
        if (dryRun) {
          return;
        }
        sync.syncNow();
        if (json) {
          System.out.println(
              CliJson.stringify(
                  new DispatchPreview(
                      false,
                      name,
                      taskSpec.id(),
                      taskSpec.title(),
                      background ? "background" : "foreground",
                      task,
                      entered.get())));
          return;
        }
        System.out.println(Ansi.AUTO.string("  @|bold Dispatching spec:|@ " + taskSpec.id()));
        System.out.println(Ansi.AUTO.string("  @|faint " + taskSpec.title() + "|@"));
        System.out.println();
      }

      @Override
      public void branchReady(String branch, String repoPath, boolean reused) {
        if (!json) {
          Banner.printBranchReady(branch, repoPath, reused, System.out, Ansi.AUTO);
        }
      }

      @Override
      public void branchUnavailable(String branch) {
        if (!json) {
          Banner.printBranchUnavailable(branch, System.out, Ansi.AUTO);
        }
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

  /** What a dispatch says when it claims its spec, with the workspace its run went into. */
  record DispatchPreview(
      boolean dryRun,
      String name,
      String specId,
      String specTitle,
      String mode,
      String task,
      DispatchOperations.WorkspaceUse workspace) {}

  /** What a dry run says, in JSON: what a real dispatch would do. */
  record DryDispatch(
      boolean dryRun,
      String name,
      String specId,
      String specTitle,
      String mode,
      String agent,
      String branch,
      boolean wouldCreateBranch,
      boolean wouldRestart,
      String workspace,
      boolean wouldReuseWorkspace,
      String task) {}

  record NoDispatch(String name, boolean dispatched, String reason) {}
}
