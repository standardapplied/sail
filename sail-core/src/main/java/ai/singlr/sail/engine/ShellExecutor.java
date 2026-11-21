/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.engine;

import java.io.BufferedReader;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Thin wrapper around {@link ProcessBuilder} that implements {@link ShellExec}. Supports dry-run
 * mode (prints commands instead of executing) and configurable timeouts.
 */
public final class ShellExecutor implements ShellExec {

  private static final Duration PROBE_TIMEOUT = Duration.ofSeconds(3);
  private static final AtomicInteger LISTING_SCOPES = new AtomicInteger();

  /** How long a timed-out command's writer may take to end once the command is killed. */
  private static final Duration WRITER_GRACE = Duration.ofSeconds(5);

  private final boolean dryRun;
  private final Duration defaultTimeout;

  public ShellExecutor(boolean dryRun, Duration defaultTimeout) {
    this.dryRun = dryRun;
    this.defaultTimeout = defaultTimeout;
  }

  public ShellExecutor(boolean dryRun) {
    this(dryRun, Duration.ofSeconds(120));
  }

  /** An open {@link #listingTimedOutProcesses} scope, closed without throwing. */
  public interface Listing extends AutoCloseable {
    @Override
    void close();
  }

  /**
   * Until the scope it opens is closed, a command that times out first lists its processes ({@link
   * CommandTimedOut#processes}), which costs a {@code ps} of up to 3 s before the kill: init opens
   * one around each of its steps, the only reader of that listing, so no other caller pays for it.
   */
  public static Listing listingTimedOutProcesses() {
    LISTING_SCOPES.incrementAndGet();
    return LISTING_SCOPES::decrementAndGet;
  }

  /** A dry run's probes execute: the same shell that runs every command, with its timeout. */
  @Override
  public ShellExec observer() {
    return dryRun ? new ShellExecutor(false, defaultTimeout) : this;
  }

  @Override
  public Result exec(List<String> command)
      throws IOException, InterruptedException, TimeoutException {
    return exec(command, null, defaultTimeout);
  }

  @Override
  public Result exec(List<String> command, Path workDir, Duration timeout)
      throws IOException, InterruptedException, TimeoutException {
    return exec(command, workDir, "", timeout);
  }

  @Override
  public Result execWithInput(List<String> command, String input, Duration timeout)
      throws IOException, InterruptedException, TimeoutException {
    return exec(command, null, input, timeout);
  }

  private Result exec(List<String> command, Path workDir, String input, Duration timeout)
      throws IOException, InterruptedException, TimeoutException {
    return exec(command, workDir, input, timeout, true);
  }

  private Result exec(
      List<String> command, Path workDir, String input, Duration timeout, boolean listOnTimeout)
      throws IOException, InterruptedException, TimeoutException {
    if (dryRun) {
      return dryRunResult(command);
    }

    var pb = new ProcessBuilder(command);
    pb.redirectErrorStream(false);
    if (workDir != null) {
      pb.directory(workDir.toFile());
    }

    var deadline = System.nanoTime() + timeout.toNanos();
    var process = pb.start();
    var stdout = new AtomicReference<String>("");
    var stderr = new AtomicReference<String>("");
    var stdoutThread =
        Thread.ofVirtual().start(() -> stdout.set(readFully(process.getInputStream())));
    var stderrThread =
        Thread.ofVirtual().start(() -> stderr.set(readFully(process.getErrorStream())));
    var writing = new FutureTask<>(() -> hand(process, input));
    Thread.ofVirtual().start(writing);

    boolean finished;
    try {
      finished = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
    } catch (InterruptedException interrupted) {
      destroyWithDescendants(process);
      throw interrupted;
    }
    if (!finished) {
      var processes = listOnTimeout && LISTING_SCOPES.get() > 0 ? processesOf(process) : "";
      destroyWithDescendants(process);
      written(writing, System.nanoTime() + WRITER_GRACE.toNanos());
      throw timedOut(command, timeout, processes);
    }

    var written = written(writing, deadline);
    if (!stdoutThread.join(left(deadline))
        || !stderrThread.join(left(deadline))
        || !written.ended()) {
      destroyWithDescendants(process);
      throw outlasted(command, timeout);
    }
    var status = process.exitValue();
    if (status == 0 && written.notTaken() != null) {
      throw new IOException(
          String.join(" ", command)
              + " exited 0 without taking all of its input ("
              + written.notTaken().getMessage()
              + ")");
    }
    return new Result(status, stdout.get(), stderr.get());
  }

  /**
   * The timeout of a command answered past its bound after its exit: one whose own process ended
   * while one it left behind still holds its output. Its readers are left to end with that process,
   * each a virtual thread. Closing the pipe does not wake a reader already blocked on it (measured
   * on JDK 26: 17 of 20 such readers were still blocked 500 ms after the close), so the pipes are
   * not closed here; the JDK releases their descriptors once the command's own process has ended
   * (20 of 20 calls held none after them).
   */
  private static CommandTimedOut outlasted(List<String> command, Duration timeout) {
    return timedOut(command, timeout, "");
  }

  private static CommandTimedOut timedOut(
      List<String> command, Duration timeout, String processes) {
    return new CommandTimedOut(
        "Command timed out after " + timeout.toSeconds() + "s: " + String.join(" ", command),
        processes);
  }

  /** What is left until {@code deadline}, never less than nothing. */
  private static Duration left(long deadline) {
    return Duration.ofNanos(Math.max(0, deadline - System.nanoTime()));
  }

  /** How a command's input was handed over: whether the write ended, and why it failed if so. */
  private record Written(boolean ended, IOException notTaken) {}

  /**
   * The write's own answer, read from its end, so whether the input was taken never depends on
   * which thread got there first; not ended when the write was still under way at {@code deadline}.
   */
  private static Written written(FutureTask<IOException> writing, long deadline)
      throws InterruptedException {
    try {
      return new Written(true, writing.get(left(deadline).toNanos(), TimeUnit.NANOSECONDS));
    } catch (TimeoutException stillWriting) {
      return new Written(false, null);
    } catch (ExecutionException failed) {
      return new Written(true, new IOException(failed.getCause()));
    }
  }

  /**
   * Gives {@code process} its {@code input} and closes it, on the writer's own thread while the
   * readers drain its output, so neither a command that writes back as it reads nor one that never
   * reads can hold the caller past its timeout. Answers why the write did not finish, the command's
   * input closed before it took all of it, or null: a command that then fails says why itself, and
   * one that exits 0 had not taken what it was given. Never the input itself.
   */
  private static IOException hand(Process process, String input) {
    try (var stdin = process.getOutputStream()) {
      stdin.write(input.getBytes(StandardCharsets.UTF_8));
      return null;
    } catch (IOException notTaken) {
      return notTaken;
    }
  }

  /**
   * What {@code process} and every process it started are doing, as procps' {@code ps} lists them:
   * state, the kernel function each waits in, and its arguments; or what {@code ps} said instead.
   * The listing is bounded as a probe is, so a step that hung cannot hang its own diagnosis.
   */
  private String processesOf(Process process) throws InterruptedException {
    var pids =
        Stream.concat(Stream.of(process.toHandle()), process.descendants())
            .map(handle -> String.valueOf(handle.pid()))
            .collect(Collectors.joining(","));
    try {
      var listed =
          exec(
              List.of("ps", "-ww", "-o", "pid,ppid,stat,wchan:32,etime,args", "-p", pids),
              null,
              "",
              PROBE_TIMEOUT,
              false);
      return listed.stdout() + listed.stderr();
    } catch (IOException | TimeoutException e) {
      return "ps could not list them: " + e.getMessage();
    }
  }

  /**
   * Destroys {@code process} and every process it started, its descendants first, while they are
   * still its descendants: once it is gone they belong to init and can no longer be found, and they
   * would run on, holding its pipes.
   */
  private static void destroyWithDescendants(Process process) {
    process.descendants().forEach(ProcessHandle::destroyForcibly);
    process.destroyForcibly();
  }

  @Override
  public Result run(List<String> command, Consumer<String> lines)
      throws IOException, InterruptedException {
    if (dryRun) {
      return dryRunResult(command);
    }
    var process = new ProcessBuilder(command).start();
    process.getOutputStream().close();
    var stderr = new AtomicReference<String>("");
    var stderrThread =
        Thread.ofVirtual().start(() -> stderr.set(readFully(process.getErrorStream())));
    var stdout = new StringBuilder();
    try (var reader =
        new BufferedReader(
            new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
      for (var line = reader.readLine(); line != null; line = reader.readLine()) {
        stdout.append(line).append('\n');
        lines.accept(line);
      }
    }
    var exit = process.waitFor();
    stderrThread.join();
    return new Result(exit, stdout.toString(), stderr.get());
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

  /**
   * Whether a plain GET of {@code url}, sent without credentials and following no redirect, asks
   * for them: a {@code 401}, or a {@code 403} that carries an authentication challenge. A {@code
   * 403} without one is a refusal no token would lift, and a URL that cannot be asked within 3 s to
   * connect and 3 s more for the answer asks for nothing.
   */
  @Override
  public boolean asksForCredentials(String url) {
    try (var client =
        HttpClient.newBuilder()
            .connectTimeout(PROBE_TIMEOUT)
            .followRedirects(HttpClient.Redirect.NEVER)
            .build()) {
      var answer =
          client.send(
              HttpRequest.newBuilder(URI.create(url)).timeout(PROBE_TIMEOUT).GET().build(),
              HttpResponse.BodyHandlers.discarding());
      return answer.statusCode() == 401
          || answer.statusCode() == 403
              && answer.headers().firstValue("WWW-Authenticate").isPresent();
    } catch (IOException | IllegalArgumentException e) {
      return false;
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return false;
    }
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
