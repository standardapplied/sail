/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.sail.store.FileStore;
import ai.singlr.sail.store.SchemaManager;
import ai.singlr.sail.store.Sqlite;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Materialization is the data-safety boundary: it refreshes copies it wrote, never clobbers a file
 * a human edited locally, and refuses a synced path that escapes the project directory.
 */
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
    assertEquals(FileMaterializer.Action.IN_SYNC, FileMaterializer.decide("A", "A", true));
    assertEquals(FileMaterializer.Action.WRITE, FileMaterializer.decide("A", null, false));
    assertEquals(FileMaterializer.Action.WRITE, FileMaterializer.decide("B", "A", true));
    assertEquals(FileMaterializer.Action.DELETE, FileMaterializer.decide(null, "A", true));
    assertEquals(FileMaterializer.Action.SKIP_DIRTY, FileMaterializer.decide("B", "user", false));
  }

  @Test
  void writesANewFilePreservingFolderStructure() throws Exception {
    files.put("acme", "scripts/deploy.sh", b64("hello"));

    var report = materializer.materialize("acme");

    assertEquals(1, report.written());
    assertEquals("hello", Files.readString(filesDir.resolve("scripts/deploy.sh")));
  }

  @Test
  void refreshesAStaleCopyThisBoxWrote() throws Exception {
    files.put("acme", "x.txt", b64("A"));
    materializer.materialize("acme");
    files.put("acme", "x.txt", b64("B"));

    var report = materializer.materialize("acme");

    assertEquals(1, report.written());
    assertEquals("B", Files.readString(filesDir.resolve("x.txt")));
  }

  @Test
  void leavesALocallyEditedFileUntouchedAndReportsIt() throws Exception {
    files.put("acme", "x.txt", b64("A"));
    materializer.materialize("acme");
    Files.writeString(filesDir.resolve("x.txt"), "MY LOCAL EDIT");
    files.put("acme", "x.txt", b64("B"));

    var report = materializer.materialize("acme");

    assertEquals(0, report.written());
    assertEquals(List.of("x.txt"), report.skipped());
    assertEquals("MY LOCAL EDIT", Files.readString(filesDir.resolve("x.txt")));
  }

  @Test
  void removesADeletedFileWhenTheDiskCopyIsOneWeWrote() throws Exception {
    files.put("acme", "x.txt", b64("A"));
    materializer.materialize("acme");
    files.delete("acme", "x.txt");

    var report = materializer.materialize("acme");

    assertEquals(1, report.deleted());
    assertFalse(Files.exists(filesDir.resolve("x.txt")));
  }

  @Test
  void keepsALocallyEditedFileEvenWhenDeletedOnMain() throws Exception {
    files.put("acme", "x.txt", b64("A"));
    materializer.materialize("acme");
    Files.writeString(filesDir.resolve("x.txt"), "MINE");
    files.delete("acme", "x.txt");

    var report = materializer.materialize("acme");

    assertEquals(0, report.deleted());
    assertEquals(List.of("x.txt"), report.skipped());
    assertTrue(Files.exists(filesDir.resolve("x.txt")));
  }

  @Test
  void refusesAPathThatEscapesTheProjectDirectory() throws Exception {
    files.put("acme", "../../escape.txt", b64("evil"));

    var report = materializer.materialize("acme");

    assertEquals(List.of("../../escape.txt"), report.skipped());
    assertFalse(Files.exists(tempDir.resolve("projects/escape.txt")));
  }

  @Test
  void doesNothingWhenDiskAlreadyMatches() throws Exception {
    files.put("acme", "x.txt", b64("A"));
    materializer.materialize("acme");

    var report = materializer.materialize("acme");

    assertEquals(0, report.written());
    assertEquals(0, report.deleted());
    assertTrue(report.skipped().isEmpty());
  }
}
