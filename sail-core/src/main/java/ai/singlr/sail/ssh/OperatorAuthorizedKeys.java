/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.ssh;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.List;

/**
 * The lines sail owns in the box operator's own {@code ~/.ssh/authorized_keys}: the login a paired
 * Mast uses, because the API's loopback port and the pty socket are the operator's. Every line it
 * writes carries a comment naming who it is for, and it removes only lines carrying that comment,
 * so the operator's own keys are never touched. Port and unix-socket forwarding stay allowed on a
 * line it writes: they are what Mast uses.
 */
public final class OperatorAuthorizedKeys {

  private static final String OPTIONS = "no-agent-forwarding,no-X11-forwarding,no-user-rc";

  private final Path file;

  /**
   * @param home the operator's home directory
   */
  public OperatorAuthorizedKeys(Path home) {
    this.file = home.resolve(".ssh").resolve("authorized_keys");
  }

  /** Lets {@code key} log in as the operator, on a line whose comment is {@code comment}. */
  public void append(SshPublicKey key, String comment) throws IOException {
    var lines = new ArrayList<>(lines());
    lines.add(OPTIONS + " " + key.type() + " " + key.blob() + " " + comment);
    write(lines);
  }

  /** Removes every key line whose comment is {@code comment}, and returns how many it removed. */
  public int removeCommented(String comment) throws IOException {
    var lines = lines();
    var kept = lines.stream().filter(line -> !carries(line, comment)).toList();
    if (kept.size() < lines.size()) {
      write(kept);
    }
    return lines.size() - kept.size();
  }

  private static boolean carries(String line, String comment) {
    var fields = line.strip().split("\\s+");
    return !fields[0].startsWith("#") && fields[fields.length - 1].equals(comment);
  }

  private List<String> lines() throws IOException {
    return Files.isRegularFile(file) ? Files.readAllLines(file) : List.of();
  }

  /**
   * Replaces the file in one rename, so a write cut short never leaves the operator a half-written
   * login file.
   */
  private void write(List<String> lines) throws IOException {
    var directory = file.getParent();
    if (!Files.isDirectory(directory)) {
      Files.createDirectories(
          directory,
          PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
    }
    var staged =
        Files.createTempFile(
            directory,
            "authorized_keys",
            ".tmp",
            PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
    try {
      Files.write(staged, lines);
      Files.move(staged, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    } finally {
      Files.deleteIfExists(staged);
    }
  }
}
