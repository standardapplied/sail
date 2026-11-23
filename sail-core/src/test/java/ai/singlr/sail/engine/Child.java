/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.engine;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

/**
 * A test's child process, every stream of which the test closes itself: a stream left open is
 * closed by the JDK's reaper only after it has woken those waiting for the child, so a class that
 * ends right after {@code waitFor} could still hold it.
 */
public final class Child {

  /** How long {@link #run(ProcessBuilder)} waits for a child to end. */
  public static final Duration PATIENCE = Duration.ofSeconds(60);

  /** A child's stdin with nothing to read: no pipe for anyone to close. */
  public static final ProcessBuilder.Redirect NO_INPUT = ShellExecutor.NO_INPUT;

  /** How a child ended: its exit status and what it wrote, stderr empty when merged. */
  public record Ended(int exit, String stdout, String stderr) {}

  private Child() {}

  /** {@code builder}'s child with no input, as {@link #run(ProcessBuilder, String, Duration)}. */
  public static Ended run(ProcessBuilder builder) throws IOException, InterruptedException {
    return run(builder, "", PATIENCE);
  }

  /**
   * Starts {@code builder}'s child, gives it {@code input} and closes its stdin, reads its stdout
   * and stderr to their ends on threads of their own, closing each, and waits for it within {@code
   * patience}; a child still running then is ended and fails the test. A child that ends without
   * taking its input says so itself, by its status or its words.
   */
  public static Ended run(ProcessBuilder builder, String input, Duration patience)
      throws IOException, InterruptedException {
    var process = builder.start();
    var stdout = reading(process.getInputStream());
    var stderr = reading(process.getErrorStream());
    try (var stdin = process.getOutputStream()) {
      stdin.write(input.getBytes(StandardCharsets.UTF_8));
    } catch (IOException notTaken) {
      var unused = notTaken;
    }
    var ended = process.waitFor(patience.toMillis(), TimeUnit.MILLISECONDS);
    if (!ended) {
      process.destroyForcibly();
      process.waitFor();
    }
    var said = new Ended(ended ? process.exitValue() : -1, read(stdout), read(stderr));
    if (!ended) {
      throw new AssertionError(
          String.join(" ", builder.command())
              + " was still running after "
              + patience
              + ":\n"
              + said.stdout()
              + said.stderr());
    }
    return said;
  }

  /**
   * Reads {@code process}'s stdout line by line into {@code lines} on a thread of its own, which
   * closes it at its end, and its stderr with it: merged into stdout, it is still a pipe the JDK
   * opened and holds for the child's life. The caller ends the child and joins the returned thread.
   */
  public static Thread drain(Process process, BlockingQueue<String> lines) {
    return Thread.ofVirtual()
        .start(
            () -> {
              try (var merged = process.getErrorStream();
                  var reader =
                      new BufferedReader(
                          new InputStreamReader(
                              process.getInputStream(), StandardCharsets.UTF_8))) {
                for (var line = reader.readLine(); line != null; line = reader.readLine()) {
                  lines.add(line);
                }
              } catch (IOException gone) {
                var unused = gone;
              }
            });
  }

  /**
   * Ends {@code process} and closes the three pipes the test holds to it, whatever was read of
   * them: a test that leaves one to the JDK's reaper may end its class before the reaper closes it.
   */
  public static void end(Process process) throws IOException, InterruptedException {
    process.destroyForcibly().waitFor();
    process.getOutputStream().close();
    process.getInputStream().close();
    process.getErrorStream().close();
  }

  /** {@code stream} read to its end and closed, on a virtual thread of its own. */
  private static FutureTask<String> reading(InputStream stream) {
    var read =
        new FutureTask<>(
            () -> {
              try (stream) {
                return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
              }
            });
    Thread.ofVirtual().start(read);
    return read;
  }

  private static String read(FutureTask<String> output) throws InterruptedException {
    try {
      return output.get();
    } catch (ExecutionException e) {
      return "output unreadable: " + e.getCause().getMessage();
    }
  }
}
