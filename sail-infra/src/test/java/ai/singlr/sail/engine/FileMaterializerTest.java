/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.sail.identity.ActingAs;
import ai.singlr.sail.store.ContentFixtures;
import ai.singlr.sail.store.Erasure;
import ai.singlr.sail.store.FileStore;
import ai.singlr.sail.store.MaterializedFiles;
import ai.singlr.sail.store.SchemaManager;
import ai.singlr.sail.store.Sqlite;
import ai.singlr.sail.sync.SyncBox;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Materialization is the data-safety boundary: it refreshes copies it wrote, never clobbers a file
 * a human edited locally, and refuses a synced path that escapes the project directory.
 */
@ActingAs
class FileMaterializerTest {

  @TempDir Path tempDir;
  private Sqlite db;
  private FileStore files;
  private FileMaterializer materializer;
  private Path filesDir;

  @BeforeEach
  void setUp() {
    db = Sqlite.open(tempDir.resolve("test.db"));
    new SchemaManager(db).migrate();
    files = new FileStore(db);
    var projectsDir = tempDir.resolve("projects");
    materializer = new FileMaterializer(files, projectsDir);
    filesDir = projectsDir.resolve("acme").resolve("files");
  }

  @AfterEach
  void tearDown() {
    db.close();
  }

  private static String b64(String text) {
    return Base64.getEncoder().encodeToString(text.getBytes());
  }

  @Test
  void decideCoversTheFourActions() {
    assertEquals(
        FileMaterializer.Action.IN_SYNC, FileMaterializer.decide(null, null, false, false));
    assertEquals(FileMaterializer.Action.WRITE, FileMaterializer.decide("A", null, false, false));
    assertEquals(FileMaterializer.Action.IN_SYNC, FileMaterializer.decide("A", "A", true, false));
    assertEquals(FileMaterializer.Action.IN_SYNC, FileMaterializer.decide("A", "A", true, true));
    assertEquals(FileMaterializer.Action.WRITE, FileMaterializer.decide("B", "A", false, true));
    assertEquals(FileMaterializer.Action.DELETE, FileMaterializer.decide(null, "A", false, true));
    assertEquals(
        FileMaterializer.Action.SKIP_DIRTY, FileMaterializer.decide("B", "user", false, false));
    assertEquals(
        FileMaterializer.Action.SKIP_DIRTY, FileMaterializer.decide(null, "user", false, false));
  }

  @Test
  void writesANewFilePreservingFolderStructure() throws Exception {
    ContentFixtures.put(files, "acme", "scripts/deploy.sh", "hello");

    var report = materializer.materialize("acme");

    assertEquals(1, report.written());
    assertEquals("hello", Files.readString(filesDir.resolve("scripts/deploy.sh")));
  }

  @Test
  void refreshesAStaleCopyThisBoxWrote() throws Exception {
    ContentFixtures.put(files, "acme", "x.txt", "A");
    materializer.materialize("acme");
    ContentFixtures.put(files, "acme", "x.txt", "B");

    var report = materializer.materialize("acme");

    assertEquals(1, report.written());
    assertEquals("B", Files.readString(filesDir.resolve("x.txt")));
  }

  @Test
  void aWriteThatDiedBeforeMovingTheFileLeavesACopyThisBoxStillRefreshes() throws Exception {
    ContentFixtures.put(files, "acme", "x.txt", "A");
    materializer.materialize("acme");
    files.recordWriting(FileStore.idOf("acme", "x.txt"), files.blobs().putText("B"), 0644);
    ContentFixtures.put(files, "acme", "x.txt", "C");

    var report = materializer.materialize("acme");

    assertEquals(new FileMaterializer.Report(1, 0, List.of()), report);
    assertEquals("C", Files.readString(filesDir.resolve("x.txt")));
    assertEquals(
        MaterializedFiles.Copy.SETTLED,
        files.copyOf(FileStore.idOf("acme", "x.txt"), files.blobs().putText("C"), 0644),
        "the write landed and the record says so");
  }

  @Test
  void aCopyTheUpgradeCouldNotTellIsKeptAndReportedApartUntilAVersionLandsInItsPlace()
      throws Exception {
    ContentFixtures.put(files, "acme", "x.txt", "A");
    Files.createDirectories(filesDir);
    Files.writeString(filesDir.resolve("x.txt"), "old");
    WorkspaceFiles.mode(filesDir.resolve("x.txt"), 0644);
    var id = FileStore.idOf("acme", "x.txt");
    files.forgetMaterialized(id);
    files.recordUndecided(id, files.blobs().putText("old"), 0644);

    var kept = materializer.materialize("acme");
    ContentFixtures.put(files, "acme", "x.txt", "B");
    var stillKept = materializer.materialize("acme");
    var untouched = Files.readString(filesDir.resolve("x.txt"));
    var oldCopy = files.copyOf(id, files.blobs().putText("old"), 0644);
    Files.writeString(filesDir.resolve("x.txt"), "B");
    var published = materializer.materialize("acme");

    assertEquals(new FileMaterializer.Report(0, 0, List.of(), List.of("x.txt")), kept);
    assertEquals(new FileMaterializer.Report(0, 0, List.of(), List.of("x.txt")), stillKept);
    assertEquals("old", untouched, "kept as it was");
    assertEquals(
        MaterializedFiles.Copy.UNDECIDED,
        oldCopy,
        "a publish of the file leaves the undecided copy so until a version lands in its place");
    assertEquals(new FileMaterializer.Report(0, 0, List.of()), published);
    assertEquals(
        MaterializedFiles.Copy.PERSONS,
        files.copyOf(id, files.blobs().putText("old"), 0644),
        "the copy the person made hold the row landed; the undecided one is retired");
    assertEquals(
        MaterializedFiles.Copy.SETTLED, files.copyOf(id, files.blobs().putText("B"), 0644));
  }

  @Test
  void aCopyAlreadyInSyncAndSettledIsNotRecordedAgain() throws Exception {
    ContentFixtures.put(files, "acme", "x.txt", "A");
    materializer.materialize("acme");
    var hash = files.blobs().putText("A");
    db.execute("DROP TABLE materialized_files");
    db.execute(
        "CREATE VIEW materialized_files AS SELECT 'acme/x.txt' AS id, '"
            + hash
            + "' AS content_hash, 420 AS mode, NULL AS previous_hash, NULL AS previous_mode,"
            + " 0 AS undecided");

    var report = materializer.materialize("acme");

    assertEquals(
        new FileMaterializer.Report(0, 0, List.of()), report, "a read-only record suffices");
  }

  @Test
  void leavesALocallyEditedFileUntouchedAndReportsIt() throws Exception {
    ContentFixtures.put(files, "acme", "x.txt", "A");
    materializer.materialize("acme");
    Files.writeString(filesDir.resolve("x.txt"), "MY LOCAL EDIT");
    ContentFixtures.put(files, "acme", "x.txt", "B");

    var report = materializer.materialize("acme");

    assertEquals(0, report.written());
    assertEquals(List.of("x.txt"), report.skipped());
    assertEquals("MY LOCAL EDIT", Files.readString(filesDir.resolve("x.txt")));
  }

  @Test
  void aLocalEditOncePublishedIsThisBoxsCopySoLaterVersionsRefreshIt() throws Exception {
    ContentFixtures.put(files, "acme", "x.txt", "A");
    materializer.materialize("acme");
    Files.writeString(filesDir.resolve("x.txt"), "B");
    ContentFixtures.put(files, "acme", "x.txt", "B");

    var published = materializer.materialize("acme");
    ContentFixtures.put(files, "acme", "x.txt", "C");
    var refreshed = materializer.materialize("acme");

    assertEquals(new FileMaterializer.Report(0, 0, List.of()), published);
    assertEquals(new FileMaterializer.Report(1, 0, List.of()), refreshed);
    assertEquals("C", Files.readString(filesDir.resolve("x.txt")));
  }

  @Test
  void aPrunedProjectsFilesAreLeftOnDiskUnreportedAndItIsNoLongerOneWithFiles() throws Exception {
    ContentFixtures.put(files, "acme", "x.txt", "A");
    materializer.materialize("acme");
    var erasure = new Erasure(db);
    erasure.erase(erasure.closure(List.of(new Erasure.Target(Erasure.PROJECT, "acme"))), "local");

    var report = materializer.materialize("acme");

    assertEquals(List.of(), report.skipped());
    assertEquals("A", Files.readString(filesDir.resolve("x.txt")));
    assertTrue(files.projectsWithFiles().isEmpty());
  }

  @Test
  void removesADeletedFileWhenTheDiskCopyIsOneWeWrote() throws Exception {
    ContentFixtures.put(files, "acme", "x.txt", "A");
    materializer.materialize("acme");
    files.delete("acme", "x.txt");

    var report = materializer.materialize("acme");

    assertEquals(1, report.deleted());
    assertFalse(Files.exists(filesDir.resolve("x.txt")));
  }

  @Test
  void keepsALocallyEditedFileEvenWhenDeletedOnMain() throws Exception {
    ContentFixtures.put(files, "acme", "x.txt", "A");
    materializer.materialize("acme");
    Files.writeString(filesDir.resolve("x.txt"), "MINE");
    files.delete("acme", "x.txt");

    var report = materializer.materialize("acme");

    assertEquals(0, report.deleted());
    assertEquals(List.of("x.txt"), report.skipped());
    assertTrue(Files.exists(filesDir.resolve("x.txt")));
  }

  @Test
  void aSymlinkedAncestorOfTheProjectsDirectoryIsNotAnEscape() throws Exception {
    var real = Files.createDirectories(tempDir.resolve("real-home"));
    var linked = Files.createSymbolicLink(tempDir.resolve("home"), real);
    var viaLink = new FileMaterializer(files, linked.resolve("projects"));
    ContentFixtures.put(files, "acme", "notes.txt", "hello");

    var report = viaLink.materialize("acme");

    assertEquals(1, report.written());
    assertEquals(List.of(), report.skipped());
    assertEquals("hello", Files.readString(real.resolve("projects/acme/files/notes.txt")));
  }

  @Test
  void aSymlinkInsideTheFilesDirectoryIsRefused() throws Exception {
    var elsewhere = Files.createDirectories(tempDir.resolve("elsewhere"));
    Files.createDirectories(filesDir);
    Files.createSymbolicLink(filesDir.resolve("out"), elsewhere);
    ContentFixtures.put(files, "acme", "out/planted.txt", "evil");

    var report = materializer.materialize("acme");

    assertEquals(List.of("out/planted.txt"), report.skipped());
    assertFalse(Files.exists(elsewhere.resolve("planted.txt")));
  }

  @Test
  void refusesAPathThatEscapesTheProjectDirectory() throws Exception {
    ContentFixtures.put(files, "acme", "../../escape.txt", "evil");

    var report = materializer.materialize("acme");

    assertEquals(List.of("../../escape.txt"), report.skipped());
    assertFalse(Files.exists(tempDir.resolve("projects/escape.txt")));
  }

  @Test
  void doesNothingWhenDiskAlreadyMatches() throws Exception {
    ContentFixtures.put(files, "acme", "x.txt", "A");
    materializer.materialize("acme");

    var report = materializer.materialize("acme");

    assertEquals(0, report.written());
    assertEquals(0, report.deleted());
    assertTrue(report.skipped().isEmpty());
  }

  @ParameterizedTest
  @ValueSource(strings = {"unchanged", "content", "mode", "deleted"})
  void preservesLocalPermissionEditsAcrossPagedSync(String update) throws Exception {
    try (var main = new SyncBox(tempDir, "main")) {
      var remote = new FileStore(main.db);
      var hash = remote.blobs().putText("original");
      remote.put(new FileStore.FileRow("acme", "x.txt", hash, 8, 0644, "text"));
      SyncBox.round(main.db, db, "file");
      materializer.materialize("acme");
      var destination = filesDir.resolve("x.txt");
      WorkspaceFiles.mode(destination, 0600);

      switch (update) {
        case "content" -> ContentFixtures.put(remote, "acme", "x.txt", "changed");
        case "mode" -> remote.put(new FileStore.FileRow("acme", "x.txt", hash, 8, 0750, "text"));
        case "deleted" -> remote.delete("acme", "x.txt");
        default -> {}
      }
      SyncBox.round(main.db, db, "file");

      var report = materializer.materialize("acme");

      assertEquals(0600, WorkspaceFiles.mode(destination));
      assertEquals("original", Files.readString(destination));
      assertEquals(new FileMaterializer.Report(0, 0, List.of("x.txt")), report);
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"content", "mode", "deleted"})
  void updatesPreviouslyRecordedVersionsAcrossPagedSync(String update) throws Exception {
    try (var main = new SyncBox(tempDir, "main")) {
      var remote = new FileStore(main.db);
      var original = remote.blobs().putText("original");
      remote.put(new FileStore.FileRow("acme", "x.txt", original, 8, 0644, "text"));
      SyncBox.round(main.db, db, "file");
      materializer.materialize("acme");
      var hash = "content".equals(update) ? remote.blobs().putText("modified") : original;
      if ("deleted".equals(update)) {
        remote.delete("acme", "x.txt");
      } else {
        remote.put(new FileStore.FileRow("acme", "x.txt", hash, 8, 0600, "text"));
      }
      SyncBox.round(main.db, db, "file");

      var report = materializer.materialize("acme");

      assertTrue(report.skipped().isEmpty());
      var destination = filesDir.resolve("x.txt");
      if ("deleted".equals(update)) {
        assertEquals(1, report.deleted());
        assertFalse(Files.exists(destination));
      } else {
        assertEquals(0600, WorkspaceFiles.mode(destination));
        assertEquals(
            "content".equals(update) ? "modified" : "original", Files.readString(destination));
        assertEquals("content".equals(update) ? 1 : 0, report.written());
      }
    }
  }
}
