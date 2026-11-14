/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.api;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import ai.singlr.sail.common.DateTimeUtils;
import ai.singlr.sail.config.Lane;
import ai.singlr.sail.config.ProjectDefinition;
import ai.singlr.sail.config.TestDefinitions;
import ai.singlr.sail.engine.AgentUnit;
import ai.singlr.sail.engine.LaunchEnv;
import ai.singlr.sail.engine.RunPlace;
import ai.singlr.sail.engine.ScriptedShellExecutor;
import ai.singlr.sail.engine.ShellExec;
import ai.singlr.sail.harness.Harnesses;
import ai.singlr.sail.identity.Acting;
import ai.singlr.sail.identity.ActingAs;
import ai.singlr.sail.store.DispatchGate;
import ai.singlr.sail.store.FdeStore;
import ai.singlr.sail.store.RunStore;
import ai.singlr.sail.store.SchemaManager;
import ai.singlr.sail.store.Sqlite;
import ai.singlr.sail.store.TestLogins;
import ai.singlr.sail.store.TestRuns;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The shared reservation seam and its best-effort cleanup: a claim returns a credential and prunes,
 * a prune or status probe that fails never fails the launch, and the run-store bookkeeping is a
 * silent no-op without a store.
 */
@ActingAs
class RunReservationTest {

  @TempDir Path tempDir;
  private Sqlite db;
  private RunStore runStore;
  private static final String RUN_ID = DateTimeUtils.newId().toString();
  private SessionYield sessionYield = SessionYield.NONE;

  private static final ProjectDefinition CONFIG =
      TestDefinitions.map(
          Map.of(
              "name",
              "acme",
              "repos",
              List.of(Map.of("url", "https://example.com/app.git", "path", "app"))));

  @BeforeEach
  void setUp() {
    db = Sqlite.open(tempDir.resolve("test.db"));
    new SchemaManager(db).migrate();
    runStore = new RunStore(db);
    TestLogins.store(db, "node");
  }

  @AfterEach
  void tearDown() {
    if (db != null) db.close();
  }

  private static ShellExec quietShell() {
    return new ShellExec() {
      @Override
      public ShellExec.Result exec(List<String> command) {
        return new ShellExec.Result(1, "", "");
      }

      @Override
      public ShellExec.Result exec(List<String> command, Path workDir, Duration timeout) {
        return new ShellExec.Result(1, "", "");
      }

      @Override
      public boolean isDryRun() {
        return false;
      }
    };
  }

  /** A container that answers, and whose unit manager says the run's unit has no process. */
  private static ShellExec answeringShell() {
    return new ShellExec() {
      @Override
      public ShellExec.Result exec(List<String> command) {
        return ScriptedShellExecutor.reachableContainer(String.join(" ", command))
            .orElseGet(() -> new ShellExec.Result(1, "", ""));
      }

      @Override
      public ShellExec.Result exec(List<String> command, Path workDir, Duration timeout) {
        return exec(command);
      }

      @Override
      public boolean isDryRun() {
        return false;
      }
    };
  }

  private static ShellExec throwingShell() {
    return new ShellExec() {
      @Override
      public ShellExec.Result exec(List<String> command) throws IOException {
        throw new IOException("container gone");
      }

      @Override
      public ShellExec.Result exec(List<String> command, Path workDir, Duration timeout)
          throws IOException {
        throw new IOException("container gone");
      }

      @Override
      public boolean isDryRun() {
        return false;
      }
    };
  }

  interface Ender {
    void end(List<String> sessions, String reason) throws IOException;
  }

  /** A seam with no lock to hold — a box with no host — that ends through {@code ender}. */
  private static SessionYield ending(Ender ender) {
    return new SessionYield() {
      @Override
      public Hold lock(String project) {
        return NO_HOLD;
      }

      @Override
      public void end(List<String> sessions, String reason) throws IOException {
        ender.end(sessions, reason);
      }
    };
  }

  private RunReservation reservation(ShellExec shell, RunStore store) {
    return new RunReservation(
        store,
        shell,
        DispatchOperations.Listener.NONE,
        sessionYield,
        RunReservation.Displaceable.NONE,
        new FdeStore(db),
        RunLauncher.Room.NONE);
  }

  private LaunchEnv reserve(RunReservation r) {
    return r.reserve(
        RUN_ID,
        "acme",
        "spec",
        "node",
        "node",
        "adhoc",
        List.of(),
        "codex",
        null,
        "task",
        AgentUnit.forRun(RUN_ID),
        CONFIG);
  }

  @Test
  void reserveClaimsTheRunAndReturnsItsCredentialWithThePersonsLogin() {
    var env = reserve(reservation(quietShell(), runStore));
    assertNotNull(env.runCredential());
    assertEquals(new RunStore.RunLogin("default", "api-key"), env.login());
    assertEquals(TestLogins.VALUE, env.stored().value());
    assertEquals("running", runStore.findById(RUN_ID).orElseThrow().status());
  }

  /** HL6: a spec run whose person stored no login is refused before anything is reserved. */
  @Test
  void aSpecRunWhosePersonStoredNoLoginIsRefusedBeforeAnyRowIsWritten() {
    var fdes = new FdeStore(db);
    fdes.logins().forget(fdes.byHandle("node").orElseThrow().id());
    var r = reservation(quietShell(), runStore);

    var refused = assertThrows(ApiException.class, () -> reserve(r));

    assertEquals(ErrorCode.LOGIN_NOT_STORED, refused.failure().errorCode());
    assertEquals(
        "No Codex CLI login stored for node, so a run of a spec cannot start: a login made by hand"
            + " in the project's container is not used by runs.",
        refused.getMessage());
    assertEquals(
        "Create an API key at platform.openai.com/api-keys, then sail agent login codex. Or:"
            + " Create a Codex access token at chatgpt.com/admin/access-tokens (ChatGPT Business"
            + " and Enterprise), then sail agent login codex --kind token.",
        refused.failure().remedy().plain());
    assertTrue(runStore.findById(RUN_ID).isEmpty(), "no row");
    assertThrows(
        ApiException.class,
        () ->
            r.reserveForReview(
                RUN_ID,
                "review-1",
                "acme",
                "spec",
                "node",
                Lane.REVIEW,
                List.of(),
                "codex",
                null,
                "task",
                AgentUnit.forRun(RUN_ID),
                CONFIG));
    assertTrue(runStore.findById(RUN_ID).isEmpty(), "no review row either");
  }

  /**
   * A refusal for want of a login is said in the spec's room once per cause, however often it is
   * met, and said again only after a run of the spec has started with a login since.
   */
  @Test
  void aMissingLoginIsToldToTheSpecsRoomWithItsRemedyEachTimeForTheRoomToSayOnce() {
    var said = new ArrayList<String>();
    var fdes = new FdeStore(db);
    var node = fdes.byHandle("node").orElseThrow().id();
    fdes.logins().forget(node);
    var r =
        new RunReservation(
            runStore,
            quietShell(),
            DispatchOperations.Listener.NONE,
            sessionYield,
            RunReservation.Displaceable.NONE,
            fdes,
            (spec, body) -> said.add(spec + ": " + body));

    assertThrows(ApiException.class, () -> reserve(r));
    assertThrows(ApiException.class, () -> reserve(r));

    assertEquals(2, said.size(), "no process keeps what it told: the room decides " + said);
    assertTrue(said.getFirst().startsWith("spec: No Codex CLI login stored for node"), said.get(0));
    assertTrue(said.getFirst().endsWith("then sail agent login codex --kind token."), said.get(0));
  }

  /** A run acting for no one on this box's roster has no stored login to start with. */
  @Test
  void aSpecRunForSomeoneNotOnTheRosterIsRefusedBeforeAnyRowIsWritten() {
    var refused =
        assertThrows(
            ApiException.class,
            () ->
                reservation(quietShell(), runStore)
                    .reserve(
                        RUN_ID,
                        "acme",
                        "spec",
                        "stranger",
                        "build",
                        List.of(),
                        "codex",
                        null,
                        "task",
                        AgentUnit.forRun(RUN_ID),
                        CONFIG));

    assertEquals(
        "No Codex CLI login stored for stranger, so a run of a spec cannot start: a login made by"
            + " hand in the project's container is not used by runs.",
        refused.getMessage());
    assertTrue(runStore.findById(RUN_ID).isEmpty());
  }

  /** A person with logins but none the default is told which to choose, not given one. */
  @Test
  void aSpecRunWhosePersonHasNoDefaultIsRefusedNamingTheLoginsStored() {
    var fdes = new FdeStore(db);
    var id = fdes.byHandle("node").orElseThrow().id();
    var codex = Harnesses.of("codex");
    fdes.logins().store(id, codex, "work", null, "sk-work");
    fdes.logins().remove(id, codex, "default");

    var refused =
        assertThrows(ApiException.class, () -> reserve(reservation(quietShell(), runStore)));

    assertEquals(
        "node has no default Codex CLI login, so a run of a spec cannot start; stored: work.",
        refused.getMessage());
    assertEquals(
        "Choose one: sail agent login use codex work.", refused.failure().remedy().plain());
    assertTrue(runStore.findById(RUN_ID).isEmpty());
  }

  /** A run that names no spec uses its container's login: nothing is stored for it, nor asked. */
  @Test
  void aRunThatNamesNoSpecStartsOnItsContainersLogin() {
    new FdeStore(db).logins().forget(new FdeStore(db).byHandle("node").orElseThrow().id());

    var env =
        reservation(quietShell(), runStore)
            .reserve(
                RUN_ID,
                "acme",
                "",
                "node",
                "adhoc",
                List.of(),
                "codex",
                null,
                "task",
                AgentUnit.forRun(RUN_ID),
                CONFIG);

    assertEquals(RunStore.RunLogin.CONTAINER, env.login());
    assertEquals(null, env.stored());
    assertEquals(
        LaunchEnv.RUN_CREDENTIAL + "=" + env.runCredential() + "\n",
        env.lines(),
        "its file carries its credential alone");
  }

  @Test
  void anOverlappingClaimIsRefused() {
    var r = reservation(quietShell(), runStore);
    reserve(r);
    var second = reservation(quietShell(), runStore);
    var ex =
        assertThrows(
            ApiException.class,
            () ->
                second.reserve(
                    DateTimeUtils.newId().toString(),
                    "acme",
                    "spec",
                    "node",
                    "node",
                    "adhoc",
                    List.of(),
                    "codex",
                    null,
                    "task",
                    AgentUnit.forRun(DateTimeUtils.newId().toString()),
                    CONFIG));
    assertEquals(ErrorCode.AGENT_ALREADY_RUNNING, ex.failure().errorCode());
  }

  private String completedRun(String specId, List<String> repos) {
    return Acting.system(
        () -> {
          var id = DateTimeUtils.newId().toString();
          runStore.reserveDispatch(
              id, "acme", specId, "node", "build", repos, "codex", null, "task", "log", "unit");
          runStore.finish(id, "running", "completed", 0, null);
          return id;
        });
  }

  private LaunchEnv reserveBuild(String specId, String role, List<String> repos) {
    return reservation(quietShell(), runStore)
        .reserve(
            RUN_ID,
            "acme",
            specId,
            "node",
            "node",
            role,
            repos,
            "codex",
            null,
            "task",
            AgentUnit.forRun(RUN_ID),
            CONFIG);
  }

  @Test
  void aClaimEndsTheResumeSessionsItDisplacesAndSparesDisjointOnes() {
    var overlapping = completedRun("old-spec", List.of("app"));
    completedRun("other-spec", List.of("web"));
    var ended = new ArrayList<String>();
    var reasons = new ArrayList<String>();
    sessionYield =
        ending(
            (sessions, reason) -> {
              ended.addAll(sessions);
              reasons.add(reason);
            });

    reserveBuild(null, DispatchGate.ROOM_FULL_ROLE, List.of("app"));

    assertEquals(
        List.of(SessionYield.resumeSession(overlapping)),
        ended,
        "only the conversation over the reserved repo yields; the disjoint one lives on");
    assertEquals(List.of("yielded to dispatch " + RUN_ID), reasons);
  }

  @Test
  void aSpecsClaimDisplacesNoConversationATerminalOpensInTheProjectsContainer() {
    completedRun("old-spec", List.of("app"));
    sessionYield =
        ending((sessions, reason) -> fail("a spec's run lives in its own workspace: " + sessions));

    assertNotNull(reserveBuild("old-spec", "build", List.of("app")));
  }

  @Test
  void aReadOnlyRoomWakeDisplacesNoConversation() {
    completedRun("old-spec", List.of("app"));
    sessionYield =
        ending((sessions, reason) -> fail("a read-only wake reserves nothing and ends nothing"));

    assertNotNull(reserveBuild("old-spec", DispatchGate.ROOM_ROLE, List.of()));
  }

  @Test
  void aWholeContainerClaimDisplacesEveryConversationAndAHostFailureNeverFailsTheClaim() {
    completedRun("old-spec", List.of("app"));
    sessionYield =
        ending(
            (sessions, reason) -> {
              throw new IOException("Session 'resume-x' belongs to mady.");
            });

    var credential = reserve(reservation(quietShell(), runStore));

    assertNotNull(credential, "a host that refuses is a warning, never a failed launch");
    assertEquals("running", runStore.findById(RUN_ID).orElseThrow().status());
  }

  @Test
  void theClaimAndTheYieldRunUnderTheProjectLockWhichEveryExitReleases() {
    completedRun("old-spec", List.of("app"));
    var held = new AtomicBoolean();
    var locked = new ArrayList<String>();
    var claimedWhileHeld = new AtomicBoolean();
    sessionYield =
        new SessionYield() {
          @Override
          public Hold lock(String project) {
            locked.add(project);
            held.set(true);
            return () -> held.set(false);
          }

          @Override
          public void end(List<String> sessions, String reason) {
            claimedWhileHeld.set(
                held.get() && "running".equals(runStore.findById(RUN_ID).orElseThrow().status()));
          }
        };

    reserveBuild(null, DispatchGate.ROOM_FULL_ROLE, List.of("app"));
    assertEquals(List.of("acme"), locked);
    assertTrue(claimedWhileHeld.get(), "the row lands and the yield runs under one hold");
    assertFalse(held.get(), "released once the yield is done");

    assertThrows(ApiException.class, () -> reserveBuild(null, "adhoc", List.of()));
    assertFalse(held.get(), "a refused claim releases the lock too");
  }

  @Test
  void anUnlockableProjectRefusesTheClaimBeforeAnyRowIsWritten() {
    sessionYield =
        new SessionYield() {
          @Override
          public Hold lock(String project) throws IOException {
            throw new IOException("locks directory is not writable");
          }

          @Override
          public void end(List<String> sessions, String reason) {
            fail("nothing is claimed, so nothing yields");
          }
        };

    var ex = assertThrows(ApiException.class, () -> reserve(reservation(quietShell(), runStore)));

    assertEquals(ErrorCode.COMMAND_FAILED, ex.failure().errorCode());
    assertTrue(runStore.findById(RUN_ID).isEmpty(), "no run row without the lock");
  }

  @Test
  void theDefaultSeamEndsNothingAndTheClaimStands() {
    completedRun("old-spec", List.of("app"));
    assertNotNull(reserve(reservation(quietShell(), runStore)));
  }

  @Test
  void aConversationYieldsByTheDispatchGateRulesHoldingItsRunsRepos() {
    var row = runStore.findById(completedRun("spec", List.of("web"))).orElseThrow();
    assertFalse(
        RunReservation.displaces("spec", "build", List.of("web"), row),
        "a spec's build works in the spec's workspace, not the container the terminal is in");
    assertFalse(
        RunReservation.displaces(null, DispatchGate.ROOM_FULL_ROLE, List.of("app"), row),
        "a run of the container over a disjoint repo leaves it alone");
    assertTrue(
        RunReservation.displaces(null, DispatchGate.ROOM_FULL_ROLE, List.of("web"), row),
        "a full chat turn claims its repos like a build");
    assertTrue(
        RunReservation.displaces(null, "adhoc", List.of(), row),
        "a whole-container claim displaces everything");
  }

  @Test
  void aPruneFailureNeverFailsTheReservation() {
    var credential = reserve(reservation(throwingShell(), runStore));
    assertNotNull(credential, "a best-effort prune that throws must not fail the claim");
  }

  @Test
  void anUnprobeableAgentIsTreatedAsLiveSoTheRunIsNotReleased() {
    reserve(reservation(quietShell(), runStore));

    reservation(throwingShell(), runStore)
        .releaseIfAbsent(RUN_ID, RunPlace.of("acme", null), AgentUnit.forRun(RUN_ID));

    assertEquals(
        "running",
        runStore.findById(RUN_ID).orElseThrow().status(),
        "an unprobeable agent is assumed live, so the reservation is kept");
  }

  @Test
  void anAgentAContainerThatRunsNoCommandCannotSpeakForIsTreatedAsLive() {
    reserve(reservation(quietShell(), runStore));

    reservation(quietShell(), runStore)
        .releaseIfAbsent(RUN_ID, RunPlace.of("acme", null), AgentUnit.forRun(RUN_ID));

    assertEquals(
        "running",
        runStore.findById(RUN_ID).orElseThrow().status(),
        "every command failing is what a live agent in an unreachable container reads as");
  }

  @Test
  void anAbsentAgentReleasesTheReservationAndItsLaunchFileLeavesTheContainer() {
    reserve(reservation(quietShell(), runStore));
    var commands = new ArrayList<String>();
    var shell = answeringShell();
    ShellExec recording =
        new ShellExec() {
          @Override
          public ShellExec.Result exec(List<String> command)
              throws IOException, InterruptedException, TimeoutException {
            commands.add(String.join(" ", command));
            return shell.exec(command);
          }

          @Override
          public ShellExec.Result exec(List<String> command, Path workDir, Duration timeout)
              throws IOException, InterruptedException, TimeoutException {
            return exec(command);
          }

          @Override
          public boolean isDryRun() {
            return false;
          }
        };

    reservation(recording, runStore)
        .releaseIfAbsent(RUN_ID, RunPlace.of("acme", null), AgentUnit.forRun(RUN_ID));

    assertEquals(
        "failed",
        runStore.findById(RUN_ID).orElseThrow().status(),
        "a container that answers and names no process for the run frees it");
    assertTrue(
        commands.getLast().endsWith("rm -f " + AgentUnit.forRun(RUN_ID).launchEnvPath()),
        commands.toString());
  }

  @Test
  void aLaunchFileThatCannotBeRemovedNowIsLeftToTheSweepAndTheReleaseStands() {
    reserve(reservation(quietShell(), runStore));
    var shell = answeringShell();
    ShellExec refusingRemoval =
        new ShellExec() {
          @Override
          public ShellExec.Result exec(List<String> command)
              throws IOException, InterruptedException, TimeoutException {
            if (command.contains("rm")) {
              throw new IOException("container gone");
            }
            return shell.exec(command);
          }

          @Override
          public ShellExec.Result exec(List<String> command, Path workDir, Duration timeout)
              throws IOException, InterruptedException, TimeoutException {
            return exec(command);
          }

          @Override
          public boolean isDryRun() {
            return false;
          }
        };

    assertDoesNotThrow(
        () ->
            reservation(refusingRemoval, runStore)
                .releaseIfAbsent(RUN_ID, RunPlace.of("acme", null), AgentUnit.forRun(RUN_ID)));

    assertEquals("failed", runStore.findById(RUN_ID).orElseThrow().status());
  }

  @Test
  void releaseWithoutARunStoreIsASilentNoOp() {
    assertDoesNotThrow(
        () ->
            new RunReservation(
                    null,
                    quietShell(),
                    DispatchOperations.Listener.NONE,
                    SessionYield.NONE,
                    RunReservation.Displaceable.NONE,
                    null,
                    RunLauncher.Room.NONE)
                .releaseIfAbsent(RUN_ID, RunPlace.of("acme", null), AgentUnit.forRun(RUN_ID)));
  }

  @Test
  void aStoreFailureDuringCleanupIsSwallowed() {
    reserve(reservation(quietShell(), runStore));
    db.close();

    assertDoesNotThrow(
        () ->
            reservation(quietShell(), runStore)
                .releaseIfAbsent(RUN_ID, RunPlace.of("acme", null), AgentUnit.forRun(RUN_ID)),
        "bookkeeping must never propagate a store error out of the launch cleanup");
    assertFalse(db == null);
  }

  /** A live member's run of {@code specId} over {@code repos}, as {@code LiveMembers} reserves. */
  private String liveMember(String specId, List<String> repos) {
    var id = DateTimeUtils.newId().toString();
    Acting.system(
        () ->
            runStore.reserveDispatch(
                id,
                "acme",
                specId,
                "node",
                "room-full",
                repos,
                "claude-code",
                null,
                "task",
                AgentUnit.live(id).logPath(),
                AgentUnit.live(id).unitName()));
    return id;
  }

  private RunReservation displacing(RunReservation.Displaceable displaceable) {
    return new RunReservation(
        runStore,
        quietShell(),
        DispatchOperations.Listener.NONE,
        sessionYield,
        displaceable,
        new FdeStore(db),
        RunLauncher.Room.NONE);
  }

  private LaunchEnv claim(RunReservation reservation, String role) {
    return reservation.reserve(
        RUN_ID,
        "acme",
        "spec",
        "node",
        role,
        List.of("app"),
        "codex",
        null,
        "task",
        AgentUnit.forRun(RUN_ID),
        CONFIG);
  }

  @Test
  void aWorkingClaimEndsTheLiveMemberThatHoldsItAndLands() {
    var member = liveMember("spec", List.of("app"));
    var asked = new ArrayList<String>();

    var credential =
        claim(
            displacing(
                (runId, why) -> {
                  asked.add(runId + ": " + why);
                  return Acting.system(
                      () -> TestRuns.transition(runStore, runId, "running", "stopped"));
                }),
            "build");

    assertNotNull(credential);
    assertEquals(List.of(member + ": displaced by run " + RUN_ID + " claiming its repos"), asked);
    assertEquals("running", runStore.findById(RUN_ID).orElseThrow().status());
  }

  @Test
  void aClaimEndsThreeMembersInTurnAndIsThenRefusedAsAnyHeldClaimIs() {
    var first = liveMember("spec", List.of("app"));
    var ended = new ArrayList<String>();

    var refused =
        assertThrows(
            ApiException.class,
            () ->
                claim(
                    displacing(
                        (runId, why) -> {
                          ended.add(runId);
                          Acting.system(
                              () -> TestRuns.transition(runStore, runId, "running", "stopped"));
                          liveMember("spec", List.of("app"));
                          return true;
                        }),
                    "adhoc"));

    assertEquals(RunReservation.MAX_DISPLACED, ended.size());
    assertEquals(first, ended.getFirst());
    assertEquals(ErrorCode.AGENT_ALREADY_RUNNING, refused.failure().errorCode());
    assertTrue(runStore.findById(RUN_ID).isEmpty(), "nothing was reserved");
  }

  @Test
  void aMemberThatDoesNotEndLeavesTheClaimHeld() {
    liveMember("spec", List.of("app"));
    var asked = new ArrayList<String>();

    assertThrows(
        ApiException.class,
        () ->
            claim(
                displacing(
                    (runId, why) -> {
                      asked.add(runId);
                      return false;
                    }),
                "fix"));

    assertEquals(1, asked.size(), "asked once, and its answer is taken");
  }

  @Test
  void aChatLanesClaimAndAClaimAnotherRunHoldsDisplaceNoOne() {
    liveMember("spec", List.of("app"));
    var asked = new ArrayList<String>();
    RunReservation.Displaceable recording =
        (runId, why) -> {
          asked.add(runId);
          return true;
        };

    assertThrows(ApiException.class, () -> claim(displacing(recording), "room-full"));
    Acting.system(
        () ->
            runStore
                .listForProject("acme")
                .forEach(run -> TestRuns.transition(runStore, run.id(), "running", "stopped")));
    Acting.system(
        () ->
            runStore.reserveDispatch(
                DateTimeUtils.newId().toString(),
                "acme",
                "spec",
                "node",
                "build",
                List.of("app"),
                "codex",
                null,
                "task",
                "log",
                "sail-agent-x"));
    assertThrows(ApiException.class, () -> claim(displacing(recording), "build"));

    assertEquals(List.of(), asked, "a conversation never ends another, and a build is never ended");
    assertFalse(RunReservation.Displaceable.NONE.endHolder("run", "why"));
  }
}
