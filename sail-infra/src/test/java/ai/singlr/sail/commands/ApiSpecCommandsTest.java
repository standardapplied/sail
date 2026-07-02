/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.commands;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import ai.singlr.sail.api.EventBus;
import ai.singlr.sail.api.SailApiOperations;
import ai.singlr.sail.api.SailApiServer;
import ai.singlr.sail.api.SpecStoreAuditPersister;
import ai.singlr.sail.engine.ShellExecutor;
import ai.singlr.sail.store.EventStore;
import ai.singlr.sail.store.Finding;
import ai.singlr.sail.store.ReviewStore;
import ai.singlr.sail.store.SchemaManager;
import ai.singlr.sail.store.SpecStore;
import ai.singlr.sail.store.Sqlite;
import ai.singlr.sail.store.TokenStore;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
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

  @BeforeEach
  void setUp() throws Exception {
    db = Sqlite.open(tempDir.resolve("test.db"));
    new SchemaManager(db).migrate();
    var tokenStore = new TokenStore(db);
    token = tokenStore.create("test", "admin").token();
    var specStore = new SpecStore(db);
    var eventStore = new EventStore(db);
    var bus = new EventBus();
    var persister = new SpecStoreAuditPersister(eventStore);
    reviewStore = new ReviewStore(db);
    var operations =
        new SailApiOperations(
            new ShellExecutor(false), "sail.yaml", bus, persister, specStore, reviewStore);
    server = new SailApiServer("127.0.0.1", 0, operations, tokenStore, bus, persister);
    server.start();
  }

  @AfterEach
  void tearDown() {
    if (server != null) server.close();
    if (db != null) db.close();
  }

  private String serverUrl() {
    return "http://127.0.0.1:" + server.port();
  }

  private String run(String... args) {
    var original = System.out;
    var buffer = new ByteArrayOutputStream();
    System.setOut(new PrintStream(buffer, true, StandardCharsets.UTF_8));
    try {
      System.setProperty("SAIL_SERVER", serverUrl());
      System.setProperty("SAIL_TOKEN", token);
      new CommandLine(new SpecCommand()).execute(withDefaultProject(args));
      return buffer.toString(StandardCharsets.UTF_8);
    } finally {
      System.setOut(original);
      System.clearProperty("SAIL_SERVER");
      System.clearProperty("SAIL_TOKEN");
    }
  }

  private static String[] withDefaultProject(String[] args) {
    if (args.length == 0 || !"create".equals(args[0])) {
      return args;
    }
    for (var arg : args) {
      if ("--project".equals(arg)) {
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
            new Finding.Suggestion("log.info(token)", "log.info(mask(token))", "Never log secrets"),
            0.9));
    reviewStore.updateReviewStatus(reviewId, "passed");
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
    run("delete", "doomed", "--force");

    var output = run("list", "--json");
    assertFalse(output.contains("doomed"));
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
      java.nio.file.Files.writeString(bodyFile, "# Spec Body");
      java.nio.file.Files.writeString(planFile, "## Plan");
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
