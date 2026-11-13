/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.sail.common.DateTimeUtils;
import ai.singlr.sail.config.YamlUtil;
import ai.singlr.sail.harness.RunUsage;
import ai.singlr.sail.identity.ActingAs;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * What a run's row records of its cost (M1, M2): the usage its finish carries, written in the very
 * update and revision that finish it, nothing written as zeros, the model asked replaced by the one
 * reported, what a resumed conversation's other runs recorded taken off its report, and the model
 * and effort its launch passed.
 */
@ActingAs
class RunUsageStoreTest {

  private static final RunUsage REPORTED =
      new RunUsage("claude-opus-5-5", 100L, 200L, 3000L, 40L, 1.5, RunUsage.Source.RESULT);

  @TempDir Path tempDir;
  private Sqlite db;
  private RunStore store;

  @BeforeEach
  void setUp() {
    db = Sqlite.open(tempDir.resolve("test.db"));
    new SchemaManager(db).migrate();
    store = new RunStore(db);
  }

  @AfterEach
  void tearDown() {
    db.close();
  }

  private String run(String session) {
    var id = DateTimeUtils.newId().toString();
    store.create(
        id,
        "acme",
        "auth",
        "node-a",
        "build",
        "claude-code",
        "feat/x",
        "do it",
        null,
        null,
        "/home/dev/.sail/runs/" + id + "/agent.log",
        "sail-agent-" + id);
    if (session != null) {
      store.recordSession(id, session, "startup", "/home/dev/.claude/projects/x/" + session);
    }
    return id;
  }

  private Map<String, Object> journaled(String id) {
    return YamlUtil.parseMap(new ChangeLog(db).head("run", id).orElseThrow().snapshot());
  }

  /** M1: the finish and its usage are one write, so main hears them as one revision. */
  @Test
  void aFinishRecordsWhatTheHarnessReportedInTheRevisionThatFinishesTheRow() {
    var id = run(null);
    store.recordLaunch(id, "opus", "high");

    assertTrue(store.finish(id, "running", "completed", 0, REPORTED));

    var row = store.findById(id).orElseThrow();
    assertEquals("completed", row.status());
    assertEquals(REPORTED, row.usage());
    assertEquals("claude-opus-5-5", row.model(), "the model it reported replaces the one asked");
    assertEquals("high", row.effort());
    var finished = journaled(id);
    assertEquals("completed", finished.get("status"));
    assertEquals(
        Map.of(
            "tokens_in",
            100,
            "tokens_out",
            200,
            "tokens_cache_read",
            3000,
            "tokens_cache_write",
            40,
            "cost_usd",
            1.5,
            "usage_source",
            "result",
            "model",
            "claude-opus-5-5",
            "effort",
            "high"),
        Map.of(
            "tokens_in", finished.get("tokens_in"),
            "tokens_out", finished.get("tokens_out"),
            "tokens_cache_read", finished.get("tokens_cache_read"),
            "tokens_cache_write", finished.get("tokens_cache_write"),
            "cost_usd", finished.get("cost_usd"),
            "usage_source", finished.get("usage_source"),
            "model", finished.get("model"),
            "effort", finished.get("effort")));
    assertFalse(
        store.finish(id, "running", "failed", 1, RunUsage.NONE), "a finished run finishes once");
    assertEquals(REPORTED, store.findById(id).orElseThrow().usage());
  }

  /** M1: a harness that reported nothing records none, with every count null, never zero. */
  @Test
  void aRunWhoseHarnessReportedNothingRecordsNoneWithNullCounts() {
    var id = run(null);
    store.recordLaunch(id, "opus", null);

    store.finish(id, "running", "completed", 0, RunUsage.NONE);

    var row = store.findById(id).orElseThrow();
    assertEquals(RunUsage.NONE.source(), row.usage().source());
    assertEquals(
        new RunUsage("opus", null, null, null, null, null, RunUsage.Source.NONE), row.usage());
    assertNull(journaled(id).get("tokens_in"));
    assertEquals("none", journaled(id).get("usage_source"));
  }

  /** A finish whose report could not be read leaves the usage unrecorded, not none. */
  @Test
  void aFinishWithNoReportReadLeavesTheUsageUnrecorded() {
    var id = run(null);

    store.finish(id, "running", "stopped", null, null);

    assertNull(store.findById(id).orElseThrow().usage());
    assertThrows(
        IllegalArgumentException.class,
        () -> store.finish(id, "stopped", "stopping", null, RunUsage.NONE));
  }

  /**
   * A harness resuming a conversation reports its whole spend: what the conversation's earlier runs
   * here recorded is taken off, so no dollar is counted twice across runs.
   */
  @Test
  void aResumedConversationsRunRecordsOnlyWhatItSpent() {
    var first = run("sess-1");
    store.finish(first, "running", "completed", 0, REPORTED);
    var other = run("sess-2");
    store.finish(other, "running", "completed", 0, REPORTED);
    var resumed = run("sess-1");

    store.finish(
        resumed,
        "running",
        "completed",
        0,
        new RunUsage("claude-opus-5-5", 150L, 260L, 4500L, 40L, 2.0, RunUsage.Source.RESULT));

    assertEquals(
        new RunUsage("claude-opus-5-5", 50L, 60L, 1500L, 0L, 0.5, RunUsage.Source.RESULT),
        store.findById(resumed).orElseThrow().usage());
  }

  /** Scenario 5 at the store: each turn's running total is set, not added, and finishes as is. */
  @Test
  void aLiveRunsTurnsSetItsUsageAndItsFinishKeepsTheLast() {
    var id = run(null);
    store.recordUsage(id, REPORTED);
    var later =
        new RunUsage("claude-opus-5-5", 300L, 600L, 9000L, 120L, 4.5, RunUsage.Source.RESULT);
    store.recordUsage(id, later);
    assertEquals(later, store.findById(id).orElseThrow().usage());

    store.finish(id, "running", "completed", 0, null);
    store.recordUsage(id, REPORTED);

    assertEquals(later, store.findById(id).orElseThrow().usage(), "only while it runs");
    assertEquals(4.5, journaled(id).get("cost_usd"));
  }

  @Test
  void anOperatorsStopThatFindsNoProcessRecordsTheUsageItRead() {
    var id = run(null);

    assertTrue(store.claimStop(id, "stopped", REPORTED, () -> {}));

    assertEquals(REPORTED, store.findById(id).orElseThrow().usage());
    assertEquals(RunStore.STOPPED_BY_OPERATOR, store.findById(id).orElseThrow().stopSource());
  }
}
