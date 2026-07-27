/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.commands;

import ai.singlr.sail.api.Actor;
import ai.singlr.sail.api.DispatchOperations;
import ai.singlr.sail.api.Event;
import ai.singlr.sail.api.SailEventPublisher;
import ai.singlr.sail.api.StopOperations;
import ai.singlr.sail.api.SyncScheduler;
import ai.singlr.sail.config.YamlUtil;
import ai.singlr.sail.engine.Banner;
import ai.singlr.sail.engine.NameValidator;
import ai.singlr.sail.engine.SailPaths;
import ai.singlr.sail.engine.ShellExec;
import ai.singlr.sail.engine.ShellExecutor;
import ai.singlr.sail.store.RunStore;
import ai.singlr.sail.store.SpecStore;
import ai.singlr.sail.store.Sqlite;
import java.util.LinkedHashMap;
import java.util.Objects;
import picocli.CommandLine.Command;
import picocli.CommandLine.Help.Ansi;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Spec;

/**
 * Stops the project's running agent through the shared {@link StopOperations} lane — the same
 * executor behind {@code POST /v1/runs/{id}/stop} — so a CLI stop records the operator's terminal
 * intent (spec {@code cancelled}, run {@code stopped}) before the process is signalled, exactly
 * like an API stop. Pure presentation: this command resolves nothing and kills nothing itself.
 */
@Command(
    name = "stop",
    description = "Stop a running agent session and cancel its spec.",
    mixinStandardHelpOptions = true)
public final class AgentStopCommand implements Runnable {

  @Parameters(
      index = "0",
      arity = "0..1",
      description = "Project name (default: the current project).")
  private String name;

  @Option(names = "--dry-run", description = "Print what would be stopped instead of stopping.")
  private boolean dryRun;

  @Option(names = "--json", description = "Output in JSON format.")
  private boolean json;

  @Mixin private SyncOptions syncOptions;

  @Spec private CommandSpec spec;

  private SailEventPublisher eventPublisher;

  @Override
  public void run() {
    CliCommand.run(spec, this::execute);
  }

  private void execute() throws Exception {
    name = CurrentProject.require(name);
    NameValidator.requireValidProjectName(name);
    try (var sync = NodeSync.scheduler(syncOptions.noSync())) {
      execute(sync);
    }
  }

  private void execute(SyncScheduler sync) throws Exception {
    sync.freshenRead();
    var shell = new ShellExecutor(false);
    var handle = Objects.toString(HostSync.handle(), "");
    try (var db = Sqlite.open(SailPaths.controlPlaneDb())) {
      var operations = operations(db, shell, this::publishLifecycle, listener());
      var outcome =
          operations.stop(
              new StopOperations.ProjectTarget(name), Actor.cliOperator(handle), handle, dryRun);
      render(outcome);
      if (!dryRun && outcome.mutated()) {
        sync.syncNow();
      }
    }
  }

  /**
   * The CLI lane's wiring of the shared stop executor: both stores come from the one control-plane
   * database, so an in-process stop records exactly what a server-lane stop would.
   */
  static StopOperations operations(
      Sqlite db,
      ShellExec shell,
      DispatchOperations.EventSink events,
      StopOperations.Listener listener) {
    return new StopOperations(
        shell,
        SailPaths.PROJECT_DESCRIPTOR,
        new SpecStore(db),
        new RunStore(db),
        events,
        StopOperations.sessionHalter(shell),
        listener);
  }

  private StopOperations.Listener listener() {
    return new StopOperations.Listener() {
      @Override
      public void halting(String project, String unit, Integer pid) {
        if (json) {
          return;
        }
        var verb = dryRun ? "Would stop" : "Stopping";
        System.out.println(
            Ansi.AUTO.string(
                "  @|bold "
                    + verb
                    + ":|@ agent PID "
                    + pid
                    + " (unit "
                    + unit
                    + ") in "
                    + project));
      }
    };
  }

  private void render(StopOperations.Outcome outcome) {
    if (json) {
      System.out.println(YamlUtil.dumpJson(jsonView(outcome)));
      return;
    }
    switch (outcome) {
      case StopOperations.Stopped stopped -> {
        System.out.println(
            Ansi.AUTO.string(
                "  @|green ✓|@ Agent "
                    + (dryRun ? "would be stopped" : "stopped")
                    + " (PID "
                    + stopped.pid()
                    + ") in "
                    + name));
        printCancelled(stopped.specId(), stopped.specCancelled());
      }
      case StopOperations.NotRunning notRunning -> {
        System.out.println(
            Ansi.AUTO.string("  @|faint No running agent session for " + name + ".|@"));
        if (notRunning.runReleased()) {
          System.out.println(
              Ansi.AUTO.string("  @|green ✓|@ Released stranded run " + notRunning.runId() + "."));
        }
        printCancelled(notRunning.specId(), notRunning.specCancelled());
      }
      case StopOperations.AlreadyTerminal terminal ->
          System.out.println(
              Ansi.AUTO.string(
                  "  @|faint Run "
                      + terminal.runId()
                      + " is already "
                      + terminal.runStatus()
                      + "; nothing to stop.|@"));
    }
  }

  private void printCancelled(String specId, boolean cancelled) {
    if (!cancelled) {
      return;
    }
    System.out.println(
        Ansi.AUTO.string(
            "  @|green ✓|@ Spec " + specId + (dryRun ? " would be cancelled." : " cancelled.")));
  }

  private LinkedHashMap<String, Object> jsonView(StopOperations.Outcome outcome) {
    var map = new LinkedHashMap<String, Object>();
    map.put("name", name);
    map.put("dry_run", dryRun);
    switch (outcome) {
      case StopOperations.Stopped stopped -> {
        map.put("stopped", true);
        map.put("pid", stopped.pid());
        putRunAndSpec(map, stopped.runId(), stopped.specId(), stopped.specCancelled());
      }
      case StopOperations.NotRunning notRunning -> {
        map.put("stopped", false);
        map.put("reason", notRunning.reason());
        map.put("run_released", notRunning.runReleased());
        putRunAndSpec(map, notRunning.runId(), notRunning.specId(), notRunning.specCancelled());
      }
      case StopOperations.AlreadyTerminal terminal -> {
        map.put("stopped", false);
        map.put("reason", terminal.reason());
        map.put("run_status", terminal.runStatus());
        putRunAndSpec(map, terminal.runId(), terminal.specId(), false);
      }
    }
    return map;
  }

  private static void putRunAndSpec(
      LinkedHashMap<String, Object> map, String runId, String specId, boolean cancelled) {
    if (runId != null) {
      map.put("run_id", runId);
    }
    if (specId != null) {
      map.put("spec_id", specId);
    }
    map.put("spec_cancelled", cancelled);
  }

  /**
   * Publishes the cancel {@link Event} to the running sail-api so SSE subscribers and the audit
   * trail see the same stop story regardless of surface. Failures are non-fatal: the control-plane
   * database already holds the terminal state, so an unreachable sail-api must not fail the stop.
   */
  private void publishLifecycle(Event event) {
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
                + "). sail-api may be unreachable; the stop itself is unaffected.",
            Ansi.AUTO));
  }
}
