/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.sail.Sail;
import ai.singlr.sail.commands.SailCommandLine;
import ai.singlr.sail.common.DateTimeUtils;
import ai.singlr.sail.config.ProjectDefinition;
import ai.singlr.sail.config.YamlUtil;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
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
        },
        List.of());
  }

  private WorkspaceTemplate.Source source() {
    return source("devpool", Set.of("acme"));
  }

  private static ScriptedShellExecutor incus(String pools, String instances, String snapshots) {
    return new ScriptedShellExecutor(new ShellExec.Result(0, "", ""))
        .onOk("incus storage list --format json", pools)
        .onOk("incus list --format json", instances)
        .onOk("incus list ^", instances)
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
    return new Workspaces(shell, shell, nanos, locks(), () -> 65536, url -> false);
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
        "user.sail.creating=1",
        "-c",
        "user.sail.owner=eng",
        "-c",
        "user.sail.owner_id=fde_eng");
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
            .create(
                acme(), "fix-login", source(), tempDir.resolve("no-files"), "eng", TestOwners::of);

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
            .create(acme(), "fix-login", source(), tempDir, "eng", TestOwners::of);

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
            .create(acme(), "fix-login", source(), tempDir, "eng", TestOwners::of);

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
        },
        List.of());
  }

  @Test
  void aFailedBuildLeavesThePreviousVersionCurrentMakesNoSnapshotAndStopsTheTemplateItStarted() {
    var shell = incus(DIR_AND_ZFS, list(template("v1", "an-older-definition")), snapshots("v1"));

    var failure = assertThrows(IOException.class, () -> templates(shell).ensure(acme(), failing()));

    assertEquals("apt-get failed", failure.getMessage());
    assertEquals(
        List.of(
            "incus start acme--tmpl",
            String.join(" ", strip("acme--tmpl")),
            "incus stop acme--tmpl"),
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
            () ->
                workspaces(shell, ticks())
                    .create(acme(), "fix-login", source(), tempDir, "eng", TestOwners::of));

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
            () -> workspaces.create(acme(), "fix-login", source(), tempDir, "eng", TestOwners::of),
            () -> templates(shell).ensure(acme(), source()),
            () -> workspaces.start(acme(), "fix-login", TestOwners::of),
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

    var listing = workspaces(shell, ticks()).list("acme", id -> true);

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

    var listing = workspaces(shell, ticks()).list("acme", id -> true);

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

    var listing = workspaces(shell, ticks()).list("acme", id -> true);

    assertEquals(
        List.of(new Workspaces.Row("main", "acme", "not_created", null, null, null, null, null)),
        listing.workspaces());
  }

  @Test
  void startResumesAStoppedWorkspaceAndSaysWhatItsResumeTook() throws Exception {
    var shell = incus(DIR_AND_ZFS, list(workspace("acme", "fix-login", "Stopped", "v1")), "[]");

    var changed =
        workspaces(shell, ticks(ms(1000), ms(1350), ms(1800)))
            .start(acme(), "fix-login", TestOwners::of);

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

  /**
   * The closing look's n9: a start whose give fails (here gh's login) stops the workspace again
   * before the failure leaves, saying so, so no run lands in a running workspace without its
   * owner's credentials; the next start strips and gives from the beginning, and it runs.
   */
  @Test
  void aStartWhoseGiveFailsStopsTheWorkspaceAgainAndTheNextStartGivesIt() throws Exception {
    var shell =
        incus(DIR_AND_ZFS, list(owned("acme-fix-login", "Stopped", "ana")), "[]")
            .onceOnFail("gh auth login", "error connecting to api.github.com");
    var ana =
        new WorkspaceOwner(
            "ana", "Ana", "ana@sail.invalid", Map.of("github.com", "ghp_a"), List.of(), "fde_ana");

    var failure =
        assertThrows(
            IOException.class,
            () -> workspaces(shell, ticks(0, 1, 2)).start(acme(), "fix-login", handle -> ana));
    var stoppedAfterFailure =
        shell.invocations().stream().filter(c -> c.startsWith("incus stop acme-fix-login")).count();
    var changed = workspaces(shell, ticks(0, 1, 2)).start(acme(), "fix-login", handle -> ana);

    assertEquals(
        "Workspace acme-fix-login could not be given its owner's credentials, so it is stopped"
            + " again; its next start strips it and gives them from the beginning: Failed to log gh"
            + " in for github.com in acme-fix-login: error connecting to api.github.com",
        failure.getMessage());
    assertEquals(1, stoppedAfterFailure, "stopped before the failure left");
    assertEquals(Workspaces.Change.STARTED, changed.change());
    assertEquals(
        1,
        shell.invocations().stream().filter(c -> c.startsWith("incus stop acme-fix-login")).count(),
        "the start that gave leaves it running");
    assertEquals(
        2,
        shell.invocations().stream().filter(c -> c.contains("gh auth login")).count(),
        "the next start gave again");
  }

  @Test
  void startingARunningWorkspaceChangesNothing() throws Exception {
    var shell = incus(DIR_AND_ZFS, list(workspace("acme", "fix-login", "Running", "v1")), "[]");

    var changed = workspaces(shell, ticks()).start(acme(), "fix-login", TestOwners::of);

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
                    .create(
                        acme(),
                        "fix-login",
                        source(),
                        tempDir.resolve("no-files"),
                        "eng",
                        TestOwners::of));

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
            () ->
                workspaces.create(
                    acme(), "second", source(), tempDir.resolve("no-files"), "eng", TestOwners::of),
            () -> templates(shell).ensure(acme(), source()),
            () -> workspaces.stop("acme", "fix-login", NO_RUN),
            () -> workspaces.start(acme(), "fix-login", TestOwners::of),
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
    var workspaces = new Workspaces(hanging, hanging, ticks(), locks(), () -> 65536, url -> false);
    var refusal =
        "Pool tank is a mirror whose health Sail cannot tell: zpool status did not answer within"
            + " 10 s, so no workspace or template is changed on it. See sudo zpool status tank.";

    for (Executable verb :
        List.<Executable>of(
            () ->
                workspaces.create(
                    acme(), "second", source(), tempDir.resolve("no-files"), "eng", TestOwners::of),
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
        assertThrows(
            IllegalArgumentException.class, () -> workspaces.start(acme(), "main", TestOwners::of));
    var shellIn =
        assertThrows(
            IllegalArgumentException.class,
            () -> workspaces.startExisting(acme(), "main", TestOwners::of, Workspaces.Entry.ANY));
    var create =
        assertThrows(
            IllegalArgumentException.class,
            () -> workspaces.create(acme(), "main", source(), tempDir, "eng", TestOwners::of));

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
            () ->
                workspaces(shell, ticks())
                    .create(config, "abcd", source(), tempDir, "eng", TestOwners::of));

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
            () ->
                workspaces(shell, ticks())
                    .create(config, "a", source(), tempDir, "eng", TestOwners::of));

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
            () -> workspaces.create(acme(), "taken", held, tempDir, "eng", TestOwners::of));
    var project =
        assertThrows(
            IllegalArgumentException.class,
            () -> workspaces.create(acme(), "billing", held, tempDir, "eng", TestOwners::of));
    var existing =
        assertThrows(
            IllegalStateException.class,
            () -> workspaces.create(acme(), "fix-login", held, tempDir, "eng", TestOwners::of));
    var invalid =
        assertThrows(
            IllegalArgumentException.class,
            () -> workspaces.create(acme(), "Fix_Login", held, tempDir, "eng", TestOwners::of));

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
            () ->
                workspaces(shell, ticks())
                    .create(config, "tmpl", source(), tempDir, "eng", TestOwners::of));

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
            () ->
                workspaces(shell, ticks())
                    .create(acme(), "fix-login", source(), tempDir, "eng", TestOwners::of));

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
                    .create(acme(), "fix-login", source(), tempDir, "eng", TestOwners::of));

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
                workspaces(shell, ticks(0))
                    .create(acme(), "fix-login", source(), tempDir, "eng", TestOwners::of));

    assertEquals(
        "Failed to clone acme--tmpl/v1 as 'acme-fix-login': Error: Instance name already in use",
        failure.getMessage());
    assertFalse(shell.invocations().stream().anyMatch(c -> c.startsWith("incus delete")));
  }

  /** Ruling 6: a shell opens in a running workspace as it is, and starts a stopped one first. */
  @Test
  void aShellEntersARunningWorkspaceAsItIsAndStartsAStoppedOne() throws Exception {
    var shell =
        incus(
            DIR_AND_ZFS,
            list(
                workspace("acme", "fix-login", "Running", "v1"),
                workspace("acme", "second", "Stopped", "v1")),
            "[]");
    var workspaces = workspaces(shell, ticks(0, 1, 2, 3));

    assertEquals(
        "acme-fix-login",
        workspaces.startExisting(acme(), "fix-login", TestOwners::of, Workspaces.Entry.ANY));
    assertEquals(
        "acme-second",
        workspaces.startExisting(acme(), "second", TestOwners::of, Workspaces.Entry.ANY));
    assertEquals(
        List.of("incus start acme-second"),
        shell.invocations().stream().filter(c -> c.startsWith("incus start")).toList());
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
                    .create(acme(), "fix-login", source(), tempDir, "eng", TestOwners::of));

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
            IOException.class,
            () -> workspaces(shell, ticks(0, 1, 2)).start(acme(), "fix-login", TestOwners::of));

    assertEquals(
        "Workspace 'acme-fix-login' did not come up: the dev user's session bus did not come up"
            + " within 30 s",
        failure.getMessage());
  }

  @Test
  void aWorkspaceInAStateIncusCannotRunIsListedAsItIs() throws Exception {
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
        workspaces.list("acme", id -> true).workspaces());
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
            () ->
                workspaces(shell, ticks())
                    .create(acme(), "fix", source(), tempDir, "eng", TestOwners::of));

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
        .create(acme(), "fix-login", source(), tempDir, "eng", TestOwners::of);
    workspaces(fresh, ticks()).list("acme", id -> true);
    workspaces(stale, ticks(0, 1, 2, 3, 4, 5, 6, 7))
        .create(acme(), "fix-login", source(), tempDir, "eng", TestOwners::of);
    workspaces(stopped, ticks(0, 1, 2)).start(acme(), "w", TestOwners::of);
    workspaces(live, ticks()).startExisting(acme(), "w", TestOwners::of, Workspaces.Entry.ANY);
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
        project=$1 workspace=$2 branch=$3
        shift 3
        step() { printf 'sail-step %s %s\\n' "$1" "$(date +%s%3N)"; }
        step start
        tr -d - < /proc/sys/kernel/random/uuid > /etc/machine-id.sail
        mv -f /etc/machine-id.sail /etc/machine-id
        if [ -e /var/lib/dbus/machine-id ]; then ln -sf /etc/machine-id /var/lib/dbus/machine-id; fi
        printf '%s\\n' "$project" > /etc/hostname
        hostname "$project"
        sed -i "s/^\\(127\\.0\\.1\\.1[[:space:]]*\\).*/\\1$project/" /etc/hosts
        keys=$(mktemp -d /etc/ssh/sail-keys.XXXXXX)
        mkdir -p "$keys/etc/ssh"
        ssh-keygen -A -f "$keys" >/dev/null
        for key in /etc/ssh/ssh_host_*; do [ -e "$keys/etc/ssh/${key##*/}" ] || rm -f "$key"; done
        mv -f "$keys"/etc/ssh/ssh_host_* /etc/ssh/
        rm -rf "$keys"
        step identity
        as_dev() { runuser -u dev -- env HOME=/home/dev "$@"; }
        retries=0
        fetch() {
          local url host until=$((SECONDS + 10))
          url=$(as_dev git -C "$1" remote get-url origin)
          host=
          case $url in *://*) host=${url#*://}; host=${host#*@}; host=${host%%[:/]*} ;; esac
          if [ -n "$host" ]; then
            until getent hosts "$host" >/dev/null; do
              if [ "$SECONDS" -ge "$until" ]; then
                echo "$host did not resolve within 10 s, so its repo could not be fetched" >&2
                return 1
              fi
              retries=$((retries + 1))
              sleep 0.1
            done
          fi
          as_dev git -C "$1" fetch --quiet origin
        }
        if [ "$#" -gt 0 ]; then
          for _ in $(seq 1 300); do
            ip -4 -o addr show dev eth0 | grep -q inet && break
            sleep 0.1
          done
          if ! ip -4 -o addr show dev eth0 | grep -q inet; then
            seen=$(networkctl list eth0 --no-legend 2>/dev/null | awk '{print $4 ", " $5}')
            echo "$project-$workspace got no address on eth0 within 30 s${seen:+ (networkd says $seen)}, so its repos cannot be fetched; check that the network Incus gives it (incus config device show $project-$workspace) serves addresses (incus network show <network>)" >&2
            exit 1
          fi
        fi
        step network
        while [ "$#" -ge 2 ]; do
          repo=/home/dev/workspace/$1 base=$2
          shift 2
          fetch "$repo"
          if [ -z "$base" ]; then
            base=$(as_dev git -C "$repo" symbolic-ref --short refs/remotes/origin/HEAD)
            base=${base#origin/}
          fi
          if [ -z "$branch" ]; then
            as_dev git -C "$repo" switch --quiet --detach "origin/$base"
          elif as_dev git -C "$repo" show-ref --verify --quiet "refs/remotes/origin/$branch"; then
            as_dev git -C "$repo" switch --quiet -C "$branch" "origin/$branch"
          else
            as_dev git -C "$repo" switch --quiet --no-track -c "$branch" "origin/$base"
          fi
        done
        step repos
        printf 'sail-count fetch_retries %s\\n' "$retries"
        for _ in $(seq 1 300); do systemctl show --property=Version >/dev/null 2>&1 && break; sleep 0.1; done
        for _ in $(seq 1 300); do
          state=$(systemctl show --property=ActiveState --value ssh.service)
          case $state in activating|deactivating|reloading) sleep 0.1 ;; *) break ;; esac
        done
        case $state in activating|deactivating|reloading)
          echo "ssh.service was still $state after 30 s of waiting for it to finish starting" >&2
          exit 1 ;;
        esac
        if ! systemctl try-restart ssh.service; then
          printf 'ssh.service: %s; %s\\n' "$(systemctl show --property=Result --value ssh.service)" \\
            "$(journalctl --unit ssh.service --lines 3 --output cat --no-pager 2>&1 | paste -s -d ';' -)" >&2
          exit 1
        fi
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
   * The fetch of {@link Workspaces#FETCH} run by bash: the dev user's {@code git} a stand-in that
   * names {@code url} as the remote and answers the fetch with {@code fetch}, a status and what it
   * says; {@code getent} one that answers each ask with the next of {@code resolves}; each pause
   * moving bash's {@code SECONDS} on by five, so the wall clock cannot change how many fit before
   * the deadline. Answered with the asks made, the retries counted and what the fetch said.
   */
  private static ShellExec.Result fetched(Path dir, String url, String fetch, String... resolves)
      throws Exception {
    var script =
        Workspaces.FETCH
            + """
            sleep() { SECONDS=$((SECONDS + 5)); }
            getent() {
              n=$(($(cat "$TRIES") + 1)); echo "$n" > "$TRIES"
              eval "status=\\${RESOLVE_$n:-0}"
              return "$status"
            }
            as_dev() {
              case "$*" in
                *"remote get-url origin") echo "$URL" ;;
                *"fetch --quiet origin")
                  status=${FETCH%%|*}; said=${FETCH#*|}
                  [ "$said" = "$FETCH" ] || echo "$said" >&2
                  return "$status" ;;
              esac
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
    process.environment().put("URL", url);
    process.environment().put("FETCH", fetch);
    for (var i = 0; i < resolves.length; i++) {
      process.environment().put("RESOLVE_" + (i + 1), resolves[i]);
    }
    var running = process.start();
    var out = new String(running.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    var err = new String(running.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
    return new ShellExec.Result(
        running.waitFor(), "asks=" + Files.readString(tries).strip() + " " + out, err);
  }

  /**
   * A fetch waits out a host that does not resolve yet, asking the resolver, never reading git's
   * words; says a host that never resolves within ten seconds; says any failed fetch in git's own
   * words; and fetches a remote with no host at once.
   */
  @Test
  @EnabledOnOs({OS.LINUX, OS.MAC})
  void aFetchWaitsForItsHostToResolveAndSaysAnyFailedFetchInGitsOwnWords() throws Exception {
    var github = "https://github.com/acme/app.git";

    var late = fetched(tempDir, github, "0", "2", "2", "0");
    var refused =
        fetched(tempDir, github, "128|fatal: Authentication failed for 'https://github.com/'");
    var never = fetched(tempDir, github, "0", "2", "2", "2", "2", "2", "2");
    var local = fetched(tempDir, "file:///srv/app.git", "0", "2");

    assertEquals(new ShellExec.Result(0, "asks=3 retries=2", ""), late);
    assertEquals(
        new ShellExec.Result(
            128, "asks=1 retries=0", "fatal: Authentication failed for 'https://github.com/'\n"),
        refused);
    assertEquals(
        new ShellExec.Result(
            1,
            "asks=3 retries=2",
            "github.com did not resolve within 10 s, so its repo could not be fetched\n"),
        never,
        "ten seconds of asking, whatever the count");
    assertEquals(new ShellExec.Result(0, "asks=0 retries=0", ""), local);
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
        .create(acme(), "fix-login", source(), files, "eng", TestOwners::of);

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
                    .create(acme(), "fix-login", source(), tempDir, "eng", TestOwners::of));

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
        workspaces.list("acme", id -> true).workspaces().stream()
            .filter(row -> row.name().equals("fix-login"))
            .findFirst()
            .orElseThrow()
            .state());
    var refusal =
        "Workspace 'fix-login' of acme was never made whole: a create of it did not finish. Create"
            + " it again, which replaces it: sail project workspace create acme fix-login.";
    assertEquals(
        refusal,
        assertThrows(
                IllegalStateException.class,
                () -> workspaces.start(acme(), "fix-login", TestOwners::of))
            .getMessage());
    assertEquals(
        refusal,
        assertThrows(
                IllegalStateException.class,
                () ->
                    workspaces.startExisting(
                        acme(), "fix-login", TestOwners::of, Workspaces.Entry.ANY))
            .getMessage());
    assertFalse(
        shell.invocations().stream()
            .anyMatch(c -> c.startsWith("incus start") || c.startsWith("incus exec")));

    workspaces.create(acme(), "fix-login", source(), tempDir, "eng", TestOwners::of);

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
            () -> workspaces.create(acme(), "fix-login", source(), tempDir, "eng", TestOwners::of),
            () -> workspaces.start(acme(), "fix-login", TestOwners::of),
            () ->
                workspaces.startExisting(
                    acme(), "fix-login", TestOwners::of, Workspaces.Entry.ANY))) {
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
        workspaces.list("acme", id -> true).workspaces().stream()
            .map(Workspaces.Row::name)
            .toList());
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
            () ->
                workspaces(shell, ticks())
                    .create(acme(), "fix", source(), tempDir, "eng", TestOwners::of));

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
            (into, fresh) -> held.add(Files.exists(locks().resolve("acme--tmpl.machinery.lock"))),
            List.of());

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
            () ->
                workspaces(shell, ticks())
                    .create(acme(), "fix-", source(), tempDir, "eng", TestOwners::of));

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
            .create(acme(), "fix-login", source(), tempDir, "eng", TestOwners::of);

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
            },
            List.of());
    var ticks = new AtomicLong();
    LongSupplier clock = ticks::getAndIncrement;
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
      return new Workspaces(box, box, clock, locks(), () -> 65536, url -> false)
          .create(acme(), name, source, tempDir, "eng", TestOwners::of);
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

    synchronized String key(String name, String key) {
      return instances.get(name).keys().get(key);
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

  private static final RunPlace.SpecWorkspace FIX =
      (RunPlace.SpecWorkspace) RunPlace.of("acme", "fix-login");

  /**
   * Spec {@code spec}'s workspace {@code fix-login} of {@code acme}, whole, owned by {@code owner}.
   */
  private static String specWorkspace(String status, String spec, String owner) {
    return instance(
        "acme-fix-login",
        status,
        "tank",
        ("\"user.sail.project\": \"acme\", \"user.sail.workspace\": \"fix-login\","
                + " \"user.sail.template\": \"v1\", \"user.sail.ready\": \"1762941601000\","
                + " \"user.sail.spec\": \"%s\", \"user.sail.owner\": \"%s\","
                + " \"user.sail.owner_id\": \"fde_%s\"")
            .formatted(spec, owner, owner));
  }

  /**
   * Scenario 14: a spec's run on a degraded mirror goes on, and what is said of the pool is carried
   * to the dispatch's answer and the spec's room, which say it once, not printed here.
   */
  @Test
  void aSpecsRunOnADegradedPoolIsMadeAndThePoolIsSaidDegradedOnce() throws Throwable {
    var shell =
        incus(DIR_AND_MIRROR, list(template("v1", INPUTS)), snapshots("v1"))
            .onOk(
                "zpool status -P tank",
                DeviceFixtures.read("handwritten-mirror-degraded-removed.zpool-status.txt")
                    .replace("devpool", "tank"));
    var workspaces = workspaces(shell, ticks(0, 0, 1, 2, 3, 4, 5, 6, 7));
    var checked = new AtomicReference<Workspaces.SpecCheck>();

    var said =
        stderrOf(
            () -> {
              checked.set(workspaces.checkSpec(FIX, INPUTS, name -> false));
              workspaces.enterSpec(
                  checked.get(),
                  acme(),
                  "uday",
                  TestOwners::of,
                  tempDir.resolve("no-files"),
                  whole -> {});
            });

    assertTrue(
        shell.invocations().stream().anyMatch(c -> c.startsWith("incus copy acme--tmpl/v1 ")),
        String.join("\n", shell.invocations()));
    assertEquals("", said);
    var degraded = checked.get().degraded().orElseThrow();
    assertTrue(degraded.startsWith("Pool tank is DEGRADED"), degraded);
    assertTrue(
        degraded.contains(
            "sudo zpool replace tank /dev/disk/by-id/wwn-0x5000c500e5f6a7b8-part5 <new device>"),
        degraded);
  }

  /**
   * Scenario 12 (W7): a spec's run that finds its template being built waits for the build, then
   * clones the version it built, never refused for a template the build has not yet recorded.
   */
  @Test
  void aSpecsRunWaitsOnABuildOfItsTemplateUnderWayAndClonesWhatItBuilt() throws Exception {
    var built = new AtomicBoolean();
    var before = incus(DIR_AND_ZFS, list(), snapshots());
    var after = incus(DIR_AND_ZFS, list(template("v1", INPUTS)), snapshots("v1"));
    var shell = switching(built, before, after);
    var workspaces = workspaces(shell, () -> 0L);
    var checked = new AtomicReference<Workspaces.SpecCheck>();
    var failed = new AtomicReference<Exception>();
    Thread checking;
    try (var build = ContainerConfig.hold(locks(), "acme--tmpl")) {
      checking =
          Thread.ofPlatform()
              .start(
                  () -> {
                    try {
                      checked.set(workspaces.checkSpec(FIX, INPUTS, name -> false));
                    } catch (Exception e) {
                      failed.set(e);
                    }
                  });
      var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
      while (checking.getState() != Thread.State.TIMED_WAITING
          && checking.getState() != Thread.State.TERMINATED
          && System.nanoTime() < deadline) {
        Thread.onSpinWait();
      }
      assertEquals(
          Thread.State.TIMED_WAITING,
          checking.getState(),
          "the check waits on the build's lock: " + failed.get());
      built.set(true);
    }
    checking.join(TimeUnit.SECONDS.toMillis(30));

    assertEquals(null, failed.get());
    assertEquals("v1", checked.get().template().version());
  }

  /**
   * R6 for a template build: a spec's run that finds its template's build still under way past
   * {@link Workspaces#CREATE_WAIT} is busy, naming the build, and makes nothing.
   */
  @Test
  void aSpecsRunWaitingPastTheLimitOnABuildOfItsTemplateIsBusyNamingIt() throws Exception {
    var shell = incus(DIR_AND_ZFS, list(template("v1", INPUTS)), snapshots("v1"));
    var workspaces = workspaces(shell, ticks(0, Workspaces.CREATE_WAIT.toNanos()));

    var refused = new AtomicReference<Exception>();
    try (var build = ContainerConfig.hold(locks(), "acme--tmpl")) {
      var checking =
          Thread.ofPlatform()
              .start(
                  () -> {
                    try {
                      workspaces.checkSpec(FIX, INPUTS, name -> false);
                    } catch (Exception e) {
                      refused.set(e);
                    }
                  });
      checking.join(TimeUnit.SECONDS.toMillis(30));
      assertEquals(Thread.State.TERMINATED, checking.getState());
    }

    var busy = assertInstanceOf(Workspaces.Busy.class, refused.get());
    assertEquals(
        "acme's template is being built, and no workspace is cloned from it until it is.",
        busy.getMessage());
    assertFalse(shell.invocations().stream().anyMatch(c -> c.startsWith("incus copy")));
  }

  /**
   * The workspace a check found whole is read again under its name lock: one deleted between the
   * check and the entry is made again, never reused as a raw failure.
   */
  @Test
  void aWorkspaceDeletedBetweenTheCheckAndTheEntryIsMadeAgain() throws Exception {
    var deleted = new AtomicBoolean();
    var before =
        incus(
            DIR_AND_ZFS,
            list(template("v1", INPUTS), specWorkspace("Running", "fix-login", "uday")),
            snapshots("v1"));
    var after = incus(DIR_AND_ZFS, list(template("v1", INPUTS)), snapshots("v1"));
    var workspaces =
        workspaces(switching(deleted, before, after), ticks(0, 0, 1, 2, 3, 4, 5, 6, 7));
    var check = workspaces.checkSpec(FIX, INPUTS, name -> false);
    assertTrue(check.whole());
    deleted.set(true);

    var entered =
        workspaces.enterSpec(
            check, acme(), "uday", TestOwners::of, tempDir.resolve("no-files"), whole -> {});

    assertInstanceOf(Workspaces.Made.class, entered);
    assertTrue(
        after.invocations().stream().anyMatch(c -> c.startsWith("incus copy acme--tmpl/v1 ")));
  }

  /**
   * A spec's workspace name another project's instance holds ({@code acme-x-y}: spec {@code x-y} of
   * acme, workspace {@code y} of {@code acme-x}) is refused naming that owner and the spec's id,
   * the one thing a spec's run can change.
   */
  @Test
  void aSpecsWorkspaceNameAnotherProjectHoldsIsRefusedNamingItsOwnerAndTheSpecsId() {
    var shell =
        incus(
            DIR_AND_ZFS,
            list(
                template("v1", INPUTS),
                instance(
                    "acme-x-y",
                    "Running",
                    "tank",
                    "\"user.sail.project\": \"acme-x\", \"user.sail.workspace\": \"y\","
                        + " \"user.sail.ready\": \"1\"")),
            snapshots("v1"));
    var place = (RunPlace.SpecWorkspace) RunPlace.of("acme", "x-y");

    var refused =
        assertThrows(
            IllegalArgumentException.class,
            () -> workspaces(shell, ticks(0)).checkSpec(place, INPUTS, name -> false));

    assertEquals(
        "Spec x-y's workspace of acme would be the instance 'acme-x-y', which is workspace 'y' of"
            + " acme-x, so no run of the spec goes there. Give the spec another id.",
        refused.getMessage());
  }

  /**
   * High 2: a spec's workspace a create left half-made (its dispatch killed: {@code creating} set,
   * no ready mark) is never entered by a lane: the next entry deletes it and makes it again.
   */
  @Test
  void aHalfMadeWorkspaceOfASpecIsDeletedAndMadeAgainNeverEntered() throws Exception {
    var shell =
        incus(
            DIR_AND_ZFS,
            list(
                template("v1", INPUTS),
                instance(
                    "acme-fix-login",
                    "Running",
                    "tank",
                    "\"user.sail.project\": \"acme\", \"user.sail.workspace\": \"fix-login\","
                        + " \"user.sail.spec\": \"fix-login\", \"user.sail.creating\": \"1\"")),
            snapshots("v1"));
    var workspaces = workspaces(shell, ticks(0, 0, 1, 2, 3, 4, 5, 6, 7));

    var check = workspaces.checkSpec(FIX, INPUTS, name -> false);
    var entered =
        workspaces.enterSpec(
            check, acme(), "uday", TestOwners::of, tempDir.resolve("no-files"), whole -> {});

    assertFalse(check.whole());
    assertInstanceOf(Workspaces.Made.class, entered);
    var delete = shell.invocations().indexOf("incus delete acme-fix-login --force");
    var copy =
        shell
            .invocations()
            .indexOf(
                shell.invocations().stream()
                    .filter(c -> c.startsWith("incus copy acme--tmpl/v1 acme-fix-login"))
                    .findFirst()
                    .orElseThrow());
    assertTrue(delete >= 0 && delete < copy, String.join("\n", shell.invocations()));
  }

  /** A shell that answers as {@code before} until {@code built} is set, then as {@code after}. */
  private static ShellExec switching(AtomicBoolean built, ShellExec before, ShellExec after) {
    return new ShellExec() {
      @Override
      public Result exec(List<String> command)
          throws IOException, InterruptedException, TimeoutException {
        return (built.get() ? after : before).exec(command);
      }

      @Override
      public Result exec(List<String> command, Path workDir, Duration timeout)
          throws IOException, InterruptedException, TimeoutException {
        return (built.get() ? after : before).exec(command, workDir, timeout);
      }

      @Override
      public boolean isDryRun() {
        return false;
      }
    };
  }

  @Test
  void aSpecsFirstRunClonesTheCurrentTemplateWithItsSpecAndOwnerAndLeavesItsReposDetached()
      throws Exception {
    var shell =
        incus(
            DIR_AND_ZFS,
            list(instance("acme", "Running", "devpool", ""), template("v2", INPUTS)),
            snapshots("v1", "v2"));
    var workspaces =
        workspaces(
            shell,
            ticks(0, 0, ms(200), ms(1100), ms(1100), ms(1200), ms(1300), ms(1400), ms(3200)));

    var check = workspaces.checkSpec(FIX, INPUTS, name -> false);
    var made =
        assertInstanceOf(
            Workspaces.Made.class,
            workspaces.enterSpec(
                check, acme(), "uday", TestOwners::of, tempDir.resolve("no-files"), whole -> {}));

    assertFalse(check.whole());
    assertFalse(check.stale());
    assertEquals("acme-fix-login", made.instance());
    assertEquals(3200L, made.created().timings().totalMs());
    assertEquals(List.of(), builtInto, "a run's workspace never builds its template");
    assertTrue(
        indexOf(
                shell,
                List.of(
                    "incus",
                    "copy",
                    "acme--tmpl/v2",
                    "acme-fix-login",
                    "-c",
                    "user.sail.project=acme",
                    "-c",
                    "user.sail.workspace=fix-login",
                    "-c",
                    "user.sail.template=v2",
                    "-c",
                    "user.sail.created_ms=" + NOW.toEpochMilli(),
                    "-c",
                    "user.sail.creating=1",
                    "-c",
                    "user.sail.spec=fix-login",
                    "-c",
                    "user.sail.owner=uday",
                    "-c",
                    "user.sail.owner_id=fde_uday"))
            >= 0,
        String.join("\n", shell.invocations()));
    assertTrue(
        indexOf(
                shell,
                List.of(
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
                    "",
                    "app",
                    "",
                    "web/site",
                    "develop"))
            >= 0,
        "no branch: every repo is left detached at its base");
  }

  @Test
  void aTemplateOlderThanTheDefinitionIsClonedAsItIsAndSaidStale() throws Exception {
    var shell = incus(DIR_AND_ZFS, list(template("v1", "an-older-definition")), snapshots("v1"));

    var check = workspaces(shell, ticks(0)).checkSpec(FIX, INPUTS, name -> false);

    assertTrue(check.stale());
    assertEquals("v1", check.template().version());
    assertFalse(
        shell.invocations().stream().anyMatch(c -> c.startsWith("incus snapshot create")),
        "nothing is built");
  }

  @Test
  void aRunOfASpecIsRefusedBeforeAnythingChangesOnABoxWithNoZfsNoTemplateOrAFaultedPool() {
    var noPool = incus(DIR_ONLY, list(instance("acme", "Running", "devpool", "")), "[]");
    var noTemplate = incus(DIR_AND_ZFS, list(instance("acme", "Running", "devpool", "")), "[]");
    var noSnapshot = incus(DIR_AND_ZFS, list(template("v2", INPUTS)), snapshots("v1"));
    var faulted =
        incus(DIR_AND_MIRROR, list(template("v1", INPUTS)), snapshots("v1"))
            .onOk(
                "zpool status -P tank",
                DeviceFixtures.read("handwritten-mirror-faulted.zpool-status.txt")
                    .replace("devpool", "tank"));

    assertEquals(
        "This box has no ZFS pool, and a workspace is a ZFS clone of its project's template. Add"
            + " one beside the box's pool with: sail host init --storage zfs --pool tank --disk"
            + " <device>, or with --pool-file-size <size> in place of --disk to try ZFS in a"
            + " file.",
        assertThrows(
                IllegalStateException.class,
                () -> workspaces(noPool, ticks(0)).checkSpec(FIX, INPUTS, name -> false))
            .getMessage());
    for (var shell : List.of(noTemplate, noSnapshot)) {
      assertEquals(
          "acme has no template yet: sail project template build acme.",
          assertThrows(
                  IllegalStateException.class,
                  () -> workspaces(shell, ticks(0)).checkSpec(FIX, INPUTS, name -> false))
              .getMessage());
    }
    assertTrue(
        assertThrows(
                IllegalStateException.class,
                () -> workspaces(faulted, ticks(0)).checkSpec(FIX, INPUTS, name -> false))
            .getMessage()
            .startsWith("Pool tank is FAULTED"));
    for (var shell : List.of(noPool, noTemplate, noSnapshot, faulted)) {
      assertFalse(
          shell.invocations().stream()
              .anyMatch(c -> c.startsWith("incus copy") || c.startsWith("incus start")),
          String.join("\n", shell.invocations()));
    }
  }

  @Test
  void aPersonsWorkspaceOrAnotherSpecsUnderTheNameIsRefusedNamingIt() {
    var persons =
        incus(
            DIR_AND_ZFS,
            list(template("v1", INPUTS), workspace("acme", "fix-login", "Running", "v1")),
            snapshots("v1"));
    var anothers =
        incus(
            DIR_AND_ZFS,
            list(template("v1", INPUTS), specWorkspace("Running", "fix-login-x", "eng")),
            snapshots("v1"));

    assertEquals(
        "Workspace 'fix-login' of acme is a person's, not spec fix-login's, so no run of the spec"
            + " goes in it. Delete it if it is not wanted (sail project workspace delete acme"
            + " fix-login).",
        assertThrows(
                IllegalStateException.class,
                () -> workspaces(persons, ticks(0)).checkSpec(FIX, INPUTS, name -> false))
            .getMessage());
    assertEquals(
        "Workspace 'fix-login' of acme is spec fix-login-x's, whose workspace name spec"
            + " fix-login's is too, so no run of fix-login goes in it. Give one of the two specs"
            + " another id.",
        assertThrows(
                IllegalStateException.class,
                () -> workspaces(anothers, ticks(0)).checkSpec(FIX, INPUTS, name -> false))
            .getMessage());
  }

  @Test
  void aSpecsStoppedWorkspaceIsStartedAndMadeTheRunsPersonsBeforeAnythingIsStaged()
      throws Exception {
    var shell =
        incus(
            DIR_AND_ZFS,
            list(template("v1", INPUTS), specWorkspace("Stopped", "fix-login", "eng")),
            snapshots("v1"));
    var workspaces = workspaces(shell, ticks(0));

    var check = workspaces.checkSpec(FIX, INPUTS, name -> false);
    var reused =
        assertInstanceOf(
            Workspaces.Reused.class,
            workspaces.enterSpec(check, acme(), "uday", TestOwners::of, tempDir, whole -> {}));

    assertEquals(new Workspaces.Reused("acme-fix-login", true), reused);
    var start = indexOf(shell, List.of("incus", "start", "acme-fix-login"));
    var owned =
        indexOf(
            shell,
            List.of(
                "incus",
                "config",
                "set",
                "acme-fix-login",
                "user.sail.owner=uday",
                "user.sail.owner_id=fde_uday"));
    assertTrue(0 <= start && start < owned, String.join("\n", shell.invocations()));
    assertFalse(shell.invocations().stream().anyMatch(c -> c.startsWith("incus copy")));
  }

  /** A mirror ZFS calls online carries no notice: only a degraded one is said. */
  @Test
  void aSpecsRunOnAnOnlineMirrorSaysNothingOfThePool() throws Exception {
    var shell =
        incus(DIR_AND_MIRROR, list(template("v1", INPUTS)), snapshots("v1"))
            .onOk(
                "zpool status -P tank",
                DeviceFixtures.read("handwritten-mirror-online.zpool-status.txt")
                    .replace("devpool", "tank"));

    assertTrue(
        workspaces(shell, ticks(0)).checkSpec(FIX, INPUTS, name -> false).degraded().isEmpty());
  }

  /** A reused workspace that cannot be made the run's person's takes no run of theirs. */
  @Test
  void aWorkspaceThatCannotBeMadeTheRunsPersonsIsRefusedSayingWhy() throws Exception {
    var shell =
        incus(
                DIR_AND_ZFS,
                list(template("v1", INPUTS), specWorkspace("Running", "fix-login", "eng")),
                snapshots("v1"))
            .onFail("incus config set acme-fix-login", "permission denied");
    var workspaces = workspaces(shell, ticks(0));
    var check = workspaces.checkSpec(FIX, INPUTS, name -> false);

    var refused =
        assertThrows(
            IOException.class,
            () ->
                workspaces.enterSpec(check, acme(), "uday", TestOwners::of, tempDir, whole -> {}));

    assertTrue(
        refused
            .getMessage()
            .startsWith(
                "Workspace acme-fix-login could not be made nobody's, so no run goes in it: "),
        refused.getMessage());
    assertFalse(
        shell.invocations().stream().anyMatch(c -> c.contains("sail-strip")),
        "nothing is given while the workspace is still labelled another's");
  }

  /**
   * A spec's workspace whose ready mark was taken off by hand is left as it is, and an instance of
   * its name that is no workspace at all is refused as Incus's own.
   */
  @Test
  void aSpecsWorkspaceWithItsMarkTakenOffOrAStrayInstanceUnderItsNameIsRefused() {
    var unmarked =
        incus(
            DIR_AND_ZFS,
            list(
                template("v1", INPUTS),
                instance(
                    "acme-fix-login",
                    "Running",
                    "tank",
                    "\"user.sail.project\": \"acme\", \"user.sail.workspace\": \"fix-login\","
                        + " \"user.sail.spec\": \"fix-login\"")),
            snapshots("v1"));
    var stray =
        incus(
            DIR_AND_ZFS,
            list(template("v1", INPUTS), instance("acme-fix-login", "Running", "tank", "")),
            snapshots("v1"));

    assertTrue(
        assertThrows(
                IllegalStateException.class,
                () -> workspaces(unmarked, ticks(0)).checkSpec(FIX, INPUTS, name -> false))
            .getMessage()
            .startsWith("Workspace 'fix-login' of acme has no ready mark"));
    assertEquals(
        "Spec fix-login's workspace of acme would be the instance 'acme-fix-login', which is an"
            + " instance Incus already has, so no run of the spec goes there. Give the spec"
            + " another id.",
        assertThrows(
                IllegalArgumentException.class,
                () -> workspaces(stray, ticks(0)).checkSpec(FIX, INPUTS, name -> false))
            .getMessage());
  }

  /** A dry run of a create narrates it and takes no create lock, so it waits on no one. */
  @Test
  void aDryRunCreateTakesNoCreateLock() throws Exception {
    var observer = incus(DIR_AND_ZFS, list(template("v1", INPUTS)), snapshots("v1"));
    var locks = locks();

    new Workspaces(
            observer,
            new ShellExecutor(true),
            ticks(0, 1, 2, 3, 4, 5, 6, 7),
            locks,
            () -> 65536,
            url -> false)
        .create(acme(), "fix-login", source(), tempDir, "eng", TestOwners::of);

    assertFalse(Files.exists(locks.resolve("workspace-create.lock")));
  }

  @Test
  void aSpecsRunningWorkspaceOfTheSamePersonIsReusedAsItIs() throws Exception {
    var shell =
        incus(
            DIR_AND_ZFS,
            list(template("v1", INPUTS), specWorkspace("Running", "fix-login", "uday")),
            snapshots("v1"));
    var workspaces = workspaces(shell, ticks(0));

    var check = workspaces.checkSpec(FIX, INPUTS, name -> false);

    assertTrue(check.running());
    assertEquals(
        new Workspaces.Reused("acme-fix-login", false),
        workspaces.enterSpec(check, acme(), "uday", TestOwners::of, tempDir, whole -> {}));
    assertFalse(
        shell.invocations().stream()
            .anyMatch(
                c ->
                    c.startsWith("incus start")
                        || c.startsWith("incus copy")
                        || c.startsWith("incus config set")));
  }

  /**
   * The proof's medium 1, in its order: the sweep holds the workspace's lock mid-stop, Incus
   * listing the instance without its keys as it does then, when a dispatch's check arrives. The
   * check waits for the lock, reads the workspace as the stop left it, stopped, and the entry
   * starts it: never a stranger's instance, never a refusal.
   */
  @Test
  void aDispatchThatArrivesWhileTheSweepStopsTheWorkspaceWaitsThenStartsIt() throws Exception {
    var stoppedNow = new AtomicBoolean();
    var midStop =
        incus(
            DIR_AND_ZFS,
            list(template("v1", INPUTS), "{\"name\": \"acme-fix-login\", \"status\": \"Error\"}"),
            snapshots("v1"));
    var stopped =
        incus(
            DIR_AND_ZFS,
            list(template("v1", INPUTS), specWorkspace("Stopped", "fix-login", "uday")),
            snapshots("v1"));
    var workspaces = workspaces(switching(stoppedNow, midStop, stopped), System::nanoTime);
    var sweeping = new CountDownLatch(1);
    var stopDone = new CountDownLatch(1);
    var sweep =
        Thread.ofPlatform()
            .start(
                () -> {
                  try (var held = workspaces.holdIfFree("acme-fix-login").orElseThrow()) {
                    sweeping.countDown();
                    stopDone.await(30, TimeUnit.SECONDS);
                    stoppedNow.set(true);
                  } catch (Exception e) {
                    throw new IllegalStateException(e);
                  }
                });
    assertTrue(sweeping.await(30, TimeUnit.SECONDS));
    var checked = new AtomicReference<Workspaces.SpecCheck>();
    var failed = new AtomicReference<Exception>();
    var dispatch =
        Thread.ofPlatform()
            .start(
                () -> {
                  try {
                    checked.set(workspaces.checkSpec(FIX, INPUTS, name -> false));
                  } catch (Exception e) {
                    failed.set(e);
                  }
                });
    var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
    while (dispatch.getState() != Thread.State.TIMED_WAITING && System.nanoTime() < deadline) {
      Thread.onSpinWait();
    }
    assertEquals(Thread.State.TIMED_WAITING, dispatch.getState(), "the check waits on the lock");
    stopDone.countDown();
    sweep.join(TimeUnit.SECONDS.toMillis(30));
    dispatch.join(TimeUnit.SECONDS.toMillis(30));

    assertNull(failed.get());
    assertTrue(checked.get().whole());
    assertEquals(
        new Workspaces.Reused("acme-fix-login", true),
        workspaces.enterSpec(checked.get(), acme(), "uday", TestOwners::of, tempDir, whole -> {}));
  }

  /**
   * A change of the workspace that holds its lock past {@link Workspaces#CREATE_WAIT}, on the
   * create lock's clock, makes the entry {@link Workspaces.Busy}; and Incus listing the spec's
   * instance without its keys, as mid-change, is busy too, never a stranger's instance.
   */
  @Test
  void anEntryPastTheWaitOrMeetingAnInstanceIncusCannotSayIsBusyNeverAStrangers() throws Exception {
    var midStop =
        incus(
            DIR_AND_ZFS,
            list(template("v1", INPUTS), "{\"name\": \"acme-fix-login\", \"status\": \"Error\"}"),
            snapshots("v1"));
    var unread =
        assertThrows(
            Workspaces.Busy.class,
            () -> workspaces(midStop, ticks(0)).checkSpec(FIX, INPUTS, name -> false));
    assertEquals(
        "Incus lists instance acme-fix-login without its keys, as it does while another change"
            + " holds it, so whose it is cannot be told yet. If it stays so, sudo incus info"
            + " acme-fix-login says why.",
        unread.getMessage());

    var shell =
        incus(
            DIR_AND_ZFS,
            list(template("v1", INPUTS), specWorkspace("Stopped", "fix-login", "uday")),
            snapshots("v1"));
    var workspaces = workspaces(shell, ticks(0, 0, Workspaces.CREATE_WAIT.toNanos()));
    var refused = new AtomicReference<Exception>();
    var release = new CountDownLatch(1);
    var holding = new CountDownLatch(1);
    var holder =
        Thread.ofPlatform()
            .start(
                () -> {
                  try (var held = workspaces.holdIfFree("acme-fix-login").orElseThrow()) {
                    holding.countDown();
                    release.await(30, TimeUnit.SECONDS);
                  } catch (Exception e) {
                    throw new IllegalStateException(e);
                  }
                });
    assertTrue(holding.await(30, TimeUnit.SECONDS));
    var entering =
        Thread.ofPlatform()
            .start(
                () -> {
                  try {
                    workspaces.checkSpec(FIX, INPUTS, name -> false);
                  } catch (Exception e) {
                    refused.set(e);
                  }
                });
    entering.join(TimeUnit.SECONDS.toMillis(30));
    release.countDown();
    holder.join(TimeUnit.SECONDS.toMillis(30));

    assertEquals(
        "Workspace fix-login of acme is being stopped or deleted on this runner, and nothing"
            + " enters it until that is done.",
        assertInstanceOf(Workspaces.Busy.class, refused.get()).getMessage());
  }

  /**
   * Ruling 4 of round 2: a run of the spec entering its kept workspace clears the kept mark, so the
   * spec, done again, is decided again.
   */
  @Test
  void aRunEnteringItsSpecsKeptWorkspaceClearsTheMark() throws Exception {
    var kept =
        specWorkspace("Running", "fix-login", "uday")
            .replace(
                "\"user.sail.ready\"",
                "\"user.sail.kept\": \"app holds a stash\", \"user.sail.ready\"");
    var shell = incus(DIR_AND_ZFS, list(template("v1", INPUTS), kept), snapshots("v1"));
    var workspaces = workspaces(shell, ticks(0));

    workspaces.enterSpec(
        workspaces.checkSpec(FIX, INPUTS, name -> false),
        acme(),
        "uday",
        TestOwners::of,
        tempDir,
        whole -> {});

    assertEquals(
        List.of("incus config set acme-fix-login user.sail.kept="),
        shell.invocations().stream().filter(c -> c.startsWith("incus config set")).toList());
  }

  /**
   * The merge with #421, question 2: a done spec's workspace #421 disowned and the sweep then kept
   * is entered by the next run as any re-own: given its new owner's credentials, labelled theirs,
   * and its kept mark cleared, nothing of the sweep's standing in the way.
   */
  @Test
  void aDisownedKeptWorkspaceIsReOwnedByTheNextRunAndItsMarkCleared() throws Exception {
    var disownedAndKept =
        specWorkspace("Stopped", "fix-login", "uday")
            .replace("\"user.sail.owner\": \"uday\"", "\"user.sail.kept\": \"app holds a stash\"");
    var shell = incus(DIR_AND_ZFS, list(template("v1", INPUTS), disownedAndKept), snapshots("v1"));
    var workspaces = workspaces(shell, ticks(0));

    var entered =
        workspaces.enterSpec(
            workspaces.checkSpec(FIX, INPUTS, name -> false),
            acme(),
            "ana",
            TestOwners::of,
            tempDir,
            whole -> {});

    assertEquals(new Workspaces.Reused("acme-fix-login", true), entered);
    assertTrue(shell.invocations().stream().anyMatch(c -> c.contains("sail-strip")), "given");
    assertEquals(
        List.of(
            "incus config set acme-fix-login user.sail.owner=ana user.sail.owner_id=fde_ana",
            "incus config set acme-fix-login user.sail.kept="),
        shell.invocations().stream().filter(c -> c.startsWith("incus config set")).toList());
  }

  /**
   * Rule G of round 4: an attach finds its spec's workspace under the entry's lock and starts it
   * when stopped, and does nothing else: no pool or template read, no kept mark cleared, nothing
   * made. One that is not there is refused naming the dispatch whose run makes it, as printed.
   */
  @Test
  void anAttachStartsItsSpecsStoppedWorkspaceAndReadsClearsAndMakesNothingElse() throws Exception {
    var kept =
        specWorkspace("Stopped", "fix-login", "uday")
            .replace(
                "\"user.sail.ready\"",
                "\"user.sail.kept\": \"app holds a stash\", \"user.sail.ready\"");
    var stopped =
        new ScriptedShellExecutor(new ShellExec.Result(0, "", ""))
            .onOk("incus list --format json", list(kept));
    var running =
        new ScriptedShellExecutor(new ShellExec.Result(0, "", ""))
            .onOk("incus list --format json", list(specWorkspace("Running", "fix-login", "uday")));
    var absent =
        new ScriptedShellExecutor(new ShellExec.Result(0, "", ""))
            .onOk("incus list --format json", list());

    workspaces(stopped, ticks(0)).startExisting(FIX, acme(), TestOwners::of);
    workspaces(running, ticks(0)).startExisting(FIX, acme(), TestOwners::of);
    var refused =
        assertThrows(
            IllegalStateException.class,
            () -> workspaces(absent, ticks(0)).startExisting(FIX, acme(), TestOwners::of));

    assertTrue(stopped.invocations().contains("incus start acme-fix-login"));
    assertTrue(
        stopped.invocations().stream().anyMatch(c -> c.contains("sail-strip")),
        "a start gives the recorded owner's credentials");
    assertTrue(running.invocations().stream().noneMatch(c -> c.contains("sail-strip")));
    for (var shell : List.of(stopped, running, absent)) {
      assertTrue(
          shell.invocations().stream()
              .noneMatch(
                  c ->
                      c.startsWith("incus storage")
                          || c.startsWith("incus snapshot")
                          || c.startsWith("incus config set")
                          || c.startsWith("incus copy")),
          shell.invocations().toString());
    }
    assertTrue(running.invocations().stream().noneMatch(c -> c.startsWith("incus start")));
    assertEquals(
        "Spec fix-login has no workspace of acme on this runner (a done spec's is deleted), and an"
            + " attach makes none: a run of the spec makes it. Dispatch one, which reopens a done"
            + " spec, with: sail spec dispatch -p acme --spec fix-login --restart",
        refused.getMessage());
    var leaf =
        SailCommandLine.of(new Sail())
            .parseArgs("spec", "dispatch", "-p", "acme", "--spec", "fix-login", "--restart");
    while (leaf.hasSubcommand()) {
      leaf = leaf.subcommand();
    }
    assertEquals("sail spec dispatch", leaf.commandSpec().qualifiedName(" "));
  }

  /**
   * Item 1 of round 4: an attach that meets the sweep holding its workspace's lock waits for it, on
   * the create lock's clock, never refusing at once, and starts the workspace the stop left.
   */
  @Test
  void anAttachWaitsForTheSweepsLockAndThenStartsTheWorkspace() throws Exception {
    var shell =
        new ScriptedShellExecutor(new ShellExec.Result(0, "", ""))
            .onOk("incus list --format json", list(specWorkspace("Stopped", "fix-login", "uday")));
    var release = new CountDownLatch(1);
    var released = new CountDownLatch(1);
    var reads = new AtomicInteger();
    LongSupplier nanos =
        () -> {
          if (reads.getAndIncrement() == 0) {
            return 0;
          }
          release.countDown();
          try {
            assertTrue(released.await(30, TimeUnit.SECONDS));
          } catch (InterruptedException e) {
            throw new IllegalStateException(e);
          }
          return Workspaces.CREATE_WAIT.toNanos() - 1;
        };
    var workspaces = workspaces(shell, nanos);
    var holding = new CountDownLatch(1);
    var sweep =
        Thread.ofPlatform()
            .start(
                () -> {
                  try (var held = workspaces.holdIfFree("acme-fix-login").orElseThrow()) {
                    holding.countDown();
                    release.await(30, TimeUnit.SECONDS);
                  } catch (Exception e) {
                    throw new IllegalStateException(e);
                  }
                  released.countDown();
                });
    assertTrue(holding.await(30, TimeUnit.SECONDS));

    workspaces.startExisting(FIX, acme(), TestOwners::of);
    sweep.join(TimeUnit.SECONDS.toMillis(30));

    assertTrue(reads.get() >= 2, "the entry read the clock while it waited");
    assertTrue(shell.invocations().contains("incus start acme-fix-login"));
  }

  /**
   * Minor m2: a workspace the check saw running but the entry finds stopped, the sweep having
   * stopped it between, starts only within the box's cap, counted over every other whole spec
   * workspace, never itself; one still running asks nothing of the cap.
   */
  @Test
  void anEntryThatStartsAStoppedWorkspaceCountsTheCapOverTheOthers() throws Exception {
    var other =
        instance(
            "acme-other",
            "Running",
            "tank",
            "\"user.sail.project\": \"acme\", \"user.sail.workspace\": \"other\","
                + " \"user.sail.ready\": \"1762941601000\", \"user.sail.spec\": \"other\"");
    var stoppedNow = new AtomicBoolean();
    var running =
        incus(
            DIR_AND_ZFS,
            list(template("v1", INPUTS), specWorkspace("Running", "fix-login", "uday"), other),
            snapshots("v1"));
    var stopped =
        incus(
            DIR_AND_ZFS,
            list(template("v1", INPUTS), specWorkspace("Stopped", "fix-login", "uday"), other),
            snapshots("v1"));
    var workspaces = workspaces(switching(stoppedNow, running, stopped), System::nanoTime);
    var check = workspaces.checkSpec(FIX, INPUTS, name -> false);
    var counted = new ArrayList<Set<String>>();

    workspaces.enterSpec(check, acme(), "uday", TestOwners::of, tempDir, counted::add);
    stoppedNow.set(true);
    var full =
        assertThrows(
            IllegalStateException.class,
            () ->
                workspaces.enterSpec(
                    check,
                    acme(),
                    "uday",
                    TestOwners::of,
                    tempDir,
                    whole -> {
                      counted.add(whole);
                      throw new IllegalStateException("full");
                    }));

    assertEquals("full", full.getMessage());
    assertEquals(List.of(Set.of("acme/other")), counted);
    assertTrue(stopped.invocations().stream().noneMatch(c -> c.startsWith("incus start")));
  }

  @Test
  void aCreateThatCannotHaveTheBoxsCreateLockWithinItsWaitIsBusyAndMakesNothing() throws Exception {
    var shell = incus(DIR_AND_ZFS, list(template("v1", INPUTS)), snapshots("v1"));
    var holding = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    var other =
        Thread.ofVirtual()
            .start(
                () -> {
                  try (var held = FileMutex.acquire(locks().resolve(Workspaces.CREATE_LOCK))) {
                    holding.countDown();
                    release.await();
                  } catch (IOException e) {
                    throw new UncheckedIOException(e);
                  } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                  }
                });
    assertTrue(holding.await(30, TimeUnit.SECONDS));
    var waited = Workspaces.CREATE_WAIT.toNanos();
    try {
      var spec = workspaces(shell, ticks(0, 0, waited));
      var check = spec.checkSpec(FIX, INPUTS, name -> false);
      assertThrows(
          Workspaces.Busy.class,
          () ->
              spec.enterSpec(
                  check, acme(), "uday", TestOwners::of, tempDir.resolve("no-files"), whole -> {}));
      assertThrows(
          Workspaces.Busy.class,
          () ->
              workspaces(shell, ticks(0, 0, waited))
                  .create(
                      acme(),
                      "second",
                      source(),
                      tempDir.resolve("no-files"),
                      "eng",
                      TestOwners::of));
    } finally {
      release.countDown();
      other.join();
    }
    assertFalse(
        shell.invocations().stream().anyMatch(c -> c.startsWith("incus copy")),
        String.join("\n", shell.invocations()));
  }

  @Test
  void aWorkspaceALiveRunOfItsSpecIsInIsNeitherStoppedNorDeleted() {
    var shell =
        incus(
            DIR_AND_ZFS,
            list(template("v1", INPUTS), specWorkspace("Running", "fix-login", "uday")),
            snapshots("v1"));
    var workspaces = workspaces(shell, ticks());
    Predicate<String> live = "fix-login"::equals;
    var refusal =
        "Workspace 'fix-login' of acme holds a live run of spec fix-login, which would die with"
            + " it. Stop the run first: sail agent stop acme --spec fix-login.";

    assertEquals(
        refusal,
        assertThrows(IllegalStateException.class, () -> workspaces.stop("acme", "fix-login", live))
            .getMessage());
    assertEquals(
        refusal,
        assertThrows(
                IllegalStateException.class,
                () ->
                    workspaces.delete(
                        "acme",
                        "fix-login",
                        live,
                        instance -> {
                          throw new AssertionError("nothing is asked");
                        }))
            .getMessage());
    assertFalse(
        shell.invocations().stream()
            .anyMatch(c -> c.startsWith("incus stop") || c.startsWith("incus delete")));
  }

  @Test
  void aListingNamesTheSpecAndThePersonOfEachWorkspace() throws Exception {
    var shell =
        incus(
            DIR_AND_ZFS,
            list(
                instance("acme", "Running", "devpool", ""),
                specWorkspace("Stopped", "fix-login", "uday")),
            "[]");

    var rows = workspaces(shell, ticks()).list("acme", id -> true).workspaces();

    assertEquals(
        List.of("main:null:null", "fix-login:fix-login:uday"),
        rows.stream().map(row -> row.name() + ":" + row.spec() + ":" + row.owner()).toList());
  }

  /**
   * N4: a listing shows a workspace's owner by the id its label stores. One whose id is no active
   * person's (the handle given to someone new) is shown as removed, never under the handle the new
   * holder now has; one whose id is active is shown by its handle; a label with a handle alone is
   * nobody's, shown with no owner.
   */
  @Test
  void aListingShowsAWorkspaceWhoseOwnerIdIsNoActivePersonsAsRemoved() throws Exception {
    var shell =
        incus(
            DIR_AND_ZFS,
            list(
                owned("acme-a", "Running", "tmp").replace("\"fde_tmp\"", "\"fde_old_tmp\""),
                owned("acme-b", "Running", "tmp").replace("\"fde_tmp\"", "\"fde_new_tmp\""),
                owned("acme-c", "Running", "uday"),
                owned("acme-d", "Running", "ana")
                    .replace(", \"user.sail.owner_id\": \"fde_ana\"", "")),
            "[]");

    var rows =
        workspaces(shell, ticks())
            .list("acme", id -> List.of("fde_new_tmp", "fde_uday").contains(id))
            .workspaces();

    assertEquals(
        List.of("main:null", "a:" + Workspaces.REMOVED_OWNER, "b:tmp", "c:uday", "d:null"),
        rows.stream().map(row -> row.name() + ":" + row.owner()).toList());
  }

  private static final WorkspaceOwner UDAY =
      new WorkspaceOwner(
          "uday",
          "Uday C",
          "uday@sail.invalid",
          Map.of("github.com", "ghp_uday"),
          List.of("ssh-ed25519 AAAAuday uday@laptop", "ssh-ed25519 AAAAbox uday@box"),
          "fde_uday");
  private static final WorkspaceOwner ANA =
      new WorkspaceOwner(
          "ana",
          "Ana",
          "ana@sail.invalid",
          Map.of("github.com", "ghp_ana"),
          List.of("ssh-ed25519 AAAAana ana@laptop"),
          "fde_ana");

  /** A shell that keeps what each push carried, by destination, before its staging file goes. */
  static final class Pushing implements ShellExec {
    final ScriptedShellExecutor shell;
    final Map<String, String> pushed = new LinkedHashMap<>();

    Pushing(ScriptedShellExecutor shell) {
      this.shell = shell;
    }

    @Override
    public Result exec(List<String> command) throws IOException {
      keep(command);
      return shell.exec(command);
    }

    @Override
    public Result exec(List<String> command, Path workDir, Duration timeout) throws IOException {
      keep(command);
      return shell.exec(command, workDir, timeout);
    }

    @Override
    public boolean isDryRun() {
      return false;
    }

    private void keep(List<String> command) throws IOException {
      if (command.size() > 3 && command.subList(0, 3).equals(List.of("incus", "file", "push"))) {
        pushed.put(command.getLast(), Files.readString(Path.of(command.get(command.size() - 2))));
      }
    }
  }

  private static List<String> strip(String instance, String... identity) {
    var argv =
        new ArrayList<>(
            List.of(
                "incus",
                "exec",
                instance,
                "--",
                "bash",
                "-c",
                WorkspaceCredentials.STRIP,
                "sail-strip"));
    argv.addAll(List.of(identity));
    return argv;
  }

  private static int indexWhere(ScriptedShellExecutor shell, Predicate<String> at) {
    var invocations = shell.invocations();
    for (var i = 0; i < invocations.size(); i++) {
      if (at.test(invocations.get(i))) {
        return i;
      }
    }
    return -1;
  }

  /**
   * Scenarios 2 and 8: a workspace is given its owner's credentials after its start and before its
   * prepare, so the fetch uses their token: every credential it held goes, their name and email
   * become its identity, their keys its authorized_keys and their token its store; gh is logged in
   * after the prepare, once the network is up.
   */
  @Test
  void aNewWorkspaceIsGivenItsOwnersCredentialsBetweenItsStartAndItsPrepare() throws Exception {
    var scripted = incus(DIR_AND_ZFS, list(template("v1", INPUTS)), snapshots("v1"));
    var shell = new Pushing(scripted);

    new Workspaces(shell, shell, ticks(0, 1, 2, 3, 4, 5, 6, 7), locks(), () -> 65536, url -> false)
        .create(
            acme(), "fix-login", source(), tempDir.resolve("no-files"), UDAY.handle(), h -> UDAY);

    var start = indexOf(scripted, List.of("incus", "start", "acme-fix-login"));
    var stripped = indexOf(scripted, strip("acme-fix-login", "Uday C", "uday@sail.invalid"));
    var keys =
        indexWhere(scripted, c -> c.endsWith("acme-fix-login/home/dev/.ssh/authorized_keys"));
    var store = indexWhere(scripted, c -> c.endsWith("acme-fix-login/home/dev/.git-credentials"));
    var helper = indexWhere(scripted, c -> c.endsWith("credential.helper store"));
    var prepare = indexWhere(scripted, c -> c.contains("sail-workspace acme fix-login"));
    var gh = indexWhere(scripted, c -> c.contains("gh auth login --hostname"));
    assertTrue(
        0 <= start
            && start < stripped
            && stripped < keys
            && keys < store
            && store < helper
            && helper < prepare
            && prepare < gh,
        String.join("\n", scripted.invocations()));
    assertEquals(
        "ssh-ed25519 AAAAuday uday@laptop\nssh-ed25519 AAAAbox uday@box\n",
        shell.pushed.get("acme-fix-login/home/dev/.ssh/authorized_keys"));
    assertEquals(
        "https://oauth2:ghp_uday@github.com\n",
        shell.pushed.get("acme-fix-login/home/dev/.git-credentials"));
    assertTrue(
        scripted.invocations().stream()
            .anyMatch(
                c ->
                    c.startsWith("incus copy ")
                        && c.contains("user.sail.owner=uday")
                        && c.contains("user.sail.owner_id=fde_uday")),
        "the clone carries its owner's id beside the handle, so no label is ever handle-only");
    assertFalse(
        scripted.invocations().stream().anyMatch(c -> c.contains("ghp_uday")),
        "a token is never on a command line");
  }

  /**
   * Scenario 7 for a person's own workspace: a creator the workspace could not be given to is
   * refused before any template is built or anything copied.
   */
  @Test
  void aPersonsCreateForAnOwnerWithNoEmailIsRefusedBeforeAnythingIsCopied() {
    var shell = incus(DIR_AND_ZFS, list(template("v1", "an-older-definition")), snapshots("v1"));
    var owner = new WorkspaceOwner("ana", "Ana", "", Map.of(), List.of(), "fde_ana");

    var refused =
        assertThrows(
            IllegalStateException.class,
            () ->
                workspaces(shell, ticks())
                    .create(
                        acme(),
                        "fix-login",
                        source(),
                        tempDir.resolve("no-files"),
                        owner.handle(),
                        h -> owner));

    assertEquals(
        "ana has no email on this box, and a workspace commits as its owner. Set it on main with"
            + " sail fde update ana --email <email>.",
        refused.getMessage());
    assertEquals(List.of(), builtInto, "no template is built");
    assertFalse(shell.invocations().stream().anyMatch(c -> c.startsWith("incus copy")));
  }

  /** Scenario 5: an owner who keeps no token is given no store and no gh login. */
  @Test
  void anOwnerWhoKeepsNoTokenGetsNoTokenStoreAndNoGhLogin() throws Exception {
    var scripted = incus(DIR_AND_ZFS, list(template("v1", INPUTS)), snapshots("v1"));
    var shell = new Pushing(scripted);
    var owner =
        new WorkspaceOwner(
            "ana", "Ana", "ana@sail.invalid", Map.of(), List.of("ssh-ed25519 AAAAana"), "fde_ana");

    new Workspaces(shell, shell, ticks(0, 1, 2, 3, 4, 5, 6, 7), locks(), () -> 65536, url -> false)
        .create(
            acme(), "fix-login", source(), tempDir.resolve("no-files"), owner.handle(), h -> owner);

    assertTrue(
        scripted
            .invocations()
            .contains(String.join(" ", strip("acme-fix-login", "Ana", "ana@sail.invalid"))));
    assertEquals(
        List.of("acme-fix-login/home/dev/.ssh/authorized_keys"),
        List.copyOf(shell.pushed.keySet()));
    assertFalse(scripted.invocations().stream().anyMatch(c -> c.contains("gh auth login")));
    assertFalse(scripted.invocations().stream().anyMatch(c -> c.contains("credential.helper")));
  }

  /**
   * Scenario 6 (T3): a spec's workspace another person owns is stripped of all of theirs and given
   * its new owner's before it is recorded as theirs, so nothing of the previous owner is left.
   */
  @Test
  void aWorkspaceOfAnotherIsStrippedAndGivenItsNewOwnersBeforeItIsRecordedTheirs()
      throws Exception {
    var scripted =
        incus(
            DIR_AND_ZFS,
            list(template("v1", INPUTS), specWorkspace("Running", "fix-login", "uday")),
            snapshots("v1"));
    var shell = new Pushing(scripted);
    var workspaces = new Workspaces(shell, shell, ticks(0, 1), locks(), () -> 65536, url -> false);
    var check = workspaces.checkSpec(FIX, INPUTS, name -> false);

    workspaces.enterSpec(
        check, acme(), ANA.handle(), h -> ANA, tempDir.resolve("no-files"), whole -> {});

    var stripped = indexOf(scripted, strip("acme-fix-login", "Ana", "ana@sail.invalid"));
    var gh = indexWhere(scripted, c -> c.contains("gh auth login --hostname"));
    var owned =
        indexOf(
            scripted,
            List.of(
                "incus",
                "config",
                "set",
                "acme-fix-login",
                "user.sail.owner=ana",
                "user.sail.owner_id=fde_ana"));
    assertTrue(
        0 <= stripped && stripped < gh && gh < owned, String.join("\n", scripted.invocations()));
    assertEquals(
        "ssh-ed25519 AAAAana ana@laptop\n",
        shell.pushed.get("acme-fix-login/home/dev/.ssh/authorized_keys"),
        "nothing of uday's keys is left");
    assertEquals(
        "https://oauth2:ghp_ana@github.com\n",
        shell.pushed.get("acme-fix-login/home/dev/.git-credentials"));
  }

  /**
   * The last look's n4: an entry judges a spec's workspace by the owner id its label stores. One
   * labelled with ana's handle but an id that is not the entering ana's was another person's (the
   * handle given to someone new), so it is stripped and given as another's, and labelled with the
   * new id; one labelled with her own id is entered with nothing taken.
   */
  @Test
  void anEntryJudgesAWorkspaceByItsStoredOwnerIdNotItsHandle() throws Exception {
    var ana =
        new WorkspaceOwner(
            ANA.handle(), ANA.name(), ANA.email(), ANA.tokens(), ANA.keys(), "fde_new_ana");
    for (var labelled : List.of("fde_old_ana", "fde_new_ana")) {
      var scripted =
          incus(
              DIR_AND_ZFS,
              list(
                  template("v1", INPUTS),
                  instance(
                      "acme-fix-login",
                      "Running",
                      "tank",
                      ("\"user.sail.project\": \"acme\", \"user.sail.workspace\": \"fix-login\","
                              + " \"user.sail.template\": \"v1\", \"user.sail.ready\":"
                              + " \"1762941601000\", \"user.sail.spec\": \"fix-login\","
                              + " \"user.sail.owner\": \"ana\", \"user.sail.owner_id\": \"%s\"")
                          .formatted(labelled))),
              snapshots("v1"));
      var shell = new Pushing(scripted);
      var workspaces =
          new Workspaces(shell, shell, ticks(0, 1), locks(), () -> 65536, url -> false);

      workspaces.enterSpec(
          workspaces.checkSpec(FIX, INPUTS, name -> false),
          acme(),
          "ana",
          h -> ana,
          tempDir.resolve("no-files"),
          whole -> {});

      var stripped = indexOf(scripted, strip("acme-fix-login", "Ana", "ana@sail.invalid")) >= 0;
      var relabelled =
          scripted
              .invocations()
              .contains(
                  "incus config set acme-fix-login user.sail.owner=ana"
                      + " user.sail.owner_id=fde_new_ana");
      assertEquals(
          labelled.equals("fde_old_ana") ? List.of(true, true) : List.of(false, false),
          List.of(stripped, relabelled),
          labelled + ": " + String.join("\n", scripted.invocations()));
    }
  }

  /** A spec's workspace already its owner's is entered as it is: nothing is taken or given. */
  @Test
  void aWorkspaceAlreadyItsOwnersIsEnteredWithNothingTakenOrGiven() throws Exception {
    var shell =
        incus(
            DIR_AND_ZFS,
            list(template("v1", INPUTS), specWorkspace("Running", "fix-login", "uday")),
            snapshots("v1"));
    var workspaces = workspaces(shell, ticks(0, 1));
    var check = workspaces.checkSpec(FIX, INPUTS, name -> false);

    workspaces.enterSpec(
        check, acme(), UDAY.handle(), h -> UDAY, tempDir.resolve("no-files"), whole -> {});

    assertFalse(shell.invocations().stream().anyMatch(c -> c.contains("sail-strip")));
    assertFalse(shell.invocations().stream().anyMatch(c -> c.startsWith("incus file push")));
  }

  private static String owned(String instance, String status, String owner) {
    return instance(
        instance,
        status,
        "tank",
        ("\"user.sail.project\": \"acme\", \"user.sail.workspace\": \"%s\","
                + " \"user.sail.ready\": \"1\", \"user.sail.owner\": \"%s\","
                + " \"user.sail.owner_id\": \"fde_%s\"")
            .formatted(instance.substring("acme-".length()), owner, owner));
  }

  /**
   * A token a person replaces reaches each running workspace of theirs at once, so its next fetch
   * uses the new one; a stopped one, and another person's, are left as they are. N5: the owner is
   * unchanged, so nothing is stripped: the store is written beside the old one and renamed over it,
   * so a live run never meets the workspace without a token.
   */
  @Test
  void aReplacedTokenReachesTheOwnersRunningWorkspacesAloneByTheOwnerStep() throws Exception {
    var scripted =
        incus(
            DIR_AND_ZFS,
            list(
                owned("acme-a", "Running", "ana"),
                owned("acme-b", "Stopped", "ana"),
                owned("acme-c", "Running", "uday")),
            "[]");
    var shell = new Pushing(scripted);
    var replaced =
        new WorkspaceOwner(
            "ana",
            "Ana",
            "ana@sail.invalid",
            Map.of("github.com", "ghp_new"),
            ANA.keys(),
            "fde_ana");

    var unreached =
        new Workspaces(shell, shell, ticks(), locks(), () -> 65536, url -> false)
            .refresh(replaced, project -> acme());

    assertEquals(List.of(), unreached);
    assertFalse(
        scripted.invocations().stream()
            .anyMatch(
                c ->
                    c.contains("sail-strip")
                        || c.endsWith("rm -f \"$1\" sh /home/dev/.git-credentials")
                        || c.contains("file delete")),
        "the token file is never removed");
    assertEquals(
        "https://oauth2:ghp_new@github.com\n",
        shell.pushed.get("acme-a/home/dev/.git-credentials.sail-new"));
    var pushed = indexWhere(scripted, c -> c.endsWith("acme-a/home/dev/.git-credentials.sail-new"));
    var renamed =
        indexWhere(
            scripted,
            c ->
                c.startsWith("incus exec acme-a")
                    && c.endsWith(
                        "mv -f /home/dev/.git-credentials.sail-new /home/dev/.git-credentials"));
    assertTrue(0 <= pushed && pushed < renamed, String.join("\n", scripted.invocations()));
    assertTrue(
        scripted.invocations().stream().anyMatch(c -> c.contains("sail-resolve github.com")));
    assertFalse(
        scripted.invocations().stream().anyMatch(c -> c.contains("acme-b") || c.contains("acme-c")),
        "a stopped workspace and another person's are not touched");
  }

  /** A running workspace a token change cannot reach is said, and stops none of the others. */
  @Test
  void aWorkspaceATokenChangeCannotReachIsSaidAndStopsNoneOfTheOthers() throws Exception {
    var shell =
        incus(
                DIR_AND_ZFS,
                list(owned("acme-a", "Running", "ana"), owned("acme-d", "Running", "ana")),
                "[]")
            .onFail("acme-a/home/dev/.git-credentials.sail-new", "Error: Instance is not running");

    var unreached = workspaces(shell, ticks()).refresh(ANA, project -> acme());

    assertEquals(1, unreached.size(), unreached.toString());
    assertTrue(unreached.getFirst().startsWith("acme-a: "), unreached.getFirst());
    assertTrue(
        shell.invocations().stream()
            .anyMatch(c -> c.startsWith("incus exec acme-d") && c.contains("mv -f")),
        String.join("\n", shell.invocations()));
  }

  /**
   * A stopped workspace takes its owner's credentials as they are now when it starts, after its
   * readiness: a token replaced or removed while it was stopped reaches it then.
   */
  @Test
  void aStoppedWorkspaceTakesItsOwnersCredentialsAsTheyAreNowWhenItStarts() throws Exception {
    var scripted = incus(DIR_AND_ZFS, list(owned("acme-fix-login", "Stopped", "ana")), "[]");
    var shell = new Pushing(scripted);
    var asked = new ArrayList<String>();

    new Workspaces(shell, shell, ticks(0, 1, 2), locks(), () -> 65536, url -> false)
        .start(
            acme(),
            "fix-login",
            handle -> {
              asked.add(handle);
              return ANA;
            });

    assertEquals(List.of("ana"), asked);
    var start = indexOf(scripted, List.of("incus", "start", "acme-fix-login"));
    var stripped = indexOf(scripted, strip("acme-fix-login", "Ana", "ana@sail.invalid"));
    assertTrue(0 <= start && start < stripped, String.join("\n", scripted.invocations()));
    assertEquals(
        "https://oauth2:ghp_ana@github.com\n",
        shell.pushed.get("acme-fix-login/home/dev/.git-credentials"));
  }

  /** A workspace whose owner was taken away is started with nothing given and no one asked. */
  @Test
  void aWorkspaceWithNoOwnerIsStartedWithNothingGiven() throws Exception {
    var shell = incus(DIR_AND_ZFS, list(owned("acme-fix-login", "Stopped", "")), "[]");

    workspaces(shell, ticks(0, 1, 2))
        .start(
            acme(),
            "fix-login",
            handle -> {
              throw new AssertionError("no owner is asked for");
            });

    assertFalse(shell.invocations().stream().anyMatch(c -> c.contains("sail-strip")));
  }

  /** A spec's stopped workspace its owner's run starts again is given their credentials anew. */
  @Test
  void aSpecsStoppedWorkspaceItsOwnersRunStartsIsGivenTheirCredentialsAnew() throws Exception {
    var shell =
        incus(
            DIR_AND_ZFS,
            list(template("v1", INPUTS), specWorkspace("Stopped", "fix-login", "ana")),
            snapshots("v1"));
    var workspaces = workspaces(shell, ticks(0, 1));
    var check = workspaces.checkSpec(FIX, INPUTS, name -> false);

    workspaces.enterSpec(
        check, acme(), ANA.handle(), h -> ANA, tempDir.resolve("no-files"), whole -> {});

    assertTrue(
        shell
            .invocations()
            .contains(String.join(" ", strip("acme-fix-login", "Ana", "ana@sail.invalid"))));
    assertFalse(
        shell
            .invocations()
            .contains(
                "incus config set acme-fix-login user.sail.owner=ana user.sail.owner_id=fde_ana"),
        "already hers: no owner is written");
  }

  /**
   * Rule 1 (review M1), and item 4 of round 3: a re-own cut short after the new owner's token is
   * written, by a gh login that fails, is stripped again and left nobody's, holding no one's token,
   * identity or key; so its previous owner's next entry strips it and gives it theirs again.
   */
  @Test
  void aReOwnCutShortLeavesTheWorkspaceNobodysSoThePreviousOwnersNextEntryGivesTheirsAgain()
      throws Exception {
    var box = new Box();
    box.put("acme--tmpl", "Stopped", template("v1", INPUTS), "v1");
    box.put("acme-fix-login", "Running", specWorkspace("Running", "fix-login", "uday"));
    var failing = new AtomicBoolean(true);
    var ran = new ArrayList<String>();
    var shell =
        new ShellExec() {
          @Override
          public Result exec(List<String> command) {
            var joined = String.join(" ", command);
            ran.add(joined);
            if (failing.get() && joined.contains("gh auth login")) {
              return new Result(1, "", "error connecting to api.github.com");
            }
            return box.exec(command);
          }

          @Override
          public Result exec(List<String> command, Path workDir, Duration timeout) {
            return exec(command);
          }

          @Override
          public boolean isDryRun() {
            return false;
          }
        };
    var workspaces =
        new Workspaces(shell, shell, ticks(0, 1, 2, 3), locks(), () -> 65536, url -> false);

    assertThrows(
        IOException.class,
        () ->
            workspaces.enterSpec(
                workspaces.checkSpec(FIX, INPUTS, name -> false),
                acme(),
                "ana",
                handle -> ANA,
                tempDir.resolve("no-files"),
                whole -> {}));
    assertEquals("", box.key("acme-fix-login", BoxInstances.OWNER_KEY), "nobody's, not uday's");
    var gh = ran.stream().filter(line -> line.contains("gh auth login")).findFirst().orElseThrow();
    assertEquals(
        String.join(" ", strip("acme-fix-login")),
        ran.subList(ran.indexOf(gh), ran.size()).stream()
            .filter(line -> line.contains("sail-strip"))
            .findFirst()
            .orElse("no strip after gh failed"),
        String.join("\n", ran));
    failing.set(false);
    ran.clear();
    workspaces.enterSpec(
        workspaces.checkSpec(FIX, INPUTS, name -> false),
        acme(),
        "uday",
        handle -> UDAY,
        tempDir.resolve("no-files"),
        whole -> {});

    assertTrue(
        ran.contains(String.join(" ", strip("acme-fix-login", "Uday C", "uday@sail.invalid"))),
        String.join("\n", ran));
    assertEquals("uday", box.key("acme-fix-login", BoxInstances.OWNER_KEY));
  }

  /**
   * Round 2, item 4: no Sail door restores a workspace's snapshot, so one is restored only by hand,
   * and Incus brings the snapshot's owner label back with it. The current owner's next entry is
   * then a re-own: stripped whole first, then given theirs, then labelled theirs, so none of the
   * snapshot owner's token, identity or keys is left in use.
   */
  @Test
  void aWorkspaceRestoredByHandToAnotherOwnersSnapshotIsStrippedThenGivenAtItsOwnersNextEntry()
      throws Exception {
    var box = new Box();
    box.put("acme--tmpl", "Stopped", template("v1", INPUTS), "v1");
    box.put("acme-fix-login", "Running", specWorkspace("Running", "fix-login", "uday"));
    var ran = new ArrayList<String>();
    var shell =
        new ShellExec() {
          @Override
          public Result exec(List<String> command) {
            ran.add(String.join(" ", command));
            return box.exec(command);
          }

          @Override
          public Result exec(List<String> command, Path workDir, Duration timeout) {
            return exec(command);
          }

          @Override
          public boolean isDryRun() {
            return false;
          }
        };
    var workspaces = new Workspaces(shell, shell, ticks(0, 1), locks(), () -> 65536, url -> false);

    workspaces.enterSpec(
        workspaces.checkSpec(FIX, INPUTS, name -> false),
        acme(),
        "ana",
        handle -> ANA,
        tempDir.resolve("no-files"),
        whole -> {});

    var stripped =
        ran.indexOf(String.join(" ", strip("acme-fix-login", "Ana", "ana@sail.invalid")));
    var keys = indexOfFirst(ran, c -> c.endsWith("acme-fix-login/home/dev/.ssh/authorized_keys"));
    var store = indexOfFirst(ran, c -> c.endsWith("acme-fix-login/home/dev/.git-credentials"));
    var labelled =
        ran.indexOf(
            "incus config set acme-fix-login user.sail.owner=ana user.sail.owner_id=fde_ana");
    assertTrue(
        0 <= stripped && stripped < keys && keys < store && store < labelled,
        String.join("\n", ran));
    assertEquals("ana", box.key("acme-fix-login", BoxInstances.OWNER_KEY));
  }

  /** Real Incus 7.6's own answer to a delete of a file that is not there, from the GCP proof. */
  private static final String MISSING_FILE =
      "Error: remove /home/dev/.git-credentials.sail-new: file does not exist";

  /**
   * Scenario 11 and the proof's High 1: a removed person's workspaces lose everything a give wrote,
   * stripped in the container by the give's own idempotent script (a stopped one is started for it
   * and stopped again), and their owner; a workspace labelled with an owner id is judged by that
   * id, one labelled by handle alone by its handle; another's is left as it is, and so is one not
   * yet due. A workspace #418's sweep kept is started for the strip all the same (a removed
   * person's credentials outrank that sweep's promise), stopped again, and its kept mark left as it
   * was. Incus answers a delete of a missing file as it really does, so no strip may lean on a
   * delete.
   */
  @Test
  void aRemovedPersonsWorkspacesAreStrippedInTheContainerAndJudgedByTheirOwnerId()
      throws Exception {
    var scripted =
        incus(
                DIR_AND_ZFS,
                list(
                    template("v1", INPUTS),
                    instance(
                        "acme-a",
                        "Running",
                        "tank",
                        "\"user.sail.project\": \"acme\", \"user.sail.workspace\": \"a\","
                            + " \"user.sail.ready\": \"1\", \"user.sail.owner\": \"uday\""),
                    instance(
                        "acme-b",
                        "Stopped",
                        "tank",
                        "\"user.sail.project\": \"acme\", \"user.sail.workspace\": \"b\","
                            + " \"user.sail.ready\": \"1\", \"user.sail.owner\": \"ana\","
                            + " \"user.sail.kept\": \"it holds commits no remote has\""),
                    instance(
                        "acme-e",
                        "Running",
                        "tank",
                        "\"user.sail.project\": \"acme\", \"user.sail.workspace\": \"e\","
                            + " \"user.sail.ready\": \"1\", \"user.sail.owner\": \"ana\""),
                    instance(
                        "acme-c",
                        "Running",
                        "tank",
                        "\"user.sail.project\": \"acme\", \"user.sail.workspace\": \"c\","
                            + " \"user.sail.ready\": \"1\", \"user.sail.owner\": \"tmp\","
                            + " \"user.sail.owner_id\": \"fde_old_tmp\""),
                    instance(
                        "acme-d",
                        "Running",
                        "tank",
                        "\"user.sail.project\": \"acme\", \"user.sail.workspace\": \"d\","
                            + " \"user.sail.ready\": \"1\", \"user.sail.owner\": \"tmp\","
                            + " \"user.sail.owner_id\": \"fde_new_tmp\"")),
                snapshots("v1"))
            .onFail("incus file delete", MISSING_FILE);

    var unreached =
        new Workspaces(scripted, scripted, ticks(), locks(), () -> 65536, url -> false)
            .disown(
                (handle, id) -> id == null ? !handle.equals("ana") : id.equals("fde_new_tmp"),
                instance -> !instance.equals("acme-e"));

    assertEquals(List.of(), unreached);
    assertEquals(
        List.of(
            "incus start acme-b",
            String.join(" ", strip("acme-b")),
            "incus stop acme-b",
            "incus config set acme-b user.sail.owner= user.sail.owner_id=",
            String.join(" ", strip("acme-c")),
            "incus config set acme-c user.sail.owner= user.sail.owner_id="),
        scripted.invocations().stream()
            .filter(c -> c.contains("acme-") && !c.startsWith("incus list"))
            .map(c -> c.startsWith("incus stop acme-b") ? "incus stop acme-b" : c)
            .toList());
    assertTrue(scripted.invocations().stream().noneMatch(c -> c.startsWith("incus file delete")));
    assertTrue(
        scripted.invocations().stream().noneMatch(c -> c.contains("user.sail.kept")),
        "the kept mark is left as it was");
  }

  /** A workspace a removal cannot strip is said, and stops none of the others. */
  @Test
  void aWorkspaceARemovalCannotReachIsSaidAndStopsNoneOfTheOthers() throws Exception {
    var shell =
        incus(
                DIR_AND_ZFS,
                list(
                    instance(
                        "acme-a",
                        "Running",
                        "tank",
                        "\"user.sail.project\": \"acme\", \"user.sail.workspace\": \"a\","
                            + " \"user.sail.owner\": \"ana\""),
                    instance(
                        "acme-b",
                        "Running",
                        "tank",
                        "\"user.sail.project\": \"acme\", \"user.sail.workspace\": \"b\","
                            + " \"user.sail.owner\": \"ana\"")),
                "[]")
            .onFail("incus exec acme-a", "/home/dev/.ssh/authorized_keys still has a key");

    var unreached =
        workspaces(shell, ticks()).disown((handle, id) -> !handle.equals("ana"), instance -> true);

    assertEquals(1, unreached.size(), unreached.toString());
    assertTrue(
        unreached.getFirst().startsWith("acme-a: ")
            && unreached.getFirst().endsWith("/home/dev/.ssh/authorized_keys still has a key"),
        unreached.getFirst());
    assertTrue(
        shell
            .invocations()
            .contains("incus config set acme-b user.sail.owner= user.sail.owner_id="));
  }

  /**
   * Scenario 1 (T1): a template build takes its builder's credentials out after the provisioner and
   * checks none is left, both before the stop, the record and the snapshot.
   */
  @Test
  void aBuildTakesItsBuildersCredentialsOutAndChecksNoneIsLeftBeforeItIsSnapshotted()
      throws Exception {
    var shell = incus(DIR_AND_ZFS, list(template("v1", "an-older-definition")), snapshots("v1"));

    templates(shell).ensure(acme(), source());

    var held =
        List.of(
            "incus",
            "exec",
            "acme--tmpl",
            "--",
            "bash",
            "-c",
            WorkspaceCredentials.HELD,
            "sail-held");
    var stripped = indexOf(shell, strip("acme--tmpl"));
    var checked = indexOf(shell, held);
    var stop = indexOf(shell, List.of("incus", "stop", "acme--tmpl"));
    var snapshot = indexOf(shell, List.of("incus", "snapshot", "create", "acme--tmpl", "v2"));
    assertTrue(
        0 <= stripped && stripped < checked && checked < stop && stop < snapshot,
        String.join("\n", shell.invocations()));
  }

  /** The check is given the build's tokens on its stdin, and none ever on a command line. */
  @Test
  void theCheckIsGivenTheBuildsTokensOnStdinNeverOnACommandLine() throws Exception {
    var shell = incus(DIR_AND_ZFS, list(template("v1", "an-older-definition")), snapshots("v1"));
    var source =
        new WorkspaceTemplate.Source(
            "devpool",
            INPUTS,
            name -> false,
            (into, fresh) -> {},
            List.of("ghp_one", "", " ", "glpat-two"));

    templates(shell).ensure(acme(), source);

    assertEquals(List.of("ghp_one\nglpat-two\n"), shell.inputs());
    assertFalse(
        shell.invocations().stream().anyMatch(c -> c.contains("ghp_one") || c.contains("glpat")));
    assertFalse(source.toString().contains("ghp_one"), source.toString());
  }

  /**
   * A build whose check finds something names it, never its value, and its remedy fits whatever
   * wrote it: a step of the definition or a hand change to the template. One whose check cannot
   * tell what it holds (exit 2) is never taken for clean.
   */
  @Test
  void aFailedBuildNamesWhatItFoundAndOneThatCannotTellIsNeverClean() {
    var found =
        incus(DIR_AND_ZFS, list(template("v1", "an-older-definition")), snapshots("v1"))
            .on(
                "sail-held",
                new ShellExec.Result(
                    1, "a token this build was given in /home/dev/.netrc, a gh login\n", ""));
    var unsure =
        incus(DIR_AND_ZFS, list(template("v1", "an-older-definition")), snapshots("v1"))
            .on(
                "sail-held",
                new ShellExec.Result(2, "", "the search for the build's tokens failed"));

    var failure = assertThrows(IOException.class, () -> templates(found).ensure(acme(), source()));
    var cannotTell =
        assertThrows(IOException.class, () -> templates(unsure).ensure(acme(), source()));

    assertEquals(
        "acme--tmpl was not snapshotted: it still holds a token this build was given in"
            + " /home/dev/.netrc, a gh login. Remove what wrote it, a step of acme's definition or a"
            + " change made by hand in acme--tmpl, then build again: sail project template build"
            + " acme.",
        failure.getMessage());
    assertEquals(
        "acme--tmpl was not snapshotted, since Sail could not tell what it holds: the search for"
            + " the build's tokens failed",
        cannotTell.getMessage());
    assertFalse(
        unsure.invocations().stream().anyMatch(c -> c.contains("snapshot create")),
        "nothing is snapshotted");
  }

  /**
   * Scenario 12: a template that still holds a credential after its removal step, or whose removal
   * step fails, fails its build: no version is recorded or snapshotted, the one before stays
   * current, and the template it started is stopped.
   */
  @Test
  void aTemplateStillHoldingACredentialFailsItsBuildAndTheVersionBeforeStaysCurrent() {
    for (var failing : List.of("sail-held", "sail-strip")) {
      var started =
          incus(DIR_AND_ZFS, list(template("v1", "an-older-definition")), snapshots("v1"));
      var shell =
          failing.equals("sail-held")
              ? started.on(
                  failing,
                  new ShellExec.Result(1, "it still holds .git-credentials user.email\n", ""))
              : started.onFail(failing, "it still holds .git-credentials user.email");

      var failure =
          assertThrows(IOException.class, () -> templates(shell).ensure(acme(), source()));

      assertTrue(
          failure.getMessage().contains("it still holds .git-credentials user.email"),
          failure.getMessage());
      assertFalse(
          shell.invocations().stream()
              .anyMatch(
                  c -> c.contains("user.sail.template.current=") || c.contains("snapshot create")),
          failing);
      assertTrue(shell.invocations().contains("incus stop acme--tmpl"), failing);
    }
  }

  /**
   * Review 1's final F-M1: a removal pass that listed a workspace while it was being made, and then
   * waited for its lock, reads it again under the lock and leaves it to its owner, whose id the
   * make wrote before letting go; nothing is stripped, nothing relabelled.
   */
  @Test
  void aRemovalPassThatListedAWorkspaceBeingMadeLeavesItToItsOwnerOnceTheMakeLetsGo()
      throws Exception {
    var idWritten = new AtomicBoolean(false);
    var listedBeforeTheId = new CountDownLatch(1);
    var commands = new CopyOnWriteArrayList<String>();
    ShellExec incus =
        new ShellExec() {
          @Override
          public Result exec(List<String> command) {
            return exec(command, null, Duration.ZERO);
          }

          @Override
          public Result exec(List<String> command, Path workDir, Duration timeout) {
            var joined = String.join(" ", command);
            commands.add(joined);
            if (joined.startsWith("incus list")) {
              var listed = being(idWritten.get());
              if (joined.equals("incus list --format json")) {
                listedBeforeTheId.countDown();
              }
              return new Result(0, listed, "");
            }
            return new Result(0, "", "");
          }

          @Override
          public boolean isDryRun() {
            return false;
          }
        };
    var workspaces = new Workspaces(incus, incus, ticks(), locks(), () -> 65536, url -> false);
    var making = new CountDownLatch(1);
    var letGo = new CountDownLatch(1);
    var make =
        Thread.ofVirtual()
            .start(
                () -> {
                  try (var lock = workspaces.holdIfFree("acme-try").orElseThrow()) {
                    making.countDown();
                    letGo.await();
                    idWritten.set(true);
                  } catch (Exception e) {
                    throw new IllegalStateException(e);
                  }
                });
    assertTrue(making.await(30, TimeUnit.SECONDS));
    var unreached = new AtomicReference<List<String>>();
    var pass =
        Thread.ofVirtual()
            .start(
                () -> {
                  try {
                    unreached.set(
                        workspaces.disown((handle, id) -> "fde_ada".equals(id), name -> true));
                  } catch (Exception e) {
                    throw new IllegalStateException(e);
                  }
                });
    assertTrue(listedBeforeTheId.await(30, TimeUnit.SECONDS), "the pass listed it mid-make");
    letGo.countDown();
    make.join();
    pass.join();

    assertEquals(List.of(), unreached.get());
    assertEquals(
        List.of(),
        commands.stream()
            .filter(c -> c.contains("sail-strip") || c.startsWith("incus config set"))
            .toList());
  }

  /**
   * Workspace {@code try} of acme being made for ada: its id in the label once {@code idWritten}.
   */
  private static String being(boolean idWritten) {
    return list(
        instance(
            "acme-try",
            "Running",
            "tank",
            "\"user.sail.project\": \"acme\", \"user.sail.workspace\": \"try\","
                + " \"user.sail.creating\": \"1\", \"user.sail.owner\": \"ada\""
                + (idWritten ? ", \"user.sail.owner_id\": \"fde_ada\"" : "")));
  }

  /**
   * A token change listed a workspace as its owner's, and found it someone else's once it held the
   * workspace's lock: it renews nothing there.
   */
  @Test
  void aTokenChangeRenewsNothingInAWorkspaceThatWasReOwnedBeforeItHeldTheLock() throws Exception {
    var scripted =
        new ScriptedShellExecutor(new ShellExec.Result(0, "", ""))
            .onOk("incus list ^acme-a$", list(owned("acme-a", "Running", "bob")))
            .onOk("incus list --format json", list(owned("acme-a", "Running", "ana")));
    var shell = new Pushing(scripted);

    var unreached =
        new Workspaces(shell, shell, ticks(), locks(), () -> 65536, url -> false)
            .refresh(TestOwners.of("ana"), project -> acme());

    assertEquals(List.of(), unreached);
    assertEquals(Map.of(), shell.pushed);
    assertTrue(scripted.invocations().stream().noneMatch(c -> c.startsWith("incus exec acme-a")));
  }

  private static final String ACME_PARTS =
      """
      [{"name": "acme", "status": "Running"},
       {"name": "acme--tmpl", "status": "Stopped", "config": {"user.sail.template.of": "acme"}},
       {"name": "acme-fix", "status": "Stopped",
        "config": {"user.sail.project": "acme", "user.sail.workspace": "fix",
                   "user.sail.ready": "1"}}]
      """;

  private static final String ACME_PARTS_AND_NEW =
      ACME_PARTS
          .strip()
          .replaceFirst(
              "]$",
              """
          ,{"name": "acme-new", "status": "Running",
            "config": {"user.sail.project": "acme", "user.sail.workspace": "new",
                       "user.sail.ready": "1"}}]""");

  /** A destroy deletes the project's workspaces and template as it reads them under its locks. */
  @Test
  void aDestroyDeletesTheWorkspacesAndTemplateItReadsUnderItsLocks() throws Exception {
    var shell = new ScriptedShellExecutor().onOk("incus list --format json", ACME_PARTS);
    var deleted = new ArrayList<List<String>>();

    workspaces(shell, System::nanoTime)
        .whileDestroying("acme", List.of("acme-fix", "acme--tmpl"), deleted::add);

    assertEquals(List.of(List.of("acme-fix", "acme--tmpl")), deleted);
  }

  /**
   * A workspace a create in flight made by the time the destroy holds the box's create lock is read
   * again there, and refuses the destroy, naming it: nothing is deleted, so no workspace is left of
   * a project that is gone, and nothing is deleted that the destroy did not show.
   */
  @Test
  void aDestroyReadsAgainUnderTheCreateLockAndRefusesNamingAWorkspaceMadeMeanwhile()
      throws Exception {
    var shell =
        new ScriptedShellExecutor()
            .onceOnOk("incus list --format", ACME_PARTS)
            .onOk("incus list --format json", ACME_PARTS_AND_NEW);
    var deleted = new ArrayList<List<String>>();

    var refused =
        assertThrows(
            IllegalStateException.class,
            () ->
                workspaces(shell, System::nanoTime)
                    .whileDestroying("acme", List.of("acme-fix", "acme--tmpl"), deleted::add));

    assertTrue(
        refused
            .getMessage()
            .contains(
                "Project acme is not destroyed: acme-new was made after the destroy read what it"
                    + " would delete."),
        refused.getMessage());
    assertTrue(refused.getMessage().contains("sail project destroy acme"), refused.getMessage());
    assertEquals(List.of(), deleted);
  }

  /**
   * A create holding the box's create lock past {@link Workspaces#CREATE_WAIT} refuses the destroy,
   * saying what holds it and how to try again; nothing is deleted.
   */
  @Test
  void aDestroyThatACreateOutlastsIsRefusedSayingWhatHoldsItAndNothingGoes() throws Exception {
    var shell = new ScriptedShellExecutor().onOk("incus list --format json", ACME_PARTS);
    var deleted = new ArrayList<List<String>>();
    var refused = new AtomicReference<Exception>();

    HeldLocks.whileHeld(
        locks().resolve(Workspaces.CREATE_LOCK),
        () -> {
          refused.set(
              assertThrows(
                  IllegalStateException.class,
                  () ->
                      workspaces(shell, HeldLocks.pastEveryWait())
                          .whileDestroying(
                              "acme", List.of("acme-fix", "acme--tmpl"), deleted::add)));
          return null;
        });

    assertTrue(
        refused
            .get()
            .getMessage()
            .contains(
                "Project acme is not destroyed: This runner is making another workspace, which"
                    + " takes about a minute."),
        refused.get().getMessage());
    assertTrue(
        refused.get().getMessage().contains("sail project destroy acme"),
        refused.get().getMessage());
    assertEquals(List.of(), deleted);
  }

  /** A workspace another change holds past the wait refuses the destroy the same way. */
  @Test
  void aDestroyOfAWorkspaceAnotherChangeHoldsIsRefusedAndNothingGoes() throws Exception {
    var shell = new ScriptedShellExecutor().onOk("incus list --format json", ACME_PARTS);
    var deleted = new ArrayList<List<String>>();
    var refused = new AtomicReference<Exception>();

    HeldLocks.whileHeld(
        locks().resolve("acme-fix.workspace.lock"),
        () -> {
          refused.set(
              assertThrows(
                  IllegalStateException.class,
                  () ->
                      workspaces(shell, HeldLocks.pastEveryWait())
                          .whileDestroying(
                              "acme", List.of("acme-fix", "acme--tmpl"), deleted::add)));
          return null;
        });

    assertTrue(
        refused
            .get()
            .getMessage()
            .contains("Project acme is not destroyed: Workspace fix of acme is being stopped"),
        refused.get().getMessage());
    assertEquals(List.of(), deleted);
  }
}
