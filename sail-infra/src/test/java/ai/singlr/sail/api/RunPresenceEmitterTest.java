/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.api;

import static org.junit.jupiter.api.Assertions.assertEquals;

import ai.singlr.sail.common.DateTimeUtils;
import ai.singlr.sail.store.RunStore;
import ai.singlr.sail.store.SchemaManager;
import ai.singlr.sail.store.Sqlite;
import java.nio.file.Path;
import java.time.Instant;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RunPresenceEmitterTest {

  @TempDir Path tempDir;
  private Sqlite db;
  private RunStore runStore;
  private EventBus bus;
  private final AtomicReference<Instant> clock =
      new AtomicReference<>(Instant.parse("2026-08-13T12:00:00Z"));
  private RunPresenceEmitter emitter;

  @BeforeEach
  void setUp() {
    db = Sqlite.open(tempDir.resolve("test.db"));
    new SchemaManager(db).migrate();
    runStore = new RunStore(db);
    bus = new EventBus();
    emitter = new RunPresenceEmitter(runStore, bus, () -> "node-a", clock::get);
  }

  @AfterEach
  void tearDown() {
    emitter.close();
    bus.close();
    if (db != null) db.close();
  }

  private String runningRunOn(String node) {
    var id = DateTimeUtils.newId().toString();
    return runStore.create(
        id,
        "backend",
        "auth",
        node,
        node,
        "build",
        "claude-code",
        "feat/x",
        "do it",
        123,
        null,
        "/home/dev/.sail/runs/" + id + "/agent.log",
        "sail-agent-" + id);
  }

  private void stampAt(String id, Instant at) {
    db.execute("UPDATE runs SET last_activity_at = ? WHERE id = ?", at.toString(), id);
  }

  private Instant now() {
    return clock.get();
  }

  @Test
  void aRunWithNoActivityStampEmitsNothing() {
    runningRunOn("node-a");

    assertEquals(0, emitter.sweep(), "presence is never guessed for an unstamped run");
  }

  @Test
  void aWorkingRunSeedsSilentlyAndOnlyTheQuietCrossingEmits() throws Exception {
    var id = runningRunOn("node-a");
    stampAt(id, now().minusSeconds(10));
    var received = new ConcurrentLinkedQueue<Event>();
    var delivered = new CountDownLatch(1);
    bus.subscribe(
        new EventSubscriber() {
          @Override
          public String name() {
            return "recorder";
          }

          @Override
          public Predicate<Event> filter() {
            return e -> Event.WellKnownTypes.AGENT_PRESENCE.equals(e.type());
          }

          @Override
          public void onEvent(Event event) {
            received.add(event);
            delivered.countDown();
          }
        });

    assertEquals(0, emitter.sweep(), "working is the default assumption — no edge on first sight");
    assertEquals(0, emitter.sweep());

    clock.set(now().plus(RunPresence.THRESHOLD).plusSeconds(60));
    assertEquals(1, emitter.sweep(), "the quiet crossing emits");
    assertEquals(0, emitter.sweep(), "once per crossing, never per pass");

    BusTesting.awaitDelivery(delivered);
    var event = received.poll();
    assertEquals("quiet", event.data().get("presence"));
    assertEquals(id, event.data().get(Event.WellKnownData.RUN_ID));
    assertEquals("build", event.data().get(Event.WellKnownData.RUN_ROLE));
    assertEquals("backend", event.project());
    assertEquals("auth", event.spec());
  }

  @Test
  void resumedActivityEmitsWorkingExactlyOnce() {
    var id = runningRunOn("node-a");
    stampAt(id, now().minus(RunPresence.THRESHOLD).minusSeconds(60));
    assertEquals(1, emitter.sweep(), "first sight already past the threshold narrates the alarm");

    stampAt(id, now());
    assertEquals(1, emitter.sweep(), "the resume crossing emits working");
    assertEquals(0, emitter.sweep());
    assertEquals(2, bus.publishedCount(), "quiet then working — transitions only, no heartbeats");
  }

  @Test
  void aForeignRunsEdgesBelongToItsExecutingBox() {
    var id = runningRunOn("node-b");
    stampAt(id, now().minus(RunPresence.THRESHOLD).minusSeconds(60));

    assertEquals(
        0,
        emitter.sweep(),
        "a synced stamp is only as fresh as the last sync — narrating quiet from it would be"
            + " noise");
  }

  @Test
  void aFinishedRunDropsItsEdgeStateInsteadOfEmitting() {
    var id = runningRunOn("node-a");
    stampAt(id, now().minus(RunPresence.THRESHOLD).minusSeconds(60));
    assertEquals(1, emitter.sweep());

    runStore.complete(id, "completed", 0);

    assertEquals(0, emitter.sweep(), "terminal runs have no presence and no edges");
  }

  @Test
  void startAndCloseAreIdempotentLifecycle() {
    emitter.start();
    emitter.close();
  }
}
