/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.engine;

import java.util.concurrent.TimeoutException;

/**
 * A command that ran out of time, with what its processes were doing at that moment, before they
 * were killed: {@code ps}'s listing of each, with its state and the kernel function it waited in,
 * or what {@code ps} said instead; empty unless the caller asked for it ({@link
 * ShellExecutor#listingTimedOutProcesses}). It also gives back what the command wrote on each of
 * its outputs up to the timeout, which its message never holds, since a command's output can hold
 * what it was given.
 */
public final class CommandTimedOut extends TimeoutException {

  private final String processes;
  private final String stdout;
  private final String stderr;

  public CommandTimedOut(String message, String processes) {
    this(message, processes, "", "");
  }

  public CommandTimedOut(String message, String processes, String stdout, String stderr) {
    super(message);
    this.processes = processes;
    this.stdout = stdout;
    this.stderr = stderr;
  }

  public String processes() {
    return processes;
  }

  /** What the command wrote on its standard output up to the timeout. */
  public String stdout() {
    return stdout;
  }

  /** What the command wrote on its standard error up to the timeout. */
  public String stderr() {
    return stderr;
  }
}
