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
   * refused saying so, and nothing is started: the next pass leaves it nobody's.
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
        "Workspace 'try' of acme is zed's, who is no longer active here, so it is not started: the"
            + " next pass leaves it nobody's. Delete it meanwhile with: sail project workspace"
            + " delete acme try --force.",
        refused.getMessage());
    assertTrue(box.invocations().stream().noneMatch(c -> c.startsWith("incus start")));
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
