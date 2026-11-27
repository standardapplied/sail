/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.engine;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.UnaryOperator;

/**
 * {@link AgentProcess} over {@code incus exec}: one host-side client process whose pipes are the
 * container process's. The wrapper first makes the pid file's folder, records its own start beside
 * it ({@link AgentUnit#startPath}) and writes its own pid to the run's pid file, then {@code exec}s
 * a login shell that reads the run's {@code launch.env} into its environment and removes it ({@link
 * LaunchEnv#read}) and {@code exec}s into the command; an {@code exec} keeps the pid and the start,
 * so the pid file names the agent itself, the run probes alive exactly as a unit's does, and a halt
 * can tell the agent from a later process given its pid; the agent's stderr is appended to the
 * run's log, which leaves stdout to carry nothing but its lines. A Java {@code Process} can only
 * signal the host-side client, so a signal is a {@code kill} run in the container against the pid
 * the file names, as an argument, and only while that client runs: once it has exited, the pid file
 * may name the process started after it.
 */
public final class ContainerAgentProcess implements AgentProcess {

  /**
   * {@code $1} the pid file, {@code $2} the log file beside it, {@code $3} the start record beside
   * it, the rest the login shell that execs the command: the folder that holds them is made first,
   * for a process that has no run's folder made for it (a chat's), and the start is recorded before
   * the pid, so the start beside a pid file is never an earlier process's; a step that fails starts
   * nothing, so no agent runs whose start is not recorded. A plain {@code sh} does this before any
   * login profile runs, so a halt in the profile's while finds the process it is about to become.
   */
  private static final String RECORD =
      "mkdir -p -- \"${1%/*}\" && cat /proc/$$/stat > \"$3\" && printf '%s\\n' \"$$\" > \"$1\""
          + " && shift 3 && exec \"$@\"";

  private static final String WRAP = "; exec \"${@:4}\" 2>> \"$2\"";

  private final ShellExec shell;
  private final UnaryOperator<List<String>> transport;

  public ContainerAgentProcess(ShellExec shell) {
    this(shell, UnaryOperator.identity());
  }

  /** {@code transport} rewrites the host-side argv, so a test runs the wrapper where it is. */
  ContainerAgentProcess(ShellExec shell, UnaryOperator<List<String>> transport) {
    this.shell = Objects.requireNonNull(shell, "shell");
    this.transport = Objects.requireNonNull(transport, "transport");
  }

  /** The host-side argv that starts {@code command} under the wrapper. */
  static List<String> startCommand(
      String container,
      String workDir,
      Map<String, String> env,
      List<String> command,
      String pidFile,
      String logFile,
      String launchEnvFile) {
    var paths = List.of(pidFile, logFile, AgentUnit.startPath(pidFile));
    var args = new ArrayList<>(List.of("sh", "-c", RECORD, "sh"));
    args.addAll(paths);
    args.addAll(List.of("bash", "-lc", LaunchEnv.read(launchEnvFile) + WRAP, "bash"));
    args.addAll(paths);
    args.addAll(command);
    return ContainerExec.asDevUser(container, workDir, env, args);
  }

  @Override
  public Handle start(
      String container,
      String workDir,
      Map<String, String> env,
      List<String> command,
      String pidFile,
      String logFile,
      String launchEnvFile)
      throws IOException {
    var argv =
        transport.apply(
            startCommand(container, workDir, env, command, pidFile, logFile, launchEnvFile));
    var process = new ProcessBuilder(argv).redirectError(ProcessBuilder.Redirect.INHERIT).start();
    return new Piped(container, pidFile, process);
  }

  private final class Piped implements Handle, LineWriter, LineReader {

    private final String container;
    private final String pidFile;
    private final Process process;
    private final BufferedWriter in;
    private final BufferedReader out;
    private volatile boolean ended;

    private Piped(String container, String pidFile, Process process) {
      this.container = container;
      this.pidFile = pidFile;
      this.process = process;
      this.in =
          new BufferedWriter(
              new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8));
      this.out =
          new BufferedReader(
              new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));
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
    public synchronized void write(String line) throws IOException {
      in.write(line);
      in.write('\n');
      in.flush();
    }

    @Override
    public synchronized void close() {
      try {
        in.close();
      } catch (IOException processAlreadyGone) {
        return;
      }
    }

    /**
     * The next line of stdout, or {@code null} once it has ended; at its end, or when it cannot be
     * read, the handle closes it and stdin, for a process done speaking takes no more input, so its
     * pipes go with the reading rather than whenever the JDK's reaper gets to them.
     */
    @Override
    public String read() throws IOException {
      if (ended) {
        return null;
      }
      String line;
      try {
        line = out.readLine();
      } catch (IOException broken) {
        endOutput();
        throw broken;
      }
      if (line == null) {
        endOutput();
      }
      return line;
    }

    private void endOutput() {
      ended = true;
      try {
        out.close();
      } catch (IOException alreadyGone) {
        var unused = alreadyGone;
      } finally {
        close();
      }
    }

    @Override
    public OptionalInt pid() {
      return ask(List.of("cat", pidFile))
          .filter(ShellExec.Result::ok)
          .map(read -> read.stdout().strip())
          .filter(text -> text.matches("[1-9][0-9]{0,8}"))
          .map(text -> OptionalInt.of(Integer.parseInt(text)))
          .orElse(OptionalInt.empty());
    }

    @Override
    public void signal(Signal signal) {
      if (!process.isAlive()) {
        return;
      }
      pid().ifPresent(pid -> ask(List.of("kill", "-" + signal.name(), String.valueOf(pid))));
    }

    /** What the container answered, or empty when it ran nothing. */
    private Optional<ShellExec.Result> ask(List<String> args) {
      try {
        return Optional.of(shell.exec(ContainerExec.asDevUser(container, args)));
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        return Optional.empty();
      } catch (IOException | TimeoutException unanswered) {
        return Optional.empty();
      }
    }

    @Override
    public OptionalInt waitFor(Duration timeout) throws InterruptedException {
      return process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)
          ? OptionalInt.of(process.exitValue())
          : OptionalInt.empty();
    }
  }
}
