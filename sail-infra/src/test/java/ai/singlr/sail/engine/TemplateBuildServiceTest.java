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
import ai.singlr.sail.config.HostYaml;
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
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A template build as both doors run it: its record kept at every step and ended with words for a
 * person, a build cut short ended by the first read that finds it, and the route's start or join.
 */
class TemplateBuildServiceTest {

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

  private TemplateBuildService builds(ShellExec shell) {
    return new TemplateBuildService(
        records(),
        lane(shell),
        shell,
        handle -> "dana".equals(handle) ? List.of("ghp_dana") : List.of(),
        logged::add);
  }

  private WorkspaceTemplate.Source source(
      String by, Runnable started, TemplateBuild.Provision provision) {
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
    Runnable told = () -> started.add(record(recordFile()));

    var built =
        lane(shell)
            .ensure(
                acme(),
                source(
                    "dana",
                    told,
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
                        () -> {},
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
                        () -> {},
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

    lane(shell).ensure(acme(), source("dana", () -> {}, (into, tracker, listener) -> {}));

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
                () -> {},
                true);

    source.recorder().started();

    assertFalse(Files.exists(recordFile()));
  }

  @Test
  void aProjectWithNoTemplateAndNoBuildIsMissing() throws Exception {
    var status = builds(incus(ZFS, "[]", "[]")).status(acme());

    assertEquals(TemplateBuildService.State.MISSING, status.state());
    assertEquals(Optional.empty(), status.build());
  }

  @Test
  void aCurrentVersionIsReadyAndStaleWhenTheDefinitionChangedSince() throws Exception {
    var same = builds(incus(ZFS, current("v2", inputs()), snapshots("v1", "v2"))).status(acme());
    var older = builds(incus(ZFS, current("v2", "older"), snapshots("v2"))).status(acme());

    assertEquals(TemplateBuildService.State.READY, same.state());
    assertEquals("v2", same.current().orElseThrow().version());
    assertFalse(same.stale());
    assertEquals(TemplateBuildService.State.READY, older.state());
    assertTrue(older.stale());
  }

  /** Ruling B: a rebuild that failed while an older version serves is ready, its failure shown. */
  @Test
  void aRebuildThatFailedWhileAnOlderVersionServesIsReadyAndSaysItsFailure() throws Exception {
    write(
        running("dana", 13)
            .ended(TemplateRecord.Outcome.FAILED, AT, "github.com refused the clone.", null));

    var status = builds(incus(ZFS, current("v1", "older"), snapshots("v1"))).status(acme());

    assertEquals(TemplateBuildService.State.READY, status.state());
    assertTrue(status.stale());
    assertEquals(TemplateRecord.Outcome.FAILED, status.build().orElseThrow().outcome());
  }

  @Test
  void aFailedBuildWithNoVersionCurrentIsFailed() throws Exception {
    write(running("dana", 13).ended(TemplateRecord.Outcome.FAILED, AT, "refused", null));

    assertEquals(
        TemplateBuildService.State.FAILED, builds(incus(ZFS, "[]", "[]")).status(acme()).state());
  }

  @Test
  void aTemplateIsBuildingWhileItsLockIsHeldAndItsRecordHasNoOutcome() throws Exception {
    var shell = incus(ZFS, "[]", "[]");
    write(running("dana", 7));
    var seen = new ArrayList<TemplateBuildService.Status>();

    HeldLocks.whileHeld(
        ContainerConfig.lockOf(locks(), "acme--tmpl"),
        () -> {
          seen.add(builds(shell).status(acme()));
          return null;
        });

    assertEquals(TemplateBuildService.State.BUILDING, seen.getFirst().state());
    assertEquals(7, seen.getFirst().build().orElseThrow().step());
    assertFalse(shell.invocations().stream().anyMatch(line -> line.contains("sail-strip")));
  }

  /** A create holds the template's lock while it checks the version: that is no build. */
  @Test
  void aHeldLockWithAnEndedRecordIsNoBuild() throws Exception {
    var shell = incus(ZFS, current("v1", inputs()), snapshots("v1"));
    write(running("dana", 20).ended(TemplateRecord.Outcome.BUILT, AT, null, null));
    var seen = new ArrayList<TemplateBuildService.Status>();

    HeldLocks.whileHeld(
        ContainerConfig.lockOf(locks(), "acme--tmpl"),
        () -> {
          seen.add(builds(shell).status(acme()));
          return null;
        });

    assertEquals(TemplateBuildService.State.READY, seen.getFirst().state());
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
    var builds = builds(shell);

    builds.endCutShort(List.of("acme"));
    var status = builds.status(acme());

    var calls = shell.invocations();
    var strip = indexOf(calls, "sail-strip");
    var search = indexOf(calls, "sail-held");
    var stop = calls.indexOf("incus stop acme--tmpl");
    assertTrue(strip >= 0 && strip < search && search < stop, String.valueOf(calls));
    assertTrue(shell.inputs().contains("ghp_dana\n"), "searched for its builder's token");
    assertEquals(TemplateBuildService.State.FAILED, status.state());
    var ended = status.build().orElseThrow();
    assertEquals(TemplateRecord.Outcome.INTERRUPTED, ended.outcome());
    assertEquals("The build stopped when the runner restarted.", ended.reason());
    assertEquals(9, ended.step());
  }

  /**
   * A cut build whose template cannot be cleaned is stopped all the same, its record says so, and
   * nothing tries again: the next build strips it.
   */
  @Test
  void aCutBuildWhoseTemplateCannotBeCleanedIsStoppedSaidAndNeverTriedAgain() throws Exception {
    var shell =
        incus(ZFS, template("Stopped", ""), "[]")
            .on("sail-held", new ShellExec.Result(1, "a git token", ""));
    write(running("dana", 9));
    var builds = builds(shell);

    builds.endCutShort(List.of("acme"));
    var starts = shell.invocations().stream().filter("incus start acme--tmpl"::equals).count();
    builds.endCutShort(List.of("acme"));
    var status = builds.status(acme());

    assertEquals(1, starts);
    assertEquals(
        starts,
        shell.invocations().stream().filter("incus start acme--tmpl"::equals).count(),
        "a cleanup that failed is never run again");
    assertTrue(shell.invocations().contains("incus stop acme--tmpl"), "it is never left running");
    assertEquals(1, logged.size());
    assertEquals(TemplateBuildService.State.FAILED, status.state());
    assertEquals(TemplateBuild.UNCLEANED, status.build().orElseThrow().reason());
    assertEquals(TemplateRecord.Outcome.INTERRUPTED, record(recordFile()).outcome());
  }

  /**
   * A read never cleans and never waits: a cut build no one has ended yet reads at once as
   * interrupted, saying when it is cleaned, and Incus is only read, never changed.
   */
  @Test
  void aReadOfACutBuildAnswersAtOnceAndOnlyReadsIncus() throws Exception {
    var shell = incus(ZFS, template("Running", ""), "[]");
    write(running("dana", 9));
    var builds = builds(shell);

    var status = builds.status(acme());
    var states = builds.states(List.of("acme"));

    assertEquals(TemplateBuildService.State.FAILED, status.state());
    assertEquals(TemplateBuildService.State.FAILED, states.get("acme"));
    var told = status.build().orElseThrow();
    assertEquals(TemplateRecord.Outcome.INTERRUPTED, told.outcome());
    assertEquals(TemplateBuild.CUT_PENDING, told.reason());
    assertEquals(9, told.step());
    assertTrue(record(recordFile()).running(), "a read writes nothing");
    assertEquals(
        List.of(),
        shell.invocations().stream()
            .filter(c -> !c.startsWith("incus list") && !c.startsWith("incus storage list"))
            .filter(c -> !c.startsWith("incus snapshot list") && !c.startsWith("incus query"))
            .toList(),
        "a read only lists");
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

    assertEquals(TemplateBuildService.State.BUILDING, first.state());
    assertEquals("dana", first.build().orElseThrow().by());
    assertFalse(first.joined());
    assertEquals(TemplateBuildService.State.BUILDING, second.state());
    assertTrue(second.joined());
    assertEquals("dana", second.build().orElseThrow().by());
    assertEquals(1, provisions.get(), "one build, joined");
    assertEquals(TemplateRecord.Outcome.BUILT, record(recordFile()).outcome());
  }

  /** A build another process runs (its record has no outcome, its lock is held) is joined. */
  @Test
  void aBuildAnotherProcessRunsIsJoinedWithNothingOfItsOwn() throws Exception {
    write(running("root", 4));
    var seen = new ArrayList<Optional<TemplateBuildService.Status>>();

    HeldLocks.whileHeld(
        ContainerConfig.lockOf(locks(), "acme--tmpl"),
        () -> {
          seen.add(builds(incus(ZFS, "[]", "[]")).joining(acme()));
          return null;
        });

    var joined = seen.getFirst().orElseThrow();
    assertTrue(joined.joined());
    assertEquals("root", joined.build().orElseThrow().by());
    assertEquals(Optional.empty(), builds(incus(ZFS, "[]", "[]")).joining(acme()));
    assertEquals(0, provisions.get());
  }

  /**
   * A second request that arrives while the first is still reading Incus, before its build holds
   * the lock, waits for it and joins: it is never answered that there is no template.
   */
  @Test
  void aSecondStartBeforeTheFirstHoldsTheLockWaitsAndJoins() throws Exception {
    var reading = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    var building = new CountDownLatch(1);
    var finish = new CountDownLatch(1);
    var scripted = incus(ZFS, "[]", "[]");
    var shell = holdingAt("incus storage list", scripted, reading, release);
    var builds = builds(shell);
    var first =
        new FutureTask<>(
            () ->
                start(
                    builds,
                    acme(),
                    "dana",
                    started ->
                        source(
                            "dana",
                            started,
                            (into, tracker, listener) -> {
                              building.countDown();
                              assertTrue(finish.await(10, TimeUnit.SECONDS));
                            })));
    Thread.ofPlatform().start(first);
    assertTrue(reading.await(10, TimeUnit.SECONDS), "the first build reads Incus");

    var second = new FutureTask<>(() -> builds.joining(acme()));
    var asking = Thread.ofPlatform().start(second);
    var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
    while (asking.getState() != Thread.State.TIMED_WAITING && !second.isDone()) {
      assertTrue(System.nanoTime() < deadline, "the second request never waited");
      Thread.onSpinWait();
    }
    release.countDown();
    assertTrue(building.await(10, TimeUnit.SECONDS));
    var joined = second.get(30, TimeUnit.SECONDS).orElseThrow();
    finish.countDown();
    first.get(30, TimeUnit.SECONDS);
    awaitTheBuildsEnd();

    assertEquals(TemplateBuildService.State.BUILDING, joined.state());
    assertTrue(joined.joined());
    assertEquals(1, provisions.get());
  }

  /** Nothing to build: the answer is the template as a read right after it says it. */
  @Test
  void aStartOfATemplateBuiltFromTheDefinitionAsItIsBuildsNothingAndAnswersAsAReadDoes()
      throws Exception {
    var shell = incus(ZFS, current("v3", inputs()), snapshots("v3"));
    var builds = builds(shell);

    var status =
        start(builds, acme(), "dana", started -> source("dana", started, (into, tracker, l) -> {}));

    assertEquals(TemplateBuildService.State.READY, status.state());
    assertEquals(builds.status(acme()), status);
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

  /**
   * The chain the GCP proof could not reach, on the scripted shell through the real provisioner: a
   * clone the host refuses fails the build at step 13 with the token sentence; once a token is
   * stored, Build Again resumes at that step with twelve kept, writes the new token for the clone,
   * searches the built template for it, and ends built.
   */
  @Test
  void aRefusedCloneThenAStoredTokenResumesAtTheCloneAndEndsBuilt() throws Exception {
    var refused =
        chainShell("[]")
            .onFail("git clone", "fatal: Authentication failed")
            .on("git ls-remote", new ShellExec.Result(128, "", "fatal: could not read Username"))
            .asks(url -> true);
    assertThrows(
        IllegalStateException.class,
        () -> lane(refused).ensure(acme(), chainSource(refused, Map.of())));

    var failed = record(recordFile());
    assertEquals(TemplateRecord.Outcome.FAILED, failed.outcome());
    assertEquals(13, failed.step());
    assertEquals(
        "github.com did not give acme's repo app: the repo may ask for a token, or may not exist.",
        failed.reason());

    var stored = chainShell(template("Stopped", "")).asks(url -> true);
    var built =
        lane(stored).ensure(acme(), chainSource(stored, Map.of("github.com", "ghp_stored_now")));

    assertTrue(built.built());
    var ended = record(recordFile());
    assertEquals(TemplateRecord.Outcome.BUILT, ended.outcome());
    assertEquals(12, ended.kept(), "it resumed at the clone");
    assertTrue(
        stored.inputs().stream().anyMatch(input -> input.contains("ghp_stored_now")),
        "the clone was given the token stored since, and the template searched for it");
    assertEquals(
        1,
        stored.invocations().stream().filter(c -> c.contains("git clone")).count(),
        "the steps before the clone were not run again");
  }

  /** The scripted Incus of a template build that runs the real provisioner. */
  private static ScriptedShellExecutor chainShell(String instances) {
    return incus(ZFS, instances, "[]")
        .onFail("incus info acme--tmpl", "not found")
        .onOk("ip -4 addr show")
        .onFail("id dev", "no such user")
        .onOk("id -u dev", "1000")
        .onFail("which podman", "")
        .onFail("test -f /etc/profile.d/testcontainers.sh", "")
        .onFail("test -d /home/dev/workspace/app", "")
        .onFail("crontab -l", "no crontab for dev")
        .onOk("mktemp", "/tmp/sail-crontab.test\n");
  }

  private WorkspaceTemplate.Source chainSource(ShellExec shell, Map<String, String> stored) {
    var tokens = CheckedTokens.of(acme(), Optional.of("dana"), stored, url -> false, Map.of());
    var host =
        new HostYaml(
            "dir", "devpool", null, "incusbr0", "singlr-base", "ubuntu/24.04", "6.0", null, null);
    return records()
        .source(
            acme(),
            "devpool",
            Set.of("acme")::contains,
            tokens.values(),
            progress(),
            "dana",
            ProvisionListener.NOOP,
            TemplateBuild.provision(
                shell, host, acme(), tokens, ai.singlr.sail.api.ProjectSkills.none()),
            () -> {},
            false);
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

  private static TemplateBuildService.Status start(
      TemplateBuildService builds,
      ProjectDefinition config,
      String by,
      Function<Runnable, WorkspaceTemplate.Source> source)
      throws Exception {
    return Actor.call(
        new Actor(by, Role.ADMIN, Actor.Lane.API), () -> builds.start(config, source));
  }

  /**
   * {@code scripted}, but the first command holding {@code at} tells {@code reached} and waits for
   * {@code release} before it answers.
   */
  private static ShellExec holdingAt(
      String at, ScriptedShellExecutor scripted, CountDownLatch reached, CountDownLatch release) {
    var held = new java.util.concurrent.atomic.AtomicBoolean();
    return new ShellExec() {
      @Override
      public Result exec(List<String> command) throws IOException {
        return exec(command, null, null);
      }

      @Override
      public Result exec(List<String> command, Path workDir, java.time.Duration timeout)
          throws IOException {
        if (String.join(" ", command).contains(at) && held.compareAndSet(false, true)) {
          reached.countDown();
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
