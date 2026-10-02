/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.store;

import ai.singlr.sail.config.ProjectRegistry;
import ai.singlr.sail.engine.WorkspaceFiles;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Seeds, once, what this box wrote of each shared file ({@link FileStore#recordMaterialized}) from
 * the history that decided it until now. A disk copy matching any retained revision of its file —
 * the current one, an older one that pushes from other boxes have since superseded, or one of a
 * file since deleted — is this box's output, recorded so that the import {@code sail migrate} runs
 * next publishes none of them. A copy matching no retained revision is a person's edit when the
 * file's history is whole: left unrecorded and reported, for that import to publish. When history
 * was compacted — its first revision is gone — the copy may match a version compaction dropped, so
 * the seed cannot tell it from an edit: an operator at the terminal is asked; otherwise it is
 * recorded as undecided ({@link FileStore#recordUndecided}), kept, reported and never published,
 * until this box writes or publishes the file again. A revision the content migration converted
 * recorded no mode, since the old materializer wrote whatever the box's umask gave, so it matches
 * its content at any mode that grants no execute bit the row lacks: that materializer never wrote
 * one, so one on disk was put there on purpose. History's one permitted use for the decision; from
 * the seed on, the record alone decides. Resumable: each file records in its own transaction, and a
 * file already recorded is left as it is. A copy this box cannot read fails the run before its
 * completion marker, so the import never takes that copy for a person's edit once it is readable;
 * what the run recorded stays, and the rerun finishes. Reads this box's own files, so it runs only
 * where they are ({@link #readsBoxFiles}).
 */
public final class MaterializedFilesMigration implements DataMigration {

  public static final String NAME = "materialized-files-v1";

  static final String WROTE = "this box, which wrote it: refresh it from the shared version";
  static final String EDITED = "you, who edited it: publish it";

  private final Path projectsDir;

  /** Seeding from the copies under {@code projectsDir}, each at {@code <project>/files/<path>}. */
  public MaterializedFilesMigration(Path projectsDir) {
    this.projectsDir = Objects.requireNonNull(projectsDir, "projectsDir");
  }

  @Override
  public String name() {
    return NAME;
  }

  @Override
  public boolean resumable() {
    return true;
  }

  @Override
  public boolean readsBoxFiles() {
    return true;
  }

  @Override
  public Report apply(Sqlite db, ProjectRegistry projects, Prompter prompter) {
    var files = new FileStore(db);
    var history = new ChangeLog(db);
    var recorded = 0;
    var undecided = 0;
    var notes = new ArrayList<String>();
    for (var project : files.projectsWithFiles()) {
      var filesDir = projectsDir.resolve(project).resolve("files").normalize();
      for (var id : files.idsForProject(project)) {
        var path = id.substring(project.length() + 1);
        var copy = filesDir.resolve(path).normalize();
        if (!copy.startsWith(filesDir)
            || !Files.isRegularFile(copy, LinkOption.NOFOLLOW_LINKS)
            || files.recordedMaterialization(id)) {
          continue;
        }
        switch (seed(db, files, history, id, project, path, copy, prompter, notes)) {
          case OURS -> recorded++;
          case UNDECIDED -> undecided++;
          case PERSONS -> {}
        }
      }
    }
    notes.addFirst("Recorded the copy this box wrote of " + recorded + " shared file(s)");
    return new Report(recorded, undecided, notes.size() - 1 - undecided, notes);
  }

  private static MaterializedFiles.Copy seed(
      Sqlite db,
      FileStore files,
      ChangeLog history,
      String id,
      String project,
      String path,
      Path copy,
      Prompter prompter,
      List<String> notes) {
    String hash;
    int mode;
    try (var input = Files.newInputStream(copy)) {
      hash = BlobStore.hash(input);
      mode = WorkspaceFiles.mode(copy);
    } catch (IOException e) {
      throw new UncheckedIOException(
          "Cannot seed what this box wrote of "
              + id
              + ": could not read "
              + copy
              + "; restore access and rerun sail migrate",
          e);
    }
    var row = files.find(project, path).orElse(null);
    var copyIs = MaterializedFiles.Copy.PERSONS;
    if (isRetainedVersion(db, id, row, hash, mode)) {
      copyIs = MaterializedFiles.Copy.OURS;
    } else if (!historyWhole(history, id)) {
      copyIs = ask(prompter, id, notes);
    } else {
      notes.add(
          "Left "
              + id
              + " unrecorded: its copy on disk matches no version this box holds, so it is a"
              + " person's edit and the import publishes it");
    }
    switch (copyIs) {
      case OURS -> db.transaction(() -> files.recordMaterialized(id, hash, mode));
      case UNDECIDED -> db.transaction(() -> files.recordUndecided(id, hash, mode));
      case PERSONS -> {}
    }
    return copyIs;
  }

  private static MaterializedFiles.Copy ask(Prompter prompter, String id, List<String> notes) {
    var answer =
        prompter.choose(
            "The copy of shared file "
                + id
                + " on disk, which matches no version this box still holds (its history was"
                + " compacted),",
            List.of(WROTE, EDITED));
    if (answer.isPresent()) {
      return WROTE.equals(answer.get())
          ? MaterializedFiles.Copy.OURS
          : MaterializedFiles.Copy.PERSONS;
    }
    notes.add(
        "Left "
            + id
            + " undecided: its copy on disk matches no version this box still holds and its"
            + " history was compacted. It is kept and not published; share it with sail project"
            + " files add if it is your edit, or delete the copy to have sail refresh it");
    return MaterializedFiles.Copy.UNDECIDED;
  }

  /** Whether the file's first revision is still retained: nothing of its history was compacted. */
  private static boolean historyWhole(ChangeLog history, String id) {
    return history.history(Erasure.FILE, id).stream()
        .anyMatch(entry -> Revisions.counterOf(entry.rev()) == 1);
  }

  private static boolean isRetainedVersion(
      Sqlite db, String id, FileStore.FileRow row, String hash, int mode) {
    var modes =
        db.query(
            """
            SELECT COALESCE(json_extract(snapshot, '$.mode'), ?) FROM change_log
                WHERE entity_type = 'file' AND entity_id = ?
                AND json_extract(snapshot, '$.content_hash') = ?
            """,
            revision -> revision.integer(0),
            LEGACY,
            id,
            hash);
    return modes.contains((long) mode)
        || (modes.contains(LEGACY) && (row == null || (mode & ~row.mode() & 0111) == 0));
  }

  private static final long LEGACY = -1;
}
