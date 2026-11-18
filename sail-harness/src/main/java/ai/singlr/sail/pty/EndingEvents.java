/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.pty;

import java.util.function.Consumer;

/**
 * {@code events}, and {@code ended} told of each session after its end is: what the host does as a
 * session ends (its uploads leave its container, U8). Every method of {@link PtyEvents} is
 * forwarded here, which {@code EndingEventsTest} holds of each one the interface has.
 */
final class EndingEvents implements PtyEvents {
  private final PtyEvents events;
  private final Consumer<PtySession.Origin> ended;

  EndingEvents(PtyEvents events, Consumer<PtySession.Origin> ended) {
    this.events = events;
    this.ended = ended;
  }

  @Override
  public void sessionStarted(PtySession.Origin origin) {
    events.sessionStarted(origin);
  }

  @Override
  public void sessionAttached(PtySession.Origin origin, String fde) {
    events.sessionAttached(origin, fde);
  }

  @Override
  public void sessionEnded(PtySession.Origin origin, String reason) {
    events.sessionEnded(origin, reason);
    ended.accept(origin);
  }

  @Override
  public void typed(PtySession.Origin origin, String person) {
    events.typed(origin, person);
  }
}
