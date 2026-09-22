/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.api;

import ai.singlr.sail.config.FileLimits;
import ai.singlr.sail.engine.FileMaterializer;
import ai.singlr.sail.store.FileStore;
import java.io.IOException;
import java.util.List;
import java.util.Optional;

/**
 * One project's shared files. The size cap is read once, when an instance is made for a command or
 * a request, so a bulk share consults {@code host.yaml} once and a corrupt one fails before any
 * file is opened.
 */
public interface ProjectFiles {
  FileLimits limits();

  List<FileStore.FileRow> list();

  Optional<FileStore.FileRow> find(String path);

  java.io.InputStream open(FileStore.FileRow row);

  default Optional<java.io.InputStream> get(String path) {
    return find(path).map(this::open);
  }

  String put(String path, java.io.InputStream bytes, long size, int mode);

  boolean remove(String path) throws IOException;

  FileMaterializer.Report materialize() throws IOException;
}
