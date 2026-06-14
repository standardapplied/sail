/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.engine;

import ai.singlr.sail.store.FileStore;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Objects;

/**
 * Projects a project's synced files from the DB onto disk at {@code
 * ~/.sail/projects/<project>/files/<path>}, so {@code sail project up} drops them into the
 * container. Safe by construction:
 *
 * <ul>
 *   <li><b>No data loss.</b> A file on disk is overwritten or deleted only when it matches a
 *       revision this box itself wrote ({@link FileStore#isKnownContent}); a file a human edited
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
    var filesDir = projectsDir.resolve(project).resolve("files").normalize();
    var written = 0;
    var deleted = 0;
    var skipped = new ArrayList<String>();

    for (var id : files.idsForProject(project)) {
      var path = id.substring(project.length() + 1);
      var target = files.comparableSnapshot(id);
      var targetContent = target == null ? null : (String) target.get("content");

      var destination = filesDir.resolve(path).normalize();
      if (!destination.startsWith(filesDir)) {
        skipped.add(path);
        continue;
      }

      var onDisk = readBase64(destination);
      switch (decide(targetContent, onDisk, onDisk != null && files.isKnownContent(id, onDisk))) {
        case IN_SYNC -> {}
        case SKIP_DIRTY -> skipped.add(path);
        case WRITE -> {
          writeFile(destination, targetContent);
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
    if (Objects.equals(onDisk, targetContent)) {
      return Action.IN_SYNC;
    }
    if (onDisk != null && !onDiskIsKnown) {
      return Action.SKIP_DIRTY;
    }
    return targetContent == null ? Action.DELETE : Action.WRITE;
  }

  private static String readBase64(Path file) throws IOException {
    if (!Files.isRegularFile(file)) {
      return null;
    }
    return Base64.getEncoder().encodeToString(Files.readAllBytes(file));
  }

  private static void writeFile(Path file, String base64) throws IOException {
    Files.createDirectories(file.getParent());
    Files.write(file, Base64.getDecoder().decode(base64));
  }
}
