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
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.LongSupplier;
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

  private static final Instant NOW = Instant.parse("2026-11-12T10:00:00Z");
  private static final String INPUTS = "a1b2c3";
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

  private Workspaces.Source source(String basePool, Set<String> projects) {
    return new Workspaces.Source(
        basePool,
        INPUTS,
        projects::contains,
        (into, fresh) -> {
          builtInto.add(into);
          builtFresh.add(fresh);
        });
  }

  private Workspaces.Source source() {
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
        "\"user.sail.project\": \"%s\", \"user.sail.workspace\": \"%s\", \"user.sail.template\": \"%s\", \"user.sail.created_ms\": \"1762941600000\""
            .formatted(project, name, template));
  }

  private static String template(String current, String inputs) {
    return instance(
        "acme--tmpl",
        "Stopped",
        "tank",
        "\"user.sail.template.current\": \"%s\", \"user.sail.template.inputs\": \"%s\""
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

  private static Workspaces workspaces(ShellExec shell, LongSupplier nanos) {
    return new Workspaces(shell, shell, nanos);
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
        "user.sail.created_ms=" + NOW.toEpochMilli());
  }

  private static int indexOf(ScriptedShellExecutor shell, List<String> argv) {
    return shell.arguments().indexOf(argv);
  }

  @Test
  void aFirstCreateBuildsTheTemplateAsV1OnTheZfsPoolThenClonesItsSnapshotAndSaysWhatItTook()
      throws Exception {
    var shell = incus(DIR_AND_ZFS, list(instance("acme", "Running", "devpool", "")), "[]");

    var created =
        workspaces(shell, ticks(0, ms(210), ms(1110), ms(1410)))
            .create(acme(), "fix-login", source(), tempDir.resolve("no-files"));

    assertEquals(
        List.of(new ProjectProvisioner.Instance("acme--tmpl", Optional.of("tank"))), builtInto);
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
                "user.sail.template.inputs=" + INPUTS));
    var copy = indexOf(shell, copyArgv("v1"));
    var start = indexOf(shell, List.of("incus", "start", "acme-fix-login"));
    var prepare = indexOf(shell, prepareArgv());
    assertTrue(
        0 <= stop
            && stop < snapshot
            && snapshot < record
            && record < copy
            && copy < start
            && start < prepare,
        String.join("\n", shell.invocations()));
    assertEquals(
        new Workspaces.Created(
            "acme",
            "fix-login",
            "acme-fix-login",
            new Workspaces.Template("acme", "acme--tmpl", "v1", INPUTS, "tank", true),
            new Workspaces.Timings(210L, 900, 300, 1410)),
        created);
  }

  @Test
  void aCurrentTemplateIsClonedWithoutABuild() throws Exception {
    var shell =
        incus(
            DIR_AND_ZFS,
            list(instance("acme", "Running", "devpool", ""), template("v1", INPUTS)),
            snapshots("v1"));

    var created =
        workspaces(shell, ticks(0, ms(150), ms(500), ms(700)))
            .create(acme(), "fix-login", source(), tempDir);

    assertEquals(List.of(), builtInto);
    assertEquals(
        new Workspaces.Template("acme", "acme--tmpl", "v1", INPUTS, "tank", false),
        created.template());
    assertTrue(indexOf(shell, copyArgv("v1")) >= 0, String.join("\n", shell.invocations()));
    assertFalse(
        shell.invocations().stream()
            .anyMatch(
                c -> c.startsWith("incus snapshot create") || c.contains("template.current=")),
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
        workspaces(shell, ticks(0, 1, 2, 3)).create(acme(), "fix-login", source(), tempDir);

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

    var template = workspaces(shell, ticks()).template(acme(), source());

    assertEquals("v4", template.version());
  }

  @Test
  void aTemplateBuiltFromTheDefinitionAsItIsIsLeftAsItIs() throws Exception {
    var shell = incus(DIR_AND_ZFS, list(template("v2", INPUTS)), snapshots("v1", "v2"));

    var template = workspaces(shell, ticks()).template(acme(), source());

    assertEquals(
        new Workspaces.Template("acme", "acme--tmpl", "v2", INPUTS, "tank", false), template);
    assertEquals(List.of(), builtInto);
  }

  @Test
  void aCurrentVersionWhoseSnapshotIsGoneIsBuiltAgain() throws Exception {
    var shell = incus(DIR_AND_ZFS, list(template("v2", INPUTS)), snapshots("v1"));

    var template = workspaces(shell, ticks()).template(acme(), source());

    assertTrue(template.built());
    assertEquals("v2", template.version());
  }

  @Test
  void aFailedBuildLeavesThePreviousVersionCurrentAndMakesNoSnapshot() {
    var shell = incus(DIR_AND_ZFS, list(template("v1", "an-older-definition")), snapshots("v1"));
    var failing =
        new Workspaces.Source(
            "devpool",
            INPUTS,
            name -> false,
            (into, fresh) -> {
              throw new IOException("apt-get failed");
            });

    var failure =
        assertThrows(IOException.class, () -> workspaces(shell, ticks()).template(acme(), failing));

    assertEquals("apt-get failed", failure.getMessage());
    assertFalse(
        shell.invocations().stream()
            .anyMatch(
                c ->
                    c.startsWith("incus snapshot create")
                        || c.startsWith("incus config set acme--tmpl")
                        || c.startsWith("incus stop")),
        String.join("\n", shell.invocations()));
  }

  @Test
  void aVersionWhoseRecordCannotBeWrittenIsRemovedSoThePreviousStaysCurrent() {
    var shell =
        incus(DIR_AND_ZFS, list(template("v1", "an-older-definition")), snapshots("v1"))
            .onFail("incus config set acme--tmpl user.sail.template.current=v2", "disk full");

    var failure =
        assertThrows(
            IOException.class, () -> workspaces(shell, ticks()).template(acme(), source()));

    assertEquals(
        "Failed to record v2 as acme's current template, so it was removed and the version"
            + " before stays current: disk full",
        failure.getMessage());
    assertTrue(
        indexOf(shell, List.of("incus", "snapshot", "delete", "acme--tmpl", "v2")) >= 0,
        String.join("\n", shell.invocations()));
  }

  @Test
  void theBaseProfilesPoolIsTakenWhenItIsZfs() throws Exception {
    var pools =
        """
        [{"name": "fast", "driver": "zfs", "config": {}}, {"name": "tank", "driver": "zfs", "config": {}}]
        """;
    var shell = incus(pools, "[]", "[]");

    var template = workspaces(shell, ticks()).template(acme(), source("tank", Set.of("acme")));

    assertEquals("tank", template.pool());
    assertEquals(
        List.of(new ProjectProvisioner.Instance("acme--tmpl", Optional.of("tank"))), builtInto);
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
            () -> workspaces(shell, ticks()).create(acme(), "fix-login", source(), tempDir));

    assertEquals(
        "Incus has the ZFS pools fast, tank and the base profile's pool 'devpool' is none of them,"
            + " so Sail cannot tell which one workspaces go on. Keep one ZFS pool beside the base"
            + " profile's, or put the base profile on the one to use.",
        failure.getMessage());
    assertEquals(List.of(), builtInto);
  }

  @Test
  void everyVerbOnABoxWithNoZfsPoolIsRefusedBeforeAnythingChanges() {
    var shell = incus(DIR_ONLY, list(workspace("acme", "fix-login", "Running", "v1")), "[]");
    var workspaces = workspaces(shell, ticks());
    var expected =
        "This box has no ZFS pool, and a workspace is a ZFS clone of its project's template. Add"
            + " one beside the box's pool with: sail host init --storage zfs --pool tank --disk"
            + " <device>, or with --pool-file-size <size> in place of --disk to try ZFS in a file.";

    List<Executable> verbs =
        List.of(
            () -> workspaces.create(acme(), "fix-login", source(), tempDir),
            () -> workspaces.template(acme(), source()),
            () -> workspaces.list("acme"),
            () -> workspaces.start("acme", "fix-login"),
            () -> workspaces.stop("acme", "fix-login"),
            () -> workspaces.delete("acme", "fix-login"),
            () -> workspaces.running("acme", "fix-login"));
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
                    "main", "acme", "running", null, "2026-11-12T09:00:00.123Z", ip("acme")),
                new Workspaces.Row(
                    "fix-login", "acme-fix-login", "running", "v1", created, ip("acme-fix-login")),
                new Workspaces.Row("paused", "acme-paused", "frozen", "v1", created, null),
                new Workspaces.Row("second", "acme-second", "stopped", "v2", created, null))),
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
        List.of(new Workspaces.Row("main", "acme", "not_created", null, null, null)),
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
            new Workspaces.Timings(null, 350, 450, 800)),
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

    assertEquals(Workspaces.Change.STOPPED, workspaces.stop("acme", "fix-login").change());
    assertEquals(Workspaces.Change.ALREADY_STOPPED, workspaces.stop("acme", "second").change());
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

    var absent = assertThrows(IllegalStateException.class, () -> workspaces.delete("a", "b-c"));
    var deleted = workspaces.delete("a-b", "c");

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
  void theDefaultWorkspaceIsRefusedByEveryVerbThatWouldTouchItNamingTheProjectVerb() {
    var shell = incus(DIR_AND_ZFS, "[]", "[]");
    var workspaces = workspaces(shell, ticks());

    var delete =
        assertThrows(IllegalArgumentException.class, () -> workspaces.delete("acme", "main"));
    var stop = assertThrows(IllegalArgumentException.class, () -> workspaces.stop("acme", "main"));
    var start =
        assertThrows(IllegalArgumentException.class, () -> workspaces.start("acme", "main"));
    var shellIn =
        assertThrows(IllegalArgumentException.class, () -> workspaces.running("acme", "main"));
    var create =
        assertThrows(
            IllegalArgumentException.class,
            () -> workspaces.create(acme(), "main", source(), tempDir));

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
            () -> workspaces(shell, ticks()).create(config, "abcd", source(), tempDir));

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
            () -> workspaces(shell, ticks()).create(config, "a", source(), tempDir));

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
            () -> workspaces.create(acme(), "taken", held, tempDir));
    var project =
        assertThrows(
            IllegalArgumentException.class,
            () -> workspaces.create(acme(), "billing", held, tempDir));
    var existing =
        assertThrows(
            IllegalStateException.class,
            () -> workspaces.create(acme(), "fix-login", held, tempDir));
    var invalid =
        assertThrows(
            IllegalArgumentException.class,
            () -> workspaces.create(acme(), "Fix_Login", held, tempDir));

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
            () -> workspaces(shell, ticks()).create(config, "tmpl", source(), tempDir));

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
            () ->
                workspaces(shell, ticks())
                    .template(acme(), source("devpool", Set.of("acme", "acme--tmpl"))));

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
                    "\"user.sail.template.current\": \"v1\", \"user.sail.template.inputs\": \""
                        + INPUTS
                        + "\"")),
            snapshots("v1"));

    var failure =
        assertThrows(
            IllegalStateException.class,
            () -> workspaces(shell, ticks()).create(acme(), "fix-login", source(), tempDir));

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
            () -> workspaces(shell, ticks(0, 1, 2)).create(acme(), "fix-login", source(), tempDir));

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
            () -> workspaces(shell, ticks(0)).create(acme(), "fix-login", source(), tempDir));

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
            "\"user.sail.template.current\": \"v1\", \"user.sail.template.inputs\": \"older\"");
    var shell = incus(DIR_AND_ZFS, list(running), snapshots("v1"));

    var template = workspaces(shell, ticks()).template(acme(), source());

    assertEquals("v2", template.version());
    assertFalse(shell.invocations().contains("incus start acme--tmpl"));
  }

  @Test
  void aDryRunNarratesTheBuildAndTakesNoLock() throws Exception {
    var observer = incus(DIR_AND_ZFS, "[]", "[]");

    var template =
        new Workspaces(observer, new ShellExecutor(true), ticks()).template(acme(), source());

    assertEquals(
        new Workspaces.Template("acme", "acme--tmpl", "v1", INPUTS, "tank", true), template);
    assertEquals(List.of(true), builtFresh);
    assertFalse(
        observer.invocations().stream().anyMatch(c -> c.startsWith("incus snapshot create")));
  }

  @Test
  void aVersionThatCanBeNeitherRecordedNorRemovedSaysBoth() {
    var shell =
        incus(DIR_AND_ZFS, list(template("v1", "older")), snapshots("v1"))
            .onFail("incus config set acme--tmpl user.sail.template.current=v2", "disk full")
            .onFail("incus snapshot delete acme--tmpl v2", "busy");

    var failure =
        assertThrows(
            IOException.class, () -> workspaces(shell, ticks()).template(acme(), source()));

    assertEquals(
        "Failed to delete snapshot 'v2' for 'acme--tmpl': busy",
        failure.getSuppressed()[0].getMessage());
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
            () -> workspaces(shell, ticks(0, 1, 2)).create(acme(), "fix-login", source(), tempDir));

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
                        + " \"user.sail.created_ms\": \"soon\""),
                instance("acme-half", "Running", "tank", "\"user.sail.project\": \"acme\"")),
            "[]");
    var workspaces = workspaces(shell, ticks());

    assertEquals(
        List.of(
            new Workspaces.Row("main", "acme", "not_created", null, null, null),
            new Workspaces.Row("odd", "acme-odd", "error", null, null, null)),
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
            () -> workspaces(shell, ticks()).create(acme(), "fix", source(), tempDir));

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

    workspaces(fresh, ticks(0, 1, 2, 3)).create(acme(), "fix-login", source(), tempDir);
    workspaces(fresh, ticks()).list("acme");
    workspaces(stale, ticks(0, 1, 2, 3)).create(acme(), "fix-login", source(), tempDir);
    workspaces(stopped, ticks(0, 1, 2)).start("acme", "w");
    workspaces(live, ticks()).running("acme", "w");
    workspaces(live, ticks()).stop("acme", "w");
    workspaces(live, ticks()).delete("acme", "w");

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
        rm -f /etc/machine-id
        systemd-machine-id-setup >/dev/null
        if [ -e /var/lib/dbus/machine-id ]; then ln -sf /etc/machine-id /var/lib/dbus/machine-id; fi
        rm -f /etc/ssh/ssh_host_*
        ssh-keygen -A >/dev/null
        systemctl try-restart ssh.service
        printf '%s\\n' "$project" > /etc/hostname
        hostname "$project"
        sed -i "s/^\\(127\\.0\\.1\\.1[[:space:]]*\\).*/\\1$project/" /etc/hosts
        as_dev() { runuser -u dev -- env HOME=/home/dev "$@"; }
        if [ "$#" -gt 0 ]; then
          for _ in $(seq 1 300); do
            ip -4 -o addr show dev eth0 | grep -q inet && break
            sleep 0.1
          done
        fi
        while [ "$#" -ge 2 ]; do
          repo=/home/dev/workspace/$1 base=$2
          shift 2
          as_dev git -C "$repo" fetch --quiet origin
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
        for _ in $(seq 1 300); do [ -S /run/user/1000/bus ] && exit 0; sleep 0.1; done
        echo "the dev user's session bus did not come up within 30 s" >&2
        exit 1
        """,
        Workspaces.PREPARE);
  }

  @Test
  @EnabledOnOs(OS.LINUX)
  void theScriptThatMakesACloneItsOwnIsValidBash() throws Exception {
    var script = tempDir.resolve("prepare.sh");
    Files.writeString(script, Workspaces.PREPARE, StandardCharsets.UTF_8);

    var checked = new ProcessBuilder("bash", "-n", script.toString()).start();

    assertSame(0, checked.waitFor());
  }
}
