/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.engine;

import ai.singlr.sail.common.DateTimeUtils;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

/**
 * The steps of an integration test's setup, each said as it starts and ends, with the time, and
 * each held to a stated bound: a command run on the step's thread is given what is left of the
 * bound as its timeout, so a step that outlasts its bound fails naming the command it was waiting
 * for. Without it a setup that crawls is said by nothing until the job's limit cancels the job: on
 * #529's run a runner whose disk copied some 60 times slower than usual held {@code WorkspaceIT}'s
 * first setup silent for 12.75 minutes. A step inside a step runs within the outer one's bound.
 */
final class SetupSteps implements ShellExec {

  /** What a command asks for when it names no timeout: {@link ShellExecutor}'s default. */
  static final Duration COMMAND_DEFAULT = Duration.ofSeconds(120);

  private final ShellExec incus;
  private final PrintStream out;
  private final LongSupplier nanos;
  private final ThreadLocal<Step> current = new ThreadLocal<>();

  SetupSteps(ShellExec incus, PrintStream out, LongSupplier nanos) {
    this.incus = incus;
    this.out = out;
    this.nanos = nanos;
  }

  /** A step's work: what it answers, or what it throws. */
  @FunctionalInterface
  interface Work<T> {
    T run() throws Exception;
  }

  private record Step(String name, Duration bound, long started) {}

  /** Runs {@code work} as the setup step {@code name}, held to {@code bound}. */
  <T> T step(String name, Duration bound, Work<T> work) throws Exception {
    if (current.get() != null) {
      return work.run();
    }
    var step = new Step(name, bound, nanos.getAsLong());
    say("start " + name + " (bound " + bound.toSeconds() + " s)");
    current.set(step);
    try {
      var answer = work.run();
      say("done " + name + " in " + seconds(elapsed(step)));
      return answer;
    } catch (Exception | AssertionError failure) {
      say("failed " + name + " after " + seconds(elapsed(step)) + ": " + failure.getMessage());
      throw failure;
    } finally {
      current.remove();
    }
  }

  @Override
  public Result exec(List<String> command)
      throws IOException, InterruptedException, TimeoutException {
    return exec(command, null, COMMAND_DEFAULT);
  }

  @Override
  public Result exec(List<String> command, Path workDir, Duration timeout)
      throws IOException, InterruptedException, TimeoutException {
    return bounded(command, timeout, given -> incus.exec(command, workDir, given));
  }

  @Override
  public Result execWithInput(List<String> command, String input, Duration timeout)
      throws IOException, InterruptedException, TimeoutException {
    return bounded(command, timeout, given -> incus.execWithInput(command, input, given));
  }

  @Override
  public Result run(List<String> command, Consumer<String> lines)
      throws IOException, InterruptedException {
    return incus.run(command, lines);
  }

  @Override
  public InputStream stream(List<String> command) throws IOException {
    return incus.stream(command);
  }

  @Override
  public boolean isDryRun() {
    return incus.isDryRun();
  }

  @Override
  public boolean asksForCredentials(String url) {
    return incus.asksForCredentials(url);
  }

  @FunctionalInterface
  private interface Call {
    Result with(Duration timeout) throws IOException, InterruptedException, TimeoutException;
  }

  /**
   * {@code call} given the least of what it asks and what is left of the current step's bound; a
   * timeout the step's bound set is the step's failure, naming the command.
   */
  private Result bounded(List<String> command, Duration asked, Call call)
      throws IOException, InterruptedException, TimeoutException {
    var step = current.get();
    if (step == null) {
      return call.with(asked);
    }
    var left = step.bound().minus(elapsed(step));
    if (!left.isPositive()) {
      throw overran(step, command);
    }
    var given = asked.compareTo(left) < 0 ? asked : left;
    try {
      return call.with(given);
    } catch (CommandTimedOut timedOut) {
      if (given.equals(left)) {
        throw overran(step, command);
      }
      throw timedOut;
    }
  }

  private static AssertionError overran(Step step, List<String> command) {
    return new AssertionError(
        "setup step '"
            + step.name()
            + "' did not end within its bound of "
            + step.bound().toSeconds()
            + " s: it was waiting for `"
            + String.join(" ", command)
            + "`");
  }

  private Duration elapsed(Step step) {
    return Duration.ofNanos(nanos.getAsLong() - step.started());
  }

  private static String seconds(Duration duration) {
    return String.format(Locale.ROOT, "%.1f s", duration.toMillis() / 1000.0);
  }

  private void say(String what) {
    out.println("[it-setup] " + DateTimeUtils.format(DateTimeUtils.now()) + " " + what);
  }
}
