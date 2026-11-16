/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.sail.common.Remedy;
import ai.singlr.sail.config.ProjectDefinition;
import ai.singlr.sail.engine.FileMutex;
import ai.singlr.sail.engine.ScriptedShellExecutor;
import ai.singlr.sail.engine.ShellExec;
import ai.singlr.sail.engine.TestOwners;
import ai.singlr.sail.engine.Workspaces;
import ai.singlr.sail.identity.Actor;
import ai.singlr.sail.identity.Actors;
import ai.singlr.sail.identity.ProjectScope;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A person's workspace doors beside a terminal (R10): each decided as Enter a workspace on the
 * workspace's owner, over a box that answers only for the workspace it holds; a delete reads what
 * the workspace holds first, with the idle sweep's own reads.
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

  @TempDir Path tempDir;

  /** Workspace {@code try} of acme as Incus lists it: {@code state}, {@code owner}'s. */
  private static String trying(String state, String owner) {
    return """
        {"name": "acme-try", "status": "%s", "type": "container",
         "config": {"user.sail.project": "acme", "user.sail.workspace": "try",
                    "user.sail.ready": "1762941601000", "user.sail.owner": "%s"},
         "expanded_devices": {"root": {"path": "/", "pool": "tank", "type": "disk"}}}"""
        .formatted(state, owner);
  }

  private static ScriptedShellExecutor box(String workspace) {
    return new ScriptedShellExecutor(new ShellExec.Result(0, "", ""))
        .onOk("incus list ^acme-try$", "[" + workspace + "]")
        .onOk("incus list --format json", "[" + workspace + "]")
        .onOk("incus storage list --format json", TestWorkspaces.POOL);
  }

  private WorkspaceDoors doors(ShellExec box) {
    return new WorkspaceDoors(
        TestWorkspaces.over(box, System::nanoTime, tempDir), box, TestOwners::of);
  }

  private static ApiException refused(Actor actor, Door door) {
    return assertThrows(ApiException.class, () -> Actor.call(actor, door::run));
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
    assertEquals(1, box.invocations().stream().filter(c -> c.startsWith("incus start")).count());
  }

  /**
   * Scenario 45 and the agents: a viewer and every agent are refused; the owner stops their own.
   */
  @Test
  void aViewerAndAnAgentAreRefusedAndTheOwnerStopsTheirOwn() throws Exception {
    var box = box(trying("Running", "ada"));
    var doors = doors(box);

    var viewer = refused(Actors.VIEWER, () -> doors.stop("acme", "try", spec -> false));
    var agent = refused(Actors.AGENT, () -> doors.stop("acme", "try", spec -> false));
    Actor.call(Actors.OWNER_API, () -> doors.stop("acme", "try", spec -> false));

    assertEquals(ErrorCode.READ_ONLY_CREDENTIAL, viewer.failure().errorCode());
    assertEquals(ErrorCode.AGENT_LANE_FORBIDDEN, agent.failure().errorCode());
    assertEquals(1, box.invocations().stream().filter(c -> c.startsWith("incus stop")).count());
  }

  /** A workspace the project does not have is not found, never refused. */
  @Test
  void aWorkspaceThatIsNotThereIsNotFound() {
    var doors = doors(box("").onOk("incus list ^acme-try$", "[]"));

    var absent = refused(Actors.ADMIN, () -> doors.start(ACME, "try"));

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
    var stopped = "[" + trying("Stopped", "bob") + "]";
    var box =
        new ScriptedShellExecutor(new ShellExec.Result(0, "", ""))
            .onOk("incus storage list --format json", TestWorkspaces.POOL)
            .onOk("incus list ^acme-try$", stopped)
            .onceOnOk("incus list --format json", stopped)
            .onOk("list --format json", "[" + trying("Running", "bob") + "]")
            .onOk("sail-left", "changes app\nstash app\n");
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
    assertEquals(
        List.of("start", "stop"),
        box.invocations().stream()
            .filter(c -> c.startsWith("incus start") || c.startsWith("incus stop"))
            .map(c -> c.split(" ")[1])
            .toList());
    assertTrue(box.invocations().stream().noneMatch(c -> c.startsWith("incus delete")));
  }

  /** A read that fails cannot tell, and needs force too; the owner's own is not named. */
  @Test
  void aDeleteWhoseReadFailsCannotTellAndIsRefused() {
    var box =
        box(trying("Running", "ada"))
            .on("sail-left", new ShellExec.Result(128, "", "fatal: bad\ngit could not read app"));
    var doors = doors(box);

    var refused =
        refused(
            Actors.OWNER_API,
            () -> doors.delete(ACME, "try", spec -> false, false, FORCED, i -> {}));

    assertEquals(ErrorCode.WORKSPACE_HOLDS_WORK, refused.failure().errorCode());
    assertEquals(
        "Whether workspace try of acme holds work cannot be told: git could not read app:"
            + " fatal: bad.",
        refused.getMessage());
  }

  /** Force deletes what it holds unread; a live run refuses the delete whatever is passed. */
  @Test
  void forceDeletesUnreadAndALiveRunRefusesWhateverIsPassed() throws Exception {
    var held = box(trying("Running", "ada")).onOk("sail-left", "changes app\n");
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

    assertTrue(held.invocations().stream().noneMatch(c -> c.contains("sail-left")));
    assertTrue(held.invocations().stream().anyMatch(c -> c.startsWith("incus delete")));
    assertTrue(refused.getMessage().contains("holds a live run of spec a"), refused.getMessage());
    assertTrue(live.invocations().stream().noneMatch(c -> c.startsWith("incus delete")));
  }

  /** Scenario 30: a member makes their own workspace from the current template, owned by them. */
  @Test
  void aMemberMakesTheirOwnWorkspaceAndAViewerNone() throws Exception {
    var definitions = (ProjectReader) project -> Optional.of(ACME);
    var box =
        TestWorkspaces.incus(definitions, "acme")
            .onOk("incus list ^acme-try$", "[]")
            .onOk("incus copy", "")
            .onOk("incus start", "");
    var doors = doors(box);

    var viewer = refused(Actors.VIEWER, () -> doors.create(ACME, "try", tempDir, p -> false));
    var created =
        Actor.call(Actors.OWNER_API, () -> doors.create(ACME, "try", tempDir, p -> false));

    assertEquals(ErrorCode.READ_ONLY_CREDENTIAL, viewer.failure().errorCode());
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
            ApiException.class, () -> WorkspaceDoors.refusal(elsewhere, "acme", "try", "ada"));
    var refused =
        assertThrows(
            ApiException.class,
            () -> WorkspaceDoors.requireEnter(Actors.OTHER_API, "acme", "try", "ada"));
    WorkspaceDoors.requireEnter(Actors.OWNER_API, "acme", "try", "ada");

    assertEquals(ErrorCode.PROJECT_NOT_IN_CATALOG, unheld.failure().errorCode());
    assertEquals(ErrorCode.FORBIDDEN_NOT_ASSIGNEE, refused.failure().errorCode());
  }

  /** A workspace that holds nothing is deleted without force, its read made first. */
  @Test
  void aDeleteOfAWorkspaceThatHoldsNothingNeedsNoForce() throws Exception {
    var box = box(trying("Running", "ada"));

    Actor.call(
        Actors.OWNER_API,
        () -> doors(box).delete(ACME, "try", spec -> false, false, FORCED, i -> {}));

    assertEquals(
        List.of("sail-left", "incus delete"),
        box.invocations().stream()
            .map(c -> c.contains("sail-left") ? "sail-left" : c)
            .filter(c -> c.equals("sail-left") || c.startsWith("incus delete"))
            .map(c -> c.startsWith("incus delete") ? "incus delete" : c)
            .toList());
  }

  /** The API says each failure of a door with its own code: a busy runner is not a conflict. */
  @Test
  void eachFailureOfADoorIsAnsweredWithItsOwnCode() throws Exception {
    var locks = Files.createDirectories(tempDir.resolve("locks"));
    var deadline = Workspaces.CREATE_WAIT.toNanos();
    var reads = new AtomicInteger();
    var box =
        TestWorkspaces.incus(project -> Optional.of(ACME), "acme")
            .onOk("incus list ^acme-try$", "[]");
    var doors =
        new WorkspaceDoors(
            TestWorkspaces.over(box, () -> reads.getAndIncrement() * deadline, locks),
            box,
            TestOwners::of);
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
    var conflict =
        assertThrows(
            ApiException.class,
            () ->
                WorkspaceDoors.answered(
                    () -> {
                      throw new IllegalStateException("taken");
                    }));
    var incus =
        assertThrows(
            ApiException.class,
            () ->
                WorkspaceDoors.answered(
                    () -> {
                      throw new IOException("Error: Failed to start device");
                    }));
    var interrupted =
        assertThrows(
            ApiException.class,
            () ->
                WorkspaceDoors.answered(
                    () -> {
                      throw new InterruptedException();
                    }));
    var kept = Thread.interrupted();
    var invalid = new IllegalArgumentException("Invalid workspace name: 'X'.");

    assertEquals(ErrorCode.RUNNER_BUSY, busy.failure().errorCode());
    assertEquals(
        "This runner is making another workspace, which takes about a minute.", busy.getMessage());
    assertEquals(ErrorCode.CONFLICT, conflict.failure().errorCode());
    assertEquals(ErrorCode.CONTAINER_ERROR, incus.failure().errorCode());
    assertEquals("Error: Failed to start device", incus.getMessage());
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
}
