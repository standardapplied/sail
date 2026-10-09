/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.sail.identity.Acting;
import ai.singlr.sail.store.ProjectStore;
import ai.singlr.sail.store.SchemaManager;
import ai.singlr.sail.store.Sqlite;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ProjectReaderTest {

  private static final String ACME = "name: acme\nagent:\n  type: codex\n";

  @TempDir Path dir;

  @Test
  void theCatalogRowIsReadAsStoredAndAProjectWithNoRowIsAbsent() {
    try (var db = Sqlite.open(dir.resolve("sail.db"))) {
      new SchemaManager(db).migrate();
      var store = new ProjectStore(db);
      Acting.system(
          () -> store.upsert("acme", ACME + "git:\n  name: Mady\n  email: mady@example.dev\n"));
      var reader = ProjectReader.ofCatalog(store);

      var acme = reader.require("acme");

      assertEquals("codex", acme.agent().type());
      assertEquals("${GIT_NAME}", acme.git().name(), "placeholders are left as the row holds them");
      assertTrue(reader.read("other").isEmpty());
    }
  }

  @Test
  void aProjectNotInTheCatalogIsNotFoundAndTheErrorSaysHowToGetOne() {
    var reader = ProjectReader.ofCatalog(null);

    assertTrue(reader.read("acme").isEmpty(), "a null store holds no project");
    var refused = assertThrows(ApiException.class, () -> reader.require("acme"));

    assertEquals(ErrorCode.PROJECT_DESCRIPTOR_NOT_FOUND, refused.failure().errorCode());
    assertEquals("Project 'acme' is not in the catalog.", refused.getMessage());
    assertTrue(
        refused.failure().action().contains("sudo sail migrate"), refused.failure().action());
    assertTrue(
        refused.failure().action().contains("sail project apply -f <file>"),
        refused.failure().action());
  }

  @Test
  void aRowThatSetsADeletedKeyIsUnreadableNamingTheProjectAndWhereTheTextGoes() {
    try (var db = Sqlite.open(dir.resolve("sail.db"))) {
      new SchemaManager(db).migrate();
      var store = new ProjectStore(db);
      Acting.system(() -> store.upsert("acme", ACME));
      db.execute(
          "UPDATE projects SET definition = ? WHERE name = ?",
          ACME + "  build_skill: acme-build\n",
          "acme");

      var unreadable =
          assertThrows(
              ProjectReader.Unreadable.class, () -> ProjectReader.ofCatalog(store).read("acme"));

      assertTrue(
          unreadable
              .getMessage()
              .startsWith(
                  "The definition of project 'acme' in the catalog could not be read:"
                      + " agent.build_skill is no longer read: sail's work prompt says how a build"
                      + " runs."),
          unreadable.getMessage());
    }
  }

  @Test
  void aRowThatDoesNotParseIsUnreadableNamingTheProjectAndWhatTheParserSaid() {
    try (var db = Sqlite.open(dir.resolve("sail.db"))) {
      new SchemaManager(db).migrate();
      var store = new ProjectStore(db);
      Acting.system(() -> store.upsert("acme", ACME));
      db.execute(
          "UPDATE projects SET definition = ? WHERE name = ?", "agent: [unterminated", "acme");

      var unreadable =
          assertThrows(
              ProjectReader.Unreadable.class, () -> ProjectReader.ofCatalog(store).read("acme"));

      assertTrue(
          unreadable
              .getMessage()
              .startsWith("The definition of project 'acme' in the catalog could not be read: "),
          unreadable.getMessage());
      assertEquals(unreadable.getCause().getMessage(), unreadable.getMessage().split(": ", 2)[1]);
      assertFalse(unreadable.getMessage().contains("sail.yaml"));
    }
  }

  @Test
  void aRowThatIsYamlButNotADefinitionIsUnreadableToo() {
    try (var db = Sqlite.open(dir.resolve("sail.db"))) {
      new SchemaManager(db).migrate();
      var store = new ProjectStore(db);
      Acting.system(() -> store.upsert("acme", ACME));
      db.execute(
          "UPDATE projects SET definition = ? WHERE name = ?",
          ACME + "  review_pipeline:\n    stages:\n      - type: agent\n",
          "acme");

      var unreadable =
          assertThrows(
              ProjectReader.Unreadable.class, () -> ProjectReader.ofCatalog(store).read("acme"));

      assertTrue(
          unreadable.getMessage().startsWith("The definition of project 'acme' in the catalog"),
          unreadable.getMessage());
      assertTrue(
          unreadable.getCause().getMessage().contains("requires a name"),
          unreadable.getCause().getMessage());
    }
  }

  @Test
  void aFailureOfTheStoreItselfIsNotWrapped() {
    var db = Sqlite.open(dir.resolve("sail.db"));
    new SchemaManager(db).migrate();
    var reader = ProjectReader.ofCatalog(new ProjectStore(db));
    db.close();

    var failure = assertThrows(RuntimeException.class, () -> reader.read("acme"));

    assertFalse(failure instanceof ProjectReader.Unreadable, failure.toString());
  }

  @Test
  void aTestReaderAnswersEveryNameWithTheFileAndIsUnreadableAsTheCatalogWouldBe() throws Exception {
    var file = dir.resolve("sail.yaml");
    var reader = TestProjects.reading(file);

    assertTrue(reader.read("acme").isEmpty());
    Files.writeString(file, ACME);
    assertEquals("codex", reader.require("anything").agent().type());
    Files.writeString(file, "agent: [unterminated");
    var unreadable = assertThrows(ProjectReader.Unreadable.class, () -> reader.read("acme"));
    assertTrue(unreadable.getMessage().startsWith("The definition of project 'acme'"));
    assertEquals(unreadable.getCause().getMessage(), unreadable.getMessage().split(": ", 2)[1]);
  }
}
