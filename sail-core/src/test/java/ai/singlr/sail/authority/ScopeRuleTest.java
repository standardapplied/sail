/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.authority;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.sail.config.SpecStatus;
import ai.singlr.sail.identity.Acting;
import ai.singlr.sail.identity.ActingAs;
import ai.singlr.sail.identity.Actor;
import ai.singlr.sail.identity.ProjectScope;
import ai.singlr.sail.identity.Role;
import ai.singlr.sail.store.ChangeLog;
import ai.singlr.sail.store.ContentFixtures;
import ai.singlr.sail.store.FileStore;
import ai.singlr.sail.store.MessageStore;
import ai.singlr.sail.store.ProjectStore;
import ai.singlr.sail.store.ReviewStore;
import ai.singlr.sail.store.RoomStore;
import ai.singlr.sail.store.SchemaManager;
import ai.singlr.sail.store.SpecStore;
import ai.singlr.sail.store.Sqlite;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The journal refuses every write outside its actor's scope, on this box's own lanes as on main's
 * commit, before the type's own rule can say anything of a row the actor may not see.
 */
@ActingAs
class ScopeRuleTest {

  private static final ProjectScope ONLY_A = ProjectScope.of(List.of("alpha"));
  private static final Actor NIA = new Actor("nia", Role.MEMBER, Actor.Lane.API).withScope(ONLY_A);
  private static final String GRANT =
      "Ask an admin on main for the project: sail fde grant <handle> <project>.";

  private Sqlite db;
  private SpecStore specs;
  private ScopeRule rule;

  @BeforeEach
  void setUp() {
    db = Sqlite.openMemory();
    new SchemaManager(db).migrate();
    specs = new SpecStore(db);
    rule = new ScopeRule(new ChangeLog(db), "spec");
    for (var project : List.of("alpha", "beta")) {
      new ProjectStore(db).upsert(project, "name: " + project + "\n");
      specs.create(spec(project + "-work", project, "uma"));
      new RoomStore(db)
          .create(
              new RoomStore.RoomRow(
                  project + "-work", project, "room", "uma", null, null, "uma", null, null, "uma"));
    }
  }

  @AfterEach
  void tearDown() {
    db.close();
  }

  @Test
  void anActorThatSeesEveryProjectIsNeverRefused() {
    for (var actor : List.of(Actors.OWNER_API, Actors.MAIN, Actors.SYSTEM, Actors.OWNER_SYNC)) {
      assertEquals(
          Optional.empty(),
          rule.decide(actor, "beta-work", Map.of("project", "beta"), Map.of("project", "beta")));
      assertEquals(Optional.empty(), rule.pushed(actor, "beta-work", Map.of("project", "beta")));
    }
  }

  @Test
  void aWriteIntoAHiddenProjectIsAProjectNotFound() {
    assertEquals(
        Refusal.of(Refusal.Kind.OUT_OF_SCOPE, "Project 'beta' was not found.", GRANT),
        rule.decide(NIA, "new", null, Map.of("project", "beta", "title", "t")));
    assertEquals(
        Refusal.of(
            Refusal.Kind.OUT_OF_SCOPE, "Spec 'new' is in no project this FDE is granted.", GRANT),
        rule.decide(NIA, "new", null, Map.of("title", "names no project")));
    assertEquals(
        Optional.empty(), rule.decide(NIA, "new", null, Map.of("project", "alpha", "title", "t")));
  }

  @Test
  void aWriteOverARowInAHiddenProjectSaysOnlyThatTheIdIsTaken() {
    var taken =
        Refusal.of(
            Refusal.Kind.OUT_OF_SCOPE, "Spec 'beta-work' already exists.", "Pick another id.");

    assertEquals(
        taken,
        rule.decide(NIA, "beta-work", Map.of("project", "beta"), Map.of("project", "alpha")));
    assertEquals(taken, rule.decide(NIA, "beta-work", Map.of("project", "beta"), null));
    assertEquals(taken, rule.pushed(NIA, "beta-work", Map.of("project", "alpha")));
    assertEquals(taken, rule.pushed(NIA, "beta-work", null));
    assertEquals(
        Optional.empty(), rule.decide(NIA, "alpha-work", Map.of("project", "alpha"), null));
    assertEquals(Optional.empty(), rule.pushed(NIA, "alpha-work", null));
    assertEquals(Optional.empty(), rule.pushed(NIA, "never-held", null));
    assertEquals(
        Optional.empty(),
        rule.pushed(NIA, "alpha-work", Map.of("_deleted_by", "nia")),
        "a marked deletion names no project and is decided by what it deletes");
  }

  @Test
  void aScopedActorsOwnWritesOnThisBoxAreRefusedOutsideItsScopeByTheJournal() {
    var hidden =
        assertThrows(
            WriteRefused.class,
            () -> Acting.by(NIA, () -> specs.create(spec("mine", "beta", "nia"))));
    assertEquals(
        new Refusal(Refusal.Kind.OUT_OF_SCOPE, "Project 'beta' was not found.", GRANT),
        hidden.refusal());
    assertTrue(specs.findById("mine").isEmpty());

    var taken =
        assertThrows(
            WriteRefused.class,
            () -> Acting.by(NIA, () -> specs.updateStatus("beta-work", SpecStatus.DONE)));
    assertEquals("Spec 'beta-work' already exists.", taken.getMessage());
    assertEquals(SpecStatus.PENDING, specs.findById("beta-work").orElseThrow().status());

    var post =
        assertThrows(
            WriteRefused.class,
            () ->
                Acting.by(NIA, () -> new MessageStore(db).append("beta-work", "nia", "hi", null)));
    assertTrue(
        post.getMessage()
            .matches("Message '[0-9a-f-]{36}' is in no project this FDE is granted\\."),
        post.getMessage());

    var file =
        assertThrows(
            WriteRefused.class,
            () ->
                Acting.by(
                    NIA,
                    () ->
                        new FileStore(db)
                            .put(
                                "beta",
                                "notes.md",
                                new ByteArrayInputStream("x".getBytes(StandardCharsets.UTF_8)),
                                0644)));
    assertEquals("File 'beta/notes.md' is in no project this FDE is granted.", file.getMessage());
    var catalogued =
        assertThrows(
            WriteRefused.class,
            () -> Acting.by(NIA, () -> new ProjectStore(db).upsert("gamma", "name: gamma\n")));
    assertEquals("Project 'gamma' was not found.", catalogued.getMessage());

    Acting.by(NIA, () -> specs.create(spec("mine", "alpha", "nia")));
    Acting.by(NIA, () -> new MessageStore(db).append("mine", "nia", "in scope", null));
    Acting.by(
        NIA,
        () ->
            new FileStore(db)
                .put(
                    "alpha",
                    "notes.md",
                    new ByteArrayInputStream("x".getBytes(StandardCharsets.UTF_8)),
                    0644));
    assertEquals("x", ContentFixtures.text(new FileStore(db), "alpha", "notes.md"));
  }

  @Test
  void aWriteOverAReviewIsDecidedByWhereTheReviewIsFiledNotWhereItsSpecIsNow() {
    var log = new ChangeLog(db);
    var reviews = new ReviewStore(db);
    var arrived = reviews.createReview("beta-work", 1);
    var left = reviews.createReview("alpha-work", 1);
    specs.update(spec("beta-work", "alpha", "nia"));
    specs.update(spec("alpha-work", "beta", "nia"));
    assertEquals(Optional.of("beta"), log.headProject("review", arrived));
    var taken =
        new Refusal(
            Refusal.Kind.OUT_OF_SCOPE,
            "Review '" + arrived + "' already exists.",
            "Pick another id.");
    var held = reviews.comparableSnapshot(arrived);

    assertEquals(Optional.of(taken), new ScopeRule(log, "review").decide(NIA, arrived, held, held));
    for (Acting.Work<RuntimeException> write :
        List.<Acting.Work<RuntimeException>>of(
            () -> reviews.supersedeForSpec("beta-work"),
            () -> reviews.updateReviewStatus(arrived, "failed"),
            () -> reviews.approve(arrived, "nia", () -> {}))) {
      assertEquals(taken, assertThrows(WriteRefused.class, () -> Acting.by(NIA, write)).refusal());
    }
    var untouched = reviews.findReview(arrived).orElseThrow();
    assertEquals("running", untouched.status());
    assertEquals(false, untouched.superseded());
    assertEquals(1, log.history("review", arrived).size(), "and nothing of it was journaled");

    assertEquals(
        new Refusal(
            Refusal.Kind.OUT_OF_SCOPE,
            "Review '" + left + "' is in no project this FDE is granted.",
            GRANT),
        assertThrows(
                WriteRefused.class,
                () -> Acting.by(NIA, () -> reviews.updateReviewStatus(left, "failed")))
            .refusal(),
        "a review nia may read is still not nia's to write once its spec left the scope");

    var later = reviews.createReview("beta-work", 1);
    Acting.by(NIA, () -> reviews.updateReviewStatus(later, "failed"));
    assertEquals("failed", reviews.findReview(later).orElseThrow().status());
    Acting.by(Actors.ADMIN, () -> reviews.supersedeForSpec("beta-work"));
    assertEquals(true, reviews.findReview(arrived).orElseThrow().superseded());
  }

  @Test
  void aHeadThatNamesNoProjectIsDecidedByTheStateItHolds() {
    db.execute("UPDATE change_heads SET project = NULL WHERE entity_type = 'spec'");

    assertEquals(
        Refusal.of(
            Refusal.Kind.OUT_OF_SCOPE, "Spec 'beta-work' already exists.", "Pick another id."),
        rule.decide(NIA, "beta-work", Map.of("project", "beta"), Map.of("project", "alpha")));
    assertEquals(
        Optional.empty(),
        rule.decide(NIA, "alpha-work", Map.of("project", "alpha"), Map.of("project", "alpha")));
  }

  @Test
  void aScopedPrunerFindsNoSpecOutsideItsScope() {
    var erase = new EraseAuthority(db);
    specs.updateStatus("beta-work", SpecStatus.ARCHIVED);
    specs.updateStatus("alpha-work", SpecStatus.ARCHIVED);
    var admin = new Actor("nia", Role.ADMIN, Actor.Lane.API).withScope(ONLY_A);

    assertEquals(
        Refusal.of(Refusal.Kind.OUT_OF_SCOPE, "Spec 'beta-work' was not found.", null),
        erase.spec(admin, specs.lastKnown("beta-work").orElseThrow()));
    assertEquals(Optional.empty(), erase.spec(admin, specs.lastKnown("alpha-work").orElseThrow()));
    assertEquals(
        Refusal.of(
            Refusal.Kind.NOT_PRUNABLE,
            "main holds no project 'beta'",
            "Sync the project before pruning it."),
        erase.requested(admin, "project", "beta"));
    assertEquals(Optional.empty(), erase.requested(admin, "project", "alpha"));
  }

  private static SpecStore.SpecRow spec(String id, String project, String owner) {
    return new SpecStore.SpecRow(
        id,
        project,
        "a spec",
        SpecStatus.PENDING,
        owner,
        null,
        null,
        null,
        null,
        0,
        owner,
        "",
        "",
        owner,
        List.of(),
        List.of());
  }
}
