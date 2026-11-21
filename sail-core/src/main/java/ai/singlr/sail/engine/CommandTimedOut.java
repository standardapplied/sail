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
 * ShellExecutor#listingTimedOutProcesses}).
 */
public final class CommandTimedOut extends TimeoutException {

  private final String processes;

  public CommandTimedOut(String message, String processes) {
    super(message);
    this.processes = processes;
  }

  public String processes() {
    return processes;
  }
}
