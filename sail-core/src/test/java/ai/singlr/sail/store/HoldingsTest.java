/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.store;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import ai.singlr.sail.config.SpecStatus;
import ai.singlr.sail.identity.Actor;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class HoldingsTest {

  @TempDir Path tempDir;
  private Sqlite db;

  @BeforeEach
  void setUp() {
    db = Sqlite.open(tempDir.resolve("main.db"));
    new SchemaManager(db).migrate();
  }

  @AfterEach
  void tearDown() {
    db.close();
  }

  /**
   * Erasure follows no room-to-spec link, so a spec born in a pruned room keeps naming it: an
   * erased conversation is gone on main before anything living in it is asked.
   */
  @Test
  void anErasedConversationIsGoneOnMainThoughASpecStillNamesIt() {
    Actor.run(
        Actor.system(),
        () -> {
          new RoomStore(db)
              .create(
                  new RoomStore.RoomRow(
                      "lab", "acme", "lab", "ada", null, null, null, null, null, null));
          new SpecStore(db)
              .create(
                  new SpecStore.SpecRow(
                          "child",
                          "acme",
                          "Child",
                          SpecStatus.PENDING,
                          "ada",
                          null,
                          null,
                          null,
                          null,
                          0,
                          null,
                          "",
                          "",
                          null,
                          List.of(),
                          List.of())
                      .withRoomId("lab"));
          new RoomStore(db).delete("lab");
          new ChangeLog(db).erase("room", "lab", "3-erased", ChangeLog.Entry.LOCAL);
        });

    assertInstanceOf(Standing.Gone.class, Holdings.main(db).conversation("lab"));
    assertInstanceOf(Standing.Gone.class, Holdings.node(db).conversation("lab"));
  }
}
