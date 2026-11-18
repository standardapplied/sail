/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.sail.common.DateTimeUtils;
import ai.singlr.sail.config.Lane;
import ai.singlr.sail.config.ProjectDefinition;
import ai.singlr.sail.config.TestDefinitions;
import ai.singlr.sail.config.YamlUtil;
import ai.singlr.sail.engine.ContainerConfig;
import ai.singlr.sail.engine.DeviceFixtures;
import ai.singlr.sail.engine.HostDetector;
import ai.singlr.sail.engine.RunPlace;
import ai.singlr.sail.engine.ScriptedShellExecutor;
import ai.singlr.sail.engine.ShellExec;
import ai.singlr.sail.engine.TestOwners;
import ai.singlr.sail.engine.WorkspaceOwner;
import ai.singlr.sail.engine.Workspaces;
import ai.singlr.sail.identity.Acting;
import ai.singlr.sail.store.RunStore;
import ai.singlr.sail.store.SchemaManager;
import ai.singlr.sail.store.Sqlite;
import ai.singlr.sail.store.TestRuns;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Where a run goes, decided around its reservation, and what each way it can fail answers: a
 * project name too long to name a spec's workspace, an Incus that cannot be read or is interrupted,
 * a create that fails, a box whose memory cannot be read, and the remedy each lane is told.
 */
class RunPlacesTest {

  private static final String YAML =
      """
      name: acme
      resources: {cpu: 2, memory: 4GB, disk: 50GB}
      repos:
        - url: https://github.com/acme/app.git
          path: app
      agent:
        type: claude-code
      """;

  @TempDir Path dir;

  private ProjectReader definitions() throws IOException {
    var yaml = dir.resolve("sail.yaml");
    Files.writeString(yaml, YAML);
    return TestProjects.reading(yaml);
  }

  private ProjectDefinition config() throws IOException {
    return TestDefinitions.map(YamlUtil.parseMap(YAML));
  }

  private RunPlaces places(ShellExec incus, ShellExec shell) throws IOException {
    return places(TestWorkspaces.over(incus), shell, runs("r.db"), RunLauncher.Room.NONE);
  }

  private RunPlaces places(
      Workspaces workspaces, ShellExec shell, RunStore runs, RunLauncher.Room room)
      throws IOException {
    return new RunPlaces(
        new ProjectLoader(shell, definitions()),
        workspaces,
        runs::liveSpecs,
        room,
        place -> {},
        TestOwners::of);
  }

  private RunStore runs(String file) {
    var db = Sqlite.open(dir.resolve(file));
    new SchemaManager(db).migrate();
    return new RunStore(db);
  }

  private static void liveBuild(RunStore runs, String specId, String boxHandle) {
    Acting.system(
        () ->
            runs.reserveDispatch(
                DateTimeUtils.newId().toString(),
                "acme",
                specId,
                boxHandle,
                "build",
                List.of("app"),
                "claude-code",
                null,
                "t",
                "l",
                "u"));
  }

  /** A shell on which every command holding {@code fragment} is interrupted, and others answer. */
  private static ShellExec interrupting(ShellExec delegate, String fragment) {
    return new ShellExec() {
      @Override
      public Result exec(List<String> command) throws IOException, InterruptedException {
        return exec(command, null, null);
      }

      @Override
      public Result exec(List<String> command, Path workDir, Duration timeout)
          throws IOException, InterruptedException {
        if (String.join(" ", command).contains(fragment)) {
          throw new InterruptedException("interrupted");
        }
        try {
          return delegate.exec(command, workDir, timeout);
        } catch (TimeoutException e) {
          throw new IOException(e);
        }
      }

      @Override
      public boolean isDryRun() {
        return false;
      }
    };
  }

  @Test
  void aProjectNameTooLongToNameASpecsWorkspaceIsABadRequest() throws IOException {
    var project = "p".repeat(55);
    var places = places(TestWorkspaces.incus(definitions(), "acme"), new ScriptedShellExecutor());

    var refused =
        assertThrows(
            ApiException.class,
            () -> places.checkSpec(project, config(), "a-long-spec-id", Lane.BUILD, "eng"));

    assertEquals(ErrorCode.BAD_REQUEST, refused.failure().errorCode());
    assertTrue(refused.getMessage().startsWith("Project '" + project + "' is 55 characters"));
  }

  @Test
  void anIncusThatCannotBeReadOrIsInterruptedIsACommandFailure() throws Exception {
    var unreadable =
        new ScriptedShellExecutor().onThrow("incus storage list", new IOException("no daemon"));
    var places = places(unreadable, new ScriptedShellExecutor());

    var refused =
        assertThrows(
            ApiException.class,
            () -> places.checkSpec("acme", config(), "auth", Lane.BUILD, "eng"));
    var interrupted =
        assertThrows(
            ApiException.class,
            () ->
                places(interrupting(unreadable, "incus storage list"), new ScriptedShellExecutor())
                    .checkSpec("acme", config(), "auth", Lane.BUILD, "eng"));

    assertEquals(ErrorCode.COMMAND_FAILED, refused.failure().errorCode());
    assertEquals("Could not read this box's Incus: no daemon", refused.getMessage());
    assertEquals(ErrorCode.COMMAND_FAILED, interrupted.failure().errorCode());
    assertTrue(Thread.interrupted(), "the interrupt is kept for whoever waits on the thread");
  }

  @Test
  void aCreateThatFailsAnswersItsOwnReasonAndAnInterruptedOneKeepsTheInterrupt() throws Exception {
    var refusing = TestWorkspaces.incus(definitions(), "acme").onFail("incus copy", "disk full");
    var places = places(refusing, new ScriptedShellExecutor());
    var check = places.checkSpec("acme", config(), "auth", Lane.BUILD, "eng");

    var failed = assertThrows(ApiException.class, () -> places.enter(check, config()));
    var interrupted =
        assertThrows(
            ApiException.class,
            () ->
                places(
                        interrupting(TestWorkspaces.incus(definitions(), "acme"), "incus copy"),
                        new ScriptedShellExecutor())
                    .enter(check, config()));

    assertEquals(ErrorCode.COMMAND_FAILED, failed.failure().errorCode());
    assertEquals(
        "Workspace auth of acme could not be readied: Failed to clone acme--tmpl/v1 as"
            + " 'acme-auth': disk full",
        failed.getMessage());
    assertEquals(ErrorCode.COMMAND_FAILED, interrupted.failure().errorCode());
    assertTrue(Thread.interrupted());
  }

  /**
   * A spec's workspace that got no address within its 30 s is not made: the dispatch is refused
   * naming the wait, as any create that failed is, and the half-made workspace is deleted.
   */
  @Test
  void aWorkspaceThatGotNoAddressIsNotMadeAndTheDispatchSaysWhy() throws Exception {
    var noAddress =
        TestWorkspaces.incus(definitions(), "acme")
            .onFail(
                "sail-workspace acme auth",
                "acme-auth got no address on eth0 within 30 s, so its repos cannot be fetched");
    var places = places(noAddress, new ScriptedShellExecutor());
    var check = places.checkSpec("acme", config(), "auth", Lane.BUILD, "eng");

    var failed = assertThrows(ApiException.class, () -> places.enter(check, config()));

    assertEquals(ErrorCode.COMMAND_FAILED, failed.failure().errorCode());
    assertEquals(
        "Workspace auth of acme could not be readied: Workspace 'auth' could not be finished:"
            + " acme-auth got no address on eth0 within 30 s, so its repos cannot be fetched",
        failed.getMessage());
    assertEquals("Then dispatch again.", failed.failure().remedy().plain());
    assertTrue(
        noAddress.invocations().stream().anyMatch(c -> c.startsWith("incus delete acme-auth")));
  }

  /**
   * D8 reads the box's memory as its host says it, and a box whose memory cannot be read refuses.
   */
  @Test
  void aBoxsMemoryIsReadFromItsHostAndOneThatCannotBeReadIsACommandFailure() throws Exception {
    var meminfo = Files.writeString(dir.resolve("meminfo"), "MemTotal:       8388608 kB\n");
    var osRelease = Files.writeString(dir.resolve("os-release"), "ID=ubuntu\n");
    var hostname = Files.writeString(dir.resolve("hostname"), "box\n");
    var cpuinfo = Files.writeString(dir.resolve("cpuinfo"), "processor\t: 0\n");
    var runs = runs("memory.db");
    liveBuild(runs, "other", "eng");

    assertEquals(
        8192,
        Workspaces.memoryOf(new HostDetector(osRelease, meminfo, hostname, cpuinfo)).getAsLong());
    var unread =
        assertThrows(
            ApiException.class,
            () ->
                places(
                        TestWorkspaces.over(
                            TestWorkspaces.incus(definitions(), "acme"),
                            Workspaces.memoryOf(
                                new HostDetector(
                                    osRelease, dir.resolve("missing"), hostname, cpuinfo))),
                        new ScriptedShellExecutor(),
                        runs,
                        RunLauncher.Room.NONE)
                    .checkSpec("acme", config(), "auth", Lane.BUILD, "eng"));
    assertEquals(ErrorCode.COMMAND_FAILED, unread.failure().errorCode());
    assertTrue(
        unread.getMessage().startsWith("Could not read this box's memory: "), unread.getMessage());
  }

  /**
   * A refusal carries the one remedy that fits the lane it is said for, in the room and to its
   * caller alike: a person dispatches a build again, the loop retries its own lanes, and a room's
   * turn is never told to dispatch, which would launch a build.
   */
  @Test
  void eachLanesRefusalCarriesTheRemedyThatFitsItInTheRoomAndToItsCaller() throws Exception {
    var runs = runs("lanes.db");
    liveBuild(runs, "other", "eng");
    var told = new ArrayList<String>();
    var places =
        places(
            TestWorkspaces.over(TestWorkspaces.incus(definitions(), "acme"), () -> 4096),
            new ScriptedShellExecutor(),
            runs,
            (specId, body) -> told.add(body));

    var said = new ArrayList<String>();
    for (var lane : List.of(Lane.BUILD, Lane.REVIEW, Lane.FIX, Lane.ROOM, Lane.ROOM_FULL)) {
      var busy =
          assertThrows(
              ApiException.class, () -> places.checkSpec("acme", config(), "auth", lane, "eng"));
      assertEquals(ErrorCode.RUNNER_BUSY, busy.failure().errorCode());
      said.add(busy.failure().remedy().plain());
    }

    var full = "Wait for one of their runs to end. ";
    assertEquals(
        List.of(
            full + "Then dispatch again.",
            full
                + "The loop then tries again by itself, and hands the review to a person if it lasts.",
            full
                + "The loop then tries again by itself, and hands the review to a person if it lasts.",
            full + "Then engage or post in the room again.",
            full + "Then engage or post in the room again."),
        said);
    assertEquals(
        said.stream()
            .map(
                remedy ->
                    "This box has 1 spec workspace with a live run, as many as its memory holds at"
                        + " acme's 4GB each (a cap of 1), so no new workspace is created. "
                        + remedy)
            .toList(),
        told,
        "the room is told what the caller is");
  }

  @Test
  void aRunOfNoSpecGoesInTheProjectsRunningContainer() throws Exception {
    var running =
        new ScriptedShellExecutor()
            .onOk(
                "incus list ^acme$",
                "[{\"name\": \"acme\", \"status\": \"Running\", \"state\": {}}]");
    var places = places(new ScriptedShellExecutor(), running);

    var checked = places.check("acme", config(), null, Lane.ROOM, "eng");

    assertEquals(new RunPlaces.InContainer(new RunPlace.ProjectContainer("acme")), checked);
    assertNull(places.enter(checked, config()), "no workspace is entered");
  }

  @Test
  void anInstanceNameNoCatalogCanReadIsTakenForAProjectsAndLeftAlone() {
    var loader =
        new ProjectLoader(
            new ScriptedShellExecutor(),
            name -> {
              throw new ProjectReader.Unreadable(name, new IOException("corrupt"));
            });

    assertTrue(loader.held("acme-auth"));
  }

  @Test
  void aReusedWorkspaceOfAStaleTemplateIsEnteredAsItIsAndTheRoomIsToldNothing() throws Exception {
    var incus =
        new ScriptedShellExecutor(new ShellExec.Result(0, "", ""))
            .onOk("incus storage list --format json", TestWorkspaces.POOL)
            .onOk(
                "incus list --format json",
                "["
                    + TestWorkspaces.template("acme", "an-older-definition")
                    + ","
                    + TestWorkspaces.specWorkspace("acme", "auth", "Running", "eng")
                    + "]")
            .onOk("incus snapshot list acme--tmpl --format json", TestWorkspaces.V1);
    var told = new ArrayList<String>();
    var places =
        places(
            TestWorkspaces.over(incus),
            new ScriptedShellExecutor(),
            runs("reused.db"),
            (specId, body) -> told.add(body));
    var check = places.checkSpec("acme", config(), "auth", Lane.BUILD, "eng");

    var entered = places.enter(check, config());

    assertTrue(entered.reused());
    assertEquals(false, entered.stale());
    assertEquals(List.of(), told);
  }

  /**
   * Scenario 14: a degraded mirror is said in the spec's room and carried to the answer once, by
   * the dispatch, never again at a review, fix or room turn's entry.
   */
  @Test
  void aWorkspaceMadeOnADegradedMirrorSaysSoOnceByTheDispatch() throws Exception {
    var mirror =
        """
        [{"name": "tank", "driver": "zfs", "config": {"source": "tank", "zfs.pool_name": "tank",
          "volatile.initial_source": "mirror=/dev/disk/by-id/wwn-0x5000c500a1b2c3d4-part5,/dev/disk/by-id/wwn-0x5000c500e5f6a7b8-part5"}}]
        """;
    var incus =
        TestWorkspaces.incus(definitions(), "acme")
            .onOk("incus storage list --format json", mirror)
            .onOk(
                "zpool status -P tank",
                DeviceFixtures.read("handwritten-mirror-degraded-removed.zpool-status.txt")
                    .replace("devpool", "tank"));
    var told = new ArrayList<String>();
    var places =
        places(
            TestWorkspaces.over(incus),
            new ScriptedShellExecutor(),
            runs("degraded.db"),
            (specId, body) -> told.add(body));

    var entered =
        places.enter(places.checkSpec("acme", config(), "auth", Lane.BUILD, "eng"), config());
    for (var lane : List.of(Lane.REVIEW, Lane.FIX, Lane.ROOM)) {
      assertNull(
          places
              .enter(places.checkSpec("acme", config(), "auth", lane, "eng"), config())
              .degraded(),
          lane + " says nothing of it");
    }

    assertTrue(entered.degraded().startsWith("Pool tank is DEGRADED"), entered.degraded());
    assertEquals(List.of(entered.degraded()), told);
  }

  /**
   * The cap again under the create lock: a spec that found room before its run was reserved is busy
   * at its create when another spec's workspace, made meanwhile, holds the last of it. The runs
   * counted are this box's, whoever the run acts for.
   */
  @Test
  void aCreateThatFindsTheCapReachedUnderTheCreateLockIsBusyAndMakesNothing() throws Exception {
    var incus =
        TestWorkspaces.incus(definitions(), "acme")
            .onOk(
                "incus list --format json",
                "["
                    + TestWorkspaces.currentTemplate(definitions(), "acme")
                    + ","
                    + TestWorkspaces.specWorkspace("acme", "other", "Running", "eng")
                    + "]");
    var runs = runs("cap.db");
    var places =
        places(
            TestWorkspaces.over(incus, () -> 6144),
            new ScriptedShellExecutor(),
            runs,
            RunLauncher.Room.NONE);
    var check = places.checkSpec("acme", config(), "auth", Lane.BUILD, "eng");
    liveBuild(runs, "other", "eng");

    var busy = assertThrows(ApiException.class, () -> places.enter(check, config()));

    assertEquals(ErrorCode.RUNNER_BUSY, busy.failure().errorCode());
    assertTrue(busy.getMessage().startsWith("This box has 1 spec workspace with a live run"));
    assertTrue(incus.invocations().stream().noneMatch(c -> c.startsWith("incus copy")));
  }

  /** A check that waits past the create wait on a build of the template is busy, naming it. */
  @Test
  void aCheckThatMeetsABuildOfTheTemplatePastTheWaitIsRunnerBusy() throws Exception {
    var locks = Files.createDirectories(dir.resolve("locks"));
    var now = new AtomicLong();
    var places =
        places(
            TestWorkspaces.over(
                TestWorkspaces.incus(definitions(), "acme"),
                () -> now.addAndGet(Workspaces.CREATE_WAIT.toNanos()),
                locks),
            new ScriptedShellExecutor(),
            runs("built.db"),
            RunLauncher.Room.NONE);
    var refused = new AtomicReference<ApiException>();
    try (var build = ContainerConfig.hold(locks, "acme--tmpl")) {
      var checking =
          Thread.ofPlatform()
              .start(
                  () -> {
                    try {
                      places.checkSpec("acme", config(), "auth", Lane.BUILD, "eng");
                    } catch (ApiException e) {
                      refused.set(e);
                    } catch (IOException e) {
                      throw new UncheckedIOException(e);
                    }
                  });
      checking.join(Duration.ofSeconds(30));
    }

    assertEquals(ErrorCode.RUNNER_BUSY, refused.get().failure().errorCode());
    assertEquals(
        "acme's template is being built, and no workspace is created from it until it is. Try"
            + " again once it is built: a build takes a few minutes.",
        refused.get().getMessage());
    assertEquals("Then dispatch again.", refused.get().failure().remedy().plain());
  }

  @Test
  void aFirstEntryMakesTheWorkspaceFromATemplateAsTheDefinitionIsAndCallsNothingStale()
      throws Exception {
    var incus = TestWorkspaces.incus(definitions(), "acme");
    var pruned = new ArrayList<RunPlace>();
    var places =
        new RunPlaces(
            new ProjectLoader(new ScriptedShellExecutor(), definitions()),
            TestWorkspaces.over(incus),
            runs("made.db")::liveSpecs,
            RunLauncher.Room.NONE,
            pruned::add,
            TestOwners::of);
    var check = places.checkSpec("acme", config(), "auth", Lane.BUILD, "eng");
    assertEquals(List.of(), pruned, "no run directory is pruned before the workspace is made");

    var entered = places.enter(check, config());

    assertEquals(false, entered.reused());
    assertEquals(false, entered.stale());
    assertEquals(List.of(check.place()), pruned, "the run directories of the made workspace are");
  }

  /**
   * Rule G at the seam every lane resumes through: a run of no spec always holds its session; a
   * spec's run holds one only while its workspace, made before the run ended, is there; and when
   * Incus cannot say, or the spec cannot have a workspace, none does, so the next run starts fresh
   * rather than resuming a session that may be gone (Minor 3 of the second look).
   */
  @Test
  void aRunHoldsItsSessionOnlyWhileTheWorkspaceItRanInIsThere() throws Exception {
    var run = new AtomicReference<RunStore.RunRow>();
    var runs = runs("held.db");
    Acting.system(
        () -> {
          var id = DateTimeUtils.newId().toString();
          runs.create(
              id,
              "acme",
              "auth",
              "eng",
              "build",
              "claude-code",
              null,
              "t",
              1,
              null,
              null,
              "sail-agent-" + id);
          TestRuns.complete(runs, id, "completed", null);
          run.set(runs.findById(id).orElseThrow());
        });
    var made = DateTimeUtils.toMillis(run.get().completedAt());
    var listed =
        TestWorkspaces.specWorkspace("acme", "auth", "Running", "eng")
            .replace(
                "\"user.sail.ready\"", "\"user.sail.created_ms\": \"%s\", \"user.sail.ready\"");
    var before = places(listing(listed.formatted(made - 1)));
    var after = places(listing(listed.formatted(made + 1)));
    var gone = places(listing(""));
    var silent =
        places(new ScriptedShellExecutor().onThrow("incus list ^", new IOException("incus: down")));
    var interrupted =
        places(
            new ShellExec() {
              @Override
              public Result exec(List<String> command) throws InterruptedException {
                throw new InterruptedException("shutting down");
              }

              @Override
              public Result exec(List<String> command, Path workDir, Duration timeout)
                  throws InterruptedException {
                throw new InterruptedException("shutting down");
              }

              @Override
              public boolean isDryRun() {
                return false;
              }
            });

    assertTrue(before.holdsSession("acme", "auth").test(run.get()));
    assertEquals(false, after.holdsSession("acme", "auth").test(run.get()));
    assertEquals(false, gone.holdsSession("acme", "auth").test(run.get()));
    assertTrue(gone.holdsSession("acme", null).test(run.get()), "a run of no spec");
    assertEquals(false, silent.holdsSession("acme", "auth").test(run.get()));
    assertEquals(
        false,
        gone.holdsSession("a".repeat(60), "auth").test(run.get()),
        "no workspace can be named");
    assertEquals(false, interrupted.holdsSession("acme", "auth").test(run.get()));
    assertTrue(Thread.interrupted(), "the interrupt is kept");
  }

  private static ScriptedShellExecutor listing(String instance) {
    return new ScriptedShellExecutor()
        .onOk("incus list ^acme-auth$", instance.isEmpty() ? "[]" : "[" + instance + "]");
  }

  private RunPlaces places(ShellExec incus) throws IOException {
    return new RunPlaces(
        new ProjectLoader(new ScriptedShellExecutor(), definitions()),
        TestWorkspaces.over(incus),
        runs("places-" + System.nanoTime() + ".db")::liveSpecs,
        RunLauncher.Room.NONE,
        place -> {},
        TestOwners::of);
  }

  private RunPlaces owning(
      ShellExec incus, Function<String, WorkspaceOwner> owners, List<String> told)
      throws IOException {
    return new RunPlaces(
        new ProjectLoader(new ScriptedShellExecutor(), definitions()),
        new Workspaces(
            incus, incus, System::nanoTime, dir.resolve("locks"), () -> 65536, url -> true),
        runs("owning.db")::liveSpecs,
        (specId, body) -> told.add(body),
        place -> {},
        owners);
  }

  private static WorkspaceOwner withoutEmail(String handle) {
    return new WorkspaceOwner(handle, handle, "", Map.of(), List.of(), "fde_" + handle);
  }

  /**
   * Scenario 4: a run whose workspace would be made for a person who keeps no token for its private
   * repo's host is refused before anything is reserved, in the spec's room too, naming the login
   * that stores one, and nothing is made.
   */
  @Test
  void aWorkspaceItsOwnerCannotFetchIsRefusedBeforeItsReservationNamingTheLogin() throws Exception {
    var incus = TestWorkspaces.incus(definitions(), "acme");
    var told = new ArrayList<String>();

    var refused =
        assertThrows(
            ApiException.class,
            () ->
                owning(incus, TestOwners::of, told)
                    .checkSpec("acme", config(), "auth", Lane.BUILD, "ana"));

    assertEquals(ErrorCode.CONFLICT, refused.failure().errorCode());
    assertEquals(
        "github.com asks for credentials to fetch acme's repo app, and ana keeps no token for it.",
        refused.getMessage());
    assertEquals(
        "Store one with sail git login github.com. Then dispatch again.",
        refused.failure().remedy().plain());
    assertEquals(
        List.of(
            "github.com asks for credentials to fetch acme's repo app, and ana keeps no token for"
                + " it. Store one with sail git login github.com. Then dispatch again."),
        told);
    assertFalse(incus.invocations().stream().anyMatch(c -> c.startsWith("incus copy")));
  }

  /**
   * A person's own whole workspace is reused whatever their roster row says now: they are asked of
   * only when a workspace will be made or re-owned.
   */
  @Test
  void aWorkspaceAlreadyTheOwnersIsNeverRefusedForTheOwner() throws Exception {
    var incus =
        TestWorkspaces.incus(definitions(), "acme")
            .onOk(
                "incus list --format json",
                "["
                    + TestWorkspaces.currentTemplate(definitions(), "acme")
                    + ","
                    + TestWorkspaces.specWorkspace("acme", "auth", "Running", "eng")
                    + "]");

    var check =
        owning(incus, RunPlacesTest::withoutEmail, new ArrayList<>())
            .checkSpec("acme", config(), "auth", Lane.REVIEW, "eng");

    assertEquals("auth", check.place().specId(), "not refused");
  }

  /** Scenarios 6 and 7: a workspace another person owns is re-owned only for one who may own it. */
  @Test
  void aWorkspaceOfAnotherIsReOwnedOnlyForAPersonWithAnEmail() throws Exception {
    var incus =
        TestWorkspaces.incus(definitions(), "acme")
            .onOk(
                "incus list --format json",
                "["
                    + TestWorkspaces.currentTemplate(definitions(), "acme")
                    + ","
                    + TestWorkspaces.specWorkspace("acme", "auth", "Running", "uday")
                    + "]");

    var refused =
        assertThrows(
            ApiException.class,
            () ->
                owning(incus, RunPlacesTest::withoutEmail, new ArrayList<>())
                    .checkSpec("acme", config(), "auth", Lane.BUILD, "ana"));

    assertEquals(ErrorCode.CONFLICT, refused.failure().errorCode());
    assertEquals(
        "ana has no email on this box, and a workspace commits as its owner.",
        refused.getMessage());
    assertEquals(
        "Set it on main with sail fde update ana --email <email>. Then dispatch again.",
        refused.failure().remedy().plain());
  }

  /**
   * Rule 4: what is given is read when it is given. A token removed between the check before the
   * reservation and the give is refused there as the same refusal, the workspace being made is
   * discarded, and nothing of the person's is written.
   */
  @Test
  void aTokenRemovedBetweenTheCheckAndTheGiveIsRefusedThenAndNothingIsWritten() throws Exception {
    var incus = TestWorkspaces.incus(definitions(), "acme");
    var told = new ArrayList<String>();
    var reads = new AtomicInteger();
    Function<String, WorkspaceOwner> owners =
        handle ->
            new WorkspaceOwner(
                handle,
                handle,
                handle + "@sail.invalid",
                reads.getAndIncrement() == 0 ? Map.of("github.com", "ghp_gone") : Map.of(),
                List.of(),
                "fde_" + handle);
    var places = owning(incus, owners, told);
    var check = places.checkSpec("acme", config(), "auth", Lane.BUILD, "ana");

    var refused = assertThrows(ApiException.class, () -> places.enter(check, config()));

    assertEquals(ErrorCode.CONFLICT, refused.failure().errorCode());
    assertEquals(
        "github.com asks for credentials to fetch acme's repo app, and ana keeps no token for it.",
        refused.getMessage());
    assertEquals(
        3, reads.get(), "read at the check, for the clone's owner id, and again at the give");
    assertFalse(incus.invocations().stream().anyMatch(c -> c.contains("sail-strip")));
    assertFalse(incus.invocations().stream().anyMatch(c -> c.startsWith("incus file push")));
    assertTrue(
        incus.invocations().stream().anyMatch(c -> c.contains("delete") && c.contains("acme-auth")),
        String.join("\n", incus.invocations()));
  }
}
