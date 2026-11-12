/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.engine;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;

/**
 * The CI runner's shell as root, through passwordless {@code sudo -n}, for the integration tests
 * that run what {@code sudo sail host init} runs.
 */
final class RootShell implements ShellExec {

  private final ShellExecutor shell = new ShellExecutor(false);

  @Override
  public Result exec(List<String> command)
      throws IOException, InterruptedException, TimeoutException {
    return shell.exec(asRoot(command));
  }

  @Override
  public Result exec(List<String> command, Path workDir, Duration timeout)
      throws IOException, InterruptedException, TimeoutException {
    return shell.exec(asRoot(command), workDir, timeout);
  }

  @Override
  public Result run(List<String> command, Consumer<String> lines)
      throws IOException, InterruptedException {
    return shell.run(asRoot(command), lines);
  }

  @Override
  public boolean isDryRun() {
    return false;
  }

  private static List<String> asRoot(List<String> command) {
    var rooted = new ArrayList<>(List.of("sudo", "-n"));
    rooted.addAll(command);
    return rooted;
  }
}
