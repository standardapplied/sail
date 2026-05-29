/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.engine;

import java.io.IOException;
import java.nio.file.Files;
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
      var cmd = new ArrayList<>(List.of("incus", "file", "push"));
      cmd.addAll(flags);
      cmd.add(tmpFile.toString());
      cmd.add(containerName + remotePath);
      var result = shell.exec(cmd);
      if (!result.ok()) {
        throw new IOException("Failed to push file to " + remotePath + ": " + result.stderr());
      }
    } finally {
      Files.deleteIfExists(tmpFile);
    }
  }
}
