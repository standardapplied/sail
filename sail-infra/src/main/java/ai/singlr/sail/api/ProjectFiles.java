/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.api;

import ai.singlr.sail.engine.FileMaterializer;
import ai.singlr.sail.store.FileStore;
import java.io.IOException;
import java.util.List;
import java.util.Optional;

public interface ProjectFiles {
  int MAX_BYTES = 5 * 1024 * 1024;

  List<FileStore.FileRow> list();

  Optional<byte[]> get(String path);

  String put(String path, byte[] bytes);

  boolean remove(String path) throws IOException;

  FileMaterializer.Report materialize() throws IOException;
}
