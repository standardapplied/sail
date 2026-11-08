/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.commands;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.sail.api.Event;
import ai.singlr.sail.api.EventSubscriber;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Predicate;
import org.junit.jupiter.api.Test;

/**
 * Server start: exactly one Slack notifier per fleet (main or a lone box narrates, a node never
 * does), and what a restart orphaned is failed by this box's machinery.
 */
class ServerStartCommandTest {

  @Test
  void slackIsNarratedOnlyWhileTheBoxSaysItNarratesAskedForEachEvent() throws Exception {
    var narrates = new AtomicBoolean(true);
    var narrated = new ArrayList<Event>();
    var reactor =
        new EventSubscriber() {
          @Override
          public String name() {
            return "slack";
          }

          @Override
          public Predicate<Event> filter() {
            return EventSubscriber.all();
          }

          @Override
          public void onEvent(Event event) {
            narrated.add(event);
          }

          @Override
          public void missed() {
            narrated.add(Event.of("acme", null, "missed", "uday", "box"));
          }

          @Override
          public void closed() {
            narrated.add(Event.of("acme", null, "closed", "uday", "box"));
          }
        };
    var gated = ServerStartCommand.whileNarrating(reactor, narrates::get);
    var first = Event.of("acme", "a", "spec_done", "uday", "box");
    var second = Event.of("acme", "b", "spec_done", "uday", "box");
    var third = Event.of("acme", "c", "spec_done", "uday", "box");

    gated.onEvent(first);
    narrates.set(false);
    gated.onEvent(second);
    narrates.set(true);
    gated.onEvent(third);

    assertEquals(
        List.of(first, third), narrated, "a node's work is announced by main, never by it");
    assertEquals("slack", gated.name());
    assertTrue(gated.filter().test(first), "the reactor's own filter decides what it is offered");
    gated.missed();
    gated.closed();
    assertEquals(
        List.of("missed", "closed"),
        narrated.subList(2, 4).stream().map(Event::type).toList(),
        "a dropped event and the end reach the reactor whatever the role");
  }
}
