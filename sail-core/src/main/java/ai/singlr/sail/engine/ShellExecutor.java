/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.engine;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Thin wrapper around {@link ProcessBuilder} that implements {@link ShellExec}. Supports dry-run
 * mode (prints commands instead of executing) and configurable timeouts.
 */
public final class ShellExecutor implements ShellExec {

  private final boolean dryRun;
  private final Duration defaultTimeout;

  public ShellExecutor(boolean dryRun, Duration defaultTimeout) {
    this.dryRun = dryRun;
    this.defaultTimeout = defaultTimeout;
  }

  public ShellExecutor(boolean dryRun) {
    this(dryRun, Duration.ofSeconds(120));
  }

  @Override
  public Result exec(List<String> command)
      throws IOException, InterruptedException, TimeoutException {
    return exec(command, null, defaultTimeout);
  }

  @Override
  public Result exec(List<String> command, Path workDir, Duration timeout)
      throws IOException, InterruptedException, TimeoutException {
    if (dryRun) {
      return dryRunResult(command);
    }

    var pb = new ProcessBuilder(command);
    pb.redirectErrorStream(false);
    if (workDir != null) {
      pb.directory(workDir.toFile());
    }

    var process = pb.start();
    process.getOutputStream().close();

    var stdout = new AtomicReference<String>("");
    var stderr = new AtomicReference<String>("");
    var stdoutThread =
        Thread.ofVirtual().start(() -> stdout.set(readFully(process.getInputStream())));
    var stderrThread =
        Thread.ofVirtual().start(() -> stderr.set(readFully(process.getErrorStream())));

    var finished = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
    if (!finished) {
      process.destroyForcibly();
      throw new TimeoutException(
          "Command timed out after " + timeout.toSeconds() + "s: " + String.join(" ", command));
    }

    stdoutThread.join();
    stderrThread.join();
    return new Result(process.exitValue(), stdout.get(), stderr.get());
  }

  /**
   * Streams the child's stdout under an idle watchdog, not a stopwatch: the child is destroyed only
   * once no byte has arrived for {@code defaultTimeout}, so a large transfer that keeps delivering
   * is never killed and one that stalls is, with the stream then failing by name. The buffered
   * {@link #exec} keeps its wall-clock deadline, the right bound for a command expected to finish.
   */
  @Override
  public InputStream stream(List<String> command) throws IOException {
    if (dryRun) {
      dryRunResult(command);
      return InputStream.nullInputStream();
    }
    var process =
        new ProcessBuilder(command).redirectError(ProcessBuilder.Redirect.INHERIT).start();
    process.getOutputStream().close();
    return new WatchedStream(process, command, defaultTimeout);
  }

  private static final class WatchedStream extends FilterInputStream {
    private final Process process;
    private final List<String> command;
    private final Duration idleTimeout;
    private final Thread watchdog;
    private volatile long lastByteNanos = System.nanoTime();
    private volatile long received;
    private volatile boolean stalled;

    WatchedStream(Process process, List<String> command, Duration idleTimeout) {
      super(process.getInputStream());
      this.process = process;
      this.command = command;
      this.idleTimeout = idleTimeout;
      this.watchdog = Thread.ofVirtual().start(this::watch);
    }

    private void watch() {
      try {
        while (true) {
          var remaining = idleTimeout.toNanos() - (System.nanoTime() - lastByteNanos);
          if (remaining <= 0) {
            stalled = true;
            process.destroyForcibly();
            return;
          }
          if (process.waitFor(remaining, TimeUnit.NANOSECONDS)) return;
        }
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    }

    private void progressed(int bytes) {
      received += bytes;
      lastByteNanos = System.nanoTime();
    }

    private void ended() throws IOException {
      try {
        var exit = process.waitFor();
        if (stalled)
          throw new IOException(
              "Content command produced no output for "
                  + idleTimeout.toSeconds()
                  + "s after "
                  + received
                  + " bytes: "
                  + String.join(" ", command));
        if (exit != 0)
          throw new IOException("Content command failed: " + String.join(" ", command));
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new IOException("Interrupted reading content", e);
      }
    }

    @Override
    public int read() throws IOException {
      var value = in.read();
      if (value == -1) ended();
      else progressed(1);
      return value;
    }

    @Override
    public int read(byte[] bytes, int offset, int length) throws IOException {
      var count = in.read(bytes, offset, length);
      if (count == -1) ended();
      else if (count > 0) progressed(count);
      return count;
    }

    @Override
    public void close() throws IOException {
      try {
        super.close();
      } finally {
        process.destroy();
        watchdog.interrupt();
      }
    }
  }

  @Override
  public boolean isDryRun() {
    return dryRun;
  }

  private static String readFully(InputStream in) {
    try (in) {
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException e) {
      return "";
    }
  }

  private static Result dryRunResult(List<String> command) {
    System.out.println("[dry-run] " + String.join(" ", command));
    return new Result(0, "", "");
  }
}
