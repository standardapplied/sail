/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.api;

import ai.singlr.sail.config.YamlUtil;
import ai.singlr.sail.store.EventStore;
import java.util.List;
import java.util.function.Predicate;

/**
 * Event subscriber that persists events to the SQLite-backed {@link EventStore}. Replaces the
 * file-based {@link AuditPersister} when the control plane server is running.
 */
public final class SpecStoreAuditPersister implements EventSubscriber {

  private final EventStore eventStore;

  public SpecStoreAuditPersister(EventStore eventStore) {
    this.eventStore = eventStore;
  }

  @Override
  public String name() {
    return "sqlite-audit-persister";
  }

  @Override
  public Predicate<Event> filter() {
    return event ->
        Event.WellKnownTypes.retentionClass(event.type()) != Event.RetentionClass.EPHEMERAL;
  }

  @Override
  public void onEvent(Event event) {
    try {
      eventStore.insert(
          new EventStore.EventRow(
              0,
              event.ts().toString(),
              event.type(),
              event.project(),
              event.spec(),
              event.agent(),
              event.host(),
              YamlUtil.dumpJson(event.data())));
    } catch (Exception e) {
      System.err.println("sqlite-audit-persister: failed to persist event: " + e.getMessage());
    }
  }

  public List<Event> recent(int limit) {
    return eventStore.recent(limit).stream()
        .map(row -> Event.of(row.project(), row.specId(), row.type(), row.agent(), row.host()))
        .toList();
  }
}
