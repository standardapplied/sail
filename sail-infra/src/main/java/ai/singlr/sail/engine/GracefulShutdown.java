/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.engine;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Closes a set of {@link AutoCloseable} resources exactly once, newest-first, isolating failures so
 * one bad close cannot strand the rest. The control-plane daemon registers this as a JVM shutdown
 * hook: the server blocks on a latch until {@code systemctl stop}/upgrade sends SIGTERM, at which
 * point the JVM does not unwind the stack — a try-with-resources never runs — so the hook is the
 * only thing that drains in-flight review writes and closes the database cleanly. The same instance
 * is also invoked from the normal exit path; the idempotency guard makes the two safe to race.
 */
public final class GracefulShutdown {

  private final Deque<AutoCloseable> resources = new ArrayDeque<>();
  private final AtomicBoolean done = new AtomicBoolean(false);

  /** Registers a resource to close on shutdown. Resources close in reverse registration order. */
  public synchronized GracefulShutdown register(AutoCloseable resource) {
    resources.push(resource);
    return this;
  }

  /** Closes every registered resource once, newest-first. Idempotent and failure-isolating. */
  public void shutdown() {
    if (!done.compareAndSet(false, true)) {
      return;
    }
    List<AutoCloseable> ordered;
    synchronized (this) {
      ordered = new ArrayList<>(resources);
    }
    for (var resource : ordered) {
      try {
        resource.close();
      } catch (Exception e) {
        System.err.println("  [shutdown] failed to close " + resource + ": " + e);
      }
    }
    System.out.println("  [shutdown] closed " + ordered.size() + " resource(s) cleanly");
  }
}
