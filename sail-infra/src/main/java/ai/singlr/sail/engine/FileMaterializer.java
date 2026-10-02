/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.engine;

import ai.singlr.sail.store.BlobStore;
import ai.singlr.sail.store.FileStore;
import ai.singlr.sail.store.MaterializedFiles;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Projects a project's synced files from the DB onto disk at {@code
 * ~/.sail/projects/<project>/files/<path>}, so {@code sail project up} drops them into the
 * container. Safe by construction:
 *
 * <ul>
 *   <li><b>No data loss.</b> A file on disk is overwritten or deleted only when it is a copy this
 *       box wrote or published, in content and in mode ({@link FileStore#copyOf}); a copy already
 *       holding the current row is in sync whatever its provenance and is recorded as this box's
 *       from then on; a file a human edited locally without publishing is neither, so it is left
 *       alone and reported as skipped; a copy the upgrade's seed could not tell is left alone too
 *       and reported apart, with {@link Report#UNDECIDED_REMEDY}. A {@code WRITE} records the
 *       version before it moves the file into place and again once it has, so a write that dies
 *       between the two leaves a copy this box still knows as its own; a {@code DELETE} removes the
 *       file and then forgets it.
 *   <li><b>No path traversal.</b> A synced path that escapes the project's {@code files/} directory
 *       (a malicious {@code ../}) is refused — the content comes from other FDEs over the wire.
 * </ul>
 */
public final class FileMaterializer {

  /** What a single file needs on disk relative to its current DB state and on-disk content. */
  enum Action {
    IN_SYNC,
    WRITE,
    DELETE,
    SKIP_DIRTY
  }

  /**
   * What one materialize did: {@code skipped} names the copies a person edited and {@code
   * undecided} those the upgrade's seed could not tell from an edit, both left as they are.
   */
  public record Report(int written, int deleted, List<String> skipped, List<String> undecided) {

    /** What to do with a copy the upgrade's seed could not tell from a person's edit. */
    public static final String UNDECIDED_REMEDY =
        "delete it to take main's, or capture it with 'sail project files add' if yours";

    public Report(int written, int deleted, List<String> skipped) {
      this(written, deleted, skipped, List.of());
    }
  }

  private final FileStore files;
  private final Path projectsDir;

  public FileMaterializer(FileStore files, Path projectsDir) {
    this.files = files;
    this.projectsDir = projectsDir;
  }

  public Report materialize(String project) throws IOException {
    NameValidator.requireValidProjectName(project);
    var filesDir = projectsDir.resolve(project).resolve("files").normalize();
    var written = 0;
    var deleted = 0;
    var skipped = new ArrayList<String>();
    var undecided = new ArrayList<String>();

    for (var id : files.idsForProject(project)) {
      var path = id.substring(project.length() + 1);
      var target = files.find(project, path).orElse(null);
      var targetContent = target == null ? null : target.contentHash();

      var destination = filesDir.resolve(path).normalize();
      if (!destination.startsWith(filesDir) || hasSymlinkBelow(filesDir, destination)) {
        skipped.add(path);
        continue;
      }

      var onDisk = diskHash(destination);
      var mode = onDisk == null ? 0 : WorkspaceFiles.mode(destination);
      var inSync = onDisk != null && target != null && target.holds(onDisk, mode);
      var copy = onDisk == null ? MaterializedFiles.Copy.PERSONS : files.copyOf(id, onDisk, mode);
      switch (decide(targetContent, onDisk, inSync, copy.ours())) {
        case IN_SYNC -> {
          if (target == null) {
            files.forgetMaterialized(id);
          } else if (!inSync || copy != MaterializedFiles.Copy.SETTLED) {
            WorkspaceFiles.mode(destination, target.mode());
            files.recordMaterialized(id, target.contentHash(), target.mode());
          }
        }
        case SKIP_DIRTY ->
            (copy == MaterializedFiles.Copy.UNDECIDED ? undecided : skipped).add(path);
        case WRITE -> {
          files.recordWriting(id, target.contentHash(), target.mode());
          writeFile(destination, target);
          files.recordMaterialized(id, target.contentHash(), target.mode());
          written++;
        }
        case DELETE -> {
          Files.deleteIfExists(destination);
          files.forgetMaterialized(id);
          deleted++;
        }
      }
    }
    return new Report(written, deleted, List.copyOf(skipped), List.copyOf(undecided));
  }

  /**
   * The action for one file: nothing when no copy is on disk and none is wanted, or the copy holds
   * the current row ({@code inSync}), or holds its content and is this box's to re-mode ({@code
   * ours}); refresh or remove a copy that is this box's; leave any other copy alone (skip) so a
   * human's work is never lost.
   */
  static Action decide(String targetContent, String onDisk, boolean inSync, boolean ours) {
    if (onDisk == null) {
      return targetContent == null ? Action.IN_SYNC : Action.WRITE;
    }
    if (Objects.equals(onDisk, targetContent)) {
      return inSync || ours ? Action.IN_SYNC : Action.SKIP_DIRTY;
    }
    if (!ours) {
      return Action.SKIP_DIRTY;
    }
    return targetContent == null ? Action.DELETE : Action.WRITE;
  }

  private static boolean hasSymlinkBelow(Path root, Path path) {
    for (var current = path;
        current != null && !current.equals(root);
        current = current.getParent()) {
      if (Files.isSymbolicLink(current)) return true;
    }
    return false;
  }

  private static String diskHash(Path file) throws IOException {
    if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) return null;
    try (var input = Files.newInputStream(file)) {
      return BlobStore.hash(input);
    }
  }

  private void writeFile(Path file, FileStore.FileRow row) throws IOException {
    Files.createDirectories(file.getParent());
    var temporary = Files.createTempFile(file.getParent(), ".sail-", ".tmp");
    try {
      try (var input = files.open(row);
          var output = Files.newOutputStream(temporary)) {
        input.transferTo(output);
      }
      WorkspaceFiles.mode(temporary, row.mode());
      Files.move(
          temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    } finally {
      Files.deleteIfExists(temporary);
    }
  }
}
