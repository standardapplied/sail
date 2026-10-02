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
 * copy the moment they upgrade — the disk-to-DB counterpart of {@link FileMaterializer}. It
 * publishes only a person's copy: one this box recorded writing ({@link FileStore#materialized}) is
 * its own output, whether current, since superseded by another box's version, or of a file since
 * deleted, and is never published or resurrected over the fleet's newer state; a deleted file's
 * copy is left for the materializer to remove. A person's copy is published unless the store
 * already holds it, and recorded as this box's either way, so no later upgrade publishes it again.
 * Idempotent: re-running on every upgrade neither churns revisions nor re-imports.
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
    var cap = files.cappedByMain(limits.get());
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
        var id = FileStore.idOf(project, path);
        if (files.materialized(id, hash, mode)) {
          continue;
        }
        if (!files.find(project, path).map(row -> holds(row, hash, mode)).orElse(false)) {
          try (var input = Files.newInputStream(file)) {
            files.put(project, path, limits.bounded(input, size), mode);
          }
          imported++;
        }
        files.recordMaterialized(id, hash, mode);
      }
    }
    return imported;
  }

  private static boolean holds(FileStore.FileRow row, String hash, int mode) {
    return row.contentHash().equals(hash) && row.mode() == mode;
  }
}
