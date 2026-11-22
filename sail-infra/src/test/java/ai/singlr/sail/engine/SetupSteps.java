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
 * bound as its timeout. Without the lines a setup that crawls is said by nothing until the job's
 * limit cancels the job: on #529's run a runner whose disk copied some 60 times slower than usual
 * held {@code WorkspaceIT}'s first setup silent for 12.75 minutes.
 *
 * <p>A command the bound cuts off times out as any command does, a {@link CommandTimedOut} whose
 * message keeps its own form and adds the step's, so what reads a timeout (a start's or a stop's
 * {@link InstanceTimeoutDiagnosis}, the lane's search for a timed-out incus command) reads this one
 * too. The step is spent from then on: what runs after on its thread, that diagnosis among it, has
 * its own timeout, and the step fails at its end even when its work went on. A step inside a step
 * runs within the outer one's bound.
 */
final class SetupSteps implements ShellExec {

  /** What a command asks for when it names no timeout. */
  static final Duration COMMAND_DEFAULT = ShellExecutor.DEFAULT_TIMEOUT;

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

  /** A step under way; {@code overran} says how its bound ran out, once it has. */
  private record Step(String name, Duration bound, long started, String overran) {}

  /** Runs {@code work} as the setup step {@code name}, held to {@code bound}. */
  <T> T step(String name, Duration bound, Work<T> work) throws Exception {
    if (current.get() != null) {
      return work.run();
    }
    var step = new Step(name, bound, nanos.getAsLong(), null);
    say("start " + name + " (bound " + bound.toSeconds() + " s)");
    current.set(step);
    try {
      var answer = work.run();
      var overran = current.get().overran();
      if (overran != null) {
        throw new AssertionError(overran);
      }
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
   * {@code call} given the least of what it asks and what is left of the current step's bound, or
   * what it asks once the step is spent; a timeout the bound set spends the step.
   */
  private Result bounded(List<String> command, Duration asked, Call call)
      throws IOException, InterruptedException, TimeoutException {
    var step = current.get();
    if (step == null || step.overran() != null) {
      return call.with(asked);
    }
    var left = step.bound().minus(elapsed(step));
    if (!left.isPositive()) {
      throw spend(
          step,
          new CommandTimedOut("Command timed out after 0s: " + String.join(" ", command), ""),
          "it was spent before `" + String.join(" ", command) + "` began");
    }
    if (asked.compareTo(left) <= 0) {
      return call.with(asked);
    }
    try {
      return call.with(left);
    } catch (CommandTimedOut timedOut) {
      throw spend(
          step,
          timedOut,
          "it gave `" + String.join(" ", command) + "` its last " + seconds(left) + " of it");
    }
  }

  /** {@code timedOut} with the step's word of its bound after its own; the step spent. */
  private CommandTimedOut spend(Step step, CommandTimedOut timedOut, String how) {
    var overran =
        "setup step '"
            + step.name()
            + "' did not end within its bound of "
            + step.bound().toSeconds()
            + " s: "
            + how;
    current.set(new Step(step.name(), step.bound(), step.started(), overran));
    var said =
        new CommandTimedOut(timedOut.getMessage() + " (" + overran + ")", timedOut.processes());
    said.initCause(timedOut);
    return said;
  }

  private Duration elapsed(Step step) {
    return Duration.ofNanos(nanos.getAsLong() - step.started());
  }

  private static String seconds(Duration duration) {
    return String.format(Locale.ROOT, "%.1f s", duration.toMillis() / 1000.0);
  }

  /** Says {@code what} as one line of the setup's, with the time. */
  void say(String what) {
    out.println("[it-setup] " + DateTimeUtils.format(DateTimeUtils.now()) + " " + what);
  }
}
