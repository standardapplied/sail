/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.commands;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.sail.api.Event;
import ai.singlr.sail.api.EventSubscriber;
import ai.singlr.sail.config.SyncConfig;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;
import org.junit.jupiter.api.Test;

/**
 * Server start: exactly one Slack notifier per fleet (main or a lone box narrates, a node never
 * does), and what a restart orphaned is failed by this box's machinery.
 */
class ServerStartCommandTest {

  @Test
  void mainNarratesSlack() {
    assertTrue(ServerStartCommand.narratesSlack(new SyncConfig("main", null, "uday")));
  }

  @Test
  void aStandaloneBoxNarratesItsOwnWork() {
    assertTrue(ServerStartCommand.narratesSlack(SyncConfig.unset()));
  }

  @Test
  void aNodePointedAtMainNeverPosts() {
    assertFalse(ServerStartCommand.narratesSlack(new SyncConfig("node", "sail@main", "uday")));
  }

  @Test
  void slackIsNarratedOnlyWhileTheBoxIsNotANodeReadForEachEvent() throws Exception {
    var sync = new AtomicReference<SyncConfig>(SyncConfig.unset());
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
        };
    var gated = ServerStartCommand.whileNarrating(reactor, sync::get);
    var first = Event.of("acme", "a", "spec_done", "uday", "box");
    var second = Event.of("acme", "b", "spec_done", "uday", "box");
    var third = Event.of("acme", "c", "spec_done", "uday", "box");

    gated.onEvent(first);
    sync.set(new SyncConfig(SyncConfig.ROLE_NODE, "sail@main", "eng"));
    gated.onEvent(second);
    sync.set(new SyncConfig(SyncConfig.ROLE_MAIN, null, "eng"));
    gated.onEvent(third);

    assertEquals(
        List.of(first, third), narrated, "a node's work is announced by main, never by it");
    assertEquals("slack", gated.name());
  }
}
