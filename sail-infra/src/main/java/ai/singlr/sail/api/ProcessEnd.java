/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.api;

import ai.singlr.sail.engine.AgentProcess;
import ai.singlr.sail.engine.AgentSession;
import ai.singlr.sail.engine.AgentUnit;
import ai.singlr.sail.engine.ShellExec;
import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.TimeoutException;
import java.util.function.BooleanSupplier;

/**
 * How a process the host drives over pipes is ended, a live member's or a chat's: its stdin is
 * closed, so it finishes what it is doing and exits; it is given a first while to do that, and is
 * then interrupted and, if that does not end it either, terminated, each signal given its own while
 * ({@link Grace}). One that no pipe of this sail reaches, left from before it started, is halted
 * through its pid file ({@link #halted}).
 */
final class ProcessEnd {

  /**
   * How long each step of an end waits: for a process that was only idle to exit once its stdin
   * closes, for an open turn's result, and after each signal.
   */
  record Grace(Duration idleExit, Duration result, Duration afterInt, Duration afterTerm) {

    static final Grace DEFAULT =
        new Grace(
            Duration.ofSeconds(60),
            Duration.ofSeconds(30),
            Duration.ofSeconds(10),
            Duration.ofSeconds(10));

    /** The longest an end can take, start to finish. */
    Duration whole() {
      return idleExit.plus(afterInt).plus(afterTerm);
    }
  }

  /** What the ending waits on: whether the process is over within a while. */
  @FunctionalInterface
  interface Settles {

    /**
     * Waits up to {@code within} for the process to be over or, when {@code answering}, for its
     * open turn to end; returns whether it is over.
     */
    boolean settled(Duration within, boolean answering);
  }

  /** What the container says of a process a halt did not prove gone. */
  @FunctionalInterface
  interface Gone {

    /** Whether the process is known gone. */
    boolean gone() throws IOException, InterruptedException, TimeoutException;
  }

  private ProcessEnd() {}

  /**
   * Halts the process the pid file of {@code unit} names in {@code instance}, left from before this
   * sail started ({@link AgentSession#killAgent}), and answers whether it is then known gone: the
   * halt's own answer, else {@code gone}'s. A container that does not answer proves nothing, so
   * whatever the process holds stays held until one does.
   */
  static boolean halted(ShellExec shell, String instance, AgentUnit unit, Gone gone) {
    try {
      return new AgentSession(shell).killAgent(instance, unit) instanceof AgentSession.Halt.Ended
          || gone.gone();
    } catch (Exception unanswered) {
      return false;
    }
  }

  /**
   * Closes {@code handle}'s stdin on a thread of its own, named {@code name}: the close waits
   * behind a write the process is not reading, and the signals that end such a process must not
   * wait with it.
   */
  static void closeInput(AgentProcess.Handle handle, String name) {
    Thread.ofVirtual().name(name).start(handle.stdin()::close);
  }

  /**
   * Waits on {@code monitor}, which the caller holds and notifies as what it waits for changes, up
   * to {@code within} or until {@code done}; an interrupt ends the wait early, and is kept.
   */
  static void await(Object monitor, Duration within, BooleanSupplier done) {
    var deadline = System.nanoTime() + within.toNanos();
    while (!done.getAsBoolean()) {
      var left = deadline - System.nanoTime();
      if (left <= 0) {
        return;
      }
      try {
        monitor.wait(Math.max(1, left / 1_000_000));
      } catch (InterruptedException interrupted) {
        Thread.currentThread().interrupt();
        return;
      }
    }
  }

  /**
   * Gives a process whose stdin is closed {@code first} to be over, or, when {@code answering}, to
   * end its open turn; then interrupts it and, when that does not end it within the grace, ends it.
   * Returns whether it is over.
   */
  static boolean signalled(
      AgentProcess.Handle handle, Grace grace, Duration first, boolean answering, Settles process) {
    if (!process.settled(first, answering)) {
      handle.signal(AgentProcess.Signal.INT);
      if (!process.settled(grace.afterInt(), false)) {
        handle.signal(AgentProcess.Signal.TERM);
      }
    }
    return process.settled(grace.afterTerm(), false);
  }
}
