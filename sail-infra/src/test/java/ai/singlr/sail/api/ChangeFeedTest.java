/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import ai.singlr.sail.config.SpecStatus;
import ai.singlr.sail.config.SyncConfig;
import ai.singlr.sail.config.YamlUtil;
import ai.singlr.sail.engine.ScriptedShellExecutor;
import ai.singlr.sail.engine.ShellExec;
import ai.singlr.sail.engine.SyncOperations;
import ai.singlr.sail.identity.Acting;
import ai.singlr.sail.store.AuthSessionStore;
import ai.singlr.sail.store.ChangeLog;
import ai.singlr.sail.store.Erasure;
import ai.singlr.sail.store.FdeStore;
import ai.singlr.sail.store.FeedFloors;
import ai.singlr.sail.store.FeedPositions;
import ai.singlr.sail.store.HeldProjects;
import ai.singlr.sail.store.MessageStore;
import ai.singlr.sail.store.RoomStore;
import ai.singlr.sail.store.RunStore;
import ai.singlr.sail.store.SchemaManager;
import ai.singlr.sail.store.SpecStore;
import ai.singlr.sail.store.Sqlite;
import com.sun.net.httpserver.HttpServer;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.time.InstantSource;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/**
 * A client that was away asks main for exactly what changed since the last thing it saw. Every
 * scenario runs through the server main runs ({@link SailApiServer}: its router and its event
 * stream) over the production stores, and every wait is bounded; nothing sleeps.
 */
@Timeout(value = 2, unit = TimeUnit.MINUTES)
class ChangeFeedTest {

  private static final SyncConfig MAIN = new SyncConfig(SyncConfig.ROLE_MAIN, null, "uday", "main");
  private static final long WAIT_SECONDS = 10;

  /** How long the 200,000-change replay may take to deliver a write made during it. */
  private static final long REPLAY_SECONDS = 240;

  @TempDir Path dir;
  private Box box;
  private String uday;

  @BeforeEach
  void aMainHoldingAcmeAndBeta() throws Exception {
    box = new Box(dir, UnaryOperator.identity());
    uday = box.person("uday", "admin");
    box.send("POST", "/v1/rooms", uday, room("acme-room", "acme"));
    box.send("POST", "/v1/specs", uday, spec("acme-spec", "acme", "acme-room"));
  }

  @AfterEach
  void close() {
    box.close();
  }

  @Test
  void aClientThatReadTheBoardIsAnsweredTheThreeChangesSinceInOrder() throws Exception {
    var read = box.send("GET", "/v1/specs", uday, null);
    var position = seq(read);

    box.send("PUT", "/v1/specs/acme-spec", uday, Map.of("title", "Edited"));
    var posted = box.post("acme-room", uday, "hello");
    box.renameRoom("acme-room");

    var page = box.changes(uday, position);
    assertEquals(List.of("spec acme-spec", "message " + posted, "room acme-room"), names(page));
    assertEquals(seqs(page), seqs(page).stream().sorted().toList());
    assertEquals((long) seqs(page).getLast(), at(page.get("next")));
    assertEquals(false, page.get("more"));
    assertEquals("Edited", row(page, 0).get("title"));
    assertTrue(
        position.matches("[0-9a-f-]+\\.[0-9]+\\.[0-9a-f]{12}"),
        "a position names the database, the seq and the entry standing at it: " + position);
  }

  @Test
  void positionsSurviveARestart() throws Exception {
    var position = seq(box.send("GET", "/v1/specs", uday, null));
    box.send("PUT", "/v1/specs/acme-spec", uday, Map.of("title", "Before"));
    var first = box.post("acme-room", uday, "before the restart");
    box.renameRoom("acme-room");

    box.restart();
    box.send("POST", "/v1/specs", uday, spec("acme-later", "acme", "acme-room"));
    var second = box.post("acme-room", uday, "after the restart");

    assertEquals(
        List.of(
            "spec acme-spec",
            "message " + first,
            "room acme-room",
            "spec acme-later",
            "message " + second),
        names(box.changes(uday, position)));
  }

  @Test
  void aPositionFromAReinstalledBoxOrFromBeforeARestoreIsToldToResyncNeverServed()
      throws Exception {
    var reinstalled = seq(box.send("GET", "/v1/specs", uday, null));
    box.reinstall();
    uday = box.person("uday", "admin");
    box.send("POST", "/v1/rooms", uday, room("acme-room", "acme"));
    box.send("POST", "/v1/specs", uday, spec("acme-spec", "acme", "acme-room"));
    box.post("acme-room", uday, "the same seqs, another database");
    assertTrue(at(seq(box.send("GET", "/v1/specs", uday, null))) >= at(reinstalled));

    var backup = dir.resolve("backup.db");
    box.backup(backup);
    box.post("acme-room", uday, "after the backup");
    var restored = seq(box.send("GET", "/v1/specs", uday, null));
    box.restore(backup);
    box.post("acme-room", uday, "written again after the restore");
    box.post("acme-room", uday, "and again");
    assertTrue(at(seq(box.send("GET", "/v1/specs", uday, null))) >= at(restored));

    for (var position : List.of(reinstalled, restored)) {
      var refused = box.send("GET", "/v1/changes?since=" + position, uday, null);
      assertEquals(409, refused.statusCode(), position + " " + refused.body());
      assertEquals("resync_required", error(refused).get("code"));
      var stream = box.stream(uday, "/v1/events/stream", position);
      assertEquals(seq(box.send("GET", "/v1/specs", uday, null)), stream.resync());
    }
  }

  @Test
  void aPositionOnAFloorRaisedAgainStillAnswersAReaderItDoesNotBind() throws Exception {
    box.send("POST", "/v1/specs", uday, spec("acme-second", "acme", "acme-room"));
    box.send("PUT", "/v1/specs/acme-spec", uday, Map.of("project", "beta"));
    var onTheFloor = seq(box.send("GET", "/v1/specs", uday, null));

    box.send("PUT", "/v1/specs/acme-second", uday, Map.of("project", "beta"));

    assertEquals(
        List.of("spec acme-second"),
        names(box.changes(uday, onTheFloor)).stream().filter(n -> n.startsWith("spec")).toList(),
        "the floor entry the position stood on moved on, and a reader of every project goes on");
  }

  @Test
  void anOldClientsBareIdIsToldToResyncOnceAndIsNeverReplayedTheBoard() throws Exception {
    for (var i = 0; i < 30; i++) {
      box.post("acme-room", uday, "history " + i);
    }
    var newest = seq(box.send("GET", "/v1/specs", uday, null));

    var stream = box.stream(uday, "/v1/events/stream", "3");
    var resync = stream.resync();
    var live = box.post("acme-room", uday, "live");

    assertEquals(newest, resync);
    assertEquals(List.of(live), ids(stream.changes(1)));
    assertEquals(1, stream.read.stream().filter(f -> f.startsWith("event: resync")).count());
    assertEquals(
        1, stream.read.stream().filter(f -> f.startsWith("event: change")).count(), "no replay");
    assertEquals(newest, stream.ids().getFirst(), "the resync moved the cursor to the newest");
  }

  @Test
  void aStreamResumedFromAPositionIsSentEveryChangeOnceInOrderIncludingOneCommittedMidReplay()
      throws Exception {
    var midReplay = new CountDownLatch(1);
    var once = new AtomicBoolean();
    box.close();
    box =
        new Box(
            dir,
            operations ->
                feeding(
                    operations,
                    feed ->
                        new Paging(
                            feed,
                            ask -> {
                              var page = feed.page(new ChangeFeed.Ask(ask.since(), ask.types(), 2));
                              if (once.compareAndSet(false, true)) {
                                Acting.as(
                                    "uday",
                                    () -> {
                                      box.messages()
                                          .append("acme-room", "uday", "mid replay", null);
                                    });
                                midReplay.countDown();
                              }
                              return page;
                            })));
    var position = seq(box.send("GET", "/v1/specs", uday, null));
    var a = box.post("acme-room", uday, "a");
    var b = box.post("acme-room", uday, "b");
    var c = box.post("acme-room", uday, "c");

    var stream = box.stream(uday, "/v1/events/stream", position);
    assertTrue(midReplay.await(WAIT_SECONDS, TimeUnit.SECONDS), "the replay never read a page");
    var replayed = stream.changesOf("message", 4);
    var live = box.post("acme-room", uday, "live");
    var after = stream.changesOf("message", 1);

    var bodies = new ArrayList<String>();
    for (var change : replayed) {
      bodies.add(((Map<?, ?>) change.get("row")).get("body").toString());
    }
    assertEquals(List.of("a", "b", "c", "mid replay"), bodies);
    assertEquals(List.of(a, b, c), ids(replayed).subList(0, 3));
    assertEquals(List.of(live), ids(after));
    var all = new ArrayList<>(seqsOf(replayed));
    all.addAll(seqsOf(after));
    assertEquals(all, all.stream().sorted().distinct().toList(), "each once, in order");
    assertEquals(
        all,
        stream.ids().stream().map(ChangeFeedTest::at).distinct().toList(),
        "each frame's id is the position of its seq");
  }

  @Test
  void aRowChangedThreeTimesIsServedOnceAsItIsNowAtItsNewestSeq() throws Exception {
    var position = seq(box.send("GET", "/v1/specs", uday, null));
    box.send("PUT", "/v1/specs/acme-spec", uday, Map.of("title", "One"));
    box.send("PUT", "/v1/specs/acme-spec", uday, Map.of("title", "Two"));
    box.send("PUT", "/v1/specs/acme-spec", uday, Map.of("title", "Three"));
    var newest = seq(box.send("GET", "/v1/specs", uday, null));

    var page = box.changes(uday, position);

    assertEquals(List.of("spec acme-spec"), names(page), "a change to one row tells no other");
    assertEquals("Three", row(page, 0).get("title"));
    assertEquals(at(newest), (long) seqs(page).getFirst());
  }

  @Test
  void aScopedViewersFeedAndStreamAreTheSameBytesWhetherOrNotBetaIsBusy() throws Exception {
    var vik = box.person("vik", "viewer");
    box.scope("vik", "acme");
    var position = seq(box.send("GET", "/v1/specs", vik, null));
    box.post("acme-room", uday, "for acme");
    box.send("PUT", "/v1/specs/acme-spec", uday, Map.of("title", "Acme's"));
    var quiet = box.sweep(vik, position);

    box.send("POST", "/v1/rooms", uday, room("beta-room", "beta"));
    box.send("POST", "/v1/specs", uday, spec("beta-spec", "beta", "beta-room"));
    box.post("beta-room", uday, "for beta");
    box.send("PUT", "/v1/specs/beta-spec", uday, Map.of("title", "Beta's"));
    box.send("POST", "/v1/rooms", uday, room("beta-gone", "beta"));
    assertEquals(200, box.send("DELETE", "/v1/rooms/beta-gone", uday, null).statusCode());
    var busy = box.sweep(vik, position);

    assertEquals(quiet.get("stream"), busy.get("stream"));
    assertEquals(
        quiet.get("feed").replaceAll("\"next\": \"[^\"]+\"", ""),
        busy.get("feed").replaceAll("\"next\": \"[^\"]+\"", ""),
        "the same changes; only the position may skip");
    assertFalse(String.join("\n", busy.values()).contains("beta"), busy.toString());
  }

  @Test
  void aRevokedMembersOpenStreamEndsBeforeAnythingOfAcmeAndTheirOldPositionIsToldToResync()
      throws Exception {
    var mia = box.person("mia", "member");
    box.scope("mia", "acme", "beta");
    var position = seq(box.send("GET", "/v1/specs", mia, null));
    var stream = box.stream(mia, "/v1/events/stream", position);
    var before = box.post("acme-room", uday, "before the revoke");
    assertEquals(List.of(before), ids(stream.changesOf("message", 1)), "past its first page");

    box.revoke("mia", "acme");
    var resync = stream.resync();
    var after = stream.ended();
    box.send("PUT", "/v1/specs/acme-spec", uday, Map.of("title", "After the revoke"));
    box.post("acme-room", uday, "after the revoke");

    assertEquals(List.of(), after, "nothing follows the resync: the stream ended");
    assertTrue(at(resync) > at(position), "the stream names where to read from afresh");
    var refused = box.send("GET", "/v1/changes?since=" + position, mia, null);
    assertEquals(409, refused.statusCode());
    assertEquals("resync_required", error(refused).get("code"));
    assertTrue(at(seq(refused)) >= at(resync));
    var afresh = box.send("GET", "/v1/specs", mia, null);
    assertEquals(List.of(), specIds(afresh));
    assertTrue(
        box.changes(mia, seq(afresh)).get("changes") instanceof List<?> none && none.isEmpty());
  }

  @Test
  void aStreamIsReadAsItsReaderIsNowWhetherItsClockMovesOrNever() throws Exception {
    var start = Instant.parse("2026-11-12T09:00:00Z");
    for (var moving : List.of(false, true)) {
      var clock = new ManualClock(start, Duration.ofDays(1));
      var base = box.clocked(clock, box.operations().changes());
      var revoked = "revoked-" + moving;
      var disabled = "disabled-" + moving;
      var revokedToken = box.person(revoked, "member");
      box.scope(revoked, "acme", "beta");
      var disabledToken = box.person(disabled, "member");
      var revokedStream = box.stream(base, "/v1/events/stream", revokedToken, null);
      var disabledStream = box.stream(base, "/v1/events/stream", disabledToken, null);
      var before = box.post("acme-room", uday, "before " + moving);
      for (var stream : List.of(revokedStream, disabledStream)) {
        assertEquals(List.of(before), ids(stream.changesOf("message", 1)), "past its first page");
      }

      if (moving) {
        clock.advance(Duration.ofSeconds(2));
      }
      box.revoke(revoked, "acme");
      box.disable(disabled);
      if (moving) {
        clock.advance(Duration.ofSeconds(2));
      }
      box.send("PUT", "/v1/specs/acme-spec", uday, Map.of("title", "SECRET " + moving));
      box.post("acme-room", uday, "SECRET " + moving);

      var revokedFrames = new ArrayList<>(List.of(revokedStream.resyncFrame()));
      revokedFrames.addAll(revokedStream.ended());
      var disabledFrames = disabledStream.ended();
      for (var frames : List.of(revokedFrames, disabledFrames)) {
        assertTrue(
            frames.stream().noneMatch(frame -> frame.startsWith("event: change")),
            "moving " + moving + ": " + frames);
        assertTrue(frames.stream().noneMatch(frame -> frame.contains("SECRET")), frames.toString());
      }
      assertTrue(disabledFrames.getLast().startsWith("event: error"), disabledFrames.toString());
    }
  }

  @Test
  void readersOfTheProjectsARowLeftAndEnteredResyncAndThenTheirFeedsAgreeWithTheirLists()
      throws Exception {
    box.send("POST", "/v1/rooms", uday, room("acme-hall", "acme"));
    var asked = box.post("acme-hall", uday, "a thread");
    box.reply("acme-hall", uday, "an answer", asked);
    box.post("acme-room", uday, "said under the spec");
    var readers = new LinkedHashMap<String, String>();
    for (var handle : List.of("vik", "bea", "ada")) {
      readers.put(handle, box.person(handle, "viewer"));
    }
    box.scope("vik", "acme");
    box.scope("bea", "beta");
    box.scope("ada", "acme", "beta");
    readers.put("uday", uday);
    var views = new LinkedHashMap<String, View>();
    var listed = new LinkedHashMap<String, Set<String>>();
    for (var reader : readers.entrySet()) {
      var view = box.view(reader.getValue());
      views.put(reader.getKey(), view);
      listed.put(reader.getKey(), new HashSet<>(view.rows().keySet()));
    }

    box.send("PUT", "/v1/specs/acme-spec", uday, Map.of("project", "beta"));
    box.moveRoom("acme-hall", "beta");

    for (var reader : List.of("vik", "bea", "ada")) {
      var refused =
          box.send(
              "GET",
              "/v1/changes?since=" + views.get(reader).position(),
              readers.get(reader),
              null);
      assertEquals(409, refused.statusCode(), reader + " " + refused.body());
    }
    var told = new LinkedHashMap<String, Set<String>>();
    for (var reader : readers.keySet()) {
      told.put(reader, new HashSet<>());
      views.put(reader, box.follow(readers.get(reader), views.get(reader), told.get(reader)));
      listed.get(reader).addAll(views.get(reader).rows().keySet());
    }
    assertSameRows(
        parsed(box.view(uday).rows()), derived(views.get("uday").rows()), "a reader of all");

    box.post("acme-hall", uday, "said in beta");
    box.reply("acme-hall", uday, "a late answer", asked);
    box.send("PUT", "/v1/specs/acme-spec", uday, Map.of("title", "edited in beta"));
    box.post("acme-room", uday, "still acme's");
    box.send("POST", "/v1/specs", uday, spec("acme-new", "acme", "acme-room"));

    for (var reader : readers.entrySet()) {
      var handle = reader.getKey();
      var followed = box.follow(reader.getValue(), views.get(handle), told.get(handle));
      var lists = box.view(reader.getValue());
      listed.get(handle).addAll(lists.rows().keySet());
      assertSameRows(
          parsed(lists.rows()),
          derived(followed.rows()),
          handle + "'s feed-built view, derived, and lists");
      var strangers = new HashSet<>(told.get(handle));
      strangers.removeAll(listed.get(handle));
      assertEquals(Set.of(), strangers, handle + " was told of rows it could never read");
    }
    assertFalse(
        box.view(readers.get("vik")).rows().containsKey("room acme-hall"),
        "the room left acme, and vik with it");
  }

  @Test
  void aChangeToOneRowNeverReSendsAnotherNorResyncsAReaderItCannotReach() throws Exception {
    var vik = box.person("vik", "viewer");
    box.scope("vik", "acme");
    var bea = box.person("bea", "viewer");
    box.scope("bea", "beta");
    var asked = box.post("acme-room", uday, "a thread before the move");
    box.moveRoom("acme-room", "beta");
    var vikAt = seq(box.send("GET", "/v1/specs", vik, null));
    var beaHeld = box.view(bea);
    var quiet = box.send("GET", "/v1/changes?since=" + vikAt, vik, null).body();

    box.reply("acme-room", uday, "a reply in the old thread", asked);

    assertEquals(
        quiet.replaceAll("\"next\": \"[^\"]+\"", ""),
        box.send("GET", "/v1/changes?since=" + vikAt, vik, null)
            .body()
            .replaceAll("\"next\": \"[^\"]+\"", ""),
        "nothing of the reply, nor of a row it speaks in, only the position moves");
    var beaAnswer = box.send("GET", "/v1/changes?since=" + beaHeld.position(), bea, null);
    assertEquals(200, beaAnswer.statusCode(), "no reader of beta reads it, so none resyncs");
    assertEquals(
        parsed(box.view(bea).rows()),
        derived(box.follow(bea, beaHeld, new HashSet<>()).rows()),
        "bea's feed-built view, derived, is her lists");
  }

  @Test
  void aRoomsWakeChangeReachesTheSpecsLivingInItThroughTheRoomRowAClientHolds() throws Exception {
    var held = box.view(uday);
    box.renameRoom("acme-room");

    var page = box.changes(uday, held.position());
    var followed = box.follow(uday, held, new HashSet<>());

    assertEquals(List.of("room acme-room"), names(page), "the room alone changed");
    assertFalse(row(page, 0).containsKey("spec_ids"), "a fed room carries no derived field");
    assertFalse(row(page, 0).containsKey("needs_reply"));
    assertEquals(parsed(box.view(uday).rows()), derived(followed.rows()));
    assertEquals("off", derived(followed.rows()).get("spec acme-spec").get("wake"));
    box.send("PUT", "/v1/specs/acme-spec", uday, Map.of("title", "told as its own row"));
    var spec = row(box.changes(uday, held.position()), 1);
    assertEquals("told as its own row", spec.get("title"));
    assertFalse(spec.containsKey("wake"), "a fed spec carries none of its room's fields");
  }

  @Test
  void anErasedSpecIsServedAsErasedWithItsIdAndTypeAndNoContent() throws Exception {
    box.send("POST", "/v1/rooms", uday, room("acme-other", "acme"));
    var position = seq(box.send("GET", "/v1/specs", uday, null));
    box.send("PUT", "/v1/specs/acme-spec", uday, Map.of("status", SpecStatus.CANCELLED.wire()));
    var pruned =
        box.send(
            "POST", "/v1/specs:prune", uday, Map.of("ids", List.of("acme-spec"), "dry_run", false));
    assertEquals(200, pruned.statusCode(), pruned.body());

    var page = box.changes(uday, position);
    var spec =
        ((List<?>) page.get("changes"))
            .stream()
                .map(Map.class::cast)
                .filter(change -> "spec".equals(change.get("type")))
                .toList();

    assertEquals(1, spec.size(), page.toString());
    assertEquals(Set.of("seq", "type", "id", "erased"), spec.getFirst().keySet());
    assertEquals("acme-spec", spec.getFirst().get("id"));
    assertEquals(true, spec.getFirst().get("erased"));
  }

  @Test
  void anErasedMessageIsToldToAReaderOfItsRoomButNeverToOneWhoHearsOnlyItsSteering()
      throws Exception {
    box.person("eng", "member");
    box.scope("eng", "acme");
    box.send("PUT", "/v1/specs/acme-spec", uday, Map.of("assignee", "eng"));
    var mia = box.person("mia", "member");
    var position = seq(box.send("GET", "/v1/specs", uday, null));
    var comment = box.post("acme-room", mia, "a comment for people");
    var steering = box.post("acme-room", uday, "an admin steers it");
    var latest = box.post("acme-room", uday, "said after both, so neither was the room's latest");
    for (var id : List.of(comment, steering)) {
      Acting.as(
          "uday",
          () -> new Erasure(box.db()).erase(List.of(new Erasure.Target("message", id)), "local"));
    }
    var credential = box.runCredential("acme-spec", "eng");

    var people = box.changes(uday, position);
    var agent = box.askedThroughTheSocket(credential, position);

    assertEquals(
        List.of("message " + comment, "message " + steering),
        names(people).stream()
            .filter(name -> name.startsWith("message") && !name.endsWith(latest))
            .toList());
    assertEquals(
        List.of(),
        names(agent).stream()
            .filter(name -> name.startsWith("message") && !name.endsWith(latest))
            .toList(),
        "one who hears only steering is told no end of a voice it can no longer weigh");
  }

  @Test
  void aPositionPastTheNewestOrNoPositionAtAllIsToldToResyncOnTheFeedAndTheStream()
      throws Exception {
    var newest = seq(box.send("GET", "/v1/specs", uday, null));
    var parts = newest.split("\\.");
    var past = parts[0] + "." + (Long.parseLong(parts[1]) + 1000) + "." + parts[2];

    for (var position : List.of(past, "spec-a1b2", "0")) {
      var refused = box.send("GET", "/v1/changes?since=" + position, uday, null);
      assertEquals(409, refused.statusCode(), refused.body());
      assertEquals("resync_required", error(refused).get("code"));
      assertEquals(newest, seq(refused));

      var stream = box.stream(uday, "/v1/events/stream", position);
      var frame = stream.resyncFrame();
      assertTrue(frame.contains("\nid: " + newest + "\n"), "the frame moves the cursor: " + frame);
      var live = box.post("acme-room", uday, "after the resync");
      assertEquals(List.of(live), ids(stream.changes(1)), "the stream goes on from the newest");
      newest = seq(box.send("GET", "/v1/specs", uday, null));
    }
  }

  @Test
  void aChangeReachesAWaitingStreamAsItCommitsAndAnIdleStreamReadsNothingAsMinutesPass()
      throws Exception {
    var pages = new AtomicInteger();
    var feed = box.operations().changes();
    var clock = new ManualClock(Instant.parse("2026-11-12T09:00:00Z"), Duration.ofDays(1));
    var checking = new AtomicLong();
    var base =
        box.clocked(
            clock,
            new Paging(
                feed,
                ask -> {
                  pages.incrementAndGet();
                  return feed.page(ask);
                }),
            exchange -> {
              var before = box.statements();
              try {
                TestAuth.sessions(box.db(), MAIN).require(exchange);
              } finally {
                checking.addAndGet(box.statements() - before);
              }
            });
    var stream = box.stream(base, "/v1/events/stream", uday, null);
    box.renameRoom("acme-room");
    assertEquals(List.of("acme-room"), ids(stream.changes(1)), "the clock never moved");
    delta("settled");
    stream.awaitNew("\"agent\": \"settled\"");
    var read = pages.get();
    var idle = box.statements();
    var checked = checking.get();

    for (var minute = 0; minute < 5; minute++) {
      delta("idle-" + minute);
      stream.awaitNew("\"agent\": \"idle-" + minute + "\"");
      clock.advance(Duration.ofMinutes(1));
      stream.awaitNew(": keepalive");
    }

    assertEquals(read, pages.get(), "no page, so no freshen and no sync round, while idle");
    assertEquals(
        checking.get() - checked,
        box.statements() - idle,
        "the only statements an idle stream runs are its heartbeats' checks of its caller");
  }

  @Test
  void aChangeAnotherProcessWritesReachesAWaitingStreamWhenThatProcessAnnouncesIt()
      throws Exception {
    var clock = new ManualClock(Instant.parse("2026-11-12T09:00:00Z"), Duration.ofDays(1));
    var stream =
        box.stream(box.clocked(clock, box.operations().changes()), "/v1/events/stream", uday, null);
    try (var other = Sqlite.open(dir.resolve("sail.db"));
        var announcer =
            new CommitAnnouncer(() -> new SailEventPublisher("127.0.0.1", box.port(), uday))) {
      var told =
          Acting.as(
              "uday", () -> new MessageStore(other).append("acme-room", "uday", "told", null));
      announcer.run();

      assertEquals(List.of(told.id()), ids(stream.changes(1)), "the clock never moved");
    }
  }

  @Test
  void aStreamResumesFromAfterAsFromItsLastIdAndRefusesATypeTheFeedDoesNotServe() throws Exception {
    var position = seq(box.send("GET", "/v1/specs", uday, null));
    var posted = box.post("acme-room", uday, "after the position");

    var after = box.stream(uday, "/v1/events/stream?after=" + position, (String) null);

    assertEquals(List.of(posted), ids(after.changes(1)));
    var refused = box.send("GET", "/v1/events/stream?types=run", uday, null);
    assertEquals(422, refused.statusCode(), refused.body());
    assertEquals("invalid_request", error(refused).get("code"));
  }

  @Test
  void aBoxWithNoChangeLogFeedsNothingAndKnowsOnePositionItsNewest() {
    var none = ChangeFeed.NONE;
    var newest = none.newest();
    var all = ChangeFeed.Ask.of(Map.of("since", newest));

    assertEquals(new ChangeFeed.Page(List.of(), newest, false), none.page(all));
    var resync = assertThrows(ChangeFeed.Resync.class, () -> none.page(all.after("1")));
    assertEquals(
        "This box keeps no change log, so no position is one of its own.", resync.reason());
    assertEquals(newest, resync.newest());
    assertEquals(ChangeFeed.Resync.ACTION, resync.remedy().plain());
    assertEquals(new ChangeFeed.Positioned<>("read", newest), none.at(() -> "read"));
    none.watch(() -> fail("a box that keeps no change log has nothing to wake")).close();
    none.wake();
    assertThrows(
        IllegalArgumentException.class,
        () -> new ChangeFeed.Change(1, "spec", "s", null, ChangeFeed.Fate.ROW, newest));
    assertThrows(IllegalArgumentException.class, () -> all.after(" "));
    try (var db = Sqlite.openMemory()) {
      assertThrows(
          IllegalArgumentException.class,
          () ->
              new JournalFeed(
                  new ChangeLog(db),
                  new FeedPositions(db),
                  new FeedFloors(db),
                  Map.of(),
                  () -> {}));
    }
  }

  @Test
  void fiveThousandChangesArePagedAtMostTheLimitAtATimeEachRowOnce() throws Exception {
    var position = seq(box.send("GET", "/v1/specs", uday, null));
    box.inOneTransaction(
        () ->
            Acting.as(
                "uday",
                () -> {
                  for (var i = 0; i < 5000; i++) {
                    box.messages().append("acme-room", "uday", "m" + i, null);
                  }
                }));

    var seen = new ArrayList<String>();
    var sizes = new ArrayList<Integer>();
    var more = true;
    for (var since = position; more; ) {
      var page = box.changes(uday, since, "&limit=" + ChangeFeed.MAX_LIMIT);
      var changes = (List<?>) page.get("changes");
      var messages =
          changes.stream()
              .map(Map.class::cast)
              .filter(change -> "message".equals(change.get("type")))
              .toList();
      sizes.add(messages.size());
      messages.forEach(change -> seen.add(change.get("id").toString()));
      since = page.get("next").toString();
      more = (Boolean) page.get("more");
    }
    assertEquals(5000, seen.size());
    assertEquals(5000, Set.copyOf(seen).size());
    assertEquals(List.of(500, 500, 500, 500, 500, 500, 500, 500, 500, 500), sizes);
    assertEquals(
        ChangeFeed.DEFAULT_LIMIT,
        names(box.changes(uday, position)).stream().filter(n -> n.startsWith("message")).count(),
        "a page names no limit takes the default");
  }

  @Test
  void aPageOverASkippedTailMovesItsNextPastWhatItReadAndToldNothingOf() throws Exception {
    var mia = box.person("mia", "member");
    box.person("eng", "member");
    box.scope("eng", "acme");
    var position = seq(box.send("GET", "/v1/specs", uday, null));
    box.post("acme-room", mia, "for people");
    box.inOneTransaction(
        () ->
            Acting.as(
                "mia",
                () -> {
                  for (var i = 0; i < 4; i++) {
                    box.messages().append("acme-room", "mia", "skipped " + i, null);
                  }
                }));
    box.send("PUT", "/v1/specs/acme-spec", uday, Map.of("assignee", "eng"));
    var credential = box.runCredential("acme-spec", "eng");
    var newest = seq(box.send("GET", "/v1/specs", uday, null));

    var first = box.askedThroughTheSocket(credential, position, "2");
    var second = box.askedThroughTheSocket(credential, first.get("next").toString(), "2");

    assertEquals(
        List.of(),
        names(first).stream().filter(name -> name.startsWith("message")).toList(),
        "an agent hears no person's comment");
    assertEquals(true, first.get("more"));
    assertTrue(at(first.get("next")) > at(position), "it moved past what it read");
    var rest = second;
    while ((Boolean) rest.get("more")) {
      rest = box.askedThroughTheSocket(credential, rest.get("next").toString(), "2");
    }
    assertEquals(newest, rest.get("next"));
  }

  @Test
  void aListsPositionIsReadInTheSnapshotOfItsRowsSoTheFeedFromItMissesNothing() throws Exception {
    box.close();
    try (var other = Sqlite.open(dir.resolve("sail.db"))) {
      box =
          new Box(
              dir,
              operations ->
                  intercepting(
                      operations,
                      "globalSpecs",
                      () ->
                          Acting.as(
                              "uday",
                              () -> {
                                new SpecStore(other).create(specRow("acme-racing", "acme"));
                              })));
      var read = box.send("GET", "/v1/specs", uday, null);

      assertEquals(List.of("acme-spec"), specIds(read), "the racing spec committed after the read");
      assertEquals(List.of("spec acme-racing"), names(box.changes(uday, seq(read))));
    }
  }

  @Test
  void aStreamWithNoPositionStartsLiveAndOnlyRowChangesCarryIds() throws Exception {
    var stream = box.stream(uday, "/v1/events/stream", (String) null);

    var posted = box.post("acme-room", uday, "now");

    var change = stream.changes(1);
    assertEquals(List.of(posted), ids(change));
    var announced = stream.await(Event.WellKnownTypes.SPEC_MESSAGE_POSTED);
    assertTrue(announced.startsWith("data: "), "a domain event carries no id: " + announced);
    assertEquals(
        List.of(seqsOf(change).getFirst()),
        stream.ids().stream().map(ChangeFeedTest::at).distinct().toList());
  }

  @Test
  void aRunsCredentialReadsTheRowsItMayReadTodayAndNoMore() throws Exception {
    box.person("eng", "member");
    box.scope("eng", "acme");
    var position = seq(box.send("GET", "/v1/specs", uday, null));
    box.send("PUT", "/v1/specs/acme-spec", uday, Map.of("assignee", "eng"));
    box.send("POST", "/v1/rooms", uday, room("beta-room", "beta"));
    var mia = box.person("mia", "member");
    var steering = box.post("acme-room", uday, "an admin steers it");
    box.post("acme-room", mia, "a comment for people");
    box.post("beta-room", uday, "another project");
    var credential = box.runCredential("acme-spec", "eng");

    assertEquals(
        List.of("spec acme-spec", "message " + steering),
        names(box.askedThroughTheSocket(credential, position)),
        "its spec and the steering it hears, and nothing of beta or of people");
  }

  @Test
  void turnDeltasAndPtyFactsOnAResumedStreamCarryNoIdAndMoveNoCursor() throws Exception {
    var position = seq(box.send("GET", "/v1/specs", uday, null));
    var stream = box.stream(uday, "/v1/events/stream", position);

    box.bus()
        .publish(Event.of("acme", "acme-room", Event.WellKnownTypes.AGENT_TEXT_DELTA, "sail", "h"));
    box.bus()
        .publish(
            Event.of("acme", "acme-room", Event.WellKnownTypes.PTY_SESSION_STARTED, "sail", "h"));
    var posted = box.post("acme-room", uday, "after the deltas");

    var change = stream.changes(1);
    assertEquals(List.of(posted), ids(change));
    for (var type :
        List.of(Event.WellKnownTypes.AGENT_TEXT_DELTA, Event.WellKnownTypes.PTY_SESSION_STARTED)) {
      var frame = stream.await(type);
      assertTrue(frame.startsWith("data: "), frame);
    }
    assertEquals(
        List.of(seqsOf(change).getFirst()),
        stream.ids().stream().map(ChangeFeedTest::at).distinct().toList(),
        "only the change moved");
  }

  @Test
  void aPageCostsTheSameStatementsForThreeChangesAsForThirty() throws Exception {
    var position = seq(box.send("GET", "/v1/specs", uday, null));
    box.send("PUT", "/v1/specs/acme-spec", uday, Map.of("title", "One"));
    box.post("acme-room", uday, "one");
    box.renameRoom("acme-room");
    var few = box.cost(uday, "/v1/changes?since=" + position);
    var middle = seq(box.send("GET", "/v1/specs", uday, null));
    for (var i = 0; i < 10; i++) {
      box.send("POST", "/v1/rooms", uday, room("acme-room-" + i, "acme"));
      box.send("POST", "/v1/specs", uday, spec("acme-spec-" + i, "acme", "acme-room-" + i));
      box.post("acme-room-" + i, uday, "many " + i);
    }
    assertEquals(30, names(box.changes(uday, middle)).size());

    assertEquals(few, box.cost(uday, "/v1/changes?since=" + middle));
  }

  @Test
  void aWakeFannedToTwentyStreamsCostsTheSameOverSixtyThousandHiddenMessages() throws Exception {
    var vik = box.person("vik", "viewer");
    box.scope("vik", "acme");
    box.send("POST", "/v1/rooms", uday, room("beta-room", "beta"));
    var base =
        box.clocked(
            new ManualClock(Instant.parse("2026-11-12T09:00:00Z"), Duration.ofDays(1)),
            box.operations().changes());
    var streams = new ArrayList<Stream>();
    for (var i = 0; i < 20; i++) {
      streams.add(box.stream(base, "/v1/events/stream", vik, null));
    }
    box.fannedOut(streams, uday, "warm");
    var quiet = box.fannedOut(streams, uday, "before");

    box.bulkMessages("beta-room", "beta", 60_000);
    box.fannedOut(streams, uday, "settle");
    var started = System.nanoTime();
    var busy = box.fannedOut(streams, uday, "after");
    var took = Duration.ofNanos(System.nanoTime() - started);

    assertEquals(quiet, busy, "a wake reads what lies after its stream's position, nothing older");
    assertTrue(took.compareTo(Duration.ofSeconds(WAIT_SECONDS)) < 0, took.toString());
  }

  @Test
  @Timeout(value = 5, unit = TimeUnit.MINUTES)
  void aReplayOfTwoHundredThousandChangesIsPagedAndAWriteLandsBetweenItsPages() throws Exception {
    var position = seq(box.send("GET", "/v1/specs", uday, null));
    box.bulkMessages("acme-room", "acme", 200_000);
    var pages = new AtomicInteger();
    var largest = new AtomicInteger();
    var feed = box.operations().changes();
    var base =
        box.clocked(
            new ManualClock(Instant.parse("2026-11-12T09:00:00Z"), Duration.ofDays(1)),
            new Paging(
                feed,
                ask -> {
                  var page = feed.page(ask);
                  pages.incrementAndGet();
                  largest.accumulateAndGet(
                      (int) page.changes().stream().filter(c -> c.type().equals("message")).count(),
                      Math::max);
                  return page;
                }));

    var stream = box.stream(base, "/v1/events/stream", uday, position);
    stream.changes(SseHandler.PAGE);
    var landed = box.post("acme-room", uday, "written mid replay");
    var readWhenItLanded = pages.get();
    delta("between pages");
    var frames = stream.untilChange(landed, REPLAY_SECONDS);

    assertTrue(readWhenItLanded < 200_000 / SseHandler.PAGE, "the write waited for no replay");
    assertTrue(
        frames.indexOf(
                frames.stream().filter(f -> f.contains("between pages")).findFirst().orElseThrow())
            < frames.size() - SseHandler.PAGE,
        "an event is sent between pages, not after the replay");
    assertEquals(SseHandler.PAGE, largest.get(), "never more than a page at a time");
    assertTrue(pages.get() >= 200_000 / SseHandler.PAGE, pages.toString());
  }

  @Test
  void aPageThatSaysMoreButDoesNotMoveTheCursorIsNotReadAgainUntilTheNextWake() throws Exception {
    var pages = new AtomicInteger();
    var feed = box.operations().changes();
    var base =
        box.clocked(
            new ManualClock(Instant.parse("2026-11-12T09:00:00Z"), Duration.ofDays(1)),
            new Paging(
                feed,
                ask -> {
                  pages.incrementAndGet();
                  return new ChangeFeed.Page(List.of(), ask.since(), true);
                }));
    var stream = box.stream(base, "/v1/events/stream", uday, null);
    delta("after the first page");
    stream.awaitNew("\"agent\": \"after the first page\"");
    var read = pages.get();
    delta("later");
    stream.awaitNew("\"agent\": \"later\"");

    assertEquals(1, read, "the first page, and no spin on it");
    assertEquals(read, pages.get(), "events that tell of no write read nothing");
    box.renameRoom("acme-room");
    delta("after a commit");
    stream.awaitNew("\"agent\": \"after a commit\"");
    assertEquals(read + 1, pages.get(), "a commit reads the page again, once");
  }

  @Test
  void aPositionHandedToAScopedReaderNamesNoFloorThatRose() throws Exception {
    HeldProjects.hold(box.db(), "gamma");
    var vik = box.person("vik", "viewer");
    box.scope("vik", "acme");
    box.person("mia", "member");
    box.scope("mia", "beta");
    box.send("POST", "/v1/rooms", uday, room("beta-room", "beta"));
    box.send("POST", "/v1/specs", uday, spec("beta-spec", "beta", "beta-room"));
    var guesses = new HashSet<String>();
    for (var floor : List.of("box", "fde:mia", "fde:vik", "project:beta", "project:gamma")) {
      guesses.add(fingerprint(FeedFloors.TYPE, floor, FeedFloors.TYPE));
      guesses.add(fingerprint(FeedFloors.TYPE, floor, ""));
    }

    box.send("PUT", "/v1/specs/beta-spec", uday, Map.of("project", "gamma"));
    var afterMove = fingerprintOf(seq(box.send("GET", "/v1/specs", vik, null)));
    box.revoke("mia", "beta");
    var afterRevoke = fingerprintOf(seq(box.send("GET", "/v1/specs", vik, null)));
    box.revoke("mia", "beta");
    box.scope("mia", "beta");
    var afterRegrant = fingerprintOf(seq(box.send("GET", "/v1/specs", vik, null)));

    assertFalse(guesses.contains(afterMove), "a move into gamma is not named");
    assertFalse(guesses.contains(afterRevoke), "mia's revoke is not named");
    assertNotEquals(afterRevoke, afterRegrant, "two raises of one floor look alike to no one");
  }

  @Test
  void aDisableOrADeletedSessionThatLandsBetweenTheDoorAndThePageSendsNothingAndEnds()
      throws Exception {
    var armed = new AtomicBoolean();
    var action = new AtomicReference<Runnable>();
    var sessions = TestAuth.sessions(box.db(), MAIN);
    ApiAuth racing =
        exchange -> {
          sessions.require(exchange);
          if (armed.compareAndSet(true, false)) {
            action.get().run();
          }
        };
    var clock = new ManualClock(Instant.parse("2026-11-12T09:00:00Z"), Duration.ofDays(1));
    var base = box.clocked(clock, box.operations().changes(), racing);
    for (var kind : List.of("disable", "session", "revoke")) {
      var handle = "racer-" + kind;
      var token = box.person(handle, "member");
      box.scope(handle, "acme");
      var stream = box.stream(base, "/v1/events/stream", token, null);
      var before = box.post("acme-room", uday, "before " + kind);
      assertEquals(List.of(before), ids(stream.changesOf("message", 1)));
      action.set(
          switch (kind) {
            case "disable" -> () -> box.disable(handle);
            case "session" ->
                () ->
                    box.db()
                        .execute(
                            "DELETE FROM sessions WHERE fde_id ="
                                + " (SELECT id FROM fdes WHERE handle = ?)",
                            handle);
            default -> () -> box.revoke(handle, "acme");
          });
      armed.set(true);
      box.post("acme-room", uday, "SECRET after " + kind);

      var frames = stream.ended();

      assertTrue(frames.stream().noneMatch(f -> f.contains("SECRET")), kind + ": " + frames);
      assertTrue(frames.stream().noneMatch(f -> f.startsWith("event: change")), kind);
    }
  }

  @Test
  void aDisabledCallersStreamEndsAtTheNextWakeThoughItHasNothingToBeSent() throws Exception {
    var vik = box.person("vik", "viewer");
    box.scope("vik", "acme");
    box.send("POST", "/v1/rooms", uday, room("beta-room", "beta"));
    var clock = new ManualClock(Instant.parse("2026-11-12T09:00:00Z"), Duration.ofDays(1));
    var stream =
        box.stream(box.clocked(clock, box.operations().changes()), "/v1/events/stream", vik, null);

    box.disable("vik");
    box.post("beta-room", uday, "nothing of this is vik's");

    var frames = stream.ended();
    assertTrue(frames.getLast().startsWith("event: error"), frames.toString());
  }

  @Test
  void aMemberNodesAnnouncementWakesTheStreamsAndNoClientIsSentIt() throws Exception {
    var mia = box.person("mia", "member");
    var clock = new ManualClock(Instant.parse("2026-11-12T09:00:00Z"), Duration.ofDays(1));
    var base = box.clocked(clock, box.operations().changes());
    var admin = box.stream(base, "/v1/events/stream", uday, null);
    try (var other = Sqlite.open(dir.resolve("sail.db"));
        var announcer =
            new CommitAnnouncer(() -> new SailEventPublisher("127.0.0.1", box.port(), mia))) {
      var pushed =
          Acting.as(
              "mia", () -> new MessageStore(other).append("acme-room", "mia", "pushed", null));
      announcer.run();

      var frames = admin.untilChange(pushed.id(), WAIT_SECONDS);

      assertTrue(
          frames.stream().noneMatch(f -> f.startsWith("data: ")),
          "the announcement is a wake, delivered to no client: " + frames);
    }
  }

  @Test
  void wakesThatArriveWhileAStreamIsBusyCostItOnePageAndOtherDoorEventsNone() throws Exception {
    var started = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    var pages = new AtomicInteger();
    var feed = box.operations().changes();
    var base =
        box.clocked(
            new ManualClock(Instant.parse("2026-11-12T09:00:00Z"), Duration.ofDays(1)),
            new Paging(
                feed,
                ask -> {
                  if (pages.incrementAndGet() == 2) {
                    started.countDown();
                    await(release);
                  }
                  return feed.page(ask);
                }));
    var stream = box.stream(base, "/v1/events/stream", uday, null);
    var publisher = new SailEventPublisher("127.0.0.1", box.port(), uday);
    try (var other = Sqlite.open(dir.resolve("sail.db"))) {
      written(other, "first");
      box.wake(uday);
      assertTrue(started.await(WAIT_SECONDS, TimeUnit.SECONDS), "the first wake read a page");
      for (var i = 0; i < 10; i++) {
        written(other, "during " + i);
        box.wake(uday);
      }
    }
    release.countDown();
    delta("after the burst");
    stream.awaitNew("\"agent\": \"after the burst\"");
    var afterBurst = pages.get();

    for (var i = 0; i < 10; i++) {
      publisher.publish(
          Event.of(
              "acme", null, Event.WellKnownTypes.BOARD_UPDATED, Event.SAIL_AGENT, "door-" + i));
    }
    stream.awaitNew("\"host\": \"door-9\"");

    assertEquals(3, afterBurst, "the start, the first wake, and one for the ten during it");
    assertEquals(afterBurst, pages.get(), "events through the door read no page");
  }

  @Test
  void wakesWithNothingWrittenSinceTheLastCostAStreamNothing() throws Exception {
    var pages = new AtomicInteger();
    var feed = box.operations().changes();
    var base =
        box.clocked(
            new ManualClock(Instant.parse("2026-11-12T09:00:00Z"), Duration.ofDays(1)),
            new Paging(
                feed,
                ask -> {
                  pages.incrementAndGet();
                  return feed.page(ask);
                }));
    var streams = new ArrayList<Stream>();
    for (var i = 0; i < 20; i++) {
      streams.add(box.stream(base, "/v1/events/stream", uday, null));
    }
    box.renameRoom("acme-room");
    for (var stream : streams) {
      assertEquals(List.of("acme-room"), ids(stream.changes(1)));
    }
    box.wake(uday);
    delta("settled");
    for (var stream : streams) {
      stream.awaitNew("\"agent\": \"settled\"");
    }
    var settled = pages.get();
    var before = box.statements();

    for (var i = 0; i < 300; i++) {
      box.wake(uday);
    }

    assertEquals(settled, pages.get(), "three hundred wakes with nothing new read no page");
    var perWake = (box.statements() - before) / 300.0;
    assertTrue(perWake < 10, "each costs its door check and one read of the newest: " + perWake);
    try (var other = Sqlite.open(dir.resolve("sail.db"))) {
      var told = written(other, "something new");
      box.wake(uday);
      for (var stream : streams) {
        assertEquals(List.of(told), ids(stream.changesOf("message", 1)), "a real one wakes them");
      }
    }
  }

  /** A message posted to acme-room on {@code other}, a connection apart from the server's. */
  private static String written(Sqlite other, String body) {
    return Acting.as("uday", () -> new MessageStore(other).append("acme-room", "uday", body, null))
        .id();
  }

  @Test
  void aRestoredMainsStalePositionIsToldToResyncEvenOnceTheEntryAtItsSeqIsErased()
      throws Exception {
    var backup = dir.resolve("older.db");
    box.backup(backup);
    box.send("POST", "/v1/specs", uday, spec("lost-spec", "acme", "acme-room"));
    for (var i = 0; i < 6; i++) {
      box.post("acme-room", uday, "lost " + i);
    }
    var lost = seq(box.send("GET", "/v1/specs", uday, null));
    box.restore(backup);
    box.send("PUT", "/v1/specs/acme-spec", uday, Map.of("title", "renamed after the restore"));
    String standing = null;
    while (standing == null) {
      var id = box.post("acme-room", uday, "regrown");
      var head = new ChangeLog(box.db()).head("message", id).orElseThrow();
      assertTrue(head.seq() <= at(lost), "a message lands on the lost position's seq");
      standing = head.seq() == at(lost) ? id : null;
    }
    assertEquals(409, box.send("GET", "/v1/changes?since=" + lost, uday, null).statusCode());
    var erased = standing;

    Acting.as(
        "uday",
        () -> new Erasure(box.db()).erase(List.of(new Erasure.Target("message", erased)), "local"));

    assertEquals(409, box.send("GET", "/v1/changes?since=" + lost, uday, null).statusCode());
  }

  @Test
  void aRestoredMainsPositionOnTheSameRowAtTheSameSeqWithAnotherRevisionIsToldToResync()
      throws Exception {
    var backup = dir.resolve("older.db");
    box.backup(backup);
    box.send("PUT", "/v1/specs/acme-spec", uday, Map.of("title", "the lost title"));
    var lost = seq(box.send("GET", "/v1/specs", uday, null));
    box.restore(backup);

    box.send("PUT", "/v1/specs/acme-spec", uday, Map.of("title", "another title"));

    assertEquals(
        at(lost),
        at(seq(box.send("GET", "/v1/specs", uday, null))),
        "the same row's revision now stands at the lost position's seq");
    assertEquals(409, box.send("GET", "/v1/changes?since=" + lost, uday, null).statusCode());
  }

  @Test
  void aStreamWhoseCursorsEntryIsErasedGoesOnAndIsToldTheErasure() throws Exception {
    var stream = box.stream(uday, "/v1/events/stream", (String) null);
    var told = box.post("acme-room", uday, "the stream's cursor stands on this");
    assertEquals(List.of(told), ids(stream.changesOf("message", 1)));

    Acting.as(
        "uday",
        () -> new Erasure(box.db()).erase(List.of(new Erasure.Target("message", told)), "local"));
    var erased = stream.changesOf("message", 1).getFirst();
    var after = box.post("acme-room", uday, "after the erasure");

    assertEquals(true, erased.get("erased"), erased.toString());
    assertEquals(List.of(after), ids(stream.changesOf("message", 1)), "the stream went on");
    assertEquals(
        409,
        box.send("GET", "/v1/changes?since=" + stream.ids().get(0), uday, null).statusCode(),
        "a client naming that position itself is answered afresh");
  }

  @Test
  void aMalformedAskIsRefusedNamingWhatToSend() throws Exception {
    var newest = seq(box.send("GET", "/v1/specs", uday, null));
    for (var query :
        List.of(
            "",
            "?since=",
            "?since=" + newest + "&limit=0",
            "?since=" + newest + "&limit=x",
            "?since=" + newest + "&limit=" + (ChangeFeed.MAX_LIMIT + 1),
            "?since=" + newest + "&types=run")) {
      var refused = box.send("GET", "/v1/changes" + query, uday, null);
      assertEquals(422, refused.statusCode(), query + " " + refused.body());
    }
  }

  private static Map<String, Object> change(long seq, String type, String id, String fate) {
    var change = new LinkedHashMap<String, Object>();
    change.put("seq", seq);
    change.put("type", type);
    change.put("id", id);
    change.put(fate, true);
    return change;
  }

  private static String seq(HttpResponse<String> response) {
    return response
        .headers()
        .firstValue("seq")
        .orElseThrow(() -> new AssertionError("no seq header: " + response.headers().map()));
  }

  /**
   * Every row of {@code rows}, parsed, with a room's specs as a set: what a comparison of a list
   * view and a feed-built one compares (the order a room's specs are listed in is the server's).
   */
  private static Map<String, Map<String, Object>> parsed(Map<String, String> rows) {
    var parsed = new TreeMap<String, Map<String, Object>>();
    rows.forEach((key, row) -> parsed.put(key, normalized(YamlUtil.parseMap(row))));
    return parsed;
  }

  /** That {@code built} holds {@code listed}'s rows, naming each row that differs. */
  private static void assertSameRows(
      Map<String, Map<String, Object>> listed,
      Map<String, Map<String, Object>> built,
      String whose) {
    var differing = new TreeMap<String, String>();
    var keys = new java.util.TreeSet<>(listed.keySet());
    keys.addAll(built.keySet());
    for (var key : keys) {
      if (!Objects.equals(listed.get(key), built.get(key))) {
        differing.put(key, "listed " + listed.get(key) + "\n    built " + built.get(key));
      }
    }
    assertEquals(Map.of(), differing, whose + ", row for row");
  }

  private static Map<String, Object> normalized(Map<String, Object> row) {
    var normal = new LinkedHashMap<>(row);
    normal.computeIfPresent(
        "spec_ids", (key, ids) -> ((List<?>) ids).stream().map(Object::toString).sorted().toList());
    return normal;
  }

  /**
   * The rows a client holds, {@code rows}, with the fields a list derives from other rows first
   * dropped, whatever a row holds of them, then derived as the client derives them: a room's specs
   * from the spec rows whose room it is, its latest activity and open question from the messages it
   * holds of it; a spec's wake and seated agent from its room row, its open question from its
   * room's messages, and its latest activity from its {@code updated_at} and the messages of the
   * conversation named as the spec is, as the list reads it. A question is open when no person
   * spoke after it, which every voice in these tests steers.
   */
  private static Map<String, Map<String, Object>> derived(Map<String, String> rows) {
    var held = parsed(rows);
    var rooms = new TreeMap<String, Map<String, Object>>();
    var messagesByRoom = new TreeMap<String, List<Map<String, Object>>>();
    held.forEach(
        (key, row) -> {
          if (key.startsWith("room ")) {
            rooms.put(row.get("id").toString(), row);
          } else if (key.startsWith("message ")) {
            messagesByRoom
                .computeIfAbsent(row.get("spec_id").toString(), room -> new ArrayList<>())
                .add(row);
          }
        });
    var derived = new TreeMap<String, Map<String, Object>>();
    held.forEach(
        (key, row) -> {
          var one = new LinkedHashMap<>(row);
          one.keySet()
              .removeAll(
                  key.startsWith("room ")
                      ? RoomsListResponse.DERIVED
                      : key.startsWith("spec ") ? GlobalSpecsListResponse.DERIVED : Set.of());
          if (key.startsWith("room ")) {
            var id = row.get("id").toString();
            one.put(
                "spec_ids",
                held.entrySet().stream()
                    .filter(e -> e.getKey().startsWith("spec "))
                    .map(Map.Entry::getValue)
                    .filter(spec -> id.equals(Objects.toString(spec.get("room_id"), null)))
                    .map(spec -> spec.get("id").toString())
                    .sorted()
                    .toList());
            var messages = messagesByRoom.getOrDefault(id, List.of());
            latest(messages).ifPresent(at -> one.put("last_activity_at", at));
            var question = openQuestion(messages);
            one.put("needs_reply", question.isPresent());
            question.ifPresent(q -> one.put("question_message_id", q));
          } else if (key.startsWith("spec ")) {
            var roomId = Objects.toString(row.get("room_id"), null);
            var room = roomId == null ? null : rooms.get(roomId);
            var messages =
                room == null
                    ? List.<Map<String, Object>>of()
                    : messagesByRoom.getOrDefault(roomId, List.of());
            if (room != null && room.get("wake") != null) {
              one.put("wake", room.get("wake"));
            }
            if (room != null && !((List<?>) room.get("members")).isEmpty()) {
              one.put("engagement", ((List<?>) room.get("members")).getFirst());
            }
            var updated = row.get("updated_at").toString();
            var latest =
                latest(messagesByRoom.getOrDefault(row.get("id").toString(), List.of()))
                    .filter(at -> at.compareTo(updated) > 0)
                    .orElse(updated);
            one.put("last_activity_at", latest);
            openQuestion(messages)
                .ifPresent(
                    q -> {
                      one.put("needs_reply", true);
                      one.put("question_message_id", q);
                    });
          }
          derived.put(key, normalized(one));
        });
    return derived;
  }

  private static Optional<String> latest(List<Map<String, Object>> messages) {
    return messages.stream().map(m -> m.get("created_at").toString()).max(String::compareTo);
  }

  /** The latest question an agent asked of {@code messages} that no person answered after it. */
  private static Optional<String> openQuestion(List<Map<String, Object>> messages) {
    var ordered =
        messages.stream().sorted(Comparator.comparing(m -> m.get("id").toString())).toList();
    String open = null;
    for (var message : ordered) {
      var author = message.get("author").toString();
      if (Boolean.TRUE.equals(message.get("question")) && author.contains("/")) {
        open = message.get("id").toString();
      } else if (!author.contains("/") && !"sail".equals(author)) {
        open = null;
      }
    }
    return Optional.ofNullable(open);
  }

  private static String fingerprint(String type, String id, String rev) throws Exception {
    var digest =
        MessageDigest.getInstance("SHA-256")
            .digest((type + "\u0000" + id + "\u0000" + rev).getBytes(StandardCharsets.UTF_8));
    return HexFormat.of().formatHex(digest, 0, 6);
  }

  private static String fingerprintOf(String position) {
    return position.split("\\.")[2];
  }

  private static void await(CountDownLatch latch) {
    try {
      assertTrue(latch.await(WAIT_SECONDS, TimeUnit.SECONDS), "never released");
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new AssertionError(e);
    }
  }

  /** The seq a position stands at: its middle part. */
  private static long at(Object position) {
    return Long.parseLong(position.toString().split("\\.")[1]);
  }

  private static Map<?, ?> error(HttpResponse<String> response) {
    return (Map<?, ?>) YamlUtil.parseMap(response.body()).get("error");
  }

  private static List<String> specIds(HttpResponse<String> response) {
    return ((List<?>) YamlUtil.parseMap(response.body()).get("specs"))
        .stream().map(spec -> ((Map<?, ?>) spec).get("id").toString()).toList();
  }

  private static List<String> names(Map<?, ?> page) {
    return ((List<?>) page.get("changes"))
        .stream()
            .map(Map.class::cast)
            .map(change -> change.get("type") + " " + change.get("id"))
            .toList();
  }

  private static List<Long> seqs(Map<?, ?> page) {
    return seqsOf(
        ((List<?>) page.get("changes")).stream().<Map<?, ?>>map(each -> (Map<?, ?>) each).toList());
  }

  private static List<Long> seqsOf(List<Map<?, ?>> changes) {
    return changes.stream().map(change -> ((Number) change.get("seq")).longValue()).toList();
  }

  private static List<String> ids(List<Map<?, ?>> changes) {
    return changes.stream().map(change -> change.get("id").toString()).toList();
  }

  private static Map<?, ?> row(Map<?, ?> page, int index) {
    return (Map<?, ?>) ((Map<?, ?>) ((List<?>) page.get("changes")).get(index)).get("row");
  }

  /** A turn's delta, from {@code agent}, as a room's live member publishes it in this process. */
  private void delta(String agent) {
    box.bus()
        .publish(Event.of("acme", "acme-room", Event.WellKnownTypes.AGENT_TEXT_DELTA, agent, "h"));
  }

  /** An event an admin's client publishes through the door, of acme. */
  private static Map<String, Object> announcement() {
    return Map.of(
        "ts",
        "2026-11-12T09:00:00Z",
        "project",
        "acme",
        "type",
        "board_updated",
        "agent",
        "client",
        "host",
        "laptop",
        "data",
        Map.of());
  }

  private static Map<String, Object> room(String id, String project) {
    return Map.of("id", id, "project", project, "title", id);
  }

  private static Map<String, Object> spec(String id, String project, String room) {
    return Map.of("id", id, "project", project, "title", id, "room_id", room);
  }

  private static SpecStore.SpecRow specRow(String id, String project) {
    return new SpecStore.SpecRow(
        id,
        project,
        id,
        SpecStatus.DRAFT,
        null,
        null,
        null,
        null,
        null,
        0,
        null,
        null,
        null,
        null,
        List.of(),
        List.of());
  }

  /** {@code operations} whose change feed is {@code wrap}ped. */
  private static Operations feeding(Operations operations, UnaryOperator<ChangeFeed> wrap) {
    return delegating(
        operations,
        (method, args) ->
            method.getName().equals("changes") ? wrap.apply(operations.changes()) : null);
  }

  /** {@code operations} that run {@code after} once {@code name} has answered, each time. */
  private static Operations intercepting(Operations operations, String name, Runnable after) {
    return delegating(
        operations,
        (method, args) -> {
          if (!method.getName().equals(name)) {
            return null;
          }
          var answer = method.invoke(operations, args);
          after.run();
          return answer;
        });
  }

  @FunctionalInterface
  private interface Answer {
    Object answer(Method method, Object[] args) throws Exception;
  }

  private static Operations delegating(Operations operations, Answer override) {
    return (Operations)
        Proxy.newProxyInstance(
            Operations.class.getClassLoader(),
            new Class<?>[] {Operations.class},
            (proxy, method, args) -> {
              try {
                var answer = override.answer(method, args);
                return answer != null ? answer : method.invoke(operations, args);
              } catch (InvocationTargetException e) {
                throw e.getCause();
              }
            });
  }

  /** A feed whose pages are read through {@code pages}, its other reads its own. */
  private record Paging(ChangeFeed feed, Pages pages) implements ChangeFeed {
    @Override
    public String newest() {
      return feed.newest();
    }

    @Override
    public ChangeLog.Watch watch(Runnable wake) {
      return feed.watch(wake);
    }

    @Override
    public void wake() {
      feed.wake();
    }

    @Override
    public Page page(Ask ask) {
      return pages.page(ask);
    }

    @Override
    public <T> Positioned<T> at(Supplier<T> read) {
      return feed.at(read);
    }
  }

  /**
   * What a reader holds of the board: its rows by type and id, and the position they were read at.
   */
  private record View(String position, Map<String, String> rows) {}

  @FunctionalInterface
  private interface Pages {
    ChangeFeed.Page page(ChangeFeed.Ask ask);
  }

  /** Main as a client reaches it: its server, its stores and its people. */
  private static final class Box implements AutoCloseable {
    private final Path dir;
    private final UnaryOperator<Operations> wrap;
    private final List<Stream> streams = new CopyOnWriteArrayList<>();
    private final List<HttpServer> servers = new CopyOnWriteArrayList<>();
    private final HttpClient client = HttpClient.newHttpClient();
    private Sqlite db;
    private EventBus bus;
    private SailOperations operations;
    private SailApiServer server;

    Box(Path dir, UnaryOperator<Operations> wrap) throws IOException {
      this.dir = dir;
      this.wrap = wrap;
      open();
    }

    private void open() throws IOException {
      db = Sqlite.open(dir.resolve("sail.db"));
      new SchemaManager(db).migrate();
      HeldProjects.hold(db, "acme", "beta");
      bus = new EventBus();
      operations =
          OperationsFactory.create(
                  db,
                  new ScriptedShellExecutor(new ShellExec.Result(0, "[]", "")),
                  bus,
                  SyncScheduler.disabled(),
                  SessionYield.NONE)
              .useControlPlane(
                  db,
                  dir,
                  new SyncOperations(
                      db,
                      MAIN.boxId(),
                      dir,
                      () -> MAIN,
                      target -> {
                        throw new IOException("main is not asked here");
                      }));
      server =
          new SailApiServer(
              "127.0.0.1", 0, wrap.apply(operations), TestAuth.sessions(db, MAIN), bus, null, null);
      server.start();
    }

    /** Stops the server and closes the database, then opens both again on the same file. */
    void restart() throws IOException {
      close();
      open();
    }

    SailOperations operations() {
      return operations;
    }

    EventBus bus() {
      return bus;
    }

    MessageStore messages() {
      return new MessageStore(db);
    }

    void inOneTransaction(Runnable work) {
      db.transaction(work);
    }

    /** A session token for a new FDE of {@code role}. */
    String person(String handle, String role) {
      var fde = new FdeStore(db).add(handle, null, null, role);
      return new AuthSessionStore(db).create(fde.id(), Duration.ofHours(1)).token();
    }

    void scope(String handle, String... projects) {
      var fdes = new FdeStore(db);
      fdes.restrict(handle);
      for (var project : projects) {
        fdes.grant(handle, project);
      }
    }

    void revoke(String handle, String project) {
      new FdeStore(db).revoke(handle, project);
    }

    /** A write of {@code room} with no route to make it: its wake, as the box's own machinery. */
    void renameRoom(String room) {
      Acting.system(() -> new RoomStore(db).updateWake(room, "off"));
    }

    String post(String room, String token, String body) throws Exception {
      var posted = send("POST", "/v1/rooms/" + room + "/messages", token, Map.of("body", body));
      assertEquals(201, posted.statusCode(), posted.body());
      return ((Map<?, ?>) YamlUtil.parseMap(posted.body()).get("message")).get("id").toString();
    }

    /** The credential of a build of {@code spec} for {@code fde}, as dispatch mints it. */
    String runCredential(String spec, String fde) {
      var reservation =
          Acting.system(
              () ->
                  new RunStore(db)
                      .reserveDispatch(
                          "019fee00-0000-7000-8000-0000000000c1",
                          "acme",
                          spec,
                          null,
                          fde,
                          "build",
                          List.of(),
                          "claude-code",
                          "feat/" + spec,
                          "work",
                          "/tmp/" + spec + ".log",
                          "sail-agent-" + spec,
                          Duration.ofHours(1)));
      return ((RunStore.Reservation.Reserved) reservation).credential();
    }

    Map<?, ?> changes(String token, String since) throws Exception {
      return changes(token, since, "");
    }

    Map<?, ?> changes(String token, String since, String more) throws Exception {
      var answer = send("GET", "/v1/changes?since=" + since + more, token, null);
      assertEquals(200, answer.statusCode(), answer.body());
      return YamlUtil.parseMap(answer.body());
    }

    /** The statements the database prepares as {@code token} asks {@code path}. */
    long cost(String token, String path) throws Exception {
      var before = db.statements();
      assertEquals(200, send("GET", path, token, null).statusCode());
      return db.statements() - before;
    }

    /**
     * What {@code token} is answered from {@code since}, byte for byte: the feed's page, and the
     * frames its stream resumed there is sent up to a change it is then told of.
     */
    Map<String, String> sweep(String token, String since) throws Exception {
      var swept = new LinkedHashMap<String, String>();
      swept.put("feed", send("GET", "/v1/changes?since=" + since, token, null).body());
      var stream = stream(token, "/v1/events/stream", since);
      var replay = (List<?>) changes(token, since).get("changes");
      swept.put("stream", String.join("\n\n", stream.frames(replay.size())));
      stream.end();
      return swept;
    }

    HttpResponse<String> send(String method, String path, String token, Map<String, Object> body)
        throws Exception {
      var request =
          HttpRequest.newBuilder(URI.create(base() + path))
              .method(
                  method,
                  body == null
                      ? HttpRequest.BodyPublishers.noBody()
                      : HttpRequest.BodyPublishers.ofString(YamlUtil.dumpJson(body)))
              .header("Content-Type", "application/json");
      if (token != null) {
        request.header("Authorization", "Bearer " + token);
      }
      return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    /** Opens {@code path}'s stream as {@code token}, sending {@code lastEventId} when it is set. */
    Stream stream(String token, String path, String lastEventId) throws Exception {
      return stream(base(), path, token, lastEventId);
    }

    /**
     * Opens {@code path}'s stream on the server at {@code base} as {@code token}, sending {@code
     * lastEventId} when it is set.
     */
    Stream stream(String base, String path, String token, String lastEventId) throws Exception {
      var request =
          HttpRequest.newBuilder(URI.create(base + path))
              .header("Authorization", "Bearer " + token)
              .GET();
      if (lastEventId != null) {
        request.header("Last-Event-ID", lastEventId);
      }
      var response = client.send(request.build(), HttpResponse.BodyHandlers.ofInputStream());
      assertEquals(200, response.statusCode());
      var stream = new Stream(response);
      streams.add(stream);
      stream.subscribed();
      return stream;
    }

    private String base() {
      return "http://127.0.0.1:" + server.port();
    }

    long statements() {
      return db.statements();
    }

    int port() {
      return server.port();
    }

    Sqlite db() {
      return db;
    }

    /**
     * The base URL of a stream handler of the box's events and changes, beside the server's, timed
     * by {@code clock} and reading {@code feed}: a test holds the clock its heartbeats and checks
     * run on, and sees the pages it reads.
     */
    String clocked(InstantSource clock, ChangeFeed feed) throws IOException {
      return clocked(clock, feed, TestAuth.sessions(db, MAIN));
    }

    /** As {@link #clocked(InstantSource, ChangeFeed)}, authenticating by {@code auth}. */
    String clocked(InstantSource clock, ChangeFeed feed, ApiAuth auth) throws IOException {
      var handler = new SseHandler(bus, auth, SseHandler.RoomTurns.NONE, feed, 32, clock);
      var alone = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
      PrivateAttributes.context(alone, "/v1/events/stream", handler);
      alone.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
      alone.start();
      servers.add(alone);
      return "http://127.0.0.1:" + alone.getAddress().getPort();
    }

    /** Uninstalls the box and installs it afresh on a new database at the same path. */
    void reinstall() throws IOException {
      close();
      for (var file : List.of("sail.db", "sail.db-wal", "sail.db-shm")) {
        Files.deleteIfExists(dir.resolve(file));
      }
      open();
    }

    /** Copies the box's database to {@code to} as it stands, the server stopped meanwhile. */
    void backup(Path to) throws IOException {
      close();
      Files.copy(dir.resolve("sail.db"), to, StandardCopyOption.REPLACE_EXISTING);
      open();
    }

    /** Restores the box's database in place from the copy {@code from}. */
    void restore(Path from) throws IOException {
      close();
      for (var file : List.of("sail.db-wal", "sail.db-shm")) {
        Files.deleteIfExists(dir.resolve(file));
      }
      Files.copy(from, dir.resolve("sail.db"), StandardCopyOption.REPLACE_EXISTING);
      open();
    }

    void disable(String handle) {
      var fdes = new FdeStore(db);
      fdes.disableUnlisted(
          fdes.list().stream().map(FdeStore.Fde::handle).filter(h -> !h.equals(handle)).toList());
    }

    /** Moves {@code room} to {@code project} as a sync from main applies it. */
    void moveRoom(String room, String project) {
      Acting.system(
          () -> {
            var rooms = new RoomStore(db);
            var moved = new LinkedHashMap<String, Object>(rooms.currentForSync(room));
            moved.put("project", project);
            rooms.applyRevision(room, moved, "9-moved-to-" + project);
          });
    }

    /** Wakes the box's streams through its door as {@code token}, answered before it returns. */
    void wake(String token) throws Exception {
      var woke = send("POST", "/v1/changes:wake", token, null);
      assertEquals(200, woke.statusCode(), woke.body());
    }

    String reply(String room, String token, String body, String replyTo) throws Exception {
      var posted =
          send(
              "POST",
              "/v1/rooms/" + room + "/messages",
              token,
              Map.of("body", body, "reply_to", replyTo));
      assertEquals(201, posted.statusCode(), posted.body());
      return ((Map<?, ?>) YamlUtil.parseMap(posted.body()).get("message")).get("id").toString();
    }

    /**
     * What {@code token} reads of the board through its list routes: every spec, room and message
     * of a room it may read, by type and id, at the position of the first of those reads.
     */
    View view(String token) throws Exception {
      var rows = new TreeMap<String, String>();
      var specs = send("GET", "/v1/specs", token, null);
      var position = seq(specs);
      listed(rows, "spec", specs, "specs");
      var rooms = send("GET", "/v1/rooms", token, null);
      listed(rows, "room", rooms, "rooms");
      for (var room : (List<?>) YamlUtil.parseMap(rooms.body()).get("rooms")) {
        var id = ((Map<?, ?>) room).get("id");
        listed(
            rows, "message", send("GET", "/v1/rooms/" + id + "/messages", token, null), "messages");
      }
      return new View(position, rows);
    }

    private static void listed(
        Map<String, String> rows, String type, HttpResponse<String> read, String key) {
      assertEquals(200, read.statusCode(), read.body());
      for (var row : (List<?>) YamlUtil.parseMap(read.body()).get(key)) {
        rows.put(type + " " + ((Map<?, ?>) row).get("id"), YamlUtil.dumpJsonValue(row));
      }
    }

    /**
     * {@code view} brought up to date by {@code token}'s feed, as a client applies it: a row
     * replaces the one held, {@code gone} and {@code erased} drop it, and a {@code resync} reads
     * the lists afresh. Every row told is added to {@code told}, by type and id.
     */
    View follow(String token, View view, Set<String> told) throws Exception {
      var rows = new TreeMap<>(view.rows());
      var since = view.position();
      while (true) {
        var answer = send("GET", "/v1/changes?since=" + since, token, null);
        if (answer.statusCode() == 409) {
          return view(token);
        }
        assertEquals(200, answer.statusCode(), answer.body());
        var page = YamlUtil.parseMap(answer.body());
        for (var each : (List<?>) page.get("changes")) {
          var change = (Map<?, ?>) each;
          var key = change.get("type") + " " + change.get("id");
          told.add(key);
          if (change.get("row") != null) {
            rows.put(key, YamlUtil.dumpJsonValue(change.get("row")));
          } else {
            rows.remove(key);
          }
        }
        since = page.get("next").toString();
        if (!(Boolean) page.get("more")) {
          return new View(since, rows);
        }
      }
    }

    Map<?, ?> askedThroughTheSocket(String credential, String since) {
      return askedThroughTheSocket(credential, since, null);
    }

    /** {@code GET /v1/changes} as a run's {@code credential} asks it through the unix socket. */
    Map<?, ?> askedThroughTheSocket(String credential, String since, String limit) {
      var query = new LinkedHashMap<String, String>();
      query.put("since", since);
      if (limit != null) {
        query.put("limit", limit);
      }
      var asked =
          new SocketDoor(operations)
              .ask(
                  new LocalApiRequest(
                      "GET",
                      "/v1/changes",
                      query,
                      Map.of("authorization", "Bearer " + credential),
                      new byte[0]));
      assertEquals(200, asked.status(), asked.body().toString());
      return asked.body();
    }

    /**
     * The statements one post to acme-room costs, with {@code streams} each sent its change and
     * then done with whatever woke them: the post, and every stream's page.
     */
    long fannedOut(List<Stream> streams, String token, String label) throws Exception {
      var before = db.statements();
      var posted = post("acme-room", token, label);
      for (var stream : streams) {
        assertEquals(List.of(posted), ids(stream.changes(1)));
      }
      bus.publish(
          Event.of(
              "acme", "acme-room", Event.WellKnownTypes.AGENT_TEXT_DELTA, "settled-" + label, "h"));
      for (var stream : streams) {
        stream.awaitNew("\"agent\": \"settled-" + label + "\"");
      }
      return db.statements() - before;
    }

    /**
     * Writes {@code count} messages into {@code room}, filed under {@code project}, as one insert
     * per table: history at a scale no test posts one by one.
     */
    void bulkMessages(String room, String project, int count) {
      db.transaction(
          () -> {
            db.execute(
                """
                WITH RECURSIVE n(i) AS (SELECT 1 UNION ALL SELECT i + 1 FROM n WHERE i < ?2)
                INSERT INTO room_messages (id, room_id, author, body, created_at, rev)
                SELECT printf('%s-%07d', ?1, i), ?1, 'uday', 'bulk ' || i, 1762938000000 + i,
                    '1-bulk' FROM n""",
                room, count);
            db.execute(
                """
                INSERT INTO change_log (entity_type, entity_id, rev, actor, recorded_at, origin,
                    snapshot)
                SELECT 'message', id, rev, 'uday', created_at, 'local',
                    json_object('room_id', room_id, 'author', author, 'body', body)
                FROM room_messages WHERE id LIKE ?1 || '-%' ORDER BY id""",
                room);
            db.execute(
                """
                INSERT INTO change_heads (entity_type, entity_id, seq, project)
                SELECT entity_type, entity_id, seq, ?2 FROM change_log
                WHERE entity_type = 'message' AND entity_id LIKE ?1 || '-%'""",
                room, project);
          });
    }

    @Override
    public void close() {
      streams.forEach(Stream::end);
      streams.clear();
      servers.forEach(alone -> alone.stop(0));
      servers.clear();
      server.close();
      operations.close();
      bus.close();
      db.close();
    }
  }

  /** An open event stream, read frame by frame as it arrives. */
  private static final class Stream {
    private static final String EOF = "<end of stream>";
    private final HttpResponse<InputStream> response;
    private final BlockingQueue<String> arriving = new LinkedBlockingQueue<>();
    private final List<String> read = new CopyOnWriteArrayList<>();

    Stream(HttpResponse<InputStream> response) {
      this.response = response;
      Thread.ofVirtual()
          .start(
              () -> {
                var reader =
                    new BufferedReader(
                        new InputStreamReader(response.body(), StandardCharsets.UTF_8));
                try {
                  var frame = new StringBuilder();
                  for (var line = reader.readLine(); line != null; line = reader.readLine()) {
                    if (line.isEmpty()) {
                      arriving.add(frame.toString());
                      frame.setLength(0);
                    } else {
                      frame.append(frame.isEmpty() ? "" : "\n").append(line);
                    }
                  }
                } catch (IOException closed) {
                  arriving.add("");
                } finally {
                  arriving.add(EOF);
                }
              });
    }

    /**
     * The next frame, waiting no later than {@code by}, a {@link System#nanoTime} deadline; a
     * stream that sends nothing by then fails the test with the last of what it sent.
     */
    private String next(long by) throws InterruptedException {
      var left = by - System.nanoTime();
      var frame = left <= 0 ? null : arriving.poll(left, TimeUnit.NANOSECONDS);
      if (frame == null) {
        fail(
            "the stream sent nothing wanted in time, after "
                + read.size()
                + " frames, the last of them: "
                + read.subList(Math.max(0, read.size() - 3), read.size()));
      }
      read.add(frame);
      return frame;
    }

    /** The deadline of a wait of {@code seconds}. */
    private static long within(long seconds) {
      return System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds);
    }

    void subscribed() throws InterruptedException {
      assertEquals(": subscribed", next(within(WAIT_SECONDS)));
    }

    /** The next {@code count} change frames, as their data. */
    List<Map<?, ?>> changes(int count) throws InterruptedException {
      return changesOf(null, count);
    }

    /** The next {@code count} change frames of rows of {@code type} (any when null), as data. */
    List<Map<?, ?>> changesOf(String type, int count) throws InterruptedException {
      var by = within(WAIT_SECONDS);
      var changes = new ArrayList<Map<?, ?>>();
      while (changes.size() < count) {
        var frame = next(by);
        if (frame.startsWith("event: change\n")) {
          var change = YamlUtil.parseMap(frame.substring(frame.indexOf("data: ") + 6));
          if (type == null || type.equals(change.get("type"))) {
            changes.add(change);
          }
        }
      }
      return changes;
    }

    /** The next {@code count} change frames, whole. */
    List<String> frames(int count) throws InterruptedException {
      var by = within(WAIT_SECONDS);
      var frames = new ArrayList<String>();
      while (frames.size() < count) {
        var frame = next(by);
        if (frame.startsWith("event: change\n")) {
          frames.add(frame);
        }
      }
      return frames;
    }

    /** The position the next {@code resync} event names, which is also its id. */
    String resync() throws InterruptedException {
      var frame = resyncFrame();
      var data = YamlUtil.parseMap(frame.substring(frame.indexOf("data: ") + 6));
      var position = data.get("position").toString();
      assertTrue(frame.contains("\nid: " + position + "\n"), frame);
      return position;
    }

    /** The next {@code resync} event, whole. */
    String resyncFrame() throws InterruptedException {
      return awaitNew("event: resync\n");
    }

    /** Every frame the stream sends from here until it ends, which it must. */
    List<String> ended() throws InterruptedException {
      var by = within(WAIT_SECONDS);
      var frames = new ArrayList<String>();
      for (var frame = next(by); !EOF.equals(frame); frame = next(by)) {
        if (!frame.isEmpty()) {
          frames.add(frame);
        }
      }
      return frames;
    }

    /** The next frame to arrive that names {@code text}. */
    String awaitNew(String text) throws InterruptedException {
      var by = within(WAIT_SECONDS);
      for (var frame = next(by); ; frame = next(by)) {
        if (frame.contains(text)) {
          return frame;
        }
      }
    }

    /**
     * Every frame the stream sends from here up to the change of the row {@code id}, which must
     * arrive within {@code seconds}.
     */
    List<String> untilChange(String id, long seconds) throws InterruptedException {
      var by = within(seconds);
      var frames = new ArrayList<String>();
      for (var frame = next(by); ; frame = next(by)) {
        frames.add(frame);
        if (frame.startsWith("event: change\n") && frame.contains("\"id\": \"" + id + "\"")) {
          return frames;
        }
      }
    }

    /** The first frame that names {@code text}, among those read so far or the next to arrive. */
    String await(String text) throws InterruptedException {
      for (var frame : read) {
        if (frame.contains(text)) {
          return frame;
        }
      }
      return awaitNew(text);
    }

    /** The {@code id} of every frame read so far that carried one, in order. */
    List<String> ids() {
      var ids = new ArrayList<String>();
      for (var frame : read) {
        frame
            .lines()
            .filter(line -> line.startsWith("id: "))
            .forEach(line -> ids.add(line.substring(4)));
      }
      return ids;
    }

    void end() {
      try {
        response.body().close();
      } catch (IOException e) {
        throw new UncheckedIOException(e);
      }
    }
  }
}
