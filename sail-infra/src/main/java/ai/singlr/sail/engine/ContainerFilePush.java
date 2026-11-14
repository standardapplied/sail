/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.engine;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeoutException;

/**
 * Writes content to a file inside an Incus container via {@code incus file push}, avoiding shell
 * injection. Content is staged to a host-side temp file, pushed with the caller-supplied flags
 * (mode, ownership), then deleted whether or not the push succeeds.
 */
public final class ContainerFilePush {

  private ContainerFilePush() {}

  /**
   * Pushes {@code content} to {@code remotePath} inside {@code containerName}. The {@code flags}
   * are inserted between {@code incus file push} and the source/destination arguments — e.g. {@code
   * List.of("--mode", "0600")} or {@code List.of("--uid", "1000", "--gid", "1000")}.
   */
  public static void push(
      ShellExec shell, String containerName, String remotePath, String content, List<String> flags)
      throws IOException, InterruptedException, TimeoutException {
    var tmpFile = Files.createTempFile("sail-push-", ".tmp");
    try {
      Files.writeString(tmpFile, content);
      push(shell, containerName, remotePath, tmpFile, flags);
    } finally {
      Files.deleteIfExists(tmpFile);
    }
  }

  /**
   * Pushes {@code content} to {@code remotePath} inside {@code containerName} as the dev user's
   * file, of {@code mode} when one is given ({@code 0600} for a secret), its directory made first
   * as the dev user: the one way Sail hands the dev user a file it wrote, a credential included.
   */
  public static void pushAsDev(
      ShellExec shell, String containerName, String remotePath, String content, String mode)
      throws IOException, InterruptedException, TimeoutException {
    var parentDir = remotePath.substring(0, remotePath.lastIndexOf('/'));
    shell.exec(ContainerExec.asDevUser(containerName, List.of("mkdir", "-p", parentDir)));
    var flags =
        new ArrayList<>(List.of("--uid", ContainerExec.DEV_UID, "--gid", ContainerExec.DEV_GID));
    if (mode != null) {
      flags.addAll(List.of("--mode", mode));
    }
    push(shell, containerName, remotePath, content, flags);
  }

  /**
   * Pushes the host file {@code source} to {@code remotePath} inside {@code containerName}, with
   * {@code flags} as above. The source is the caller's: it is read, never deleted.
   */
  public static void push(
      ShellExec shell, String containerName, String remotePath, Path source, List<String> flags)
      throws IOException, InterruptedException, TimeoutException {
    var cmd = new ArrayList<>(List.of("incus", "file", "push"));
    cmd.addAll(flags);
    cmd.add(source.toString());
    cmd.add(containerName + remotePath);
    var result = shell.exec(cmd);
    if (!result.ok()) {
      throw new IOException("Failed to push file to " + remotePath + ": " + result.stderr());
    }
  }
}
