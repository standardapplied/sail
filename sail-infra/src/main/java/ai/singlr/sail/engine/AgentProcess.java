/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.engine;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;

/**
 * The one seam a live member's process is started and driven through: a command run in a container
 * with its stdin and stdout held open as pipes, so the node writes each prompt as a line and reads
 * each step as a line. A terminal needs the pty host because its state is on the screen; a live
 * member's state is its harness's transcript and the room's messages, so pipes are all it needs,
 * and a host that outlived a restart of sail would plug in here.
 */
public interface AgentProcess {

  /** The signals a live member is ended by when it does not end on its own. */
  enum Signal {
    INT,
    TERM
  }

  /** Writes whole lines to the process's stdin. */
  interface LineWriter {

    /** Writes {@code line} and its newline, flushed, so the process reads it at once. */
    void write(String line) throws IOException;

    /** Closes stdin: the process reads end of input. Closing twice does nothing. */
    void close();
  }

  /** Reads whole lines from the process's stdout. */
  interface LineReader {

    /** The next line, blocking until one is written; {@code null} once stdout has ended. */
    String read() throws IOException;
  }

  /** A started process. */
  interface Handle {

    LineWriter stdin();

    LineReader stdout();

    /** The process's pid inside the container, read from its pid file; empty until written. */
    OptionalInt pid();

    /** Sends {@code signal} to the process inside the container; a process already gone is fine. */
    void signal(Signal signal);

    /**
     * The exit code, waiting up to {@code timeout} for the process to end; empty when it has not.
     */
    OptionalInt waitFor(Duration timeout) throws InterruptedException;
  }

  /**
   * Starts {@code command} in {@code container} as the dev user, in {@code workDir} under {@code
   * env}, its pid written to {@code pidFile} and its stderr appended to {@code logFile}, both paths
   * inside the container.
   */
  Handle start(
      String container,
      String workDir,
      Map<String, String> env,
      List<String> command,
      String pidFile,
      String logFile)
      throws IOException;
}
