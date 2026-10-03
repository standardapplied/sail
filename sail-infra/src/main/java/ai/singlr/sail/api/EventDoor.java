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
import java.util.Objects;
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
 * post.
 */
final class EventDoor {

  private static final String MESSAGE_ID = "message_id";

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
        : about(offered);
  }

  private Event about(Event offered) {
    var subject =
        authority.subject(
            offered.project(),
            offered.spec(),
            Objects.toString(offered.data().get(Event.WellKnownData.RUN_ID), null));
    var publisher = admitted(offered, subject);
    return offered.admitted(
        subject.project(),
        Strings.isBlank(offered.spec()) ? null : subject.conversation(),
        publisher,
        DateTimeUtils.now());
  }

  private Event announcement(Event offered) {
    var message = messages.findById(Objects.toString(offered.data().get(MESSAGE_ID), ""));
    var subject =
        authority.subject(
            offered.project(),
            message.map(MessageStore.MessageRow::roomId).orElse(offered.spec()),
            null);
    var publisher = admitted(offered, subject);
    var held = message.orElseThrow(() -> unheld(offered));
    return SyncTransitionEvents.messagePosted(
            subject.project(),
            held.roomId(),
            held.id(),
            held.author(),
            held.body(),
            held.question(),
            offered.host())
        .admitted(subject.project(), held.roomId(), publisher, DateTimeUtils.now());
  }

  private Event.Publisher admitted(Event offered, EventAuthority.Subject subject) {
    var actor = Actor.current();
    var retold =
        Event.WellKnownData.SOURCE_SYNC.equals(offered.data().get(Event.WellKnownData.SOURCE));
    Refusals.enforce(authority.decide(actor, offered.type(), subject, retold));
    return Event.Publisher.of(actor);
  }

  private static ApiException unheld(Event offered) {
    return new ApiException(
        ErrorCode.NOT_FOUND,
        "Event type '"
            + offered.type()
            + "' announces a message this box holds, and it holds none with id '"
            + Objects.toString(offered.data().get(MESSAGE_ID), "")
            + "'.",
        "Post through the messages route; the server announces a post itself.");
  }
}
