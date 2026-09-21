/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.engine;

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

  @Override
  public InputStream stream(List<String> command) throws IOException {
    if (dryRun) {
      dryRunResult(command);
      return InputStream.nullInputStream();
    }
    var process =
        new ProcessBuilder(command).redirectError(ProcessBuilder.Redirect.INHERIT).start();
    process.getOutputStream().close();
    var watchdog =
        Thread.ofVirtual()
            .start(
                () -> {
                  try {
                    if (!process.waitFor(defaultTimeout.toMillis(), TimeUnit.MILLISECONDS))
                      process.destroyForcibly();
                  } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                  }
                });
    return new java.io.FilterInputStream(process.getInputStream()) {
      private void finished(int read) throws IOException {
        if (read != -1) return;
        try {
          if (process.waitFor() != 0)
            throw new IOException("Content command failed: " + String.join(" ", command));
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          throw new IOException("Interrupted reading content", e);
        }
      }

      @Override
      public int read() throws IOException {
        var value = in.read();
        finished(value);
        return value;
      }

      @Override
      public int read(byte[] bytes, int offset, int length) throws IOException {
        var count = in.read(bytes, offset, length);
        finished(count);
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
    };
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
