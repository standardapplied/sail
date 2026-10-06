/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.sail.engine.ShellExecutor;
import ai.singlr.sail.identity.Acting;
import ai.singlr.sail.identity.Actor;
import ai.singlr.sail.store.FdeStore;
import ai.singlr.sail.store.MessageStore;
import ai.singlr.sail.store.ProjectStore;
import ai.singlr.sail.store.ReviewStore;
import ai.singlr.sail.store.RoomStore;
import ai.singlr.sail.store.RunStore;
import ai.singlr.sail.store.SchemaManager;
import ai.singlr.sail.store.SpecStore;
import ai.singlr.sail.store.Sqlite;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Rooms are made on purpose: reading them creates none, for any FDE in any project, whether the box
 * syncs with nobody or is main or a node, whose reads freshen and whose writes propagate.
 */
class RoomsReadTest {

  @TempDir Path tempDir;
  private Sqlite db;
  private RoomStore roomStore;
  private final AtomicInteger rounds = new AtomicInteger();

  @BeforeEach
  void setUp() {
    db = Sqlite.open(tempDir.resolve("rooms.db"));
    new SchemaManager(db).migrate();
    roomStore = new RoomStore(db);
    Acting.system(
        () -> {
          var fdes = new FdeStore(db);
          fdes.add("uday", "Uday", null, "admin");
          fdes.add("rajesh", "Rajesh", null, "member");
          var projects = new ProjectStore(db);
          projects.upsert("acme", "name: acme\nagent:\n  type: claude-code\n");
          projects.upsert("nautilus", "name: nautilus\nagent:\n  type: codex\n");
        });
  }

  @AfterEach
  void tearDown() {
    db.close();
  }

  @Test
  void readingRoomsOnAStandaloneBoxCreatesNoRoom() throws Exception {
    readAsEveryFde(operations(SyncScheduler.disabled()));

    assertEquals(List.of(), roomStore.listAll());
  }

  @Test
  void readingRoomsOnASyncedBoxCreatesNoRoomAndPropagatesNothing() throws Exception {
    var synced =
        new SyncScheduler(
            rounds::incrementAndGet,
            Duration.ZERO,
            Duration.ofSeconds(15),
            new DirectExecutorService(),
            () -> 0L,
            duration -> {});

    readAsEveryFde(operations(synced));

    assertEquals(List.of(), roomStore.listAll());
    assertEquals(1, rounds.get(), "one freshen for the reads, and no write to propagate");
  }

  private void readAsEveryFde(SailOperations ops) {
    for (var handle : List.of("uday", "rajesh")) {
      for (var project : new String[] {"acme", "nautilus", null}) {
        var result = Actor.call(Actor.cliOperator(handle), () -> ops.rooms(project));
        assertTrue(result instanceof Result.Success<RoomsListResponse>, result.toString());
        assertEquals(List.of(), ((Result.Success<RoomsListResponse>) result).value().rooms());
      }
    }
  }

  private SailOperations operations(SyncScheduler scheduler) throws Exception {
    var yaml = tempDir.resolve("sail.yaml");
    Files.writeString(yaml, "name: acme\n");
    return new SailOperations(
            new ShellExecutor(false),
            TestProjects.reading(yaml),
            null,
            null,
            new SpecStore(db),
            new ReviewStore(db),
            new RunStore(db),
            new ProjectStore(db),
            scheduler,
            new FdeStore(db),
            SessionYield.NONE)
        .useMessages(new MessageStore(db))
        .useRooms(roomStore);
  }
}
