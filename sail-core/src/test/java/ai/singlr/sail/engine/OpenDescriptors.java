/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.engine;

import java.io.IOException;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/** The descriptors this process holds open, and what of them a stretch of work opened. */
public final class OpenDescriptors {

  private static final Path DEV_FD = Path.of("/dev/fd");

  private OpenDescriptors() {}

  /**
   * The descriptors this process holds open, as {@code /dev/fd} lists them on both systems, each
   * with what it is open on (its file key, which a reused number does not share); the listing's own
   * descriptor is left out, and so is one closed while it is listed (gone on Linux, a bad
   * descriptor on macOS).
   */
  public static Map<String, Object> list() throws IOException {
    var listing = Files.readAttributes(DEV_FD, BasicFileAttributes.class).fileKey();
    var open = new TreeMap<String, Object>();
    try (var fds = Files.list(DEV_FD)) {
      for (var fd : fds.toList()) {
        try {
          var key = Files.readAttributes(fd, BasicFileAttributes.class).fileKey();
          if (!Objects.equals(key, listing)) {
            open.put(fd.getFileName().toString(), Objects.requireNonNullElse(key, "unkeyed"));
          }
        } catch (FileSystemException closedMeanwhile) {
          var unused = closedMeanwhile;
        }
      }
    }
    return open;
  }

  /** What is open {@code now} that was not open on the same thing {@code before}. */
  public static Map<String, Object> opened(Map<String, Object> before, Map<String, Object> now) {
    var opened = new TreeMap<String, Object>(now);
    opened.entrySet().removeIf(entry -> entry.getValue().equals(before.get(entry.getKey())));
    return opened;
  }
}
