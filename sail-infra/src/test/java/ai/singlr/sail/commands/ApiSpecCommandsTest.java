/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.commands;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import ai.singlr.sail.api.EventBus;
import ai.singlr.sail.api.ProjectReader;
import ai.singlr.sail.api.SailApiServer;
import ai.singlr.sail.api.SailOperations;
import ai.singlr.sail.api.SpecStoreAuditPersister;
import ai.singlr.sail.api.TestAuth;
import ai.singlr.sail.engine.OperatorCommand;
import ai.singlr.sail.engine.ShellExecutor;
import ai.singlr.sail.identity.Acting;
import ai.singlr.sail.store.EventStore;
import ai.singlr.sail.store.FdeStore;
import ai.singlr.sail.store.Finding;
import ai.singlr.sail.store.HeldProjects;
import ai.singlr.sail.store.MessageStore;
import ai.singlr.sail.store.ProjectStore;
import ai.singlr.sail.store.ReviewStore;
import ai.singlr.sail.store.RoomStore;
import ai.singlr.sail.store.SchemaManager;
import ai.singlr.sail.store.SpecStore;
import ai.singlr.sail.store.Sqlite;
import ai.singlr.sail.store.TokenStore;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

class ApiSpecCommandsTest {

  @TempDir Path tempDir;
  private Sqlite db;
  private SailApiServer server;
  private String token;
  private ReviewStore reviewStore;
  private final BooleanSupplier originalTerminal = ConsoleHelper.terminal;

  @BeforeEach
  void setUp() throws Exception {
    db = Sqlite.open(tempDir.resolve("test.db"));
    new SchemaManager(db).migrate();
    HeldProjects.hold(db, "other", "test");
    var tokenStore = new TokenStore(db);
    var fde = new FdeStore(db).add("test", "Test", "test@example.com", "admin");
    token = tokenStore.create("test", "admin", fde.id(), null).token();
    var specStore = new SpecStore(db);
    var eventStore = new EventStore(db);
    var bus = new EventBus();
    var persister = new SpecStoreAuditPersister(eventStore);
    reviewStore = new ReviewStore(db);
    var operations =
        new SailOperations(
                new ShellExecutor(false),
                ProjectReader.ofCatalog(new ProjectStore(db)),
                bus,
                persister,
                specStore,
                reviewStore,
                OperatorCommand.PLAIN)
            .useMessages(new MessageStore(db))
            .useRooms(new RoomStore(db));
    server =
        new SailApiServer(
            "127.0.0.1", 0, operations, TestAuth.tokens(db), bus, persister, null, null);
    server.start();
  }

  @AfterEach
  void tearDown() {
    ConsoleHelper.terminal = originalTerminal;
    ConsoleHelper.resetStdin();
    if (server != null) server.close();
    if (db != null) db.close();
  }

  private String serverUrl() {
    return "http://127.0.0.1:" + server.port();
  }

  private String run(String... args) {
    return execute(args).out();
  }

  private record Ran(int code, String out, String err) {}

  private Ran execute(String... args) {
    var originalOut = System.out;
    var originalErr = System.err;
    var out = new ByteArrayOutputStream();
    var err = new ByteArrayOutputStream();
    System.setOut(new PrintStream(out, true, StandardCharsets.UTF_8));
    System.setErr(new PrintStream(err, true, StandardCharsets.UTF_8));
    try {
      System.setProperty("SAIL_SERVER", serverUrl());
      System.setProperty("SAIL_TOKEN", token);
      var command = new CommandLine(new SpecCommand());
      command.setExecutionExceptionHandler((failure, line, parsed) -> 1);
      var code = command.execute(withDefaultProject(args));
      return new Ran(
          code, out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8));
    } finally {
      System.setOut(originalOut);
      System.setErr(originalErr);
      System.clearProperty("SAIL_SERVER");
      System.clearProperty("SAIL_TOKEN");
    }
  }

  private Ran answering(String stdin, boolean terminal, String... args) {
    var original = System.in;
    System.setIn(new ByteArrayInputStream(stdin.getBytes(StandardCharsets.UTF_8)));
    ConsoleHelper.resetStdin();
    ConsoleHelper.terminal = () -> terminal;
    try {
      return execute(args);
    } finally {
      System.setIn(original);
    }
  }

  private static final Set<String> PROJECT_SCOPED = Set.of("create", "list", "board");

  private static String[] withDefaultProject(String[] args) {
    if (args.length == 0 || !PROJECT_SCOPED.contains(args[0])) {
      return args;
    }
    for (var arg : args) {
      if ("--project".equals(arg) || "--all-projects".equals(arg)) {
        return args;
      }
    }
    var expanded = new String[args.length + 2];
    System.arraycopy(args, 0, expanded, 0, args.length);
    expanded[args.length] = "--project";
    expanded[args.length + 1] = "test";
    return expanded;
  }

  private void seedPassedReviewWithOpenFinding(String specId) {
    Acting.system(
        () -> {
          var reviewId = reviewStore.createReview(specId, 1);
          var stageId = reviewStore.createStage(reviewId, "security", "agent");
          reviewStore.addFinding(
              stageId,
              Finding.create(
                  Finding.Severity.HIGH,
                  Finding.Category.SECURITY,
                  "Auth.java",
                  10,
                  12,
                  "Token leak",
                  "Token is logged.",
                  "log.info(token)",
                  new Finding.Suggestion(
                      "log.info(token)", "log.info(mask(token))", "Never log secrets"),
                  0.9));
          reviewStore.updateReviewStatus(reviewId, "passed");
        });
  }

  @Test
  void createFromReviewDraftsFollowupSpec() {
    run("create", "--id", "auth", "--title", "OAuth flow", "--status", "done");
    seedPassedReviewWithOpenFinding("auth");

    var output = run("create", "--from-review", "auth");
    assertTrue(output.contains("Follow-up spec drafted: auth-followup"));
    assertTrue(output.contains("1 open findings"));

    var show = run("show", "auth-followup", "--json");
    assertTrue(show.contains("\"status\": \"draft\""));
    assertTrue(show.contains("Address review findings: OAuth flow"));
    assertTrue(show.contains("Token leak"));
  }

  @Test
  void createFromReviewHonorsExplicitId() {
    run("create", "--id", "auth", "--title", "OAuth flow", "--status", "done");
    seedPassedReviewWithOpenFinding("auth");

    var output = run("create", "--from-review", "auth", "--id", "auth-round2");
    assertTrue(output.contains("Follow-up spec drafted: auth-round2"));
  }

  @Test
  void showAppendsOpenFindingsToStatus() {
    run("create", "--id", "auth", "--title", "OAuth flow", "--status", "done");
    seedPassedReviewWithOpenFinding("auth");

    var output = run("show", "auth");
    assertTrue(output.contains("1 open findings"));
  }

  @Test
  void boardAppendsOpenFindingsToDoneColumn() {
    run("create", "--id", "auth", "--title", "OAuth flow", "--status", "done");
    seedPassedReviewWithOpenFinding("auth");

    var output = run("board");
    assertTrue(output.contains("1 open findings"));
  }

  @Test
  void boardWithoutResidueShowsNoFindingsSuffix() {
    run("create", "--id", "auth", "--title", "OAuth flow", "--status", "done");

    var output = run("board");
    assertFalse(output.contains("open findings"));
  }

  @Test
  void createWithARoomIsBornThereAndMintsNoIdentityRoom() {
    run("create", "--id", "epic", "--title", "Epic");
    var output = run("create", "--id", "child", "--title", "Child", "--room", "epic");

    assertTrue(output.contains("Spec created: child"), output);
    var rooms = new RoomStore(db);
    assertEquals("epic", new SpecStore(db).findById("child").orElseThrow().roomIdOrIdentity());
    assertTrue(
        rooms.findById("child").isEmpty(), "a spec with a home room gets no room of its own");
    assertTrue(rooms.findById("epic").isPresent());

    var refused = run("create", "--id", "orphan", "--title", "Orphan", "--room", "nowhere");
    assertTrue(new SpecStore(db).findById("orphan").isEmpty(), refused);
  }

  @Test
  void createAndListSpec() {
    run("create", "--id", "auth", "--title", "OAuth flow");

    var output = run("list", "--json");
    assertTrue(output.contains("\"id\": \"auth\""));
    assertTrue(output.contains("\"title\": \"OAuth flow\""));
  }

  @Test
  void createAndShowSpec() {
    run("create", "--id", "payment", "--title", "Payment integration");

    var output = run("show", "payment", "--json");
    assertTrue(output.contains("\"id\": \"payment\""));
    assertTrue(output.contains("\"title\": \"Payment integration\""));
  }

  @Test
  void commentAndCommentsRoundTripAsJson() {
    run("create", "--id", "room", "--title", "Room");

    var posted = run("comment", "room", "--body", "Progress update");
    assertTrue(posted.contains("\"author\": \"test\""));
    assertTrue(posted.contains("\"body\": \"Progress update\""));

    var listed = run("comments", "room");
    assertTrue(listed.contains("\"messages\""));
    assertTrue(listed.contains("\"body\": \"Progress update\""));
  }

  @Test
  void commentQuestionFlagRidesTheRequestAndReturnsOnTheMessage() {
    run("create", "--id", "asking", "--title", "Asking");

    var posted = run("comment", "asking", "--body", "Which flow?", "--question");
    assertTrue(posted.contains("\"question\": true"));

    var listed = run("comments", "asking");
    assertTrue(listed.contains("\"question\": true"));
  }

  @Test
  void commentReadsBodyFromStdin() {
    run("create", "--id", "stdin-room", "--title", "Room");
    var original = System.in;
    try {
      System.setIn(
          new ByteArrayInputStream("Question from stdin".getBytes(StandardCharsets.UTF_8)));
      assertTrue(
          run("comment", "stdin-room", "--body", "-")
              .contains("\"body\": \"Question from stdin\""));
    } finally {
      System.setIn(original);
    }
  }

  @Test
  void createSpecWithStatus() {
    run("create", "--id", "ready", "--title", "Ready spec", "--status", "pending");

    var output = run("show", "ready", "--json");
    assertTrue(output.contains("\"status\": \"pending\""));
  }

  @Test
  void editSpecUpdatesTitle() {
    run("create", "--id", "editable", "--title", "Original");
    run("edit", "editable", "--title", "Updated title");

    var output = run("show", "editable", "--json");
    assertTrue(output.contains("\"title\": \"Updated title\""));
  }

  @Test
  void editSpecUpdatesStatus() {
    run("create", "--id", "lifecycle", "--title", "Lifecycle test", "--status", "draft");
    run("edit", "lifecycle", "--status", "pending");

    var output = run("show", "lifecycle", "--json");
    assertTrue(output.contains("\"status\": \"pending\""));
  }

  @Test
  void deleteSpec() {
    run("create", "--id", "doomed", "--title", "Will be deleted");

    var deleted = answering("", false, "delete", "doomed", "--force");

    assertEquals(new Ran(0, "  ✓ Spec deleted: doomed\n", ""), deleted);
    var output = run("list", "--json");
    assertFalse(output.contains("doomed"));
  }

  @Test
  void aDeleteWithNoTerminalToConfirmOnIsRefusedAndDeletesNothing() {
    run("create", "--id", "kept", "--title", "Kept");

    var refused = answering("", false, "delete", "kept");

    assertEquals(
        new Ran(1, "", "  ✗ Not deleted: no terminal to confirm on. Re-run with --force.\n"),
        refused);
    assertTrue(run("show", "kept", "--json").contains("\"id\": \"kept\""));
  }

  @Test
  void aJsonDeleteWithNoTerminalToConfirmOnIsRefusedAndDeletesNothing() {
    run("create", "--id", "kept", "--title", "Kept");

    var refused = answering("", false, "delete", "kept", "--json");

    assertEquals(
        new Ran(1, "", "  ✗ Not deleted: no terminal to confirm on. Re-run with --force.\n"),
        refused);
    assertTrue(run("show", "kept", "--json").contains("\"id\": \"kept\""));
  }

  @Test
  void aForcedJsonDeleteWithNoTerminalDeletes() {
    run("create", "--id", "doomed", "--title", "Doomed");

    var deleted = answering("", false, "delete", "doomed", "--json", "--force");

    assertEquals(0, deleted.code());
    assertTrue(deleted.out().contains("\"deleted\": true"));
    assertFalse(run("list", "--json").contains("doomed"));
  }

  @Test
  void aDeleteAnsweredWithAnEmptyLineOnATerminalIsCancelled() {
    run("create", "--id", "kept", "--title", "Kept");

    var cancelled = answering("\n", true, "delete", "kept");

    assertEquals(new Ran(0, "  Delete spec 'kept'? [y/N]   Cancelled.\n", ""), cancelled);
    assertTrue(run("show", "kept", "--json").contains("\"id\": \"kept\""));
  }

  @Test
  void aDeleteAnsweredYesOnATerminalDeletes() {
    run("create", "--id", "doomed", "--title", "Doomed");

    var deleted = answering("y\n", true, "delete", "doomed");

    assertEquals(
        new Ran(0, "  Delete spec 'doomed'? [y/N]   ✓ Spec deleted: doomed\n", ""), deleted);
  }

  @Test
  void boardShowsCounts() {
    run("create", "--id", "a", "--title", "A", "--status", "draft");
    run("create", "--id", "b", "--title", "B", "--status", "pending");
    run("create", "--id", "c", "--title", "C", "--status", "pending");

    var output = run("board", "--json");
    assertTrue(output.contains("\"draft\": 1"));
    assertTrue(output.contains("\"pending\": 2"));
  }

  @Test
  void listDefaultsToActiveStatusesIncludingAwaitingMerge() {
    run("create", "--id", "queued", "--title", "Queued", "--status", "pending");
    run("create", "--id", "merge-me", "--title", "Merge queue", "--status", "awaiting_merge");
    run("create", "--id", "shipped", "--title", "Shipped", "--status", "done");
    run("create", "--id", "buried", "--title", "Buried", "--status", "archived");

    var output = run("list", "--json");
    assertTrue(output.contains("\"id\": \"queued\""));
    assertTrue(output.contains("\"id\": \"merge-me\""));
    assertFalse(output.contains("\"id\": \"shipped\""));
    assertFalse(output.contains("\"id\": \"buried\""));
  }

  @Test
  void listAllIncludesDoneAndArchived() {
    run("create", "--id", "shipped", "--title", "Shipped", "--status", "done");
    run("create", "--id", "buried", "--title", "Buried", "--status", "archived");

    var output = run("list", "--all", "--json");
    assertTrue(output.contains("\"id\": \"shipped\""));
    assertTrue(output.contains("\"id\": \"buried\""));
  }

  @Test
  void listExplicitDoneStatusShowsDoneSpecs() {
    run("create", "--id", "shipped", "--title", "Shipped", "--status", "done");

    var output = run("list", "--status", "done", "--json");
    assertTrue(output.contains("\"id\": \"shipped\""));
  }

  @Test
  void listScopesToTheRequestedProjectOnly() {
    run("create", "--id", "mine", "--title", "Mine", "--project", "test");
    run("create", "--id", "theirs", "--title", "Theirs", "--project", "other");

    var scoped = run("list", "--project", "test", "--json");
    assertTrue(scoped.contains("\"id\": \"mine\""));
    assertFalse(scoped.contains("\"id\": \"theirs\""));

    var everywhere = run("list", "--all-projects", "--json");
    assertTrue(everywhere.contains("\"id\": \"mine\""));
    assertTrue(everywhere.contains("\"id\": \"theirs\""));
  }

  @Test
  void updateIsAnAcceptedVerbForEdit() {
    run("create", "--id", "renamed", "--title", "Original");
    run("update", "renamed", "--title", "Via update verb");

    var output = run("show", "renamed", "--json");
    assertTrue(output.contains("\"title\": \"Via update verb\""));
  }

  @Test
  void listFiltersByStatus() {
    run("create", "--id", "p1", "--title", "Pending 1", "--status", "pending");
    run("create", "--id", "d1", "--title", "Done 1", "--status", "done");

    var output = run("list", "--status", "pending", "--json");
    assertTrue(output.contains("\"id\": \"p1\""));
    assertFalse(output.contains("\"id\": \"d1\""));
  }

  @Test
  void listSearchesByQuery() {
    run("create", "--id", "oauth-flow", "--title", "OAuth 2.0 authorization");
    run("create", "--id", "payment", "--title", "Payment integration");

    var output = run("list", "-q", "oauth", "--json");
    assertTrue(output.contains("oauth-flow"));
    assertFalse(output.contains("payment"));
  }

  @Test
  void contentSetAndGet() {
    run("create", "--id", "with-content", "--title", "Content test");

    var bodyFile = tempDir.resolve("body.md");
    var planFile = tempDir.resolve("plan.md");
    try {
      Files.writeString(bodyFile, "# Spec Body");
      Files.writeString(planFile, "## Plan");
    } catch (Exception e) {
      fail(e);
    }

    run(
        "content",
        "with-content",
        "--set",
        "--body-file",
        bodyFile.toString(),
        "--plan-file",
        planFile.toString());

    var output = run("content", "with-content", "--json");
    assertTrue(output.contains("# Spec Body"));
    assertTrue(output.contains("## Plan"));
  }

  @Test
  void aBodyFileWithoutSetSetsTheBodyAsTheSpecCliDocumentsIt() throws Exception {
    run("create", "--id", "drafted", "--title", "Drafted");
    var bodyFile = Files.writeString(tempDir.resolve("b.md"), "# Drafted body");

    var set = execute("content", "drafted", "--body-file", bodyFile.toString());

    assertEquals(new Ran(0, "  ✓ Content updated for: drafted\n", ""), set);
    assertEquals("# Drafted body\n", run("content", "drafted"));
  }

  @Test
  void aPlanFileWithoutSetSetsThePlan() throws Exception {
    run("create", "--id", "planned", "--title", "Planned");
    var planFile = Files.writeString(tempDir.resolve("p.md"), "## Steps");

    assertEquals(0, execute("content", "planned", "--plan-file", planFile.toString()).code());

    assertEquals("\n--- Plan ---\n## Steps\n", run("content", "planned"));
  }

  @Test
  void showHumanReadableOutput() {
    run("create", "--id", "human-test", "--title", "Human readable");

    var output = run("show", "human-test");
    assertTrue(output.contains("Spec:"));
    assertTrue(output.contains("human-test"));
    assertTrue(output.contains("Human readable"));
  }

  @Test
  void listHumanReadableWithEmptyDatabase() {
    var output = run("list");
    assertTrue(output.contains("No specs found"));
  }

  @Test
  void boardHumanReadableOutput() {
    var output = run("board");
    assertTrue(output.contains("Spec Board"));
  }

  @Test
  void createWithDependencies() {
    run("create", "--id", "base", "--title", "Base", "--status", "done");
    run("create", "--id", "child", "--title", "Child", "--depends-on", "base");

    var output = run("show", "child", "--json");
    assertTrue(output.contains("\"depends_on\""));
    assertTrue(output.contains("base"));
  }

  @Test
  void createWithRepos() {
    run("create", "--id", "multi-repo", "--title", "Multi repo", "--repos", "backend,frontend");

    var output = run("show", "multi-repo", "--json");
    assertTrue(output.contains("backend"));
    assertTrue(output.contains("frontend"));
  }

  @Test
  void editWithNoChangesWarns() {
    run("create", "--id", "unchanged", "--title", "No changes");

    var output = run("edit", "unchanged");
    assertTrue(output.contains("Nothing to update"));
  }

  @Test
  void createSpecJsonOutput() {
    var output = run("create", "--id", "json-test", "--title", "JSON", "--json");
    assertTrue(output.contains("\"id\": \"json-test\""));
  }
}
