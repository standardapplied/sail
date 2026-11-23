/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.engine;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Test double for {@link ShellExec} that returns scripted responses based on command patterns.
 * Commands are matched by checking if the joined command string contains the pattern. Also records
 * all invocations for verification, one command at a time, so threads may share it.
 */
public final class ScriptedShellExecutor implements ShellExec {

  private final Map<String, Result> scripts = new LinkedHashMap<>();
  private final Map<String, IOException> throwing = new LinkedHashMap<>();
  private final Set<String> consumable = new HashSet<>();
  private final Set<String> consumed = new HashSet<>();
  private final List<String> invocations = new ArrayList<>();
  private final List<List<String>> arguments = new ArrayList<>();
  private final List<String> unbounded = new ArrayList<>();
  private final List<String> inputs = new ArrayList<>();
  private final Result defaultResult;
  private java.util.function.Predicate<String> asks = url -> false;

  public ScriptedShellExecutor() {
    this(new Result(1, "", "command not found"));
  }

  public ScriptedShellExecutor(Result defaultResult) {
    this.defaultResult = defaultResult;
  }

  /** Register a response for commands containing the given pattern. */
  public ScriptedShellExecutor on(String pattern, Result result) {
    scripts.put(pattern, result);
    return this;
  }

  /** Shorthand: command containing pattern succeeds with given stdout. */
  public ScriptedShellExecutor onOk(String pattern, String stdout) {
    return on(pattern, new Result(0, stdout, ""));
  }

  /** Shorthand: command containing pattern succeeds with empty output. */
  public ScriptedShellExecutor onOk(String pattern) {
    return on(pattern, new Result(0, "", ""));
  }

  /** Shorthand: command containing pattern fails with given stderr. */
  public ScriptedShellExecutor onFail(String pattern, String stderr) {
    return on(pattern, new Result(1, "", stderr));
  }

  /**
   * Command containing pattern throws — modeling a missing binary, where the real {@link
   * ShellExecutor} fails at {@code ProcessBuilder.start} with {@link IOException} rather than
   * returning a non-zero result.
   */
  public ScriptedShellExecutor onThrow(String pattern, IOException exception) {
    throwing.put(pattern, exception);
    return this;
  }

  /**
   * Command containing pattern succeeds once with given stdout, then falls through to the patterns
   * registered after it.
   */
  public ScriptedShellExecutor onceOnOk(String pattern, String stdout) {
    return onceOn(pattern, new Result(0, stdout, ""));
  }

  /** Shorthand: command containing pattern fails once, then falls through to default on reuse. */
  public ScriptedShellExecutor onceOnFail(String pattern, String stderr) {
    return onceOn(pattern, new Result(1, "", stderr));
  }

  /**
   * Shorthand: command containing pattern succeeds once, then falls through to default on reuse.
   */
  public ScriptedShellExecutor onceOnOk(String pattern) {
    return onceOn(pattern, new Result(0, "", ""));
  }

  /**
   * Command containing pattern answers {@code result} once, then falls through to the patterns
   * registered after it.
   */
  public ScriptedShellExecutor onceOn(String pattern, Result result) {
    consumable.add(pattern);
    return on(pattern, result);
  }

  /**
   * Hangs as a command that never answers does, until {@code timeout}, then times out as {@link
   * ShellExecutor} says it; an interrupt ends it as it ends a command. No process is started, so
   * nothing it opened is left closing once its caller has stopped waiting for it.
   */
  public static Result hang(List<String> command, Duration timeout)
      throws InterruptedException, CommandTimedOut {
    new CountDownLatch(1).await(timeout.toNanos(), TimeUnit.NANOSECONDS);
    throw new CommandTimedOut(
        "Command timed out after " + timeout.toSeconds() + "s: " + String.join(" ", command), "");
  }

  /**
   * What a container that is up, with no agent in it and nothing uncommitted in its repos, answers
   * when it is asked whether a run's agent is there (the one script {@code AgentSession.presence}
   * believes): {@code gone}; and whether a repo holds uncommitted work ({@code git status
   * --porcelain}): nothing. Empty for any other command. A test's own stub shell falls through to
   * this, so that it models such a container rather than a container nobody can reach — which fails
   * every command too, and of which nothing may be concluded.
   */
  public static Optional<Result> reachableContainer(String command) {
    if (command.contains("printf gone") && command.contains("--property=MainPID")) {
      return Optional.of(new Result(0, "gone", ""));
    }
    return command.endsWith(" status --porcelain")
        ? Optional.of(new Result(0, "", ""))
        : Optional.empty();
  }

  /**
   * Returns each executed command as the arguments it was given, in order: what {@link
   * #invocations} joins, for a test that must tell one argument from two.
   */
  public List<List<String>> arguments() {
    return Collections.unmodifiableList(arguments);
  }

  /** Returns an unmodifiable list of all commands that were executed, in order. */
  public List<String> invocations() {
    return Collections.unmodifiableList(invocations);
  }

  @Override
  public Result exec(List<String> command) throws IOException {
    return exec(command, null, Duration.ZERO);
  }

  /** Answers as {@link #exec} does, keeping what was written to the command's stdin. */
  @Override
  public Result execWithInput(List<String> command, String input, Duration timeout)
      throws IOException {
    inputs.add(input);
    return exec(command, null, timeout);
  }

  /** What each command run with input was given on its stdin, in order. */
  public List<String> inputs() {
    return Collections.unmodifiableList(inputs);
  }

  /** The commands run to exit with no deadline ({@link #run}), joined, in order. */
  public List<String> unbounded() {
    return Collections.unmodifiableList(unbounded);
  }

  /** Answers as {@link #exec} does, handing the scripted stdout over line by line first. */
  @Override
  public Result run(List<String> command, Consumer<String> lines) throws IOException {
    unbounded.add(String.join(" ", command));
    var result = exec(command);
    result.stdout().lines().forEach(lines);
    return result;
  }

  @Override
  public synchronized Result exec(List<String> command, Path workDir, Duration timeout)
      throws IOException {
    var joined = String.join(" ", command);
    invocations.add(joined);
    arguments.add(List.copyOf(command));
    for (var entry : throwing.entrySet()) {
      if (joined.contains(entry.getKey())) {
        throw entry.getValue();
      }
    }
    for (var entry : scripts.entrySet()) {
      if (joined.contains(entry.getKey())) {
        if (consumed.contains(entry.getKey())) {
          continue;
        }
        if (consumable.contains(entry.getKey())) {
          consumed.add(entry.getKey());
        }
        return entry.getValue();
      }
    }
    return defaultResult;
  }

  @Override
  public java.io.InputStream stream(List<String> command) throws IOException {
    var result = exec(command);
    if (!result.ok()) throw new IOException(result.stderr());
    return new java.io.ByteArrayInputStream(
        result.stdout().getBytes(java.nio.charset.StandardCharsets.UTF_8));
  }

  @Override
  public boolean isDryRun() {
    return false;
  }

  /** Answers a plain request to {@code url} as asking for credentials when {@code asks} says so. */
  public ScriptedShellExecutor asks(java.util.function.Predicate<String> asks) {
    this.asks = asks;
    return this;
  }

  @Override
  public boolean asksForCredentials(String url) {
    return asks.test(url);
  }
}
