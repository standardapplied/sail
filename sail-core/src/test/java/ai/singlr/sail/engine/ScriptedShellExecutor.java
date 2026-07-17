/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.engine;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Test double for {@link ShellExec} that returns scripted responses based on command patterns.
 * Commands are matched by checking if the joined command string contains the pattern. Also records
 * all invocations for verification.
 */
public final class ScriptedShellExecutor implements ShellExec {

  private final Map<String, Result> scripts = new LinkedHashMap<>();
  private final Set<String> consumable = new HashSet<>();
  private final Set<String> consumed = new HashSet<>();
  private final List<String> invocations = new ArrayList<>();
  private final Result defaultResult;

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

  /** Shorthand: command containing pattern fails once, then falls through to default on reuse. */
  public ScriptedShellExecutor onceOnFail(String pattern, String stderr) {
    consumable.add(pattern);
    return on(pattern, new Result(1, "", stderr));
  }

  /**
   * Shorthand: command containing pattern succeeds once, then falls through to default on reuse.
   */
  public ScriptedShellExecutor onceOnOk(String pattern) {
    consumable.add(pattern);
    return on(pattern, new Result(0, "", ""));
  }

  /** Returns an unmodifiable list of all commands that were executed, in order. */
  public List<String> invocations() {
    return Collections.unmodifiableList(invocations);
  }

  @Override
  public Result exec(List<String> command) {
    return exec(command, null, Duration.ZERO);
  }

  @Override
  public Result exec(List<String> command, Path workDir, Duration timeout) {
    var joined = String.join(" ", command);
    invocations.add(joined);
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
  public boolean isDryRun() {
    return false;
  }
}
