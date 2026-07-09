/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import ai.singlr.sail.config.SpecStatus;
import ai.singlr.sail.engine.ShellExec;
import ai.singlr.sail.store.SchemaManager;
import ai.singlr.sail.store.SpecStore;
import ai.singlr.sail.store.Sqlite;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The node lane's sync-on-write and read-freshness behavior at the operations seam: successful spec
 * mutations schedule propagation to main, spec reads freshen at most once per TTL window, the
 * {@code --no-sync} request scope skips both, and a failing reconcile never fails the caller's
 * request. Reconciles run on a same-thread executor with an injected clock, so every assertion is
 * deterministic.
 */
class SailApiOperationsSyncTest {

  private static final String RUNNING_JSON =
      """
      [
        {
          "name": "acme",
          "status": "Running",
          "state": {
            "network": {
              "eth0": {
                "addresses": [
                  {"family": "inet", "address": "10.0.0.42", "scope": "global"}
                ]
              }
            }
          }
        }
      ]
      """;

  @TempDir Path tempDir;

  private final AtomicLong nanos = new AtomicLong();
  private final AtomicInteger rounds = new AtomicInteger();

  @Test
  void aSpecMutationTriggersExactlyOneReconcile() throws Exception {
    var operations = operations(scheduler(), store -> {});

    var result = operations.createGlobalSpec(create("auth"));

    assertInstanceOf(Result.Success.class, result);
    assertEquals(1, rounds.get());
  }

  @Test
  void aFailedMutationTriggersNothing() throws Exception {
    var operations = operations(scheduler(), store -> {});

    var result = operations.updateGlobalSpec("missing", update("pending"));

    assertInstanceOf(Result.Failure.class, result);
    assertEquals(0, rounds.get());
  }

  @Test
  void aFailingReconcileNeverFailsTheWrite() throws Exception {
    var operations =
        operations(
            scheduler(
                () -> {
                  rounds.incrementAndGet();
                  throw new IllegalStateException("main unreachable");
                }),
            store -> {});

    var result = operations.createGlobalSpec(create("auth"));

    assertInstanceOf(Result.Success.class, result);
    assertEquals(1, rounds.get());
    assertInstanceOf(Result.Success.class, operations.createGlobalSpec(create("billing")));
    assertEquals(2, rounds.get());
  }

  @Test
  void readsFreshenOncePerTtlWindow() throws Exception {
    var operations = operations(scheduler(), store -> {});
    var filter = new SpecStore.SpecFilter(null, null, null, null, null);

    operations.globalSpecs(filter);
    operations.globalSpec("missing");
    operations.globalBoard(null);

    assertEquals(1, rounds.get());
    nanos.addAndGet(Duration.ofSeconds(16).toNanos());
    operations.globalSpecs(filter);
    assertEquals(2, rounds.get());
  }

  @Test
  void theNoSyncRequestScopeSkipsFreshenAndPropagation() throws Exception {
    var operations = operations(scheduler(), store -> {});

    ScopedValue.where(SyncControl.NO_SYNC, true)
        .run(
            () -> {
              operations.globalSpecs(new SpecStore.SpecFilter(null, null, null, null, null));
              assertInstanceOf(Result.Success.class, operations.createGlobalSpec(create("auth")));
            });

    assertEquals(0, rounds.get());
  }

  @Test
  void aSpecLifecycleEventPublishTriggersPropagation() throws Exception {
    var operations = operations(scheduler(), new EventBus(), plainShell(), store -> {});

    operations.publishEvent(event(Event.WellKnownTypes.SPEC_DISPATCHED));
    assertEquals(1, rounds.get());

    operations.publishEvent(event(Event.WellKnownTypes.AGENT_LOG_CHUNK));
    assertEquals(1, rounds.get());
  }

  @Test
  void aDispatchClaimPropagatesBeyondItsReadFreshen() throws Exception {
    var operations =
        operations(scheduler(), null, dispatchShell(), SailApiOperationsSyncTest::seedReady);

    var result =
        operations.dispatch(
            "acme",
            new DispatchRequest("auth", "background", true),
            new Actor("uday", Role.ADMIN, Actor.Lane.API),
            "uday");

    assertInstanceOf(Result.Success.class, result);
    assertEquals(2, rounds.get());
  }

  @Test
  void aDispatchWithNothingToClaimOnlyFreshens() throws Exception {
    var operations = operations(scheduler(), null, dispatchShell(), store -> {});

    var result =
        operations.dispatch(
            "acme",
            new DispatchRequest(null, "background", true),
            new Actor("uday", Role.ADMIN, Actor.Lane.API),
            "uday");

    assertInstanceOf(Result.Success.class, result);
    assertEquals(1, rounds.get());
  }

  private SyncScheduler scheduler() {
    return scheduler(rounds::incrementAndGet);
  }

  private SyncScheduler scheduler(SyncScheduler.Reconcile reconcile) {
    return new SyncScheduler(
        reconcile,
        Duration.ZERO,
        Duration.ofSeconds(15),
        new DirectExecutorService(),
        nanos::get,
        duration -> nanos.addAndGet(duration.toNanos()));
  }

  private SailApiOperations operations(SyncScheduler scheduler, Consumer<SpecStore> seed)
      throws Exception {
    return operations(scheduler, null, plainShell(), seed);
  }

  private SailApiOperations operations(
      SyncScheduler scheduler, EventBus bus, ShellExec shell, Consumer<SpecStore> seed)
      throws Exception {
    var yaml = tempDir.resolve("sail-" + System.nanoTime() + ".yaml");
    Files.writeString(
        yaml,
        """
        name: acme
        ssh:
          user: dev
        agent:
          type: claude-code
          specs_dir: specs
        """);
    var db = Sqlite.open(tempDir.resolve("sync-" + System.nanoTime() + ".db"));
    new SchemaManager(db).migrate();
    var specStore = new SpecStore(db);
    seed.accept(specStore);
    return new SailApiOperations(
        shell,
        yaml.toString(),
        (command, logPath) -> 4242L,
        bus,
        null,
        specStore,
        null,
        null,
        null,
        () -> new ai.singlr.sail.engine.ConnectEnvironment("203.0.113.7", "uday", true),
        scheduler);
  }

  private static void seedReady(SpecStore store) {
    store.create(
        new SpecStore.SpecRow(
            "auth",
            "acme",
            "Add auth",
            SpecStatus.PENDING,
            "uday",
            null,
            null,
            null,
            null,
            0,
            "uday",
            "",
            "",
            "uday",
            List.of(),
            List.of()));
  }

  private static SpecCreateRequest create(String id) {
    return new SpecCreateRequest(
        id,
        "acme",
        "Spec " + id,
        "pending",
        null,
        null,
        null,
        null,
        null,
        0,
        List.of(),
        List.of(),
        null,
        null,
        "uday");
  }

  private static SpecUpdateRequest update(String status) {
    return new SpecUpdateRequest(
        null, null, status, null, null, null, null, null, null, null, null, "uday", false);
  }

  private static Event event(String type) {
    return Event.of("acme", "auth", type, Event.SAIL_AGENT, "node-a");
  }

  private static ShellExec plainShell() {
    return new ScriptedShell(false);
  }

  private static ShellExec dispatchShell() {
    return new ScriptedShell(true);
  }

  private record ScriptedShell(boolean containerRunning) implements ShellExec {

    @Override
    public Result exec(List<String> command) {
      var joined = String.join(" ", command);
      if (containerRunning && joined.contains("incus list ^acme$")) {
        return new Result(0, RUNNING_JSON, "");
      }
      return new Result(1, "", "no script for " + joined);
    }

    @Override
    public Result exec(List<String> command, Path workDir, Duration timeout) {
      return exec(command);
    }

    @Override
    public boolean isDryRun() {
      return false;
    }
  }
}
