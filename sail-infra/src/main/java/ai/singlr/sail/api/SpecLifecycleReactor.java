/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.api;

import ai.singlr.sail.config.SpecStatus;
import ai.singlr.sail.store.SpecStore;
import java.util.Objects;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Bus subscriber that advances a spec's lifecycle in the database when its agent session ends: on
 * {@code agent_session_stopped} it transitions the spec from {@code in_progress} to {@code review}.
 * The dispatch-time transition to {@code in_progress} lives with dispatch; this handles the back
 * half once the agent reports it is done. The database is the single source of truth — there is no
 * container file to keep in step.
 *
 * <p>The audit trail is not this reactor's job: every lifecycle event is already persisted to the
 * {@code EventStore} by {@link SpecStoreAuditPersister}. Failures here are logged and swallowed so
 * a single broken transition cannot take the bus down.
 */
public final class SpecLifecycleReactor implements EventSubscriber {

  private static final Set<String> HANDLED_TYPES =
      Set.of(Event.WellKnownTypes.AGENT_SESSION_STOPPED);

  private final SpecStore specStore;

  public SpecLifecycleReactor(SpecStore specStore) {
    this.specStore = Objects.requireNonNull(specStore, "specStore");
  }

  @Override
  public String name() {
    return "spec-lifecycle";
  }

  @Override
  public Predicate<Event> filter() {
    return e -> HANDLED_TYPES.contains(e.type()) && e.spec() != null && !e.spec().isBlank();
  }

  @Override
  public void onEvent(Event event) {
    try {
      specStore
          .findById(event.spec())
          .filter(spec -> spec.status() == SpecStatus.IN_PROGRESS)
          .ifPresent(spec -> specStore.updateStatus(spec.id(), SpecStatus.REVIEW));
    } catch (Exception e) {
      System.err.println(
          "  [spec-lifecycle] Warning: failed to advance "
              + event.project()
              + "/"
              + event.spec()
              + " to review: "
              + e.getMessage());
    }
  }
}
