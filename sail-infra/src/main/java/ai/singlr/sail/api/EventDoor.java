/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.api;

import ai.singlr.sail.authority.EventAuthority;
import ai.singlr.sail.common.DateTimeUtils;
import ai.singlr.sail.common.Strings;
import ai.singlr.sail.identity.Actor;
import ai.singlr.sail.store.MessageStore;
import ai.singlr.sail.store.Sqlite;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * The one way a client's event reaches the bus, whichever door took it: the HTTP API and the
 * in-container socket both hand it here. Asks {@link EventAuthority} as the bound actor, then
 * stamps what the server knows itself, so nothing a subscriber acts on, or the event log records,
 * rests on the sender's word: the project and conversation of the run, spec or room the event
 * names, the server's clock, and the publisher. A sender may leave the conversation out, as the
 * hooks of a run that works only a room do; it never names one the run does not work.
 *
 * <p>A message announcement is the one event about a row another process wrote: a sync round writes
 * the messages it carries straight to the database, then announces each so rooms wake and live
 * clients see it. The announcement names the message and nothing more; it is decided on the
 * conversation that message is in, never on a run or a room its sender names beside it, and the
 * event is rebuilt whole from the stored row, exactly as the messages route emits it for a local
 * post: under the project the message is filed in ({@link MessageStore#projectOf}), its room's,
 * which is not its spec's once that spec has moved, and only for a sender whose scope admits it.
 */
final class EventDoor {

  private final EventAuthority authority;
  private final MessageStore messages;

  /** The door over {@code db}, on the box whose FDE handle {@code boxFde} reads. */
  EventDoor(Sqlite db, Supplier<String> boxFde) {
    this.authority = new EventAuthority(db, boxFde);
    this.messages = new MessageStore(db);
  }

  /**
   * {@code offered} as the bus takes it, or the rule's refusal: an {@link ApiException} naming the
   * event type and what its sender may not drive.
   */
  Event admit(Event offered) {
    return Event.WellKnownTypes.SPEC_MESSAGE_POSTED.equals(offered.type())
        ? announcement(offered)
        : report(offered);
  }

  private Event report(Event offered) {
    var subject =
        seen(authority.subject(
                offered.project(),
                offered.spec(),
                Objects.toString(offered.data().get(Event.WellKnownData.RUN_ID), null)))
            .orElseGet(() -> unheld(offered.project(), offered.spec()));
    var publisher = publisher(offered, subject);
    return offered.admitted(
        subject.project(),
        Strings.isBlank(offered.spec()) ? null : subject.conversation(),
        publisher,
        DateTimeUtils.now());
  }

  private Event announcement(Event offered) {
    var message =
        Scoped.row(Actor.current(), messages.findById(announced(offered)), messages::projectOf);
    var about =
        seen(
            authority.subject(
                offered.project(),
                message.map(MessageStore.MessageRow::roomId).orElse(offered.spec()),
                null));
    var subject = about.orElseGet(() -> unheld(offered.project(), offered.spec()));
    var publisher = publisher(offered, subject);
    var held = message.filter(found -> about.isPresent()).orElseThrow(() -> unheld(offered));
    var project = Objects.requireNonNullElse(messages.projectOf(held), subject.project());
    return SyncTransitionEvents.messagePosted(
            project,
            held.roomId(),
            held.id(),
            held.author(),
            held.body(),
            held.question(),
            offered.host())
        .admitted(project, held.roomId(), publisher, DateTimeUtils.now());
  }

  /**
   * {@code subject} when the sender's scope admits its project, and empty when it does not: what
   * this box holds outside that scope is decided as work it does not hold, which has no owner to
   * act for, so no refusal says whose it is.
   */
  private static Optional<EventAuthority.Subject> seen(EventAuthority.Subject subject) {
    return Scoped.row(Actor.current(), Optional.of(subject), EventAuthority.Subject::project);
  }

  /** The subject of an event about work this box does not hold, as its sender addressed it. */
  private static EventAuthority.Subject unheld(String project, String conversation) {
    return new EventAuthority.Subject(
        project,
        Strings.isBlank(conversation) ? null : conversation,
        null,
        List.of(),
        List.of(),
        false);
  }

  private Event.Publisher publisher(Event offered, EventAuthority.Subject subject) {
    var actor = Actor.current();
    var retold =
        Event.WellKnownData.SOURCE_SYNC.equals(offered.data().get(Event.WellKnownData.SOURCE));
    Refusals.enforce(authority.decide(actor, offered.type(), subject, retold));
    return Event.Publisher.of(actor);
  }

  private static String announced(Event offered) {
    return Objects.toString(offered.data().get(Event.WellKnownData.MESSAGE_ID), "");
  }

  private static ApiException unheld(Event offered) {
    return new ApiException(
        ErrorCode.NOT_FOUND,
        "Event type '"
            + offered.type()
            + "' announces a message this box holds, and it holds none with id '"
            + announced(offered)
            + "'.",
        "Post through the messages route; the server announces a post itself.");
  }
}
