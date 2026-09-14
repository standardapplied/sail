/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.commands;

import ai.singlr.sail.pty.PtyChildShim;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

/**
 * The session shim the pty host spawns every child under: {@code sail _pty-child --exit <file> --
 * <command...>}. Everything it does is {@link PtyChildShim}; this is its command-line skin.
 */
@Command(
    name = "_pty-child",
    description = "Internal: run a session's command and record its exit status.",
    hidden = true)
public final class PtyChildCommand implements Callable<Integer> {

  @Option(names = "--exit", required = true, description = "Where the exit status is written.")
  private Path exitFile;

  @Parameters(arity = "1..*", description = "The command to run.")
  private List<String> command;

  @Override
  public Integer call() throws Exception {
    return PtyChildShim.run(exitFile, command);
  }
}
