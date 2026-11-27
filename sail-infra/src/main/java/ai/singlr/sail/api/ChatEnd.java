/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.api;

import ai.singlr.sail.engine.AgentProcess;
import java.time.Duration;
import java.util.Objects;
import java.util.OptionalInt;
import java.util.function.BooleanSupplier;

/**
 * How a chat's process ends, as {@link ProcessEnd} ends any: whether it is ending and why, whether
 * its person ended the chat, and whether it is over and its exit known. Every field is guarded by
 * the monitor of the process it ends ({@code guard}), the one a turn opens under, so deciding an
 * end and opening a turn never interleave.
 */
final class ChatEnd {

  /** How long a process whose output ended is given to report its exit code. */
  private static final Duration EXIT_GRACE = Duration.ofSeconds(5);

  private final Object guard;
  private final String session;
  private final String agent;
  private final AgentProcess.Handle handle;
  private final ChatTiming timing;
  private boolean ending;
  private String reason;
  private boolean chatEnded;
  private boolean over;
  private boolean known;

  /**
   * The end of chat {@code session}'s process {@code handle}, with {@code agent}, under {@code
   * guard}.
   */
  ChatEnd(
      Object guard, String session, String agent, AgentProcess.Handle handle, ChatTiming timing) {
    this.guard = Objects.requireNonNull(guard, "guard");
    this.session = Objects.requireNonNull(session, "session");
    this.agent = Objects.requireNonNull(agent, "agent");
    this.handle = Objects.requireNonNull(handle, "handle");
    this.timing = Objects.requireNonNull(timing, "timing");
  }

  /** Whether the process is ending: it takes no message from now on. Called under the guard. */
  boolean ending() {
    return ending;
  }

  /** Whether the person ended the chat. Called under the guard. */
  boolean chatEnded() {
    return chatEnded;
  }

  /**
   * Begins the end, Sail saying {@code why} of a turn it cuts (null when that needs no saying):
   * stdin is closed, so the process finishes and exits. Called under the guard.
   */
  void begin(String why) {
    ending = true;
    reason = why;
    ProcessEnd.closeInput(handle, "sail-chat-end-" + session);
  }

  /**
   * Ends the process, given {@code first} to end on its own, then interrupted and terminated; the
   * first end begun says why. Returns whether it is over.
   */
  boolean end(Duration first, String why) {
    synchronized (guard) {
      if (!ending) {
        begin(why);
      }
    }
    return ProcessEnd.signalled(handle, timing.grace(), first, false, this::settled);
  }

  /** Ends the process as its person ended the chat. Returns whether it is over. */
  boolean endChat(Duration first) {
    synchronized (guard) {
      chatEnded = true;
    }
    return end(first, null);
  }

  /**
   * Sees out an end begun because the process sat idle: it is given its idle grace to exit, and is
   * never signalled while a turn it began itself is open ({@code turnOpen}, read under the guard,
   * whose changes notify it). Returns whether it is over.
   */
  boolean awaitIdle(BooleanSupplier turnOpen) {
    return ProcessEnd.signalled(
        handle,
        timing.grace(),
        timing.grace().idleExit(),
        false,
        (within, answering) -> idleSettled(within, turnOpen));
  }

  /**
   * The process's exit once its reading stopped, however it stopped: one whose exit is not then
   * known is ended as any is, its stdin closed and signalled, so none outlives its reader. Empty
   * when it is still not known.
   */
  OptionalInt seenOut() {
    var exit = exitWithin(EXIT_GRACE);
    if (exit.isPresent()) {
      return exit;
    }
    ProcessEnd.closeInput(handle, "sail-chat-unread-" + session);
    ProcessEnd.signalled(
        handle,
        timing.grace(),
        timing.grace().afterInt(),
        false,
        (within, answering) -> exitWithin(within).isPresent());
    return exitWithin(Duration.ZERO);
  }

  /** What a turn cut as the process ended with {@code exit} says. Called under the guard. */
  String why(OptionalInt exit) {
    if (ending) {
      return reason;
    }
    return agent
        + "'s process ended before its turn did"
        + (exit.isPresent() ? " (exit code " + exit.getAsInt() + ")" : "")
        + "; your next message starts it again.";
  }

  /** The process ended with {@code exit}, empty when not known. Called under the guard. */
  void exited(OptionalInt exit) {
    known = exit.isPresent();
  }

  /** The process is over and its host told: whoever waits on its end is told. */
  void done() {
    synchronized (guard) {
      over = true;
      guard.notifyAll();
    }
  }

  /** Whether the process is over: nothing reads it any more. */
  boolean over() {
    synchronized (guard) {
      return over;
    }
  }

  /** Whether the process is known gone: over, and its exit known. */
  boolean known() {
    synchronized (guard) {
      return known;
    }
  }

  private OptionalInt exitWithin(Duration within) {
    try {
      return handle.waitFor(within);
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      return OptionalInt.empty();
    }
  }

  private boolean settled(Duration within, boolean answering) {
    synchronized (guard) {
      ProcessEnd.await(guard, within, () -> over);
      return over;
    }
  }

  private boolean idleSettled(Duration within, BooleanSupplier turnOpen) {
    synchronized (guard) {
      ProcessEnd.await(guard, within, () -> over);
      while (turnOpen.getAsBoolean() && !over && !Thread.currentThread().isInterrupted()) {
        ProcessEnd.await(guard, within, () -> over || !turnOpen.getAsBoolean());
        if (!turnOpen.getAsBoolean()) {
          ProcessEnd.await(guard, within, () -> over);
        }
      }
      return over;
    }
  }
}
