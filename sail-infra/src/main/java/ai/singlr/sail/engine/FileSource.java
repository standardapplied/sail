/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.engine;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/**
 * Where the file picker reads from — the host filesystem ({@link HostFileSource}) or a project's
 * container workspace ({@link ContainerFileSource}). The pure {@link FilePicker} navigation engine
 * works on path values alone; only these four operations touch a real filesystem, so swapping the
 * source is all it takes to browse a container instead of the host.
 */
public interface FileSource {

  /** Immediate children of {@code dir} (files and sub-folders), unsorted and unfiltered. */
  List<FilePicker.Entry> children(Path dir) throws IOException;

  /** Whether {@code path} is a directory. */
  boolean isDirectory(Path path) throws IOException;

  /** Size of {@code file} in bytes — used to skip oversized files before reading them. */
  long size(Path file) throws IOException;

  /**
   * Every regular file under {@code dir}, recursively, with {@link FilePicker#IGNORED_DIRS} pruned
   * and unreadable subtrees tolerated (skipped, never fatal).
   */
  List<Path> walkFiles(Path dir) throws IOException;

  /** Reads a file's bytes. */
  java.io.InputStream open(Path file) throws IOException;

  int mode(Path file) throws IOException;
}
