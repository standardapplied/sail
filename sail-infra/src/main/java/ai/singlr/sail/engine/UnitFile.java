/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.engine;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * A systemd unit's file and, for a user unit, the discovery link systemd finds it through. Writing
 * and removing them honours the shell's dry run the way its commands do: a dry run prints what it
 * would change on disk and changes nothing.
 */
record UnitFile(ShellExec shell, Path file, Path link) {

  /** Writes {@code content} to the unit file and points the discovery link, if any, at it. */
  void write(String content) throws IOException {
    if (shell.isDryRun()) {
      System.out.println("[dry-run] Write " + file);
      if (link != null) {
        System.out.println("[dry-run] Link " + link + " -> " + file);
      }
      return;
    }
    Files.createDirectories(file.getParent());
    Files.writeString(file, content);
    if (link != null) {
      Files.createDirectories(link.getParent());
      Files.deleteIfExists(link);
      Files.createSymbolicLink(link, file);
    }
  }

  /** Removes the discovery link, if any, and the unit file; either missing is no error. */
  void remove() throws IOException {
    if (shell.isDryRun()) {
      if (link != null) {
        System.out.println("[dry-run] Remove " + link);
      }
      System.out.println("[dry-run] Remove " + file);
      return;
    }
    if (link != null) {
      Files.deleteIfExists(link);
    }
    Files.deleteIfExists(file);
  }
}
