/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.engine;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Resolves and lists workspace files from the {@code files/} directory convention. A {@code files/}
 * directory next to {@code sail.yaml} is pushed path-preserving into {@code ~/workspace/} inside
 * the container during provisioning.
 */
public final class WorkspaceFiles {

  /** A file entry with its host path and path relative to the {@code files/} directory. */
  public record FileEntry(Path hostPath, String relativePath) {}

  private static final Set<String> EXECUTABLE_EXTENSIONS = Set.of(".sh");

  private WorkspaceFiles() {}

  /**
   * Returns {@code true} if the given path should be treated as executable based on its file
   * extension.
   */
  public static boolean isExecutable(String path) {
    if (path == null) {
      return false;
    }
    var lower = path.toLowerCase(Locale.ROOT);
    return EXECUTABLE_EXTENSIONS.stream().anyMatch(lower::endsWith);
  }

  /**
   * Sets POSIX executable permissions on the given file if it {@linkplain #isExecutable(String) is
   * executable}.
   */
  public static void setExecutableIfNeeded(Path file) throws IOException {
    if (isExecutable(file.getFileName().toString())) {
      var perms = Files.getPosixFilePermissions(file);
      var mutable = new HashSet<>(perms);
      mutable.add(PosixFilePermission.OWNER_EXECUTE);
      mutable.add(PosixFilePermission.GROUP_EXECUTE);
      mutable.add(PosixFilePermission.OTHERS_EXECUTE);
      Files.setPosixFilePermissions(file, mutable);
    }
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
