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
import ai.singlr.sail.identity.Acting;
import ai.singlr.sail.identity.ActingAs;
import ai.singlr.sail.sync.SyncBox;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * What a run's row records of its cost (M1, M2): the usage its finish carries, written in the very
 * update and revision that finish it, nothing written as zeros, the model asked replaced by the one
 * reported, and the model and effort its launch passed. A report is its harness session's running
 * total, so a run records its share: the report less what the session's other runs were given, kept
 * per session where retention never reaches ({@code session_usage}). A report below the session's
 * highest tells no share and is recorded unattributable (M9). The four histories of the first
 * review each end with the harness's own total or an honest lower bound.
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
    HeldProjects.hold(db, "proj", "acme");
    store = new RunStore(db);
    Acting.as("uday", () -> new SpecStore(db).create(SyncBox.spec("auth", "t", "in_progress")));
  }

  @AfterEach
  void tearDown() {
    db.close();
  }

  private String run(String session) {
    var id = DateTimeUtils.newId().toString();
    store.create(
        id,
        "proj",
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
    store.recordLaunch(id, "opus", "high", null);

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
    store.recordLaunch(id, "opus", null, null);

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

  private static RunUsage report(long tokens, double usd) {
    return new RunUsage("m", tokens, tokens, tokens, tokens, usd, RunUsage.Source.RESULT);
  }

  private void finish(String id, RunUsage usage) {
    assertTrue(store.finish(id, "running", "completed", 0, usage));
  }

  private RunUsage usageOf(String id) {
    return store.findById(id).orElseThrow().usage();
  }

  private SpecStore.KeptRuns kept() {
    return new SpecStore(db).recorded(List.of("auth")).get("auth").runs();
  }

  /** A share that tells: a session given 100 that reports 300 gives its run 200, not 100 or 300. */
  @Test
  void aResumedSessionsRunRecordsItsReportLessWhatItsSessionGave() {
    var first = run("sess-1");
    finish(first, report(100, 1.0));
    var other = run("sess-2");
    finish(other, report(70, 0.7));
    var resumed = run("sess-1");

    finish(resumed, report(300, 3.0));

    assertEquals(report(200, 2.0), usageOf(resumed));
    assertEquals(370L, kept().tokensIn());
  }

  /** Review 1, history 1: the session's earlier run is erased by retention before it resumes. */
  @Test
  void aSessionWhoseEarlierRunWasErasedEndsWithItsHarnessesOwnTotal() {
    var a = run("S1");
    finish(a, report(100, 1.0));
    var b = run("S1");
    finish(b, report(150, 1.5));
    Acting.system(
        () -> new Erasure(db).erase(List.of(new Erasure.Target(Erasure.RUN, a)), "retention"));
    var c = run("S1");

    finish(c, report(180, 1.8));

    assertEquals(30L, usageOf(c).tokensIn());
    assertEquals(180L, kept().tokensIn(), "the harness's own total, not 280");
    assertEquals(1.8, kept().costUsd(), 1e-9);
    assertEquals(0L, kept().withoutUsage());
  }

  /**
   * Review 1, history 2: the later run finishes first. The earlier one's report is below what the
   * session already reported, so its share cannot be told: it is unattributable, adds nothing, and
   * its spec counts it as a run without usage, so its tokens and dollars are a lower bound.
   */
  @Test
  void anEarlierRunFinishingAfterALaterOneOfItsSessionIsUnattributable() {
    var a = run("S3");
    var b = run("S3");
    finish(b, report(150, 1.5));

    finish(a, report(100, 1.0));

    assertEquals(
        new RunUsage("m", null, null, null, null, null, RunUsage.Source.UNATTRIBUTABLE),
        usageOf(a));
    assertEquals(150L, kept().tokensIn());
    assertEquals(1L, kept().withoutUsage());
    assertEquals(1L, kept().withoutCost());
    assertEquals("unattributable", journaled(a).get("usage_source"));
  }

  /**
   * Review 1, history 3: a resumed run whose hook never reported its session still has it, stamped
   * at launch from the session it resumed; a run that names no session at all started one, so its
   * report is its own.
   */
  @Test
  void aResumedRunHasItsSessionFromItsLaunchAndARunWithNoneKeepsItsWholeReport() {
    var a = run("S4");
    finish(a, RunUsage.NONE);
    var b = run("S4");
    finish(b, report(100, 1.0));
    var resumed = run(null);
    store.recordLaunch(resumed, "opus", null, "S4");

    finish(resumed, report(150, 1.5));
    var fresh = run(null);
    finish(fresh, report(40, 0.4));

    assertEquals("S4", store.findById(resumed).orElseThrow().sessionId());
    assertEquals(50L, usageOf(resumed).tokensIn());
    assertEquals(40L, usageOf(fresh).tokensIn());
    assertEquals(190L, kept().tokensIn());
  }

  /**
   * Review 1, history 4: a harness that restarted its count at zero. Below the session's highest,
   * its share cannot be told and the spec's figures are a lower bound; above it, the session ends
   * with the harness's own last total.
   */
  @Test
  void aHarnessThatRestartedAtZeroIsUnattributableBelowTheHighestAndItsOwnTotalAbove() {
    var a = run("S2");
    finish(a, report(100, 1.0));
    var restarted = run("S2");
    finish(restarted, report(60, 0.6));
    var b = run("S7");
    finish(b, report(100, 1.0));
    var larger = run("S7");

    finish(larger, report(500, 5.0));

    assertEquals(RunUsage.Source.UNATTRIBUTABLE, usageOf(restarted).source());
    assertEquals(400L, usageOf(larger).tokensIn());
    assertEquals(600L, kept().tokensIn());
    assertEquals(1L, kept().withoutUsage());
  }

  /**
   * Scenario 5 at the store: each turn's running total sets the share; the finish keeps the last.
   */
  @Test
  void aLiveRunsTurnsSetItsUsageAndItsFinishKeepsTheLast() {
    var sibling = run("live");
    finish(sibling, report(100, 1.0));
    var id = run("live");
    store.recordUsage(id, report(110, 1.1));
    store.recordUsage(id, report(160, 1.6));
    assertEquals(60L, usageOf(id).tokensIn());

    assertTrue(store.finishAsTurnsRecorded(id, "completed", 0));
    store.recordUsage(id, report(900, 9.0));

    assertEquals(60L, usageOf(id).tokensIn(), "only while it runs");
    assertEquals(0.6, (Double) journaled(id).get("cost_usd"), 1e-9);
    assertEquals(160L, kept().tokensIn());
    assertFalse(store.finishAsTurnsRecorded(id, "failed", 1), "it finishes once");
  }

  /** Review 1, minor 6: a live member that answered no turn finishes with none, in one write. */
  @Test
  void aLiveRunThatAnsweredNoTurnFinishesWithNone() {
    var id = run(null);

    assertTrue(store.finishAsTurnsRecorded(id, "stopped", null));

    assertEquals(RunUsage.Source.NONE, usageOf(id).source());
    assertThrows(
        IllegalArgumentException.class, () -> store.finishAsTurnsRecorded(id, "stopping", null));
  }

  /** A member that never started records none, so its directory is not kept for a read. */
  @Test
  void aRunEndedUnstartedRecordsNone() {
    var id = run(null);

    assertTrue(store.transitionUnstarted(id, "stopped"));

    assertEquals(RunUsage.NONE, usageOf(id));
  }

  /**
   * An operator's stop leaves the report unread; the sweep reads it later and records it once, in a
   * revision of its own, as its share of its session; a run still unread is listed for the sweep of
   * the box that ran it.
   */
  @Test
  void anOperatorsStopLeavesTheReportUnreadForTheSweepToRecordOnce() {
    var sibling = run("op");
    finish(sibling, report(100, 1.0));
    var id = run("op");

    assertTrue(store.claimStop(id, "stopped", () -> {}));

    assertNull(usageOf(id));
    assertEquals(RunStore.STOPPED_BY_OPERATOR, store.findById(id).orElseThrow().stopSource());
    assertEquals(List.of(id), store.unread("node-a").stream().map(RunStore.RunRow::id).toList());
    assertEquals(List.of(), store.unread("node-b"));
    assertTrue(store.recordRead(id, report(130, 1.3)));
    assertFalse(store.recordRead(id, report(900, 9.0)), "recorded once");
    assertEquals(30L, usageOf(id).tokensIn());
    assertEquals(30, journaled(id).get("tokens_in"));
    assertEquals(List.of(), store.unread("node-a"));
    assertEquals(130L, kept().tokensIn());
    assertFalse(store.recordRead(run(null), report(1, 1.0)), "a run still running is not read");
  }

  /**
   * A session's usage is box-local and outlives retention's erasure of its runs; it goes with the
   * conversation it is of.
   */
  @Test
  void aSessionsUsageOutlivesItsRunsAndGoesWithItsConversation() {
    var a = run("kept");
    finish(a, report(100, 1.0));
    Acting.system(
        () -> new Erasure(db).erase(List.of(new Erasure.Target(Erasure.RUN, a)), "retention"));
    assertEquals(1, sessions());

    Acting.system(
        () -> new Erasure(db).erase(List.of(new Erasure.Target(Erasure.SPEC, "auth")), "local"));

    assertEquals(0, sessions());
  }

  private long sessions() {
    return db.queryOne("SELECT COUNT(*) FROM session_usage", row -> row.integer(0)).orElseThrow();
  }
}
