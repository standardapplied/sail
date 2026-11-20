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
import ai.singlr.sail.engine.ContainerExec;
import ai.singlr.sail.engine.ScriptedShellExecutor;
import ai.singlr.sail.engine.SessionLogin;
import ai.singlr.sail.engine.WorkspaceOwner;
import ai.singlr.sail.harness.Harnesses;
import ai.singlr.sail.identity.Acting;
import ai.singlr.sail.identity.Actor;
import ai.singlr.sail.identity.Role;
import ai.singlr.sail.pty.PtyIdentity;
import ai.singlr.sail.pty.PtyMessage;
import ai.singlr.sail.store.FdeStore;
import ai.singlr.sail.store.HeldProjects;
import ai.singlr.sail.store.SchemaManager;
import ai.singlr.sail.store.Sqlite;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Whose login an agent starts with in a workspace, decided at the terminal host's door before the
 * workspace is entered: its owner's own default, looked up by the FDE id the workspace is labelled
 * for; never the box's, an admin's or another person's; refused in words a person acts on when the
 * workspace is not the asker's, the project does not install the harness, or the owner stored no
 * login for it. And how the login reaches the workspace: on the dev user's stdin, never a line.
 */
class HostPtyAgentTest {

  private static final PtyMessage.Agent CLAUDE = new PtyMessage.Agent("claude-code", "full", false);
  private static final PtyMessage.Agent CODEX = new PtyMessage.Agent("codex", "full", false);

  @TempDir Path dir;

  private Sqlite db;
  private FdeStore fdes;
  private String adaId;
  private ScriptedShellExecutor box;
  private HostLanes.Pty lane;

  private final PtyIdentity.Person ada = person("ada", Role.MEMBER);
  private final PtyIdentity.Person bob = person("bob", Role.MEMBER);
  private final PtyIdentity.Person root = person("root", Role.ADMIN);

  private static PtyIdentity.Person person(String handle, Role role) {
    return new PtyIdentity.Person(new Actor(handle, role, Actor.Lane.API));
  }

  /**
   * Workspace {@code name} of {@code project}, running, labelled for {@code owner} by {@code id}.
   */
  private static String workspace(String project, String name, String owner, String id) {
    return """
        {"name": "%s-%s", "status": "Running", "type": "container",
         "config": {"user.sail.project": "%s", "user.sail.workspace": "%s",
                    "user.sail.ready": "1762941601000"%s},
         "expanded_devices": {"root": {"path": "/", "pool": "tank", "type": "disk"}}}"""
        .formatted(
            project,
            name,
            project,
            name,
            owner == null
                ? ""
                : ", \"user.sail.owner\": \"%s\", \"user.sail.owner_id\": \"%s\""
                    .formatted(owner, id));
  }

  @BeforeEach
  void setUp() {
    db = Sqlite.open(dir.resolve("sail.db"));
    new SchemaManager(db).migrate();
    HeldProjects.hold(db, "acme");
    HeldProjects.hold(db, "harbor");
    fdes = new FdeStore(db);
    adaId = Acting.system(() -> fdes.add("ada", "Ada", null, "member")).id();
    var bobId = Acting.system(() -> fdes.add("bob", "Bob", null, "member")).id();
    Acting.system(() -> fdes.add("root", "Root", null, "admin"));
    var claude = Harnesses.of("claude-code");
    fdes.logins().store(adaId, claude, "work", "token", "sk-ant-oat01-ada-work");
    fdes.logins().store(bobId, claude, "work", "token", "sk-ant-oat01-bob-work");
    var codex = Harnesses.of("codex");
    fdes.logins().store(adaId, codex, "work", "api-key", "sk-proj-ada-work");
    fdes.logins().store(adaId, codex, "home", "api-key", "sk-proj-ada-home");
    fdes.logins().remove(adaId, codex, "work");
    box =
        new ScriptedShellExecutor()
            .onOk("incus list ^acme-dock$", "[" + workspace("acme", "dock", "ada", adaId) + "]")
            .onOk("incus list ^acme-spare$", "[" + workspace("acme", "spare", "bob", bobId) + "]")
            .onOk("incus list ^acme-loose$", "[" + workspace("acme", "loose", null, null) + "]")
            .onOk("incus list ^acme-old$", "[" + workspace("acme", "old", "ada", "fde_gone") + "]")
            .onOk("incus list ^harbor-pier$", "[" + workspace("harbor", "pier", "ada", adaId) + "]")
            .onOk("incus exec acme-dock", "");
    lane =
        new HostLanes.Pty(
            db,
            TestAuth.roles(db, new SyncConfig("main", null, "root", "box")),
            null,
            new BoxInstances(box),
            TestWorkspaces.over(box),
            project ->
                Optional.of(
                    TestDefinitions.yaml(
                        "name: "
                            + project
                            + "\nagent:\n  type: claude-code\n  install: "
                            + (project.equals("acme") ? "[claude-code, codex]" : "[claude-code]")
                            + "\n")),
            handle -> WorkspaceOwner.onThisBox(fdes, handle, "root"),
            box);
  }

  @AfterEach
  void tearDown() {
    db.close();
  }

  /** L1, scenarios 1 and 11: the owner's own default login, never another person's. */
  @Test
  void anAgentStartsWithItsWorkspaceOwnersOwnDefaultLogin() throws IOException {
    var given = lane.admitAgent("acme", "dock", CLAUDE, ada).orElseThrow();

    var adas = fdes.logins().defaultFor(adaId, Harnesses.of("claude-code")).orElseThrow();
    assertEquals(adas.row(), given.row());
    assertEquals(adas.value(), given.value());
  }

  /** L2, scenarios 9 and 10: only the owner starts one; an admin who may enter it does not. */
  @Test
  void anAgentStartsInAWorkspaceOnlyAsItsOwner() {
    for (var asker : List.of(root, bob)) {
      var refused =
          assertThrows(Remedied.Io.class, () -> lane.admitAgent("acme", "dock", CLAUDE, asker));
      assertEquals(
          "dock is ada's workspace, so an agent starts in it only as ada, with ada's login.",
          refused.reason());
      assertEquals(
          Remedy.of("Open a shell in it, or start the agent in a workspace of your own."),
          refused.remedy());
    }
    assertEquals(
        "loose is no one's workspace, so no agent starts in it.",
        assertThrows(Remedied.Io.class, () -> lane.admitAgent("acme", "loose", CLAUDE, root))
            .reason());
  }

  /** Scenario 12: a workspace labelled for another person of a reused handle is no one's. */
  @Test
  void aWorkspaceLabelledForAnotherPersonOfTheSameHandleIsNotTheirs() {
    assertEquals(
        "old is no one's workspace, so no agent starts in it.",
        assertThrows(Remedied.Io.class, () -> lane.admitAgent("acme", "old", CLAUDE, ada))
            .reason());
  }

  /** Scenario 8: a harness the project does not install is not started. */
  @Test
  void aHarnessTheProjectDoesNotInstallIsNotStarted() {
    var refused =
        assertThrows(Remedied.Io.class, () -> lane.admitAgent("harbor", "pier", CODEX, ada));

    assertEquals(
        "Codex CLI is not installed in harbor's workspaces; its agent installation lists Claude"
            + " Code.",
        refused.reason());
    assertEquals(
        Remedy.of(
            "An admin adds codex to harbor's agent install list and builds its template again."),
        refused.remedy());
  }

  /** Scenarios 6 and 7: no login stored, or none the default, with the remedy as data. */
  @Test
  void anOwnerWithNoDefaultLoginIsToldHowToStoreOrChooseOne() {
    var none = assertThrows(Remedied.Io.class, () -> lane.admitAgent("acme", "spare", CODEX, bob));
    assertEquals(
        "No Codex CLI login stored for bob, so a Codex CLI session cannot start in spare.",
        none.reason());
    assertEquals(
        List.of(new Remedy.Command(Remedy.Runs.SAIL, "agent login", List.of("codex"))),
        none.remedy().commands());
    assertEquals("login_not_stored", none.code(), "the app tells it apart by its code");

    var noDefault =
        assertThrows(Remedied.Io.class, () -> lane.admitAgent("acme", "dock", CODEX, ada));
    assertEquals(
        "ada has no default Codex CLI login, so a Codex CLI session cannot start in dock; stored:"
            + " home.",
        noDefault.reason());
    assertEquals(
        List.of(new Remedy.Command(Remedy.Runs.SAIL, "agent login use", List.of("codex", "home"))),
        noDefault.remedy().commands());
  }

  /** Q1: an agent that signs in inside needs no stored login, and is handed none. */
  @Test
  void anAgentThatSignsInInsideNeedsNoStoredLogin() throws IOException {
    assertEquals(
        Optional.empty(),
        lane.admitAgent("acme", "spare", new PtyMessage.Agent("codex", "full", true), bob));
    var foreign =
        assertThrows(
            Remedied.Io.class,
            () ->
                lane.admitAgent("acme", "dock", new PtyMessage.Agent("codex", "full", true), bob));
    assertTrue(foreign.reason().startsWith("dock is ada's workspace"), foreign.reason());
  }

  /** L4: the login reaches the workspace as the dev user's input, never as an argument. */
  @Test
  void aLoginIsHandedToTheWorkspacesDevUserOnItsInputAndWithdrawnTheSameWay() throws IOException {
    var file = SessionLogin.file("i-1");
    var lines = "CLAUDE_CODE_OAUTH_TOKEN=sk-ant-oat01-ada-work\n";

    lane.handOver("acme", "dock", file, lines, ada);
    lane.withdraw("acme-dock", file);

    assertEquals(
        List.of(
            ContainerExec.asDevUser(
                "acme-dock",
                "/",
                Map.of(),
                List.of("sh", "-c", SessionLogin.HAND, "sail-login", file)),
            ContainerExec.asDevUser(
                "acme-dock",
                "/",
                Map.of(),
                List.of("sh", "-c", SessionLogin.REMOVE, "sail-login", file))),
        box.arguments().stream().filter(argv -> argv.get(1).equals("exec")).toList());
    assertEquals(List.of(lines), box.inputs());
    assertFalse(box.invocations().stream().anyMatch(line -> line.contains("sk-ant")));
  }

  /**
   * Minor 3: the hand-over reads the workspace again under its lock and writes only while it is
   * still the asker's: one that changed hands after the admission (here, asked as another person)
   * is refused, and nothing reaches it.
   */
  @Test
  void aHandOverToAWorkspaceThatChangedHandsIsRefusedAndWritesNothing() {
    var refused =
        assertThrows(
            Remedied.Io.class,
            () -> lane.handOver("acme", "dock", SessionLogin.file("i-4"), "A=1\n", bob));

    assertEquals("workspace dock of acme changed hands while it was entered.", refused.reason());
    assertTrue(box.inputs().isEmpty(), "nothing was written");
    assertTrue(box.invocations().stream().noneMatch(line -> line.startsWith("incus exec")));
  }

  /** A hand-over the workspace refuses is a failure the host says in its own words. */
  @Test
  void aHandOverTheWorkspaceRefusesFails() {
    box.onFail("incus exec acme-dock", "mkdir: cannot create directory: File exists");

    assertEquals(
        "the hand-over ended 1",
        assertThrows(
                IOException.class,
                () -> lane.handOver("acme", "dock", SessionLogin.file("i-2"), "A=1\n", ada))
            .getMessage());
  }

  /**
   * A shell whose every command ends in {@code failure}: Incus slow, interrupted or unreachable.
   */
  private static ai.singlr.sail.engine.ShellExec failing(Exception failure) {
    return new ai.singlr.sail.engine.ShellExec() {
      @Override
      public Result exec(List<String> command)
          throws IOException, InterruptedException, java.util.concurrent.TimeoutException {
        return exec(command, null, null);
      }

      @Override
      public Result exec(List<String> command, Path workDir, java.time.Duration timeout)
          throws IOException, InterruptedException, java.util.concurrent.TimeoutException {
        switch (failure) {
          case IOException io -> throw io;
          case InterruptedException interrupted -> throw interrupted;
          case java.util.concurrent.TimeoutException late -> throw late;
          default -> throw new IllegalStateException(failure);
        }
      }

      @Override
      public Result execWithInput(List<String> command, String input, java.time.Duration timeout)
          throws IOException, InterruptedException, java.util.concurrent.TimeoutException {
        return exec(command, null, timeout);
      }

      @Override
      public boolean isDryRun() {
        return false;
      }
    };
  }

  private HostLanes.Pty laneOver(ai.singlr.sail.engine.ShellExec shell) {
    return new HostLanes.Pty(
        db,
        TestAuth.roles(db, new SyncConfig("main", null, "root", "box")),
        null,
        new BoxInstances(box),
        TestWorkspaces.over(box),
        project -> Optional.of(TestDefinitions.named(project)),
        handle -> WorkspaceOwner.onThisBox(fdes, handle, "root"),
        shell);
  }

  /**
   * A hand-over that does not end in time, or is interrupted, fails in the host's words, the
   * interrupt kept; a withdrawal that cannot run is said in the log and changes nothing else.
   */
  @Test
  void aHandOverIncusDoesNotFinishFailsAndAWithdrawalThatCannotRunIsOnlySaid() {
    var file = SessionLogin.file("i-3");
    assertEquals(
        "the hand-over did not end within 30 s",
        assertThrows(
                IOException.class,
                () ->
                    laneOver(failing(new java.util.concurrent.TimeoutException()))
                        .handOver("acme", "dock", file, "A=1\n", ada))
            .getMessage());
    assertEquals(
        "the hand-over was interrupted",
        assertThrows(
                IOException.class,
                () ->
                    laneOver(failing(new InterruptedException()))
                        .handOver("acme", "dock", file, "A=1\n", ada))
            .getMessage());
    assertTrue(Thread.interrupted(), "the interrupt is kept");

    laneOver(failing(new IOException("incus is gone"))).withdraw("acme-dock", file);
    laneOver(failing(new InterruptedException())).withdraw("acme-dock", file);
    assertTrue(Thread.interrupted(), "the interrupt is kept");
  }

  /** A project whose definition installs no harness says it lists none. */
  @Test
  void aProjectThatInstallsNoHarnessSaysItListsNone() {
    assertEquals(
        "Claude Code is not installed in acme's workspaces; its agent installation lists none.",
        assertThrows(Remedied.Io.class, () -> laneOver(box).admitAgent("acme", "dock", CLAUDE, ada))
            .reason());
  }
}
