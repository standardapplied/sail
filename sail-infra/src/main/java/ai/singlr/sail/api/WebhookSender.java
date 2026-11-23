/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.api;

/**
 * Outbound webhook delivery channel. Abstracted so {@link WebhookReactor} can be tested without
 * making real HTTP calls. {@link ai.singlr.sail.engine.WebhookNotifier} is the production
 * implementation.
 */
@FunctionalInterface
public interface WebhookSender extends AutoCloseable {

  /** Send a notification — implementations must be best-effort and never throw. */
  void send(String event, String project, String title, String message);

  /** Lets go of what the sender holds open; one that holds nothing does nothing. */
  @Override
  default void close() {}
}
