/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.sail.common.Remedied;
import ai.singlr.sail.common.Remedy;
import ai.singlr.sail.config.SyncConfig;
import ai.singlr.sail.config.TestDefinitions;
import ai.singlr.sail.engine.BoxInstances;
import ai.singlr.sail.engine.ScriptedShellExecutor;
import ai.singlr.sail.engine.ShellExec;
import ai.singlr.sail.engine.TestOwners;
import ai.singlr.sail.identity.Actor;
import ai.singlr.sail.identity.ProjectScope;
import ai.singlr.sail.identity.Role;
import ai.singlr.sail.pty.PtyAdmission;
import ai.singlr.sail.pty.PtyIdentity;
import ai.singlr.sail.store.HeldProjects;
import ai.singlr.sail.store.SchemaManager;
import ai.singlr.sail.store.Sqlite;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** The terminal facet's project gate: a session opens only in a project its person holds here. */
class HostPtyTest {

  private Sqlite db;
  private HostLanes.Pty pty;
  private final Incus incus = new Incus();

  @BeforeEach
  void setUp() {
    db = Sqlite.openMemory();
    new SchemaManager(db).migrate();
    HeldProjects.hold(db, "acme");
    pty =
        new HostLanes.Pty(
            db,
            TestAuth.roles(db, new SyncConfig("main", null, "root", "box")),
            null,
            new BoxInstances(incus),
            TestWorkspaces.over(incus),
            project -> Optional.of(TestDefinitions.named(project)),
            TestOwners::of,
            incus);
  }

  @AfterEach
  void tearDown() {
    db.close();
  }

  @Test
  void aTerminalOpensOnlyInAProjectItsPersonHoldsOnThisBoxWhateverTheirRole() throws IOException {
    var absent =
        "Project 'beta' is not in the catalog. Create it with 'sail project create beta', or sync"
            + " it from main with 'sail sync'.";
    var held =
        new Actor("ada", Role.MEMBER, Actor.Lane.API).withScope(ProjectScope.of(List.of("acme")));
    var viewer =
        new Actor("vic", Role.VIEWER, Actor.Lane.API).withScope(ProjectScope.of(List.of("acme")));
    var elsewhere =
        new Actor("ada", Role.MEMBER, Actor.Lane.API).withScope(ProjectScope.of(List.of("beta")));
    HeldProjects.hold(db, "beta");

    pty.admitProject("acme", new PtyIdentity.Person(held));
    pty.admitProject("acme", new PtyIdentity.Person(viewer));
    assertEquals(
        absent,
        assertThrows(
                Remedied.Io.class, () -> pty.admitProject("beta", new PtyIdentity.Person(held)))
            .getMessage());
    assertEquals(
        "Project 'acme' is not in the catalog. Create it with 'sail project create acme', or sync"
            + " it from main with 'sail sync'.",
        assertThrows(
                Remedied.Io.class,
                () -> pty.admitProject("acme", new PtyIdentity.Person(elsewhere)))
            .getMessage());
    assertEquals(
        "Project 'gone' is not in the catalog. Create it with 'sail project create gone', or sync"
            + " it from main with 'sail sync'.",
        assertThrows(
                Remedied.Io.class,
                () ->
                    pty.admitProject(
                        "gone",
                        new PtyIdentity.Person(new Actor("root", Role.ADMIN, Actor.Lane.API))))
            .getMessage());
  }

  @Test
  void anOpensInstanceIsAskedOfIncusByItsNameAloneWithinThreeSecondsAndAProjectCheckAsksNone()
      throws IOException {
    HeldProjects.hold(db, "acme-fix");
    var admin = new PtyIdentity.Person(new Actor("root", Role.ADMIN, Actor.Lane.API));

    pty.admitProject("acme-fix", admin);
    assertEquals(List.of(), incus.asked, "attach and tickets ask the project alone");
    pty.admitInstance("acme");
    var refused = assertThrows(Remedied.Io.class, () -> pty.admitInstance("acme-fix"));

    assertEquals(
        "Project acme-fix cannot take instance acme-fix: it is workspace fix of project acme."
            + " Delete the workspace (sail project workspace delete acme fix) or give the project"
            + " another name; the workspace itself is reached with sail project workspace shell"
            + " acme fix.",
        refused.getMessage());
    assertEquals(
        List.of(
            "incus list ^acme$ --format json within PT3S",
            "incus list ^acme-fix$ --format json within PT3S"),
        incus.asked);
  }

  /**
   * Incus failing, or not answering in time, is the runner unable to read its workspaces: said in a
   * person's sentence, unavailable and never refused, the tool's own words kept as the cause for
   * the log and out of what a person reads.
   */
  @Test
  void anOpenIsUnavailableInAPersonsWordsWhenIncusFailsOrDoesNotAnswer() {
    var slow = assertThrows(PtyAdmission.Unavailable.class, () -> pty.admitInstance("slow"));
    var interrupted =
        assertThrows(PtyAdmission.Unavailable.class, () -> pty.admitInstance("interrupted"));
    assertTrue(Thread.interrupted(), "the interrupt is kept");
    var denied = assertThrows(PtyAdmission.Unavailable.class, () -> pty.admitInstance("denied"));

    for (var unavailable : List.of(slow, interrupted, denied)) {
      assertEquals(
          "The runner could not read its workspaces, so no terminal was opened.",
          unavailable.reason());
      assertEquals("An admin looks at the runner.", unavailable.remedy().plain());
    }
    assertEquals(
        "incus list failed: Error: not authorized",
        denied.getCause().getMessage(),
        "what Incus said is the cause, for the log");
    assertFalse(denied.getMessage().contains("incus"), denied.getMessage());
  }

  /**
   * This box's Incus as a one-name lookup asks it: {@code acme} plain, {@code acme-fix} a workspace
   * of acme, {@code slow} never answering, {@code interrupted} interrupting the asker, {@code
   * denied} refusing; each ask recorded with its bound.
   */
  /**
   * R10 and R15 at the terminal: workspace {@code try} is entered by its owner or an admin, found
   * by its own instance's name and keys; another member is refused naming the owner; one the
   * project does not have is not found; entering a stopped one starts it and gives its owner
   * theirs.
   */
  @Test
  void aWorkspaceIsEnteredByItsOwnerOrAnAdminAndAStoppedOneIsStartedForIt() throws IOException {
    var stopped =
        """
        {"name": "acme-try", "status": "Stopped", "type": "container",
         "config": {"user.sail.project": "acme", "user.sail.workspace": "try",
                    "user.sail.ready": "1762941601000", "user.sail.owner": "ada",
                    "user.sail.owner_id": "fde_ada"},
         "expanded_devices": {"root": {"path": "/", "pool": "tank", "type": "disk"}}}""";
    var box =
        new ScriptedShellExecutor(new ShellExec.Result(0, "", ""))
            .onOk("incus storage list --format json", TestWorkspaces.POOL)
            .onOk("incus list ^acme-try$", "[" + stopped + "]")
            .onOk("incus list ^acme-gone$", "[]")
            .onOk("incus list --format json", "[" + stopped + "]");
    var lane =
        new HostLanes.Pty(
            db,
            TestAuth.roles(db, new SyncConfig("main", null, "root", "box")),
            null,
            new BoxInstances(box),
            TestWorkspaces.over(box),
            project -> Optional.of(TestDefinitions.named(project)),
            TestOwners::of,
            box);
    var ada = new PtyIdentity.Person(new Actor("ada", Role.MEMBER, Actor.Lane.API));
    var bob = new PtyIdentity.Person(new Actor("bob", Role.MEMBER, Actor.Lane.API));
    var root = new PtyIdentity.Person(new Actor("root", Role.ADMIN, Actor.Lane.API));

    lane.admitWorkspace("acme", "try", ada);
    lane.admitWorkspace("acme", "try", root);
    var refused = assertThrows(Remedied.Io.class, () -> lane.admitWorkspace("acme", "try", bob));
    var absent =
        assertThrows(PtyAdmission.Absent.class, () -> lane.admitWorkspace("acme", "gone", ada));
    lane.enterWorkspace("acme", "try", ada);

    assertEquals(
        "Workspace 'try' belongs to 'ada', not you. Ask ada to enter it, or have an admin do it.",
        refused.getMessage());
    assertEquals("Project 'acme' has no workspace 'gone' on this box.", absent.reason());
    assertEquals(
        List.of(new Remedy.Command(Remedy.Runs.SAIL, "project workspace list", List.of("acme"))),
        absent.remedy().commands(),
        "the door answers the remedy typed, never flattened into the reason");
    assertTrue(box.invocations().contains("incus start acme-try"), box.invocations().toString());
    assertTrue(box.invocations().stream().anyMatch(c -> c.contains("sail-strip")), "given ada's");
  }

  /**
   * A terminal opened by an admin in a workspace whose labelled person is no longer active here is
   * refused saying so, and nothing is started until the box's pass leaves it nobody's.
   */
  @Test
  void aWorkspaceWhosePersonIsGoneIsNotStartedForATerminalSayingSo() {
    var stopped =
        """
        {"name": "acme-try", "status": "Stopped", "type": "container",
         "config": {"user.sail.project": "acme", "user.sail.workspace": "try",
                    "user.sail.ready": "1762941601000", "user.sail.owner": "zed",
                    "user.sail.owner_id": "fde_zed"},
         "expanded_devices": {"root": {"path": "/", "pool": "tank", "type": "disk"}}}""";
    var box =
        new ScriptedShellExecutor()
            .onOk("incus storage list --format json", TestWorkspaces.POOL)
            .onOk("incus list --format json", "[" + stopped + "]");
    var lane =
        new HostLanes.Pty(
            db,
            TestAuth.roles(db, new SyncConfig("main", null, "root", "box")),
            null,
            new BoxInstances(box),
            TestWorkspaces.over(box),
            project -> Optional.of(TestDefinitions.named(project)),
            handle -> {
              throw new IllegalStateException(handle + " is not on this box's roster.");
            },
            box);
    var root = new PtyIdentity.Person(new Actor("root", Role.ADMIN, Actor.Lane.API));

    var refused = assertThrows(IOException.class, () -> lane.enterWorkspace("acme", "try", root));

    assertEquals(
        "try is zed's workspace, and zed is no longer active here, so it is not started until the"
            + " box's pass leaves it nobody's. Delete it meanwhile with: sail project workspace delete"
            + " acme try --force.",
        refused.getMessage());
    assertEquals("owner_gone", ((Remedied) refused).code(), "the app tells it apart by its code");
    assertTrue(box.invocations().stream().noneMatch(c -> c.startsWith("incus start")));
  }

  /** Workspace {@code name} of acme as Incus lists it, stopped, labelled {@code label}. */
  private static String acme(String name, String label) {
    return """
        {"name": "acme-%s", "status": "Stopped", "type": "container",
         "config": {"user.sail.project": "acme", "user.sail.workspace": "%s",
                    "user.sail.ready": "1762941601000"%s},
         "expanded_devices": {"root": {"path": "/", "pool": "tank", "type": "disk"}}}"""
        .formatted(name, name, label);
  }

  /**
   * N1: on a node, its own person opens a terminal in, or starts, a workspace labelled for them by
   * id or for no one, never one labelled for anyone else: a person gone from the node, or another
   * person of their handle. Each is refused before anything starts or is stripped, with its code.
   */
  @Test
  void onANodeItsPersonEntersOnlyAWorkspaceLabelledForThemByIdOrForNoOne() throws IOException {
    var old = acme("old", ", \"user.sail.owner\": \"ada\", \"user.sail.owner_id\": \"fde_old\"");
    var gone = acme("gone", ", \"user.sail.owner\": \"zed\", \"user.sail.owner_id\": \"fde_zed\"");
    var loose = acme("loose", "");
    var box =
        new ScriptedShellExecutor(new ShellExec.Result(0, "", ""))
            .onOk("incus storage list --format json", TestWorkspaces.POOL)
            .onOk("incus list ^acme-old$", "[" + old + "]")
            .onOk("incus list ^acme-gone$", "[" + gone + "]")
            .onOk("incus list ^acme-loose$", "[" + loose + "]")
            .onOk("incus list --format json", "[" + old + "," + gone + "," + loose + "]");
    var lane =
        new HostLanes.Pty(
            db,
            TestAuth.roles(db, new SyncConfig(SyncConfig.ROLE_NODE, "sail@main", "ada", "ada-box")),
            null,
            new BoxInstances(box),
            TestWorkspaces.over(box),
            project -> Optional.of(TestDefinitions.named(project)),
            handle -> {
              if (handle.equals("zed")) {
                throw new IllegalStateException("zed is not on this box's roster.");
              }
              return TestOwners.of(handle);
            },
            box);
    var ada = new PtyIdentity.Person(new Actor("ada", Role.MEMBER, Actor.Lane.API));

    var notTheirs = assertThrows(Remedied.Io.class, () -> lane.admitWorkspace("acme", "old", ada));
    var goneOwner = assertThrows(Remedied.Io.class, () -> lane.admitWorkspace("acme", "gone", ada));
    var notStarted = assertThrows(Remedied.Io.class, () -> lane.enterWorkspace("acme", "old", ada));
    lane.admitWorkspace("acme", "loose", ada);
    var bob = new PtyIdentity.Person(new Actor("bob", Role.MEMBER, Actor.Lane.API));
    var bobsOld = assertThrows(Remedied.Io.class, () -> lane.admitWorkspace("acme", "old", bob));
    var bobsLoose =
        assertThrows(Remedied.Io.class, () -> lane.admitWorkspace("acme", "loose", bob));

    assertEquals(
        List.of(
            "not_your_workspace",
            "old is labelled for a person this box does not know as ada, so no terminal opens in"
                + " it until the box's pass leaves it nobody's."),
        List.of(notTheirs.code(), notTheirs.reason()));
    assertEquals(
        List.of(notTheirs.code(), notTheirs.reason()),
        List.of(bobsOld.code(), bobsOld.reason()),
        "another member is told what it is, never that it is ada's");
    assertEquals("Workspace 'loose' belongs to 'ada', not you.", bobsLoose.reason());
    assertEquals(
        List.of(
            "owner_gone",
            "gone is zed's workspace, and zed is no longer active here, so no terminal opens in"
                + " it until the box's pass leaves it nobody's."),
        List.of(goneOwner.code(), goneOwner.reason()));
    assertEquals(Remedy.NONE, goneOwner.remedy(), "on a node no one deletes it but the pass");
    assertEquals("not_your_workspace", notStarted.code());
    assertTrue(
        box.invocations().stream()
            .noneMatch(c -> c.startsWith("incus start") || c.contains("sail-strip")),
        box.invocations().toString());
  }

  /** Workspace {@code name} of acme as Incus lists it, {@code status}, labelled {@code label}. */
  private static String acme(String name, String status, String label) {
    return acme(name, label).replace("\"Stopped\"", "\"" + status + "\"");
  }

  /** A box's pty lane over {@code box}, as {@code role} with {@code handle} its person. */
  private HostLanes.Pty lane(ScriptedShellExecutor box, SyncConfig config) {
    return new HostLanes.Pty(
        db,
        TestAuth.roles(db, config),
        null,
        new BoxInstances(box),
        TestWorkspaces.over(box),
        project -> Optional.of(TestDefinitions.named(project)),
        handle -> {
          if (handle.equals("zed")) {
            throw new IllegalStateException("zed is not on this box's roster.");
          }
          return TestOwners.of(handle);
        },
        box);
  }

  /**
   * A workspace's files are read by whoever may enter it, decided as entering it is: its owner and
   * an admin, never another member; one the project does not have is not found; and a stopped one
   * is refused with the remedy that starts it, and nothing is started.
   */
  @Test
  void aWorkspacesFilesAreItsOwnersOrAnAdminsAndAStoppedOneIsNeverStartedForThem()
      throws IOException {
    var owned = ", \"user.sail.owner\": \"ada\", \"user.sail.owner_id\": \"fde_ada\"";
    var running = acme("try", "Running", owned);
    var idle = acme("idle", owned);
    var box =
        new ScriptedShellExecutor(new ShellExec.Result(0, "", ""))
            .onOk("incus storage list --format json", TestWorkspaces.POOL)
            .onOk("incus list ^acme-try$", "[" + running + "]")
            .onOk("incus list ^acme-idle$", "[" + idle + "]")
            .onOk("incus list ^acme-gone$", "[]");
    var lane = lane(box, new SyncConfig("main", null, "root", "box"));
    var ada = new PtyIdentity.Person(new Actor("ada", Role.MEMBER, Actor.Lane.API));
    var bob = new PtyIdentity.Person(new Actor("bob", Role.MEMBER, Actor.Lane.API));
    var root = new PtyIdentity.Person(new Actor("root", Role.ADMIN, Actor.Lane.API));

    lane.admitFiles("acme", "try", ada);
    lane.admitFiles("acme", "try", root);
    var refused = assertThrows(Remedied.Io.class, () -> lane.admitFiles("acme", "try", bob));
    var absent =
        assertThrows(PtyAdmission.Absent.class, () -> lane.admitFiles("acme", "gone", ada));
    var stopped =
        assertThrows(PtyAdmission.Stopped.class, () -> lane.admitFiles("acme", "idle", ada));
    var bobs = assertThrows(Remedied.Io.class, () -> lane.admitFiles("acme", "idle", bob));

    assertEquals("Workspace 'try' belongs to 'ada', not you.", refused.reason());
    assertEquals("Project 'acme' has no workspace 'gone' on this box.", absent.reason());
    assertEquals(
        "Workspace idle of acme is stopped, and reading its files does not start it. Start it with:"
            + " sail project workspace start acme idle.",
        stopped.getMessage());
    assertEquals("workspace_stopped", stopped.code());
    assertEquals(
        List.of(
            new Remedy.Command(
                Remedy.Runs.SAIL, "project workspace start", List.of("acme", "idle"))),
        stopped.remedy().commands());
    assertEquals(
        "Workspace 'idle' belongs to 'ada', not you.",
        bobs.reason(),
        "who may enter it is asked before whether it runs, so a stranger learns nothing more");
    assertTrue(
        box.invocations().stream()
            .noneMatch(c -> c.startsWith("incus start") || c.contains("sail-strip")),
        box.invocations().toString());
  }

  /**
   * m1: what the sweep asks of a files connection is the workspace as it is labelled now: ada's own
   * reads, and once it is re-owned (a run's re-own, a hand-made relabel) for bob, or disowned, no
   * one's, ada's files are refused there, while an admin still reads them.
   */
  @Test
  void aWorkspacesFilesFollowItsLabelNowAReOwnOrADisownEndingTheirsReading() throws IOException {
    var ada = new PtyIdentity.Person(new Actor("ada", Role.MEMBER, Actor.Lane.API));
    var root = new PtyIdentity.Person(new Actor("root", Role.ADMIN, Actor.Lane.API));
    var labels =
        List.of(
            ", \"user.sail.owner\": \"ada\", \"user.sail.owner_id\": \"fde_ada\"",
            ", \"user.sail.owner\": \"bob\", \"user.sail.owner_id\": \"fde_bob\"",
            "");
    var refusals = new ArrayList<String>();
    for (var label : labels) {
      var box =
          new ScriptedShellExecutor(new ShellExec.Result(0, "", ""))
              .onOk("incus storage list --format json", TestWorkspaces.POOL)
              .onOk("incus list ^acme-try$", "[" + acme("try", "Running", label) + "]");
      var lane = lane(box, new SyncConfig("main", null, "root", "box"));
      try {
        lane.admitFiles("acme", "try", ada);
        refusals.add("read");
      } catch (Remedied.Io refused) {
        refusals.add(refused.reason());
      }
      lane.admitFiles("acme", "try", root);
    }

    assertEquals(
        List.of(
            "read",
            "Workspace 'try' belongs to 'bob', not you.",
            "Workspace 'try' has no owner, so only an admin may enter it."),
        refusals);
  }

  /**
   * N1 for files: on a node, its own person reads the files of a workspace labelled for them by id
   * or for no one, never of one labelled for another person of their handle or for a person gone
   * from the node, each refused with its code in a files ticket's own words.
   */
  @Test
  void onANodeAWorkspacesFilesAreReadOnlyWhereItsPersonMayEnter() throws IOException {
    var old =
        acme(
            "old",
            "Running",
            ", \"user.sail.owner\": \"ada\", \"user.sail.owner_id\": \"fde_old\"");
    var gone =
        acme(
            "gone",
            "Running",
            ", \"user.sail.owner\": \"zed\", \"user.sail.owner_id\": \"fde_zed\"");
    var loose = acme("loose", "Running", "");
    var box =
        new ScriptedShellExecutor(new ShellExec.Result(0, "", ""))
            .onOk("incus storage list --format json", TestWorkspaces.POOL)
            .onOk("incus list ^acme-old$", "[" + old + "]")
            .onOk("incus list ^acme-gone$", "[" + gone + "]")
            .onOk("incus list ^acme-loose$", "[" + loose + "]");
    var lane = lane(box, new SyncConfig(SyncConfig.ROLE_NODE, "sail@main", "ada", "ada-box"));
    var ada = new PtyIdentity.Person(new Actor("ada", Role.MEMBER, Actor.Lane.API));
    var bob = new PtyIdentity.Person(new Actor("bob", Role.MEMBER, Actor.Lane.API));

    var reused = assertThrows(Remedied.Io.class, () -> lane.admitFiles("acme", "old", ada));
    var goneOwner = assertThrows(Remedied.Io.class, () -> lane.admitFiles("acme", "gone", ada));
    lane.admitFiles("acme", "loose", ada);
    var bobsOld = assertThrows(Remedied.Io.class, () -> lane.admitFiles("acme", "old", bob));

    assertEquals(
        List.of(
            "not_your_workspace",
            "old is labelled for a person this box does not know as ada, so its files are not"
                + " read until the box's pass leaves it nobody's."),
        List.of(reused.code(), reused.reason()));
    assertEquals(
        List.of(reused.code(), reused.reason()),
        List.of(bobsOld.code(), bobsOld.reason()),
        "another member is told what it is, never that it is ada's");
    assertEquals(
        List.of(
            "owner_gone",
            "gone is zed's workspace, and zed is no longer active here, so its files are not read"
                + " until the box's pass leaves it nobody's."),
        List.of(goneOwner.code(), goneOwner.reason()));
  }

  /** A workspace Incus does not answer for, or whose start fails, opens nothing, saying why. */
  @Test
  void aWorkspaceIncusCannotAnswerForOrStartOpensNothingSayingWhy() throws IOException {
    var ada = new PtyIdentity.Person(new Actor("ada", Role.MEMBER, Actor.Lane.API));
    var stopped =
        """
        {"name": "acme-try", "status": "Stopped", "type": "container",
         "config": {"user.sail.project": "acme", "user.sail.workspace": "try",
                    "user.sail.ready": "1762941601000", "user.sail.owner": "ada",
                    "user.sail.owner_id": "fde_ada"},
         "expanded_devices": {"root": {"path": "/", "pool": "tank", "type": "disk"}}}""";
    var interrupting =
        new ShellExec() {
          final ScriptedShellExecutor box =
              new ScriptedShellExecutor(new ShellExec.Result(0, "", ""))
                  .onOk("incus storage list --format json", TestWorkspaces.POOL)
                  .onOk("incus list ^acme-try$", "[" + stopped + "]")
                  .onOk("incus list --format json", "[" + stopped + "]");

          @Override
          public Result exec(List<String> command) throws IOException, InterruptedException {
            return exec(command, null, Duration.ZERO);
          }

          @Override
          public Result exec(List<String> command, Path workDir, Duration timeout)
              throws IOException, InterruptedException {
            if (String.join(" ", command).startsWith("incus start")) {
              throw new InterruptedException("shutting down");
            }
            return box.exec(command, workDir, timeout);
          }

          @Override
          public boolean isDryRun() {
            return false;
          }
        };
    var lane =
        new HostLanes.Pty(
            db,
            TestAuth.roles(db, new SyncConfig("main", null, "root", "box")),
            null,
            new BoxInstances(interrupting),
            TestWorkspaces.over(interrupting),
            project -> Optional.of(TestDefinitions.named(project)),
            TestOwners::of,
            interrupting);

    var slow =
        assertThrows(PtyAdmission.Unavailable.class, () -> pty.admitWorkspace("acme", "slow", ada));
    var cut =
        assertThrows(
            PtyAdmission.Unavailable.class, () -> pty.admitWorkspace("acme", "interrupted", ada));
    assertTrue(Thread.interrupted(), "the interrupt is kept");
    var unstarted =
        assertThrows(PtyAdmission.Unavailable.class, () -> lane.enterWorkspace("acme", "try", ada));
    assertTrue(Thread.interrupted(), "the interrupt is kept");

    assertEquals(PtyAdmission.Unavailable.UNREAD, slow.reason());
    assertEquals(PtyAdmission.Unavailable.UNREAD, cut.reason());
    assertEquals(PtyAdmission.Unavailable.UNFINISHED, unstarted.reason());
    assertEquals(
        "shutting down", unstarted.getCause().getMessage(), "kept as the cause, for the log");
  }

  /**
   * R1: a stopped workspace whose start fails, as "Start and Open" asks it, tells the person the
   * runner could not finish that and who looks, never Incus's own words, a command line among them;
   * what Incus said is the cause, for the log.
   */
  @Test
  void aWorkspaceWhoseStartFailsTellsThePersonNotTheToolsWords() throws IOException {
    var ada = new PtyIdentity.Person(new Actor("ada", Role.MEMBER, Actor.Lane.API));
    var stopped =
        """
        {"name": "acme-try", "status": "Stopped", "type": "container",
         "config": {"user.sail.project": "acme", "user.sail.workspace": "try",
                    "user.sail.ready": "1762941601000", "user.sail.owner": "ada",
                    "user.sail.owner_id": "fde_ada"},
         "expanded_devices": {"root": {"path": "/", "pool": "tank", "type": "disk"}}}""";
    var incus = "Error: Failed to start device. Try `incus info --show-log acme-try` for more info";
    var box =
        new ScriptedShellExecutor(new ShellExec.Result(0, "", ""))
            .onOk("incus storage list --format json", TestWorkspaces.POOL)
            .onOk("incus list ^acme-try$", "[" + stopped + "]")
            .onOk("incus list --format json", "[" + stopped + "]")
            .onFail("incus start acme-try", incus);
    var lane =
        new HostLanes.Pty(
            db,
            TestAuth.roles(db, new SyncConfig("main", null, "root", "box")),
            null,
            new BoxInstances(box),
            TestWorkspaces.over(box),
            project -> Optional.of(TestDefinitions.named(project)),
            TestOwners::of,
            box);

    var bob = new PtyIdentity.Person(new Actor("bob", Role.MEMBER, Actor.Lane.API));
    var unstarted =
        assertThrows(PtyAdmission.Unavailable.class, () -> lane.enterWorkspace("acme", "try", ada));
    var refused = assertThrows(IOException.class, () -> lane.enterWorkspace("acme", "try", bob));

    assertEquals(
        "Workspace 'try' belongs to 'ada', not you. Ask ada to enter it, or have an admin do it.",
        refused.getMessage(),
        "words written for a person are told as they are");
    assertEquals("The runner could not finish that.", unstarted.reason());
    assertEquals("An admin looks at the runner.", unstarted.remedy().plain());
    assertFalse(unstarted.getMessage().contains("incus"), unstarted.getMessage());
    assertTrue(unstarted.getCause().getMessage().contains(incus), "what Incus said, for the log");
  }

  private static final class Incus implements ShellExec {
    final List<String> asked = new ArrayList<>();

    @Override
    public Result exec(List<String> command) {
      throw new AssertionError("asked without a bound: " + command);
    }

    @Override
    public Result exec(List<String> command, Path workDir, Duration timeout)
        throws InterruptedException, TimeoutException {
      asked.add(String.join(" ", command) + " within " + timeout);
      return switch (command.get(2)) {
        case "^acme$" -> new Result(0, "[{\"name\": \"acme\", \"config\": {}}]", "");
        case "^acme-fix$" ->
            new Result(
                0,
                "[{\"name\": \"acme-fix\", \"config\": {\"user.sail.project\": \"acme\","
                    + " \"user.sail.workspace\": \"fix\"}}]",
                "");
        case "^slow$", "^acme-slow$" -> throw new TimeoutException("timed out");
        case "^interrupted$", "^acme-interrupted$" -> throw new InterruptedException();
        default -> new Result(1, "", "Error: not authorized");
      };
    }

    @Override
    public boolean isDryRun() {
      return false;
    }
  }
}
