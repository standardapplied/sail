/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.commands;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.sail.config.SyncConfig;
import ai.singlr.sail.identity.Acting;
import ai.singlr.sail.identity.Actor;
import ai.singlr.sail.store.ChangeLog;
import ai.singlr.sail.store.ReviewStore;
import ai.singlr.sail.store.RunStore;
import ai.singlr.sail.store.SchemaManager;
import ai.singlr.sail.store.Sqlite;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Server start: exactly one Slack notifier per fleet (main or a lone box narrates, a node never
 * does), and what a restart orphaned is failed by this box's machinery.
 */
class ServerStartCommandTest {

  @Test
  void aRestartsOrphanedReviewsAreFailedByThisBoxsMachinery(@TempDir Path dir) {
    try (var db = Sqlite.open(dir.resolve("sail.db"))) {
      new SchemaManager(db).migrate();
      var reviews = new ReviewStore(db);
      var runs = new RunStore(db);
      var review =
          Acting.as(
              "uday",
              () -> {
                var id = reviews.createReview("auth", 1);
                reviews.updateReviewStatus(id, "running");
                return id;
              });
      Acting.as(
          "uday",
          () ->
              runs.createReview(
                  "review-run",
                  "acme",
                  "auth",
                  "node-a",
                  "uday",
                  "claude-code",
                  "main",
                  "review it",
                  "/tmp/review.log",
                  "sail-review-run"));

      var orphans = ServerStartCommand.failOrphans(reviews, runs, "node-a");

      assertEquals(new ServerStartCommand.Orphans(1, 1), orphans);
      var log = new ChangeLog(db);
      assertEquals(Actor.SYSTEM_HANDLE, log.head("review", review).orElseThrow().actor());
      assertEquals(Actor.SYSTEM_HANDLE, log.head("run", "review-run").orElseThrow().actor());
    }
  }

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
}
