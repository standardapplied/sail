/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.api;

import ai.singlr.sail.engine.AgentProcess;
import java.io.IOException;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;

/**
 * Stands in for the processes live members run as, beside {@link FakeContainer} for the shell: each
 * process started here has a scripted stdout and a captured stdin, and is found alive in the
 * container by its pid file until the test ends it. Nothing here sleeps: {@link Fake#settle}
 * returns once the reader has taken every line said so far and is waiting for the next.
 */
final class FakeAgentProcess implements AgentProcess {

  /** One {@link #start} call, as it was made. */
  static final class Started {

    private final String container;
    private final String workDir;
    private final Map<String, String> env;
    private final List<String> command;
    private final String pidFile;
    private final String logFile;

    private Started(
        String container,
        String workDir,
        Map<String, String> env,
        List<String> command,
        String pidFile,
        String logFile) {
      this.container = container;
      this.workDir = workDir;
      this.env = Map.copyOf(env);
      this.command = List.copyOf(command);
      this.pidFile = pidFile;
      this.logFile = logFile;
    }

    String container() {
      return container;
    }

    String workDir() {
      return workDir;
    }

    Map<String, String> env() {
      return env;
    }

    List<String> command() {
      return command;
    }

    String pidFile() {
      return pidFile;
    }

    String logFile() {
      return logFile;
    }
  }

  final List<Started> starts = new CopyOnWriteArrayList<>();
  final List<Fake> processes = new CopyOnWriteArrayList<>();
  private final FakeContainer container;
  private volatile IOException refusal;
  private volatile Consumer<Fake> onStart = fake -> {};

  FakeAgentProcess(FakeContainer container) {
    this.container = container;
  }

  /** Every start from now on fails with {@code refusal}, as a host with no incus would. */
  void refusing(IOException refusal) {
    this.refusal = refusal;
  }

  /** Scripts every process started from now on, before anything reads or writes it. */
  void onStart(Consumer<Fake> script) {
    this.onStart = script;
  }

  /** The process started last. */
  Fake latest() {
    return processes.getLast();
  }

  @Override
  public Handle start(
      String containerName,
      String workDir,
      Map<String, String> env,
      List<String> command,
      String pidFile,
      String logFile)
      throws IOException {
    if (refusal != null) {
      throw refusal;
    }
    starts.add(new Started(containerName, workDir, Map.copyOf(env), command, pidFile, logFile));
    var fake = new Fake(env.get("SAIL_RUN_ID"));
    container.started(fake.runId);
    processes.add(fake);
    onStart.accept(fake);
    return fake;
  }

  /** One fake process: what it was written, what it was signalled with, and what it says. */
  final class Fake implements Handle, LineWriter, LineReader {

    private static final long PATIENCE_SECONDS = 20;

    final String runId;
    final List<String> written = new CopyOnWriteArrayList<>();
    final List<Signal> signals = new CopyOnWriteArrayList<>();
    private final ReentrantLock lock = new ReentrantLock();
    private final Condition moved = lock.newCondition();
    private final Deque<String> lines = new ArrayDeque<>();
    private boolean waiting;
    private boolean ended;
    private boolean endSeen;
    private IOException broken;
    private Integer exitCode;
    private volatile boolean stdinClosed;
    private volatile boolean stdinBroken;
    private volatile boolean waitInterrupted;
    private volatile Runnable onClose = () -> {};
    private volatile Consumer<Signal> onSignal = signal -> {};
    private volatile Consumer<String> onWrite = line -> {};

    private Fake(String runId) {
      this.runId = runId;
    }

    /** The process writes {@code said} to its stdout, a line each. */
    Fake says(String... said) {
      lock.lock();
      try {
        lines.addAll(List.of(said));
        moved.signalAll();
      } finally {
        lock.unlock();
      }
      return this;
    }

    /** The process exits with {@code code}, having written {@code log} to its stderr. */
    void exits(int code, String log) {
      container.exited(runId, log, code);
      lock.lock();
      try {
        exitCode = code;
        ended = true;
        moved.signalAll();
      } finally {
        lock.unlock();
      }
    }

    void exits(int code) {
      exits(code, "");
    }

    /** The process exits cleanly unless it already ended. */
    void exitsIfRunning() {
      lock.lock();
      try {
        if (ended || broken != null) {
          return;
        }
      } finally {
        lock.unlock();
      }
      exits(0);
    }

    /** The process's stdout ends with no exit code to be had: its client lost it. */
    void vanishes() {
      container.exited(runId, "", 0);
      lock.lock();
      try {
        ended = true;
        moved.signalAll();
      } finally {
        lock.unlock();
      }
    }

    /** The next read of the process's stdout fails with {@code failure}. */
    void breaks(IOException failure) {
      container.exited(runId, "", 1);
      lock.lock();
      try {
        broken = failure;
        exitCode = 1;
        moved.signalAll();
      } finally {
        lock.unlock();
      }
    }

    /** A write to the process's stdin from now on fails, and the process is found dead. */
    void breaksStdin() {
      stdinBroken = true;
    }

    /** Asking for the exit code from now on is interrupted. */
    void interruptsWait() {
      waitInterrupted = true;
    }

    /** What the process does when its stdin closes: nothing, unless scripted. */
    Fake onClose(Runnable reaction) {
      this.onClose = reaction;
      return this;
    }

    /** What the process does with a signal: ignores it, unless scripted. */
    Fake onSignal(Consumer<Signal> reaction) {
      this.onSignal = reaction;
      return this;
    }

    /** What the process does with a line written to it: nothing, unless scripted. */
    Fake onWrite(Consumer<String> reaction) {
      this.onWrite = reaction;
      return this;
    }

    boolean stdinClosed() {
      return stdinClosed;
    }

    /** Blocks until the reader has taken everything said so far, or seen the process end. */
    void settle() {
      lock.lock();
      try {
        var remaining = TimeUnit.SECONDS.toNanos(PATIENCE_SECONDS);
        while (!(lines.isEmpty() && (waiting || endSeen))) {
          if (remaining <= 0) {
            throw new AssertionError("nothing read the fake process of run " + runId);
          }
          remaining = moved.awaitNanos(remaining);
        }
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new AssertionError("interrupted while the fake process settled", e);
      } finally {
        lock.unlock();
      }
    }

    @Override
    public LineWriter stdin() {
      return this;
    }

    @Override
    public LineReader stdout() {
      return this;
    }

    @Override
    public void write(String line) throws IOException {
      if (stdinBroken) {
        exits(1);
        throw new IOException("Broken pipe");
      }
      written.add(line);
      onWrite.accept(line);
    }

    @Override
    public void close() {
      if (stdinClosed) {
        return;
      }
      stdinClosed = true;
      onClose.run();
    }

    @Override
    public String read() throws IOException {
      lock.lock();
      try {
        while (lines.isEmpty()) {
          if (broken != null) {
            endSeen = true;
            moved.signalAll();
            throw broken;
          }
          if (ended) {
            endSeen = true;
            moved.signalAll();
            return null;
          }
          waiting = true;
          moved.signalAll();
          moved.awaitUninterruptibly();
        }
        waiting = false;
        return lines.pop();
      } finally {
        lock.unlock();
      }
    }

    @Override
    public OptionalInt pid() {
      return OptionalInt.of(Integer.parseInt(container.pidOf(runId)));
    }

    @Override
    public void signal(Signal signal) {
      signals.add(signal);
      onSignal.accept(signal);
    }

    @Override
    public OptionalInt waitFor(Duration timeout) throws InterruptedException {
      if (waitInterrupted) {
        throw new InterruptedException("interrupted while waiting for run " + runId);
      }
      lock.lock();
      try {
        return exitCode == null ? OptionalInt.empty() : OptionalInt.of(exitCode);
      } finally {
        lock.unlock();
      }
    }
  }
}
