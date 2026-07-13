/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import ai.singlr.sail.config.Notifications;
import ai.singlr.sail.config.SlackNotifications;
import ai.singlr.sail.config.SpecStatus;
import ai.singlr.sail.engine.SlackPoster;
import ai.singlr.sail.store.SchemaManager;
import ai.singlr.sail.store.SlackThreadStore;
import ai.singlr.sail.store.SpecStore;
import ai.singlr.sail.store.Sqlite;
import ai.singlr.sail.sync.SyncTransitions;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The Brick 3 acceptance loop end-to-end on main's side: a node's synced state transitions run
 * through detection and mapping and land on the Slack reactor, which posts today's exact message
 * copy — one thread root per dispatch, every later step threaded under it, nothing duplicated. The
 * node needs no Slack configuration anywhere in this test; everything is derived from replicated
 * state on main.
 */
class SlackFromSyncNarrationTest {

  private static final Notifications SLACK_ONLY =
      new Notifications(null, null, new SlackNotifications("#sail-activity"));

  @TempDir Path tempDir;
  private Sqlite db;
  private RecordingPoster poster;
  private SlackReactor reactor;
  private Function<String, String> latestReviewStatus = specId -> null;

  @BeforeEach
  void setUp() {
    db = Sqlite.open(tempDir.resolve("test.db"));
    new SchemaManager(db).migrate();
    poster = new RecordingPoster();
    reactor =
        new SlackReactor(project -> SLACK_ONLY, new SlackThreadStore(db), id -> specRow(), poster);
  }

  @AfterEach
  void tearDown() {
    if (db != null) db.close();
  }

  private static SpecStore.SpecRow specRow() {
    return new SpecStore.SpecRow(
        "auth",
        "proj",
        "Add auth",
        SpecStatus.fromWire("in_progress"),
        null,
        "claude-code",
        null,
        null,
        "feat/auth",
        0,
        "uday",
        "",
        "",
        "uday",
        List.of(),
        List.of());
  }

  private void sync(
      String entityType, String id, Map<String, Object> before, Map<String, Object> after) {
    for (var transition : SyncTransitions.detect(entityType, id, before, after)) {
      for (var event :
          SyncTransitionEvents.eventsFor(
              transition, specId -> "proj", latestReviewStatus, "main")) {
        reactor.onEvent(event);
      }
    }
  }

  private static Map<String, Object> spec(String status) {
    var map = new LinkedHashMap<String, Object>();
    map.put("project", "proj");
    map.put("title", "Add auth");
    map.put("status", status);
    map.put("agent", "claude-code");
    map.put("branch", "feat/auth");
    return map;
  }

  private static Map<String, Object> run(String status, Integer exitCode) {
    var map = new LinkedHashMap<String, Object>();
    map.put("project", "proj");
    map.put("spec_id", "auth");
    map.put("agent", "claude-code");
    map.put("status", status);
    map.put("exit_code", exitCode);
    return map;
  }

  private static Map<String, Object> review(
      String reviewStatus, String stageStatus, Map<String, Object> counts) {
    var stage = new LinkedHashMap<String, Object>();
    stage.put("id", "s1");
    stage.put("name", "quality-gate");
    stage.put("status", stageStatus);
    stage.put("finding_counts", counts);
    var map = new LinkedHashMap<String, Object>();
    map.put("spec_id", "auth");
    map.put("iteration", 1);
    map.put("status", reviewStatus);
    map.put("stages", List.of(stage));
    return map;
  }

  @Test
  void theFullLoopNarratesFromSyncedStateWithTodaysCopy() {
    sync("spec", "auth", spec("pending"), spec("in_progress"));
    sync("run", "r1", null, run("running", null));
    sync("run", "r1", run("running", null), run("completed", 0));
    sync("spec", "auth", spec("in_progress"), spec("review"));
    sync("review", "rev1", null, review("running", "running", Map.of()));
    var counts = Map.<String, Object>of("HIGH", 2, "MEDIUM", 2);
    sync(
        "review",
        "rev1",
        review("running", "running", Map.of()),
        review("running", "failed", counts));
    sync("review", "rev1", review("running", "failed", counts), review("failed", "failed", counts));
    latestReviewStatus = specId -> "failed";
    sync("spec", "auth", spec("review"), spec("in_progress"));
    sync("spec", "auth", spec("in_progress"), spec("review"));
    sync("review", "rev2", null, review("running", "running", Map.of()));
    sync(
        "review",
        "rev2",
        review("running", "running", Map.of()),
        review("running", "passed", Map.of()));
    sync(
        "review",
        "rev2",
        review("running", "passed", Map.of()),
        review("passed", "passed", Map.of()));
    sync("spec", "auth", spec("review"), spec("awaiting_merge"));

    var texts = poster.posts.stream().map(SlackPoster.Post::text).toList();
    assertEquals(
        List.of(
            "Dispatched *auth*: Add auth\nproject `proj` · branch `feat/auth` · agent"
                + " `claude-code`",
            "Agent claude-code stopped (exit 0).",
            "Review started: quality-gate.",
            "Review stage failed: quality-gate (2 high, 2 medium).",
            "Fix iteration started.",
            "Review started: quality-gate.",
            "Review stage passed: quality-gate (no findings).",
            "Review passed. Awaiting merge."),
        texts);
  }

  @Test
  void theDispatchRootsTheThreadAndEveryLaterStepRepliesInIt() {
    sync("spec", "auth", spec("pending"), spec("in_progress"));
    sync("run", "r1", run("running", null), run("completed", 0));

    assertEquals(2, poster.posts.size());
    assertNull(poster.posts.get(0).threadTs());
    assertEquals("1.100", poster.posts.get(1).threadTs());
  }

  @Test
  void reApplyingTheSameSyncedStateNeverDuplicatesAPost() {
    sync("spec", "auth", spec("pending"), spec("in_progress"));
    sync("spec", "auth", spec("in_progress"), spec("in_progress"));
    sync("run", "r1", run("running", null), run("completed", 0));
    sync("run", "r1", run("completed", 0), run("completed", 0));

    assertEquals(2, poster.posts.size());
  }

  @Test
  void anEscalationBroadcastsTheThreadReply() {
    sync("spec", "auth", spec("pending"), spec("in_progress"));
    sync(
        "review",
        "rev1",
        review("failed", "failed", Map.of("HIGH", 1)),
        review("escalated", "failed", Map.of("HIGH", 1)));

    var reply = poster.posts.getLast();
    assertEquals(
        "Escalated: review iterations exhausted. This spec needs a human decision.", reply.text());
    assertEquals("1.100", reply.threadTs());
  }

  private static final class RecordingPoster implements SlackPoster {
    final List<Post> posts = new ArrayList<>();

    @Override
    public Result post(Post post) {
      posts.add(post);
      return new Result("C123", "1.100");
    }
  }
}
