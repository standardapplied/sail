/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.api;

import ai.singlr.sail.common.Ids;
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
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * Stands in for the processes live members run as, beside {@link FakeContainer} for the shell: each
 * process started here has a scripted stdout and a captured stdin, and is found alive in the
 * container by its pid file until the test ends it; one that names no run (a chat's) is known by an
 * id of its own. Nothing here sleeps: {@link Fake#settle} returns once the reader has taken every
 * line said so far and is waiting for the next.
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
    private final Map<String, String> secrets;

    private Started(
        String container,
        String workDir,
        Map<String, String> env,
        List<String> command,
        String pidFile,
        String logFile,
        Map<String, String> secrets) {
      this.container = container;
      this.workDir = workDir;
      this.env = Map.copyOf(env);
      this.command = List.copyOf(command);
      this.pidFile = pidFile;
      this.logFile = logFile;
      this.secrets = secrets;
    }

    /** What its wrapper read from its launch.env, which is gone from the container since. */
    Map<String, String> secrets() {
      return secrets;
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
  private volatile boolean slowToName;
  private volatile Consumer<Fake> onStart = fake -> {};

  FakeAgentProcess(FakeContainer container) {
    this.container = container;
  }

  /** The container its processes run in, which finds each by its pid file. */
  FakeContainer container() {
    return container;
  }

  /** Every start from now on fails with {@code refusal}, as a host with no incus would. */
  void refusing(IOException refusal) {
    this.refusal = refusal;
  }

  /**
   * Whether a process started from now on has written no pid file yet, as one whose wrapper has not
   * run: it is in the container only once {@link Fake#writesPid} says so.
   */
  void slowToName(boolean slow) {
    this.slowToName = slow;
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
      String logFile,
      String launchEnvFile)
      throws IOException {
    if (refusal != null) {
      throw refusal;
    }
    starts.add(
        new Started(
            containerName,
            workDir,
            Map.copyOf(env),
            command,
            pidFile,
            logFile,
            container.takeLaunchEnv(launchEnvFile)));
    var fake = new Fake(env.getOrDefault("SAIL_RUN_ID", Ids.newId().toString()), pidFile);
    if (!slowToName) {
      container.started(fake.runId, pidFile);
    }
    processes.add(fake);
    onStart.accept(fake);
    return fake;
  }

  /** One fake process: what it was written, what it was signalled with, and what it says. */
  final class Fake implements Handle, LineWriter, LineReader {

    private static final long PATIENCE_SECONDS = 20;

    final String runId;
    private final String pidFile;
    final List<String> written = new CopyOnWriteArrayList<>();
    final List<Signal> signals = new CopyOnWriteArrayList<>();
    private final ReentrantLock lock = new ReentrantLock();
    private final Condition moved = lock.newCondition();
    private final Deque<String> lines = new ArrayDeque<>();
    private boolean waiting;
    private boolean ended;
    private boolean endSeen;
    private IOException broken;
    private RuntimeException readerFailure;
    private Integer exitCode;
    private boolean writeBlocked;
    private volatile boolean stdinClosed;
    private volatile boolean stdinBroken;
    private volatile boolean deaf;
    private volatile boolean waitInterrupted;
    private volatile boolean outlivedByItsClient;
    private boolean endHeld;
    private boolean heldReader;
    private boolean writeRefused;
    private Thread reader;
    private volatile Runnable onPidAsked = () -> {};
    private volatile Runnable onClose = () -> {};
    private volatile Consumer<Signal> onSignal = signal -> {};
    private volatile Consumer<String> onWrite = line -> {};

    private Fake(String runId, String pidFile) {
      this.runId = runId;
      this.pidFile = pidFile;
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

    /**
     * The reader of the process's stdout fails with {@code failure}, as a log that could not be
     * written fails it, while the process lives on.
     */
    void failsItsReader(RuntimeException failure) {
      lock.lock();
      try {
        readerFailure = failure;
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

    /**
     * The process stops reading its stdin: a write from now on blocks, as on a full pipe, until the
     * process ends, and then fails; closing its stdin waits behind such a write.
     */
    void stopsReading() {
      deaf = true;
    }

    /** Blocks until a write to the process's stdin is blocked on it. */
    void awaitBlockedWrite() {
      await(() -> writeBlocked, "nothing was written to the fake process of run " + runId);
    }

    /** Blocks until a read of the process's stdout has met its end or its failure. */
    void awaitEndSeen() {
      await(() -> endSeen, "nothing read the fake process of run " + runId + " to its end");
    }

    /** Blocks until the process's stdin has been closed. */
    void awaitStdinClosed() {
      await(() -> stdinClosed, "nothing closed the stdin of the fake process of run " + runId);
    }

    private void await(BooleanSupplier reached, String otherwise) {
      lock.lock();
      try {
        var remaining = TimeUnit.SECONDS.toNanos(PATIENCE_SECONDS);
        while (!reached.getAsBoolean()) {
          if (remaining <= 0) {
            throw new AssertionError(otherwise);
          }
          remaining = moved.awaitNanos(remaining);
        }
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new AssertionError("interrupted: " + otherwise, e);
      } finally {
        lock.unlock();
      }
    }

    /** The process's wrapper writes its pid file: the container finds the process from now on. */
    void writesPid() {
      container.started(runId, pidFile);
    }

    /** What happens as the process is asked for its pid, before it answers. */
    Fake onPidAsked(Runnable reaction) {
      this.onPidAsked = reaction;
      return this;
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

    /**
     * Blocks until the reader has taken everything said so far and waits for more, or, once the
     * process ended, until its reader is done with it: what follows the end (the run finished, the
     * member gone from its room) has happened on return.
     */
    void settle() {
      await(
          () ->
              lines.isEmpty()
                  && (ended || broken != null || readerFailure != null ? endSeen : waiting),
          "nothing read the fake process of run " + runId);
      Thread finishing;
      lock.lock();
      try {
        finishing = endSeen ? reader : null;
      } finally {
        lock.unlock();
      }
      if (finishing != null && finishing != Thread.currentThread()) {
        try {
          if (!finishing.join(Duration.ofSeconds(PATIENCE_SECONDS))) {
            throw new AssertionError(
                "the reader of the fake process of run " + runId + " never finished");
          }
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          throw new AssertionError("interrupted while the reader of run " + runId + " finished", e);
        }
      }
    }

    /**
     * What wraps the process outlives it: a line written after the process ended is taken, as the
     * client of a container exec takes it until it notices its process gone, and never read.
     */
    Fake outlivedByItsClient() {
      outlivedByItsClient = true;
      return this;
    }

    /** Once the process ends, its reader is not told so until {@link #releasesItsEnd}. */
    Fake holdsItsEnd() {
      lock.lock();
      try {
        endHeld = true;
      } finally {
        lock.unlock();
      }
      return this;
    }

    /** The reader held at the end of the process is told it ended. */
    void releasesItsEnd() {
      lock.lock();
      try {
        endHeld = false;
        moved.signalAll();
      } finally {
        lock.unlock();
      }
    }

    /** Blocks until a write was refused because the process had ended. */
    void awaitRefusedWrite() {
      await(() -> writeRefused, "nothing was written to the ended fake process of run " + runId);
    }

    /** Blocks until the reader has been held at the end of the process. */
    void awaitHeldEnd() {
      await(() -> heldReader, "nothing read the end of the fake process of run " + runId);
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
      if (endedUnwrapped()) {
        throw new IOException("Broken pipe");
      }
      if (stdinBroken) {
        exits(1);
        throw new IOException("Broken pipe");
      }
      if (deaf) {
        blockUntilEnded();
        throw new IOException("Broken pipe");
      }
      written.add(line);
      onWrite.accept(line);
    }

    private boolean endedUnwrapped() {
      lock.lock();
      try {
        writeRefused = ended && !outlivedByItsClient;
        moved.signalAll();
        return writeRefused;
      } finally {
        lock.unlock();
      }
    }

    private void blockUntilEnded() {
      lock.lock();
      try {
        writeBlocked = true;
        moved.signalAll();
        while (!ended) {
          moved.awaitUninterruptibly();
        }
        writeBlocked = false;
        moved.signalAll();
      } finally {
        lock.unlock();
      }
    }

    @Override
    public void close() {
      lock.lock();
      try {
        while (writeBlocked) {
          moved.awaitUninterruptibly();
        }
        if (stdinClosed) {
          return;
        }
        stdinClosed = true;
        moved.signalAll();
      } finally {
        lock.unlock();
      }
      onClose.run();
    }

    @Override
    public String read() throws IOException {
      lock.lock();
      try {
        while (lines.isEmpty()) {
          if (readerFailure != null) {
            reader = Thread.currentThread();
            endSeen = true;
            moved.signalAll();
            throw readerFailure;
          }
          if (broken != null) {
            reader = Thread.currentThread();
            endSeen = true;
            moved.signalAll();
            throw broken;
          }
          if (ended) {
            reader = Thread.currentThread();
            heldReader = endHeld;
            moved.signalAll();
            while (endHeld) {
              moved.awaitUninterruptibly();
            }
            heldReader = false;
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
      onPidAsked.run();
      return container.launched().contains(runId)
          ? OptionalInt.of(Integer.parseInt(container.pidOf(runId)))
          : OptionalInt.empty();
    }

    @Override
    public void signal(Signal signal) {
      if (!container.launched().contains(runId)) {
        return;
      }
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
