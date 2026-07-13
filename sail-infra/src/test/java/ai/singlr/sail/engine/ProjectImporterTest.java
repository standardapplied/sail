/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.sail.store.ProjectStore;
import ai.singlr.sail.store.SchemaManager;
import ai.singlr.sail.store.Sqlite;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ProjectImporterTest {

  @TempDir Path tempDir;
  private Sqlite db;
  private ProjectStore store;
  private Path projectsDir;

  @BeforeEach
  void setUp() throws IOException {
    db = Sqlite.open(tempDir.resolve("test.db"));
    new SchemaManager(db).migrate();
    store = new ProjectStore(db);
    projectsDir = tempDir.resolve("projects");
    Files.createDirectories(projectsDir);
  }

  @AfterEach
  void tearDown() {
    if (db != null) db.close();
  }

  private void writeProject(String name, String definition) throws IOException {
    var dir = projectsDir.resolve(name);
    Files.createDirectories(dir);
    Files.writeString(dir.resolve(SailPaths.PROJECT_DESCRIPTOR), definition);
  }

  @Test
  void importsEveryDescriptorIntoTheCatalog() throws IOException {
    writeProject("acme", "name: acme\n");
    writeProject("beta", "name: beta\n");

    var report = new ProjectImporter(projectsDir, store).importAll();

    assertEquals(2, report.imported());
    assertEquals("name: acme\n", store.findByName("acme").orElseThrow().definition());
    assertEquals("name: beta\n", store.findByName("beta").orElseThrow().definition());
  }

  @Test
  void leavesAlreadyCataloguedProjectsUntouched() throws IOException {
    writeProject("acme", "name: acme\nv: 1\n");
    new ProjectImporter(projectsDir, store).importAll();

    writeProject("acme", "name: acme\nv: 2\n");
    var report = new ProjectImporter(projectsDir, store).importAll();

    assertEquals(0, report.imported(), "a catalogued project is not re-imported on later runs");
    assertEquals(1, report.skipped());
    assertEquals(1, store.list().size());
    assertEquals(
        "name: acme\nv: 1\n",
        store.findByName("acme").orElseThrow().definition(),
        "the database is the source of truth — a disk edit never overwrites it");
  }

  @Test
  void skipsDirectoriesWithoutADescriptor() throws IOException {
    Files.createDirectories(projectsDir.resolve("empty"));
    writeProject("acme", "name: acme\n");

    var report = new ProjectImporter(projectsDir, store).importAll();

    assertEquals(1, report.imported());
    assertTrue(store.findByName("empty").isEmpty());
  }

  @Test
  void emptyWhenProjectsDirIsAbsent() {
    var report = new ProjectImporter(tempDir.resolve("nonexistent"), store).importAll();

    assertEquals(0, report.imported());
    assertEquals(0, report.skipped());
    assertTrue(store.list().isEmpty());
  }
}
