/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.sail.common.DateTimeUtils;
import ai.singlr.sail.config.SpecStatus;
import ai.singlr.sail.identity.ActingAs;
import ai.singlr.sail.identity.Actor;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Every change head carries the project its entity belongs to, from the first write through a
 * deletion, a withdrawal and an erasure, and the heads written before it did are filled in once.
 */
@ActingAs
class ScopedHeadsTest {

  @TempDir Path dir;
  private Sqlite db;
  private ChangeLog log;
  private SpecStore specs;

  @BeforeEach
  void setUp() {
    db = Sqlite.open(dir.resolve("main.db"));
    new SchemaManager(db).migrate();
    log = new ChangeLog(db);
    specs = new SpecStore(db);
  }

  @AfterEach
  void tearDown() {
    db.close();
  }

  @Test
  void everyTypesHeadCarriesItsProjectFromItsFirstWrite() {
    var seeded = seed("alpha");

    assertEquals(Optional.of("alpha"), log.headProject("project", "alpha"));
    assertEquals(Optional.of("alpha"), log.headProject("spec", "alpha-work"));
    assertEquals(Optional.of("alpha"), log.headProject("room", "alpha-work"));
    assertEquals(Optional.of("alpha"), log.headProject("message", seeded.message()));
    assertEquals(Optional.of("alpha"), log.headProject("run", seeded.run()));
    assertEquals(Optional.of("alpha"), log.headProject("review", seeded.review()));
    assertEquals(Optional.of("alpha"), log.headProject("file", "alpha/notes.md"));
    assertEquals(List.of("alpha"), log.heldProjects());
    assertEquals(
        List.of("alpha"),
        log.headsAfter("spec", 0, 10).stream().map(ChangeLog.Head::project).toList());
  }

  @Test
  void aMessageInASpecsConversationWithNoRoomRowTakesTheSpecsProject() {
    specs.create(spec("lone", "alpha"));

    var message = new MessageStore(db).append("lone", "ada", "no room row yet", null);

    assertEquals(Optional.of("alpha"), log.headProject("message", message.id()));
    assertNull(log.projectOf("message", "m", Map.of("room_id", "nowhere")));
    assertEquals("alpha", log.projectOf("message", "m", Map.of("spec_id", "lone")));
  }

  @Test
  void aStateThatNamesNoProjectHasNone() {
    assertNull(log.projectOf("spec", "s", Map.of("title", "t")));
    assertNull(log.projectOf("spec", "s", Map.of("project", "")));
    assertNull(log.projectOf("spec", "s", (Map<String, Object>) null));
    assertNull(log.projectOf("review", "r", Map.of("spec_id", "unknown")));
    assertNull(log.projectOf("file", "no-slash", Map.of("mode", 420)));
    assertEquals("alpha", log.projectOf("file", "alpha/a/b.md", Map.of("mode", 420)));
    assertEquals("alpha", log.projectOf("project", "alpha", Map.of()));
    assertEquals(Optional.empty(), log.headProject("spec", "never-held"));
  }

  @Test
  void aWithdrawalADeletionAnErasureAndAPurgeAllKeepTheHeadsProject() {
    var seeded = seed("alpha");
    var erasure = new Erasure(db);

    specs.delete("alpha-work");
    assertEquals(Optional.of("alpha"), log.headProject("spec", "alpha-work"));
    Actor.run(Actor.main(), () -> new ReviewStore(db).applyRevision(seeded.review(), null, null));
    assertEquals(
        ChangeLog.Entry.DENIED, log.head("review", seeded.review()).orElseThrow().origin());
    assertEquals(Optional.of("alpha"), log.headProject("review", seeded.review()));

    erasure.erase(erasure.closure(List.of(new Erasure.Target("spec", "alpha-work"))), "local");

    for (var target :
        List.of(
            new Erasure.Target("spec", "alpha-work"),
            new Erasure.Target("room", "alpha-work"),
            new Erasure.Target("message", seeded.message()),
            new Erasure.Target("run", seeded.run()),
            new Erasure.Target("review", seeded.review()))) {
      assertTrue(log.isErased(target.type(), target.id()), target.toString());
      assertEquals(
          Optional.of("alpha"), log.headProject(target.type(), target.id()), target.toString());
    }
    log.purge("spec", "alpha-work");
    assertEquals(Optional.of("alpha"), log.headProject("spec", "alpha-work"));
    assertTrue(log.isErased("spec", "alpha-work"));
  }

  @Test
  void aSpecMovedToAnotherProjectMovesItsHead() {
    specs.create(spec("mover", "alpha"));

    specs.reproject("alpha", "beta");

    assertEquals(Optional.of("beta"), log.headProject("spec", "mover"));
  }

  @Test
  void aReviewAndAMessageStayFiledWhereTheyWereFirstWrittenWhenTheirSpecMoves() {
    specs.create(spec("mover", "alpha"));
    var reviews = new ReviewStore(db);
    var review = reviews.createReview("mover", 1);
    var message = new MessageStore(db).append("mover", "ada", "said in alpha", null);

    specs.reproject("alpha", "beta");
    reviews.supersedeForSpec("mover");
    log.append("message", message.id(), "2-again", "local", false, "{\"spec_id\": \"mover\"}");
    var later = reviews.createReview("mover", 1);

    assertEquals("beta", log.projectOf("review", review, Map.of("spec_id", "mover")));
    assertEquals(Optional.of("alpha"), log.headProject("review", review));
    assertEquals(Optional.of("alpha"), log.headProject("message", message.id()));
    assertEquals(
        Optional.of("beta"),
        log.headProject("review", later),
        "what is first written after the move is the new project's");
  }

  @Test
  void aHeadFiledUnderNoProjectTakesTheOneItsNextEntryNames() {
    var reviews = new ReviewStore(db);
    specs.create(spec("late", "alpha"));
    var review = reviews.createReview("late", 1);
    db.execute("UPDATE change_heads SET project = NULL WHERE entity_type = 'review'");

    reviews.supersedeForSpec("late");

    assertEquals(Optional.of("alpha"), log.headProject("review", review));
  }

  @Test
  void forgettingAnEntityLeavesNoEntryHeadOrOfferOfIt() {
    seed("alpha");
    log.recordOffer("spec", "alpha-work", Map.of("title", "t"), null);

    log.forget("spec", "alpha-work");

    assertEquals(List.of(), log.history("spec", "alpha-work"));
    assertEquals(Optional.empty(), log.head("spec", "alpha-work"));
    assertEquals(Optional.empty(), log.offer("spec", "alpha-work"));
    assertEquals(Optional.of("alpha"), log.headProject("room", "alpha-work"));
  }

  private record Seeded(String message, String run, String review) {}

  private Seeded seed(String project) {
    var id = project + "-work";
    new ProjectStore(db).upsert(project, "name: " + project + "\n");
    specs.create(spec(id, project));
    new RoomStore(db)
        .create(
            new RoomStore.RoomRow(
                id, project, "room", "ada", null, null, "ada", null, null, "ada"));
    var messages = new MessageStore(db);
    var first = messages.append(id, "ada", "a word", null);
    messages.append(id, "ada", "a reply", first.id());
    var run = DateTimeUtils.newId().toString();
    var runs = new RunStore(db);
    runs.create(run, project, id, "ada", "build", "claude", "b", "t", null, null, "/log", "u");
    runs.complete(run, "completed", 0);
    var review = new ReviewStore(db).createReview(id, 1);
    ContentFixtures.put(new FileStore(db), project, "notes.md", "shared in " + project);
    return new Seeded(first.id(), run, review);
  }

  private static SpecStore.SpecRow spec(String id, String project) {
    return new SpecStore.SpecRow(
        id,
        project,
        "the " + project + " spec",
        SpecStatus.PENDING,
        "ada",
        null,
        null,
        null,
        null,
        0,
        "ada",
        "",
        "",
        "ada",
        List.of(),
        List.of());
  }
}
