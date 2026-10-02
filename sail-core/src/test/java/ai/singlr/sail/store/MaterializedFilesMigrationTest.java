/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.sail.config.ProjectRegistry;
import ai.singlr.sail.config.YamlUtil;
import ai.singlr.sail.engine.WorkspaceFiles;
import ai.singlr.sail.identity.ActingAs;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.LinkedHashMap;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The upgrade seeds, once, what this box wrote of each shared file from the history that decided it
 * until now: a copy matching any retained revision, a superseded one or a deleted file's included,
 * is this box's output; a copy matching none is a person's edit, reported and left.
 */
@ActingAs
class MaterializedFilesMigrationTest {

  @TempDir Path tempDir;
  private Sqlite db;
  private FileStore files;
  private Path projectsDir;

  @BeforeEach
  void setUp() {
    db = Sqlite.open(tempDir.resolve("test.db"));
    new SchemaManager(db).migrate();
    files = new FileStore(db);
    projectsDir = tempDir.resolve("projects");
  }

  @AfterEach
  void tearDown() {
    db.close();
  }

  private List<DataMigrator.Run> migrate() {
    return new DataMigrator(db, List.of(new MaterializedFilesMigration(projectsDir)))
        .run(
            ProjectRegistry.loadFromDisk(tempDir.resolve("no-projects")),
            DataMigration.Prompter.NON_INTERACTIVE);
  }

  private Path copy(String project, String path, String text) throws IOException {
    var file = projectsDir.resolve(project).resolve("files").resolve(path).normalize();
    Files.createDirectories(file.getParent());
    Files.writeString(file, text);
    WorkspaceFiles.mode(file, 0644);
    return file;
  }

  private String hashOf(String text) {
    return files.blobs().putText(text);
  }

  @Test
  void seedsEveryCopyMatchingARetainedVersionOnceAndReportsAPersonsEdit() throws IOException {
    ContentFixtures.put(files, "acme", "current.txt", "v1");
    copy("acme", "current.txt", "v1");
    ContentFixtures.put(files, "acme", "stale.txt", "v1");
    copy("acme", "stale.txt", "v1");
    ContentFixtures.put(files, "acme", "stale.txt", "v2");
    ContentFixtures.put(files, "acme", "gone.txt", "v1");
    copy("acme", "gone.txt", "v1");
    files.delete("acme", "gone.txt");
    ContentFixtures.put(files, "acme", "edited.txt", "v1");
    copy("acme", "edited.txt", "mine");
    ContentFixtures.put(files, "acme", "absent.txt", "v1");

    var first = migrate().getFirst();
    var second = migrate().getFirst();

    assertEquals(3, first.report().applied());
    assertEquals(1, first.report().skipped());
    assertTrue(
        first.report().notes().stream()
            .anyMatch(note -> note.contains("acme/edited.txt") && note.contains("person's edit")),
        first.report().notes().toString());
    assertTrue(second.alreadyApplied());
    assertTrue(files.materialized("acme/current.txt", hashOf("v1"), 0644));
    assertTrue(files.materialized("acme/stale.txt", hashOf("v1"), 0644), "a superseded copy");
    assertTrue(files.materialized("acme/gone.txt", hashOf("v1"), 0644), "a deleted file's copy");
    assertFalse(files.materialized("acme/edited.txt", hashOf("mine"), 0644));
    assertFalse(files.materialized("acme/edited.txt", hashOf("v1"), 0644));
    assertEquals(
        3L,
        db.queryOne("SELECT count(*) FROM materialized_files", row -> row.integer(0)).orElseThrow(),
        "nothing recorded for a file with no copy on disk");
  }

  @Test
  void resumesWhereAnInterruptedRunStoppedLeavingWhatIsAlreadyRecorded() throws IOException {
    ContentFixtures.put(files, "acme", "done.txt", "v1");
    copy("acme", "done.txt", "v1");
    ContentFixtures.put(files, "acme", "pending.txt", "v1");
    copy("acme", "pending.txt", "v1");
    files.recordMaterialized("acme/done.txt", hashOf("earlier"), 0600);

    var run = migrate().getFirst();

    assertEquals(1, run.report().applied(), "only the file not yet recorded");
    assertTrue(files.materialized("acme/done.txt", hashOf("earlier"), 0600));
    assertTrue(files.materialized("acme/pending.txt", hashOf("v1"), 0644));
  }

  @Test
  void anUnreadableCopyFailsTheRunBeforeItsMarkerSoARerunSeedsItOnceReadable() throws IOException {
    ContentFixtures.put(files, "acme", "stale.txt", "v1");
    var copy = copy("acme", "stale.txt", "v1");
    ContentFixtures.put(files, "acme", "stale.txt", "v2");
    Files.setPosixFilePermissions(copy, PosixFilePermissions.fromString("---------"));

    var failure = assertThrows(UncheckedIOException.class, this::migrate);
    Files.setPosixFilePermissions(copy, PosixFilePermissions.fromString("rw-r--r--"));
    var rerun = migrate().getFirst();

    assertTrue(failure.getMessage().contains("acme/stale.txt"), failure.getMessage());
    assertTrue(failure.getMessage().contains("rerun sail migrate"), failure.getMessage());
    assertFalse(rerun.alreadyApplied(), "the failed run left no completion marker");
    assertEquals(1, rerun.report().applied());
    assertTrue(files.materialized("acme/stale.txt", hashOf("v1"), 0644));
    assertTrue(migrate().getFirst().alreadyApplied());
  }

  @Test
  void aLegacyRevisionWithoutAModeSeedsAtAnyModeExceptAnExecuteBitTheRowLacks() throws IOException {
    legacy("umask.sh", 0664, 0644);
    legacy("restricted.md", 0600, 0644);
    legacy("bin/setup", 0755, 0644);
    legacy("bin/gone", 0755, 0644);
    files.delete("acme", "bin/gone");

    var run = migrate().getFirst();

    assertEquals(3, run.report().applied());
    assertTrue(files.materialized("acme/umask.sh", hashOf("umask.sh"), 0664));
    assertTrue(files.materialized("acme/restricted.md", hashOf("restricted.md"), 0600));
    assertFalse(
        files.materialized("acme/bin/setup", hashOf("bin/setup"), 0755),
        "an execute bit the old materializer never wrote was put there on purpose");
    assertTrue(
        run.report().notes().stream().anyMatch(note -> note.contains("acme/bin/setup")),
        run.report().notes().toString());
    assertTrue(
        files.materialized("acme/bin/gone", hashOf("bin/gone"), 0755),
        "with no row to lack the bit, a deleted file's legacy copy matches at any mode");
  }

  @Test
  void aCopyEscapingTheFilesDirectoryOrOfAnErasedProjectIsNotSeeded() throws IOException {
    ContentFixtures.put(files, "acme", "../../escape.txt", "evil");
    Files.createDirectories(projectsDir);
    Files.writeString(projectsDir.resolve("escape.txt"), "evil");
    ContentFixtures.put(files, "globex", "a.txt", "v1");
    copy("globex", "a.txt", "v1");
    var erasure = new Erasure(db);
    erasure.erase(erasure.closure(List.of(new Erasure.Target(Erasure.PROJECT, "globex"))), "local");

    var run = migrate().getFirst();

    assertEquals(0, run.report().applied());
    assertEquals(0, run.report().skipped());
    assertEquals(
        0L,
        db.queryOne("SELECT count(*) FROM materialized_files", row -> row.integer(0))
            .orElseThrow());
  }

  /**
   * A shared file as the content migration left it: one revision recording no mode, the row at
   * {@code rowMode}, and the copy on disk at {@code diskMode}, its content being its path.
   */
  private void legacy(String path, int diskMode, int rowMode) throws IOException {
    ContentFixtures.put(files, "acme", path, path);
    WorkspaceFiles.mode(copy("acme", path, path), diskMode);
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
  }
}
