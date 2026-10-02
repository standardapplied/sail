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
 * next publishes none of them. A copy matching no retained revision may be a person's unpublished
 * edit or a version this box wrote that compaction has since dropped — on main, whose copies lag
 * the fleet's pushes, mostly the latter — and nothing left can tell which: it is recorded as
 * undecided ({@link FileStore#recordUndecided}), kept, reported with the remedy, and never
 * published, until a version this box writes lands in its place. A revision the content migration
 * converted recorded no mode, since the old materializer wrote whatever the box's umask gave, so it
 * matches its content at any mode that grants no execute bit the row lacks: that materializer never
 * wrote one, so one on disk was put there on purpose, the one edit history can still tell, left for
 * the import to publish. History's one permitted use for the decision; from the seed on, the record
 * alone decides. Resumable: each file records in its own transaction, and a file already recorded
 * is left as it is. A copy this box cannot read fails the run before its completion marker, so the
 * import never takes that copy for a person's edit once it is readable; what the run recorded
 * stays, and the rerun finishes. Reads this box's own files, so it runs only where they are ({@link
 * #readsBoxFiles}).
 */
public final class MaterializedFilesMigration implements DataMigration {

  public static final String NAME = "materialized-files-v1";

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
    var recorded = 0;
    var undecided = 0;
    var skipped = 0;
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
        switch (seed(db, files, id, project, path, copy, notes)) {
          case OURS -> recorded++;
          case UNDECIDED -> undecided++;
          default -> skipped++;
        }
      }
    }
    notes.addFirst("Recorded the copy this box wrote of " + recorded + " shared file(s)");
    return new Report(recorded, undecided, skipped, notes);
  }

  /**
   * Seeds the copy as this box's when history still holds its version, as undecided when it holds
   * no version of its content, and leaves a person's deliberate chmod for the import.
   */
  private static MaterializedFiles.Copy seed(
      Sqlite db,
      FileStore files,
      String id,
      String project,
      String path,
      Path copy,
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
    var copyIs = retained(db, id, row, hash, mode);
    switch (copyIs) {
      case OURS -> db.transaction(() -> files.recordMaterialized(id, hash, mode));
      case PERSONS ->
          notes.add(
              "Left "
                  + id
                  + " unrecorded: its copy on disk carries an execute bit no version of it had, put"
                  + " there on purpose, so it is a person's edit and the import publishes it");
      default -> {
        db.transaction(() -> files.recordUndecided(id, hash, mode));
        notes.add(
            "Left "
                + id
                + " undecided: its copy on disk matches no version this box still holds, so it may"
                + " be your unpublished edit or a version this box wrote before history was"
                + " compacted. It is kept and not published; "
                + MaterializedFiles.UNDECIDED_REMEDY);
      }
    }
    return copyIs;
  }

  /**
   * What retained history says of the copy: {@code OURS} when a revision holds its content at its
   * mode, or at no mode and the copy grants no execute bit the row lacks; {@code PERSONS} when a
   * mode-less revision holds its content and the copy does grant one, which the old materializer
   * never wrote; {@code UNDECIDED} when no retained revision holds its content at all.
   */
  private static MaterializedFiles.Copy retained(
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
    if (modes.contains((long) mode)) {
      return MaterializedFiles.Copy.OURS;
    }
    if (modes.contains(LEGACY)) {
      var extraExecuteBit = row != null && (mode & ~row.mode() & 0111) != 0;
      return extraExecuteBit ? MaterializedFiles.Copy.PERSONS : MaterializedFiles.Copy.OURS;
    }
    return MaterializedFiles.Copy.UNDECIDED;
  }

  private static final long LEGACY = -1;
}
