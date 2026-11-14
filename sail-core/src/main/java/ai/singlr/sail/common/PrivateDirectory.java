/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.common;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.Optional;

/**
 * A directory this process's account alone may enter: its own user and group, mode exactly {@code
 * 0700}, whatever a parent's setgid bit or group would give it. What holds a secret on the host
 * (the logins, content staged for a container) lives in one.
 */
public final class PrivateDirectory {

  private static final int OWNER_ONLY_MODE = 0700;

  private PrivateDirectory() {}

  /**
   * Makes {@code dir} such a directory, repairing one found otherwise, and returns it; a link, or a
   * file, where it belongs is refused.
   *
   * @throws UncheckedIOException when it cannot be made or made this account's alone
   */
  public static Path ensure(Path dir) {
    try {
      try {
        Files.createDirectory(
            dir,
            PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
      } catch (FileAlreadyExistsException present) {
        if (Files.isSymbolicLink(dir) || !Files.isDirectory(dir, LinkOption.NOFOLLOW_LINKS)) {
          throw new IOException(dir + " is not a directory.");
        }
      }
      if (ProcessOwner.CURRENT.isPresent()) {
        var self = ProcessOwner.CURRENT.get();
        Files.setAttribute(dir, "unix:uid", self.uid(), LinkOption.NOFOLLOW_LINKS);
        Files.setAttribute(dir, "unix:gid", self.gid(), LinkOption.NOFOLLOW_LINKS);
      }
      Files.setAttribute(dir, "unix:mode", OWNER_ONLY_MODE, LinkOption.NOFOLLOW_LINKS);
      return dir;
    } catch (IOException e) {
      throw new UncheckedIOException("Could not make " + dir + " a private directory.", e);
    }
  }

  /**
   * This process's effective user and group ids, from {@code /proc/self/status}: on Linux, where
   * every box runs. A system without it (a macOS test run) has none, and a directory there keeps
   * the owner it was made with; its mode is still set.
   */
  public record ProcessOwner(int uid, int gid) {

    private static final Path STATUS = Path.of("/proc/self/status");
    public static final Optional<ProcessOwner> CURRENT =
        Files.isReadable(STATUS) ? Optional.of(read(STATUS)) : Optional.empty();

    static ProcessOwner read(Path status) {
      try {
        var lines = Files.readAllLines(status);
        return new ProcessOwner(effective(lines, "Uid:"), effective(lines, "Gid:"));
      } catch (IOException e) {
        throw new UncheckedIOException("Could not read this process's ids from " + status + ".", e);
      }
    }

    private static int effective(List<String> lines, String field) {
      return lines.stream()
          .filter(line -> line.startsWith(field))
          .map(line -> Integer.parseInt(line.substring(field.length()).trim().split("\\s+")[1]))
          .findFirst()
          .orElseThrow(
              () -> new IllegalStateException("No " + field + " line in /proc/self/status."));
    }
  }
}
