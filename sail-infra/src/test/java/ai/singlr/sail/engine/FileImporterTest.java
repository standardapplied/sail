/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.sail.config.FileLimits;
import ai.singlr.sail.config.ProjectRegistry;
import ai.singlr.sail.config.YamlUtil;
import ai.singlr.sail.identity.Acting;
import ai.singlr.sail.identity.ActingAs;
import ai.singlr.sail.store.ChangeLog;
import ai.singlr.sail.store.ContentFixtures;
import ai.singlr.sail.store.DataMigration;
import ai.singlr.sail.store.DataMigrator;
import ai.singlr.sail.store.Erasure;
import ai.singlr.sail.store.FileStore;
import ai.singlr.sail.store.MaterializedFilesMigration;
import ai.singlr.sail.store.SchemaManager;
import ai.singlr.sail.store.Sqlite;
import ai.singlr.sail.store.SyncLimits;
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
    assertEquals(b64("hello"), ContentFixtures.encoded(files, "acme", "scripts/deploy.sh"));
    assertEquals(b64("docs"), ContentFixtures.encoded(files, "acme", "README.md"));
  }

  @Test
  void aSkillInTheFilesDirectoryIsImportedAsTheProjectFilesItIs() throws Exception {
    writeOnDisk("acme", ".sail/skills/acme-review/SKILL.md", "Judge.");
    writeOnDisk("acme", ".sail/skills/acme-review/scripts/check.sh", "true");

    var report = importer.importAll();

    assertEquals(2, report.imported(), "the importer walks the directory itself");
    assertEquals(
        b64("Judge."), ContentFixtures.encoded(files, "acme", ".sail/skills/acme-review/SKILL.md"));
    assertEquals(
        "text", files.find("acme", ".sail/skills/acme-review/SKILL.md").orElseThrow().kind());
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
    assertEquals(b64("A2"), ContentFixtures.encoded(files, "acme", "a.txt"));
  }

  @Test
  void aCopyThisBoxWroteThatAnotherBoxHasSinceSupersededIsNotImported() throws Exception {
    writeOnDisk("acme", "a.txt", "v1");
    importer.importAll();
    new FileMaterializer(files, projectsDir).materialize("acme");
    ContentFixtures.put(files, "acme", "a.txt", "v2");

    var report = importer.importAll();

    assertEquals(0, report.imported(), "this box's stale output is not a person's edit");
    assertEquals(b64("v2"), ContentFixtures.encoded(files, "acme", "a.txt"));
  }

  @Test
  void aCopyTheUpgradeCouldNotTellIsNeverPublished() throws Exception {
    writeOnDisk("acme", "a.txt", "old");
    ContentFixtures.put(files, "acme", "a.txt", "v9");
    var id = FileStore.idOf("acme", "a.txt");
    files.forgetMaterialized(id);
    files.recordUndecided(
        id,
        files.blobs().putText("old"),
        WorkspaceFiles.mode(projectsDir.resolve("acme/files/a.txt")));

    var report = importer.importAll();

    assertEquals(0, report.imported(), "an undecided copy is not a person's edit to publish");
    assertEquals(b64("v9"), ContentFixtures.encoded(files, "acme", "a.txt"));
  }

  @Test
  void aCopyPublishedFromDiskAndSupersededByMainBeforeAnyMaterializeIsNotRepublished()
      throws Exception {
    writeOnDisk("acme", "a.txt", "v1");
    importer.importAll();
    new FileMaterializer(files, projectsDir).materialize("acme");
    writeOnDisk("acme", "a.txt", "v2");
    var copy = projectsDir.resolve("acme/files/a.txt");
    try (var input = Files.newInputStream(copy)) {
      files.put("acme", "a.txt", input, WorkspaceFiles.mode(copy));
    }
    mainsVersionArrives("acme", "a.txt", "v3");

    var report = importer.importAll();

    assertEquals(0, report.imported(), "a version this box published is never a lost edit");
    assertEquals(b64("v3"), ContentFixtures.encoded(files, "acme", "a.txt"));
    assertEquals(1, new FileMaterializer(files, projectsDir).materialize("acme").written());
  }

  /** Main's version of the file lands here as a pull does, touching no record of this box's. */
  private void mainsVersionArrives(String project, String path, String text) {
    try (var main = Sqlite.open(tempDir.resolve("main-" + text + ".db"))) {
      new SchemaManager(main).migrate();
      var mains = new FileStore(main);
      ContentFixtures.put(mains, project, path, text);
      var id = FileStore.idOf(project, path);
      files.blobs().putText(text);
      files.applyRevision(id, mains.comparableSnapshot(id), "9-" + text);
    }
  }

  @Test
  void aDeletedFilesCopyThisBoxWroteIsLeftForTheMaterializerNotImported() throws Exception {
    writeOnDisk("acme", "a.txt", "v1");
    importer.importAll();
    new FileMaterializer(files, projectsDir).materialize("acme");
    files.delete("acme", "a.txt");

    var report = importer.importAll();

    assertEquals(0, report.imported());
    assertTrue(files.find("acme", "a.txt").isEmpty(), "resurrected");
    assertEquals(1, new FileMaterializer(files, projectsDir).materialize("acme").deleted());
  }

  @Test
  void aPersonsCopyIsPublishedOnceAndRecordedAsThisBoxsFromThenOn() throws Exception {
    writeOnDisk("acme", "a.txt", "mine");
    var copy = projectsDir.resolve("acme/files/a.txt");
    WorkspaceFiles.mode(copy, 0600);

    assertEquals(1, importer.importAll().imported());

    var hash = files.find("acme", "a.txt").orElseThrow().contentHash();
    assertTrue(files.copyOf(FileStore.idOf("acme", "a.txt"), hash, 0600).ours());
    ContentFixtures.put(files, "acme", "a.txt", "theirs");
    assertEquals(0, importer.importAll().imported(), "published once, never again over newer");
  }

  @Test
  void aCopyTheStoreAlreadyHoldsIsRecordedAsThisBoxsWithoutARevision() throws Exception {
    ContentFixtures.put(files, "acme", "a.txt", "same");
    writeOnDisk("acme", "a.txt", "same");
    var copy = projectsDir.resolve("acme/files/a.txt");
    WorkspaceFiles.mode(copy, 0644);
    var id = FileStore.idOf("acme", "a.txt");

    assertEquals(0, importer.importAll().imported());

    assertEquals(1, revisions(id));
    assertTrue(
        files.copyOf(id, files.find("acme", "a.txt").orElseThrow().contentHash(), 0644).ours());
  }

  @Test
  void aLegacyCopyAtTheUmasksModeIsSeededAsThisBoxsAndNotImported() throws Exception {
    var id = legacy("deploy.sh", 0644, 0755);
    seed();

    var report = importer.importAll();

    assertEquals(0, report.imported());
    assertEquals(0755, files.find("acme", "deploy.sh").orElseThrow().mode());
    assertEquals(1, revisions(id));
  }

  @Test
  void aLegacyCopyRestrictedOnlyByTheUmaskIsSeededAndStaysAsItsRowSays() throws Exception {
    var id = legacy("notes.md", 0600, 0644);
    seed();

    assertEquals(0, importer.importAll().imported());
    assertEquals(0644, files.find("acme", "notes.md").orElseThrow().mode());
    assertEquals(1, revisions(id));
  }

  @Test
  void aLegacyExecutableKeepsTheExecuteBitSomeoneGaveIt() throws Exception {
    var id = legacy("bin/setup", 0755, 0644);
    seed();

    assertEquals(1, importer.importAll().imported());
    assertEquals(0755, files.find("acme", "bin/setup").orElseThrow().mode());
    assertEquals(2, revisions(id));
  }

  private void seed() {
    new DataMigrator(db, List.of(new MaterializedFilesMigration(projectsDir)))
        .run(
            ProjectRegistry.loadFromDisk(tempDir.resolve("no-projects")),
            DataMigration.Prompter.NON_INTERACTIVE);
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
   * {@code rowMode}, the copy on disk at {@code diskMode}, and no record of what this box wrote.
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
          files.forgetMaterialized(id);
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
              return FileLimits.defaults();
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
  void mainsLowerFileCapRefusesAnOversizedFileAtImport() throws Exception {
    new SyncLimits(db).recordMainFileMax(1);
    writeOnDisk("acme", "big.txt", "too big");
    var bounded = new FileImporter(projectsDir, files, FileLimits::defaults);

    var failure = assertThrows(IllegalArgumentException.class, bounded::importAll);

    assertTrue(failure.getMessage().contains("limits.file_max"), failure.getMessage());
    assertTrue(files.find("acme", "big.txt").isEmpty());
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
