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
import java.util.Optional;
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
    return migrate(DataMigration.Prompter.NON_INTERACTIVE);
  }

  private List<DataMigrator.Run> migrate(DataMigration.Prompter prompter) {
    return new DataMigrator(db, List.of(new MaterializedFilesMigration(projectsDir)))
        .run(ProjectRegistry.loadFromDisk(tempDir.resolve("no-projects")), prompter);
  }

  /** The record as a box upgrading has it: empty, publishes before the upgrade recorded nothing. */
  private void beforeUpgrade() {
    db.execute("DELETE FROM materialized_files");
  }

  private long records() {
    return db.queryOne("SELECT count(*) FROM materialized_files", row -> row.integer(0))
        .orElseThrow();
  }

  /** Pushes past the history cap and compacts, as main does under a busy node. */
  private void compacted(String path) {
    for (var i = 2; i <= ChangeLog.HISTORY_REVISIONS + 5; i++) {
      ContentFixtures.put(files, "acme", path, "v" + i);
    }
    files.blobs().gc(BlobStore.Compaction.ALL, true);
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

    beforeUpgrade();
    var first = migrate().getFirst();
    var second = migrate().getFirst();

    assertEquals(3, first.report().applied());
    assertEquals(1, first.report().skipped());
    assertTrue(
        first.report().notes().stream()
            .anyMatch(note -> note.contains("acme/edited.txt") && note.contains("person's edit")),
        first.report().notes().toString());
    assertTrue(second.alreadyApplied());
    assertTrue(files.copyOf("acme/current.txt", hashOf("v1"), 0644).ours());
    assertTrue(files.copyOf("acme/stale.txt", hashOf("v1"), 0644).ours(), "a superseded copy");
    assertTrue(files.copyOf("acme/gone.txt", hashOf("v1"), 0644).ours(), "a deleted file's copy");
    assertFalse(files.copyOf("acme/edited.txt", hashOf("mine"), 0644).ours());
    assertFalse(files.copyOf("acme/edited.txt", hashOf("v1"), 0644).ours());
    assertEquals(3L, records(), "nothing recorded for a file with no copy on disk");
  }

  @Test
  void resumesWhereAnInterruptedRunStoppedLeavingWhatIsAlreadyRecorded() throws IOException {
    ContentFixtures.put(files, "acme", "done.txt", "v1");
    copy("acme", "done.txt", "v1");
    ContentFixtures.put(files, "acme", "pending.txt", "v1");
    copy("acme", "pending.txt", "v1");
    beforeUpgrade();
    files.recordMaterialized("acme/done.txt", hashOf("earlier"), 0600);

    var run = migrate().getFirst();

    assertEquals(1, run.report().applied(), "only the file not yet recorded");
    assertTrue(files.copyOf("acme/done.txt", hashOf("earlier"), 0600).ours());
    assertTrue(files.copyOf("acme/pending.txt", hashOf("v1"), 0644).ours());
  }

  @Test
  void anUnreadableCopyFailsTheRunBeforeItsMarkerSoARerunSeedsItOnceReadable() throws IOException {
    ContentFixtures.put(files, "acme", "stale.txt", "v1");
    var copy = copy("acme", "stale.txt", "v1");
    ContentFixtures.put(files, "acme", "stale.txt", "v2");
    Files.setPosixFilePermissions(copy, PosixFilePermissions.fromString("---------"));
    beforeUpgrade();

    var failure = assertThrows(UncheckedIOException.class, this::migrate);
    Files.setPosixFilePermissions(copy, PosixFilePermissions.fromString("rw-r--r--"));
    var rerun = migrate().getFirst();

    assertTrue(failure.getMessage().contains("acme/stale.txt"), failure.getMessage());
    assertTrue(failure.getMessage().contains("rerun sail migrate"), failure.getMessage());
    assertFalse(rerun.alreadyApplied(), "the failed run left no completion marker");
    assertEquals(1, rerun.report().applied());
    assertTrue(files.copyOf("acme/stale.txt", hashOf("v1"), 0644).ours());
    assertTrue(migrate().getFirst().alreadyApplied());
  }

  @Test
  void aLegacyRevisionWithoutAModeSeedsAtAnyModeExceptAnExecuteBitTheRowLacks() throws IOException {
    legacy("umask.sh", 0664, 0644);
    legacy("restricted.md", 0600, 0644);
    legacy("bin/setup", 0755, 0644);
    legacy("bin/gone", 0755, 0644);
    files.delete("acme", "bin/gone");
    beforeUpgrade();

    var run = migrate().getFirst();

    assertEquals(3, run.report().applied());
    assertTrue(files.copyOf("acme/umask.sh", hashOf("umask.sh"), 0664).ours());
    assertTrue(files.copyOf("acme/restricted.md", hashOf("restricted.md"), 0600).ours());
    assertFalse(
        files.copyOf("acme/bin/setup", hashOf("bin/setup"), 0755).ours(),
        "an execute bit the old materializer never wrote was put there on purpose");
    assertTrue(
        run.report().notes().stream().anyMatch(note -> note.contains("acme/bin/setup")),
        run.report().notes().toString());
    assertTrue(
        files.copyOf("acme/bin/gone", hashOf("bin/gone"), 0755).ours(),
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
    beforeUpgrade();

    var run = migrate().getFirst();

    assertEquals(0, run.report().applied());
    assertEquals(0, run.report().skipped());
    assertEquals(0L, records());
  }

  @Test
  void aCopyMatchingNothingOfACompactedHistoryIsUndecidedKeptAndNeverPublished()
      throws IOException {
    ContentFixtures.put(files, "acme", "stale.conf", "v1");
    copy("acme", "stale.conf", "v1");
    compacted("stale.conf");
    ContentFixtures.put(files, "acme", "gone.conf", "v1");
    copy("acme", "gone.conf", "v1");
    compacted("gone.conf");
    files.delete("acme", "gone.conf");
    files.blobs().gc(BlobStore.Compaction.ALL, true);
    ContentFixtures.put(files, "acme", "fresh.txt", "v1");
    copy("acme", "fresh.txt", "mine");
    beforeUpgrade();

    var run = migrate().getFirst();

    assertEquals(0, run.report().applied());
    assertEquals(2, run.report().ambiguous());
    assertEquals(1, run.report().skipped(), "the whole-history edit is a person's");
    assertEquals(
        MaterializedFiles.Copy.UNDECIDED, files.copyOf("acme/stale.conf", hashOf("v1"), 0644));
    assertEquals(
        MaterializedFiles.Copy.UNDECIDED, files.copyOf("acme/gone.conf", hashOf("v1"), 0644));
    assertEquals(
        MaterializedFiles.Copy.PERSONS, files.copyOf("acme/fresh.txt", hashOf("mine"), 0644));
    assertTrue(
        run.report().notes().stream()
            .anyMatch(
                note ->
                    note.contains("acme/stale.conf")
                        && note.contains("undecided")
                        && note.contains("sail project files add")),
        run.report().notes().toString());
    assertTrue(migrate().getFirst().alreadyApplied());
    files.recordMaterialized("acme/stale.conf", hashOf("v9"), 0644);
    assertEquals(
        MaterializedFiles.Copy.PERSONS,
        files.copyOf("acme/stale.conf", hashOf("v1"), 0644),
        "once this box writes the file again, the undecided copy is not kept as its own");
  }

  @Test
  void anOperatorAtTheTerminalDecidesACompactedCopy() throws IOException {
    ContentFixtures.put(files, "acme", "wrote.conf", "v1");
    copy("acme", "wrote.conf", "v1");
    compacted("wrote.conf");
    ContentFixtures.put(files, "acme", "edited.conf", "v1");
    copy("acme", "edited.conf", "v1");
    compacted("edited.conf");
    beforeUpgrade();
    DataMigration.Prompter operator =
        (context, candidates) ->
            Optional.of(
                context.contains("acme/wrote.conf")
                    ? MaterializedFilesMigration.WROTE
                    : MaterializedFilesMigration.EDITED);

    var run = migrate(operator).getFirst();

    assertEquals(1, run.report().applied());
    assertEquals(0, run.report().ambiguous());
    assertTrue(files.copyOf("acme/wrote.conf", hashOf("v1"), 0644).ours());
    assertEquals(
        MaterializedFiles.Copy.PERSONS, files.copyOf("acme/edited.conf", hashOf("v1"), 0644));
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
