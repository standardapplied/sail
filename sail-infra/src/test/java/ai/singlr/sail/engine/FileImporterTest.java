/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.sail.config.YamlUtil;
import ai.singlr.sail.identity.Acting;
import ai.singlr.sail.identity.ActingAs;
import ai.singlr.sail.store.ChangeLog;
import ai.singlr.sail.store.Erasure;
import ai.singlr.sail.store.FileStore;
import ai.singlr.sail.store.SchemaManager;
import ai.singlr.sail.store.Sqlite;
import ai.singlr.sail.sync.SyncBox;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Importing is the disk-to-DB boundary: it captures the workspace files an FDE already has so they
 * become the shared, replicated copy, and it is idempotent so every upgrade can run it for free.
 */
@ActingAs
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
    assertEquals(
        b64("hello"),
        ai.singlr.sail.store.ContentFixtures.encoded(files, "acme", "scripts/deploy.sh"));
    assertEquals(
        b64("docs"), ai.singlr.sail.store.ContentFixtures.encoded(files, "acme", "README.md"));
  }

  @Test
  void importsSymlinkTargetPermissionsAndPreservesThemAcrossSync() throws Exception {
    var source = Files.writeString(tempDir.resolve("restricted.txt"), "private");
    WorkspaceFiles.mode(source, 0600);
    var link = projectsDir.resolve("acme/files/linked.txt");
    Files.createDirectories(link.getParent());
    Files.createSymbolicLink(link, source);

    var report = importer.importAll();

    assertEquals(1, report.imported());
    assertTrue(report.notes().isEmpty());
    assertEquals(0600, files.find("acme", "linked.txt").orElseThrow().mode());
    try (var node = new SyncBox(tempDir, "node")) {
      SyncBox.round(db, node.db, "file");
      var nodeProjects = tempDir.resolve("node-projects");
      var materialized =
          new FileMaterializer(new FileStore(node.db), nodeProjects).materialize("acme");
      var copy = nodeProjects.resolve("acme/files/linked.txt");
      assertEquals(1, materialized.written());
      assertEquals("private", Files.readString(copy));
      assertEquals(0600, WorkspaceFiles.mode(copy));
    }
    assertEquals(0, importer.importAll().imported());
    assertEquals(0600, WorkspaceFiles.mode(source));
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
  void aPrunedProjectsFilesLeftOnDiskAreNeverImportedAndTheOthersStillAre() throws Exception {
    writeOnDisk("acme", "a.txt", "A");
    importer.importAll();
    var erasure = new Erasure(db);
    erasure.erase(erasure.closure(List.of(new Erasure.Target(Erasure.PROJECT, "acme"))), "local");
    writeOnDisk("globex", "b.txt", "B");

    var report = importer.importAll();

    assertEquals(1, report.imported());
    assertTrue(files.find("acme", "a.txt").isEmpty());
    assertTrue(
        report.notes().stream()
            .anyMatch(note -> note.contains("'acme'") && note.contains("pruned")),
        report.notes().toString());
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
    assertEquals(b64("A2"), ai.singlr.sail.store.ContentFixtures.encoded(files, "acme", "a.txt"));
  }

  @Test
  void aLegacyScriptsUnchangedCopyAtTheUmasksModeRecordsNothing() throws Exception {
    var id = legacy("deploy.sh", 0644, 0755);

    var report = importer.importAll();

    assertEquals(0, report.imported());
    assertEquals(0755, files.find("acme", "deploy.sh").orElseThrow().mode());
    assertEquals(1, revisions(id));
  }

  @Test
  void aLegacyFileRestrictedOnlyByTheUmaskStaysAsItsRowSays() throws Exception {
    var id = legacy("notes.md", 0600, 0644);

    assertEquals(0, importer.importAll().imported());
    assertEquals(0644, files.find("acme", "notes.md").orElseThrow().mode());
    assertEquals(1, revisions(id));
  }

  @Test
  void aLegacyExecutableKeepsTheExecuteBitSomeoneGaveIt() throws Exception {
    var id = legacy("bin/setup", 0755, 0644);

    assertEquals(1, importer.importAll().imported());
    assertEquals(0755, files.find("acme", "bin/setup").orElseThrow().mode());
    assertEquals(2, revisions(id));
  }

  @Test
  void aChmodBackToAnEarlierModeIsRecordedNotUndone() throws Exception {
    writeOnDisk("acme", "deploy.sh", "echo hi");
    var copy = projectsDir.resolve("acme/files/deploy.sh");
    WorkspaceFiles.mode(copy, 0644);
    importer.importAll();
    WorkspaceFiles.mode(copy, 0755);
    importer.importAll();
    WorkspaceFiles.mode(copy, 0644);

    assertEquals(1, importer.importAll().imported());
    assertEquals(0644, files.find("acme", "deploy.sh").orElseThrow().mode());
    assertEquals(3, revisions(FileStore.idOf("acme", "deploy.sh")));
  }

  /**
   * A shared file as the content migration left it: one revision recording no mode, the row at
   * {@code rowMode}, and the copy on disk at {@code diskMode}.
   */
  private String legacy(String path, int diskMode, int rowMode) throws Exception {
    return Acting.system(
        () -> {
          writeOnDisk("acme", path, "#!/bin/sh\necho " + path);
          WorkspaceFiles.mode(projectsDir.resolve("acme/files").resolve(path), diskMode);
          importer.importAll();
          var id = FileStore.idOf("acme", path);
          var snapshot =
              new LinkedHashMap<>(
                  YamlUtil.parseMap(new ChangeLog(db).head("file", id).orElseThrow().snapshot()));
          snapshot.remove("mode");
          db.execute(
              "UPDATE change_log SET snapshot = ? WHERE entity_type = 'file' AND entity_id = ?",
              YamlUtil.dumpJson(snapshot),
              id);
          db.execute("UPDATE project_files SET mode = ? WHERE id = ?", rowMode, id);
          return id;
        });
  }

  @Test
  void aRealChmodOfUnchangedContentIsRecorded() throws Exception {
    writeOnDisk("acme", "deploy.sh", "echo hi");
    var copy = projectsDir.resolve("acme/files/deploy.sh");
    WorkspaceFiles.mode(copy, 0644);
    importer.importAll();
    WorkspaceFiles.mode(copy, 0755);

    var report = importer.importAll();

    assertEquals(1, report.imported());
    assertEquals(0755, files.find("acme", "deploy.sh").orElseThrow().mode());
    assertEquals(2, revisions(FileStore.idOf("acme", "deploy.sh")));
  }

  private long revisions(String id) {
    return db.queryOne(
            "SELECT count(*) FROM change_log WHERE entity_type = 'file' AND entity_id = ?",
            row -> row.integer(0),
            id)
        .orElseThrow();
  }

  @Test
  void readsTheCapOnceForTheWholeImport() throws Exception {
    writeOnDisk("acme", "a.txt", "A");
    writeOnDisk("acme", "b.txt", "B");
    writeOnDisk("globex", "c.txt", "C");
    var loads = new java.util.concurrent.atomic.AtomicInteger();
    var counting =
        new FileImporter(
            projectsDir,
            files,
            () -> {
              loads.incrementAndGet();
              return ai.singlr.sail.config.FileLimits.defaults();
            });

    assertEquals(3, counting.importAll().imported());

    assertEquals(1, loads.get());
  }

  @Test
  void aCorruptCapFailsTheImportBeforeAnyFileIsRead() throws Exception {
    writeOnDisk("acme", "a.txt", "A");
    var corrupt =
        new FileImporter(
            projectsDir,
            files,
            () -> {
              throw new IllegalArgumentException(
                  "limits.file_max must be an integer number of bytes");
            });

    var failure = assertThrows(IllegalArgumentException.class, corrupt::importAll);

    assertTrue(failure.getMessage().contains("limits.file_max"));
    assertTrue(files.find("acme", "a.txt").isEmpty());
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
