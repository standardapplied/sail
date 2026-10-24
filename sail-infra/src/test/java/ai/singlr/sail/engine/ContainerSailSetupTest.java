/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.sail.harness.Harnesses;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ContainerSailSetupTest {

  @TempDir Path locks;

  private static final String CONTAINER = "light-grid";
  private static final String PROBE = "cmp -s --";

  @Test
  void aVerifiedContainerIsAlreadyPresentAndRunsNoInstaller() throws Exception {
    var shell =
        new ScriptedShellExecutor(new ShellExec.Result(0, "", ""))
            .onOk("config device get " + CONTAINER, "/run/sail\n")
            .onOk(PROBE);

    var result = ContainerSailSetup.ensureInstalled(shell, shell, CONTAINER, locks);

    assertEquals(ContainerSailSetup.Result.ALREADY_PRESENT, result);
    assertTrue(
        shell.invocations().stream().noneMatch(c -> c.contains("chmod 0755")),
        "a current container must cost zero installer shells on the dispatch hot path");
    assertEquals(
        1,
        shell.invocations().stream().filter(c -> c.contains(CONTAINER + " --user 1000")).count(),
        "the staleness probe is exactly one shell: verify stamp and contents");
  }

  @Test
  void theProbeVerifiesObservedContentsNotJustTheStamp() throws Exception {
    var shell =
        new ScriptedShellExecutor(new ShellExec.Result(0, "", ""))
            .onOk("config device get " + CONTAINER, "/run/sail\n")
            .onOk(PROBE);

    ContainerSailSetup.ensureInstalled(shell, shell, CONTAINER, locks);

    var probe =
        shell.invocations().stream().filter(c -> c.contains(PROBE)).findFirst().orElseThrow();
    assertTrue(probe.contains(ContainerSailSetup.STAMP_PATH), "the probe must read the stamp");
    assertTrue(
        probe.contains(ContainerSailSetup.fingerprint()),
        "the probe must carry the expected fingerprint");
    ContainerSailSetup.installedFiles()
        .forEach(
            (path, content) -> {
              assertTrue(probe.contains(path), "the probe must check the installed file " + path);
              assertTrue(
                  probe.contains(content),
                  "the probe must compare " + path + " against this binary's payload");
            });
  }

  @Test
  void theProbeRequiresBinScriptsToStayExecutable() throws Exception {
    var shell =
        new ScriptedShellExecutor(new ShellExec.Result(0, "", ""))
            .onOk("config device get " + CONTAINER, "/run/sail\n")
            .onOk(PROBE);

    ContainerSailSetup.ensureInstalled(shell, shell, CONTAINER, locks);

    var probe =
        shell.invocations().stream().filter(c -> c.contains(PROBE)).findFirst().orElseThrow();
    assertTrue(
        probe.contains("[ -x \"$path\" ]"),
        "byte-identical scripts with a dropped executable bit are still stale — hooks and the"
            + " spec CLI fail with EACCES, so the probe must fail and trigger a heal");
    assertTrue(
        probe.contains(SpecCliHelper.SCRIPT_DIR),
        "the executable check must scope to the bin directory the scripts install into");
  }

  @Test
  void theSplitOverloadProbesOnTheObserverAndInstallsOnTheMutator() throws Exception {
    var probe = new ScriptedShellExecutor(new ShellExec.Result(0, "", "")).onFail(PROBE, "stale");
    var mutator =
        new ScriptedShellExecutor(new ShellExec.Result(0, "", ""))
            .onOk("config device get " + CONTAINER, "/run/sail\n");

    var result = ContainerSailSetup.ensureInstalled(probe, mutator, CONTAINER, locks);

    assertEquals(ContainerSailSetup.Result.UPDATED, result);
    assertTrue(
        probe.invocations().stream().allMatch(c -> c.contains(PROBE)),
        "the observer shell must carry only the staleness probe");
    assertTrue(
        mutator.invocations().stream().noneMatch(c -> c.contains(PROBE)),
        "the mutator shell must never carry the probe — a dry-run mutator would blind it");
    assertTrue(
        mutator.invocations().stream().anyMatch(c -> c.contains("chmod 0755")),
        "installers must ride the mutator shell");
  }

  @Test
  void theSshdKeepaliveDropInIsMachineryInstalledAsRootAndVerifiedLikeTheRest() throws Exception {
    assertEquals(
        SshdKeepalive.DROP_IN.content(),
        ContainerSailSetup.installedFiles().get(SshdKeepalive.DROP_IN.path()),
        "the drop-in is a fingerprinted payload: a stale container heals it on the next probe");
    var shell =
        new ScriptedShellExecutor(new ShellExec.Result(0, "", ""))
            .onOk("config device get " + CONTAINER, "/run/sail\n")
            .onFail(PROBE, "stale");

    ContainerSailSetup.ensureInstalled(shell, shell, CONTAINER, locks);

    var install =
        shell.invocations().stream()
            .filter(c -> c.contains(SshdKeepalive.DROP_IN.path()) && !c.contains(PROBE))
            .findFirst()
            .orElseThrow();
    assertTrue(install.startsWith("incus exec " + CONTAINER + " -- bash"), install);
    assertFalse(install.contains("--user 1000"), "sshd's config is root's to write: " + install);
  }

  @Test
  void aFailedVerificationInstallsEverythingAndStamps() throws Exception {
    var shell =
        new ScriptedShellExecutor(new ShellExec.Result(0, "", ""))
            .onOk("config device get " + CONTAINER, "/run/sail\n")
            .onFail(PROBE, "No such file or directory");

    var result = ContainerSailSetup.ensureInstalled(shell, shell, CONTAINER, locks);

    assertEquals(ContainerSailSetup.Result.UPDATED, result);
    var commands = shell.invocations();
    for (var path : ContainerSailSetup.installedFiles().keySet()) {
      if (path.equals(SpecCliHelper.PROFILE_PATH)) {
        continue;
      }
      assertTrue(
          commands.stream().anyMatch(c -> c.endsWith(" " + path)),
          "every sail-owned file must be rewritten, missing: " + path);
    }
    assertTrue(
        commands.stream().anyMatch(c -> c.contains(SpecCliHelper.PROFILE_PATH)),
        "the profile PATH line must be ensured");
    assertTrue(
        commands.stream()
            .anyMatch(
                c ->
                    c.contains(ContainerSailSetup.fingerprint())
                        && c.endsWith(" " + ContainerSailSetup.STAMP_PATH)),
        "a full install must stamp the current fingerprint");
  }

  @Test
  void aTamperedPayloadReinstallsAndRestampsEvenWithACurrentStamp() throws Exception {
    var shell =
        new ScriptedShellExecutor(new ShellExec.Result(0, "", ""))
            .onOk("config device get " + CONTAINER, "/run/sail\n")
            .onFail(PROBE, "");

    var result = ContainerSailSetup.ensureInstalled(shell, shell, CONTAINER, locks);

    assertEquals(
        ContainerSailSetup.Result.UPDATED,
        result,
        "observed state that does not match what this binary would install is stale by"
            + " definition — a matching stamp alone is never proof");
    assertTrue(
        shell.invocations().stream()
            .anyMatch(
                c ->
                    c.contains(ContainerSailSetup.fingerprint())
                        && c.endsWith(" " + ContainerSailSetup.STAMP_PATH)),
        "the reinstall must rewrite the stamp to the current fingerprint");
  }

  @Test
  void fingerprintIsDeterministic() {
    assertEquals(ContainerSailSetup.fingerprint(), ContainerSailSetup.fingerprint());
    assertEquals(
        ContainerSailSetup.fingerprint(),
        ContainerSailSetup.fingerprintOf(ContainerSailSetup.installedFiles()));
  }

  @Test
  void fingerprintChangesWhenAnySinglePayloadChanges() {
    var baseline = ContainerSailSetup.fingerprint();
    for (var path : ContainerSailSetup.installedFiles().keySet()) {
      var mutated = ContainerSailSetup.installedFiles();
      mutated.put(path, mutated.get(path) + "\n# drift");
      assertNotEquals(
          baseline,
          ContainerSailSetup.fingerprintOf(mutated),
          "a change to " + path + " must change the fingerprint");
    }
  }

  @Test
  void fingerprintCoversEveryInstalledFile() {
    var files = ContainerSailSetup.installedFiles();

    assertEquals(
        java.util.List.of(
            SailEventHelper.SCRIPT_PATH,
            SailStopGate.SCRIPT_PATH,
            SailRoomRelay.SCRIPT_PATH,
            SailSessionReport.SCRIPT_PATH,
            SpecCliHelper.SCRIPT_PATH,
            SpecCliHelper.PROFILE_PATH,
            Harnesses.of("claude-code").hooks().path(),
            "/home/dev/.sail/claude-live.json",
            Harnesses.of("codex").hooks().path(),
            SshdKeepalive.DROP_IN.path()),
        java.util.List.copyOf(files.keySet()),
        "the fingerprint must cover every sail-owned in-container file, in stable order — a"
            + " script riding in this list IS its rollout: the fingerprint changes and every"
            + " container converges on next apply or dispatch");
    files.forEach((path, content) -> assertFalse(content.isBlank(), path + " has no payload"));
    for (var harness : Harnesses.all()) {
      assertEquals(
          HarnessHooks.render(harness),
          files.get(harness.hooks().path()),
          "each harness's hook file holds its own hooks");
    }
    assertEquals(
        HarnessHooks.renderLive(Harnesses.of("claude-code")).orElseThrow(),
        files.get("/home/dev/.sail/claude-live.json"),
        "the live member's settings ride the same fingerprint and byte-for-byte verify");
  }

  @Test
  void theSessionReportRidesTheFingerprintSoItRollsOutByItself() {
    var without = new LinkedHashMap<>(ContainerSailSetup.installedFiles());
    without.remove(SailSessionReport.SCRIPT_PATH);

    assertNotEquals(
        ContainerSailSetup.fingerprint(),
        ContainerSailSetup.fingerprintOf(without),
        "shipping the session report changes the fingerprint — that IS the rollout: every"
            + " container converges on next apply or dispatch");
  }

  @Test
  void fingerprintDependsOnPathOrderAndBoundaries() {
    var swapped = new LinkedHashMap<String, String>();
    ContainerSailSetup.installedFiles().reversed().forEach(swapped::put);

    assertNotEquals(
        ContainerSailSetup.fingerprint(),
        ContainerSailSetup.fingerprintOf(swapped),
        "the hash is over ordered (path, content) pairs, so order is part of the content");
  }

  @Test
  void refreshHappensEvenWhenSourcePathUnchanged() throws Exception {
    var hostDir = SailPaths.apiSocketHostDir().toString();
    var shell =
        new ScriptedShellExecutor(new ShellExec.Result(0, "", ""))
            .onOk("config device get " + CONTAINER, hostDir + "\n")
            .onOk(PROBE);

    ContainerSailSetup.ensureInstalled(shell, shell, CONTAINER, locks);

    var commands = shell.invocations();
    assertTrue(
        commands.stream().anyMatch(c -> c.contains("config device remove")),
        "identical source paths must NOT short-circuit the refresh — Incus tracks the bind by"
            + " inode, and the source directory can be recreated under the same path");
    assertTrue(
        commands.stream().anyMatch(c -> c.contains("config device add")),
        "the bind mount must be re-added after removal");
  }

  @Test
  void addsFreshMountWhenNotConfigured() throws Exception {
    var shell =
        new ScriptedShellExecutor(new ShellExec.Result(0, "", ""))
            .onFail("config device get " + CONTAINER, "Device not found")
            .onOk(PROBE);

    ContainerSailSetup.ensureInstalled(shell, shell, CONTAINER, locks);

    var commands = shell.invocations();
    assertTrue(
        commands.stream().anyMatch(c -> c.contains("config device add")),
        "fresh mount must be added when the device was not previously configured");
    assertFalse(
        commands.stream().anyMatch(c -> c.contains("config device remove")),
        "no remove needed when the device was absent");
  }

  @Test
  void aFailedStampWriteFailsLoud() {
    var shell =
        new ScriptedShellExecutor(new ShellExec.Result(0, "", ""))
            .onOk("config device get " + CONTAINER, "/run/sail\n")
            .onFail(PROBE, "missing")
            .onFail(ContainerSailSetup.STAMP_PATH, "read-only filesystem");

    assertThrows(
        IOException.class,
        () -> ContainerSailSetup.ensureInstalled(shell, shell, CONTAINER, locks));
  }

  @Test
  void rejectsInvalidContainerName() {
    var shell = new ScriptedShellExecutor();

    assertThrows(
        Exception.class, () -> ContainerSailSetup.ensureInstalled(shell, shell, "../bad", locks));
  }

  @Test
  void propagatesRefreshFailure() {
    var shell =
        new ScriptedShellExecutor(new ShellExec.Result(0, "", ""))
            .onFail("config device get", "Device not found")
            .onFail("config device add", "incus error");

    assertThrows(
        IOException.class,
        () -> ContainerSailSetup.ensureInstalled(shell, shell, CONTAINER, locks));
  }

  @Test
  void aDryRunReconcilesUnderNoLock() throws Exception {
    var probe = new ScriptedShellExecutor(new ShellExec.Result(0, "", "")).onFail(PROBE, "stale");

    var result =
        ContainerSailSetup.ensureInstalled(probe, new ShellExecutor(true), CONTAINER, locks);

    assertEquals(ContainerSailSetup.Result.UPDATED, result);
    assertEquals(List.of(), Files.list(locks).toList(), "nothing changes, so nothing is held");
  }

  @Test
  void twoReconcilesOfOneContainerTakeTurnsAndNeitherFails() throws Exception {
    var threads = new ArrayList<Thread>();
    var incus =
        new SharedIncus(
            () -> awaitUntil(() -> otherThan(threads).allMatch(parkedOrDone())), CONTAINER);
    var first =
        new FutureTask<>(() -> ContainerSailSetup.ensureInstalled(incus, incus, CONTAINER, locks));
    var second =
        new FutureTask<>(() -> ContainerSailSetup.ensureInstalled(incus, incus, CONTAINER, locks));
    threads.addAll(List.of(new Thread(first), new Thread(second)));

    threads.forEach(Thread::start);

    assertEquals(
        Set.of(ContainerSailSetup.Result.UPDATED, ContainerSailSetup.Result.ALREADY_PRESENT),
        Set.of(first.get(), second.get()));
    assertEquals(
        List.of(
            "remove " + CONTAINER, "add " + CONTAINER, "remove " + CONTAINER, "add " + CONTAINER),
        incus.devices());
    assertEquals(
        1, incus.stampWrites(), "the helpers are written by the call that found them stale");
  }

  @Test
  void anApplyReachingTheMachineryWhileTheSweepHoldsItWaitsThenFindsItCurrent() throws Exception {
    var sweepInside = new CountDownLatch(1);
    var applying = new AtomicReference<Thread>();
    var incus =
        new SharedIncus(
            () -> {
              sweepInside.countDown();
              awaitUntil(() -> applying.get() != null && parkedOrDone().test(applying.get()));
            },
            CONTAINER);
    var sweep = new FutureTask<>(() -> ContainerSetupSweep.reconcile(incus, CONTAINER, locks));
    var apply =
        new FutureTask<>(() -> ContainerSailSetup.ensureInstalled(incus, incus, CONTAINER, locks));

    new Thread(sweep).start();
    assertTrue(sweepInside.await(10, TimeUnit.SECONDS), "the sweep never reached the device");
    applying.set(new Thread(apply));
    applying.get().start();

    assertTrue(sweep.get(), "the sweep reconciled the container");
    assertEquals(ContainerSailSetup.Result.ALREADY_PRESENT, apply.get());
  }

  @Test
  void differentContainersReconcileAtOnce() throws Exception {
    var threads = new ArrayList<Thread>();
    var incus =
        new SharedIncus(
            () ->
                awaitUntil(
                    () ->
                        otherThan(threads)
                            .allMatch(thread -> thread.getState() == Thread.State.TERMINATED)),
            "acme",
            "beta");
    var acme =
        new FutureTask<>(() -> ContainerSailSetup.ensureInstalled(incus, incus, "acme", locks));
    var beta =
        new FutureTask<>(() -> ContainerSailSetup.ensureInstalled(incus, incus, "beta", locks));
    threads.addAll(List.of(new Thread(acme), new Thread(beta)));

    threads.forEach(Thread::start);

    assertEquals(ContainerSailSetup.Result.UPDATED, acme.get());
    assertEquals(ContainerSailSetup.Result.UPDATED, beta.get());
  }

  private static Stream<Thread> otherThan(List<Thread> threads) {
    return threads.stream().filter(thread -> thread != Thread.currentThread());
  }

  private static Predicate<Thread> parkedOrDone() {
    return thread ->
        thread.getState() == Thread.State.WAITING || thread.getState() == Thread.State.TERMINATED;
  }

  private static void awaitUntil(BooleanSupplier condition) {
    var horizon = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
    while (!condition.getAsBoolean()) {
      if (System.nanoTime() > horizon) {
        throw new AssertionError("the other reconcile neither ran nor waited");
      }
      Thread.yield();
    }
  }

  /**
   * Incus as every sail process on the box sees it: each container's {@code sail-api-sock} device
   * and machinery stamp are shared state, read and written one call at a time, and refused as Incus
   * refuses them — a remove of a device that is gone, an add of one that is there. The first remove
   * runs {@code afterFirstRemove} before it answers, so a test can hold one reconcile inside the
   * device refresh while another starts.
   */
  private static final class SharedIncus implements ShellExec {

    private final Set<String> attached = ConcurrentHashMap.newKeySet();
    private final Set<String> stamped = ConcurrentHashMap.newKeySet();
    private final List<String> devices = new CopyOnWriteArrayList<>();
    private final AtomicInteger stampWrites = new AtomicInteger();
    private final AtomicInteger removes = new AtomicInteger();
    private final Runnable afterFirstRemove;

    SharedIncus(Runnable afterFirstRemove, String... containers) {
      this.afterFirstRemove = afterFirstRemove;
      attached.addAll(List.of(containers));
    }

    List<String> devices() {
      return List.copyOf(devices);
    }

    int stampWrites() {
      return stampWrites.get();
    }

    @Override
    public Result exec(List<String> command) {
      var joined = String.join(" ", command);
      if (joined.startsWith("incus config device get")) {
        return attached.contains(command.get(4))
            ? new Result(0, SailPaths.apiSocketHostDir() + "\n", "")
            : new Result(1, "", "Error: Device not found");
      }
      if (joined.startsWith("incus config device remove")) {
        return remove(command.get(4));
      }
      if (joined.startsWith("incus config device add")) {
        if (!attached.add(command.get(4))) {
          return new Result(1, "", "Error: The device already exists");
        }
        devices.add("add " + command.get(4));
        return new Result(0, "", "");
      }
      if (joined.contains(PROBE)) {
        return new Result(stamped.contains(command.get(2)) ? 0 : 1, "", "");
      }
      if (joined.contains(ContainerSailSetup.STAMP_PATH)) {
        stamped.add(command.get(2));
        stampWrites.incrementAndGet();
      }
      return new Result(0, "", "");
    }

    private Result remove(String container) {
      if (!attached.remove(container)) {
        return new Result(1, "", "Error: Device \u201csail-api-sock\u201d doesn't exist");
      }
      devices.add("remove " + container);
      if (removes.getAndIncrement() == 0) {
        afterFirstRemove.run();
      }
      return new Result(0, "", "");
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
