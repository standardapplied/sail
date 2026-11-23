/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.engine;

import ai.singlr.sail.common.Strings;

/**
 * Posts one message to Slack. The production implementation is {@link SlackClient}; tests inject a
 * fake that records posts. Implementations are best-effort and never throw: a failed post returns
 * {@code null} after logging why.
 */
@FunctionalInterface
public interface SlackPoster extends AutoCloseable {

  /**
   * One message to post.
   *
   * @param channel the channel name or id to post to
   * @param text the message text (Slack mrkdwn)
   * @param threadTs root message timestamp to thread under, or {@code null} for a root message
   * @param broadcast also show a threaded reply in the channel ({@code reply_broadcast})
   */
  record Post(String channel, String text, String threadTs, boolean broadcast) {
    public Post {
      if (Strings.isBlank(channel)) {
        throw new IllegalArgumentException("channel is required");
      }
      if (Strings.isBlank(text)) {
        throw new IllegalArgumentException("text is required");
      }
    }
  }

  /**
   * A successful post: the channel id Slack resolved and the message timestamp, which doubles as
   * the {@code thread_ts} for replies.
   */
  record Result(String channel, String ts) {}

  /** Posts the message. Returns {@code null} when the post could not be delivered. */
  Result post(Post post);

  /** Lets go of what the poster holds open; one that holds nothing does nothing. */
  @Override
  default void close() {}
}
