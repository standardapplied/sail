/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.sail.common.DateTimeUtils;
import ai.singlr.sail.config.RetentionConfig;
import ai.singlr.sail.config.SpecStatus;
import ai.singlr.sail.identity.Acting;
import ai.singlr.sail.identity.ActingAs;
import ai.singlr.sail.identity.Actor;
import ai.singlr.sail.identity.Role;
import ai.singlr.sail.store.BlobStore;
import ai.singlr.sail.store.EraseRequests;
import ai.singlr.sail.store.Erasure;
import ai.singlr.sail.store.MessageStore;
import ai.singlr.sail.store.ProjectStore;
import ai.singlr.sail.store.RoomStore;
import ai.singlr.sail.store.RunStore;
import ai.singlr.sail.store.SchemaManager;
import ai.singlr.sail.store.SpecStore;
import ai.singlr.sail.store.Sqlite;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.api.io.TempDir;

/**
 * The one prune method: who may erase what, a dry run that is the erasure itself undone, a node
 * that asks main instead of erasing, the selectors a policy and retention use, and restore of a
 * deleted spec beside the refusal a pruned one gets.
 */
@ActingAs
class SpecPruneTest {

  private static final Actor ADMIN = new Actor("ops", Role.ADMIN, Actor.Lane.API);
  private static final Actor UDAY = new Actor("uday", Role.MEMBER, Actor.Lane.API);
  private static final Actor MADY = new Actor("mady", Role.MEMBER, Actor.Lane.API);
  private static final Instant NOW = Instant.parse("2026-09-23T00:00:00Z");

  @TempDir Path dir;
  private Sqlite db;
  private SpecStore specs;
  private RoomStore rooms;
  private final AtomicBoolean authoritative = new AtomicBoolean(true);
  private final AtomicReference<Instant> clock = new AtomicReference<>(NOW);
  private EventBus bus;
  private SpecPruner pruner;
  private GlobalSpecOperations ops;

  @BeforeEach
  void setUp() {
    db = Sqlite.open(dir.resolve("prune.db"));
    new SchemaManager(db).migrate();
    specs = new SpecStore(db);
    rooms = new RoomStore(db);
    bus = new EventBus();
    pruner = new SpecPruner(db, bus, authoritative::get, clock::get);
    ops = new GlobalSpecOperations(specs, null, bus, new RunStore(db), () -> rooms);
  }

  @AfterEach
  void tearDown() {
    bus.close();
    db.close();
  }

  @Test
  void anOwnersDryRunReportsExactlyWhatTheirPruneThenErases() {
    archived("old", "uday");
    specs.create(row("kept", "uday", SpecStatus.PENDING));

    var rehearsed =
        Acting.by(UDAY, () -> pruner.prune(PruneRequest.ids(List.of("old"), true), UDAY));
    assertTrue(specs.findById("old").isPresent(), "a dry run erases nothing");
    var published = bus.publishedCount();
    var erased = Acting.by(UDAY, () -> pruner.prune(PruneRequest.ids(List.of("old"), false), UDAY));

    assertTrue(rehearsed.dryRun());
    assertFalse(erased.dryRun());
    assertEquals(withoutDryRun(rehearsed.toMap()), withoutDryRun(erased.toMap()));
    assertEquals(1, erased.specs());
    assertEquals(1, erased.rooms());
    assertEquals(2, erased.messages());
    assertEquals(1, erased.runs());
    assertTrue(erased.blobBytes() > 0, "the body only this spec held is freed");
    assertTrue(specs.findById("old").isEmpty());
    assertTrue(rooms.findById("old").isEmpty());
    assertTrue(specs.findById("kept").isPresent());
    assertEquals(
        5,
        count("SELECT count(*) FROM change_log WHERE kind = 'erasure' AND actor = 'uday'"),
        "one erasure row per erased entity, naming who pruned");
    assertTrue(bus.publishedCount() > published, "the board hears the spec went");
  }

  @Test
  void anotherMembersSpecAViewerAndAnAgentAreRefused() {
    archived("theirs", "uday");

    assertRefused(
        ErrorCode.FORBIDDEN_NOT_ASSIGNEE,
        () -> Acting.by(MADY, () -> pruner.prune(PruneRequest.ids(List.of("theirs"), true), MADY)));
    assertRefused(
        ErrorCode.READ_ONLY_CREDENTIAL,
        () ->
            Acting.by(
                new Actor("uday", Role.VIEWER, Actor.Lane.API),
                () ->
                    pruner.prune(
                        PruneRequest.ids(List.of("theirs"), true),
                        new Actor("uday", Role.VIEWER, Actor.Lane.API))));
    assertRefused(
        ErrorCode.AGENT_LANE_FORBIDDEN,
        () ->
            Acting.by(
                Actor.agentPrincipal("claude/r", "uday"),
                () ->
                    pruner.prune(
                        PruneRequest.ids(List.of("theirs"), false),
                        Actor.agentPrincipal("claude/r", "uday"))));
    assertTrue(specs.findById("theirs").isPresent());
    assertEquals(
        1,
        Acting.by(ADMIN, () -> pruner.prune(PruneRequest.ids(List.of("theirs"), true), ADMIN))
            .specs());
  }

  @Test
  void aPolicyAProjectOrRetentionIsAdminOnly() {
    var policy = new PruneRequest.Policy(List.of(SpecStatus.ARCHIVED), Duration.ofDays(1), null);

    assertRefused(
        ErrorCode.FORBIDDEN_ADMIN_ONLY,
        () ->
            Acting.by(
                UDAY, () -> pruner.prune(new PruneRequest(List.of(), policy, null, true), UDAY)));
    assertRefused(
        ErrorCode.FORBIDDEN_ADMIN_ONLY,
        () -> Acting.by(UDAY, () -> pruner.prune(PruneRequest.project("acme", true), UDAY)));
  }

  @Test
  void aPolicyTakesOnlySpecsLongEnoughInTheStatusAndOnlyInItsProject() {
    archived("ancient", "uday");
    archived("fresh", "uday");
    specs.create(row("cancelled-long-ago", "uday", SpecStatus.CANCELLED));
    specs.create(row("elsewhere", "uday", SpecStatus.ARCHIVED, "other"));
    archived("talking", "uday");
    var wake = run(new RunStore(db), "running", null);
    db.execute("UPDATE runs SET room_id = 'talking' WHERE id = ?", wake);
    db.execute(
        "UPDATE specs SET archived_at = '2026-01-01T00:00:00Z' WHERE id IN ('ancient',"
            + " 'elsewhere', 'talking')");
    db.execute("UPDATE specs SET cancelled_at = '2026-01-01T00:00:00Z'");

    var archivedOnly =
        Acting.by(
            ADMIN,
            () ->
                pruner.prune(
                    new PruneRequest(
                        List.of(),
                        new PruneRequest.Policy(
                            List.of(SpecStatus.ARCHIVED), Duration.ofDays(90), "proj"),
                        null,
                        true),
                    ADMIN));
    var both =
        Acting.by(
            ADMIN,
            () ->
                pruner.prune(
                    new PruneRequest(
                        List.of(),
                        new PruneRequest.Policy(
                            List.of(SpecStatus.ARCHIVED, SpecStatus.CANCELLED),
                            Duration.ofDays(90),
                            null),
                        null,
                        true),
                    ADMIN));

    assertEquals(
        List.of("spec:ancient"),
        specIds(archivedOnly),
        "an agent still at work in a room keeps it");
    assertEquals(
        List.of("spec:ancient", "spec:cancelled-long-ago", "spec:elsewhere"), specIds(both));
  }

  @Test
  void aWholeProjectGoesWithEverythingInIt() {
    archived("old", "uday");
    run(new RunStore(db), "running", null, "old");
    new ProjectStore(db).upsert("proj", "name: proj\n");

    var report = Acting.by(ADMIN, () -> pruner.prune(PruneRequest.project("proj", false), ADMIN));

    assertEquals(1, report.projects());
    assertEquals(1, report.specs());
    assertEquals(2, report.runs(), "a purge takes a project's runs, going or not");
    assertTrue(new ProjectStore(db).findByName("proj").isEmpty());
  }

  @Test
  void anErasureWhoseCollectionCannotRunStillErasesAndSaysHowToFreeTheContent() {
    archived("old", "uday");
    var err = new ByteArrayOutputStream();
    var original = System.err;
    PruneReport report;
    try (var capture = new PrintStream(err, true, StandardCharsets.UTF_8)) {
      System.setErr(capture);
      report =
          db.transaction(
              () ->
                  Acting.by(
                      UDAY, () -> pruner.prune(PruneRequest.ids(List.of("old"), false), UDAY)));
    } finally {
      System.setErr(original);
    }

    assertEquals(1, report.specs());
    assertEquals(0, report.blobBytes());
    assertTrue(specs.findById("old").isEmpty());
    assertTrue(
        err.toString(StandardCharsets.UTF_8).contains("'sail sync gc' frees it"),
        err.toString(StandardCharsets.UTF_8));
  }

  @Test
  void aProjectThisBoxHoldsNothingOfIsNeverSpent() {
    var report = Acting.by(ADMIN, () -> pruner.prune(PruneRequest.project("typo", false), ADMIN));

    assertEquals(List.of(), report.entries());
    assertEquals(0, count("SELECT count(*) FROM change_log"));
  }

  @Test
  void onlyAnArchivedCancelledOrDeletedSpecIsPrunedAndNeverWhileARunOfItGoesOn() {
    specs.create(row("live", "uday", SpecStatus.IN_PROGRESS));
    specs.create(row("dropped", "uday", SpecStatus.CANCELLED));
    specs.create(row("deleted", "uday", SpecStatus.PENDING));
    specs.delete("deleted");
    archived("busy", "uday");
    var going = run(new RunStore(db), "running", null, "busy");

    var live =
        assertThrows(
            ApiException.class,
            () ->
                Acting.by(
                    ADMIN, () -> pruner.prune(PruneRequest.ids(List.of("live"), true), ADMIN)));
    assertEquals(ErrorCode.SPEC_NOT_PRUNABLE, live.failure().errorCode());
    assertTrue(live.getMessage().contains("is in_progress"), live.getMessage());
    var busy =
        assertThrows(
            ApiException.class,
            () ->
                Acting.by(
                    UDAY, () -> pruner.prune(PruneRequest.ids(List.of("busy"), false), UDAY)));
    assertEquals(ErrorCode.SPEC_NOT_PRUNABLE, busy.failure().errorCode());
    assertTrue(busy.getMessage().contains(going), busy.getMessage());
    assertTrue(specs.findById("busy").isPresent());

    assertEquals(
        2,
        Acting.by(
                UDAY,
                () -> pruner.prune(PruneRequest.ids(List.of("dropped", "deleted"), false), UDAY))
            .specs());
  }

  @Test
  void retentionTakesOldMessagesButNeverAParentAYoungReplyPointsAtAndOnlyFinishedRuns() {
    Acting.as(
        "uday",
        () ->
            rooms.create(
                new RoomStore.RoomRow(
                    "lobby", "proj", "Lobby", null, null, null, "uday", null, null, "uday")));
    var messages = new MessageStore(db);
    var oldAlone = messages.append("lobby", "uday", "old and alone", null);
    var oldParent = messages.append("lobby", "uday", "old, but answered lately", null);
    var youngReply = messages.append("lobby", "uday", "a young reply", oldParent.id());
    db.execute(
        "UPDATE room_messages SET created_at = '2025-01-01T00:00:00Z' WHERE id IN (?, ?)",
        oldAlone.id(),
        oldParent.id());
    var runs = new RunStore(db);
    var finished = run(runs, "completed", "2025-01-01T00:00:00Z");
    var running = run(runs, "running", null);
    var recent = run(runs, "failed", "2026-09-22T00:00:00Z");

    var question = messages.append("lobby", "claude/r1", "old, and still waiting", null, true);
    db.execute(
        "UPDATE room_messages SET created_at = '2025-01-01T00:00:00Z' WHERE id = ?", question.id());

    var report =
        pruner.retain(new RetentionConfig(null, Duration.ofDays(180), Duration.ofDays(30)));

    assertEquals(
        List.of("message:" + oldAlone.id(), "run:" + finished),
        report.entries().stream().map(target -> target.type() + ":" + target.id()).toList());
    assertTrue(messages.findById(oldParent.id()).isPresent());
    assertTrue(messages.findById(youngReply.id()).isPresent());
    assertTrue(messages.findById(question.id()).isPresent(), "an open question is never swept");
    assertTrue(runs.findById(running).isPresent());
    assertTrue(runs.findById(recent).isPresent());
  }

  @Test
  void aChildWrittenAfterTheDryRunGoesWithItsParentWhenTheApplyErases() {
    archived("old", "uday");
    Acting.by(UDAY, () -> pruner.prune(PruneRequest.ids(List.of("old"), true), UDAY));
    var late = run(new RunStore(db), "completed", "2026-09-22T00:00:00Z", "old");

    var report = Acting.by(UDAY, () -> pruner.prune(PruneRequest.ids(List.of("old"), false), UDAY));

    assertTrue(report.entries().contains(new Erasure.Target(Erasure.RUN, late)));
    assertEquals(0, count("SELECT count(*) FROM runs WHERE spec_id = 'old'"));
    assertEquals(0, count("SELECT count(*) FROM change_log WHERE kind <> 'erasure'"));
  }

  @Test
  void theApplyChecksTheOwnerAgainNeverTrustingTheDryRun() {
    archived("old", "uday");
    Acting.by(UDAY, () -> pruner.prune(PruneRequest.ids(List.of("old"), true), UDAY));
    specs.update(row("old", "mady", SpecStatus.ARCHIVED));

    assertRefused(
        ErrorCode.FORBIDDEN_NOT_ASSIGNEE,
        () -> Acting.by(UDAY, () -> pruner.prune(PruneRequest.ids(List.of("old"), false), UDAY)));
    assertTrue(specs.findById("old").isPresent());
  }

  @Test
  void aLargePolicyIsErasedInBoundedBatchesThatStillTakeEverything() {
    for (var i = 0; i < 450; i++) {
      specs.create(row("old-" + i, "uday", SpecStatus.ARCHIVED));
    }
    db.execute("UPDATE specs SET archived_at = '2026-01-01T00:00:00Z'");
    var policy =
        new PruneRequest(
            List.of(),
            new PruneRequest.Policy(List.of(SpecStatus.ARCHIVED), Duration.ofDays(90), null),
            null,
            false);

    var report = Acting.by(ADMIN, () -> pruner.prune(policy, ADMIN));

    assertEquals(450, report.specs());
    assertEquals(0, count("SELECT count(*) FROM specs"));
  }

  @Test
  void anUnknownSpecIsNotFoundAndAPrunedOneIsNothingToDo() {
    archived("old", "uday");
    Acting.by(UDAY, () -> pruner.prune(PruneRequest.ids(List.of("old"), false), UDAY));

    assertRefused(
        ErrorCode.SPEC_NOT_FOUND,
        () -> Acting.by(UDAY, () -> pruner.prune(PruneRequest.ids(List.of("never"), true), UDAY)));
    var again = Acting.by(UDAY, () -> pruner.prune(PruneRequest.ids(List.of("old"), false), UDAY));

    assertEquals(List.of(), again.entries());
    assertFalse(again.dryRun());
    assertFalse(again.requested());
  }

  @Test
  void onANodeAnApplyIsAskedOfMainAndNothingIsErasedHereYet() {
    authoritative.set(false);
    archived("old", "uday");
    db.execute("UPDATE specs SET base_rev = rev WHERE id = 'old'");

    var report = Acting.by(UDAY, () -> pruner.prune(PruneRequest.ids(List.of("old"), false), UDAY));

    assertTrue(report.requested());
    assertFalse(report.dryRun());
    assertEquals(1, report.specs(), "the report is this box's view of what goes");
    assertTrue(specs.findById("old").isPresent(), "main erases; this box follows on sync");
    assertEquals(List.of("old"), new EraseRequests(db).pending(Erasure.SPEC));
    assertRefused(
        ErrorCode.INVALID_REQUEST,
        () ->
            Acting.by(
                ADMIN,
                () ->
                    pruner.prune(
                        new PruneRequest(
                            List.of(),
                            new PruneRequest.Policy(
                                List.of(SpecStatus.ARCHIVED), Duration.ofDays(1), null),
                            null,
                            false),
                        ADMIN)));
    assertThrows(
        IllegalStateException.class,
        () -> pruner.retain(new RetentionConfig(Duration.ofDays(1), null, null)));
  }

  @Test
  void onANodeASpecMainNeverSawIsDiscardedHereNotAskedOfMainAndItsIdStaysFree() {
    authoritative.set(false);
    archived("draft", "uday");
    var published = bus.publishedCount();

    var report =
        Acting.by(UDAY, () -> pruner.prune(PruneRequest.ids(List.of("draft"), false), UDAY));

    assertFalse(report.requested(), "nothing was asked of main");
    assertEquals(1, report.specs());
    assertTrue(specs.findById("draft").isEmpty());
    assertEquals(List.of(), new EraseRequests(db).pending(Erasure.SPEC));
    assertEquals(0, count("SELECT count(*) FROM change_log WHERE kind = 'erasure'"));
    assertTrue(bus.publishedCount() > published, "the board hears the spec went");
    assertEquals(0, new BlobStore(db).collectable(), "its content went with it");
  }

  @Test
  void onANodeAProjectMainHoldsSpecsOfIsAskedOfMainEvenIfItsCatalogRowNeverSynced() {
    authoritative.set(false);
    archived("synced", "uday");
    db.execute("UPDATE specs SET base_rev = rev WHERE id = 'synced'");

    var report = Acting.by(ADMIN, () -> pruner.prune(PruneRequest.project("proj", false), ADMIN));

    assertTrue(report.requested());
    assertTrue(specs.findById("synced").isPresent(), "main erases it; this box follows");
    assertEquals(List.of("proj"), new EraseRequests(db).pending(Erasure.PROJECT));
  }

  @Test
  void aDeletedSpecIsRestoredWithTheIdentityRoomItMinted() {
    archived("old", "uday");
    var rev = specs.latestRev("old");
    Acting.by(UDAY, () -> ops.delete("old", UDAY));
    assertTrue(rooms.findById("old").isEmpty());

    var restored = Acting.by(UDAY, () -> ops.restore("old", new SpecRestoreRequest(rev), UDAY));

    assertEquals("old", restored.spec().id());
    assertTrue(specs.findById("old").isPresent());
    assertTrue(rooms.findById("old").isPresent(), "the room it converses in comes back with it");
    assertRefused(
        ErrorCode.FORBIDDEN_NOT_ASSIGNEE,
        () -> {
          Acting.by(UDAY, () -> ops.delete("old", UDAY));
          Acting.by(MADY, () -> ops.restore("old", new SpecRestoreRequest(rev), MADY));
        });
  }

  @Test
  void aPrunedSpecIsGoneAndARevisionHistoryNoLongerKeepsIsNamed() {
    archived("old", "uday");
    var rev = specs.latestRev("old");
    specs.create(row("kept", "uday", SpecStatus.PENDING));
    Acting.by(UDAY, () -> pruner.prune(PruneRequest.ids(List.of("old"), false), UDAY));

    var pruned =
        assertThrows(
            ApiException.class,
            () -> Acting.by(UDAY, () -> ops.restore("old", new SpecRestoreRequest(rev), UDAY)));
    var missing =
        assertThrows(
            ApiException.class,
            () ->
                Acting.by(UDAY, () -> ops.restore("kept", new SpecRestoreRequest("9-gone"), UDAY)));

    assertEquals(ErrorCode.SPEC_PRUNED, pruned.failure().errorCode());
    assertEquals(410, ErrorCode.SPEC_PRUNED.httpCode());
    assertTrue(pruned.getMessage().contains("was pruned by uday"), pruned.getMessage());
    assertEquals(ErrorCode.INVALID_REQUEST, missing.failure().errorCode());
    assertTrue(missing.getMessage().contains("not in its retained history"), missing.getMessage());
    assertRefused(
        ErrorCode.SPEC_NOT_FOUND,
        () -> Acting.by(UDAY, () -> ops.restore("never", new SpecRestoreRequest(rev), UDAY)));
  }

  @Test
  void theRequestIsValidatedFromItsBody() {
    var named = PruneRequest.fromMap(Map.of("ids", List.of("a", "b")));
    assertEquals(List.of("a", "b"), named.ids());
    assertTrue(named.dryRun(), "a prune reports unless the body says otherwise");
    var policy =
        PruneRequest.fromMap(
            Map.of(
                "policy",
                Map.of("statuses", List.of("archived"), "older_than_days", 90, "project", "p"),
                "dry_run",
                false));
    assertEquals(Duration.ofDays(90), policy.policy().olderThan());
    assertEquals("p", policy.policy().project());
    assertFalse(policy.dryRun());
    assertEquals("acme", PruneRequest.fromMap(Map.of("project", "acme")).project());

    for (var bad :
        List.<Map<String, Object>>of(
            Map.of(),
            Map.of("ids", "a"),
            Map.of("ids", List.of("a"), "project", "p"),
            Map.of("ids", List.of("../x")),
            Map.of("policy", "archived"),
            Map.of("policy", Map.of("statuses", "archived", "older_than_days", 1)),
            Map.of("policy", Map.of("statuses", List.of("archived"))),
            Map.of("policy", Map.of("statuses", List.of("archived"), "older_than_days", -1)),
            Map.of("policy", Map.of("statuses", List.of(), "older_than_days", 1)),
            Map.of("policy", Map.of("statuses", List.of("done"), "older_than_days", 1)),
            Map.of("dry_run", "yes", "ids", List.of("a")),
            Map.of("project", 7),
            Map.of("ids", List.of(7)),
            Map.of("policy", Map.of("statuses", List.of("archived"), "older_than_days", 36_501)),
            Map.of("policy", Map.of("statuses", List.of("archived"), "older_than_days", 1.5)),
            Map.of(
                "policy",
                Map.of("statuses", List.of("archived"), "older_than_days", 1),
                "project",
                "p"))) {
      assertThrows(
          IllegalArgumentException.class,
          () -> PruneRequest.fromMap(new HashMap<>(bad)),
          bad::toString);
    }
    assertThrows(
        NullPointerException.class,
        () -> new PruneRequest.Policy(List.of(SpecStatus.ARCHIVED), null, null));
    assertThrows(
        IllegalArgumentException.class,
        () -> new PruneRequest.Policy(List.of(SpecStatus.ARCHIVED), Duration.ofDays(-1), null));
    assertThrows(
        IllegalArgumentException.class,
        () -> new PruneRequest.Policy(List.of(SpecStatus.ARCHIVED), Duration.ofDays(1), "a b"));
  }

  @Test
  void theReportCarriesEveryCountAndEntity() {
    var report =
        new PruneReport(
            true, false, 1, 2, 3, 4, 5, 6, 7, 8, 9, List.of(new Erasure.Target("spec", "s")));

    assertEquals(
        Map.ofEntries(
            Map.entry("dry_run", true),
            Map.entry("requested", false),
            Map.entry("specs", 1),
            Map.entry("rooms", 2),
            Map.entry("messages", 3),
            Map.entry("runs", 4),
            Map.entry("reviews", 5),
            Map.entry("files", 6),
            Map.entry("projects", 7),
            Map.entry("events", 8),
            Map.entry("blob_bytes", 9L),
            Map.entry("entries", List.of(Map.of("type", "spec", "id", "s")))),
        report.toMap());
  }

  @Test
  void pruningNeedsARequestAndAnActor() {
    assertThrows(
        NullPointerException.class, () -> Acting.by(ADMIN, () -> pruner.prune(null, ADMIN)));
    assertThrows(
        NullPointerException.class, () -> pruner.prune(PruneRequest.ids(List.of("a"), true), null));
    assertThrows(
        NullPointerException.class, () -> new SpecPruner(null, bus, () -> true, clock::get));
  }

  private void archived(String id, String owner) {
    Acting.system(
        () -> {
          specs.create(row(id, owner, SpecStatus.ARCHIVED));
          specs.setContent(id, "a body only " + id + " holds", "");
          Acting.as(
              owner,
              () ->
                  rooms.create(
                      new RoomStore.RoomRow(
                          id, "proj", id, owner, null, null, owner, null, null, owner)));
          var messages = new MessageStore(db);
          var first = messages.append(id, owner, "first", null);
          messages.append(id, owner, "reply", first.id());
          run(new RunStore(db), "completed", "2026-09-01T00:00:00Z", id);
        });
  }

  private String run(RunStore runs, String status, String completedAt) {
    return run(runs, status, completedAt, null);
  }

  private String run(RunStore runs, String status, String completedAt, String specId) {
    var id = DateTimeUtils.newId().toString();
    runs.create(
        id, "proj", specId, "node", "node", "build", "claude", "b", "t", null, null, "/l", "u");
    db.execute(
        "UPDATE runs SET status = ?, completed_at = ? WHERE id = ?", status, completedAt, id);
    return id;
  }

  private static SpecStore.SpecRow row(String id, String owner, SpecStatus status) {
    return row(id, owner, status, "proj");
  }

  private static SpecStore.SpecRow row(String id, String owner, SpecStatus status, String project) {
    return new SpecStore.SpecRow(
        id, project, id, status, owner, null, null, null, null, 0, owner, "", "", owner, List.of(),
        List.of());
  }

  private static List<String> specIds(PruneReport report) {
    return report.entries().stream()
        .filter(target -> target.type().equals("spec"))
        .map(target -> "spec:" + target.id())
        .sorted()
        .toList();
  }

  private static Map<String, Object> withoutDryRun(Map<String, Object> report) {
    var copy = new HashMap<>(report);
    copy.remove("dry_run");
    return copy;
  }

  private long count(String sql) {
    return db.queryOne(sql, row -> row.integer(0)).orElseThrow();
  }

  private static void assertRefused(ErrorCode code, Executable prune) {
    var refused = assertThrows(ApiException.class, prune);
    assertEquals(code, refused.failure().errorCode(), refused.getMessage());
  }
}
