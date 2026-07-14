/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.sail.config.ReviewPipelineConfig;
import ai.singlr.sail.config.SpecStatus;
import ai.singlr.sail.engine.AbstractIncusIT;
import ai.singlr.sail.engine.AgentSession;
import ai.singlr.sail.engine.AgentUnit;
import ai.singlr.sail.engine.ContainerFilePush;
import ai.singlr.sail.engine.WatcherSpawner;
import ai.singlr.sail.store.FdeStore;
import ai.singlr.sail.store.ReviewStore;
import ai.singlr.sail.store.RunStore;
import ai.singlr.sail.store.SchemaManager;
import ai.singlr.sail.store.SpecStore;
import ai.singlr.sail.store.Sqlite;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Two specs on disjoint repos dispatched into <strong>one real incus container</strong>: both
 * agents run concurrently under their own {@code sail-agent-<runId>} units and run-scoped files,
 * both stop, both reviews fire serially through the pipeline, and the event log carries two
 * distinct run lifecycles. A fake {@code codex} binary stands in for the agent (sleeps, then exits)
 * and for the reviewer (clean findings) — no LLM, no API key. Runs only under the {@code
 * integration} profile against a real incus daemon; skips elsewhere.
 */
class ConcurrentDispatchIT extends AbstractIncusIT {

  private static final String CONTAINER = "sail-it-concurrent-dispatch";
  private static final String HANDLE = "it-node";
  private static final Actor OPERATOR = Actor.cliOperator(HANDLE);

  private static final String FAKE_AGENT =
      """
      #!/usr/bin/env bash
      case "$*" in
        *"Output your findings"*) printf '[]\\n' ;;
        *) sleep 10; echo build-done ;;
      esac
      """;

  private Path stateDir;

  @BeforeEach
  void provision() throws Exception {
    ensureIncusOrSkip();
    launch(CONTAINER);
    var setup =
        exec(
            CONTAINER,
            List.of(
                "bash",
                "-c",
                "userdel -r ubuntu 2>/dev/null || true;"
                    + " id -u dev >/dev/null 2>&1 || useradd -m -u 1000 -s /bin/bash dev;"
                    + " mkdir -p /home/dev/.sail /home/dev/workspace;"
                    + " chown -R dev:dev /home/dev;"
                    + " loginctl enable-linger dev"));
    assertTrue(setup.ok(), "container provisioning failed: " + setup.stderr());
    awaitUserManager();
    ContainerFilePush.push(
        shell, CONTAINER, "/usr/local/bin/codex", FAKE_AGENT, List.of("--mode", "0755"));
    stateDir = Files.createTempDirectory("concurrent-dispatch-it");
  }

  @AfterEach
  void cleanup() {
    deleteContainerQuietly(CONTAINER);
    if (stateDir != null) {
      deleteRecursively(stateDir);
    }
  }

  @Test
  void twoDisjointRepoSpecsRunStopAndReviewInOneContainer() throws Exception {
    var yaml = stateDir.resolve("sail.yaml");
    Files.writeString(
        yaml,
        """
        name: %s
        ssh:
          user: dev
        repos:
          - url: https://example.invalid/app.git
            path: app
          - url: https://example.invalid/web.git
            path: web
        agent:
          type: codex
          specs_dir: specs
        """
            .formatted(CONTAINER));
    try (var db = Sqlite.open(stateDir.resolve("it.db"))) {
      new SchemaManager(db).migrate();
      var specStore = new SpecStore(db);
      var reviewStore = new ReviewStore(db);
      var runStore = new RunStore(db);
      new FdeStore(db).add(HANDLE, null, null, "admin");
      seedSpec(specStore, "spec-app", List.of("app"));
      seedSpec(specStore, "spec-web", List.of("web"));
      var events = new CopyOnWriteArrayList<Event>();
      var dispatchOps =
          new DispatchOperations(
              shell,
              yaml.toString(),
              specStore,
              reviewStore,
              runStore,
              new FdeStore(db),
              events::add,
              new WatcherSpawner(refusingShell(), (command, logPath) -> 4242L),
              (project, config) -> "",
              DispatchOperations.shellLauncher(shell),
              DispatchOperations.Listener.NONE);

      var first = dispatchBackground(dispatchOps, "spec-app");
      var second = dispatchBackground(dispatchOps, "spec-web");

      var runs = runStore.listForProject(CONTAINER);
      assertEquals(2, runs.size(), "both dispatches recorded their runs");
      assertTrue(
          runs.stream().allMatch(run -> "running".equals(run.status())),
          "the second dispatch was admitted while the first agent was still running");
      assertNotEquals(
          runs.get(0).unit(), runs.get(1).unit(), "each run owns its own recorded unit");
      assertEquals(
          Set.copyOf(List.of("sail-agent-" + first.runId(), "sail-agent-" + second.runId())),
          Set.copyOf(List.of(runs.get(0).unit(), runs.get(1).unit())));

      awaitExit(first);
      awaitExit(second);
      assertTrue(runLog(first).contains("build-done"), "run A's agent executed and logged");
      assertTrue(runLog(second).contains("build-done"), "run B's agent executed and logged");

      var config =
          ReviewPipelineConfig.fromMap(
              Map.of(
                  "max_iterations",
                  3,
                  "stages",
                  List.of(
                      Map.of(
                          "name", "security",
                          "type", "agent",
                          "agent", "codex",
                          "gate", "no_critical"))));
      var controller =
          new ReviewPipelineController(
              specStore,
              reviewStore,
              p -> config,
              p -> "codex",
              new ContainerReviewAgentRunner(shell),
              null,
              () -> {},
              new DirectExecutorService());
      var tracker = new RunTracker(runStore, SyncScheduler.disabled(), () -> HANDLE);

      for (var dispatched : List.of(first, second)) {
        var stop = stopEventFor(dispatched);
        events.add(stop);
        tracker.onEvent(stop);
        controller.onEvent(stop);
      }

      assertEquals(
          SpecStatus.AWAITING_MERGE, specStore.findById("spec-app").orElseThrow().status());
      assertEquals(
          SpecStatus.AWAITING_MERGE, specStore.findById("spec-web").orElseThrow().status());
      assertTrue(
          runStore.listForProject(CONTAINER).stream()
              .allMatch(run -> "stopped".equals(run.status())),
          "both runs reach their terminal state");
      assertEquals(1, reviewStore.reviewsForSpec("spec-app").size());
      assertEquals(1, reviewStore.reviewsForSpec("spec-web").size());
      assertLifecycle(events, first.runId(), "spec-app");
      assertLifecycle(events, second.runId(), "spec-web");
    }
  }

  private DispatchOperations.Dispatched dispatchBackground(DispatchOperations ops, String specId) {
    var outcome =
        ops.dispatch(
            CONTAINER,
            new DispatchOperations.Request(specId, "background", false, null, false),
            OPERATOR,
            HANDLE);
    return assertInstanceOf(DispatchOperations.Dispatched.class, outcome);
  }

  private void awaitExit(DispatchOperations.Dispatched dispatched) throws Exception {
    var session = new AgentSession(shell);
    var unit = AgentUnit.forRun(dispatched.runId());
    var deadline = Instant.now().plus(Duration.ofSeconds(90));
    while (Instant.now().isBefore(deadline)) {
      if (!session.queryExitStatus(CONTAINER, unit).active()) {
        return;
      }
      Thread.sleep(Duration.ofSeconds(2));
    }
    throw new AssertionError("agent unit " + unit.unitName() + " never exited");
  }

  private String runLog(DispatchOperations.Dispatched dispatched) throws Exception {
    var result = exec(CONTAINER, List.of("cat", AgentUnit.forRun(dispatched.runId()).logPath()));
    return result.ok() ? result.stdout() : "";
  }

  private Event stopEventFor(DispatchOperations.Dispatched dispatched) {
    var data = new LinkedHashMap<String, Object>();
    data.put(Event.WellKnownData.EXIT_CODE, 0);
    data.put(Event.WellKnownData.SOURCE, Event.WellKnownData.SOURCE_WATCHER);
    data.put(Event.WellKnownData.RUN_ID, dispatched.runId());
    return Event.of(
        CONTAINER,
        dispatched.taskSpec().id(),
        Event.WellKnownTypes.AGENT_SESSION_STOPPED,
        "codex",
        "it-host",
        data);
  }

  private static void assertLifecycle(List<Event> events, String runId, String specId) {
    assertTrue(
        events.stream()
            .anyMatch(
                e ->
                    Event.WellKnownTypes.SPEC_DISPATCHED.equals(e.type())
                        && specId.equals(e.spec())),
        "spec_dispatched recorded for " + specId);
    assertTrue(
        events.stream()
            .anyMatch(
                e ->
                    Event.WellKnownTypes.AGENT_SESSION_STARTED.equals(e.type())
                        && runId.equals(Objects.toString(e.data().get(Event.WellKnownData.RUN_ID)))
                        && specId.equals(e.spec())),
        "agent_session_started carries " + specId + "'s own run id");
    assertTrue(
        events.stream()
            .anyMatch(
                e ->
                    Event.WellKnownTypes.AGENT_SESSION_STOPPED.equals(e.type())
                        && runId.equals(
                            Objects.toString(e.data().get(Event.WellKnownData.RUN_ID)))),
        "the stop addresses " + specId + "'s exact run");
  }

  private static void seedSpec(SpecStore store, String id, List<String> repos) {
    store.create(
        new SpecStore.SpecRow(
            id,
            CONTAINER,
            "Title " + id,
            SpecStatus.PENDING,
            HANDLE,
            "codex",
            null,
            null,
            null,
            0,
            HANDLE,
            null,
            null,
            HANDLE,
            List.of(),
            repos));
    store.setContent(id, "Do " + id, "");
  }

  /**
   * A shell that refuses every command: the watcher spawner falls straight to its fake process
   * fallback instead of launching host-side systemd units the CI runner would have to clean up.
   */
  private static ai.singlr.sail.engine.ShellExec refusingShell() {
    return new ai.singlr.sail.engine.ShellExec() {
      @Override
      public Result exec(List<String> command) {
        return new Result(1, "", "refused");
      }

      @Override
      public Result exec(List<String> command, Path workDir, Duration timeout) {
        return new Result(1, "", "refused");
      }

      @Override
      public boolean isDryRun() {
        return false;
      }
    };
  }

  private void awaitUserManager() throws Exception {
    var deadline = Instant.now().plus(Duration.ofSeconds(60));
    while (Instant.now().isBefore(deadline)) {
      var probe = exec(CONTAINER, List.of("test", "-S", "/run/user/1000/bus"));
      if (probe.ok()) {
        return;
      }
      Thread.sleep(Duration.ofSeconds(1));
    }
    throw new AssertionError("dev user's systemd manager (bus) never came up in " + CONTAINER);
  }
}
