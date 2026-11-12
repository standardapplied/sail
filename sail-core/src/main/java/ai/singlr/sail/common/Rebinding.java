/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.common;

import java.io.IOException;
import java.time.Duration;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * A listener on a private address that may not be up yet: the private network comes up after the
 * box does. It is bound when first asked, and while it is not, again on a tick once each backoff
 * has passed, from {@link #FIRST_RETRY} doubling to {@link #LAST_RETRY}; it says so once each time
 * its state changes, never once per try. Nothing else waits on it: loopback and the unix socket
 * serve whatever it does. Driven by a timer with the time, and by tests with synthetic time.
 */
public final class Rebinding {

  static final Duration FIRST_RETRY = Duration.ofSeconds(1);
  static final Duration LAST_RETRY = Duration.ofMinutes(1);

  /** One try at binding the listener. */
  @FunctionalInterface
  public interface Bind {
    void bind() throws IOException;
  }

  private final String door;
  private final Bind bind;
  private final Consumer<String> say;
  private boolean listening;
  private String failing;
  private long retryAt;
  private Duration backoff = FIRST_RETRY;

  /** The listener {@code door} names, bound by {@code bind}, its changes said to {@code say}. */
  public Rebinding(String door, Bind bind, Consumer<String> say) {
    this.door = Objects.requireNonNull(door, "door");
    this.bind = Objects.requireNonNull(bind, "bind");
    this.say = Objects.requireNonNull(say, "say");
  }

  /** Tries to bind at {@code nowNanos} when not listening and the backoff has passed. */
  public synchronized void tick(long nowNanos) {
    if (listening || failing != null && nowNanos - retryAt < 0) {
      return;
    }
    try {
      bind.bind();
      listening = true;
      say.accept(door + " is listening.");
    } catch (IOException unbound) {
      var reason = Objects.requireNonNullElse(unbound.getMessage(), unbound.toString());
      if (!reason.equals(failing)) {
        say.accept(door + " is not listening (" + reason + "); it is tried again until it binds.");
      }
      failing = reason;
      retryAt = nowNanos + backoff.toNanos();
      backoff =
          backoff.multipliedBy(2).compareTo(LAST_RETRY) > 0 ? LAST_RETRY : backoff.multipliedBy(2);
    }
  }

  /**
   * The bound listener was lost at {@code nowNanos} for {@code reason}: the door is not listening,
   * says so once, and is tried again from the first backoff at its next tick.
   */
  public synchronized void lost(long nowNanos, String reason) {
    listening = false;
    failing = reason;
    retryAt = nowNanos;
    backoff = FIRST_RETRY;
    say.accept(door + " stopped listening (" + reason + "); it is tried again until it binds.");
  }

  /** Whether the listener is bound. */
  public synchronized boolean listening() {
    return listening;
  }
}
