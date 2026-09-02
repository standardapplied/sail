/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.api;

import ai.singlr.sail.config.YamlUtil;
import ai.singlr.sail.store.EventStore;
import java.time.Duration;
import java.time.Instant;

/**
 * Completes the live half of the pty event lane. The pty host persists its session facts straight
 * into the events table from its own process, so they never cross this server's {@link EventBus} —
 * which is exactly the stream {@code /v1/events/stream} serves, the one mast treats as its
 * accelerator. Each pass publishes the table's new pty rows onto the bus; {@link
 * SpecStoreAuditPersister} skips them (persisted at source), so a row is never written twice and
 * the lane cannot loop. A row the bridge cannot translate — a blank project, unparseable data — is
 * skipped with one stderr line naming the row, never a stalled cursor: the durable record already
 * holds it, and history reads still serve it.
 */
public final class PtyEventBridge implements AutoCloseable {

  public static final Duration INTERVAL = Duration.ofSeconds(2);
  static final int BATCH = 500;

  private final EventStore events;
  private final EventBus bus;
  private final int batch;
  private final PeriodicPass pass = new PeriodicPass("pty-event-bridge", this::publishNewRows);
  private long lastSeenId;

  public PtyEventBridge(EventStore events, EventBus bus) {
    this(events, bus, BATCH);
  }

  PtyEventBridge(EventStore events, EventBus bus, int batch) {
    this.events = events;
    this.bus = bus;
    this.batch = batch;
    var newest = events.recent(1);
    this.lastSeenId = newest.isEmpty() ? 0 : newest.getFirst().id();
  }

  /** Starts bridging on {@code interval}; rows persisted before construction are never replayed. */
  public void start(Duration interval) {
    pass.start(interval);
  }

  /** One pass over rows newer than the cursor; returns how many pty facts went live. */
  public int publishNewRows() {
    var published = 0;
    while (true) {
      var rows = events.since(lastSeenId, batch);
      for (var row : rows) {
        lastSeenId = row.id();
        if (Event.WellKnownTypes.ptySessionFact(row.type()) && publish(row)) {
          published++;
        }
      }
      if (rows.size() < batch) {
        return published;
      }
    }
  }

  private boolean publish(EventStore.EventRow row) {
    try {
      bus.publish(
          new Event(
              Event.CURRENT_VERSION,
              0,
              Instant.parse(row.timestamp()),
              row.project(),
              row.specId(),
              row.type(),
              row.agent(),
              row.host(),
              YamlUtil.parseMap(row.data())));
      return true;
    } catch (RuntimeException untranslatable) {
      System.err.println(
          "pty-event-bridge: skipped row "
              + row.id()
              + " ("
              + row.type()
              + "): "
              + untranslatable.getMessage());
      return false;
    }
  }

  @Override
  public void close() {
    pass.close();
  }
}
