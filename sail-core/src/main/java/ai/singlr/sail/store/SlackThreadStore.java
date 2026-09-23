/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.store;

import java.util.Optional;

/**
 * Persists the Slack thread root for each spec's latest dispatch attempt, so every lifecycle event
 * after the dispatch threads under the same root message. A re-dispatch replaces the row — new
 * attempt, new thread. The channel is stored alongside the timestamp because Slack requires replies
 * to target the channel id returned by {@code chat.postMessage}, not the configured channel name.
 */
public final class SlackThreadStore {

  /** A spec's current Slack thread: the resolved channel id and the root message timestamp. */
  public record ThreadRef(String channel, String threadTs) {}

  private final Sqlite db;

  public SlackThreadStore(Sqlite db) {
    this.db = db;
  }

  public void save(String project, String specId, String channel, String threadTs) {
    db.execute(
        """
        INSERT INTO slack_threads (project, spec_id, channel, thread_ts, created_at)
        VALUES (?, ?, ?, ?, datetime('now'))
        ON CONFLICT (project, spec_id) DO UPDATE
        SET channel = excluded.channel,
            thread_ts = excluded.thread_ts,
            created_at = excluded.created_at""",
        project,
        specId,
        channel,
        threadTs);
  }

  /** Forgets the threads of an erased spec, in whichever project they were opened. */
  public void eraseBySpec(String specId) {
    db.execute("DELETE FROM slack_threads WHERE spec_id = ?", specId);
  }

  public Optional<ThreadRef> find(String project, String specId) {
    return db.queryOne(
        "SELECT channel, thread_ts FROM slack_threads WHERE project = ? AND spec_id = ?",
        row -> new ThreadRef(row.text(0), row.text(1)),
        project,
        specId);
  }
}
