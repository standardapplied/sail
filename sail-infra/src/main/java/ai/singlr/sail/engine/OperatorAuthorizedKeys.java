/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.engine;

import ai.singlr.sail.ssh.SshPublicKey;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.function.UnaryOperator;
import java.util.stream.Stream;

/**
 * The lines sail owns in the box operator's own {@code ~/.ssh/authorized_keys}: the login a paired
 * Mast uses, because the API's loopback port and the pty socket are the operator's. Every line it
 * writes carries a comment naming who it is for, and it removes only lines carrying that comment,
 * so the operator's own keys are never touched. Port and unix-socket forwarding stay allowed on a
 * line it writes: they are what Mast uses. An edit reads the file and replaces it whole, so each
 * one holds a lock beside the file from its read to its rename: two commands editing together would
 * otherwise each write back what it read, and a pairing revoked by one would return with the
 * other's write.
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
    Files.createDirectories(
        file.getParent(),
        PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
    var line = OPTIONS + " " + key.type() + " " + key.blob() + " " + comment;
    edit(lines -> Stream.concat(lines.stream(), Stream.of(line)).toList());
  }

  /**
   * Removes every key line whose comment is {@code comment}, and returns how many it removed. A
   * home with no such file has none, and is left as it is.
   */
  public int removeCommented(String comment) throws IOException {
    return Files.isRegularFile(file)
        ? edit(lines -> lines.stream().filter(line -> !carries(line, comment)).toList())
        : 0;
  }

  private static boolean carries(String line, String comment) {
    var fields = line.strip().split("\\s+");
    return !fields[0].startsWith("#") && fields[fields.length - 1].equals(comment);
  }

  /** Applies {@code change} to the file's lines as one step, and returns how many lines it took. */
  private int edit(UnaryOperator<List<String>> change) throws IOException {
    try (var held = FileMutex.acquire(file.resolveSibling(file.getFileName() + ".sail.lock"))) {
      var lines = Files.isRegularFile(file) ? Files.readAllLines(file) : List.<String>of();
      var changed = change.apply(lines);
      if (!changed.equals(lines)) {
        write(changed);
      }
      return lines.size() - changed.size();
    }
  }

  /**
   * Replaces the file in one rename, so a write cut short never leaves the operator a half-written
   * login file.
   */
  private void write(List<String> lines) throws IOException {
    var staged =
        Files.createTempFile(
            file.getParent(),
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
