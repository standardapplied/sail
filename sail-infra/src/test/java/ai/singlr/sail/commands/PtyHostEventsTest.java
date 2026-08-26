/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.commands;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.sail.store.EventStore;
import ai.singlr.sail.store.SchemaManager;
import ai.singlr.sail.store.Sqlite;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PtyHostEventsTest {

  @TempDir Path dir;

  @Test
  void theThreeSessionFactsBecomeRecordClassRows() {
    var path = dir.resolve("cp.db");
    try (var db = Sqlite.open(path)) {
      new SchemaManager(db).migrate();
    }
    var events = new PtyHostEvents(path);

    events.sessionStarted("lounge", "acme", "uday");
    events.sessionAttached("lounge", "acme", "mady");
    events.sessionEnded("lounge", "acme", "exited(0)");

    try (var db = Sqlite.open(path)) {
      var recent = new EventStore(db).recent(10);
      assertEquals(3, recent.size());
      assertTrue(
          recent.stream()
              .anyMatch(
                  e ->
                      e.type().equals("pty_session_started")
                          && e.agent().equals("uday")
                          && e.data().contains("lounge")));
      assertTrue(
          recent.stream()
              .anyMatch(e -> e.type().equals("pty_session_attached") && e.agent().equals("mady")));
      assertTrue(
          recent.stream()
              .anyMatch(
                  e -> e.type().equals("pty_session_ended") && e.data().contains("exited(0)")));
    }
  }
}
