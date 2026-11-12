/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.sail.identity.Acting;
import ai.singlr.sail.store.HeldProjects;
import ai.singlr.sail.store.RoomStore;
import ai.singlr.sail.store.SchemaManager;
import ai.singlr.sail.store.Sqlite;
import com.sun.net.httpserver.HttpServer;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A process that is not the box's API server wakes its change-feed streams after each commit to the
 * change log, off the committing thread, once per burst, by a wake that carries nothing, and owes
 * nothing when there is no server to wake. A command passes its announcer to the databases it
 * opens; the server passes none.
 */
class CommitAnnouncerTest {

  private static final long WAIT_SECONDS = 10;

  @TempDir Path dir;
  private HttpServer server;
  private final List<String> told = new CopyOnWriteArrayList<>();
  private final CountDownLatch arrivedTwice = new CountDownLatch(2);
  private final Semaphore arrivals = new Semaphore(0);

  @BeforeEach
  void aServerThatIsWoken() throws IOException {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/v1/changes:wake",
        exchange -> {
          told.add(
              exchange.getRequestMethod() + " " + exchange.getRequestBody().readAllBytes().length);
          arrivedTwice.countDown();
          arrivals.release();
          var answer = "{\"woken\": true}".getBytes();
          exchange.sendResponseHeaders(200, answer.length);
          exchange.getResponseBody().write(answer);
          exchange.close();
        });
    server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
    server.start();
  }

  @AfterEach
  void stop() {
    server.stop(0);
  }

  @Test
  void aBurstOfCommitsWakesOnceAndOneDuringAWakeWakesAgainAfterIt() throws Exception {
    var connecting = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    var publisher = new SailEventPublisher("127.0.0.1", server.getAddress().getPort(), "token");
    try (var announcer =
        new CommitAnnouncer(
            () -> {
              connecting.countDown();
              release.await(WAIT_SECONDS, TimeUnit.SECONDS);
              return publisher;
            })) {
      announcer.run();
      assertTrue(connecting.await(WAIT_SECONDS, TimeUnit.SECONDS), "the first was never begun");
      for (var i = 0; i < 4; i++) {
        announcer.run();
      }
      release.countDown();
    }
    assertTrue(arrivedTwice.await(WAIT_SECONDS, TimeUnit.SECONDS), told.toString());

    assertEquals(List.of("POST 0", "POST 0"), told, "two wakes, each carrying nothing");
  }

  @Test
  void closingSendsTheWakeStillOwedAndSendsNothingAfter() throws Exception {
    var connects = new AtomicInteger();
    var publisher = new SailEventPublisher("127.0.0.1", server.getAddress().getPort(), "token");
    var announcer =
        new CommitAnnouncer(
            () -> {
              connects.incrementAndGet();
              return publisher;
            });
    announcer.run();
    announcer.close();
    assertTrue(arrivals.tryAcquire(WAIT_SECONDS, TimeUnit.SECONDS), "the owed wake was sent");

    announcer.run();
    announcer.close();
    assertEquals(1, connects.get(), "a closed announcer owes nothing");
  }

  @Test
  void withNoServerToWakeACommitIsUnaffectedAndTheWakeIsDropped() {
    var attempts = new AtomicInteger();
    try (var announcer =
        new CommitAnnouncer(
            () -> {
              attempts.incrementAndGet();
              throw new IOException("sail-api is not running here");
            })) {
      announcer.run();
    }
    try (var announcer =
        new CommitAnnouncer(
            () -> {
              attempts.incrementAndGet();
              throw new InterruptedException("stopping");
            })) {
      announcer.run();
    }

    assertEquals(2, attempts.get());
  }

  @Test
  void aServerThatTakesTheWakeAndNeverAnswersDelaysAnExitingCommandByNothing() throws Exception {
    try (var silent = new ServerSocket(0, 50, InetAddress.getLoopbackAddress())) {
      var request = new LinkedBlockingQueue<String>();
      Thread.ofVirtual()
          .start(
              () -> {
                try {
                  var taken = silent.accept();
                  var reader =
                      new BufferedReader(
                          new InputStreamReader(taken.getInputStream(), StandardCharsets.US_ASCII));
                  request.add(
                      reader.readLine() + " | " + reader.readLine() + " | " + reader.readLine());
                } catch (IOException closed) {
                  request.add("closed: " + closed);
                }
              });
      var announcer =
          new CommitAnnouncer(
              () -> new SailEventPublisher("127.0.0.1", silent.getLocalPort(), "a-token"));
      var started = System.nanoTime();
      announcer.run();
      announcer.close();
      var took = Duration.ofNanos(System.nanoTime() - started);

      assertTrue(took.compareTo(CommitAnnouncer.DRAIN) < 0, "it waited " + took);
      var sent = request.poll(WAIT_SECONDS, TimeUnit.SECONDS);
      assertTrue(sent != null && sent.startsWith("POST /v1/changes:wake HTTP/1.1"), sent);
      assertTrue(sent.contains("Authorization: Bearer a-token"), sent);
    }
    assertThrows(
        IllegalArgumentException.class, () -> new SailEventPublisher("127.0.0.1", 1, "a\r\nX: y"));
  }

  @Test
  void theBinarysAnnouncerOwesNothingUntilACommitAndClosesAtOnce() {
    var announcer = CommitAnnouncer.toThisBox();
    var started = System.nanoTime();
    announcer.close();
    assertTrue(
        System.nanoTime() - started < CommitAnnouncer.DRAIN.toNanos(),
        "with nothing owed it waits for nothing");
  }

  @Test
  void aProcessInterruptedAsItExitsStopsWaitingForTheWakeItOwes() throws Exception {
    var release = new CountDownLatch(1);
    var connecting = new CountDownLatch(1);
    var announcer =
        new CommitAnnouncer(
            () -> {
              connecting.countDown();
              release.await(WAIT_SECONDS, TimeUnit.SECONDS);
              throw new IOException("no server");
            });
    announcer.run();
    assertTrue(connecting.await(WAIT_SECONDS, TimeUnit.SECONDS));
    Thread.currentThread().interrupt();
    try {
      announcer.close();
      assertTrue(Thread.interrupted(), "the interrupt is kept for the caller");
    } finally {
      release.countDown();
    }
  }

  @Test
  void everyDatabaseACommandOpensRunsItsAnnouncerAfterEachCommitAndTheServersNone()
      throws Exception {
    var announced = new AtomicInteger();
    Runnable counting = announced::incrementAndGet;
    var path = dir.resolve("sail.db");
    try (var db = Sqlite.open(path)) {
      new SchemaManager(db).migrate();
      HeldProjects.hold(db, "acme");
    }
    ScopedValue.where(Sqlite.ANNOUNCING, counting)
        .run(
            () -> {
              try (var db = Sqlite.open(path)) {
                var rooms = new RoomStore(db);
                Acting.as(
                    "uday",
                    () -> {
                      db.transaction(
                          () -> {
                            rooms.create(room("one"));
                            rooms.create(room("two"));
                          });
                      rooms.create(room("three"));
                    });
              }
            });
    assertEquals(2, announced.get(), "once per commit");

    try (var db = Sqlite.open(path)) {
      Acting.as("uday", () -> new RoomStore(db).create(room("four")));
    }
    assertEquals(2, announced.get(), "a database opened with no announcer bound announces nothing");
  }

  private static RoomStore.RoomRow room(String id) {
    return new RoomStore.RoomRow(id, "acme", id, "uday", null, null, "uday", null, null, "uday");
  }

  private CommitAnnouncer announcer() {
    return new CommitAnnouncer(
        () -> new SailEventPublisher("127.0.0.1", server.getAddress().getPort(), "token"));
  }
}
