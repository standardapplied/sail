/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.sail.config.ProjectDefinition;
import ai.singlr.sail.config.ProjectDefinition.Section;
import ai.singlr.sail.config.ProjectDefinitionJson;
import ai.singlr.sail.config.TestDefinitions;
import ai.singlr.sail.identity.ActingAs;
import ai.singlr.sail.identity.Actor;
import ai.singlr.sail.identity.Role;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@ActingAs(value = Actor.Lane.CLI, handle = "uday")
class ProjectStoreTest {

  @TempDir Path tempDir;
  private Sqlite db;
  private ProjectStore store;

  @BeforeEach
  void setUp() {
    db = Sqlite.open(tempDir.resolve("test.db"));
    new SchemaManager(db).migrate();
    store = new ProjectStore(db);
  }

  @AfterEach
  void tearDown() {
    if (db != null) db.close();
  }

  @Test
  void renameTombstonesTheOldIdentityAndCreatesTheNew() {
    store.upsert(TestDefinitions.yaml("name: old\nimage: ubuntu/24.04\n"));
    var oldRev = store.latestRev("old");

    store.rename("old", "renamed", TestDefinitions.yaml("name: renamed\nimage: ubuntu/24.04\n"));

    assertTrue(store.findByName("old").isEmpty(), "the old name is gone from the catalog");
    assertTrue(
        Snapshots.isDeletionMark(store.currentForSync("old")),
        "the old identity keeps a resurrection-blocking tombstone so a stale peer cannot revive it");
    var row = store.findByName("renamed").orElseThrow();
    assertEquals("renamed", row.definition().name());
    assertEquals("ubuntu/24.04", row.definition().image());
    assertNotEquals(
        oldRev, store.latestRev("renamed"), "the new identity is a fresh revision, not a re-key");
  }

  @Test
  void renameIsIdempotentOnceTheOldNameIsGone() {
    store.upsert(TestDefinitions.named("old"));
    store.rename("old", "renamed", TestDefinitions.named("renamed"));

    store.rename("old", "renamed", TestDefinitions.named("renamed"));

    assertTrue(store.findByName("renamed").isPresent());
  }

  @Test
  void renameOntoALiveNameIsRejectedAndLeavesBothIntact() {
    store.upsert(TestDefinitions.named("old"));
    store.upsert(TestDefinitions.named("taken"));

    assertThrows(
        IllegalStateException.class,
        () -> store.rename("old", "taken", TestDefinitions.named("taken")));

    assertTrue(store.findByName("old").isPresent(), "the failed rename left the old name intact");
    assertFalse(
        Snapshots.isDeletionMark(store.currentForSync("old")),
        "a rejected rename records no tombstone");
  }

  @Test
  void plainDeleteDoesNotBlockResurrection() {
    store.upsert(TestDefinitions.named("p"));

    store.delete("p");

    assertTrue(store.findByName("p").isEmpty(), "the plain delete removed the catalog row");
    assertFalse(
        Snapshots.isDeletionMark(store.currentForSync("p")),
        "only a rename tombstone is authoritative over a stale create; a plain delete is not");
  }

  @Test
  void upsertStoresTheDefinitionAsItsJsonDocument() {
    store.upsert(TestDefinitions.yaml(RESOURCED));

    var row = store.findByName("acme").orElseThrow();
    assertEquals("acme", row.name());
    assertEquals(
        "{\"name\": \"acme\", \"resources\": {\"cpu\": 2, \"memory\": \"8GB\", \"disk\":"
            + " \"50GB\"}}",
        row.document());
    assertEquals(TestDefinitions.yaml(RESOURCED), row.definition());
    assertEquals("uday", row.createdBy());
    assertEquals("uday", row.updatedBy());
    assertEquals(row.createdAt(), row.updatedAt());
  }

  private static final String RESOURCED =
      "name: acme\nresources:\n  cpu: 2\n  memory: 8GB\n  disk: 50GB\n";

  @Test
  void upsertReplacesDefinitionPreservingCreationProvenance() {
    store.upsert(TestDefinitions.described("acme", "v1"));
    var created = store.findByName("acme").orElseThrow();

    Actor.run(
        new Actor("ada", Role.MEMBER, Actor.Lane.API),
        () -> store.upsert(TestDefinitions.described("acme", "v2")));

    var updated = store.findByName("acme").orElseThrow();
    assertEquals("v2", updated.definition().description());
    assertEquals("uday", updated.createdBy(), "created_by is preserved across an update");
    assertEquals(created.createdAt(), updated.createdAt(), "created_at is preserved");
    assertEquals("ada", updated.updatedBy());
    assertTrue(updated.updatedAt().compareTo(updated.createdAt()) >= 0, "updated_at is monotonic");
  }

  @Test
  void listIsOrderedByName() {
    store.upsert(TestDefinitions.described("zeta", "z"));
    store.upsert(TestDefinitions.described("alpha", "a"));
    store.upsert(TestDefinitions.described("mu", "m"));

    assertEquals(
        List.of("alpha", "mu", "zeta"),
        store.list().stream().map(ProjectStore.ProjectRow::name).toList());
  }

  @Test
  void findByNameIsEmptyWhenAbsent() {
    assertTrue(store.findByName("ghost").isEmpty());
  }

  @Test
  void deleteRemovesAndReportsWhetherARowWentAway() {
    store.upsert(TestDefinitions.described("acme", "x"));

    assertTrue(store.delete("acme"));
    assertTrue(store.findByName("acme").isEmpty());
    assertFalse(store.delete("acme"));
  }

  @Test
  void aMachineTokenLeavesNoAuthor() {
    Actor.run(
        new Actor(null, Role.MEMBER, Actor.Lane.API),
        () -> store.upsert(TestDefinitions.described("acme", "x")));
    assertNull(store.findByName("acme").orElseThrow().createdBy());
  }

  @Test
  void aDefinitionIsCheckedWholeBeforeItIsHeld() {
    var clash =
        TestDefinitions.yaml(
                "name: acme\nservices:\n  db:\n    image: postgres:16\n    ports: [5432]\n")
            .withRepos(List.of(new ProjectDefinition.Repo("git@github.com:a/b.git", "b", null)));

    assertThrows(IllegalArgumentException.class, () -> store.upsert(clash));
    assertThrows(IllegalArgumentException.class, () -> store.create(clash));
    assertTrue(store.findByName("acme").isEmpty(), "a refused definition is held nowhere");
  }

  @Test
  void createRefusesANameTheCatalogHolds() {
    var created = store.create(TestDefinitions.described("acme", "v1"));

    assertEquals("v1", created.definition().description());
    assertEquals(
        "A project named 'acme' already exists.",
        assertThrows(
                ProjectStore.NameTaken.class,
                () -> store.create(TestDefinitions.described("acme", "v2")))
            .getMessage());
    assertEquals("v1", store.findByName("acme").orElseThrow().definition().description());
  }

  @Test
  void eachSectionIsAFieldOfItsOwnInTheComparison() {
    store.upsert(TestDefinitions.yaml(RESOURCED));

    var snapshot = store.comparableSnapshot("acme");

    assertEquals(
        "{\"cpu\": 2, \"memory\": \"8GB\", \"disk\": \"50GB\"}", snapshot.get("resources"));
    assertEquals("acme", snapshot.get("name"), "a live project's name is a work field");
    assertFalse(snapshot.containsKey("image"), "an unset section has no field");
    assertFalse(snapshot.containsKey("definition"), "no field holds the definition whole");
  }

  @Test
  void aSectionWriteIsOneRevisionOverTheSectionItsWriterRead() {
    store.upsert(TestDefinitions.yaml(RESOURCED));
    var before = store.latestRev("acme");
    var read =
        ProjectStore.tagOf(
            ProjectDefinitionJson.section(
                store.findByName("acme").orElseThrow().definition(), Section.IMAGE));

    var written = store.writeSection("acme", Section.IMAGE, "\"ubuntu/24.04\"", read);

    assertEquals("ubuntu/24.04", written.definition().image());
    assertEquals(2, written.definition().resources().cpu(), "the other sections are kept");
    assertNotEquals(before, store.latestRev("acme"));
    var stale =
        assertThrows(
            ProjectStore.StaleSection.class,
            () -> store.writeSection("acme", Section.IMAGE, "\"debian/12\"", read));
    assertEquals(
        "The image section of project 'acme' changed since it was read. Read it again, then write"
            + " it.",
        stale.getMessage());
    assertEquals("ubuntu/24.04", stale.current().definition().image());
    assertEquals("ubuntu/24.04", store.findByName("acme").orElseThrow().definition().image());
    assertEquals(
        "debian/12",
        store.writeSection("acme", Section.IMAGE, "\"debian/12\"", null).definition().image(),
        "no tag writes over whatever the section holds");
    assertNull(
        store.writeSection("acme", Section.IMAGE, null, null).definition().image(),
        "null unsets the section");
  }

  @Test
  void aWriteOfSeveralSectionsIsOneRevisionOrNone() {
    store.upsert(TestDefinitions.yaml(RESOURCED));
    var held = store.findByName("acme").orElseThrow().definition();
    var values = new LinkedHashMap<Section, String>();
    values.put(Section.IMAGE, "\"ubuntu/24.04\"");
    values.put(Section.DESCRIPTION, "\"Storefront\"");
    var tags = new LinkedHashMap<Section, String>();
    tags.put(Section.IMAGE, ProjectStore.tagOf(ProjectDefinitionJson.section(held, Section.IMAGE)));
    tags.put(Section.DESCRIPTION, "stale");
    var before = store.latestRev("acme");

    assertThrows(ProjectStore.StaleSection.class, () -> store.writeSections("acme", values, tags));
    assertEquals(before, store.latestRev("acme"), "a refused write records nothing");
    assertNull(store.findByName("acme").orElseThrow().definition().image());

    tags.remove(Section.DESCRIPTION);
    var written = store.writeSections("acme", values, tags).definition();
    assertEquals("ubuntu/24.04", written.image());
    assertEquals("Storefront", written.description());
    assertEquals(
        "Project 'ghost' is not in the catalog.",
        assertThrows(
                IllegalArgumentException.class,
                () -> store.writeSection("ghost", Section.IMAGE, null, null))
            .getMessage());
  }

  @Test
  void equalSectionsHaveEqualTagsAndAnUnsetSectionHasOne() {
    assertEquals(ProjectStore.tagOf("{\"cpu\": 2}"), ProjectStore.tagOf("{\"cpu\": 2}"));
    assertNotEquals(ProjectStore.tagOf("{\"cpu\": 2}"), ProjectStore.tagOf("{\"cpu\": 3}"));
    assertEquals(ProjectStore.tagOf("null"), ProjectStore.tagOf(null));
  }
}
