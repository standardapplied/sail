/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.engine;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.TimeoutException;
import java.util.stream.Stream;

/**
 * Reads a folder someone is sharing as a skill, wherever they made it: on this box, or in a
 * project's container as the dev user, who made it there. Both read it the same way: every entry
 * under it a folder or a regular file at a path that can be shared, its files counted and summed
 * against {@link StageSkill}'s bounds before a byte is read, and each file read whole with its
 * mode. Whether the files are a skill is {@link StageSkill}'s to say; here they are only read.
 *
 * <p>A path from a container is untrusted input and reaches a shell only as an argument.
 */
public final class SkillFolders {

  /** The exit status the container gives for a path that is not what it was asked to be. */
  static final int NOT_WHAT_IT_WAS = 3;

  /**
   * Lists the folder's entries that are not folders, no more than {@code $2} of them, so a folder
   * that could never be a skill is refused without the host holding its whole listing. The pipe's
   * status is {@code head}'s, so a {@code find} that could not read part of the folder is known by
   * what it wrote to stderr, where it writes nothing when it succeeds or when {@code head} closes.
   */
  private static final String LIST =
      """
      if [ ! -d "$1" ] || [ -L "$1" ]; then exit %d; fi
      cd "$1" && find . -mindepth 1 ! -type d -printf '%%y %%s %%m %%P\\0' | head -z -n "$2"
      """
          .formatted(NOT_WHAT_IT_WAS);

  /**
   * Prints a regular file, no more than {@code $2} bytes of it, as base64: a file that became a
   * link or a device since it was listed is refused, and one that grew is cut where its listed size
   * ends plus one byte, so its read is bounded and the growth is seen.
   */
  private static final String READ =
      """
      if [ ! -f "$1" ] || [ -L "$1" ]; then exit %d; fi
      head -c "$2" -- "$1" | base64 -w0
      """
          .formatted(NOT_WHAT_IT_WAS);

  private SkillFolders() {}

  /**
   * One file of a folder being shared.
   *
   * @param path its path relative to the folder
   * @param bytes its content
   * @param mode its permission bits
   */
  public record Entry(String path, byte[] bytes, int mode) {}

  /** The folder at {@code folder} on this box, read as skill {@code name}. */
  public static List<Entry> onHost(Path folder, String name) throws IOException {
    if (!Files.isDirectory(folder, LinkOption.NOFOLLOW_LINKS)) {
      throw new IllegalArgumentException("Not a folder: " + folder);
    }
    var listed = new ArrayList<Path>();
    var bytes = 0L;
    try (Stream<Path> walk = Files.walk(folder)) {
      for (var path : walk.sorted().toList()) {
        if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
          continue;
        }
        var relative = folder.relativize(path).toString().replace('\\', '/');
        requireShareable(name, relative, Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS));
        listed.add(path);
        bytes += Files.size(path);
      }
    }
    StageSkill.requireWithinBounds(name, listed.size(), bytes);
    var entries = new ArrayList<Entry>();
    for (var path : listed) {
      var relative = folder.relativize(path).toString().replace('\\', '/');
      entries.add(new Entry(relative, Files.readAllBytes(path), WorkspaceFiles.mode(path)));
    }
    return List.copyOf(entries);
  }

  /**
   * The folder at the absolute {@code folder} in {@code project}'s container, read as skill {@code
   * name}.
   */
  public static List<Entry> inContainer(ShellExec shell, String project, String folder, String name)
      throws IOException, InterruptedException, TimeoutException {
    if (!folder.startsWith("/") || folder.chars().anyMatch(c -> c < 0x20)) {
      throw new IllegalArgumentException(
          "The folder to share must be an absolute path in the container: " + folder);
    }
    StageSkill.requireName(name);
    var listed =
        shell.exec(
            ContainerExec.asDevUser(
                project,
                List.of("sh", "-c", LIST, "sh", folder, String.valueOf(StageSkill.MAX_FILES + 1))));
    if (listed.exitCode() == NOT_WHAT_IT_WAS) {
      throw new IllegalArgumentException("Not a folder: " + folder);
    }
    if (!listed.ok() || !listed.stderr().isBlank()) {
      throw new IOException(
          "Failed to list " + folder + " in " + project + ": " + listed.stderr().strip());
    }
    var files = new ArrayList<Listed>();
    for (var line : listed.stdout().split("\0")) {
      if (!line.isEmpty()) {
        files.add(Listed.of(line));
      }
    }
    if (files.size() > StageSkill.MAX_FILES) {
      throw new IllegalArgumentException(
          "Skill '%s' has more than %d files; the limit is %d."
              .formatted(name, StageSkill.MAX_FILES, StageSkill.MAX_FILES));
    }
    files.forEach(file -> requireShareable(name, file.path(), file.regular()));
    StageSkill.requireWithinBounds(
        name, files.size(), files.stream().mapToLong(Listed::size).sum());
    var entries = new ArrayList<Entry>();
    for (var file : files.stream().sorted((a, b) -> a.path().compareTo(b.path())).toList()) {
      entries.add(new Entry(file.path(), read(shell, project, folder, name, file), file.mode()));
    }
    return List.copyOf(entries);
  }

  private static byte[] read(
      ShellExec shell, String project, String folder, String name, Listed file)
      throws IOException, InterruptedException, TimeoutException {
    var path = folder + "/" + file.path();
    var read =
        shell.exec(
            ContainerExec.asDevUser(
                project, List.of("sh", "-c", READ, "sh", path, String.valueOf(file.size() + 1))));
    if (read.exitCode() == NOT_WHAT_IT_WAS) {
      throw new IllegalArgumentException(
          "Skill '%s' changed while it was being read: %s is no longer a regular file. Share it again."
              .formatted(name, path));
    }
    if (!read.ok() || !read.stderr().isBlank()) {
      throw new IOException(
          "Failed to read " + path + " in " + project + ": " + read.stderr().strip());
    }
    var bytes = Base64.getDecoder().decode(read.stdout().strip());
    if (bytes.length != file.size()) {
      throw new IllegalArgumentException(
          "Skill '%s' changed while it was being read: %s is no longer %d bytes. Share it again."
              .formatted(name, path, file.size()));
    }
    return bytes;
  }

  private static void requireShareable(String name, String path, boolean regular) {
    if (!regular) {
      throw new IllegalArgumentException(
          "Skill '" + name + "' holds " + path + ", which is not a regular file.");
    }
    if (!FilePicker.isShareablePath(path)) {
      throw new IllegalArgumentException(
          "Skill '" + name + "' holds the path '" + path + "', which cannot be shared.");
    }
  }

  /** One entry of the container's listing: its type, size, mode and path. */
  private record Listed(boolean regular, long size, int mode, String path) {

    static Listed of(String line) {
      var fields = line.split(" ", 4);
      return new Listed(
          fields[0].equals("f"),
          Long.parseLong(fields[1]),
          Integer.parseInt(fields[2], 8),
          fields[3]);
    }
  }
}
