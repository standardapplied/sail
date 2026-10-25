/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.sail.config.SpecStatus;
import ai.singlr.sail.engine.ContainerSailSetup;
import ai.singlr.sail.engine.ShellExec;
import ai.singlr.sail.engine.WatcherSpawner;
import ai.singlr.sail.identity.Acting;
import ai.singlr.sail.identity.ActingAs;
import ai.singlr.sail.identity.Actor;
import ai.singlr.sail.identity.ProjectScope;
import ai.singlr.sail.store.FdeStore;
import ai.singlr.sail.store.HeldProjects;
import ai.singlr.sail.store.MessageStore;
import ai.singlr.sail.store.ReviewStore;
import ai.singlr.sail.store.RunStore;
import ai.singlr.sail.store.SchemaManager;
import ai.singlr.sail.store.SpecStore;
import ai.singlr.sail.store.Sqlite;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * The build lane's edge behaviors, each asserted through a real dispatch: a project with no agent
 * block is refused loud before any claim; a repo that pins its own base branch forks the work
 * branch off that pin rather than {@code origin/HEAD}; a shell failure while preparing the checkout
 * surfaces as {@code COMMAND_FAILED}; and a room message rendered into the prompt is recorded on
 * the run's delivery ledger at launch.
 */
@ActingAs
class BuildDispatchCoverageTest {

  private static final String HANDLE = "me";
  private static final Actor ADMIN = Actor.cliOperator(HANDLE);
  private static final Actor ONLY_ACME = ADMIN.withScope(ProjectScope.of(List.of("acme")));
  private static final String REPO = "git -C /home/dev/workspace/app";

  private static final String RUNNING_JSON =
      """
      [
        {
          "name": "acme",
          "status": "Running",
          "state": {
            "network": {
              "eth0": {
                "addresses": [
                  {"family": "inet", "address": "10.0.0.42", "scope": "global"}
                ]
              }
            }
          }
        }
      ]
      """;

  private static final String YAML =
      """
      name: acme
      ssh:
        user: dev
      repos:
        - url: https://github.com/acme/app.git
          path: app
      agent:
        type: claude-code
        auto_branch: true
        branch_prefix: sail/
      """;

  private static final String YAML_PINNED_BASE =
      """
      name: acme
      ssh:
        user: dev
      repos:
        - url: https://github.com/acme/app.git
          path: app
          branch: develop
      agent:
        type: claude-code
        auto_branch: true
        branch_prefix: sail/
      """;

  private static final String YAML_NO_AGENT =
      """
      name: acme
      ssh:
        user: dev
      repos:
        - url: https://github.com/acme/app.git
          path: app
      """;

  @TempDir Path tempDir;

  @Test
  void aDispatchWithNoAgentConfiguredFailsLoud() throws IOException {
    var db = seedDb();
    var ops = ops(happyPath(), YAML_NO_AGENT, db);

    var thrown = assertThrows(ApiException.class, () -> dispatch(ops));

    assertEquals(ErrorCode.AGENT_NOT_CONFIGURED, thrown.failure().errorCode());
  }

  @ParameterizedTest
  @CsvSource({"false,pending", "true,review"})
  void aDispatchWhoseLastRowFailsLeavesNoRunAndNoClaim(boolean restart, String status)
      throws IOException {
    var db = seedDb();
    var specs = new SpecStore(db);
    Acting.system(
        () -> {
          specs.updateStatus("auth", SpecStatus.fromWire(status));
          new ReviewStore(db).createReview("auth", 1);
        });
    db.execute(
        """
        CREATE TRIGGER refuse_the_supersede BEFORE UPDATE ON reviews
        BEGIN SELECT RAISE(ABORT, 'the last row is refused'); END""");
    var shell = happyPath();
    var ops = ops(shell, YAML, db);

    var thrown =
        assertThrows(
            RuntimeException.class,
            () ->
                Actor.call(
                    ADMIN,
                    () ->
                        ops.dispatch(
                            "acme",
                            new DispatchOperations.Request(
                                "auth", "background", false, null, restart),
                            HANDLE)));

    assertEquals("the last row is refused (sqlite error 19)", rootCause(thrown).getMessage());
    assertEquals(SpecStatus.fromWire(status), specs.findById("auth").orElseThrow().status());
    assertNull(specs.findById("auth").orElseThrow().branch(), "no claim");
    assertEquals(List.of(), new RunStore(db).listForProject("acme"), "no reservation");
    assertFalse(ranContaining(shell, "checkout"), "and nothing started for it");
  }

  private static Throwable rootCause(Throwable thrown) {
    var cause = thrown;
    while (cause.getCause() != null) {
      cause = cause.getCause();
    }
    return cause;
  }

  @Test
  void aFreshBranchForksOffThePinnedBaseBranch() throws IOException {
    var shell =
        happyPath()
            .on(REPO + " rev-parse --verify --quiet refs/remotes/origin/develop", "")
            .on(REPO + " checkout -b sail/auth origin/develop", "");
    var ops = ops(shell, YAML_PINNED_BASE, seedDb());

    assertInstanceOf(DispatchOperations.Dispatched.class, dispatch(ops));

    assertTrue(
        ranContaining(shell, REPO + " fetch --quiet origin develop"),
        "the pinned base is fetched from origin before the branch is cut");
    assertTrue(
        ranContaining(shell, REPO + " checkout -b sail/auth origin/develop"),
        "the fresh branch forks off the repo's pinned base, not origin/HEAD");
  }

  @Test
  void aCheckoutTheContainerRefusesFailsWithBranchCreateFailed() throws IOException {
    var shell =
        happyPath()
            .on(REPO + " rev-parse --verify --quiet refs/remotes/origin/main", "")
            .refuse(
                REPO + " checkout -b sail/auth", "fatal: cannot lock ref 'refs/heads/sail/auth'");
    var ops = ops(shell, YAML, seedDb());

    var thrown = assertThrows(ApiException.class, () -> dispatch(ops));

    assertEquals(ErrorCode.BRANCH_CREATE_FAILED, thrown.failure().errorCode());
    assertEquals(
        "Failed to create branch 'sail/auth' in repo 'app':"
            + " fatal: cannot lock ref 'refs/heads/sail/auth'",
        thrown.getMessage());
  }

  @Test
  void aDefaultBranchAnsweredWithoutTheOriginPrefixIsForkedFromAsIs() throws IOException {
    var shell =
        happyPath()
            .on(REPO + " rev-parse --abbrev-ref origin/HEAD", "main\n")
            .on(REPO + " rev-parse --verify --quiet refs/remotes/origin/main", "")
            .on(REPO + " checkout -b sail/auth origin/main", "");
    var ops = ops(shell, YAML, seedDb());

    assertInstanceOf(DispatchOperations.Dispatched.class, dispatch(ops));

    assertTrue(ranContaining(shell, REPO + " checkout -b sail/auth origin/main"));
  }

  @Test
  void aShellFailureWhilePreparingTheCheckoutFailsWithCommandFailed() throws IOException {
    var shell = happyPath().throwOn("test -d /home/dev/workspace/app/.git");
    var ops = ops(shell, YAML, seedDb());

    var thrown = assertThrows(ApiException.class, () -> dispatch(ops));

    assertEquals(ErrorCode.COMMAND_FAILED, thrown.failure().errorCode());
  }

  @Test
  void aRoomMessageRenderedIntoThePromptIsMarkedDeliveredAtLaunch() throws IOException {
    var shell =
        happyPath()
            .on(REPO + " rev-parse --verify --quiet refs/remotes/origin/main", "")
            .on(REPO + " checkout -b sail/auth origin/main", "");
    var db = seedDb();
    var messages = new MessageStore(db);
    var seeded = messages.append("auth", HANDLE, "Please start with the login form.", null);
    var runStore = new RunStore(db);
    var ops = ops(shell, YAML, db, runStore).useMessages(messages);

    var outcome = dispatch(ops);

    var dispatched = assertInstanceOf(DispatchOperations.Dispatched.class, outcome);
    assertTrue(
        runStore.deliveredMessageIds(dispatched.runId()).contains(seeded.id()),
        "the message rendered into the dispatch prompt is recorded as delivered on the run");
  }

  @Test
  void aScopedCallerDispatchesNoSpecWhoseEarlierReviewIsFiledOutsideItsScope() throws IOException {
    var db = seedDb();
    var specs = new SpecStore(db);
    var reviews = new ReviewStore(db);
    var left = beforeItMoved(db, () -> reviews.createReview("auth", 1));
    var shell =
        happyPath()
            .on(REPO + " rev-parse --verify --quiet refs/remotes/origin/main", "")
            .on(REPO + " checkout -b sail/auth origin/main", "");
    var ops = ops(shell, YAML, db);

    var thrown = assertThrows(ApiException.class, () -> dispatch(ops, ONLY_ACME));

    assertEquals(ErrorCode.CONFLICT, thrown.failure().errorCode());
    assertEquals(
        "Dispatching spec 'auth' would close a review outside your project scope.",
        thrown.getMessage());
    assertEquals("Ask an admin to dispatch it.", thrown.failure().remedy().plain());
    assertFalse(reviews.findReview(left).orElseThrow().superseded());
    assertEquals(SpecStatus.PENDING, specs.findById("auth").orElseThrow().status(), "no claim");
    assertEquals(List.of(), new RunStore(db).listForProject("acme"), "no reservation");
    assertFalse(ranContaining(shell, "checkout"), "and nothing started for it");

    assertInstanceOf(DispatchOperations.Dispatched.class, dispatch(ops, ADMIN));
    assertTrue(
        reviews.findReview(left).orElseThrow().superseded(), "an admin's dispatch closes it");
  }

  @Test
  void aDispatchNamingNoSpecPassesOverOneWhoseEarlierReviewIsFiledOutsideTheCallersScope()
      throws IOException {
    var db = seedDb();
    var specs = new SpecStore(db);
    var reviews = new ReviewStore(db);
    Acting.system(
        () -> {
          Acting.as("me", () -> specs.create(spec("login", "acme")));
          specs.setContent("login", "Do login", "");
          return null;
        });
    var left = beforeItMoved(db, () -> reviews.createReview("auth", 1));
    var shell =
        happyPath()
            .on(REPO + " rev-parse --verify --quiet refs/remotes/origin/main", "")
            .on(REPO + " checkout -b sail/login origin/main", "");
    var ops = ops(shell, YAML, db);

    var outcome =
        Actor.call(
            ONLY_ACME,
            () ->
                ops.dispatch(
                    "acme",
                    new DispatchOperations.Request(null, "background", false, null, false),
                    HANDLE));

    var dispatched = assertInstanceOf(DispatchOperations.Dispatched.class, outcome);
    assertEquals("login", dispatched.taskSpec().id(), "the next ready spec the caller may close");
    assertFalse(reviews.findReview(left).orElseThrow().superseded());
    assertEquals(SpecStatus.PENDING, specs.findById("auth").orElseThrow().status());
  }

  @Test
  void aScopedCallersPromptCarriesNoTalkFiledOutsideItsScope() throws IOException {
    var db = seedDb();
    var messages = new MessageStore(db);
    var reviews = new ReviewStore(db);
    var left =
        beforeItMoved(
            db,
            () -> {
              reviews.createReview("auth", 1);
              reviews.supersedeForSpec("auth");
              return messages.append("auth", "bea", "Said where the spec was before.", null);
            });
    var here = messages.append("auth", HANDLE, "Please start with the login form.", null);
    var runStore = new RunStore(db);
    var shell =
        happyPath()
            .on(REPO + " rev-parse --verify --quiet refs/remotes/origin/main", "")
            .on(REPO + " checkout -b sail/auth origin/main", "");
    var ops = ops(shell, YAML, db, runStore).useMessages(messages);

    var dispatched =
        assertInstanceOf(DispatchOperations.Dispatched.class, dispatch(ops, ONLY_ACME));

    assertTrue(dispatched.task().contains(here.body()), dispatched.task());
    assertFalse(dispatched.task().contains(left.body()), dispatched.task());
    assertEquals(List.of(here.id()), List.copyOf(runStore.deliveredMessageIds(dispatched.runId())));
  }

  private Sqlite seedDb() {
    return Acting.system(
        () -> {
          var db = Sqlite.open(tempDir.resolve("sail.db"));
          new SchemaManager(db).migrate();
          HeldProjects.hold(db, "acme");
          var specStore = new SpecStore(db);
          Acting.as("me", () -> specStore.create(auth("acme")));
          specStore.setContent("auth", "Do auth", "");
          new FdeStore(db).add(HANDLE, null, null, "admin");
          return db;
        });
  }

  private static SpecStore.SpecRow auth(String project) {
    return spec("auth", project);
  }

  private static SpecStore.SpecRow spec(String id, String project) {
    return new SpecStore.SpecRow(
        id,
        project,
        "Add " + id,
        SpecStatus.PENDING,
        HANDLE,
        null,
        null,
        null,
        null,
        0,
        "me",
        null,
        null,
        "me",
        List.of(),
        List.of());
  }

  /** Runs {@code written} while the spec is in another project, as a spec that moved here was. */
  private static <T> T beforeItMoved(Sqlite db, Supplier<T> written) {
    return Acting.system(
        () -> {
          var specs = new SpecStore(db);
          specs.update(auth("elsewhere"));
          var left = written.get();
          specs.update(auth("acme"));
          specs.setContent("auth", "Do auth", "");
          return left;
        });
  }

  private DispatchOperations ops(RecordingShell shell, String yamlBody, Sqlite db)
      throws IOException {
    return ops(shell, yamlBody, db, new RunStore(db));
  }

  private DispatchOperations ops(
      RecordingShell shell, String yamlBody, Sqlite db, RunStore runStore) throws IOException {
    var yaml = tempDir.resolve("sail.yaml");
    Files.writeString(yaml, yamlBody);
    return new DispatchOperations(
        shell,
        TestProjects.reading(yaml),
        new SpecStore(db),
        new ReviewStore(db),
        runStore,
        new FdeStore(db),
        new CopyOnWriteArrayList<Event>()::add,
        new WatcherSpawner(happyPath(), (command, logPath) -> 4242L),
        (project, config) -> "",
        command -> 0,
        DispatchOperations.Listener.NONE,
        SessionYield.NONE);
  }

  private static DispatchOperations.Outcome dispatch(DispatchOperations ops) {
    return dispatch(ops, ADMIN);
  }

  private static DispatchOperations.Outcome dispatch(DispatchOperations ops, Actor caller) {
    return Actor.call(
        caller,
        () ->
            ops.dispatch(
                "acme",
                new DispatchOperations.Request("auth", "background", false, null, false),
                HANDLE));
  }

  private static RecordingShell happyPath() {
    return new RecordingShell()
        .on("incus list ^acme$", RUNNING_JSON)
        .on("mkdir -p /home/dev/.sail", "")
        .on("printf '%s'", "")
        .on("test -d /home/dev/workspace/app/.git", "")
        .on(REPO + " rev-parse --abbrev-ref origin/HEAD", "origin/main\n")
        .on(REPO + " fetch --quiet origin main", "")
        .on(REPO + " fetch --quiet origin develop", "")
        .on("claude", "");
  }

  private static boolean ranContaining(RecordingShell shell, String fragment) {
    return shell.executed.stream().anyMatch(command -> command.contains(fragment));
  }

  private static final class RecordingShell implements ShellExec {
    private final Map<String, Result> scripts = new LinkedHashMap<>();
    private final List<String> executed = new ArrayList<>();
    private String throwPattern;

    RecordingShell() {
      on("incus config device add", "");
      on("cat " + ContainerSailSetup.STAMP_PATH, ContainerSailSetup.fingerprint());
    }

    RecordingShell on(String pattern, String stdout) {
      scripts.put(pattern, new Result(0, stdout, ""));
      return this;
    }

    RecordingShell refuse(String pattern, String stderr) {
      scripts.put(pattern, new Result(1, "", stderr));
      return this;
    }

    RecordingShell throwOn(String pattern) {
      this.throwPattern = pattern;
      return this;
    }

    @Override
    public Result exec(List<String> command) throws IOException {
      var joined = String.join(" ", command);
      executed.add(joined);
      if (throwPattern != null && joined.contains(throwPattern)) {
        throw new IOException("shell unavailable for " + joined);
      }
      for (var entry : scripts.entrySet()) {
        if (joined.contains(entry.getKey())) {
          return entry.getValue();
        }
      }
      return new Result(1, "", "no script for " + joined);
    }

    @Override
    public Result exec(List<String> command, Path workDir, Duration timeout) throws IOException {
      return exec(command);
    }

    @Override
    public boolean isDryRun() {
      return false;
    }
  }
}
