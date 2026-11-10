/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.access.baseline;

import ai.singlr.sail.identity.Actor;
import ai.singlr.sail.store.ChangeLog;
import ai.singlr.sail.store.HeldProjects;
import ai.singlr.sail.store.MessageStore;
import ai.singlr.sail.sync.SyncBox;
import ai.singlr.sail.sync.SyncTransportException;
import ai.singlr.sail.sync.SyncedEntities;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/**
 * A4, A5 and A6's snapshot: what each reader is shown is what the snapshot records ({@link
 * Baseline}, {@code core-reads}). Each member of the {@link Cast} reads every conversation's
 * messages through the store, so an agent's steering filter and a scoped reader's audience both
 * apply; and each sync session pulls a full round from main into a box of its own, which then
 * holds, or lacks, each row main holds.
 */
class ReadsBaselineTest {

  private static final List<String> CONVERSATIONS =
      List.of("lobby", "den", "hall", "s-ada", "s-bob", "b-room", "ghost");

  @Test
  void everyReaderIsShownWhatTheBaselineRecords() {
    try (var world = new World()) {
      var corpus = new Corpus(world.minted());
      for (var member : Cast.everyone()) {
        for (var conversation : CONVERSATIONS) {
          corpus.ask(
              "read messages of " + conversation + " as " + member.label(),
              () -> shown(world, member.actor(), conversation));
        }
      }
      for (var pusher : Cast.pushers()) {
        pulled(world, pusher, corpus);
      }
      Baseline.check("core-reads", corpus);
    }
  }

  private static String shown(World world, Actor reader, String conversation) {
    var shown =
        Actor.call(reader, () -> world.messages.list(conversation, null, 100)).stream()
            .map(MessageStore.MessageRow::id)
            .collect(Collectors.joining(", "));
    return shown.isEmpty() ? "shown nothing" : "shown " + shown;
  }

  /**
   * One full round of a box that syncs as {@code pusher} and starts with no row, and, per row main
   * holds, whether the box holds it after. A session that names no FDE is refused at the hello,
   * before any row is read, so each row's answer is that refusal: it receives nothing.
   */
  private static void pulled(World world, Cast.Member pusher, Corpus corpus) {
    try (var node =
        new SyncBox(Objects.requireNonNullElse(pusher.actor().handle(), "anonymous") + "-box")
            .syncsAs(pusher.actor())) {
      HeldProjects.hold(node.db, "beta");
      String failed = null;
      try {
        SyncBox.round(world.box, node);
      } catch (SyncTransportException refused) {
        failed = Corpus.refused(refused);
      }
      var mains = new ChangeLog(world.db);
      var nodes = new ChangeLog(node.db);
      for (var entity : SyncedEntities.all()) {
        var type = entity.type();
        for (var id : world.store(type).syncEntityIds()) {
          var held = nodes.head(type, id).isPresent();
          corpus.put(
              "pull " + type + " " + id + " to " + pusher.label(),
              failed != null
                  ? failed
                  : held ? "received " + mains.head(type, id).orElseThrow().kind() : "withheld");
        }
      }
    }
  }
}
