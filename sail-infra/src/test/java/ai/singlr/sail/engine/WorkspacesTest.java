/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.sail.common.DateTimeUtils;
import ai.singlr.sail.config.ProjectDefinition;
import ai.singlr.sail.config.YamlUtil;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import java.util.function.Predicate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.api.io.TempDir;

/**
 * A project's workspaces through the one lane that makes them, on a scripted Incus: the exact
 * command of every change, read back as Incus would answer it. The box has a {@code dir} pool its
 * base profile uses and a ZFS pool beside it unless a test says otherwise.
 */
class WorkspacesTest {

  private static final Predicate<String> NO_RUN = spec -> false;

  private static final Instant NOW = Instant.parse("2026-11-12T10:00:00Z");
  private static final String INPUTS = "a1b2c3";
  private static final Map<String, String> TEMPLATE_OF = Map.of("user.sail.template.of", "acme");
  private static final String DIR_AND_ZFS =
      """
      [{"name": "devpool", "driver": "dir", "config": {"source": "/var/lib/incus/storage-pools/devpool"}},
       {"name": "tank", "driver": "zfs", "config": {"source": "/var/lib/incus/disks/tank.img", "zfs.pool_name": "tank"}}]
      """;
  private static final String DIR_ONLY =
      """
      [{"name": "devpool", "driver": "dir", "config": {"source": "/var/lib/incus/storage-pools/devpool"}}]
      """;

  @TempDir Path tempDir;
  private Clock clock;
  private final List<ProjectProvisioner.Instance> builtInto = new ArrayList<>();
  private final List<Boolean> builtFresh = new ArrayList<>();

  @BeforeEach
  void pinTheClock() {
    clock = DateTimeUtils.clock;
    DateTimeUtils.clock = Clock.fixed(NOW, ZoneOffset.UTC);
  }

  @AfterEach
  void restoreTheClock() {
    DateTimeUtils.clock = clock;
  }

  private static ProjectDefinition acme() {
    return new ProjectDefinition(
        "acme",
        "Acme",
        new ProjectDefinition.Resources(2, "4GB", "50GB"),
        "ubuntu/24.04",
        null,
        null,
        List.of(
            new ProjectDefinition.Repo("https://github.com/acme/app.git", "app", null),
            new ProjectDefinition.Repo("https://github.com/acme/site.git", "web/site", "develop")),
        null,
        null,
        null,
        null);
  }

  /** The clock phases are read on: each read the next of {@code nanos}, and none past them. */
  private static LongSupplier ticks(long... nanos) {
    var at = new AtomicInteger();
    return () -> {
      var next = at.getAndIncrement();
      if (next >= nanos.length) {
        throw new IllegalStateException("the clock would pass its horizon");
      }
      return nanos[next];
    };
  }

  private static long ms(long millis) {
    return TimeUnit.MILLISECONDS.toNanos(millis);
  }

  private WorkspaceTemplate.Source source(String basePool, Set<String> projects) {
    return new WorkspaceTemplate.Source(
        basePool,
        INPUTS,
        projects::contains,
        (into, fresh) -> {
          builtInto.add(into);
          builtFresh.add(fresh);
        });
  }

  private WorkspaceTemplate.Source source() {
    return source("devpool", Set.of("acme"));
  }

  private static ScriptedShellExecutor incus(String pools, String instances, String snapshots) {
    return new ScriptedShellExecutor(new ShellExec.Result(0, "", ""))
        .onOk("incus storage list --format json", pools)
        .onOk("incus list --format json", instances)
        .onOk("incus snapshot list acme--tmpl --format json", snapshots);
  }

  private static String instance(String name, String status, String pool, String config) {
    return """
        {"name": "%s", "status": "%s", "created_at": "2026-11-12T09:00:00.123456789Z",
         "config": {%s}, "expanded_devices": {"root": {"path": "/", "pool": "%s", "type": "disk"}},
         "state": {"network": {"eth0": {"addresses": [{"family": "inet", "address": "10.0.0.%d"}]}}}}"""
        .formatted(name, status, config, pool, Math.abs(name.hashCode() % 200) + 2);
  }

  private static String workspace(String project, String name, String status, String template) {
    return instance(
        project + "-" + name,
        status,
        "tank",
        "\"user.sail.project\": \"%s\", \"user.sail.workspace\": \"%s\", \"user.sail.template\": \"%s\", \"user.sail.created_ms\": \"1762941600000\", \"user.sail.ready\": \"1762941601000\""
            .formatted(project, name, template));
  }

  private static String template(String current, String inputs) {
    return instance(
        "acme--tmpl",
        "Stopped",
        "tank",
        ("\"user.sail.template.of\": \"acme\", \"user.sail.template.current\": \"%s\","
                + " \"user.sail.template.inputs\": \"%s\"")
            .formatted(current, inputs));
  }

  private static String list(String... instances) {
    return "[" + String.join(",", instances) + "]";
  }

  private static String snapshots(String... names) {
    var entries = new ArrayList<String>();
    for (var name : names) {
      entries.add("{\"name\": \"" + name + "\", \"created_at\": \"2026-11-12T08:00:00Z\"}");
    }
    return "[" + String.join(",", entries) + "]";
  }

  private Workspaces workspaces(ShellExec shell, LongSupplier nanos) {
    return new Workspaces(shell, shell, nanos, locks());
  }

  private WorkspaceTemplate templates(ShellExec shell) {
    return new WorkspaceTemplate(shell, shell, locks());
  }

  private Path locks() {
    return tempDir.resolve("locks");
  }

  private static List<String> prepareArgv() {
    return List.of(
        "incus",
        "exec",
        "acme-fix-login",
        "--",
        "bash",
        "-c",
        Workspaces.PREPARE,
        "sail-workspace",
        "acme",
        "fix-login",
        "app",
        "",
        "web/site",
        "develop");
  }

  private static List<String> copyArgv(String version) {
    return List.of(
        "incus",
        "copy",
        "acme--tmpl/" + version,
        "acme-fix-login",
        "-c",
        "user.sail.project=acme",
        "-c",
        "user.sail.workspace=fix-login",
        "-c",
        "user.sail.template=" + version,
        "-c",
        "user.sail.created_ms=" + NOW.toEpochMilli(),
        "-c",
        "user.sail.creating=1");
  }

  private static int indexOf(ScriptedShellExecutor shell, List<String> argv) {
    return shell.arguments().indexOf(argv);
  }

  @Test
  void aFirstCreateBuildsTheTemplateAsV1OnTheZfsPoolThenClonesItsSnapshotAndSaysWhatItTook()
      throws Exception {
    var shell =
        incus(DIR_AND_ZFS, list(instance("acme", "Running", "devpool", "")), "[]")
            .onOk(
                "sail-workspace acme fix-login",
                "sail-step start 1762941600000\nsail-step identity 1762941600150\nfetched\n"
                    + "sail-step network 1762941600400\nsail-count fetch_retries 2\n"
                    + "sail-step bus x\n");

    var created =
        workspaces(
                shell,
                ticks(0, ms(210), ms(1110), ms(1110), ms(1200), ms(1300), ms(1400), ms(1410)))
            .create(acme(), "fix-login", source(), tempDir.resolve("no-files"), "eng");

    assertEquals(
        List.of(new ProjectProvisioner.Instance("acme--tmpl", Optional.of("tank"), TEMPLATE_OF)),
        builtInto);
    assertEquals(List.of(true), builtFresh);
    var stop = indexOf(shell, List.of("incus", "stop", "acme--tmpl"));
    var snapshot = indexOf(shell, List.of("incus", "snapshot", "create", "acme--tmpl", "v1"));
    var record =
        indexOf(
            shell,
            List.of(
                "incus",
                "config",
                "set",
                "acme--tmpl",
                "user.sail.template.current=v1",
                "user.sail.template.inputs=" + INPUTS,
                "user.sail.template.last=v1"));
    var copy = indexOf(shell, copyArgv("v1"));
    var start = indexOf(shell, List.of("incus", "start", "acme-fix-login"));
    var prepare = indexOf(shell, prepareArgv());
    assertTrue(
        0 <= stop
            && stop < record
            && record < snapshot
            && snapshot < copy
            && copy < start
            && start < prepare,
        String.join("\n", shell.invocations()));
    assertEquals(
        new Workspaces.Created(
            "acme",
            "fix-login",
            "acme-fix-login",
            new WorkspaceTemplate.Template("acme", "acme--tmpl", "v1", INPUTS, "tank", true),
            new Workspaces.Timings(
                210L,
                900,
                300,
                1410,
                Map.of(
                    "identity", 150L,
                    "network", 250L,
                    "fetch_retries", 2L,
                    "files", 90L,
                    "machinery", 100L,
                    "mark", 100L))),
        created);
    assertEquals(
        List.of("identity", "network", "fetch_retries", "files", "machinery", "mark"),
        List.copyOf(created.timings().readySteps().keySet()),
        "the steps in the order they ran");
  }

  @Test
  void aCurrentTemplateIsClonedWithoutABuild() throws Exception {
    var shell =
        incus(
            DIR_AND_ZFS,
            list(instance("acme", "Running", "devpool", ""), template("v1", INPUTS)),
            snapshots("v1"));

    var created =
        workspaces(shell, ticks(0, ms(150), ms(500), ms(500), ms(600), ms(650), ms(690), ms(700)))
            .create(acme(), "fix-login", source(), tempDir, "eng");

    assertEquals(List.of(), builtInto);
    assertEquals(
        new WorkspaceTemplate.Template("acme", "acme--tmpl", "v1", INPUTS, "tank", false),
        created.template());
    assertTrue(indexOf(shell, copyArgv("v1")) >= 0, String.join("\n", shell.invocations()));
    assertFalse(
        shell.invocations().stream()
            .anyMatch(
                c ->
                    c.startsWith("incus snapshot create")
                        || c.startsWith("incus config set acme--tmpl")),
        "a current template is neither snapshotted nor recorded again");
    assertEquals(150L, created.timings().cloneMs());
  }

  @Test
  void anEditedDefinitionRebuildsTheTemplateAsTheNextVersionAndLeavesOlderWorkspacesAlone()
      throws Exception {
    var shell =
        incus(
            DIR_AND_ZFS,
            list(
                instance("acme", "Running", "devpool", ""),
                template("v1", "an-older-definition"),
                workspace("acme", "older", "Running", "v1")),
            snapshots("v1"));

    var created =
        workspaces(shell, ticks(0, 1, 2, 3, 4, 5, 6, 7))
            .create(acme(), "fix-login", source(), tempDir, "eng");

    assertEquals(List.of(false), builtFresh);
    var start = indexOf(shell, List.of("incus", "start", "acme--tmpl"));
    assertTrue(start >= 0, "a stopped template is started before its provisioner runs");
    assertTrue(indexOf(shell, List.of("incus", "snapshot", "create", "acme--tmpl", "v2")) > start);
    assertTrue(indexOf(shell, copyArgv("v2")) >= 0, String.join("\n", shell.invocations()));
    assertEquals("v2", created.template().version());
    assertFalse(
        shell.invocations().stream().anyMatch(c -> c.contains("acme-older")),
        "a workspace cloned from v1 is not touched by the rebuild");
  }

  @Test
  void theNextVersionFollowsTheHighestSnapshotSoAStrayOneIsNeverReused() throws Exception {
    var shell =
        incus(
            DIR_AND_ZFS,
            list(template("v1", "an-older-definition")),
            snapshots("v1", "v3", "before-upgrade", "v0"));

    var template = templates(shell).ensure(acme(), source());

    assertEquals("v4", template.version());
  }

  @Test
  void aTemplateBuiltFromTheDefinitionAsItIsIsLeftAsItIs() throws Exception {
    var shell = incus(DIR_AND_ZFS, list(template("v2", INPUTS)), snapshots("v1", "v2"));

    var template = templates(shell).ensure(acme(), source());

    assertEquals(
        new WorkspaceTemplate.Template("acme", "acme--tmpl", "v2", INPUTS, "tank", false),
        template);
    assertEquals(List.of(), builtInto);
  }

  @Test
  void aCurrentVersionWhoseSnapshotIsGoneIsBuiltAgain() throws Exception {
    var shell = incus(DIR_AND_ZFS, list(template("v2", INPUTS)), snapshots("v1"));

    var template = templates(shell).ensure(acme(), source());

    assertTrue(template.built());
    assertEquals("v2", template.version());
  }

  private static WorkspaceTemplate.Source failing() {
    return new WorkspaceTemplate.Source(
        "devpool",
        INPUTS,
        name -> false,
        (into, fresh) -> {
          throw new IOException("apt-get failed");
        });
  }

  @Test
  void aFailedBuildLeavesThePreviousVersionCurrentMakesNoSnapshotAndStopsTheTemplateItStarted() {
    var shell = incus(DIR_AND_ZFS, list(template("v1", "an-older-definition")), snapshots("v1"));

    var failure = assertThrows(IOException.class, () -> templates(shell).ensure(acme(), failing()));

    assertEquals("apt-get failed", failure.getMessage());
    assertEquals(
        List.of("incus start acme--tmpl", "incus stop acme--tmpl"),
        shell.invocations().stream()
            .filter(
                c ->
                    !c.startsWith("incus list")
                        && !c.startsWith("incus storage list")
                        && !c.startsWith("incus query")
                        && !c.startsWith("incus snapshot list"))
            .toList());
  }

  @Test
  void aFailedBuildLeavesATemplateItFoundRunningRunningAndOneItLaunchedStopped() {
    var running =
        instance(
            "acme--tmpl",
            "Running",
            "tank",
            "\"user.sail.template.of\": \"acme\", \"user.sail.template.current\": \"v1\"");
    var found = incus(DIR_AND_ZFS, list(running), snapshots("v1"));
    var none = incus(DIR_AND_ZFS, "[]", "[]").onFail("incus stop acme--tmpl", "not found");

    assertThrows(IOException.class, () -> templates(found).ensure(acme(), failing()));
    var fresh = assertThrows(IOException.class, () -> templates(none).ensure(acme(), failing()));

    assertTrue(found.invocations().stream().noneMatch(c -> c.startsWith("incus stop")));
    assertTrue(none.invocations().contains("incus stop acme--tmpl"));
    assertEquals(
        "Failed to stop container 'acme--tmpl': not found", fresh.getSuppressed()[0].getMessage());
  }

  @Test
  void aVersionWhoseRecordCannotBeWrittenIsNeverSnapshottedSoThePreviousStaysCurrent() {
    var shell =
        incus(DIR_AND_ZFS, list(template("v1", "an-older-definition")), snapshots("v1"))
            .onFail("incus config set acme--tmpl user.sail.template.current=v2", "disk full");

    var failure = assertThrows(IOException.class, () -> templates(shell).ensure(acme(), source()));

    assertEquals(
        "Failed to record v2 as acme's current template, so the version before stays current:"
            + " disk full",
        failure.getMessage());
    assertTrue(shell.invocations().stream().noneMatch(c -> c.startsWith("incus snapshot create")));
  }

  @Test
  void aVersionRecordedButNeverSnapshottedIsStaleSoTheNextBuildIsTheOneAfter() throws Exception {
    var recorded =
        instance(
            "acme--tmpl",
            "Stopped",
            "tank",
            ("\"user.sail.template.of\": \"acme\", \"user.sail.template.current\": \"v2\","
                    + " \"user.sail.template.inputs\": \"%s\", \"user.sail.template.last\": \"v2\"")
                .formatted(INPUTS));
    var shell = incus(DIR_AND_ZFS, list(recorded), snapshots("v1"));

    var template = templates(shell).ensure(acme(), source());

    assertEquals("v3", template.version());
    assertTrue(template.built());
  }

  @Test
  void theBaseProfilesPoolIsTakenWhenItIsZfs() throws Exception {
    var pools =
        """
        [{"name": "fast", "driver": "zfs", "config": {}}, {"name": "tank", "driver": "zfs", "config": {}}]
        """;
    var shell = incus(pools, "[]", "[]");

    var template = templates(shell).ensure(acme(), source("tank", Set.of("acme")));

    assertEquals("tank", template.pool());
    assertEquals(
        List.of(new ProjectProvisioner.Instance("acme--tmpl", Optional.of("tank"), TEMPLATE_OF)),
        builtInto);
  }

  @Test
  void severalZfsPoolsBesideABasePoolThatIsNotZfsAreRefused() {
    var pools =
        """
        [{"name": "devpool", "driver": "dir", "config": {}}, {"name": "fast", "driver": "zfs", "config": {}},
         {"name": "tank", "driver": "zfs", "config": {}}]
        """;
    var shell = incus(pools, "[]", "[]");

    var failure =
        assertThrows(
            IllegalStateException.class,
            () -> workspaces(shell, ticks()).create(acme(), "fix-login", source(), tempDir, "eng"));

    assertEquals(
        "Incus has the ZFS pools fast, tank and the base profile's pool 'devpool' is none of them,"
            + " so Sail cannot tell which one workspaces go on. Keep one ZFS pool beside the base"
            + " profile's, or put the base profile on the one to use.",
        failure.getMessage());
    assertEquals(List.of(), builtInto);
  }

  @Test
  void everyVerbThatMakesOrChangesOnABoxWithNoZfsPoolIsRefusedBeforeAnythingChanges() {
    var shell = incus(DIR_ONLY, list(workspace("acme", "fix-login", "Running", "v1")), "[]");
    var workspaces = workspaces(shell, ticks());
    var expected =
        "This box has no ZFS pool, and a workspace is a ZFS clone of its project's template. Add"
            + " one beside the box's pool with: sail host init --storage zfs --pool tank --disk"
            + " <device>, or with --pool-file-size <size> in place of --disk to try ZFS in a file.";

    List<Executable> verbs =
        List.of(
            () -> workspaces.create(acme(), "fix-login", source(), tempDir, "eng"),
            () -> templates(shell).ensure(acme(), source()),
            () -> workspaces.start("acme", "fix-login"),
            () -> workspaces.stop("acme", "fix-login", NO_RUN),
            () -> workspaces.delete("acme", "fix-login", NO_RUN, instance -> {}));
    for (var verb : verbs) {
      assertEquals(expected, assertThrows(IllegalStateException.class, verb).getMessage());
    }
    assertEquals(
        List.of(),
        shell.invocations().stream()
            .filter(
                c ->
                    !c.equals("incus storage list --format json")
                        && !c.startsWith("incus query /1.0/storage-pools/"))
            .toList());
    assertEquals(List.of(), builtInto);
  }

  @Test
  void aBoxWithNoZfsPoolListsItsDefaultWorkspaceAlone() throws Exception {
    var shell = incus(DIR_ONLY, list(instance("acme", "Stopped", "devpool", "")), "[]");

    var listing = workspaces(shell, ticks()).list("acme");

    assertEquals(
        List.of(
            new Workspaces.Row(
                "main", "acme", "stopped", null, "2026-11-12T09:00:00.123Z", null, null, null)),
        listing.workspaces());
    assertEquals(List.of("incus list --format json"), shell.invocations());
  }

  @Test
  void theListShowsTheDefaultWorkspaceThenEveryCloneWithItsStateTemplateTimeAndAddress()
      throws Exception {
    var shell =
        incus(
            DIR_AND_ZFS,
            list(
                workspace("acme", "second", "Stopped", "v2"),
                instance("acme", "Running", "devpool", ""),
                template("v2", INPUTS),
                workspace("acme", "fix-login", "Running", "v1"),
                workspace("acme", "paused", "Frozen", "v1"),
                workspace("bravo", "fix-login", "Running", "v1"),
                instance("acme-x", "Running", "devpool", "")),
            "[]");

    var listing = workspaces(shell, ticks()).list("acme");

    var created = "2025-11-12T10:00:00.000Z";
    assertEquals(
        new Workspaces.Listing(
            "acme",
            List.of(
                new Workspaces.Row(
                    "main",
                    "acme",
                    "running",
                    null,
                    "2026-11-12T09:00:00.123Z",
                    ip("acme"),
                    null,
                    null),
                new Workspaces.Row(
                    "fix-login",
                    "acme-fix-login",
                    "running",
                    "v1",
                    created,
                    ip("acme-fix-login"),
                    null,
                    null),
                new Workspaces.Row(
                    "paused", "acme-paused", "frozen", "v1", created, null, null, null),
                new Workspaces.Row(
                    "second", "acme-second", "stopped", "v2", created, null, null, null))),
        listing);
  }

  private static String ip(String name) {
    return "10.0.0." + (Math.abs(name.hashCode() % 200) + 2);
  }

  @Test
  void aProjectWhoseContainerIsNotMadeListsItsDefaultWorkspaceAsNotCreated() throws Exception {
    var shell =
        incus(
            DIR_AND_ZFS,
            list(
                instance(
                    "acme",
                    "Running",
                    "tank",
                    "\"user.sail.project\": \"ac\", \"user.sail.workspace\": \"me\"")),
            "[]");

    var listing = workspaces(shell, ticks()).list("acme");

    assertEquals(
        List.of(new Workspaces.Row("main", "acme", "not_created", null, null, null, null, null)),
        listing.workspaces());
  }

  @Test
  void startResumesAStoppedWorkspaceAndSaysWhatItsResumeTook() throws Exception {
    var shell = incus(DIR_AND_ZFS, list(workspace("acme", "fix-login", "Stopped", "v1")), "[]");

    var changed = workspaces(shell, ticks(ms(1000), ms(1350), ms(1800))).start("acme", "fix-login");

    assertEquals(
        new Workspaces.Changed(
            "acme",
            "fix-login",
            "acme-fix-login",
            Workspaces.Change.STARTED,
            new Workspaces.Timings(null, 350, 450, 800, Map.of())),
        changed);
    var start = indexOf(shell, List.of("incus", "start", "acme-fix-login"));
    var bus =
        indexOf(
            shell,
            List.of("incus", "exec", "acme-fix-login", "--", "bash", "-c", Workspaces.AWAIT_BUS));
    assertTrue(0 <= start && start < bus, String.join("\n", shell.invocations()));
  }

  @Test
  void startingARunningWorkspaceChangesNothing() throws Exception {
    var shell = incus(DIR_AND_ZFS, list(workspace("acme", "fix-login", "Running", "v1")), "[]");

    var changed = workspaces(shell, ticks()).start("acme", "fix-login");

    assertEquals(Workspaces.Change.ALREADY_RUNNING, changed.change());
    assertFalse(shell.invocations().stream().anyMatch(c -> c.startsWith("incus start")));
  }

  private static final String DIR_AND_MIRROR =
      """
      [{"name": "devpool", "driver": "dir", "config": {"source": "/var/lib/incus/storage-pools/devpool"}},
       {"name": "tank", "driver": "zfs", "config": {"source": "tank", "zfs.pool_name": "tank",
        "volatile.initial_source": "mirror=/dev/disk/by-id/wwn-0x5000c500a1b2c3d4-part5,/dev/disk/by-id/wwn-0x5000c500e5f6a7b8-part5"}}]
      """;

  /** What {@code verb} printed on stderr while it ran. */
  private static String stderrOf(Executable verb) throws Throwable {
    var err = System.err;
    var said = new ByteArrayOutputStream();
    System.setErr(new PrintStream(said, true, StandardCharsets.UTF_8));
    try {
      verb.execute();
    } finally {
      System.setErr(err);
    }
    return said.toString(StandardCharsets.UTF_8);
  }

  @Test
  void aWorkspaceOnADegradedPoolIsMadeAndThePoolIsSaidDegradedOnce() throws Throwable {
    var shell =
        incus(DIR_AND_MIRROR, list(template("v1", INPUTS)), snapshots("v1"))
            .onOk(
                "zpool status -P tank",
                DeviceFixtures.read("handwritten-mirror-degraded-removed.zpool-status.txt")
                    .replace("devpool", "tank"));

    var said =
        stderrOf(
            () ->
                workspaces(shell, ticks(0, 1, 2, 3, 4, 5, 6, 7))
                    .create(acme(), "fix-login", source(), tempDir.resolve("no-files"), "eng"));

    assertTrue(indexOf(shell, copyArgv("v1")) >= 0, String.join("\n", shell.invocations()));
    assertEquals(1, said.split("Pool tank is DEGRADED", -1).length - 1, said);
    assertTrue(
        said.contains(
            "sudo zpool replace tank /dev/disk/by-id/wwn-0x5000c500e5f6a7b8-part5 <new device>"),
        said);
  }

  @Test
  void aFaultedPoolRefusesEveryVerbThatChangesAWorkspaceOrTemplateAndChangesNothing() {
    var shell =
        incus(
                DIR_AND_MIRROR,
                list(template("v1", INPUTS), workspace("acme", "fix-login", "Running", "v1")),
                snapshots("v1"))
            .onOk(
                "zpool status -P tank",
                DeviceFixtures.read("handwritten-mirror-faulted.zpool-status.txt")
                    .replace("devpool", "tank"));
    var workspaces = workspaces(shell, ticks());
    var refusal =
        "Pool tank is FAULTED (/dev/disk/by-id/wwn-0x5000c500a1b2c3d4-part5 is UNAVAIL,"
            + " /dev/disk/by-id/wwn-0x5000c500e5f6a7b8-part5 is UNAVAIL), as zpool status says, so"
            + " no workspace or template is changed on it. See sudo zpool status tank.";

    for (Executable verb :
        List.<Executable>of(
            () -> workspaces.create(acme(), "second", source(), tempDir.resolve("no-files"), "eng"),
            () -> templates(shell).ensure(acme(), source()),
            () -> workspaces.stop("acme", "fix-login", NO_RUN),
            () -> workspaces.start("acme", "fix-login"),
            () -> workspaces.delete("acme", "fix-login", NO_RUN, instance -> {}))) {
      assertEquals(refusal, assertThrows(IllegalStateException.class, verb).getMessage());
    }
    assertEquals(
        List.of(),
        shell.arguments().stream()
            .filter(a -> a.getFirst().equals("incus"))
            .filter(a -> List.of("copy", "stop", "start", "delete", "launch").contains(a.get(1)))
            .toList());
  }

  @Test
  void aMirrorWhoseHealthZpoolDoesNotTellRefusesEveryChangeAndChangesNothing() {
    var scripted =
        incus(
            DIR_AND_MIRROR,
            list(template("v1", INPUTS), workspace("acme", "fix-login", "Running", "v1")),
            snapshots("v1"));
    var hanging = new TimingOutShell(scripted, "zpool status");
    var workspaces = new Workspaces(hanging, hanging, ticks(), locks());
    var refusal =
        "Pool tank is a mirror whose health Sail cannot tell: zpool status did not answer within"
            + " 10 s, so no workspace or template is changed on it. See sudo zpool status tank.";

    for (Executable verb :
        List.<Executable>of(
            () -> workspaces.create(acme(), "second", source(), tempDir.resolve("no-files"), "eng"),
            () -> workspaces.stop("acme", "fix-login", NO_RUN))) {
      assertEquals(refusal, assertThrows(IllegalStateException.class, verb).getMessage());
    }
    assertEquals(
        "Pool tank is a mirror whose health Sail cannot tell: zpool cannot be run, so no workspace"
            + " or template is changed on it. See sudo zpool status tank.",
        assertThrows(
                IllegalStateException.class,
                () ->
                    workspaces(
                            incus(
                                    DIR_AND_MIRROR,
                                    list(workspace("acme", "fix-login", "Running", "v1")),
                                    "[]")
                                .onThrow("zpool", new IOException("no zpool")),
                            ticks())
                        .stop("acme", "fix-login", NO_RUN))
            .getMessage());
    assertEquals(
        List.of(),
        scripted.arguments().stream()
            .filter(a -> a.getFirst().equals("incus"))
            .filter(a -> List.of("copy", "stop", "start", "delete", "launch").contains(a.get(1)))
            .toList());
  }

  @Test
  void stopStopsALiveWorkspaceAndLeavesAStoppedOne() throws Exception {
    var shell =
        incus(
            DIR_AND_ZFS,
            list(
                workspace("acme", "fix-login", "Frozen", "v1"),
                workspace("acme", "second", "Stopped", "v1")),
            "[]");
    var workspaces = workspaces(shell, ticks());

    assertEquals(Workspaces.Change.STOPPED, workspaces.stop("acme", "fix-login", NO_RUN).change());
    assertEquals(
        Workspaces.Change.ALREADY_STOPPED, workspaces.stop("acme", "second", NO_RUN).change());
    assertEquals(
        List.of(List.of("incus", "stop", "acme-fix-login")),
        shell.arguments().stream().filter(a -> a.get(1).equals("stop")).toList());
  }

  @Test
  void deleteRemovesTheInstanceItsKeysNameAndNeverSplitsAName() throws Exception {
    var shell =
        incus(
            DIR_AND_ZFS,
            list(
                instance(
                    "a-b-c",
                    "Running",
                    "tank",
                    "\"user.sail.project\": \"a-b\", \"user.sail.workspace\": \"c\"")),
            "[]");
    var workspaces = workspaces(shell, ticks());

    var absent =
        assertThrows(
            IllegalStateException.class,
            () -> workspaces.delete("a", "b-c", NO_RUN, instance -> {}));
    var deleted = workspaces.delete("a-b", "c", NO_RUN, instance -> {});

    assertEquals(
        "Project 'a' has no workspace 'b-c' on this box. List them with: sail project workspace"
            + " list a.",
        absent.getMessage());
    assertEquals(
        new Workspaces.Changed("a-b", "c", "a-b-c", Workspaces.Change.DELETED, null), deleted);
    assertEquals(
        List.of(List.of("incus", "delete", "a-b-c", "--force")),
        shell.arguments().stream().filter(a -> a.get(1).equals("delete")).toList());
  }

  @Test
  void aDeleteAsksOnlyOfAWorkspaceThatExistsAndDeletesNothingWhenTheAnswerIsNo() throws Exception {
    var shell = incus(DIR_AND_ZFS, list(workspace("acme", "fix", "Running", "v1")), "[]");
    var workspaces = workspaces(shell, ticks());
    var asked = new ArrayList<String>();
    Consumer<String> never =
        instance -> {
          throw new AssertionError("asked about " + instance);
        };

    assertThrows(
        IllegalArgumentException.class, () -> workspaces.delete("acme", "main", NO_RUN, never));
    assertThrows(
        IllegalStateException.class, () -> workspaces.delete("acme", "gone", NO_RUN, never));
    assertThrows(
        IllegalStateException.class,
        () ->
            workspaces.delete(
                "acme",
                "fix",
                NO_RUN,
                instance -> {
                  asked.add(instance);
                  throw new IllegalStateException("Not deleted.");
                }));

    assertEquals(List.of("acme-fix"), asked);
    assertTrue(shell.invocations().stream().noneMatch(c -> c.startsWith("incus delete")));
    workspaces.delete("acme", "fix", NO_RUN, asked::add);
    assertEquals(List.of("acme-fix", "acme-fix"), asked);
    assertEquals("incus delete acme-fix --force", shell.invocations().getLast());
  }

  @Test
  void theDefaultWorkspaceIsRefusedByEveryVerbThatWouldTouchItNamingTheProjectVerb() {
    var shell = incus(DIR_AND_ZFS, "[]", "[]");
    var workspaces = workspaces(shell, ticks());

    var delete =
        assertThrows(
            IllegalArgumentException.class,
            () -> workspaces.delete("acme", "main", NO_RUN, instance -> {}));
    var stop =
        assertThrows(IllegalArgumentException.class, () -> workspaces.stop("acme", "main", NO_RUN));
    var start =
        assertThrows(IllegalArgumentException.class, () -> workspaces.start("acme", "main"));
    var shellIn =
        assertThrows(IllegalArgumentException.class, () -> workspaces.running("acme", "main"));
    var create =
        assertThrows(
            IllegalArgumentException.class,
            () -> workspaces.create(acme(), "main", source(), tempDir, "eng"));

    var prefix =
        "'main' is acme's default workspace, the project's own container, which workspace"
            + " commands leave alone. Use: sail project ";
    assertEquals(prefix + "destroy acme.", delete.getMessage());
    assertEquals(prefix + "stop acme.", stop.getMessage());
    assertEquals(prefix + "start acme.", start.getMessage());
    assertEquals(prefix + "shell acme.", shellIn.getMessage());
    assertEquals(
        "'main' is acme's default workspace, its own container, so no new workspace takes that"
            + " name. Name the workspace for its line of work, e.g. sail project workspace create"
            + " acme fix-login.",
        create.getMessage());
    assertEquals(List.of(), shell.invocations());
  }

  @Test
  void aWorkspaceWhoseInstanceNameIsOverSixtyThreeCharactersIsRefused() {
    var project = "p".repeat(60);
    var config =
        new ProjectDefinition(
            project, null, acme().resources(), null, null, null, null, null, null, null, null);
    var shell = incus(DIR_AND_ZFS, "[]", "[]");

    var failure =
        assertThrows(
            IllegalArgumentException.class,
            () -> workspaces(shell, ticks()).create(config, "abcd", source(), tempDir, "eng"));

    assertEquals(
        "Workspace 'abcd' of "
            + project
            + " would be the instance '"
            + project
            + "-abcd', 65 characters, and Incus names an instance in at most 63. Use a shorter"
            + " name.",
        failure.getMessage());
    assertEquals(List.of(), shell.invocations());
  }

  @Test
  void aProjectWhoseTemplateNameIsOverSixtyThreeCharactersHasNoTemplate() {
    var project = "p".repeat(58);
    var config =
        new ProjectDefinition(
            project, null, acme().resources(), null, null, null, null, null, null, null, null);
    var shell = incus(DIR_AND_ZFS, "[]", "[]");

    var failure =
        assertThrows(
            IllegalArgumentException.class,
            () -> workspaces(shell, ticks()).create(config, "a", source(), tempDir, "eng"));

    assertEquals(
        project
            + "'s template would be the instance '"
            + project
            + "--tmpl', 64 characters, and Incus names an instance in at most 63. Use a shorter"
            + " name.",
        failure.getMessage());
  }

  @Test
  void aNameThatCollidesIsRefusedNamingWhatItCollidesWith() {
    var shell =
        incus(
            DIR_AND_ZFS,
            list(
                instance("acme-taken", "Running", "devpool", ""),
                workspace("acme", "fix-login", "Running", "v1")),
            "[]");
    var workspaces = workspaces(shell, ticks());
    var held = source("devpool", Set.of("acme", "acme-billing"));

    var instance =
        assertThrows(
            IllegalArgumentException.class,
            () -> workspaces.create(acme(), "taken", held, tempDir, "eng"));
    var project =
        assertThrows(
            IllegalArgumentException.class,
            () -> workspaces.create(acme(), "billing", held, tempDir, "eng"));
    var existing =
        assertThrows(
            IllegalStateException.class,
            () -> workspaces.create(acme(), "fix-login", held, tempDir, "eng"));
    var invalid =
        assertThrows(
            IllegalArgumentException.class,
            () -> workspaces.create(acme(), "Fix_Login", held, tempDir, "eng"));

    assertEquals(
        "Workspace 'taken' of acme would be the instance 'acme-taken', which Incus already has."
            + " Give the workspace another name.",
        instance.getMessage());
    assertEquals(
        "Workspace 'billing' of acme would be the instance 'acme-billing', the container of"
            + " project 'acme-billing'. Give the workspace another name.",
        project.getMessage());
    assertEquals(
        "Workspace 'fix-login' of acme exists already, as the instance 'acme-fix-login'.",
        existing.getMessage());
    assertEquals(
        "Invalid workspace name: 'Fix_Login'. Must match [a-z0-9][a-z0-9-]*, as a project name"
            + " does.",
        invalid.getMessage());
    assertEquals(List.of(), builtInto);
    assertFalse(shell.invocations().stream().anyMatch(c -> c.startsWith("incus copy")));
  }

  @Test
  void aWorkspaceThatWouldTakeATemplatesNameIsRefused() {
    var config =
        new ProjectDefinition(
            "acme-", null, acme().resources(), null, null, null, null, null, null, null, null);
    var shell = incus(DIR_AND_ZFS, "[]", "[]");

    var failure =
        assertThrows(
            IllegalArgumentException.class,
            () -> workspaces(shell, ticks()).create(config, "tmpl", source(), tempDir, "eng"));

    assertEquals(
        "Workspace 'tmpl' of acme- would be the instance 'acme--tmpl', a name kept for templates."
            + " Give the workspace another name.",
        failure.getMessage());
    assertEquals(List.of(), shell.invocations());
  }

  @Test
  void aTemplateThatWouldTakeAProjectsNameIsRefused() {
    var shell = incus(DIR_AND_ZFS, "[]", "[]");

    var failure =
        assertThrows(
            IllegalArgumentException.class,
            () -> templates(shell).ensure(acme(), source("devpool", Set.of("acme", "acme--tmpl"))));

    assertEquals(
        "acme's template would be the instance 'acme--tmpl', which is the name of project"
            + " 'acme--tmpl' on this box, so acme has no workspaces here.",
        failure.getMessage());
    assertEquals(List.of(), builtInto);
  }

  @Test
  void aTemplateThatIsNotOnZfsIsNeverCopied() {
    var shell =
        incus(
            DIR_AND_ZFS,
            list(
                instance(
                    "acme--tmpl",
                    "Stopped",
                    "devpool",
                    "\"user.sail.template.of\": \"acme\", \"user.sail.template.current\": \"v1\","
                        + " \"user.sail.template.inputs\": \""
                        + INPUTS
                        + "\"")),
            snapshots("v1"));

    var failure =
        assertThrows(
            IllegalStateException.class,
            () -> workspaces(shell, ticks()).create(acme(), "fix-login", source(), tempDir, "eng"));

    assertEquals(
        "acme--tmpl is on pool 'devpool', which is not ZFS, so a copy of it would be a full copy,"
            + " not a clone. Delete it so the next create builds it on ZFS: incus delete --force"
            + " acme--tmpl",
        failure.getMessage());
    assertFalse(shell.invocations().stream().anyMatch(c -> c.startsWith("incus copy")));
  }

  @Test
  void aWorkspaceThatCannotBeMadeItsOwnIsDeletedSoNoneIsLeftHalfMade() {
    var shell =
        incus(DIR_AND_ZFS, list(template("v1", INPUTS)), snapshots("v1"))
            .onFail("sail-workspace acme fix-login", "fatal: could not read from remote");

    var failure =
        assertThrows(
            IOException.class,
            () ->
                workspaces(shell, ticks(0, 1, 2))
                    .create(acme(), "fix-login", source(), tempDir, "eng"));

    assertEquals(
        "Workspace 'fix-login' could not be made its own: fatal: could not read from remote",
        failure.getMessage());
    assertTrue(
        indexOf(shell, List.of("incus", "delete", "acme-fix-login", "--force"))
            > indexOf(shell, prepareArgv()),
        String.join("\n", shell.invocations()));
  }

  @Test
  void aCloneIncusRefusesLeavesNothingToDelete() {
    var shell =
        incus(DIR_AND_ZFS, list(template("v1", INPUTS)), snapshots("v1"))
            .onFail("incus copy", "Error: Instance name already in use");

    var failure =
        assertThrows(
            IOException.class,
            () ->
                workspaces(shell, ticks(0)).create(acme(), "fix-login", source(), tempDir, "eng"));

    assertEquals(
        "Failed to clone acme--tmpl/v1 as 'acme-fix-login': Error: Instance name already in use",
        failure.getMessage());
    assertFalse(shell.invocations().stream().anyMatch(c -> c.startsWith("incus delete")));
  }

  @Test
  void aShellOpensOnlyInARunningWorkspace() throws Exception {
    var shell =
        incus(
            DIR_AND_ZFS,
            list(
                workspace("acme", "fix-login", "Running", "v1"),
                workspace("acme", "second", "Stopped", "v1"),
                workspace("acme", "paused", "Frozen", "v1")),
            "[]");
    var workspaces = workspaces(shell, ticks());

    assertEquals("acme-fix-login", workspaces.running("acme", "fix-login"));
    assertEquals(
        "Workspace 'second' of acme is stopped. Start it with: sail project workspace start acme"
            + " second.",
        assertThrows(IllegalStateException.class, () -> workspaces.running("acme", "second"))
            .getMessage());
    assertEquals(
        "Workspace 'paused' of acme is frozen. Start it with: sail project workspace start acme"
            + " paused.",
        assertThrows(IllegalStateException.class, () -> workspaces.running("acme", "paused"))
            .getMessage());
  }

  @Test
  void aTemplateLeftRunningByAnEarlierBuildIsProvisionedAsItIs() throws Exception {
    var running =
        instance(
            "acme--tmpl",
            "Running",
            "tank",
            "\"user.sail.template.of\": \"acme\", \"user.sail.template.current\": \"v1\","
                + " \"user.sail.template.inputs\": \"older\"");
    var shell = incus(DIR_AND_ZFS, list(running), snapshots("v1"));

    var template = templates(shell).ensure(acme(), source());

    assertEquals("v2", template.version());
    assertFalse(shell.invocations().contains("incus start acme--tmpl"));
  }

  @Test
  void aDryRunNarratesTheBuildAndTakesNoLock() throws Exception {
    var observer = incus(DIR_AND_ZFS, "[]", "[]");

    var template =
        new WorkspaceTemplate(observer, new ShellExecutor(true), locks()).ensure(acme(), source());

    assertEquals(
        new WorkspaceTemplate.Template("acme", "acme--tmpl", "v1", INPUTS, "tank", true), template);
    assertEquals(List.of(true), builtFresh);
    assertFalse(
        observer.invocations().stream().anyMatch(c -> c.startsWith("incus snapshot create")));
  }

  @Test
  void aWorkspaceThatCannotBeDeletedAfterAFailureSaysSo() {
    var shell =
        incus(DIR_AND_ZFS, list(template("v1", INPUTS)), snapshots("v1"))
            .onFail("sail-workspace acme fix-login", "no bus")
            .onFail("incus delete acme-fix-login --force", "busy");

    var failure =
        assertThrows(
            IOException.class,
            () ->
                workspaces(shell, ticks(0, 1, 2))
                    .create(acme(), "fix-login", source(), tempDir, "eng"));

    assertEquals(
        "Failed to delete container 'acme-fix-login': busy",
        failure.getSuppressed()[0].getMessage());
  }

  @Test
  void aWorkspaceWhoseBusNeverComesUpIsNotStarted() {
    var shell =
        incus(DIR_AND_ZFS, list(workspace("acme", "fix-login", "Stopped", "v1")), "[]")
            .onFail("bash -c for _ in", "the dev user's session bus did not come up within 30 s");

    var failure =
        assertThrows(
            IOException.class, () -> workspaces(shell, ticks(0, 1, 2)).start("acme", "fix-login"));

    assertEquals(
        "Workspace 'acme-fix-login' did not come up: the dev user's session bus did not come up"
            + " within 30 s",
        failure.getMessage());
  }

  @Test
  void aWorkspaceInAStateIncusCannotRunIsListedAndRefusedAShell() throws Exception {
    var shell =
        incus(
            DIR_AND_ZFS,
            list(
                instance(
                    "acme-odd",
                    "Error",
                    "tank",
                    "\"user.sail.project\": \"acme\", \"user.sail.workspace\": \"odd\","
                        + " \"user.sail.created_ms\": \"soon\", \"user.sail.ready\": \"1\""),
                instance("acme-half", "Running", "tank", "\"user.sail.project\": \"acme\"")),
            "[]");
    var workspaces = workspaces(shell, ticks());

    assertEquals(
        List.of(
            new Workspaces.Row("main", "acme", "not_created", null, null, null, null, null),
            new Workspaces.Row("odd", "acme-odd", "error", null, null, null, null, null)),
        workspaces.list("acme").workspaces());
    assertEquals(
        "Workspace 'odd' of acme is not running: Incus lists it as"
            + " Error[message=Unexpected container status: Error].",
        assertThrows(IllegalStateException.class, () -> workspaces.running("acme", "odd"))
            .getMessage());
  }

  @Test
  void anInstanceOfTheNameThatIsAnotherWorkspaceIsACollision() {
    var shell =
        incus(
            DIR_AND_ZFS,
            list(
                instance(
                    "acme-fix",
                    "Running",
                    "tank",
                    "\"user.sail.project\": \"acme\", \"user.sail.workspace\": \"other\"")),
            "[]");

    var failure =
        assertThrows(
            IllegalArgumentException.class,
            () -> workspaces(shell, ticks()).create(acme(), "fix", source(), tempDir, "eng"));

    assertEquals(
        "Workspace 'fix' of acme would be the instance 'acme-fix', which Incus already has. Give"
            + " the workspace another name.",
        failure.getMessage());
  }

  @Test
  void noVerbWritesToCopiesFromSnapshotsOrRestartsTheProjectsOwnContainer() throws Exception {
    var running = instance("acme", "Running", "devpool", "");
    var shells = new ArrayList<ScriptedShellExecutor>();
    var fresh = incus(DIR_AND_ZFS, list(running), "[]");
    var stale = incus(DIR_AND_ZFS, list(running, template("v1", "older")), snapshots("v1"));
    var stopped = incus(DIR_AND_ZFS, list(running, workspace("acme", "w", "Stopped", "v1")), "[]");
    var live = incus(DIR_AND_ZFS, list(running, workspace("acme", "w", "Running", "v1")), "[]");
    shells.addAll(List.of(fresh, stale, stopped, live));

    workspaces(fresh, ticks(0, 1, 2, 3, 4, 5, 6, 7))
        .create(acme(), "fix-login", source(), tempDir, "eng");
    workspaces(fresh, ticks()).list("acme");
    workspaces(stale, ticks(0, 1, 2, 3, 4, 5, 6, 7))
        .create(acme(), "fix-login", source(), tempDir, "eng");
    workspaces(stopped, ticks(0, 1, 2)).start("acme", "w");
    workspaces(live, ticks()).running("acme", "w");
    workspaces(live, ticks()).stop("acme", "w", NO_RUN);
    workspaces(live, ticks()).delete("acme", "w", NO_RUN, instance -> {});

    var touching = new ArrayList<String>();
    for (var shell : shells) {
      for (var argv : shell.arguments()) {
        var target = argv.indexOf("--") < 0 ? argv : argv.subList(0, argv.indexOf("--"));
        if (target.stream().anyMatch(word -> word.equals("acme") || word.startsWith("acme/"))) {
          touching.add(String.join(" ", argv));
        }
      }
    }
    assertEquals(List.of(), touching);
    assertTrue(fresh.invocations().size() > 10, "the verbs ran: " + fresh.invocations());
  }

  @Test
  void theScriptThatMakesACloneItsOwnIsPinnedWhole() {
    assertEquals(
        """
        set -eu
        project=$1 workspace=$2
        shift 2
        step() { printf 'sail-step %s %s\\n' "$1" "$(date +%s%3N)"; }
        step start
        rm -f /etc/machine-id
        systemd-machine-id-setup >/dev/null 2>&1
        if [ -e /var/lib/dbus/machine-id ]; then ln -sf /etc/machine-id /var/lib/dbus/machine-id; fi
        printf '%s\\n' "$project" > /etc/hostname
        hostname "$project"
        sed -i "s/^\\(127\\.0\\.1\\.1[[:space:]]*\\).*/\\1$project/" /etc/hosts
        rm -f /etc/ssh/ssh_host_*
        ssh-keygen -A >/dev/null
        step identity
        as_dev() { runuser -u dev -- env HOME=/home/dev "$@"; }
        retries=0
        fetch() {
          local until=$((SECONDS + 10))
          while ! failed=$(as_dev env LC_ALL=C git -C "$1" fetch --quiet origin 2>&1); do
            case $failed in
              *"Could not resolve host"*) ;;
              *) printf '%s\\n' "$failed" >&2; return 1 ;;
            esac
            if [ "$SECONDS" -ge "$until" ]; then
              printf '%s\\n' "$failed" >&2
              return 1
            fi
            retries=$((retries + 1))
            sleep 0.1
          done
        }
        if [ "$#" -gt 0 ]; then
          for _ in $(seq 1 300); do
            ip -4 -o addr show dev eth0 | grep -q inet && break
            sleep 0.1
          done
        fi
        step network
        while [ "$#" -ge 2 ]; do
          repo=/home/dev/workspace/$1 base=$2
          shift 2
          fetch "$repo"
          if as_dev git -C "$repo" show-ref --verify --quiet "refs/remotes/origin/$workspace"; then
            as_dev git -C "$repo" switch --quiet -C "$workspace" "origin/$workspace"
          else
            if [ -z "$base" ]; then
              base=$(as_dev git -C "$repo" symbolic-ref --short refs/remotes/origin/HEAD)
              base=${base#origin/}
            fi
            as_dev git -C "$repo" switch --quiet --no-track -c "$workspace" "origin/$base"
          fi
        done
        step repos
        printf 'sail-count fetch_retries %s\\n' "$retries"
        for _ in $(seq 1 300); do systemctl show --property=Version >/dev/null 2>&1 && break; sleep 0.1; done
        systemctl try-restart ssh.service
        step sshd
        for _ in $(seq 1 300); do [ -S /run/user/1000/bus ] && break; sleep 0.1; done
        if [ ! -S /run/user/1000/bus ]; then
          echo "the dev user's session bus did not come up within 30 s" >&2
          exit 1
        fi
        step bus
        """,
        Workspaces.PREPARE);
  }

  /**
   * The fetch of {@link Workspaces#FETCH} run by bash, the dev user's {@code git} a stand-in that
   * answers each attempt with the next of {@code answers}, a status and what it says, and each
   * pause moving bash's {@code SECONDS} on by five, so the wall clock's own second ticking during
   * the run cannot change how many pauses fit before the deadline; answered with the attempts made,
   * the retries counted and what the fetch said.
   */
  private static ShellExec.Result fetched(Path dir, String... answers) throws Exception {
    var script =
        Workspaces.FETCH
            + """
            sleep() { SECONDS=$((SECONDS + 5)); }
            as_dev() {
              n=$(($(cat "$TRIES") + 1)); echo "$n" > "$TRIES"; echo "$*" > "$TRIES.args"
              eval "answer=\\${ANSWER_$n:-0}"
              status=${answer%%|*}; said=${answer#*|}
              [ "$said" = "$answer" ] || echo "$said" >&2
              return "$status"
            }
            fetch /home/dev/workspace/app
            status=$?
            printf 'retries=%s' "$retries"
            exit "$status"
            """;
    var tries = dir.resolve("tries");
    Files.writeString(tries, "0");
    var process = new ProcessBuilder("bash", "-c", script);
    process.environment().put("TRIES", tries.toString());
    for (var i = 0; i < answers.length; i++) {
      process.environment().put("ANSWER_" + (i + 1), answers[i]);
    }
    var running = process.start();
    var out = new String(running.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    var err = new String(running.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
    assertEquals(
        "env LC_ALL=C git -C /home/dev/workspace/app fetch --quiet origin",
        Files.readString(dir.resolve("tries.args")).strip(),
        "git is run in the C locale");
    return new ShellExec.Result(
        running.waitFor(), "attempts=" + Files.readString(tries).strip() + " " + out, err);
  }

  @Test
  @EnabledOnOs({OS.LINUX, OS.MAC})
  void aFetchWaitsOutAHostThatDoesNotResolveForTenSecondsAndSaysAnyOtherFailureAtOnce()
      throws Exception {
    var unresolved =
        "128|fatal: unable to access 'https://github.com/acme/app.git/': Could not resolve host:"
            + " github.com";

    var late = fetched(tempDir, unresolved, unresolved, "0");
    var refused =
        fetched(tempDir, unresolved, "128|fatal: Authentication failed for 'https://github.com/'");
    var never = fetched(tempDir, Collections.nCopies(50, unresolved).toArray(String[]::new));

    assertEquals(new ShellExec.Result(0, "attempts=3 retries=2", ""), late);
    assertEquals(
        new ShellExec.Result(
            1, "attempts=2 retries=1", "fatal: Authentication failed for 'https://github.com/'\n"),
        refused);
    assertEquals(
        new ShellExec.Result(1, "attempts=3 retries=2", unresolved.substring(4) + "\n"),
        never,
        "ten seconds of asking, whatever the count");
  }

  private static String incomplete(String project, String name) {
    return instance(
        project + "-" + name,
        "Running",
        "tank",
        ("\"user.sail.project\": \"%s\", \"user.sail.workspace\": \"%s\", \"user.sail.template\":"
                + " \"v1\", \"user.sail.creating\": \"1\"")
            .formatted(project, name));
  }

  @Test
  void aWorkspaceIsMarkedWholeLastAfterItsFilesAndMachinery() throws Exception {
    var files = tempDir.resolve("files");
    Files.createDirectories(files);
    Files.writeString(files.resolve("notes.md"), "shared");
    var shell = incus(DIR_AND_ZFS, list(template("v1", INPUTS)), snapshots("v1"));

    workspaces(shell, ticks(0, 1, 2, 3, 4, 5, 6, 7))
        .create(acme(), "fix-login", source(), files, "eng");

    var calls = shell.invocations();
    var prepare = indexOf(shell, prepareArgv());
    var pushed =
        indexOfFirst(
            calls,
            c ->
                c.startsWith("incus file push")
                    && c.endsWith(" acme-fix-login/home/dev/workspace/notes.md"));
    var machinery =
        indexOfFirst(
            calls, c -> c.equals("incus config device get acme-fix-login sail-api-sock source"));
    var ready =
        indexOfFirst(
            calls,
            c ->
                c.equals(
                    "incus config set acme-fix-login user.sail.ready="
                        + NOW.toEpochMilli()
                        + " user.sail.creating= user.sail.template.of= user.sail.template.current="
                        + " user.sail.template.inputs= user.sail.template.last= user.sail.inputs="));
    assertTrue(
        0 <= prepare
            && prepare < pushed
            && pushed < machinery
            && machinery < ready
            && ready == calls.size() - 1,
        String.join("\n", calls));
  }

  private static int indexOfFirst(List<String> calls, Predicate<String> match) {
    for (var i = 0; i < calls.size(); i++) {
      if (match.test(calls.get(i))) {
        return i;
      }
    }
    return -1;
  }

  @Test
  void aWorkspaceThatCannotBeMarkedWholeIsDeleted() {
    var shell =
        incus(DIR_AND_ZFS, list(template("v1", INPUTS)), snapshots("v1"))
            .onFail("incus config set acme-fix-login user.sail.ready=", "disk full");

    var failure =
        assertThrows(
            IOException.class,
            () ->
                workspaces(shell, ticks(0, 1, 2, 3, 4, 5))
                    .create(acme(), "fix-login", source(), tempDir, "eng"));

    assertEquals(
        "Workspace 'fix-login' could not be marked whole: disk full", failure.getMessage());
    assertEquals(
        List.of("incus", "delete", "acme-fix-login", "--force"), shell.arguments().getLast());
  }

  @Test
  void anIncompleteWorkspaceIsListedAsSuchNeverStartedOrEnteredAndReplacedByTheNextCreate()
      throws Exception {
    var half = incomplete("acme", "fix-login");
    var shell = incus(DIR_AND_ZFS, list(half, template("v1", INPUTS)), snapshots("v1"));
    var workspaces = workspaces(shell, ticks(0, 1, 2, 3, 4, 5, 6, 7));

    assertEquals(
        "incomplete",
        workspaces.list("acme").workspaces().stream()
            .filter(row -> row.name().equals("fix-login"))
            .findFirst()
            .orElseThrow()
            .state());
    var refusal =
        "Workspace 'fix-login' of acme was never made whole: a create of it did not finish. Create"
            + " it again, which replaces it: sail project workspace create acme fix-login.";
    assertEquals(
        refusal,
        assertThrows(IllegalStateException.class, () -> workspaces.start("acme", "fix-login"))
            .getMessage());
    assertEquals(
        refusal,
        assertThrows(IllegalStateException.class, () -> workspaces.running("acme", "fix-login"))
            .getMessage());
    assertFalse(
        shell.invocations().stream()
            .anyMatch(c -> c.startsWith("incus start") || c.startsWith("incus exec")));

    workspaces.create(acme(), "fix-login", source(), tempDir, "eng");

    var deleted = indexOf(shell, List.of("incus", "delete", "acme-fix-login", "--force"));
    assertTrue(
        0 <= deleted && deleted < indexOf(shell, copyArgv("v1")),
        String.join("\n", shell.invocations()));
  }

  @Test
  void aWholeWorkspaceWhoseMarkWasTakenOffByHandIsNeitherReplacedNorUsedButNamesItsDelete() {
    var unmarked =
        instance(
            "acme-fix-login",
            "Running",
            "tank",
            "\"user.sail.project\": \"acme\", \"user.sail.workspace\": \"fix-login\","
                + " \"user.sail.template\": \"v1\"");
    var shell = incus(DIR_AND_ZFS, list(unmarked, template("v1", INPUTS)), snapshots("v1"));
    var workspaces = workspaces(shell, ticks());
    var refusal =
        "Workspace 'fix-login' of acme has no ready mark, yet a create of it finished: the mark was"
            + " taken off by hand, so the workspace is left as it is. Delete it if it is not wanted:"
            + " sail project workspace delete acme fix-login.";

    for (Executable verb :
        List.<Executable>of(
            () -> workspaces.create(acme(), "fix-login", source(), tempDir, "eng"),
            () -> workspaces.start("acme", "fix-login"),
            () -> workspaces.running("acme", "fix-login"))) {
      assertEquals(refusal, assertThrows(IllegalStateException.class, verb).getMessage());
    }
    assertTrue(
        shell.invocations().stream()
            .allMatch(
                c ->
                    c.startsWith("incus list")
                        || c.startsWith("incus storage list")
                        || c.startsWith("incus query")),
        String.join("\n", shell.invocations()));
  }

  @Test
  void aWorkspaceIsTheOneInstanceNamedForItAndCopiesCarryingItsKeysAreRefusedByName()
      throws Exception {
    var copy =
        instance(
            "acme-backup",
            "Stopped",
            "tank",
            "\"user.sail.project\": \"acme\", \"user.sail.workspace\": \"fix\", \"user.sail.ready\": \"1\"");
    var beta =
        instance(
            "beta",
            "Running",
            "tank",
            "\"user.sail.project\": \"acme\", \"user.sail.workspace\": \"x\"");
    var shell =
        incus(
            DIR_AND_ZFS,
            list(
                copy,
                workspace("acme", "fix", "Running", "v1"),
                beta,
                workspace("bravo", "w", "Running", "v1")),
            "[]");
    var workspaces = workspaces(shell, ticks());

    assertEquals(
        "Instances acme-backup, acme-fix carry the keys of workspace 'fix' of acme, which only"
            + " acme-fix may: nothing is done to any of them. Delete the copies, or their keys,"
            + " first.",
        assertThrows(
                IllegalStateException.class,
                () -> workspaces.delete("acme", "fix", NO_RUN, instance -> {}))
            .getMessage());
    assertEquals(
        "Instance beta carries the keys of workspace 'x' of acme, which only acme-x may: nothing is"
            + " done to any of them. Delete the copies, or their keys, first.",
        assertThrows(
                IllegalStateException.class,
                () -> workspaces.delete("acme", "x", NO_RUN, instance -> {}))
            .getMessage());
    assertEquals(
        "Project 'acme' has no workspace 'w' on this box. List them with: sail project workspace"
            + " list acme.",
        assertThrows(
                IllegalStateException.class,
                () -> workspaces.delete("acme", "w", NO_RUN, instance -> {}))
            .getMessage());
    assertEquals(
        List.of("main", "fix"),
        workspaces.list("acme").workspaces().stream().map(Workspaces.Row::name).toList());
    assertFalse(shell.invocations().stream().anyMatch(c -> c.startsWith("incus delete")));
  }

  @Test
  void aCreateIsRefusedWhileACopyCarriesTheKeysOfTheWorkspace() {
    var copy =
        instance(
            "acme-backup",
            "Stopped",
            "tank",
            "\"user.sail.project\": \"acme\", \"user.sail.workspace\": \"fix\"");
    var shell = incus(DIR_AND_ZFS, list(copy, template("v1", INPUTS)), snapshots("v1"));

    var failure =
        assertThrows(
            IllegalStateException.class,
            () -> workspaces(shell, ticks()).create(acme(), "fix", source(), tempDir, "eng"));

    assertEquals(
        "Instance acme-backup carries the keys of workspace 'fix' of acme under another name."
            + " Delete it or its keys before creating the workspace.",
        failure.getMessage());
  }

  @Test
  void theTemplatesLockIsHeldWhileItBuilds() throws Exception {
    var shell = incus(DIR_AND_ZFS, "[]", "[]");
    var held = new ArrayList<Boolean>();
    var source =
        new WorkspaceTemplate.Source(
            "devpool",
            INPUTS,
            name -> false,
            (into, fresh) -> held.add(Files.exists(locks().resolve("acme--tmpl.machinery.lock"))));

    templates(shell).ensure(acme(), source);

    assertEquals(List.of(true), held);
  }

  @Test
  void aVersionNumberIsNeverGivenTwiceEvenWhenEverySnapshotIsGone() throws Exception {
    var gone =
        instance(
            "acme--tmpl",
            "Stopped",
            "tank",
            "\"user.sail.template.of\": \"acme\", \"user.sail.template.current\": \"v1\","
                + " \"user.sail.template.inputs\": \"older\", \"user.sail.template.last\": \"v3\"");
    var shell = incus(DIR_AND_ZFS, list(gone), "[]");

    assertEquals("v4", templates(shell).ensure(acme(), source()).version());
  }

  @Test
  void aWorkspaceNameEndingInADashIsRefusedBeforeAnythingIsBuilt() {
    var shell = incus(DIR_AND_ZFS, "[]", "[]");

    var failure =
        assertThrows(
            IllegalArgumentException.class,
            () -> workspaces(shell, ticks()).create(acme(), "fix-", source(), tempDir, "eng"));

    assertEquals(
        "Workspace 'fix-' ends in '-', and Incus names no instance so. Give the workspace another"
            + " name.",
        failure.getMessage());
    assertEquals(List.of(), shell.invocations());
    assertEquals(List.of(), builtInto);
  }

  @Test
  void anExistingTemplateStaysOnItsPoolWhateverPoolsWereAddedSince() throws Exception {
    var pools =
        """
        [{"name": "devpool", "driver": "dir", "config": {}}, {"name": "fast", "driver": "zfs", "config": {}},
         {"name": "tank", "driver": "zfs", "config": {}}]
        """;
    var shell = incus(pools, list(template("v1", INPUTS)), snapshots("v1"));

    var created =
        workspaces(shell, ticks(0, 1, 2, 3, 4, 5, 6, 7))
            .create(acme(), "fix-login", source(), tempDir, "eng");

    assertEquals("tank", created.template().pool());
    assertTrue(indexOf(shell, copyArgv("v1")) >= 0);
  }

  @Test
  void anInstanceNamedLikeTheTemplateThatIsNoneIsNeverBuiltInto() {
    var stranger = instance("acme--tmpl", "Running", "tank", "");
    var shell = incus(DIR_AND_ZFS, list(stranger), "[]");

    var failure =
        assertThrows(IllegalStateException.class, () -> templates(shell).ensure(acme(), source()));

    assertEquals(
        "Instance 'acme--tmpl' carries no user.sail.template.of of project acme, so it is not"
            + " acme's template and nothing is built into it.",
        failure.getMessage());
    assertEquals(List.of(), builtInto);
    assertFalse(shell.invocations().stream().anyMatch(c -> c.contains("snapshot")));
  }

  @Test
  @EnabledOnOs(OS.LINUX)
  void theScriptThatMakesACloneItsOwnIsValidBash() throws Exception {
    var script = tempDir.resolve("prepare.sh");
    Files.writeString(script, Workspaces.PREPARE, StandardCharsets.UTF_8);

    var checked = new ProcessBuilder("bash", "-n", script.toString()).start();

    assertSame(0, checked.waitFor());
  }

  /**
   * Two creates on two threads over one stateful Incus: the second starts once the first is
   * building, under every lock the first takes, and the first holds its build until the second is
   * parked on a lock, having read all it reads before one.
   */
  private List<Object> concurrently(Box box, String firstName, String secondName) throws Exception {
    var firstBuilding = new CountDownLatch(1);
    var secondThread = new AtomicReference<Thread>();
    var builds = new AtomicInteger();
    var source =
        new WorkspaceTemplate.Source(
            "devpool",
            INPUTS,
            Set.of("acme")::contains,
            (into, isFresh) -> {
              builds.incrementAndGet();
              if (isFresh) {
                box.launch("acme--tmpl", Map.of("user.sail.template.of", "acme"));
              }
              if (Thread.currentThread().getName().equals("first")) {
                firstBuilding.countDown();
                awaitParked(secondThread);
              }
            });
    var clock = ticks(0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15);
    var first = new FutureTask<Object>(() -> outcome(box, clock, firstName, source));
    var second = new FutureTask<Object>(() -> outcome(box, clock, secondName, source));
    Thread.ofPlatform().name("first").start(first);
    assertTrue(firstBuilding.await(30, TimeUnit.SECONDS), "the first create never built");
    secondThread.set(Thread.ofPlatform().name("second").start(second));
    var outcomes = List.of(first.get(60, TimeUnit.SECONDS), second.get(60, TimeUnit.SECONDS));
    assertEquals(1, builds.get(), "one definition is built once");
    return outcomes;
  }

  /** Returns once {@code thread} is started and parked, as on a lock another thread holds. */
  private static void awaitParked(AtomicReference<Thread> thread) {
    var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
    while (thread.get() == null || thread.get().getState() != Thread.State.WAITING) {
      assertTrue(System.nanoTime() < deadline, "the second create never waited on a lock");
      Thread.onSpinWait();
    }
  }

  private Object outcome(
      Box box, LongSupplier clock, String name, WorkspaceTemplate.Source source) {
    try {
      return new Workspaces(box, box, clock, locks()).create(acme(), name, source, tempDir, "eng");
    } catch (Exception refused) {
      return refused.getMessage();
    }
  }

  @Test
  void twoCreatesOfOneStaleTemplateBuildItOnceAndBothCloneThatVersion() throws Exception {
    var box = new Box();
    box.put("acme--tmpl", "Stopped", template("v1", "an-older-definition"), "v1");

    var outcomes = concurrently(box, "one", "two");

    assertEquals(
        List.of("v2", "v2"),
        outcomes.stream().map(o -> ((Workspaces.Created) o).template().version()).toList());
    assertEquals(List.of("v1", "v2"), box.versions("acme--tmpl"));
    assertEquals(
        List.of(true, false),
        outcomes.stream().map(o -> ((Workspaces.Created) o).template().built()).toList());
  }

  @Test
  void twoCreatesOnABoxWithNoTemplateBuildItOnceIntoAFreshInstanceAndBothClone() throws Exception {
    var box = new Box();

    var outcomes = concurrently(box, "one", "two");

    assertEquals(
        List.of("v1", "v1"),
        outcomes.stream().map(o -> ((Workspaces.Created) o).template().version()).toList());
    assertEquals(List.of("v1"), box.versions("acme--tmpl"));
    assertEquals(List.of("acme--tmpl", "acme-one", "acme-two"), box.names());
  }

  @Test
  void twoCreatesOfOneNameMakeOneWorkspaceAndTheOtherIsRefusedLeavingItWhole() throws Exception {
    var box = new Box();
    box.put("acme--tmpl", "Stopped", template("v1", "an-older-definition"), "v1");

    var outcomes = concurrently(box, "fix-login", "fix-login");

    assertEquals("acme-fix-login", ((Workspaces.Created) outcomes.getFirst()).instance());
    assertEquals(
        "Workspace 'fix-login' of acme exists already, as the instance 'acme-fix-login'.",
        outcomes.getLast());
    assertEquals(List.of("acme--tmpl", "acme-fix-login"), box.names());
    assertTrue(box.ready("acme-fix-login"));
    assertEquals(1, box.copies());
  }

  /**
   * One box's Incus, kept as it answers: each instance's status, keys and snapshots, changed by the
   * commands that change them, and every other command answered as done.
   */
  private static final class Box implements ShellExec {
    private record Held(String status, Map<String, String> keys, List<String> snapshots) {}

    private final Map<String, Held> instances = new TreeMap<>();
    private int copies;

    synchronized void put(String name, String status, String listed, String... snapshots) {
      var keys = new HashMap<String, String>();
      var config = (Map<?, ?>) YamlUtil.parseList("[" + listed + "]").getFirst().get("config");
      config.forEach((key, value) -> keys.put(String.valueOf(key), String.valueOf(value)));
      instances.put(name, new Held(status, keys, new ArrayList<>(List.of(snapshots))));
    }

    synchronized void launch(String name, Map<String, String> keys) {
      instances.put(name, new Held("Running", new HashMap<>(keys), new ArrayList<>()));
    }

    synchronized List<String> names() {
      return List.copyOf(instances.keySet());
    }

    synchronized List<String> versions(String name) {
      return List.copyOf(instances.get(name).snapshots());
    }

    synchronized boolean ready(String name) {
      var ready = instances.get(name).keys().get("user.sail.ready");
      return ready != null && !ready.isEmpty();
    }

    synchronized int copies() {
      return copies;
    }

    @Override
    public synchronized Result exec(List<String> command) {
      var joined = String.join(" ", command);
      if (joined.equals("incus storage list --format json")) {
        return new Result(0, DIR_AND_ZFS, "");
      }
      if (joined.equals("incus list --format json")) {
        var listed = new ArrayList<String>();
        instances.forEach(
            (name, held) ->
                listed.add(
                    instance(
                        name,
                        held.status(),
                        "tank",
                        String.join(
                            ", ",
                            held.keys().entrySet().stream()
                                .map(e -> "\"" + e.getKey() + "\": \"" + e.getValue() + "\"")
                                .toList()))));
        return new Result(0, list(listed.toArray(String[]::new)), "");
      }
      if (command.size() == 6 && joined.startsWith("incus snapshot list ")) {
        return new Result(
            0, snapshots(instances.get(command.get(3)).snapshots().toArray(String[]::new)), "");
      }
      if (joined.startsWith("incus snapshot create ")) {
        instances.get(command.get(3)).snapshots().add(command.get(4));
        return new Result(0, "", "");
      }
      if (joined.startsWith("incus config set ")) {
        var keys = instances.get(command.get(3)).keys();
        for (var assignment : command.subList(4, command.size())) {
          var at = assignment.indexOf('=');
          keys.put(assignment.substring(0, at), assignment.substring(at + 1));
        }
        return new Result(0, "", "");
      }
      if (joined.startsWith("incus copy ")) {
        var name = command.get(3);
        if (instances.containsKey(name)) {
          return new Result(1, "", "Error: Instance \"" + name + "\" already exists");
        }
        copies++;
        var keys = new HashMap<>(instances.get(command.get(2).split("/")[0]).keys());
        for (var i = 4; i + 1 < command.size(); i += 2) {
          var assignment = command.get(i + 1);
          var at = assignment.indexOf('=');
          keys.put(assignment.substring(0, at), assignment.substring(at + 1));
        }
        instances.put(name, new Held("Stopped", keys, new ArrayList<>()));
        return new Result(0, "", "");
      }
      if (command.size() == 3
          && (joined.startsWith("incus start ") || joined.startsWith("incus stop "))) {
        var held = instances.get(command.get(2));
        instances.put(
            command.get(2),
            new Held(
                command.get(1).equals("start") ? "Running" : "Stopped",
                held.keys(),
                held.snapshots()));
        return new Result(0, "", "");
      }
      if (joined.startsWith("incus delete ")) {
        instances.remove(command.get(2));
        return new Result(0, "", "");
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
