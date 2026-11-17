/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.sail.common.Remedied;
import ai.singlr.sail.common.Remedy;
import ai.singlr.sail.config.ProjectDefinition;
import ai.singlr.sail.identity.Actor;
import ai.singlr.sail.identity.Role;
import ai.singlr.sail.pty.PtyAdmission;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A template build as both doors run it: its record kept at every step and ended with words for a
 * person, a build cut short ended by the first read that finds it, and the route's start or join.
 */
class TemplateBuildsTest {

  private static final Instant AT = Instant.parse("2026-11-17T09:00:00Z");
  private static final String ZFS =
      """
      [{"name": "devpool", "driver": "dir", "config": {}},
       {"name": "tank", "driver": "zfs", "config": {"zfs.pool_name": "tank"}}]
      """;
  private static final String DIR_ONLY = "[{\"name\": \"devpool\", \"driver\": \"dir\"}]";

  @TempDir Path tempDir;
  private final List<Exception> logged = new CopyOnWriteArrayList<>();
  private final AtomicInteger provisions = new AtomicInteger();

  private static ProjectDefinition acme() {
    return new ProjectDefinition(
        "acme",
        "Acme",
        new ProjectDefinition.Resources(2, "4GB", "50GB"),
        "ubuntu/24.04",
        null,
        null,
        List.of(new ProjectDefinition.Repo("https://github.com/acme/app.git", "app", null)),
        null,
        null,
        null,
        null);
  }

  private static String inputs() {
    return ProjectProvisioner.inputs(acme(), Optional.empty());
  }

  private static ScriptedShellExecutor incus(String pools, String instances, String snapshots) {
    return new ScriptedShellExecutor(new ShellExec.Result(0, "", ""))
        .onOk("incus storage list --format json", pools)
        .onOk("incus list --format json", instances)
        .onOk("incus list ^", instances)
        .onOk("incus snapshot list acme--tmpl --format json", snapshots);
  }

  private static String template(String status, String config) {
    return """
        [{"name": "acme--tmpl", "status": "%s", "created_at": "2026-11-12T09:00:00Z",
         "config": {"user.sail.template.of": "acme"%s},
         "expanded_devices": {"root": {"path": "/", "pool": "tank", "type": "disk"}}}]"""
        .formatted(status, config);
  }

  private static String current(String version, String inputs) {
    return template(
        "Stopped",
        ", \"user.sail.template.current\": \"%s\", \"user.sail.template.inputs\": \"%s\""
            .formatted(version, inputs));
  }

  private static String snapshots(String... names) {
    var entries = new ArrayList<String>();
    for (var name : names) {
      entries.add("{\"name\": \"" + name + "\", \"created_at\": \"2026-11-12T08:00:00Z\"}");
    }
    return "[" + String.join(",", entries) + "]";
  }

  private Path locks() {
    return tempDir.resolve("locks");
  }

  private Path projects() {
    return tempDir.resolve("projects");
  }

  private Path recordFile() {
    return TemplateRecord.fileOf(projects(), "acme");
  }

  private Path progress() {
    return projects().resolve("acme").resolve("template-provision-state.yaml");
  }

  private TemplateBuild records() {
    return new TemplateBuild(locks(), projects(), () -> AT);
  }

  private WorkspaceTemplate lane(ShellExec shell) {
    return new WorkspaceTemplate(shell, shell, locks());
  }

  private TemplateBuilds builds(ShellExec shell) {
    return new TemplateBuilds(
        records(),
        lane(shell),
        shell,
        handle -> "dana".equals(handle) ? List.of("ghp_dana") : List.of(),
        logged::add,
        () -> AT);
  }

  private WorkspaceTemplate.Source source(
      String by, Consumer<TemplateRecord> started, TemplateBuild.Provision provision) {
    return records()
        .source(
            acme(),
            "devpool",
            Set.of("acme")::contains,
            List.of("ghp_dana"),
            progress(),
            by,
            ProvisionListener.NOOP,
            (into, tracker, listener) -> {
              provisions.incrementAndGet();
              provision.into(into, tracker, listener);
            },
            started,
            false);
  }

  private static TemplateRecord record(Path file) {
    return TemplateRecord.read(file).orElseThrow();
  }

  private void write(TemplateRecord record) {
    record.write(recordFile());
  }

  private static TemplateRecord running(String by, int step) {
    return TemplateRecord.started(by, AT, TemplateBuild.STEPS).at(step, AT);
  }

  @Test
  void aBuildKeepsItsRecordAtEveryStepAndItsFinishIsStepTwentyOfTwenty() throws Exception {
    var shell = incus(ZFS, "[]", "[]");
    var started = new ArrayList<TemplateRecord>();
    var during = new ArrayList<TemplateRecord>();

    var built =
        lane(shell)
            .ensure(
                acme(),
                source(
                    "dana",
                    started::add,
                    (into, tracker, listener) -> {
                      during.add(record(recordFile()));
                      listener.onStep(1, 19, "Container launched");
                      during.add(record(recordFile()));
                      listener.onStep(13, 19, "Repositories cloned");
                      during.add(record(recordFile()));
                    }));

    assertTrue(built.built());
    assertEquals(20, TemplateBuild.STEPS);
    assertEquals(List.of(running("dana", 0)), started);
    assertEquals(List.of(0, 1, 13), during.stream().map(TemplateRecord::step).toList());
    assertTrue(during.stream().allMatch(TemplateRecord::running));
    assertEquals(
        running("dana", 20).ended(TemplateRecord.Outcome.BUILT, AT, null, null),
        record(recordFile()));
  }

  @Test
  void aFailureInAToolsWordsReachesTheRecordAsTheRunnerCouldNotFinish() throws Exception {
    var shell = incus(ZFS, "[]", "[]");

    assertThrows(
        IOException.class,
        () ->
            lane(shell)
                .ensure(
                    acme(),
                    source(
                        "dana",
                        record -> {},
                        (into, tracker, listener) -> {
                          listener.onStep(13, 19, "Repositories cloned");
                          throw new IOException(
                              "fatal: Authentication failed for 'https://github.com/acme/app.git'");
                        })));

    var ended = record(recordFile());
    assertEquals(TemplateRecord.Outcome.FAILED, ended.outcome());
    assertEquals(13, ended.step());
    assertEquals(PtyAdmission.Unavailable.UNFINISHED, ended.reason());
    assertEquals(PtyAdmission.Unavailable.REMEDY, ended.remedy());
    assertTrue(shell.invocations().contains("incus stop acme--tmpl"), "a failed build is stopped");
  }

  @Test
  void aFailureWrittenForAPersonKeepsItsReasonAndItsTypedRemedy() throws Exception {
    var shell = incus(ZFS, "[]", "[]");
    var remedy = Remedy.of("Store one with ", Remedy.sail("git login", "github.com"), ".");

    assertThrows(
        IllegalStateException.class,
        () ->
            lane(shell)
                .ensure(
                    acme(),
                    source(
                        "dana",
                        record -> {},
                        (into, tracker, listener) -> {
                          throw Remedied.state("github.com refused the clone.", remedy);
                        })));

    var ended = record(recordFile());
    assertEquals("github.com refused the clone.", ended.reason());
    assertEquals(remedy, ended.remedy());
  }

  @Test
  void aBuildThatResumesSaysHowManyStepsTheOneBeforeItFinished() throws Exception {
    var shell = incus(ZFS, template("Stopped", ""), "[]");
    var earlier = new ProvisionTracker<>(ProjectPhase.class, progress(), false).load(inputs());
    earlier.advance(ProjectPhase.CONTAINER_LAUNCHED);
    earlier.advance(ProjectPhase.DISK_QUOTA_SET);

    lane(shell).ensure(acme(), source("dana", record -> {}, (into, tracker, listener) -> {}));

    assertEquals(2, record(recordFile()).kept());
  }

  @Test
  void aFreshTemplateOrAFinishedRecordStartsEveryStepAgainAndAnInterruptedOneResumes()
      throws Exception {
    var earlier = new ProvisionTracker<>(ProjectPhase.class, progress(), false).load("in");
    earlier.advance(ProjectPhase.CONTAINER_LAUNCHED);

    var resumed = TemplateBuild.tracker(progress(), "in", false, false);
    assertTrue(
        resumed.isCompleted(ProjectPhase.CONTAINER_LAUNCHED), "an interrupted build resumes");

    var fresh = TemplateBuild.tracker(progress(), "in", true, false);
    assertFalse(
        fresh.isCompleted(ProjectPhase.CONTAINER_LAUNCHED),
        "a template that does not exist yet starts from the first step");
  }

  @Test
  void aDryRunKeepsNoRecord() throws Exception {
    var source =
        records()
            .source(
                acme(),
                "devpool",
                name -> true,
                List.of(),
                progress(),
                "dana",
                ProvisionListener.NOOP,
                (into, tracker, listener) -> {},
                record -> {},
                true);

    source.recorder().started();

    assertFalse(Files.exists(recordFile()));
  }

  @Test
  void aProjectWithNoTemplateAndNoBuildIsMissing() throws Exception {
    var status = builds(incus(ZFS, "[]", "[]")).status(acme());

    assertEquals(TemplateBuilds.State.MISSING, status.state());
    assertEquals(Optional.empty(), status.build());
  }

  @Test
  void aCurrentVersionIsReadyAndStaleWhenTheDefinitionChangedSince() throws Exception {
    var same = builds(incus(ZFS, current("v2", inputs()), snapshots("v1", "v2"))).status(acme());
    var older = builds(incus(ZFS, current("v2", "older"), snapshots("v2"))).status(acme());

    assertEquals(TemplateBuilds.State.READY, same.state());
    assertEquals("v2", same.current().orElseThrow().version());
    assertFalse(same.stale());
    assertEquals(TemplateBuilds.State.READY, older.state());
    assertTrue(older.stale());
  }

  /** Ruling B: a rebuild that failed while an older version serves is ready, its failure shown. */
  @Test
  void aRebuildThatFailedWhileAnOlderVersionServesIsReadyAndSaysItsFailure() throws Exception {
    write(
        running("dana", 13)
            .ended(TemplateRecord.Outcome.FAILED, AT, "github.com refused the clone.", null));

    var status = builds(incus(ZFS, current("v1", "older"), snapshots("v1"))).status(acme());

    assertEquals(TemplateBuilds.State.READY, status.state());
    assertTrue(status.stale());
    assertEquals(TemplateRecord.Outcome.FAILED, status.build().orElseThrow().outcome());
  }

  @Test
  void aFailedBuildWithNoVersionCurrentIsFailed() throws Exception {
    write(running("dana", 13).ended(TemplateRecord.Outcome.FAILED, AT, "refused", null));

    assertEquals(
        TemplateBuilds.State.FAILED, builds(incus(ZFS, "[]", "[]")).status(acme()).state());
  }

  @Test
  void aTemplateIsBuildingWhileItsLockIsHeldAndItsRecordHasNoOutcome() throws Exception {
    var shell = incus(ZFS, "[]", "[]");
    write(running("dana", 7));
    var seen = new ArrayList<TemplateBuilds.Status>();

    HeldLocks.whileHeld(
        ContainerConfig.lockOf(locks(), "acme--tmpl"),
        () -> {
          seen.add(builds(shell).status(acme()));
          return null;
        });

    assertEquals(TemplateBuilds.State.BUILDING, seen.getFirst().state());
    assertEquals(7, seen.getFirst().build().orElseThrow().step());
    assertFalse(shell.invocations().stream().anyMatch(line -> line.contains("sail-strip")));
  }

  /** A create holds the template's lock while it checks the version: that is no build. */
  @Test
  void aHeldLockWithAnEndedRecordIsNoBuild() throws Exception {
    var shell = incus(ZFS, current("v1", inputs()), snapshots("v1"));
    write(running("dana", 20).ended(TemplateRecord.Outcome.BUILT, AT, null, null));
    var seen = new ArrayList<TemplateBuilds.Status>();

    HeldLocks.whileHeld(
        ContainerConfig.lockOf(locks(), "acme--tmpl"),
        () -> {
          seen.add(builds(shell).status(acme()));
          return null;
        });

    assertEquals(TemplateBuilds.State.READY, seen.getFirst().state());
  }

  /**
   * A build cut short (its record has no outcome, its lock is free) is ended by the first read: the
   * template is stripped and searched for its builder's tokens and stopped, and only then is the
   * record ended as interrupted.
   */
  @Test
  void aBuildCutShortIsStrippedSearchedAndStoppedBeforeItsRecordSaysInterrupted() throws Exception {
    var shell = incus(ZFS, template("Running", ""), "[]");
    write(running("dana", 9));

    var status = builds(shell).status(acme());

    var calls = shell.invocations();
    var strip = indexOf(calls, "sail-strip");
    var search = indexOf(calls, "sail-held");
    var stop = calls.indexOf("incus stop acme--tmpl");
    assertTrue(strip >= 0 && strip < search && search < stop, String.valueOf(calls));
    assertTrue(shell.inputs().contains("ghp_dana\n"), "searched for its builder's token");
    assertEquals(TemplateBuilds.State.FAILED, status.state());
    var ended = status.build().orElseThrow();
    assertEquals(TemplateRecord.Outcome.INTERRUPTED, ended.outcome());
    assertEquals("The build stopped when the runner restarted.", ended.reason());
    assertEquals(9, ended.step());
  }

  @Test
  void aCutBuildWhoseTemplateStillHoldsATokenIsLeftForTheNextReadAndCountsAsFailed()
      throws Exception {
    var shell =
        incus(ZFS, template("Stopped", ""), "[]")
            .on("sail-held", new ShellExec.Result(1, "a git token", ""));
    write(running("dana", 9));

    var status = builds(shell).status(acme());

    assertEquals(TemplateBuilds.State.FAILED, status.state());
    assertTrue(record(recordFile()).running(), "the record waits for a read that can end it");
    assertEquals(1, logged.size());
    assertTrue(shell.invocations().contains("incus start acme--tmpl"));
    assertTrue(shell.invocations().contains("incus stop acme--tmpl"), "it is never left running");
  }

  /**
   * A read that meets a cut build while sail-api, starting, is still cleaning its template waits
   * for that end and reads it interrupted, never as a build under way: the cleanup holds the lock.
   */
  @Test
  void aReadThatMeetsACutBuildBeingEndedWaitsForItsEnd() throws Exception {
    var scripted = incus(ZFS, template("Running", ""), "[]");
    var stripping = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    var shell =
        new ShellExec() {
          @Override
          public Result exec(List<String> command) throws IOException {
            return exec(command, null, null);
          }

          @Override
          public Result exec(List<String> command, Path workDir, java.time.Duration timeout)
              throws IOException {
            if (String.join(" ", command).contains("sail-strip")) {
              stripping.countDown();
              try {
                assertTrue(release.await(10, TimeUnit.SECONDS));
              } catch (InterruptedException e) {
                throw new IOException(e);
              }
            }
            return scripted.exec(command, workDir, timeout);
          }

          @Override
          public boolean isDryRun() {
            return false;
          }
        };
    write(running("dana", 9));
    var builds = builds(shell);
    var starting = Thread.ofPlatform().start(() -> builds.endCutShort(List.of("acme")));
    assertTrue(stripping.await(10, TimeUnit.SECONDS), "the cleanup reached the strip");

    var read = new java.util.concurrent.FutureTask<>(() -> builds.status(acme()));
    var reader = Thread.ofPlatform().start(read);
    var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
    while (!waitsOnTheCleanup(reader) && !read.isDone()) {
      assertTrue(System.nanoTime() < deadline, "the read never waited");
      Thread.onSpinWait();
    }
    assertFalse(read.isDone(), "the read answered while the cleanup held the template");
    release.countDown();
    var status = read.get(30, TimeUnit.SECONDS);
    starting.join(TimeUnit.SECONDS.toMillis(30));

    assertEquals(TemplateBuilds.State.FAILED, status.state());
    assertEquals(TemplateRecord.Outcome.INTERRUPTED, status.build().orElseThrow().outcome());
  }

  @Test
  void sailApiStartingEndsEveryCutBuildItCan() throws Exception {
    var shell = incus(ZFS, "[]", "[]");
    write(running("dana", 3));

    builds(shell).endCutShort(List.of("acme", "beta"));

    assertEquals(TemplateRecord.Outcome.INTERRUPTED, record(recordFile()).outcome());
    assertEquals(List.of(), logged);
  }

  @Test
  void aStartAnswersOnceTheBuildHoldsTheLockAndASecondStartJoinsIt() throws Exception {
    var shell = incus(ZFS, "[]", "[]");
    var builds = builds(shell);
    var inside = new CountDownLatch(1);
    var release = new CountDownLatch(1);

    var first =
        start(
            builds,
            acme(),
            "dana",
            started ->
                source(
                    "dana",
                    started,
                    (into, tracker, listener) -> {
                      listener.onStep(5, 19, "Packages installed");
                      inside.countDown();
                      assertTrue(release.await(10, TimeUnit.SECONDS));
                    }));
    assertTrue(inside.await(10, TimeUnit.SECONDS));
    var second =
        start(
            builds,
            acme(),
            "ana",
            started -> source("ana", started, (into, tracker, listener) -> {}));
    release.countDown();
    awaitTheBuildsEnd();

    assertEquals(TemplateBuilds.State.BUILDING, first.state());
    assertEquals("dana", first.build().orElseThrow().by());
    assertFalse(first.joined());
    assertEquals(TemplateBuilds.State.BUILDING, second.state());
    assertTrue(second.joined());
    assertEquals("dana", second.build().orElseThrow().by());
    assertEquals(1, provisions.get(), "one build, joined");
    assertEquals(TemplateRecord.Outcome.BUILT, record(recordFile()).outcome());
  }

  @Test
  void aStartJoinsABuildAnotherProcessRuns() throws Exception {
    write(running("root", 4));
    var seen = new ArrayList<TemplateBuilds.Status>();

    HeldLocks.whileHeld(
        ContainerConfig.lockOf(locks(), "acme--tmpl"),
        () -> {
          seen.add(
              start(
                  builds(incus(ZFS, "[]", "[]")),
                  acme(),
                  "dana",
                  started -> source("dana", started, (into, tracker, listener) -> {})));
          return null;
        });

    assertTrue(seen.getFirst().joined());
    assertEquals("root", seen.getFirst().build().orElseThrow().by());
    assertEquals(0, provisions.get());
  }

  @Test
  void aStartOfATemplateBuiltFromTheDefinitionAsItIsBuildsNothingAndSaysSo() throws Exception {
    var shell = incus(ZFS, current("v3", inputs()), snapshots("v3"));

    var status =
        start(
            builds(shell),
            acme(),
            "dana",
            started -> source("dana", started, (into, tracker, listener) -> {}));

    assertEquals(TemplateBuilds.State.READY, status.state());
    assertEquals(TemplateRecord.Outcome.CURRENT, status.build().orElseThrow().outcome());
    assertEquals(0, provisions.get());
    assertFalse(Files.exists(recordFile()), "nothing built, nothing recorded");
  }

  @Test
  void aStartRefusedBeforeTheBuildHoldsTheLockIsThrownToTheCaller() throws Exception {
    var refused =
        assertThrows(
            IllegalStateException.class,
            () ->
                start(
                    builds(incus(DIR_ONLY, "[]", "[]")),
                    acme(),
                    "dana",
                    started -> source("dana", started, (into, tracker, listener) -> {})));

    assertTrue(refused.getMessage().contains("no ZFS pool"), refused.getMessage());
    assertFalse(Files.exists(recordFile()));
  }

  @Test
  void aRecordReadBackIsTheRecordWrittenAndAnUnreadableOneIsNone() throws Exception {
    var ended =
        running("dana", 13)
            .keeping(2)
            .ended(
                TemplateRecord.Outcome.FAILED,
                AT,
                "refused",
                Remedy.of("Store one with ", Remedy.sail("git login", "github.com"), "."));
    write(ended);

    assertEquals(ended, record(recordFile()));
    Files.writeString(recordFile(), "{not json");
    assertEquals(Optional.empty(), TemplateRecord.read(recordFile()));
    assertEquals(
        Map.of("by", "dana", "outcome", "failed", "kept", 2),
        Map.of(
            "by", ended.toMap().get("by"),
            "outcome", ended.toMap().get("outcome"),
            "kept", ended.toMap().get("kept")));
  }

  private static TemplateBuilds.Status start(
      TemplateBuilds builds,
      ProjectDefinition config,
      String by,
      java.util.function.Function<Consumer<TemplateRecord>, WorkspaceTemplate.Source> source)
      throws Exception {
    return Actor.call(
        new Actor(by, Role.ADMIN, Actor.Lane.API), () -> builds.start(config, by, source));
  }

  /** Whether {@code thread} is parked waiting for the end another thread is making. */
  private static boolean waitsOnTheCleanup(Thread thread) {
    return java.util.Arrays.stream(thread.getStackTrace())
        .anyMatch(
            frame ->
                frame.getClassName().equals(java.util.concurrent.CompletableFuture.class.getName())
                    && Set.of("get", "timedGet").contains(frame.getMethodName()));
  }

  private static int indexOf(List<String> calls, String part) {
    for (var i = 0; i < calls.size(); i++) {
      if (calls.get(i).contains(part)) {
        return i;
      }
    }
    return -1;
  }

  private void awaitTheBuildsEnd() throws Exception {
    try (var ended =
        FileMutex.acquireUntil(
                ContainerConfig.lockOf(locks(), "acme--tmpl"),
                System.nanoTime() + TimeUnit.SECONDS.toNanos(10),
                System::nanoTime)
            .orElseThrow()) {
      assertTrue(record(recordFile()).outcome() != null, "the build ended under its lock");
    }
  }
}
