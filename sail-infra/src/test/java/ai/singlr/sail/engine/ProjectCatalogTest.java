/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.engine;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.sail.common.Remedied;
import ai.singlr.sail.config.ProjectDefinition;
import ai.singlr.sail.config.ProjectDefinition.Section;
import ai.singlr.sail.config.ProjectDefinitionJson;
import ai.singlr.sail.config.TestDefinitions;
import ai.singlr.sail.identity.Acting;
import ai.singlr.sail.identity.Actor;
import ai.singlr.sail.identity.ProjectScope;
import ai.singlr.sail.identity.Role;
import ai.singlr.sail.store.ChangeLog;
import ai.singlr.sail.store.Erasure;
import ai.singlr.sail.store.ProjectStore;
import ai.singlr.sail.store.SchemaManager;
import ai.singlr.sail.store.Sqlite;
import ai.singlr.sail.store.SyncScope;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ProjectCatalogTest {

  @TempDir Path dir;

  @Test
  void aPrunedNameIsRefusedBeforeAnythingIsProvisionedUnderItAndOthersPass() {
    var catalog = dir.resolve("sail.db");
    try (var db = Sqlite.open(catalog)) {
      new SchemaManager(db).migrate();
      Acting.system(() -> new ProjectStore(db).upsert(TestDefinitions.named("gone")));
      Acting.system(() -> new ProjectStore(db).upsert(TestDefinitions.named("kept")));
      var erasure = new Erasure(db);
      Acting.system(
          () ->
              erasure.erase(
                  erasure.closure(List.of(new Erasure.Target(Erasure.PROJECT, "gone"))), "local"));
    }

    var refused =
        assertThrows(
            IllegalStateException.class, () -> new ProjectCatalog(catalog).requireUnpruned("gone"));

    assertTrue(refused.getMessage().contains("'gone' was pruned"), refused.getMessage());
    assertDoesNotThrow(() -> new ProjectCatalog(catalog).requireUnpruned("kept"));
    assertDoesNotThrow(() -> new ProjectCatalog(catalog).requireUnpruned("fresh"));
  }

  @Test
  void aNodeRefusesAProjectOutsideTheScopeMainNamedBeforeAnythingIsWritten() {
    var catalog = dir.resolve("sail.db");
    var nia = new Actor("nia", Role.ADMIN, Actor.Lane.CLI);
    assertDoesNotThrow(() -> new ProjectCatalog(catalog).requireInScope(() -> "client2", "any"));
    assertFalse(Files.exists(catalog), "a check must not create the catalog");
    try (var db = Sqlite.open(catalog)) {
      new SchemaManager(db).migrate();
    }
    assertDoesNotThrow(() -> new ProjectCatalog(catalog).requireInScope(() -> "client2", "beta"));
    try (var db = Sqlite.open(catalog)) {
      new SyncScope(db).record(ProjectScope.of(List.of("alpha")));
    }

    var refused =
        assertThrows(
            IllegalStateException.class,
            () -> new ProjectCatalog(catalog).requireInScope(() -> "client2", "beta"));

    assertEquals(
        "Project 'beta' is outside the projects main grants this box, so main would take nothing"
            + " of it. If an admin granted it since your last sync, run: sudo sail sync. Otherwise"
            + " ask an admin to grant it on main: sudo sail fde grant client2 beta.",
        OperatorCommand.SUDO.render((Remedied) refused));
    assertEquals(
        "Project 'beta' is outside the projects main grants this box, so main would take nothing"
            + " of it. If an admin granted it since your last sync, run: sail sync. Otherwise ask"
            + " an admin to grant it on main: sail fde grant nia beta.",
        assertThrows(
                IllegalStateException.class,
                () -> new ProjectCatalog(catalog).requireRecordable("beta", nia))
            .getMessage());
    assertEquals(
        "Project 'beta' is outside the projects main grants this box, so main would take nothing"
            + " of it. If an admin granted it since your last sync, run: sail sync. Otherwise ask"
            + " an admin to grant it on main: sail fde grant <handle> beta.",
        assertThrows(
                IllegalStateException.class,
                () -> new ProjectCatalog(catalog).requireInScope(() -> null, "beta"))
            .getMessage());
    assertDoesNotThrow(() -> new ProjectCatalog(catalog).requireInScope(() -> "client2", "alpha"));
    assertDoesNotThrow(() -> new ProjectCatalog(catalog).requireRecordable("alpha", nia));
  }

  @Test
  void aBoxWithNoCatalogYetRefusesNothingAndIsLeftWithoutOne() {
    var missing = dir.resolve("none.db");

    assertDoesNotThrow(() -> new ProjectCatalog(missing).requireUnpruned("any"));
    assertFalse(Files.exists(missing), "a dry run must not create the catalog");
  }

  @Test
  void aDefinitionIsRecordedAsTheOperatorTheCallerResolved() {
    var catalog = dir.resolve("sail.db");
    var mady = new Actor("mady", Role.MEMBER, Actor.Lane.CLI);

    assertDoesNotThrow(
        () -> new ProjectCatalog(catalog).create(TestDefinitions.named("web"), mady));

    try (var db = Sqlite.open(catalog)) {
      assertEquals("mady", new ProjectStore(db).findByName("web").orElseThrow().updatedBy());
      assertEquals("mady", new ChangeLog(db).head("project", "web").orElseThrow().actor());
    }
  }

  @Test
  void aDefinitionTheCatalogDidNotTakeIsNotRecordedAndTheFailureNamesTheProject() throws Exception {
    var unopenable = Files.createDirectory(dir.resolve("not-a-database"));

    var failed =
        assertThrows(
            IllegalStateException.class,
            () ->
                new ProjectCatalog(unopenable)
                    .create(TestDefinitions.named("web"), Actor.cliOperator("uday")));

    assertTrue(
        failed.getMessage().startsWith("Project 'web' was not recorded in the catalog: "),
        failed.getMessage());
    assertNotNull(failed.getCause(), "the cause rides along");
  }

  @Test
  void aCatalogThatCannotBeReadRefusesNothing() throws Exception {
    var unreadable = Files.createDirectory(dir.resolve("not-a-database"));

    assertDoesNotThrow(() -> new ProjectCatalog(unreadable).requireUnpruned("any"));
  }

  @Test
  void aCatalogNotYetMadeHoldsNoDefinitionAndIsNotMadeByTheRead() {
    var missing = dir.resolve("none.db");

    assertEquals(Optional.empty(), new ProjectCatalog(missing).read("web"));
    assertFalse(Files.exists(missing));
  }

  @Test
  void aSecondCreateOfANameIsRefusedAsTheNameTakenAndLeavesTheFirst() {
    var catalog = new ProjectCatalog(dir.resolve("sail.db"));
    var uday = Actor.cliOperator("uday");
    catalog.create(TestDefinitions.described("web", "first"), uday);

    var taken =
        assertThrows(
            ProjectStore.NameTaken.class,
            () -> catalog.create(TestDefinitions.described("web", "second"), uday));

    assertEquals("A project named 'web' already exists.", taken.getMessage());
    assertEquals("first", catalog.read("web").orElseThrow().description());
  }

  @Test
  void aSectionIsWrittenOverTheVersionItWasReadFromAndRefusedOverAnyOther() {
    var catalog = new ProjectCatalog(dir.resolve("sail.db"));
    var uday = Actor.cliOperator("uday");
    catalog.create(TestDefinitions.described("web", "Storefront"), uday);
    var read = catalog.read("web").orElseThrow();

    catalog.writeSection("web", Section.IMAGE, read, image(read, "x/1"), uday);
    var stale =
        assertThrows(
            ProjectStore.StaleSection.class,
            () -> catalog.writeSection("web", Section.IMAGE, read, image(read, "x/2"), uday));

    assertEquals(
        "The image section of project 'web' changed since it was read. Read it again, then write"
            + " it.",
        stale.getMessage());
    assertEquals("x/1", catalog.read("web").orElseThrow().image());
    assertEquals("Storefront", catalog.read("web").orElseThrow().description());
  }

  @Test
  void anEditWritesTheSectionsItChangesInOneRevisionAndOneThatChangesNothingWritesNone() {
    var catalog = new ProjectCatalog(dir.resolve("sail.db"));
    var uday = Actor.cliOperator("uday");
    catalog.create(TestDefinitions.described("web", "Storefront"), uday);
    var read = catalog.read("web").orElseThrow();
    var edited =
        ProjectDefinitionJson.withSection(
            image(read, "x/1"), Section.DESCRIPTION, "\"The storefront\"");

    assertEquals(
        List.of(Section.DESCRIPTION, Section.IMAGE), catalog.writeChanged(read, edited, uday));
    var revision = revision();
    assertEquals(List.of(), catalog.writeChanged(edited, edited, uday));

    assertEquals(edited, catalog.read("web").orElseThrow());
    assertEquals(revision, revision(), "nothing changed, so nothing was written");
    assertThrows(
        ProjectStore.StaleSection.class,
        () -> catalog.writeChanged(read, image(read, "x/2"), uday),
        "an edit of a section written since it was read is refused");
  }

  private String revision() {
    try (var db = Sqlite.open(dir.resolve("sail.db"))) {
      return new ChangeLog(db).head("project", "web").orElseThrow().rev();
    }
  }

  private static ProjectDefinition image(ProjectDefinition definition, String image) {
    return ProjectDefinitionJson.withSection(definition, Section.IMAGE, "\"" + image + "\"");
  }
}
