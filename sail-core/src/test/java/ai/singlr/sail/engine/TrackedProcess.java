/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.engine;

import java.io.FilterInputStream;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Stream;

/**
 * A process that says whether whoever started it closed its stdin and its stdout. The JDK's reaper
 * closes an exited child's pipes on its own a moment after the exit, so a descriptor count cannot
 * tell a pipe its owner closed from one left to the reaper; this can, as the reaper never closes
 * these wrappers. The stderr of the processes it wraps is dropped at their start, as production
 * drops it, so it is not tracked.
 */
public final class TrackedProcess extends Process {

  private final Process process;
  private final Input input;
  private final Output output;
  private final CompletableFuture<Void> closed = new CompletableFuture<>();

  private TrackedProcess(Process process) {
    this.process = process;
    this.input = new Input(process.getOutputStream());
    this.output = new Output(process.getInputStream());
  }

  /** {@code process}, its stdin and stdout tracked. */
  public static TrackedProcess of(Process process) {
    return new TrackedProcess(process);
  }

  /** Whether the process's stdin was closed through this. */
  public boolean inputClosed() {
    return input.closed;
  }

  /** Whether the process's stdout was closed through this. */
  public boolean outputClosed() {
    return output.closed;
  }

  /**
   * Waits at most {@code patience} for whoever started the process to close its stdin and its
   * stdout, failing with the one still open: for a close made on a thread of the code under test.
   */
  public void awaitClosed(Duration patience) throws InterruptedException {
    try {
      closed.get(patience.toMillis(), TimeUnit.MILLISECONDS);
    } catch (TimeoutException | ExecutionException open) {
      throw new AssertionError(
          info().commandLine().orElse("a process")
              + " still has open: "
              + (inputClosed() ? "" : "stdin ")
              + (outputClosed() ? "" : "stdout"));
    }
  }

  private void closing() {
    if (input.closed && output.closed) {
      closed.complete(null);
    }
  }

  @Override
  public OutputStream getOutputStream() {
    return input;
  }

  @Override
  public InputStream getInputStream() {
    return output;
  }

  @Override
  public InputStream getErrorStream() {
    return process.getErrorStream();
  }

  @Override
  public int waitFor() throws InterruptedException {
    return process.waitFor();
  }

  @Override
  public boolean waitFor(long timeout, TimeUnit unit) throws InterruptedException {
    return process.waitFor(timeout, unit);
  }

  @Override
  public int exitValue() {
    return process.exitValue();
  }

  @Override
  public void destroy() {
    process.destroy();
  }

  @Override
  public Process destroyForcibly() {
    process.destroyForcibly();
    return this;
  }

  @Override
  public boolean isAlive() {
    return process.isAlive();
  }

  @Override
  public long pid() {
    return process.pid();
  }

  @Override
  public ProcessHandle toHandle() {
    return process.toHandle();
  }

  @Override
  public CompletableFuture<Process> onExit() {
    return process.onExit().thenApply(ended -> this);
  }

  @Override
  public ProcessHandle.Info info() {
    return process.info();
  }

  @Override
  public Stream<ProcessHandle> children() {
    return process.children();
  }

  @Override
  public Stream<ProcessHandle> descendants() {
    return process.descendants();
  }

  private final class Input extends FilterOutputStream {
    private volatile boolean closed;

    Input(OutputStream out) {
      super(out);
    }

    @Override
    public void write(byte[] bytes, int offset, int length) throws IOException {
      out.write(bytes, offset, length);
    }

    @Override
    public void close() throws IOException {
      closed = true;
      closing();
      out.close();
    }
  }

  private final class Output extends FilterInputStream {
    private volatile boolean closed;

    Output(InputStream in) {
      super(in);
    }

    @Override
    public void close() throws IOException {
      closed = true;
      closing();
      in.close();
    }
  }
}
