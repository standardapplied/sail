/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.sail.engine.ScriptedShellExecutor;
import ai.singlr.sail.engine.ShellExec;
import ai.singlr.sail.store.RunStore;
import ai.singlr.sail.store.SchemaManager;
import ai.singlr.sail.store.Sqlite;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SnapshotOperationsTest {

  private static final String RUNNING_JSON =
      """
      [{"name": "acme", "status": "Running", "state": {}}]
      """;

  private static final String STOPPED_JSON =
      """
      [{"name": "acme", "status": "Stopped", "state": {}}]
      """;

  private static final String SNAPSHOTS_JSON =
      """
      [
        {"name": "invite-run-7", "created_at": "2026-08-17T10:00:00Z"},
        {"name": "guardrail-20260817-090000", "created_at": "2026-08-17T09:00:00Z"},
        {"name": "snap-20260817-080000", "created_at": "2026-08-17T08:00:00Z"},
        {"name": "my-checkpoint", "created_at": "2026-08-17T07:00:00Z"}
      ]
      """;

  @TempDir Path tempDir;

  private final List<Event> events = new ArrayList<>();

  @Test
  void listClassifiesEverySourceByItsNamePrefix() throws Exception {
    var ops = ops(shell(RUNNING_JSON), null, new DirectExecutorService());

    var response = ops.list("acme");

    assertEquals(4, response.snapshots().size());
    assertEquals("invite", response.snapshots().get(0).source());
    assertEquals("guardrail", response.snapshots().get(1).source());
    assertEquals("dispatch", response.snapshots().get(2).source());
    assertEquals("manual", response.snapshots().get(3).source());
    assertEquals("invite-run-7", response.snapshots().getFirst().name());
    assertEquals("2026-08-17T10:00:00Z", response.snapshots().getFirst().createdAt());
  }

  @Test
  void listRefusesAProjectWithoutAContainer() throws Exception {
    var shell =
        new ScriptedShellExecutor()
            .onOk("incus list ^acme$", "[]")
            .onOk("incus snapshot list acme", SNAPSHOTS_JSON);
    var ops = ops(shell, null, new DirectExecutorService());

    var error = assertThrows(ApiException.class, () -> ops.list("acme"));

    assertEquals(ErrorCode.PROJECT_NOT_CREATED, error.failure().errorCode());
  }

  @Test
  void listWrapsAnIncusFailureAsSnapshotFailed() throws Exception {
    var shell =
        new ScriptedShellExecutor()
            .onOk("incus list ^acme$", RUNNING_JSON)
            .onFail("incus snapshot list acme", "daemon not running");
    var ops = ops(shell, null, new DirectExecutorService());

    var error = assertThrows(ApiException.class, () -> ops.list("acme"));

    assertEquals(ErrorCode.SNAPSHOT_FAILED, error.failure().errorCode());
    assertTrue(error.getMessage().contains("daemon not running"));
  }

  @Test
  void listMarksAnInterruptedThreadBeforeRefusing() {
    var ops = ops(new InterruptingShell(), null, new DirectExecutorService());

    var error = assertThrows(ApiException.class, () -> ops.list("acme"));

    assertEquals(ErrorCode.SNAPSHOT_FAILED, error.failure().errorCode());
    assertTrue(Thread.interrupted(), "the interrupt flag must be restored before wrapping");
  }

  @Test
  void restoreStopsARunningContainerRestoresAndStartsIt() throws Exception {
    var shell = shell(RUNNING_JSON);
    var ops = ops(shell, null, new DirectExecutorService());

    var response = ops.restore("acme", "my-checkpoint", "uday");

    assertEquals("accepted", response.status());
    assertEquals("restore", response.action());
    var commands = shell.invocations();
    var stopIndex = commands.indexOf("incus stop acme");
    var restoreIndex = commands.indexOf("incus snapshot restore acme my-checkpoint");
    var startIndex = commands.indexOf("incus start acme");
    assertTrue(stopIndex >= 0 && stopIndex < restoreIndex && restoreIndex < startIndex);
    assertEquals(1, events.size());
    assertEquals(Event.WellKnownTypes.SNAPSHOT_RESTORED, events.getFirst().type());
    assertEquals("my-checkpoint", events.getFirst().data().get("label"));
  }

  @Test
  void restoreOfAStoppedContainerNeverStopsIt() throws Exception {
    var shell = shell(STOPPED_JSON);
    var ops = ops(shell, null, new DirectExecutorService());

    ops.restore("acme", "my-checkpoint", "uday");

    assertFalse(shell.invocations().contains("incus stop acme"));
    assertTrue(shell.invocations().contains("incus snapshot restore acme my-checkpoint"));
  }

  @Test
  void restoreRefusesWhileASpecRunIsLiveAndNamesWhatItWouldDiscard() throws Exception {
    var runs = runStore();
    runs.reserveDispatch(
        "r-7",
        "acme",
        "auth",
        "uday",
        "uday",
        "build",
        List.of(),
        "claude-code",
        null,
        "t",
        "l",
        "u");
    var ops = ops(shell(RUNNING_JSON), runs, new DirectExecutorService());

    var error =
        assertThrows(ApiException.class, () -> ops.restore("acme", "my-checkpoint", "uday"));

    assertEquals(ErrorCode.AGENT_ALREADY_RUNNING, error.failure().errorCode());
    assertTrue(error.getMessage().contains("r-7"));
    assertTrue(error.getMessage().contains("spec 'auth'"));
    assertTrue(error.getMessage().contains("would discard its live work"));
    assertTrue(events.isEmpty());
  }

  @Test
  void restoreRefusesWhileAnInviteOccupiesTheContainer() throws Exception {
    var runs = runStore();
    runs.reserveDispatch(
        "r-9", "acme", "", "uday", "uday", "invite", List.of(), "claude-code", null, "t", "l", "u");
    var ops = ops(shell(RUNNING_JSON), runs, new DirectExecutorService());

    var error =
        assertThrows(ApiException.class, () -> ops.restore("acme", "my-checkpoint", "uday"));

    assertEquals(ErrorCode.AGENT_ALREADY_RUNNING, error.failure().errorCode());
    assertTrue(error.getMessage().contains("Ad-hoc agent run r-9 is occupying this container"));
  }

  @Test
  void aForeignNodesRunNeverBlocksARestoreHere() throws Exception {
    var runs = runStore();
    runs.reserveDispatch(
        "r-8",
        "acme",
        "auth",
        "other",
        "uday",
        "build",
        List.of(),
        "claude-code",
        null,
        "t",
        "l",
        "u");
    var ops = ops(shell(RUNNING_JSON), runs, new DirectExecutorService());

    var response = ops.restore("acme", "my-checkpoint", "uday");

    assertEquals("accepted", response.status());
  }

  @Test
  void restoreOfAnUnknownLabelIsNotFound() throws Exception {
    var ops = ops(shell(RUNNING_JSON), null, new DirectExecutorService());

    var error = assertThrows(ApiException.class, () -> ops.restore("acme", "no-such", "uday"));

    assertEquals(ErrorCode.NOT_FOUND, error.failure().errorCode());
  }

  @Test
  void restoreOfAnInvalidLabelIsRejectedBeforeAnyShellCall() throws Exception {
    var shell = shell(RUNNING_JSON);
    var ops = ops(shell, null, new DirectExecutorService());

    assertThrows(IllegalArgumentException.class, () -> ops.restore("acme", "-bad", "uday"));

    assertTrue(shell.invocations().isEmpty());
  }

  @Test
  void anAcceptedRestoreLeasesTheContainerAgainstDispatchUntilItCompletes() throws Exception {
    var runs = runStore();
    var executor = new HoldingExecutorService();
    var ops = ops(shell(RUNNING_JSON), runs, executor);

    ops.restore("acme", "my-checkpoint", "uday");

    var during = reserve(runs, "r-1");
    var held = assertInstanceOf(RunStore.Reservation.LeaseHeld.class, during);
    assertEquals("restore", held.action());

    executor.runAll();
    assertInstanceOf(RunStore.Reservation.Reserved.class, reserve(runs, "r-2"));
  }

  @Test
  void aFailedRestoreReleasesTheLeaseSoDispatchCanProceed() throws Exception {
    var runs = runStore();
    var shell =
        new ScriptedShellExecutor(new ShellExec.Result(0, "", ""))
            .onOk("incus list ^acme$", RUNNING_JSON)
            .onOk("incus snapshot list acme", SNAPSHOTS_JSON)
            .onFail("incus snapshot restore acme my-checkpoint", "boom");
    var ops = ops(shell, runs, new DirectExecutorService());

    ops.restore("acme", "my-checkpoint", "uday");

    assertEquals(1, events.size());
    assertTrue(events.getFirst().data().get("error").toString().contains("boom"));
    assertInstanceOf(RunStore.Reservation.Reserved.class, reserve(runs, "r-3"));
  }

  @Test
  void aRestoreIsRefusedWhileAnotherLeaseHoldsTheContainer() throws Exception {
    var runs = runStore();
    runs.acquireContainerLease("acme", "uday", "restore");
    var ops = ops(shell(RUNNING_JSON), runs, new DirectExecutorService());

    var error =
        assertThrows(ApiException.class, () -> ops.restore("acme", "my-checkpoint", "uday"));

    assertEquals(ErrorCode.CONFLICT, error.failure().errorCode());
    assertTrue(error.getMessage().contains("already in progress"));
    assertEquals(
        "accepted",
        ops.delete("acme", "my-checkpoint").status(),
        "the refused restore must release its in-flight claim");
  }

  @Test
  void aFailedRestoreOfARunningContainerStartsItAgain() throws Exception {
    var shell = shell(RUNNING_JSON).onFail("incus snapshot restore acme my-checkpoint", "boom");
    var ops = ops(shell, null, new DirectExecutorService());

    ops.restore("acme", "my-checkpoint", "uday");

    var commands = shell.invocations();
    assertTrue(
        commands.indexOf("incus stop acme") < commands.indexOf("incus start acme"),
        "a restore that stopped a running container must start it again on failure");
    assertEquals(1, events.size());
    assertTrue(events.getFirst().data().get("error").toString().contains("boom"));
  }

  @Test
  void aFailedRestartAfterAFailedRestoreStillPublishesTheError() throws Exception {
    var shell =
        shell(RUNNING_JSON)
            .onFail("incus snapshot restore acme my-checkpoint", "boom")
            .onFail("incus start acme", "cannot start");
    var ops = ops(shell, null, new DirectExecutorService());

    ops.restore("acme", "my-checkpoint", "uday");

    assertTrue(shell.invocations().contains("incus start acme"));
    assertEquals(1, events.size());
    assertTrue(events.getFirst().data().get("error").toString().contains("boom"));
  }

  @Test
  void aFailedLeaseReleaseStillClearsTheInFlightClaim() throws Exception {
    var db = Sqlite.open(tempDir.resolve("lease-" + System.nanoTime() + ".db"));
    new SchemaManager(db).migrate();
    var runs = new RunStore(db);
    var executor = new HoldingExecutorService();
    var ops = ops(shell(RUNNING_JSON), runs, executor);
    ops.restore("acme", "my-checkpoint", "uday");
    db.close();

    executor.runAll();

    assertEquals(1, events.size());
    assertEquals(Event.WellKnownTypes.SNAPSHOT_RESTORED, events.getFirst().type());
    assertEquals(
        "accepted",
        ops.delete("acme", "my-checkpoint").status(),
        "a lease release that fails must never wedge the in-flight claim");
  }

  private static RunStore.Reservation reserve(RunStore runs, String id) {
    return runs.reserveDispatch(
        id, "acme", "auth", "uday", "uday", "build", List.of(), "claude-code", null, "t", "l", "u");
  }

  @Test
  void restoreFailurePublishesTheErrorAndReleasesTheClaim() throws Exception {
    var shell =
        new ScriptedShellExecutor()
            .onOk("incus list ^acme$", STOPPED_JSON)
            .onOk("incus snapshot list acme", SNAPSHOTS_JSON)
            .onFail("incus snapshot restore acme my-checkpoint", "boom");
    var ops = ops(shell, null, new DirectExecutorService());

    ops.restore("acme", "my-checkpoint", "uday");

    assertEquals(1, events.size());
    assertEquals(Event.WellKnownTypes.SNAPSHOT_RESTORED, events.getFirst().type());
    assertTrue(events.getFirst().data().get("error").toString().contains("boom"));
    assertEquals("accepted", ops.restore("acme", "my-checkpoint", "uday").status());
  }

  @Test
  void deleteDeletesAndPublishesCompletion() throws Exception {
    var shell = shell(RUNNING_JSON);
    var ops = ops(shell, null, new DirectExecutorService());

    var response = ops.delete("acme", "invite-run-7");

    assertEquals("accepted", response.status());
    assertEquals("delete", response.action());
    assertTrue(shell.invocations().contains("incus snapshot delete acme invite-run-7"));
    assertEquals(1, events.size());
    assertEquals(Event.WellKnownTypes.SNAPSHOT_DELETED, events.getFirst().type());
    assertEquals("invite-run-7", events.getFirst().data().get("label"));
  }

  @Test
  void deleteFailurePublishesTheErrorAndReleasesTheClaim() throws Exception {
    var shell =
        new ScriptedShellExecutor()
            .onOk("incus list ^acme$", RUNNING_JSON)
            .onOk("incus snapshot list acme", SNAPSHOTS_JSON)
            .onFail("incus snapshot delete acme invite-run-7", "storage error");
    var ops = ops(shell, null, new DirectExecutorService());

    ops.delete("acme", "invite-run-7");

    assertEquals(1, events.size());
    assertTrue(events.getFirst().data().get("error").toString().contains("storage error"));
    assertEquals("accepted", ops.delete("acme", "invite-run-7").status());
  }

  @Test
  void deleteOfAnUnknownLabelIsNotFound() throws Exception {
    var ops = ops(shell(RUNNING_JSON), null, new DirectExecutorService());

    var error = assertThrows(ApiException.class, () -> ops.delete("acme", "no-such"));

    assertEquals(ErrorCode.NOT_FOUND, error.failure().errorCode());
  }

  @Test
  void aSecondMutationIsRefusedWhileOneIsInFlight() throws Exception {
    var executor = new HoldingExecutorService();
    var ops = ops(shell(RUNNING_JSON), null, executor);

    ops.delete("acme", "invite-run-7");
    var error = assertThrows(ApiException.class, () -> ops.delete("acme", "my-checkpoint"));

    assertEquals(ErrorCode.CONFLICT, error.failure().errorCode());
    assertTrue(error.getMessage().contains("already in progress"));

    executor.runAll();
    assertEquals(1, events.size());
    assertEquals("accepted", ops.delete("acme", "my-checkpoint").status());
  }

  @Test
  void theDefaultExecutorConstructorServesSynchronousReads() throws Exception {
    var loader = loader(shell(RUNNING_JSON));
    var ops = new SnapshotOperations(shell(RUNNING_JSON), loader, null, events::add);

    assertEquals(4, ops.list("acme").snapshots().size());
  }

  private ScriptedShellExecutor shell(String stateJson) {
    return new ScriptedShellExecutor(new ShellExec.Result(0, "", ""))
        .onOk("incus list ^acme$", stateJson)
        .onOk("incus snapshot list acme", SNAPSHOTS_JSON);
  }

  private SnapshotOperations ops(ShellExec shell, RunStore runs, ExecutorService executor) {
    return new SnapshotOperations(shell, loader(shell), runs, events::add, executor);
  }

  private ProjectLoader loader(ShellExec shell) {
    try {
      var yaml = tempDir.resolve("sail-" + System.nanoTime() + ".yaml");
      Files.writeString(
          yaml,
          """
          name: acme
          ssh:
            user: dev
          agent:
            type: claude-code
          """);
      return new ProjectLoader(shell, yaml.toString());
    } catch (IOException e) {
      throw new IllegalStateException(e);
    }
  }

  private RunStore runStore() throws Exception {
    var db = Sqlite.open(tempDir.resolve("runs-" + System.nanoTime() + ".db"));
    new SchemaManager(db).migrate();
    return new RunStore(db);
  }

  private static final class InterruptingShell implements ShellExec {
    @Override
    public Result exec(List<String> command) throws InterruptedException {
      if (String.join(" ", command).contains("snapshot")) {
        throw new InterruptedException("interrupted");
      }
      return new Result(0, RUNNING_JSON, "");
    }

    @Override
    public Result exec(List<String> command, Path workDir, Duration timeout)
        throws InterruptedException {
      return exec(command);
    }

    @Override
    public boolean isDryRun() {
      return false;
    }
  }

  private static final class HoldingExecutorService extends AbstractExecutorService {
    private final List<Runnable> held = new ArrayList<>();

    void runAll() {
      var pending = List.copyOf(held);
      held.clear();
      pending.forEach(Runnable::run);
    }

    @Override
    public void execute(Runnable command) {
      held.add(command);
    }

    @Override
    public void shutdown() {}

    @Override
    public List<Runnable> shutdownNow() {
      return List.of();
    }

    @Override
    public boolean isShutdown() {
      return false;
    }

    @Override
    public boolean isTerminated() {
      return false;
    }

    @Override
    public boolean awaitTermination(long timeout, TimeUnit unit) {
      return true;
    }
  }
}
