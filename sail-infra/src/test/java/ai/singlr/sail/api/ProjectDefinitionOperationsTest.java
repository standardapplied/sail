/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import ai.singlr.sail.config.ProjectDefinition;
import ai.singlr.sail.config.ProjectDefinitionJson;
import ai.singlr.sail.config.TestDefinitions;
import ai.singlr.sail.identity.Acting;
import ai.singlr.sail.identity.ActingAs;
import ai.singlr.sail.identity.Actor;
import ai.singlr.sail.identity.ProjectScope;
import ai.singlr.sail.identity.Role;
import ai.singlr.sail.store.ProjectStore;
import ai.singlr.sail.store.SchemaManager;
import ai.singlr.sail.store.Sqlite;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

@ActingAs(value = Actor.Lane.API, handle = "ada")
class ProjectDefinitionOperationsTest {

  private Sqlite db;
  private ProjectStore projects;
  private ProjectDefinitionOperations operations;

  @BeforeEach
  void setUp() {
    db = Sqlite.openMemory();
    new SchemaManager(db).migrate();
    projects = new ProjectStore(db);
    operations = new ProjectDefinitionOperations(projects);
    Acting.system(() -> projects.upsert(TestDefinitions.yaml("name: acme\nimage: ubuntu/24.04\n")));
  }

  @AfterEach
  void tearDown() {
    db.close();
  }

  @Test
  void aDefinitionIsServedWithEachSectionsTag() {
    var read = operations.definition("acme");

    assertEquals("acme", read.name());
    assertEquals(Map.of("name", "acme", "image", "ubuntu/24.04"), read.toMap().get("definition"));
    assertEquals(ProjectStore.tagOf("\"ubuntu/24.04\""), read.tags().get("image"));
    assertEquals(ProjectStore.tagOf(null), read.tags().get("pipeline"));
    assertEquals("sail", read.updatedBy());
  }

  @Test
  void aSectionIsWrittenOverTheTagItWasReadAtAndAStaleOneIsRefused() {
    var tag = operations.definition("acme").tags().get("image");

    var written = operations.writeSection("acme", "image", "debian/12", "\"" + tag + "\"");
    var stale =
        assertThrows(
            ApiException.class, () -> operations.writeSection("acme", "image", "x/1", tag));

    assertEquals("{\"name\": \"acme\", \"image\": \"debian/12\"}", written.document());
    assertEquals(ErrorCode.PRECONDITION_FAILED, stale.failure().errorCode());
    assertEquals("The image section of 'acme' changed since you read it.", stale.getMessage());
    assertEquals("debian/12", projects.findByName("acme").orElseThrow().definition().image());
    assertEquals(
        "x/1",
        read(operations.writeSection("acme", "image", "x/1", "*")).image(),
        "* writes over whatever the section holds");
  }

  @Test
  void aSectionWithNoTagIsWrittenOverWhateverItHoldsAndNullUnsetsIt() {
    operations.writeSection("acme", "description", "Storefront", null);
    var unset = operations.writeSection("acme", "image", null, "  ");

    assertEquals("Storefront", read(unset).description());
    assertNull(read(unset).image());
  }

  private static ProjectDefinition read(ProjectDefinitionResponse response) {
    return ProjectDefinitionJson.read(response.document());
  }

  @Test
  void aProjectIsCreatedOnceAndAnExistingNameIsAConflict() {
    var created = operations.create(Map.of("name", "beta", "description", "Second"));
    var taken = assertThrows(ApiException.class, () -> operations.create(Map.of("name", "beta")));

    assertEquals("beta", created.name());
    assertEquals(ErrorCode.CONFLICT, taken.failure().errorCode());
    assertEquals("A project named 'beta' already exists.", taken.getMessage());
  }

  @Test
  void aProjectOutsideTheReadersScopeOrNotHeldIsNotFound() {
    var scoped =
        new Actor("mady", Role.MEMBER, Actor.Lane.API, null, ProjectScope.of(Set.of("beta")));

    var outside =
        assertThrows(
            ApiException.class, () -> Acting.by(scoped, () -> operations.definition("acme")));
    var missing = assertThrows(ApiException.class, () -> operations.definition("ghost"));
    var createdOutside =
        assertThrows(
            ApiException.class,
            () -> Acting.by(scoped, () -> operations.create(Map.of("name", "gamma"))));

    assertEquals(ErrorCode.PROJECT_NOT_IN_CATALOG, outside.failure().errorCode());
    assertEquals(ErrorCode.PROJECT_NOT_IN_CATALOG, missing.failure().errorCode());
    assertEquals(ErrorCode.PROJECT_NOT_IN_CATALOG, createdOutside.failure().errorCode());
  }

  @Test
  void aSectionThereIsNoneOfOrAValueThatIsNoneIsRefused() {
    assertEquals(
        "'git' is not a section of a project definition. The sections are: description, image,"
            + " resources, runtimes, repos, services, processes, packages, agent, pipeline.",
        assertThrows(
                IllegalArgumentException.class,
                () -> operations.writeSection("acme", "git", Map.of(), null))
            .getMessage());
    assertEquals(
        "resources.cpu must be a whole number.",
        assertThrows(
                IllegalArgumentException.class,
                () ->
                    operations.writeSection(
                        "acme",
                        "resources",
                        Map.of("cpu", "4", "memory", "8GB", "disk", "50GB"),
                        null))
            .getMessage());
  }
}
