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
 * container process's. The wrapper writes its own pid to the run's pid file and {@code exec}s into
 * the command, so the pid file names the agent itself and the run probes alive exactly as a unit's
 * does; the agent's stderr is appended to the run's log, which leaves stdout to carry nothing but
 * its lines. A Java {@code Process} can only signal the host-side client, so a signal is a {@code
 * kill} run in the container against the pid the file names, as an argument.
 */
public final class ContainerAgentProcess implements AgentProcess {

  /** {@code $1} the pid file, {@code $2} the log file, the rest the command. */
  static final String WRAP = "printf '%s\\n' \"$$\" > \"$1\"; exec \"${@:3}\" 2>> \"$2\"";

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
      String logFile) {
    var args = new ArrayList<>(List.of("bash", "-lc", WRAP, "bash", pidFile, logFile));
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
      String logFile)
      throws IOException {
    var argv = transport.apply(startCommand(container, workDir, env, command, pidFile, logFile));
    var process = new ProcessBuilder(argv).redirectError(ProcessBuilder.Redirect.INHERIT).start();
    return new Piped(container, pidFile, process);
  }

  private final class Piped implements Handle, LineWriter, LineReader {

    private final String container;
    private final String pidFile;
    private final Process process;
    private final BufferedWriter in;
    private final BufferedReader out;

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

    @Override
    public String read() throws IOException {
      return out.readLine();
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
