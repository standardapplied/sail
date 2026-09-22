/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.engine;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * Resolves and lists workspace files from the {@code files/} directory convention. A {@code files/}
 * directory next to {@code sail.yaml} is pushed path-preserving into {@code ~/workspace/} inside
 * the container during provisioning.
 */
public final class WorkspaceFiles {

  /** A file entry with its host path and path relative to the {@code files/} directory. */
  public record FileEntry(Path hostPath, String relativePath) {}

  private WorkspaceFiles() {}

  public static int mode(Path file) throws IOException {
    return (int) Files.getAttribute(file, "unix:mode") & 0777;
  }

  public static void mode(Path file, int mode) throws IOException {
    Files.setAttribute(file, "unix:mode", mode, java.nio.file.LinkOption.NOFOLLOW_LINKS);
  }

  /**
   * Resolves the {@code files/} directory next to the given {@code sail.yaml} path.
   *
   * @return the files directory path, or {@code null} if it does not exist or is not a directory
   */
  public static Path resolveFilesDir(Path sailYamlPath) {
    if (sailYamlPath == null) {
      return null;
    }
    var parent = sailYamlPath.toAbsolutePath().getParent();
    if (parent == null) {
      return null;
    }
    var filesDir = parent.resolve("files");
    if (Files.isDirectory(filesDir)) {
      return filesDir;
    }
    return null;
  }

  /**
   * Lists all regular files under the given directory, recursively. Returns entries with paths
   * relative to {@code filesDir} using forward slashes.
   *
   * @return an immutable list of file entries sorted by relative path
   */
  public static List<FileEntry> listFiles(Path filesDir) throws IOException {
    try (Stream<Path> walk = Files.walk(filesDir)) {
      return walk.filter(Files::isRegularFile)
          .map(
              p -> {
                var rel = filesDir.relativize(p);
                var relStr = rel.toString().replace('\\', '/');
                return new FileEntry(p, relStr);
              })
          .sorted(Comparator.comparing(FileEntry::relativePath))
          .toList();
    }
  }
}
