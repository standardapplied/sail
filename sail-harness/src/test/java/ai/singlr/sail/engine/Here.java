/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.engine;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeoutException;

/**
 * The container, as far as one script goes: the command after {@code incus exec ... --} runs on
 * this machine, so a script sail sends into a container is proven against the test's own real
 * processes. Every command it was handed is kept, in order.
 */
class Here implements ShellExec {

  final List<List<String>> commands = new ArrayList<>();

  @Override
  public Result exec(List<String> command)
      throws IOException, InterruptedException, TimeoutException {
    commands.add(List.copyOf(command));
    var process =
        new ProcessBuilder(command.subList(command.indexOf("--") + 1, command.size()))
            .redirectError(ProcessBuilder.Redirect.DISCARD)
            .start();
    var stdout = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    return new Result(process.waitFor(), stdout, "");
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
