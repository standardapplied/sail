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
import java.util.List;
import java.util.concurrent.TimeoutException;

/**
 * A shell on which every command containing {@code pattern} runs past the deadline it is given, as
 * a {@code zpool status} of a suspended pool does, and every other command answers as {@code
 * delegate} does. It records each deadline the timed-out commands were given.
 */
public final class TimingOutShell implements ShellExec {

  private final ScriptedShellExecutor delegate;
  private final String pattern;
  private final List<Duration> deadlines = Collections.synchronizedList(new ArrayList<>());

  public TimingOutShell(ScriptedShellExecutor delegate, String pattern) {
    this.delegate = delegate;
    this.pattern = pattern;
  }

  /** The deadline each timed-out command was given, in order. */
  public List<Duration> deadlines() {
    return List.copyOf(deadlines);
  }

  @Override
  public Result exec(List<String> command) throws IOException, TimeoutException {
    return exec(command, null, null);
  }

  @Override
  public Result exec(List<String> command, Path workDir, Duration timeout)
      throws IOException, TimeoutException {
    if (String.join(" ", command).contains(pattern)) {
      deadlines.add(timeout);
      throw new TimeoutException(String.join(" ", command) + " timed out after " + timeout);
    }
    return delegate.exec(command, workDir, timeout);
  }

  @Override
  public boolean isDryRun() {
    return false;
  }
}
