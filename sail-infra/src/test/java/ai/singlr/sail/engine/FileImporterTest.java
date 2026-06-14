/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.sail.store.FileStore;
import ai.singlr.sail.store.SchemaManager;
import ai.singlr.sail.store.Sqlite;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Importing is the disk-to-DB boundary: it captures the workspace files an FDE already has so they
 * become the shared, replicated copy, and it is idempotent so every upgrade can run it for free.
 */
class FileImporterTest {

  @TempDir Path tempDir;
  private Sqlite db;
  private FileStore files;
  private Path projectsDir;
  private FileImporter importer;

  @BeforeEach
  void setUp() {
    db = Sqlite.open(tempDir.resolve("test.db"));
    new SchemaManager(db).migrate();
    files = new FileStore(db);
    projectsDir = tempDir.resolve("projects");
    importer = new FileImporter(projectsDir, files);
  }

  @AfterEach
  void tearDown() {
    db.close();
  }

  private void writeOnDisk(String project, String path, String text) throws Exception {
    var file = projectsDir.resolve(project).resolve("files").resolve(path);
    Files.createDirectories(file.getParent());
    Files.writeString(file, text);
  }

  private static String b64(String text) {
    return Base64.getEncoder().encodeToString(text.getBytes());
  }

  @Test
  void importsEveryFilePreservingTheRelativeTree() throws Exception {
    writeOnDisk("acme", "scripts/deploy.sh", "hello");
    writeOnDisk("acme", "README.md", "docs");

    var report = importer.importAll();

    assertEquals(2, report.imported());
    assertEquals(b64("hello"), files.find("acme", "scripts/deploy.sh").orElseThrow().content());
    assertEquals(b64("docs"), files.find("acme", "README.md").orElseThrow().content());
  }

  @Test
  void importsAcrossMultipleProjects() throws Exception {
    writeOnDisk("acme", "a.txt", "A");
    writeOnDisk("globex", "b.txt", "B");

    var report = importer.importAll();

    assertEquals(2, report.imported());
    assertTrue(files.find("acme", "a.txt").isPresent());
    assertTrue(files.find("globex", "b.txt").isPresent());
  }

  @Test
  void reRunningImportsNothingWhenContentIsUnchanged() throws Exception {
    writeOnDisk("acme", "a.txt", "A");
    importer.importAll();

    var report = importer.importAll();

    assertEquals(0, report.imported());
  }

  @Test
  void reImportsOnlyTheFileWhoseContentChanged() throws Exception {
    writeOnDisk("acme", "a.txt", "A");
    writeOnDisk("acme", "b.txt", "B");
    importer.importAll();
    writeOnDisk("acme", "a.txt", "A2");

    var report = importer.importAll();

    assertEquals(1, report.imported());
    assertEquals(b64("A2"), files.find("acme", "a.txt").orElseThrow().content());
  }

  @Test
  void isQuietWhenThereIsNoProjectsDirectory() {
    var report = importer.importAll();

    assertEquals(0, report.imported());
    assertTrue(report.notes().isEmpty());
  }

  @Test
  void ignoresAProjectWithNoFilesDirectory() throws Exception {
    Files.createDirectories(projectsDir.resolve("acme"));

    var report = importer.importAll();

    assertEquals(0, report.imported());
  }

  @Test
  void reportsANoteWhenTheProjectsDirectoryCannotBeScanned() throws Exception {
    Files.createDirectories(projectsDir);
    var perms = Files.getPosixFilePermissions(projectsDir);
    Files.setPosixFilePermissions(projectsDir, java.util.Set.of());
    try {
      var report = importer.importAll();

      assertEquals(0, report.imported());
      assertEquals(1, report.notes().size());
      assertTrue(report.notes().get(0).startsWith("Could not scan project files:"));
    } finally {
      Files.setPosixFilePermissions(projectsDir, perms);
    }
  }
}
