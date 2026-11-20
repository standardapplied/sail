/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.sail.common.Remedied;
import ai.singlr.sail.common.Remedy;
import ai.singlr.sail.config.ProjectDefinition;
import ai.singlr.sail.config.SyncConfig;
import ai.singlr.sail.engine.FileMutex;
import ai.singlr.sail.engine.ScriptedShellExecutor;
import ai.singlr.sail.engine.ShellExec;
import ai.singlr.sail.engine.TestOwners;
import ai.singlr.sail.engine.WorkspaceOwner;
import ai.singlr.sail.engine.Workspaces;
import ai.singlr.sail.identity.Acting;
import ai.singlr.sail.identity.Actor;
import ai.singlr.sail.identity.Actors;
import ai.singlr.sail.identity.ProjectScope;
import ai.singlr.sail.identity.Role;
import ai.singlr.sail.identity.RoleRule;
import ai.singlr.sail.store.FdeStore;
import ai.singlr.sail.store.ProjectStore;
import ai.singlr.sail.store.SchemaManager;
import ai.singlr.sail.store.Sqlite;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A person's workspace doors beside a terminal (R10): each decided as Enter a workspace on the
 * workspace's owner, by the id its label stores, on the instance the workspace's locked entry
 * found, over a box that answers only the commands each door is given and fails any other; a delete
 * reads what the workspace holds first, with the idle sweep's own reads, under the same lock.
 */
class WorkspaceDoorsTest {

  private static final ProjectDefinition ACME =
      new ProjectDefinition(
          "acme",
          "",
          null,
          null,
          null,
          null,
          List.of(new ProjectDefinition.Repo("https://github.com/acme/app.git", "app", null)),
          null,
          null,
          null,
          null);
  private static final Remedy FORCED = Remedy.of("delete it with force");
  private static final String EXEC_IN_IT =
      "[{\"status\": \"Running\", \"description\": \"Executing command\","
          + " \"resources\": {\"instances\": [\"/1.0/instances/acme-try?project=default\"]}}]";

  @TempDir Path tempDir;
  private final List<String> ended = new ArrayList<>();
  private final SessionYield sessions =
      new SessionYield() {
        @Override
        public Hold lock(String project) {
          return NO_HOLD;
        }

        @Override
        public void end(List<String> names, Ending ending) {}

        @Override
        public void endIn(String project, String workspace, Ending ending) {
          ended.add(project + "/" + workspace + ": " + ending.line() + " | " + ending.record());
        }
      };

  /** Workspace {@code try} of acme as Incus lists it: {@code state}, {@code owner}'s by id. */
  private static String trying(String state, String owner) {
    return workspace(
        state,
        ", \"user.sail.owner\": \"%s\", \"user.sail.owner_id\": \"fde_%s\""
            .formatted(owner, owner));
  }

  /**
   * Workspace {@code try} of acme as Incus lists it: {@code state}, labelled with {@code label}.
   */
  private static String workspace(String state, String label) {
    return """
        {"name": "acme-try", "status": "%s", "type": "container",
         "config": {"user.sail.project": "acme", "user.sail.workspace": "try",
                    "user.sail.ready": "1762941601000"%s},
         "expanded_devices": {"root": {"path": "/", "pool": "tank", "type": "disk"}}}"""
        .formatted(state, label);
  }

  /**
   * A box holding acme's current template and no workspace {@code try}, failing every command but
   * the ones a create gives it.
   */
  private static ScriptedShellExecutor templated() {
    return new ScriptedShellExecutor()
        .onOk("incus list ^acme-try$", "[]")
        .onOk(
            "incus list --format json",
            "[" + TestWorkspaces.currentTemplate(project -> Optional.of(ACME), "acme") + "]")
        .onOk("incus snapshot list acme--tmpl --format json", TestWorkspaces.V1)
        .onOk("incus storage list --format json", TestWorkspaces.POOL)
        .onOk("incus query /1.0/storage-pools/tank/resources", "{}")
        .onOk("incus copy")
        .onOk("incus start acme-try")
        .onOk("incus config set acme-try")
        .onOk("incus file push")
        .onOk("incus exec acme-try");
  }

  /** A box holding {@code workspace}, failing every command but the ones a door gives it. */
  private static ScriptedShellExecutor box(String workspace) {
    return box(workspace, first -> {});
  }

  /**
   * As {@link #box(String)}, with {@code first}'s answers ahead of every other: each by a pattern
   * of its own, since an answer given again for the same pattern replaces the first.
   */
  private static ScriptedShellExecutor box(
      String workspace, Consumer<ScriptedShellExecutor> first) {
    var box = new ScriptedShellExecutor();
    first.accept(box);
    return box.onOk("incus list ^acme-try$", "[" + workspace + "]")
        .onOk("incus list --format json", "[" + workspace + "]")
        .onOk("incus storage list --format json", TestWorkspaces.POOL)
        .onOk("incus query /1.0/storage-pools/tank/resources", "{}")
        .onOk("incus operation list --format json", "[]")
        .onOk("incus start acme-try")
        .onOk("incus stop acme-try")
        .onOk("incus delete acme-try")
        .onOk("incus file push")
        .onOk("incus exec acme-try");
  }

  private WorkspaceDoors doors(ShellExec box) {
    return doors(box, TestOwners::of, null);
  }

  private WorkspaceDoors doors(
      ShellExec box, Function<String, WorkspaceOwner> owners, String keeper) {
    return new WorkspaceDoors(over(box), box, owners, sessions, keeper);
  }

  private Workspaces over(ShellExec box) {
    return TestWorkspaces.over(box, System::nanoTime, tempDir);
  }

  private static ApiException refused(Actor actor, Door door) {
    return assertThrows(ApiException.class, () -> Actor.call(actor, door::run));
  }

  private static List<String> changes(ScriptedShellExecutor box) {
    return box.invocations().stream()
        .filter(
            c ->
                c.startsWith("incus start")
                    || c.startsWith("incus stop")
                    || c.startsWith("incus delete")
                    || c.contains("sail-left"))
        .map(c -> c.contains("sail-left") ? "read" : c.split(" ")[1])
        .toList();
  }

  @FunctionalInterface
  private interface Door {
    Object run() throws Exception;
  }

  /** Scenarios 30, 56: another member's workspace is refused naming its owner; an admin enters. */
  @Test
  void anotherPersonsWorkspaceIsRefusedNamingItsOwnerAndAnAdminEntersIt() throws Exception {
    var box = box(trying("Stopped", "bob"));
    var doors = doors(box);

    var refused = refused(Actors.OWNER_API, () -> doors.start(ACME, "try"));
    Actor.call(Actors.ADMIN, () -> doors.start(ACME, "try"));

    assertEquals(ErrorCode.FORBIDDEN_NOT_ASSIGNEE, refused.failure().errorCode());
    assertEquals("Workspace 'try' belongs to 'bob', not you.", refused.getMessage());
    assertEquals(List.of("start"), changes(box));
  }

  /**
   * Scenario 45 and the agents: a viewer and every agent are refused; the owner stops their own,
   * and its open terminals are told who stopped it.
   */
  @Test
  void aViewerAndAnAgentAreRefusedAndTheOwnerStopsTheirOwnTellingItsTerminals() throws Exception {
    var box = box(trying("Running", "ada"));
    var doors = doors(box);

    var viewer = refused(Actors.VIEWER, () -> doors.stop("acme", "try", spec -> false));
    var agent = refused(Actors.AGENT, () -> doors.stop("acme", "try", spec -> false));
    Actor.call(Actors.OWNER_API, () -> doors.stop("acme", "try", spec -> false));

    assertEquals(ErrorCode.READ_ONLY_CREDENTIAL, viewer.failure().errorCode());
    assertEquals(ErrorCode.AGENT_LANE_FORBIDDEN, agent.failure().errorCode());
    assertEquals(List.of("stop"), changes(box));
    assertEquals(
        List.of(
            "acme/try: Workspace try of acme is being stopped by ada, which ends this terminal. |"
                + " Workspace try of acme was stopped by ada."),
        ended);
  }

  /** A workspace the project does not have is not found, never refused. */
  @Test
  void aWorkspaceThatIsNotThereIsNotFound() {
    var doors = doors(box("").onOk("incus list --format json", "[]"));

    var absent =
        refused(Actors.ADMIN, () -> WorkspaceDoors.answered(() -> doors.start(ACME, "try")));

    assertEquals(ErrorCode.NOT_FOUND, absent.failure().errorCode());
    assertEquals("Project 'acme' has no workspace 'try' on this box.", absent.getMessage());
    assertEquals(
        "List them with: sail project workspace list acme.", absent.failure().remedy().plain());
  }

  /**
   * Ruling 3: a delete of a workspace that holds work is refused naming what it holds, the owner by
   * name when the caller is not the owner; a stopped one is started for the read and stopped again.
   */
  @Test
  void aDeleteOfAWorkspaceThatHoldsWorkIsRefusedNamingItAndItsOwner() {
    var box =
        box(trying("Stopped", "bob"), first -> first.onOk("sail-left", "changes app\nstash app\n"));
    var doors = doors(box);

    var refused =
        refused(
            Actors.ADMIN, () -> doors.delete(ACME, "try", spec -> false, false, FORCED, i -> {}));

    assertEquals(ErrorCode.WORKSPACE_HOLDS_WORK, refused.failure().errorCode());
    assertEquals(
        "Workspace try of acme, bob's, holds work: app holds uncommitted changes and a stash.",
        refused.getMessage());
    assertEquals(
        "Take what it holds first, or delete it anyway: delete it with force",
        refused.failure().remedy().plain());
    assertEquals(List.of("start", "read", "stop"), changes(box));
  }

  /** A read that fails cannot tell, and needs force too; the owner's own is not named. */
  @Test
  void aDeleteWhoseReadFailsCannotTellAndIsRefused() {
    var box =
        box(
            trying("Running", "ada"),
            first ->
                first.on(
                    "sail-left",
                    new ShellExec.Result(128, "", "fatal: bad\ngit could not read app")));

    var refused =
        refused(
            Actors.OWNER_API,
            () -> doors(box).delete(ACME, "try", spec -> false, false, FORCED, i -> {}));

    assertEquals(ErrorCode.WORKSPACE_HOLDS_WORK, refused.failure().errorCode());
    assertEquals(
        "Whether workspace try of acme holds work cannot be told: git could not read app:"
            + " fatal: bad.",
        refused.getMessage());
    assertEquals(List.of("read"), changes(box));
  }

  /**
   * Ruling 2 of review 1: a workspace someone is in (a terminal, an attach or an exec, as Incus
   * lists its operations) is not deleted without force, naming that; with it, its terminals are
   * told who deleted it.
   */
  @Test
  void aDeleteOfAWorkspaceSomeoneIsInWaitsForForceAndForceTellsTheirTerminals() throws Exception {
    var box = box(trying("Running", "ada"), first -> first.onOk("operation list", EXEC_IN_IT));
    var doors = doors(box);

    var refused =
        refused(
            Actors.OWNER_API,
            () -> doors.delete(ACME, "try", spec -> false, false, FORCED, i -> {}));
    var deleted =
        Actor.call(
            Actors.OWNER_API,
            () -> doors.delete(ACME, "try", spec -> false, true, FORCED, i -> {}));

    assertEquals(ErrorCode.WORKSPACE_HOLDS_WORK, refused.failure().errorCode());
    assertEquals(
        "Workspace try of acme is in use: Incus runs a command in it (a terminal, an attach or"
            + " an exec).",
        refused.getMessage());
    assertEquals(
        "End what runs in it first, or delete it anyway: delete it with force",
        refused.failure().remedy().plain());
    assertEquals(Workspaces.Change.DELETED, deleted.change());
    assertEquals(List.of("read", "delete"), changes(box));
    assertEquals(
        List.of(
            "acme/try: Workspace try of acme is being deleted by ada, which ends this terminal. |"
                + " Workspace try of acme was deleted by ada."),
        ended);
  }

  /** Force deletes what it holds unread; a live run refuses the delete whatever is passed. */
  @Test
  void forceDeletesUnreadAndALiveRunRefusesWhateverIsPassed() throws Exception {
    var held = box(trying("Running", "ada"), first -> first.onOk("sail-left", "changes app\n"));
    var live =
        box(
            trying("Running", "ada")
                .replace("\"user.sail.owner\"", "\"user.sail.spec\": \"a\", \"user.sail.owner\""));

    Actor.call(
        Actors.OWNER_API,
        () -> doors(held).delete(ACME, "try", spec -> false, true, FORCED, i -> {}));
    var refused =
        assertThrows(
            IllegalStateException.class,
            () ->
                Actor.call(
                    Actors.OWNER_API,
                    () -> doors(live).delete(ACME, "try", spec -> true, true, FORCED, i -> {})));

    assertEquals(List.of("delete"), changes(held));
    assertTrue(refused.getMessage().contains("holds a live run of spec a"), refused.getMessage());
    assertEquals(List.of(), changes(live));
  }

  /** A workspace that holds nothing and no one is deleted without force, read first. */
  @Test
  void aDeleteOfAWorkspaceThatHoldsNothingNeedsNoForce() throws Exception {
    var box = box(trying("Running", "ada"));

    Actor.call(
        Actors.OWNER_API,
        () -> doors(box).delete(ACME, "try", spec -> false, false, FORCED, i -> {}));

    assertEquals(List.of("read", "delete"), changes(box));
    var asked = box.invocations().indexOf("incus operation list --format json");
    var deleted =
        box.invocations()
            .indexOf(
                box.invocations().stream()
                    .filter(c -> c.startsWith("incus delete"))
                    .findFirst()
                    .orElseThrow());
    assertTrue(0 <= asked && asked < deleted, "who is in it is asked before the delete");
  }

  /**
   * Review 1's B2 and M2: while another holds the workspace's lock (the sweep stopping or deleting
   * it, or a terminal's start), a start or a delete waits no longer than the create wait and then
   * says the runner is busy, having read, started and deleted nothing.
   */
  @Test
  void aStartOrADeleteWaitsForTheWorkspacesLockAndThenSaysTheRunnerIsBusy() throws Exception {
    var locks = Files.createDirectories(tempDir.resolve("locks"));
    var deadline = Workspaces.CREATE_WAIT.toNanos();
    var reads = new AtomicInteger();
    var box = box(trying("Stopped", "ada"));
    var workspaces = TestWorkspaces.over(box, () -> reads.getAndIncrement() * deadline, locks);
    var doors = new WorkspaceDoors(workspaces, box, TestOwners::of, sessions, null);
    var holding = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    var other =
        Thread.ofVirtual()
            .start(
                () -> {
                  try (var held = workspaces.holdIfFree("acme-try").orElseThrow()) {
                    holding.countDown();
                    release.await();
                  } catch (Exception e) {
                    throw new IllegalStateException(e);
                  }
                });
    assertTrue(holding.await(30, TimeUnit.SECONDS));
    ApiException start;
    ApiException delete;
    try {
      start =
          refused(Actors.OWNER_API, () -> WorkspaceDoors.answered(() -> doors.start(ACME, "try")));
      delete =
          refused(
              Actors.OWNER_API,
              () ->
                  WorkspaceDoors.answered(
                      () -> doors.delete(ACME, "try", spec -> false, false, FORCED, i -> {})));
    } finally {
      release.countDown();
      other.join();
    }

    for (var busy : List.of(start, delete)) {
      assertEquals(ErrorCode.RUNNER_BUSY, busy.failure().errorCode());
      assertEquals(
          "Workspace try of acme is being stopped or deleted on this runner, and nothing enters it"
              + " until that is done.",
          busy.getMessage());
    }
    assertEquals(List.of(), changes(box));
    assertTrue(box.invocations().stream().noneMatch(c -> c.startsWith("incus list")));
  }

  /**
   * Scenario 30: a member makes their own workspace from the current template, owned by them; a
   * viewer makes none, and nor does a credential that names no person.
   */
  @Test
  void aMemberMakesTheirOwnWorkspaceAndAViewerAndANamelessCredentialNone() throws Exception {
    var box = templated();
    var doors = doors(box);
    var nameless = new Actor(null, Role.ADMIN, Actor.Lane.API);

    var viewer = refused(Actors.VIEWER, () -> doors.create(ACME, "try", tempDir, p -> false));
    var noOne = refused(nameless, () -> doors.create(ACME, "try", tempDir, p -> false));
    var created =
        Actor.call(Actors.OWNER_API, () -> doors.create(ACME, "try", tempDir, p -> false));

    assertEquals(ErrorCode.READ_ONLY_CREDENTIAL, viewer.failure().errorCode());
    assertEquals(ErrorCode.FORBIDDEN, noOne.failure().errorCode());
    assertEquals(
        "This credential names no person, and a workspace is created for the person who asks.",
        noOne.getMessage());
    assertEquals("acme-try", created.instance());
    assertTrue(
        box.invocations().stream().anyMatch(c -> c.contains("user.sail.owner=ada")),
        String.join("\n", box.invocations()));
    assertTrue(
        box.invocations().stream().noneMatch(c -> c.contains("template build")),
        "the API builds no template");
  }

  /**
   * One decision for every door (R10): a project outside the caller's scope is one this box does
   * not hold, and the CLI's refusal carries the same code as the API's.
   */
  @Test
  void aProjectOutsideTheCallersScopeIsNotHeldAndTheRefusalCarriesItsCode() {
    var elsewhere = Actors.OWNER_API.withScope(ProjectScope.of(List.of("beta")));

    var unheld =
        assertThrows(
            ApiException.class,
            () -> WorkspaceDoors.refusal(elsewhere, "acme", "try", "ada", null));
    var refused =
        assertThrows(
            ApiException.class,
            () -> WorkspaceDoors.requireEnter(Actors.OTHER_API, "acme", "try", "ada", null));
    WorkspaceDoors.requireEnter(Actors.OWNER_API, "acme", "try", "ada", null);

    assertEquals(ErrorCode.PROJECT_NOT_IN_CATALOG, unheld.failure().errorCode());
    assertEquals(ErrorCode.FORBIDDEN_NOT_ASSIGNEE, refused.failure().errorCode());
  }

  /** The API says each failure of a door with its own code: a busy runner is not a conflict. */
  @Test
  void eachFailureOfADoorIsAnsweredWithItsOwnCode() throws Exception {
    var locks = Files.createDirectories(tempDir.resolve("locks"));
    var deadline = Workspaces.CREATE_WAIT.toNanos();
    var reads = new AtomicInteger();
    var box = templated();
    var doors =
        new WorkspaceDoors(
            TestWorkspaces.over(box, () -> reads.getAndIncrement() * deadline, locks),
            box,
            TestOwners::of,
            sessions,
            null);
    var holding = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    var other =
        Thread.ofVirtual()
            .start(
                () -> {
                  try (var held = FileMutex.acquire(locks.resolve("workspace-create.lock"))) {
                    holding.countDown();
                    release.await();
                  } catch (Exception e) {
                    throw new IllegalStateException(e);
                  }
                });
    assertTrue(holding.await(30, TimeUnit.SECONDS));
    ApiException busy;
    try {
      busy =
          refused(
              Actors.OWNER_API,
              () -> WorkspaceDoors.answered(() -> doors.create(ACME, "try", tempDir, p -> false)));
    } finally {
      release.countDown();
      other.join();
    }
    var conflict = answered(Remedied.state("Workspace 'try' of acme exists already.", Remedy.NONE));
    var unwritten = answered(new IllegalStateException("taken"));
    var incus = answered(new IOException("Error: Failed to start device"));
    var interrupted = answered(new InterruptedException());
    var kept = Thread.interrupted();
    var invalid = new IllegalArgumentException("Invalid workspace name: 'X'.");

    assertEquals(ErrorCode.RUNNER_BUSY, busy.failure().errorCode());
    assertEquals(
        "This runner is creating another workspace, which takes about a minute.",
        busy.getMessage());
    assertEquals(ErrorCode.CONFLICT, conflict.failure().errorCode());
    assertEquals("Workspace 'try' of acme exists already.", conflict.getMessage());
    for (var unsaid : List.of(unwritten, incus)) {
      assertEquals(ErrorCode.CONTAINER_ERROR, unsaid.failure().errorCode());
      assertEquals(
          "The runner could not finish that.",
          unsaid.getMessage(),
          "words written for no person go to the log, never to one");
      assertEquals("An admin looks at the runner.", unsaid.failure().remedy().plain());
    }
    assertEquals(ErrorCode.INTERNAL, interrupted.failure().errorCode());
    assertTrue(kept, "the interrupt is kept");
    assertEquals(
        invalid,
        assertThrows(
            IllegalArgumentException.class,
            () ->
                WorkspaceDoors.answered(
                    () -> {
                      throw invalid;
                    })));
  }

  private static ApiException answered(Exception failure) {
    return assertThrows(
        ApiException.class,
        () ->
            WorkspaceDoors.answered(
                () -> {
                  throw failure;
                }));
  }

  /**
   * #427 and review 1's M1: a workspace is its owner's by the id its label stores. Its handle given
   * since to another person, its person gone, or a label with no id at all makes it nobody's, an
   * admin's alone; the same label with the person's own id is theirs. An admin is told the truth of
   * a gone person's workspace rather than given it.
   */
  @Test
  void aWorkspaceIsItsOwnersByTheIdItsLabelStoresAndALabelWithNoIdIsNobodys() throws Exception {
    var labelled = trying("Stopped", "ada").replace("fde_ada", "fde_old_ada");
    Function<String, WorkspaceOwner> gone =
        handle -> {
          throw new IllegalStateException(handle + " is not on this box's roster.");
        };
    var regiven = box(labelled);
    var removed = box(labelled);
    var handleOnly = box(workspace("Stopped", ", \"user.sail.owner\": \"ada\""));
    var hers = box(trying("Stopped", "ada"));

    var notHers = refused(Actors.OWNER_API, () -> doors(regiven).start(ACME, "try"));
    var noOnes = refused(Actors.OWNER_API, () -> doors(removed, gone, null).start(ACME, "try"));
    var noId = refused(Actors.OWNER_API, () -> doors(handleOnly).start(ACME, "try"));
    var told =
        assertThrows(
            ApiException.class,
            () -> Actor.call(Actors.ADMIN, () -> doors(removed, gone, null).start(ACME, "try")));
    Actor.call(Actors.OWNER_API, () -> doors(hers).start(ACME, "try"));

    for (var refusal : List.of(notHers, noOnes, noId)) {
      assertEquals(ErrorCode.FORBIDDEN_NOT_ASSIGNEE, refusal.failure().errorCode());
      assertEquals(
          "Workspace 'try' has no owner, so only an admin may enter it.", refusal.getMessage());
    }
    assertEquals(
        List.of(
            "owner_gone",
            "Workspace 'try' of acme is ada's, who is no longer active here, so it is not started:"
                + " the next pass leaves it nobody's.",
            "Delete it meanwhile with: sail project workspace delete acme try --force."),
        List.of(told.code(), told.reason(), told.remedy().plain()));
    assertEquals(List.of(), changes(removed));
    assertEquals(List.of("start"), changes(hers));
  }

  /**
   * Review 1's B1: on a node, every person but its own acts as a viewer (the role rule's cap,
   * kept), and is told their workspaces live on main; the node's own person keeps what nobody owns
   * there, as an admin does on main, and on main the same member is refused it.
   */
  @Test
  void onANodeOthersAreSentToMainAndItsOwnPersonKeepsWhatNobodyOwns() throws Exception {
    try (var db = Sqlite.open(tempDir.resolve("roster.db"))) {
      new SchemaManager(db).migrate();
      var fdes = new FdeStore(db);
      fdes.add("client", null, "client@x", "member");
      fdes.add("eng", null, "eng@x", "member");
      var node =
          new RoleRule(() -> new SyncConfig("node", "sail@main", "client", "client-box"), fdes);
      var eng = node.principal(RoleRule.Credential.token("eng", "member")).orElseThrow();
      var client = node.principal(RoleRule.Credential.token("client", "member")).orElseThrow();
      var onNode = box(workspace("Stopped", ""));
      var onMain = box(workspace("Stopped", ""));

      var capped =
          refused(
              eng,
              () ->
                  doors(onNode, TestOwners::of, "client").create(ACME, "new", tempDir, p -> false));
      Actor.call(client, () -> doors(onNode, TestOwners::of, "client").start(ACME, "try"));
      var mainRefuses = refused(client, () -> doors(onMain).start(ACME, "try"));

      assertEquals(Role.VIEWER, eng.role());
      assertEquals(ErrorCode.READ_ONLY_CREDENTIAL, capped.failure().errorCode());
      assertEquals(
          "This box is client's node, where only client creates or enters a workspace; another"
              + " person's workspaces live on main.",
          capped.getMessage());
      assertEquals(
          "Workspaces are created on main: create yours there.", capped.failure().remedy().plain());
      assertEquals(List.of("start"), changes(onNode));
      assertEquals(
          "Workspace 'try' has no owner, so only an admin may enter it.", mainRefuses.getMessage());
      assertEquals(Optional.of("client"), node.nodeFde());
    }
  }

  /**
   * A template is built by an admin; on a node, by its own person alone, and every other person is
   * told the templates of their workspaces are built on main.
   */
  @Test
  void onANodeOnlyItsOwnPersonBuildsATemplateAndOnMainOnlyAnAdmin() throws Exception {
    try (var db = Sqlite.open(tempDir.resolve("roster.db"))) {
      new SchemaManager(db).migrate();
      var fdes = new FdeStore(db);
      fdes.add("client", null, "client@x", "admin");
      fdes.add("eng", null, "eng@x", "admin");
      fdes.add("ana", null, "ana@x", "member");
      var catalog = new ProjectStore(db);
      Acting.system(() -> catalog.upsert(ACME));
      var node =
          new RoleRule(() -> new SyncConfig("node", "sail@main", "client", "client-box"), fdes);
      var main = new RoleRule(() -> new SyncConfig("main", null, "client", "main"), fdes);
      var engOnNode = node.principal(RoleRule.Credential.token("eng", "admin")).orElseThrow();
      var clientOnNode = node.principal(RoleRule.Credential.token("client", "admin")).orElseThrow();
      var anaOnMain = main.principal(RoleRule.Credential.token("ana", "member")).orElseThrow();

      Door onTheNode =
          () -> {
            WorkspaceDoors.requireBuild(catalog, "acme", "client");
            return null;
          };
      Door onMain =
          () -> {
            WorkspaceDoors.requireBuild(catalog, "acme", null);
            return null;
          };

      var capped = refused(engOnNode, onTheNode);
      Actor.call(clientOnNode, onTheNode::run);
      var member = refused(anaOnMain, onMain);

      assertEquals(ErrorCode.READ_ONLY_CREDENTIAL, capped.failure().errorCode());
      assertEquals(
          "This box is client's node, where only client builds a template; acme's template for"
              + " your workspaces is built on main.",
          capped.getMessage());
      assertEquals(
          "Templates are built on main, where your workspaces are created.",
          capped.failure().remedy().plain());
      assertEquals(ErrorCode.FORBIDDEN_ADMIN_ONLY, member.failure().errorCode());
      assertEquals("Only an admin builds acme's template.", member.getMessage());
      assertEquals("Ask an admin to build it.", member.failure().remedy().plain());
    }
  }

  /**
   * Access's table says who builds a template: an admin. A node's own person who is a member is
   * refused on their own node as on main, in the same sentence, not sent to main.
   */
  @Test
  void aNodesOwnPersonWhoIsAMemberIsRefusedAsAMemberIsOnMain() throws Exception {
    try (var db = Sqlite.open(tempDir.resolve("keeper.db"))) {
      new SchemaManager(db).migrate();
      var fdes = new FdeStore(db);
      fdes.add("client", null, "client@x", "member");
      var catalog = new ProjectStore(db);
      Acting.system(() -> catalog.upsert(ACME));
      var node =
          new RoleRule(() -> new SyncConfig("node", "sail@main", "client", "client-box"), fdes);
      var keeper = node.principal(RoleRule.Credential.token("client", "member")).orElseThrow();

      var refused =
          refused(
              keeper,
              () -> {
                WorkspaceDoors.requireBuild(catalog, "acme", "client");
                return null;
              });

      assertEquals(ErrorCode.FORBIDDEN_ADMIN_ONLY, refused.failure().errorCode());
      assertEquals("Only an admin builds acme's template.", refused.getMessage());
    }
  }

  /**
   * Ruling 6 at the CLI: a shell enters its own workspace through the same locked entry, starting a
   * stopped one, and answers where it opens; another member's is refused.
   */
  @Test
  void aShellStartsItsOwnersStoppedWorkspaceAndAnswersItsInstance() throws Exception {
    var box = box(trying("Stopped", "ada"));
    var doors = doors(box);

    var refused = refused(Actors.OTHER_API, () -> doors.startExisting(ACME, "try"));
    var instance = Actor.call(Actors.OWNER_API, () -> doors.startExisting(ACME, "try"));

    assertEquals(ErrorCode.FORBIDDEN_NOT_ASSIGNEE, refused.failure().errorCode());
    assertEquals("acme-try", instance);
    assertEquals(List.of("start"), changes(box));
  }

  /**
   * A stop whose terminal host cannot be told still stops: its terminals end with the workspace,
   * and that is said on the server's log.
   */
  @Test
  void aStopWhoseTerminalHostCannotBeToldStillStopsSayingSo() throws Exception {
    var box = box(trying("Running", "ada"));
    var untold =
        new SessionYield() {
          @Override
          public Hold lock(String project) {
            return NO_HOLD;
          }

          @Override
          public void end(List<String> names, Ending ending) {}

          @Override
          public void endIn(String project, String workspace, Ending ending) throws IOException {
            throw new IOException("the session host did not answer");
          }
        };
    var err = new ByteArrayOutputStream();
    var before = System.err;
    System.setErr(new PrintStream(err, true, StandardCharsets.UTF_8));
    try {
      Actor.call(
          Actors.OWNER_API,
          () ->
              new WorkspaceDoors(over(box), box, TestOwners::of, untold, null)
                  .stop("acme", "try", spec -> false));
    } finally {
      System.setErr(before);
    }

    assertEquals(List.of("stop"), changes(box));
    assertEquals(
        "[workspaces] the terminals of workspace try of acme end without being told why: the"
            + " session host did not answer",
        err.toString(StandardCharsets.UTF_8).strip());
  }

  /**
   * Review 1's B2: the delete reads the workspace again, under its lock, immediately before it
   * deletes: a run of its spec that became live after the first look refuses it.
   */
  @Test
  void aRunThatBecameLiveAfterTheFirstLookRefusesTheDeleteAtTheLast() {
    var box =
        box(
            trying("Running", "ada")
                .replace("\"user.sail.owner\"", "\"user.sail.spec\": \"a\", \"user.sail.owner\""));
    var asks = new AtomicInteger();

    var refused =
        assertThrows(
            IllegalStateException.class,
            () ->
                Actor.call(
                    Actors.OWNER_API,
                    () ->
                        doors(box)
                            .delete(
                                ACME,
                                "try",
                                spec -> asks.incrementAndGet() > 1,
                                true,
                                FORCED,
                                i -> {})));

    assertTrue(refused.getMessage().contains("holds a live run of spec a"), refused.getMessage());
    assertEquals(2, asks.get());
    assertEquals(List.of(), changes(box));
  }

  /**
   * Review 1's final minor 1: a stop ends the terminals only once every check that could refuse it
   * has passed, and a stop Incus then fails has told them it was being stopped (true either way),
   * and says why to whoever asked; a stop the checks refuse tells no terminal.
   */
  @Test
  void aStopTellsTerminalsOnlyOnceItWillProceedAndAFailedStopIsSaidToItsCaller() {
    var failing =
        box(
            trying("Running", "ada"),
            first -> first.on("stop acme-try", new ShellExec.Result(1, "", "Error: busy")));
    var live =
        box(
            trying("Running", "ada")
                .replace("\"user.sail.owner\"", "\"user.sail.spec\": \"a\", \"user.sail.owner\""));

    var failed =
        assertThrows(
            Exception.class,
            () ->
                Actor.call(
                    Actors.OWNER_API, () -> doors(failing).stop("acme", "try", spec -> false)));
    var told = List.copyOf(ended);
    ended.clear();
    assertThrows(
        IllegalStateException.class,
        () -> Actor.call(Actors.OWNER_API, () -> doors(live).stop("acme", "try", spec -> true)));

    assertTrue(failed.getMessage().contains("Error: busy"), failed.getMessage());
    assertEquals(
        List.of(
            "acme/try: Workspace try of acme is being stopped by ada, which ends this terminal. |"
                + " Workspace try of acme was stopped by ada."),
        told);
    assertEquals(List.of(), ended, "a stop refused for its live run tells no terminal");
  }
}
