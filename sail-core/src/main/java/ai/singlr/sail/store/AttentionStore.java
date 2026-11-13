/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.store;

import ai.singlr.sail.access.Access;
import ai.singlr.sail.common.DateTimeUtils;
import ai.singlr.sail.config.YamlUtil;
import ai.singlr.sail.identity.Actor;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Collection;
import java.util.List;
import java.util.Objects;

/**
 * The minutes this box saw a person give a conversation: one row per (person, conversation, minute,
 * channel), from a fact the server observed, and nothing else about the person (M7) — no input, no
 * text, no title, no duration. Box-local, never synced: each box counts what it saw.
 */
public final class AttentionStore {

  /** Where a minute was seen. */
  public enum Channel {
    /** Input the session's writer typed in a room-bound terminal, accepted. */
    TERMINAL_INPUT("terminal_input"),
    /** A message the person posted. */
    POST("post"),
    /** A review decision or a status move the person made. */
    REVIEW_ACT("review_act"),
    /** A client's view of the conversation, focused, with the person's input in the last minute. */
    FOCUS("focus");

    private final String wire;

    Channel(String wire) {
      this.wire = wire;
    }

    public String wire() {
      return wire;
    }

    static Channel fromWire(String wire) {
      for (var channel : values()) {
        if (channel.wire.equals(wire)) {
          return channel;
        }
      }
      throw new IllegalArgumentException("Unknown attention channel: '" + wire + "'.");
    }
  }

  /** One minute a person gave a conversation, and where it was seen. */
  public record Minute(String person, String conversation, Instant minute, Channel channel) {}

  private final Sqlite db;

  public AttentionStore(Sqlite db) {
    this.db = Objects.requireNonNull(db, "db");
  }

  /** The minute {@code at} falls in: its start. */
  public static Instant minuteOf(Instant at) {
    return at.truncatedTo(ChronoUnit.MINUTES);
  }

  /**
   * Records that {@code actor} gave {@code conversation} the current minute through {@code
   * channel}, when the actor is a person on a person's own lane ({@link Access#attendee}); anything
   * else records nothing. Returns whether a row was new.
   */
  public boolean attend(Actor actor, String conversation, Channel channel) {
    return Access.attendee(actor)
        .map(person -> record(person, conversation, DateTimeUtils.now(), channel))
        .orElse(false);
  }

  /**
   * Records that {@code person} gave {@code conversation} the minute {@code at} falls in, through
   * {@code channel}; a minute already recorded is not recorded again. Returns whether a row was
   * new.
   */
  public boolean record(String person, String conversation, Instant at, Channel channel) {
    db.execute(
        "INSERT OR IGNORE INTO attention (person, conversation, minute, channel) VALUES (?, ?, ?, ?)",
        person,
        conversation,
        minuteOf(at),
        channel.wire());
    return db.changes() > 0;
  }

  /** Every minute this box recorded of {@code conversations}, oldest first. */
  public List<Minute> of(Collection<String> conversations) {
    return db.query(
        "SELECT person, conversation, minute, channel FROM attention"
            + " WHERE conversation IN (SELECT value FROM json_each(?))"
            + " ORDER BY minute, person, channel",
        row -> new Minute(row.text(0), row.text(1), row.instant(2), Channel.fromWire(row.text(3))),
        YamlUtil.dumpJson(List.copyOf(conversations)));
  }

  /** Removes every minute of {@code conversation}: it goes with the conversation it is of. */
  public void forget(String conversation) {
    db.execute("DELETE FROM attention WHERE conversation = ?", conversation);
  }
}
