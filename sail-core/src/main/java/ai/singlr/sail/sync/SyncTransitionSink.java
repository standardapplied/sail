/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.sync;

/**
 * Receives each {@link SyncTransition} the {@link SyncRpcServer} detects while committing a node's
 * push. The server invokes the sink after the commit succeeds and shields the session from any sink
 * failure, so an implementation may do best-effort side work (main publishes lifecycle events to
 * its local sail-api) without ever affecting the sync itself.
 */
@FunctionalInterface
public interface SyncTransitionSink {

  /** A sink that ignores every transition — the default for callers that do not narrate. */
  SyncTransitionSink NONE = transition -> {};

  void onTransition(SyncTransition transition);
}
