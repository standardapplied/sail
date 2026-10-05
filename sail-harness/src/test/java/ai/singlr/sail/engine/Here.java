/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.engine;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * The container, as far as one script goes: the command after {@code incus exec ... --} runs on
 * this machine, so a script sail sends into a container is proven against the test's own real
 * processes. Every command it was handed is kept, in order, and one that does not return fails the
 * test rather than hold it.
 */
class Here implements ShellExec {

  private static final Duration PATIENCE = Duration.ofSeconds(30);

  final List<List<String>> commands = new ArrayList<>();
  private final Path firstOnPath;

  Here() {
    this(null);
  }

  /**
   * Commands found in {@code firstOnPath} stand in for the container's own of that name, whether a
   * script calls them or they are the command itself.
   */
  Here(Path firstOnPath) {
    this.firstOnPath = firstOnPath;
  }

  @Override
  public Result exec(List<String> command)
      throws IOException, InterruptedException, TimeoutException {
    commands.add(List.copyOf(command));
    var inner = new ArrayList<>(command.subList(command.indexOf("--") + 1, command.size()));
    var builder = new ProcessBuilder(inner).redirectError(ProcessBuilder.Redirect.DISCARD);
    if (firstOnPath != null) {
      builder
          .environment()
          .merge("PATH", firstOnPath.toString(), (path, first) -> first + ":" + path);
      var standIn = firstOnPath.resolve(inner.getFirst());
      if (Files.isExecutable(standIn)) {
        inner.set(0, standIn.toString());
      }
    }
    var process = builder.start();
    if (!process.waitFor(PATIENCE.toSeconds(), TimeUnit.SECONDS)) {
      process.destroyForcibly();
      throw new TimeoutException(command + " was still running after " + PATIENCE);
    }
    var stdout = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    return new Result(process.exitValue(), stdout, "");
  }

  @Override
  public Result exec(List<String> command, Path workDir, Duration timeout)
      throws IOException, InterruptedException, TimeoutException {
    return exec(command);
  }

  @Override
  public boolean isDryRun() {
    return false;
  }
}
