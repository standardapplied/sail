/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.engine;

import ai.singlr.sail.config.FileLimits;
import ai.singlr.sail.store.BlobStore;
import ai.singlr.sail.store.FileStore;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;
import java.util.stream.Stream;

/**
 * Imports each project's on-disk workspace files ({@code ~/.sail/projects/<name>/files/**}) into
 * the synced {@link FileStore}, so the file tree an FDE already has becomes the shared, replicated
 * copy the moment they upgrade — the disk-to-DB counterpart of {@link FileMaterializer}.
 * Idempotent: a file already stored with the same content is skipped, so re-running on every
 * upgrade neither churns revisions nor re-imports. The read-only counterpart to the git-based
 * {@code project pull} files directory it replaces.
 */
public final class FileImporter {

  private final Path projectsDir;
  private final FileStore files;
  private final Supplier<FileLimits> limits;

  public FileImporter(Path projectsDir, FileStore files) {
    this(projectsDir, files, FileLimits::load);
  }

  /** With the size cap read by {@code limits}, once per import, before any file is opened. */
  public FileImporter(Path projectsDir, FileStore files, Supplier<FileLimits> limits) {
    this.projectsDir = projectsDir;
    this.files = files;
    this.limits = limits;
  }

  public record Report(int imported, List<String> notes) {}

  public Report importAll() {
    var cap = limits.get();
    if (!Files.isDirectory(projectsDir)) {
      return new Report(0, List.of());
    }
    var imported = 0;
    var notes = new ArrayList<String>();
    try (Stream<Path> projects = Files.list(projectsDir)) {
      for (var projectDir : projects.filter(Files::isDirectory).toList()) {
        if (files.projectPruned(projectDir.getFileName().toString())) {
          notes.add(
              "Skipped '"
                  + projectDir.getFileName()
                  + "': the project was pruned, so its files on disk are not imported.");
          continue;
        }
        imported +=
            importProject(projectDir.getFileName().toString(), projectDir.resolve("files"), cap);
      }
    } catch (IOException e) {
      notes.add("Could not scan project files: " + e.getMessage());
    }
    return new Report(imported, List.copyOf(notes));
  }

  private int importProject(String project, Path filesDir, FileLimits limits) throws IOException {
    if (!Files.isDirectory(filesDir)) {
      return 0;
    }
    var imported = 0;
    try (Stream<Path> tree = Files.walk(filesDir)) {
      for (var file : tree.filter(Files::isRegularFile).toList()) {
        var path = filesDir.relativize(file).toString();
        var size = Files.size(file);
        limits.check(size);
        String hash;
        try (var input = Files.newInputStream(file)) {
          hash = BlobStore.hash(limits.bounded(input, size));
        }
        var mode = WorkspaceFiles.mode(file);
        if (files.find(project, path).map(row -> changed(row, hash, mode)).orElse(true)) {
          try (var input = Files.newInputStream(file)) {
            files.put(project, path, limits.bounded(input, size), mode);
          }
          imported++;
        }
      }
    }
    return imported;
  }

  /**
   * Whether the copy on disk is an edit the store has not seen. New content always is. A new mode
   * on the same content is only when no revision of this file knows that content at that mode — a
   * real {@code chmod}; a legacy revision recorded no mode, so the umask an older materializer gave
   * the copy is no edit, and recording it would raise a mode conflict on every node.
   */
  private boolean changed(FileStore.FileRow row, String hash, int mode) {
    if (!row.contentHash().equals(hash)) {
      return true;
    }
    return row.mode() != mode
        && !files.isKnownVersion(FileStore.idOf(row.project(), row.path()), hash, mode);
  }
}
