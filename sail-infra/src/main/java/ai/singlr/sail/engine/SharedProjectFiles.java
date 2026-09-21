/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.engine;

import ai.singlr.sail.api.ProjectFiles;
import ai.singlr.sail.store.FileStore;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

public record SharedProjectFiles(FileStore files, Path projectsDir, String project)
    implements ProjectFiles {
  public SharedProjectFiles {
    NameValidator.requireValidProjectName(project);
  }

  @Override
  public List<FileStore.FileRow> list() {
    return files.list(project);
  }

  @Override
  public Optional<FileStore.FileRow> find(String path) {
    return files.find(project, path);
  }

  @Override
  public InputStream open(FileStore.FileRow row) {
    return files.open(row);
  }

  @Override
  public String put(String path, InputStream bytes, long size, int mode) {
    if (!FilePicker.isShareablePath(path)) {
      throw new IllegalArgumentException("Unsafe share path: '" + path + "'.");
    }
    var limits = limits();
    limits.check(size);
    files.put(project, path, limits.bounded(bytes, size), mode);
    return path;
  }

  @Override
  public boolean remove(String path) throws IOException {
    if (!files.delete(project, path)) {
      return false;
    }
    materialize();
    return true;
  }

  @Override
  public FileMaterializer.Report materialize() throws IOException {
    return new FileMaterializer(files, projectsDir).materialize(project);
  }
}
