/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.pty;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * What a session leaves on disk beside its master descriptor: the ring (its history), the meta
 * sidecar (what the descriptor cannot carry) and the exit file (the status its shim records). A
 * successor host adopts a session from these three plus the inherited descriptor; a sweep deletes
 * them together.
 */
public record SessionFiles(Path ring, Path meta, Path exit) {

  public static final String RING_SUFFIX = ".ring";
  public static final String META_SUFFIX = ".meta";
  public static final String EXIT_SUFFIX = ".exit";

  public static SessionFiles in(Path dir, String name) {
    return new SessionFiles(
        dir.resolve(name + RING_SUFFIX),
        dir.resolve(name + META_SUFFIX),
        dir.resolve(name + EXIT_SUFFIX));
  }

  /** The files of the session whose ring this is. */
  public static SessionFiles beside(Path ring) {
    var file = ring.getFileName().toString();
    var name =
        file.endsWith(RING_SUFFIX) ? file.substring(0, file.length() - RING_SUFFIX.length()) : file;
    var dir = ring.toAbsolutePath().getParent();
    return in(dir, name);
  }

  public void deleteAll() throws IOException {
    Files.deleteIfExists(ring);
    Files.deleteIfExists(meta);
    Files.deleteIfExists(exit);
  }
}
