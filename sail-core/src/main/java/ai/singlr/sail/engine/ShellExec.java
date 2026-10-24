/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.engine;

import ai.singlr.sail.common.Strings;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;

/**
 * Contract for executing shell commands. Production code depends on this interface, enabling test
 * doubles without subclassing.
 */
public interface ShellExec {

  /** Result of a completed shell command. */
  record Result(int exitCode, String stdout, String stderr) {
    public boolean ok() {
      return exitCode == 0;
    }

    /** Why the command failed: what it wrote to stderr, or its exit code when it wrote nothing. */
    public String reason() {
      return Strings.isBlank(stderr) ? "exited " + exitCode : stderr.strip();
    }
  }

  /**
   * Executes a command with the default timeout and no specific working directory.
   *
   * @param command the command tokens (e.g. {@code List.of("incus", "list", "--format", "json")})
   * @return the command result
   */
  Result exec(List<String> command) throws IOException, InterruptedException, TimeoutException;

  /**
   * Executes a command with a specific working directory and timeout.
   *
   * @param command the command tokens
   * @param workDir optional working directory (null = inherit)
   * @param timeout how long to wait before killing the process
   * @return the command result
   */
  Result exec(List<String> command, Path workDir, Duration timeout)
      throws IOException, InterruptedException, TimeoutException;

  /**
   * Runs a command to its exit however long it takes, handing each line of its stdout to {@code
   * lines} as it arrives: for a command that bounds its own waits and reports its own progress,
   * such as apt, where a deadline could only ever kill it mid-write. The result holds all of both
   * streams.
   */
  default Result run(List<String> command, Consumer<String> lines)
      throws IOException, InterruptedException {
    throw new UnsupportedOperationException("This executor does not run commands to exit");
  }

  /** Streams raw stdout; closing the stream also closes the child process. */
  default java.io.InputStream stream(List<String> command) throws IOException {
    throw new UnsupportedOperationException("This executor does not support streaming output");
  }

  /** Returns true if this executor is in dry-run mode (prints commands instead of executing). */
  boolean isDryRun();
}
