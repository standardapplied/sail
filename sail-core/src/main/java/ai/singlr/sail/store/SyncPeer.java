/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.store;

import java.util.ArrayList;
import java.util.concurrent.Callable;
import java.util.function.Supplier;

/**
 * The provenance of a synced revision: the box that pushed or served it, bound as a {@link
 * ScopedValue} around the sync apply so {@link ChangeLog#append} records it without every store and
 * replica threading an extra argument. On main receiving a node's push it is the pushing FDE's
 * handle; on a node adopting main's state it is main. Unbound — a purely local mutation — records
 * no peer, and {@code origin=local} already says "this box".
 *
 * <p>This turns an opaque {@code origin=sync} row, whose {@code actor} is the inherited author and
 * so cannot say which box acted, into one that names the source. Same-thread by construction: the
 * append runs synchronously inside the commit/adopt the peer is bound around.
 */
public final class SyncPeer {

  private static final ScopedValue<String> CURRENT = ScopedValue.newInstance();

  private SyncPeer() {}

  /** The peer the current sync apply is bound to, or {@code null} for a local mutation. */
  public static String current() {
    return CURRENT.isBound() ? CURRENT.get() : null;
  }

  /** Runs {@code work} with {@code peer} recorded as the provenance of any revision it journals. */
  public static void with(String peer, Runnable work) {
    if (peer == null) {
      work.run();
    } else {
      ScopedValue.where(CURRENT, peer).run(work);
    }
  }

  /** As {@link #with(String, Runnable)} for work that returns a value. */
  public static <T> T with(String peer, Supplier<T> work) {
    if (peer == null) {
      return work.get();
    }
    var holder = new ArrayList<T>(1);
    ScopedValue.where(CURRENT, peer).run(() -> holder.add(work.get()));
    return holder.getFirst();
  }

  /** As {@link #with(String, Supplier)} for work that may throw — the node's reconcile session. */
  public static <T> T withChecked(String peer, Callable<T> work) throws Exception {
    return peer == null ? work.call() : ScopedValue.where(CURRENT, peer).call(work::call);
  }
}
