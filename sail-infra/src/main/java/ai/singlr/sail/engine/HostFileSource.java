/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.engine;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.FileVisitor;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/** The host filesystem as a {@link FileSource} — used when picking with {@code --from}. */
public final class HostFileSource implements FileSource {

  @Override
  public List<FilePicker.Entry> children(Path dir) throws IOException {
    try (Stream<Path> entries = Files.list(dir)) {
      return entries.map(HostFileSource::toEntry).toList();
    }
  }

  private static FilePicker.Entry toEntry(Path p) {
    var dir = Files.isDirectory(p);
    long size = 0;
    if (!dir) {
      try {
        size = Files.size(p);
      } catch (IOException ignored) {
        size = 0;
      }
    }
    return new FilePicker.Entry(p, dir, size);
  }

  @Override
  public boolean isDirectory(Path path) {
    return Files.isDirectory(path);
  }

  @Override
  public long size(Path file) throws IOException {
    return Files.size(file);
  }

  @Override
  public List<Path> walkFiles(Path dir) throws IOException {
    var files = new ArrayList<Path>();
    Files.walkFileTree(dir, new CollectingVisitor(files));
    return files;
  }

  @Override
  public java.io.InputStream open(Path file) throws IOException {
    return Files.newInputStream(file);
  }

  @Override
  public int mode(Path file) throws IOException {
    return WorkspaceFiles.mode(file);
  }

  private record CollectingVisitor(List<Path> files) implements FileVisitor<Path> {
    @Override
    public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
      return FilePicker.IGNORED_DIRS.contains(dir.getFileName().toString())
          ? FileVisitResult.SKIP_SUBTREE
          : FileVisitResult.CONTINUE;
    }

    @Override
    public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
      if (attrs.isRegularFile()) {
        files.add(file);
      }
      return FileVisitResult.CONTINUE;
    }

    @Override
    public FileVisitResult visitFileFailed(Path file, IOException exc) {
      return FileVisitResult.CONTINUE;
    }

    @Override
    public FileVisitResult postVisitDirectory(Path dir, IOException exc) {
      return FileVisitResult.CONTINUE;
    }
  }
}
