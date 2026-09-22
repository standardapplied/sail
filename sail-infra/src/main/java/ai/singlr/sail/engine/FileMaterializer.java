/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.engine;

import ai.singlr.sail.store.BlobStore;
import ai.singlr.sail.store.FileStore;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Projects a project's synced files from the DB onto disk at {@code
 * ~/.sail/projects/<project>/files/<path>}, so {@code sail project up} drops them into the
 * container. Safe by construction:
 *
 * <ul>
 *   <li><b>No data loss.</b> A file on disk is overwritten or deleted only when it matches a
 *       revision's content and mode ({@link FileStore#isKnownVersion}); a file a human edited
 *       locally without {@code sail project files add} matches nothing in history, so it is left
 *       alone and reported as skipped.
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

  public record Report(int written, int deleted, List<String> skipped) {}

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

    for (var id : files.idsForProject(project)) {
      var path = id.substring(project.length() + 1);
      var target = files.find(project, path).orElse(null);
      var targetContent = target == null ? null : target.contentHash();

      var destination = filesDir.resolve(path).normalize();
      if (!destination.startsWith(filesDir) || hasSymlink(destination)) {
        skipped.add(path);
        continue;
      }

      var onDisk = diskHash(destination);
      switch (decide(
          targetContent,
          onDisk,
          onDisk != null && files.isKnownVersion(id, onDisk, WorkspaceFiles.mode(destination)))) {
        case IN_SYNC -> {
          if (target != null) WorkspaceFiles.mode(destination, target.mode());
        }
        case SKIP_DIRTY -> skipped.add(path);
        case WRITE -> {
          writeFile(destination, target);
          written++;
        }
        case DELETE -> {
          Files.deleteIfExists(destination);
          deleted++;
        }
      }
    }
    return new Report(written, deleted, List.copyOf(skipped));
  }

  /**
   * The action for one file: nothing if disk already matches the DB; refresh or remove if disk
   * holds a copy this box wrote; leave a locally-edited file alone (skip) so a human's work is
   * never lost.
   */
  static Action decide(String targetContent, String onDisk, boolean onDiskIsKnown) {
    if (onDisk != null && !onDiskIsKnown) {
      return Action.SKIP_DIRTY;
    }
    if (Objects.equals(onDisk, targetContent)) {
      return Action.IN_SYNC;
    }
    return targetContent == null ? Action.DELETE : Action.WRITE;
  }

  private static boolean hasSymlink(Path path) {
    for (var current = path; current != null; current = current.getParent()) {
      if (Files.isSymbolicLink(current)) return true;
    }
    return false;
  }

  private static String diskHash(Path file) throws IOException {
    if (!Files.isRegularFile(file, java.nio.file.LinkOption.NOFOLLOW_LINKS)) return null;
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
          temporary,
          file,
          java.nio.file.StandardCopyOption.ATOMIC_MOVE,
          java.nio.file.StandardCopyOption.REPLACE_EXISTING);
    } finally {
      Files.deleteIfExists(temporary);
    }
  }
}
