/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.commands;

import ai.singlr.sail.config.TestDefinitions;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.sail.api.SyncRequest;
import ai.singlr.sail.common.DateTimeUtils;
import ai.singlr.sail.config.SpecStatus;
import ai.singlr.sail.config.SyncConfig;
import ai.singlr.sail.engine.PipedSyncChannel;
import ai.singlr.sail.engine.ProjectImporter;
import ai.singlr.sail.engine.ShellExec;
import ai.singlr.sail.engine.SyncOperations;
import ai.singlr.sail.identity.Acting;
import ai.singlr.sail.identity.Actor;
import ai.singlr.sail.identity.ProjectScope;
import ai.singlr.sail.identity.Role;
import ai.singlr.sail.store.AuthSessionStore;
import ai.singlr.sail.store.BoxDatabase;
import ai.singlr.sail.store.FdeStore;
import ai.singlr.sail.store.ProjectStore;
import ai.singlr.sail.store.RunStore;
import ai.singlr.sail.store.SchemaManager;
import ai.singlr.sail.store.SpecStore;
import ai.singlr.sail.store.Sqlite;
import ai.singlr.sail.store.SyncScope;
import ai.singlr.sail.sync.SyncDatabase;
import ai.singlr.sail.sync.SyncTransitionSink;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A node's whole round after main changes its FDE's scope: main's {@code _sync} server answers a
 * real session of {@code nia}, and the node's Incus is a fake that holds containers, each Sail's or
 * not. What the box holds after the round — rows, roster, directories and containers — is what the
 * new scope admits.
 */
class ScopeChangeRoundTest {

  private static final String WHY = " — main no longer grants this box the project";

  @TempDir Path tempDir;
  private SyncDatabase main;
  private Sqlite mainDb;
  private Sqlite nodeDb;
  private FdeStore fdes;
  private Path projectsDir;
  private Incus incus;
  private SyncOperations node;
  private final PrintStream originalErr = System.err;

  @BeforeEach
  void setUp() {
    var mainPath = tempDir.resolve("main.db");
    try (var db = Sqlite.open(mainPath)) {
      BoxDatabase.prepare(db);
    }
    main = SyncDatabase.converge(mainPath, "main");
    mainDb = main.db();
    fdes = new FdeStore(mainDb);
    fdes.add("ada", "Ada", "ada@x.dev", "admin");
    var nia = fdes.add("nia", "Nia", "nia@x.dev", "member");
    fdes.add("yan", "Yan", "yan@x.dev", "member");
    fdes.restrict("yan");
    Acting.system(
        () -> {
          var projects = new ProjectStore(mainDb);
          projects.upsert(TestDefinitions.named("acme"));
          projects.upsert(TestDefinitions.named("beta"));
          var specs = new SpecStore(mainDb);
          specs.create(spec("a1", "acme"));
          specs.create(spec("b1", "beta"));
        });
    fdes.grant("yan", "beta");
    var token = new AuthSessionStore(mainDb).create(nia.id(), Duration.ofHours(1)).token();
    nodeDb = Sqlite.open(tempDir.resolve("node.db"));
    new SchemaManager(nodeDb).migrate();
    projectsDir = tempDir.resolve("projects");
    incus = new Incus();
    node =
        new SyncOperations(
            nodeDb,
            "node",
            projectsDir,
            () -> new SyncConfig(SyncConfig.ROLE_NODE, "sail@main", "nia", "node-box"),
            target ->
                PipedSyncChannel.to(
                    (in, out) ->
                        SyncServerCommand.serve(
                            main,
                            "main",
                            token,
                            in,
                            out,
                            SyncTransitionSink.NONE,
                            () -> new SyncConfig(SyncConfig.ROLE_MAIN, null, "ada", "main"))),
            event -> {},
            SyncOperations.PLAIN_NOTICES,
            incus);
    node.prepare();
  }

  @AfterEach
  void tearDown() {
    System.setErr(originalErr);
    nodeDb.close();
    main.close();
  }

  /** One round of the node, answering what it printed to stderr. */
  private List<String> round() throws Exception {
    var err = new ByteArrayOutputStream();
    System.setErr(new PrintStream(err, true, StandardCharsets.UTF_8));
    try {
      Actor.call(
          new Actor("nia", Role.MEMBER, Actor.Lane.CLI), () -> node.sync(new SyncRequest(null)));
    } finally {
      System.setErr(originalErr);
    }
    return err.toString(StandardCharsets.UTF_8).lines().toList();
  }

  private void narrowTo(String... projects) {
    fdes.restrict("nia");
    for (var project : projects) {
      fdes.grant("nia", project);
    }
  }

  private List<String> nodeSpecs() {
    return nodeDb.query("SELECT id FROM specs ORDER BY id", row -> row.text(0));
  }

  private List<String> nodeRoster() {
    return new FdeStore(nodeDb)
        .list().stream().map(fde -> fde.handle() + "|" + fde.email() + "|" + fde.status()).toList();
  }

  private void write(String id, String project) {
    Acting.as("nia", () -> new SpecStore(nodeDb).create(spec(id, project)));
  }

  private Path filesOf(String project) throws IOException {
    var file = projectsDir.resolve(project).resolve("files").resolve("notes.md");
    Files.createDirectories(file.getParent());
    Files.writeString(file, "work");
    return file;
  }

  @Test
  void aNarrowingDropsTheProjectsItDoesNotGrantWithTheirContainersAndPushesTheRest()
      throws Exception {
    assertEquals(
        List.of(
            "  ⚠ 2 new project(s) synced from main: acme, beta. Provision each with:",
            "    sail project apply acme",
            "    sail project apply beta"),
        round());
    filesOf("beta");
    incus.sail("acme");
    incus.sail("beta");
    write("a2", "acme");
    write("b2", "beta");
    narrowTo("acme");
    incus.invocations.clear();

    assertEquals(
        List.of(
            "  ⚠ project beta: dropped from this box with the 5 rows it held here, unpushed work"
                + " included (1 row never pushed)"
                + WHY,
            "  roster: removed 1 FDE(s) main no longer lists to this box",
            "  ⚠ project beta: its container deleted, workspace and uncommitted work included"
                + WHY),
        round());

    assertEquals(List.of("a1", "a2"), nodeSpecs());
    assertTrue(new SpecStore(mainDb).findById("a2").isPresent(), "acme's work reached main");
    assertTrue(new SpecStore(mainDb).findById("b2").isEmpty(), "beta's never did");
    assertEquals(List.of("acme"), incus.names());
    assertFalse(Files.exists(projectsDir.resolve("beta")));
    assertTrue(Files.isRegularFile(projectsDir.resolve("acme").resolve("sail.yaml")));
    assertEquals(
        List.of(
            "incus list --format json",
            "incus config device get beta sail-api-sock source",
            "incus delete beta --force"),
        incus.invocations);
    assertEquals(List.of("ada||active", "nia|nia@x.dev|active"), nodeRoster());
  }

  @Test
  void aContainerSailDidNotProvisionStaysAndTheBoxSaysWhoseItIsToRemove() throws Exception {
    round();
    incus.foreign("beta");
    narrowTo("acme");

    assertEquals(
        List.of(
            "  ⚠ project beta: dropped from this box with the 3 rows it held here" + WHY,
            "  roster: removed 1 FDE(s) main no longer lists to this box",
            "  ⚠ project beta: container 'beta' kept — Sail did not provision it; remove it"
                + " yourself if it holds the project's work"),
        round());
    assertEquals(List.of("beta"), incus.names());
    assertFalse(Files.exists(projectsDir.resolve("beta")));

    assertEquals(List.of(), round(), "said once, in the round that dropped the project");
    assertEquals(List.of("beta"), incus.names());
  }

  @Test
  void aRoundThatCannotReachIncusLeavesTheContainerToTheNextOneThatCan() throws Exception {
    round();
    filesOf("beta");
    incus.sail("beta");
    incus.reachable = false;
    narrowTo("acme");

    assertEquals(
        List.of(
            "  ⚠ project beta: dropped from this box with the 3 rows it held here" + WHY,
            "  roster: removed 1 FDE(s) main no longer lists to this box",
            "  ⚠ project beta: its container, if any, is deleted by the next round that can reach"
                + " Incus (sail-api within a minute, or 'sail sync')"),
        round());
    assertEquals(List.of("beta"), incus.names());
    assertFalse(Files.exists(projectsDir.resolve("beta")));
    assertEquals(List.of(), round(), "a round that cannot reach Incus says nothing again");

    incus.reachable = true;
    assertEquals(
        List.of(
            "  ⚠ project beta: its container deleted, workspace and uncommitted work included"
                + WHY),
        round());
    assertEquals(List.of(), incus.names());
  }

  @Test
  void aContainerIncusWillNotDeleteIsSaidAndTakenByTheNextRoundWhileTheRestGoesNow()
      throws Exception {
    round();
    incus.sail("beta");
    incus.sail("gamma");
    incus.busy.add("beta");
    narrowTo("acme");

    assertEquals(
        List.of(
            "  ⚠ project beta: dropped from this box with the 3 rows it held here" + WHY,
            "  roster: removed 1 FDE(s) main no longer lists to this box",
            "  ✗ Could not take project beta off this box (the next sync tries again): Failed to"
                + " delete container 'beta': Error: instance is busy",
            "  ⚠ project gamma: its container deleted, workspace and uncommitted work included"
                + WHY),
        round());
    assertEquals(List.of("beta"), incus.names());

    assertEquals(
        List.of(
            "  ⚠ project beta: its container deleted, workspace and uncommitted work included"
                + WHY),
        round());
    assertEquals(List.of(), incus.names());
  }

  @Test
  void aProjectKeptForALiveRunKeepsItsContainerAndDirectoryUntilTheRoundAfterTheRunEnds()
      throws Exception {
    round();
    incus.sail("beta");
    var run = DateTimeUtils.newId().toString();
    Acting.as(
        "nia",
        () ->
            new RunStore(nodeDb)
                .create(
                    run, "beta", "b1", "nia", "build", "claude", "b", "t", null, null, "/l", "u"));
    narrowTo("acme");

    assertEquals(
        List.of(
            "  ⚠ project beta: kept this round, run '"
                + run
                + "' being still under way here; dropped on the first round after it ends"
                + WHY,
            "  roster: removed 1 FDE(s) main no longer lists to this box"),
        round());
    assertEquals(List.of("beta"), incus.names());
    assertTrue(Files.isRegularFile(projectsDir.resolve("beta").resolve("sail.yaml")));

    Acting.system(() -> new RunStore(nodeDb).complete(run, "completed", 0));
    var after = round();

    assertEquals(
        "  ⚠ project beta: its container deleted, workspace and uncommitted work included" + WHY,
        after.getLast());
    assertEquals(List.of(), incus.names());
    assertFalse(Files.exists(projectsDir.resolve("beta")));
  }

  @Test
  void aWideningBringsEveryProjectAndTheWholeRosterBackAndCreatesNoContainer() throws Exception {
    narrowTo("acme");
    round();
    assertEquals(List.of("a1"), nodeSpecs());
    assertEquals(List.of("ada||active", "nia|nia@x.dev|active"), nodeRoster());
    incus.invocations.clear();

    fdes.unrestrict("nia");
    assertEquals(
        List.of(
            "  ⚠ 1 new project(s) synced from main: beta. Provision each with:",
            "    sail project apply beta"),
        round());

    assertEquals(List.of("a1", "b1"), nodeSpecs());
    assertEquals(
        List.of("ada|ada@x.dev|active", "nia|nia@x.dev|active", "yan|yan@x.dev|active"),
        nodeRoster());
    assertEquals(ProjectScope.all(), new SyncScope(nodeDb).held());
    assertTrue(
        incus.invocations.stream().noneMatch(command -> command.contains("launch")),
        incus.invocations.toString());
    assertEquals(List.of(), incus.names());
  }

  @Test
  void aBoxAnEarlierReleaseLeftHoldingAnOutOfScopeProjectIsBroughtInLineByItsFirstRound()
      throws Exception {
    narrowTo("acme");
    round();
    Files.createDirectories(projectsDir.resolve("beta"));
    Files.writeString(projectsDir.resolve("beta").resolve("sail.yaml"), "name: beta\n");
    incus.sail("beta");
    new FdeStore(nodeDb).add("client2", "Client 2", "client2@rehearsal.invalid", "member");
    new FdeStore(nodeDb).disableUnlisted(List.of("ada", "nia"));

    assertEquals(
        List.of(
            "  roster: removed 1 FDE(s) main no longer lists to this box",
            "  ⚠ project beta: its container deleted, workspace and uncommitted work included"
                + WHY),
        round());

    assertEquals(List.of("acme"), childrenOf(projectsDir));
    assertEquals(List.of(), incus.names());
    assertEquals(List.of("ada||active", "nia|nia@x.dev|active"), nodeRoster());
  }

  @Test
  void anUnchangedScopedRoundAsksIncusOnceAndNeverOfAProjectInScope() throws Exception {
    narrowTo("acme");
    round();
    incus.sail("acme");
    incus.foreign("scratch");
    incus.invocations.clear();

    assertEquals(List.of(), round());

    assertEquals(
        List.of("incus list --format json", "incus config device get scratch sail-api-sock source"),
        incus.invocations);
    assertEquals(List.of("acme", "scratch"), incus.names());
  }

  @Test
  void anUnscopedRoundNeverAsksIncus() throws Exception {
    round();
    incus.sail("acme");
    incus.sail("beta");
    incus.sail("gamma");
    incus.invocations.clear();

    assertEquals(List.of(), round());

    assertEquals(List.of(), incus.invocations);
    assertEquals(List.of("acme", "beta", "gamma"), incus.names());
  }

  private static List<String> childrenOf(Path dir) throws IOException {
    try (var children = Files.list(dir)) {
      return children.map(child -> child.getFileName().toString()).sorted().toList();
    }
  }

  private static SpecStore.SpecRow spec(String id, String project) {
    return new SpecStore.SpecRow(
        id,
        project,
        "the " + id + " spec",
        SpecStatus.PENDING,
        null,
        null,
        null,
        null,
        null,
        0,
        "nia",
        null,
        null,
        "nia",
        List.of(),
        List.of());
  }

  /**
   * This box's Incus: containers by name, each Sail's (it carries the API-socket device) or not,
   * reachable or failing every call as an unprivileged round finds it.
   */
  private static final class Incus implements ShellExec {
    private final Map<String, Boolean> containers = new LinkedHashMap<>();
    final List<String> invocations = new ArrayList<>();
    final Set<String> busy = new HashSet<>();
    boolean reachable = true;

    void sail(String name) {
      containers.put(name, true);
    }

    void foreign(String name) {
      containers.put(name, false);
    }

    List<String> names() {
      return containers.keySet().stream().sorted().toList();
    }

    @Override
    public Result exec(List<String> command) {
      var joined = String.join(" ", command);
      invocations.add(joined);
      if (!reachable) {
        return new Result(1, "", "permission denied");
      }
      if (joined.equals("incus list --format json")) {
        return new Result(
            0,
            containers.keySet().stream()
                .map(
                    name ->
                        "{\"name\":\"" + name + "\",\"status\":\"Running\",\"type\":\"container\"}")
                .collect(Collectors.joining(",", "[", "]")),
            "");
      }
      if (command.size() == 7 && joined.startsWith("incus config device get ")) {
        return Boolean.TRUE.equals(containers.get(command.get(4)))
            ? new Result(0, "/run/sail/api\n", "")
            : new Result(1, "", "Error: Device doesn't exist");
      }
      if (command.size() == 4
          && joined.startsWith("incus delete ")
          && joined.endsWith(" --force")) {
        if (busy.remove(command.get(2))) {
          return new Result(1, "", "Error: instance is busy");
        }
        return containers.remove(command.get(2)) == null
            ? new Result(1, "", "Error: Instance not found")
            : new Result(0, "", "");
      }
      return new Result(1, "", "unexpected: " + joined);
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

  @Test
  void aLeftoverDescriptorIsNeverImportedAndTheNextRoundTakesItWithNoDrop() throws Exception {
    narrowTo("beta");
    round();
    Files.createDirectories(projectsDir.resolve("acme"));
    Files.writeString(projectsDir.resolve("acme").resolve("sail.yaml"), "name: acme\n");

    for (var migrate = 0; migrate < 2; migrate++) {
      var report =
          Actor.call(
              Actor.system(),
              () ->
                  new ProjectImporter(
                          projectsDir, new ProjectStore(nodeDb), new SyncScope(nodeDb).held())
                      .importAll());
      assertEquals(new ProjectImporter.Report(0, 2, List.of()), report);
    }

    assertEquals(List.of(), round());
    assertEquals(List.of("beta"), childrenOf(projectsDir));
  }
}
