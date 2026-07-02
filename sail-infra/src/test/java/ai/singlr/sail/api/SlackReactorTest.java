/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.sail.config.Notifications;
import ai.singlr.sail.config.SlackNotifications;
import ai.singlr.sail.config.SpecStatus;
import ai.singlr.sail.engine.SlackPoster;
import ai.singlr.sail.store.SchemaManager;
import ai.singlr.sail.store.SlackThreadStore;
import ai.singlr.sail.store.SpecStore;
import ai.singlr.sail.store.Sqlite;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SlackReactorTest {

  @TempDir Path tempDir;
  private Sqlite db;
  private SlackThreadStore threads;
  private SpecStore specStore;

  private static final Notifications SLACK_ONLY =
      new Notifications(null, null, new SlackNotifications("#sail-activity"));

  @BeforeEach
  void setUp() {
    db = Sqlite.open(tempDir.resolve("test.db"));
    new SchemaManager(db).migrate();
    threads = new SlackThreadStore(db);
    specStore = new SpecStore(db);
  }

  @AfterEach
  void tearDown() {
    if (db != null) db.close();
  }

  private SlackReactor reactor(SlackPoster poster) {
    return new SlackReactor(project -> SLACK_ONLY, threads, id -> null, poster);
  }

  private static Event event(String type) {
    return Event.of("light", "auth", type, "sail", "host").withId(1L);
  }

  private static Event event(String type, Map<String, Object> data) {
    return Event.of("light", "auth", type, "sail", "host", data).withId(1L);
  }

  private static Event authoritativeStop() {
    return event(
        Event.WellKnownTypes.AGENT_SESSION_STOPPED,
        Map.of(
            Event.WellKnownData.SOURCE,
            Event.WellKnownData.SOURCE_WATCHER,
            Event.WellKnownData.EXIT_CODE,
            0));
  }

  @Test
  void constructorRejectsNullResolver() {
    assertThrows(
        NullPointerException.class, () -> new SlackReactor(null, threads, id -> null, null));
  }

  @Test
  void constructorRejectsNullThreadStore() {
    assertThrows(
        NullPointerException.class,
        () -> new SlackReactor(project -> null, null, id -> null, null));
  }

  @Test
  void constructorRejectsNullSpecLookup() {
    assertThrows(
        NullPointerException.class, () -> new SlackReactor(project -> null, threads, null, null));
  }

  @Test
  void nameIsStable() {
    assertEquals("slack-reactor", reactor(new RecordingPoster()).name());
  }

  @Test
  void filterAcceptsAllNotifiableTypes() {
    var filter = reactor(new RecordingPoster()).filter();
    for (var type : SlackReactor.NOTIFIABLE_TYPES) {
      assertTrue(filter.test(Event.of("p", null, type, "a", "h")), "should accept " + type);
    }
  }

  @Test
  void filterRejectsOtherTypes() {
    var filter = reactor(new RecordingPoster()).filter();
    assertFalse(filter.test(Event.of("p", null, "agent_tool_started", "a", "h")));
    assertFalse(filter.test(Event.of("p", null, "snapshot_created", "a", "h")));
  }

  @Test
  void skipsWhenResolverReturnsNull() {
    var poster = new RecordingPoster();
    var reactor = new SlackReactor(project -> null, threads, id -> null, poster);

    reactor.onEvent(event("spec_dispatched"));

    assertTrue(poster.posts.isEmpty());
  }

  @Test
  void skipsWhenNotificationsHaveNoSlackBlock() {
    var poster = new RecordingPoster();
    var webhookOnly = new Notifications("https://example.com/wh", null);
    var reactor = new SlackReactor(project -> webhookOnly, threads, id -> null, poster);

    reactor.onEvent(event("spec_dispatched"));

    assertTrue(poster.posts.isEmpty());
  }

  @Test
  void warnsLoudlyWhenNoTokenConfigured() {
    var reactor = reactor(null);

    var err = captureStderr(() -> reactor.onEvent(event("spec_dispatched")));

    assertTrue(err.contains("SAIL_SLACK_TOKEN"));
    assertTrue(err.contains("light"));
  }

  @Test
  void dispatchPostsRootAndRecordsThread() {
    var poster = new RecordingPoster();
    reactor(poster).onEvent(event("spec_dispatched", Map.of("branch", "feat/auth")));

    assertEquals(1, poster.posts.size());
    var post = poster.posts.getFirst();
    assertEquals("#sail-activity", post.channel());
    assertNull(post.threadTs());
    assertFalse(post.broadcast());
    assertTrue(post.text().contains("*auth*"));

    var thread = threads.find("light", "auth").orElseThrow();
    assertEquals("C123", thread.channel());
    assertEquals("1.100", thread.threadTs());
  }

  @Test
  void rootMessageUsesSpecTitleFromLookup() {
    specStore.create(
        new SpecStore.SpecRow(
            "auth",
            "light",
            "OAuth flow",
            SpecStatus.PENDING,
            null,
            "claude-code",
            null,
            null,
            null,
            0,
            null,
            "",
            "",
            null,
            List.of(),
            List.of()));
    var poster = new RecordingPoster();
    var reactor =
        new SlackReactor(
            project -> SLACK_ONLY, threads, SlackReactor.specLookup(specStore), poster);

    reactor.onEvent(event("spec_dispatched"));

    assertTrue(poster.posts.getFirst().text().contains("OAuth flow"));
    assertTrue(poster.posts.getFirst().text().contains("agent `claude-code`"));
  }

  @Test
  void specLookupReturnsNullForUnknownSpec() {
    assertNull(SlackReactor.specLookup(specStore).apply("ghost"));
  }

  @Test
  void redispatchStartsANewThread() {
    var poster = new RecordingPoster();
    var reactor = reactor(poster);

    reactor.onEvent(event("spec_dispatched"));
    poster.nextTs = "2.200";
    reactor.onEvent(event("spec_restarted"));

    assertNull(poster.posts.get(1).threadTs());
    assertEquals("2.200", threads.find("light", "auth").orElseThrow().threadTs());
  }

  @Test
  void lifecycleEventsThreadUnderTheRoot() {
    var poster = new RecordingPoster();
    var reactor = reactor(poster);

    reactor.onEvent(event("spec_dispatched"));
    reactor.onEvent(authoritativeStop());
    reactor.onEvent(event("review_stage_started", Map.of("detail", "security")));

    var stop = poster.posts.get(1);
    assertEquals("C123", stop.channel());
    assertEquals("1.100", stop.threadTs());
    assertTrue(stop.text().contains("exit 0"));
    assertEquals("1.100", poster.posts.get(2).threadTs());
  }

  @Test
  void turnEndStopWithoutSourceIsIgnored() {
    var poster = new RecordingPoster();
    var reactor = reactor(poster);

    reactor.onEvent(event("spec_dispatched"));
    reactor.onEvent(event(Event.WellKnownTypes.AGENT_SESSION_STOPPED));

    assertEquals(1, poster.posts.size());
  }

  @Test
  void escalationBroadcastsToTheChannel() {
    var poster = new RecordingPoster();
    var reactor = reactor(poster);

    reactor.onEvent(event("spec_dispatched"));
    reactor.onEvent(event("review_escalated"));

    var escalation = poster.posts.get(1);
    assertEquals("1.100", escalation.threadTs());
    assertTrue(escalation.broadcast());
  }

  @Test
  void otherThreadRepliesDoNotBroadcast() {
    var poster = new RecordingPoster();
    var reactor = reactor(poster);

    reactor.onEvent(event("spec_dispatched"));
    reactor.onEvent(event("review_completed"));

    assertFalse(poster.posts.get(1).broadcast());
  }

  @Test
  void replyWithoutARecordedThreadIsAdoptedAsRoot() {
    var poster = new RecordingPoster();
    var reactor = reactor(poster);

    reactor.onEvent(event("review_errored", Map.of("detail", "boom")));

    var post = poster.posts.getFirst();
    assertNull(post.threadTs());
    assertFalse(post.broadcast());
    assertEquals("1.100", threads.find("light", "auth").orElseThrow().threadTs());
  }

  @Test
  void eventWithoutSpecPostsStandaloneAndRecordsNothing() {
    var poster = new RecordingPoster();
    var reactor = reactor(poster);

    reactor.onEvent(
        Event.of("light", null, "guardrail_triggered", "sail", "h", Map.of("reason", "budget"))
            .withId(1L));

    assertNull(poster.posts.getFirst().threadTs());
    assertTrue(threads.find("light", "null").isEmpty());
  }

  @Test
  void failedRootPostRecordsNoThread() {
    var poster = new RecordingPoster();
    poster.failAll = true;
    var reactor = reactor(poster);

    reactor.onEvent(event("spec_dispatched"));

    assertTrue(threads.find("light", "auth").isEmpty());
  }

  @Test
  void posterExceptionIsCaughtAndLogged() {
    var reactor =
        reactor(
            post -> {
              throw new IllegalStateException("boom");
            });

    var err = captureStderr(() -> reactor.onEvent(event("spec_dispatched")));

    assertTrue(err.contains("failed to process spec_dispatched"));
    assertTrue(err.contains("boom"));
  }

  @Test
  void defaultPosterIsNullWithoutToken() {
    assertNull(SlackReactor.defaultPoster());
  }

  @Test
  void defaultPosterBuildsClientWhenTokenPresent() {
    System.setProperty("SAIL_SLACK_TOKEN", "xoxb-test");
    try {
      assertNotNull(SlackReactor.defaultPoster());
    } finally {
      System.clearProperty("SAIL_SLACK_TOKEN");
    }
  }

  @Test
  void withDefaultsDoesNotThrow() {
    assertNotNull(SlackReactor.withDefaults(threads, specStore));
  }

  private static String captureStderr(Runnable work) {
    var original = System.err;
    var buffer = new ByteArrayOutputStream();
    System.setErr(new PrintStream(buffer));
    try {
      work.run();
    } finally {
      System.setErr(original);
    }
    return buffer.toString();
  }

  private static final class RecordingPoster implements SlackPoster {
    final List<Post> posts = new ArrayList<>();
    String nextTs = "1.100";
    boolean failAll;

    @Override
    public Result post(Post post) {
      posts.add(post);
      return failAll ? null : new Result("C123", nextTs);
    }
  }
}
