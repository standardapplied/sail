/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import ai.singlr.sail.authority.Refusal.Kind;
import ai.singlr.sail.authority.WriteRefused;
import ai.singlr.sail.common.DateTimeUtils;
import ai.singlr.sail.config.Lane;
import ai.singlr.sail.config.SpecStatus;
import ai.singlr.sail.identity.Acting;
import ai.singlr.sail.identity.Actor;
import ai.singlr.sail.identity.Role;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.lang.classfile.ClassFile;
import java.lang.classfile.instruction.InvokeDynamicInstruction;
import java.lang.classfile.instruction.InvokeInstruction;
import java.lang.constant.DirectMethodHandleDesc;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * A2, no unchecked write: every write a synced store offers is decided by the journal as its
 * revision is recorded. Each one, made by an actor its type's rule refuses from every local lane —
 * the host CLI, an HTTP token, a run's agent and a room agent — is refused with the rule's kind and
 * leaves the database exactly as it was; made by this box's machinery, the same write lands. The
 * list is every public store method that reaches the journal, read from the stores' own bytecode,
 * so a write added without a row here fails the test.
 */
class JournalDecidesEveryWriteTest {

  private static final String ADAS_RUN = "019fee00-0000-7000-8000-00000000ada0";
  private static final String FRESH_RUN = "019fee00-0000-7000-8000-0000000000f1";

  private static final List<Actor> READ_ONLY =
      List.of(
          new Actor("ada", Role.VIEWER, Actor.Lane.CLI),
          new Actor("ada", Role.VIEWER, Actor.Lane.API),
          new Actor("claude/" + ADAS_RUN, Role.VIEWER, Actor.Lane.AGENT, "ada"),
          Actor.roomPrincipal("claude/room-" + ADAS_RUN, "ada"));

  private static final List<Actor> NOT_THE_OWNER =
      List.of(
          new Actor("ada", Role.MEMBER, Actor.Lane.CLI),
          new Actor("ada", Role.MEMBER, Actor.Lane.API),
          Actor.agentPrincipal("claude/" + ADAS_RUN, "ada"));

  /**
   * The writes a store makes as this box's machinery whoever calls it: a box re-stamping the runs
   * it made itself as its own ({@link RunStore#stamp} binds {@link Actor#system()}).
   */
  private static final Set<String> MACHINERY = Set.of("RunStore.stamp", "RunStore.stampUnstamped");

  /** One store write: the public methods it proves, and what a member who is not the owner gets. */
  record Write(String name, Set<String> proves, Kind memberRefusal, Consumer<Fixture> act) {
    @Override
    public String toString() {
      return name;
    }
  }

  /** Bob's work on one box: everything a write here changes is his. */
  static final class Fixture implements AutoCloseable {
    final Sqlite db = Sqlite.openMemory();
    final SpecStore specs;
    final RoomStore rooms;
    final RunStore runs;
    final ReviewStore reviews;
    final FileStore files;
    final ProjectStore projects;
    final MessageStore messages;
    final String run = DateTimeUtils.newId().toString();
    final String stopping = DateTimeUtils.newId().toString();
    String review;
    String stage;
    Finding finding;
    String goneRev;

    Fixture() {
      new SchemaManager(db).migrate();
      specs = new SpecStore(db);
      rooms = new RoomStore(db);
      runs = new RunStore(db);
      reviews = new ReviewStore(db);
      files = new FileStore(db);
      projects = new ProjectStore(db);
      messages = new MessageStore(db);
      Acting.as("bob", this::seed);
    }

    private void seed() {
      projects.upsert("acme", "name: acme\n");
      db.execute(
          "INSERT INTO projects (name, definition, created_at, updated_at)"
              + " VALUES ('unscrubbed', 'git:\n  name: Bob\n  email: bob@example.com\n', 'now',"
              + " 'now')");
      specs.create(spec("theirs", SpecStatus.IN_PROGRESS));
      specs.create(spec("legacy", SpecStatus.PENDING));
      db.execute("UPDATE specs SET project = 'unassigned' WHERE id = 'legacy'");
      specs.create(spec("shipped", SpecStatus.REVIEW));
      specs.create(spec("fix", SpecStatus.DONE));
      specs.create(spec("gone", SpecStatus.PENDING));
      goneRev = specs.latestRev("gone");
      specs.delete("gone");
      rooms.create(room("den"));
      rooms.create(room("attic"));
      rooms.delete("attic");
      specs.create(spec("guest", SpecStatus.PENDING).withRoomId("den"));
      runs.create(run, "acme", "theirs", "bob", "room", "claude-code", "b", "t", 1, 2, "/l", "u");
      runs.create(
          stopping, "acme", "theirs", "bob", "room", "claude-code", "b", "t", 1, 2, "/l", "u");
      runs.claimStop(stopping, "stopping", () -> {});
      review = reviews.createReview("theirs", 1);
      stage = reviews.createStage(review, "codex", "agent");
      reviews.startStage(stage, "codex");
      finding = finding("leak");
      reviews.addFinding(stage, finding);
      reviews.linkSourceFindings("fix", List.of(finding.id()));
      var passed = reviews.createReview("shipped", 1);
      reviews.addFinding(reviews.createStage(passed, "codex", "agent"), finding("residue"));
      reviews.pass(passed, () -> {});
      files.put("acme", "notes.md", bytes("notes"), 0644);
      messages.append("theirs", "bob", "hello", null);
    }

    /** Every row of every synced table and of the journal, as text. */
    String state() {
      return Stream.of(
              "specs",
              "spec_content",
              "spec_dependencies",
              "spec_repos",
              "rooms",
              "runs",
              "run_principals",
              "reviews",
              "review_stages",
              "review_findings",
              "project_files",
              "projects",
              "room_messages",
              "blobs",
              "change_log")
          .map(this::rows)
          .collect(Collectors.joining("\n"));
    }

    private String rows(String table) {
      var columns =
          db.query("SELECT name FROM pragma_table_info(?)", row -> row.text(0), table).stream()
              .map(column -> "quote(\"" + column + "\")")
              .collect(Collectors.joining(" || '|' || "));
      return table
          + db.query(
              "SELECT " + columns + " FROM " + table + " ORDER BY rowid", row -> row.text(0));
    }

    @Override
    public void close() {
      db.close();
    }
  }

  private Fixture box;

  @BeforeEach
  void setUp() {
    box = new Fixture();
  }

  @AfterEach
  void tearDown() {
    box.close();
  }

  static Stream<Write> writes() {
    return Stream.of(
        owned("SpecStore.create", f -> f.specs.create(spec("gone", SpecStatus.PENDING))),
        owned("SpecStore.update", f -> f.specs.update(spec("theirs", SpecStatus.REVIEW))),
        owned("SpecStore.updateStatus", f -> f.specs.updateStatus("theirs", SpecStatus.DONE)),
        owned("SpecStore.moveFromLoop", f -> f.specs.moveFromLoop("theirs", SpecStatus.DONE)),
        owned(
            "SpecStore.compareAndSetStatus",
            f -> f.specs.compareAndSetStatus("theirs", SpecStatus.IN_PROGRESS, SpecStatus.REVIEW)),
        owned(
            "SpecStore.updateReposAndStatus",
            f -> f.specs.updateReposAndStatus("theirs", List.of("app"), SpecStatus.REVIEW, "b")),
        write("SpecStore.reHomeToOwnRoom", Kind.FIXED, f -> f.specs.reHomeToOwnRoom("guest")),
        owned("SpecStore.delete", f -> f.specs.delete("theirs")),
        owned("SpecStore.setContent", f -> f.specs.setContent("theirs", "body", "plan")),
        owned("SpecStore.restore", f -> f.specs.restore("gone", f.goneRev)),
        owned("SpecStore.reproject", f -> f.specs.reproject("acme", "acme2")),
        owned(
            "SpecStore.assignMigrationProject",
            f -> f.specs.assignMigrationProject("legacy", "acme")),
        write("RoomStore.create", null, f -> f.rooms.create(room("lounge"))),
        write(
            "RoomStore.createJournaled",
            null,
            f ->
                f.rooms.createJournaled(
                    new RoomStore.RoomRow(
                        "lounge", "acme", "lounge", null, null, null, "ada", "now", "now", null))),
        write(
            "RoomStore.ensureFor", null, f -> f.rooms.ensureFor("lounge", "acme", "l", null, null)),
        owned("RoomStore.updateRoster", f -> f.rooms.updateRoster("den", "{}")),
        owned("RoomStore.updateWake", f -> f.rooms.updateWake("den", "off")),
        owned("RoomStore.delete", f -> f.rooms.delete("den")),
        owned("RoomStore.restoreDeleted", f -> f.rooms.restoreDeleted("attic")),
        owned(
            "RunStore.create",
            f ->
                f.runs.create(
                    FRESH_RUN,
                    "acme",
                    "theirs",
                    "bob",
                    "fix",
                    "claude-code",
                    "b",
                    "t",
                    1,
                    2,
                    "/l",
                    "u")),
        owned(
            "RunStore.reserveDispatch",
            f ->
                f.runs.reserveDispatch(
                    FRESH_RUN,
                    "acme",
                    "guest",
                    "bob",
                    "build",
                    List.of(),
                    "claude-code",
                    "b",
                    "t",
                    "/l",
                    "u")),
        owned(
            "RunStore.reserveForReview",
            f ->
                f.runs.reserveForReview(
                    FRESH_RUN,
                    f.review,
                    "acme",
                    "guest",
                    "bob",
                    Lane.REVIEW,
                    List.of(),
                    "codex",
                    "b",
                    "t",
                    "/l",
                    "u",
                    null)),
        owned("RunStore.recordSession", f -> f.runs.recordSession(f.run, "s-1", "startup", "/t")),
        owned("RunStore.complete", f -> f.runs.complete(f.run, "completed", 0)),
        owned("RunStore.claimStop", f -> f.runs.claimStop(f.run, "stopping", () -> {})),
        owned("RunStore.releaseStop", f -> f.runs.releaseStop(f.stopping, () -> {})),
        owned("RunStore.transition", f -> f.runs.transition(f.run, "running", "stopped", 0)),
        owned("RunStore.recordExitCode", f -> f.runs.recordExitCode(f.run, 7)),
        owned("ReviewStore.createReview", f -> f.reviews.createReview("theirs", 2)),
        owned("ReviewStore.supersedeForSpec", f -> f.reviews.supersedeForSpec("theirs")),
        owned(
            "ReviewStore.failReviewWithError",
            f -> f.reviews.failReviewWithError(f.review, "boom")),
        owned(
            "ReviewStore.updateReviewStatus",
            f -> f.reviews.updateReviewStatus(f.review, "failed")),
        owned("ReviewStore.approve", f -> f.reviews.approve(f.review, "ada", () -> {})),
        owned("ReviewStore.pass", f -> f.reviews.pass(f.review, () -> {})),
        owned("ReviewStore.escalate", f -> f.reviews.escalate(f.review, "stuck", () -> {})),
        owned("ReviewStore.createStage", f -> f.reviews.createStage(f.review, "human", "human")),
        owned("ReviewStore.startStage", f -> f.reviews.startStage(f.stage, "claude")),
        owned("ReviewStore.completeStage", f -> f.reviews.completeStage(f.stage, "passed")),
        owned("ReviewStore.addFinding", f -> f.reviews.addFinding(f.stage, finding("another"))),
        owned(
            "ReviewStore.carryForward",
            f -> f.reviews.carryForward(f.stage, f.finding, "still there")),
        owned(
            "ReviewStore.applyStageResult",
            f -> f.reviews.applyStageResult(f.stage, List.of(), List.of(finding("found")))),
        owned(
            "ReviewStore.resolveFinding",
            f -> f.reviews.resolveFinding(f.finding.id(), Finding.Resolution.DISMISSED)),
        owned(
            "ReviewStore.linkSourceFindings",
            f -> f.reviews.linkSourceFindings("guest", List.of(f.finding.id()))),
        owned(
            "ReviewStore.resolveFindingsOfShippedFollowUps",
            f -> f.reviews.resolveFindingsOfShippedFollowUps()),
        owned(
            "ReviewStore.resolveShippedFindings", f -> f.reviews.resolveShippedFindings("shipped")),
        write("FileStore.put", null, f -> f.files.put("acme", "new.md", bytes("new"), 0644)),
        write(
            "FileStore.put(FileRow)",
            Set.of("FileStore.put"),
            null,
            f -> {
              var held = f.files.find("acme", "notes.md").orElseThrow();
              f.files.put(
                  new FileStore.FileRow(
                      "acme", "copy.md", held.contentHash(), held.size(), 0600, held.kind()));
            }),
        write("FileStore.delete", null, f -> f.files.delete("acme", "notes.md")),
        write("FileStore.reproject", null, f -> f.files.reproject("acme", "acme2")),
        write("ProjectStore.upsert", null, f -> f.projects.upsert("acme", "name: acme\nx: 1\n")),
        write(
            "ProjectStore.canonicalizeDefinitions",
            null,
            f -> f.projects.canonicalizeDefinitions()),
        write("ProjectStore.delete", null, f -> f.projects.delete("acme")),
        write(
            "ProjectStore.rename",
            Kind.ADMIN_ONLY,
            f -> f.projects.rename("acme", "acme2", "name: acme2\n")),
        owned(
            "MessageStore.append",
            f -> f.messages.append("theirs", Actor.current().handle(), "hi", null)));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("writes")
  void aWriteItsRuleRefusesIsRefusedOnEveryLaneAndLeavesNothing(Write write) {
    var before = box.state();

    for (var actor : READ_ONLY) {
      var expected =
          actor.roomLane() && write.name().startsWith("MessageStore")
              ? Kind.NOT_OWNER
              : Kind.READ_ONLY;
      assertEquals(expected, refusal(actor, write), actor.lane() + " read-only");
      assertEquals(before, box.state(), actor.lane() + " read-only wrote nothing");
    }
    if (write.memberRefusal() != null) {
      for (var actor : NOT_THE_OWNER) {
        assertEquals(write.memberRefusal(), refusal(actor, write), actor.lane() + " member");
        assertEquals(before, box.state(), actor.lane() + " member wrote nothing");
      }
    }

    Acting.system(() -> write.act().accept(box));
    assertNotEquals(before, box.state(), "the same write by this box's machinery lands");
  }

  @Test
  void everyStoreMethodThatReachesTheJournalIsInTheList() throws IOException {
    var journaled = new TreeSet<String>();
    for (var store :
        List.of(
            SpecStore.class,
            RoomStore.class,
            RunStore.class,
            ReviewStore.class,
            FileStore.class,
            ProjectStore.class,
            MessageStore.class)) {
      journaled.addAll(journaledWrites(store));
    }
    var proven =
        Stream.concat(writes().flatMap(write -> write.proves().stream()), MACHINERY.stream())
            .collect(Collectors.toCollection(TreeSet::new));

    assertEquals(journaled, proven);
  }

  @Test
  void aRevisionNeverAdmitsItself() {
    var before = box.state();
    var ada = new Actor("ada", Role.MEMBER, Actor.Lane.API);
    var claimed =
        new SpecStore.SpecRow(
            "theirs",
            "acme",
            "theirs",
            SpecStatus.IN_PROGRESS,
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
            List.of());

    var refused =
        assertThrows(WriteRefused.class, () -> Acting.by(ada, () -> box.specs.update(claimed)));

    assertEquals(
        Kind.ADMIN_ONLY,
        refused.refusal().kind(),
        "decided against bob's spec as the journal holds it, not against the row ada just wrote");
    assertEquals(before, box.state());
  }

  @Test
  void aRefusalSwallowedInsideATransactionStillRollsItBack() {
    var before = box.state();
    var ada = new Actor("ada", Role.MEMBER, Actor.Lane.API);

    assertThrows(
        WriteRefused.class,
        () ->
            Acting.by(
                ada,
                () ->
                    box.specs.atomically(
                        () -> {
                          box.rooms.create(room("lounge"));
                          try {
                            box.specs.updateStatus("theirs", SpecStatus.DONE);
                          } catch (WriteRefused swallowed) {
                            return null;
                          }
                          return null;
                        })));

    assertEquals(before, box.state(), "neither the refused row nor the one written beside it");
  }

  @Test
  void aPushedRevisionWasDecidedAtMainsCommitAndIsNotDecidedAgain() {
    Acting.by(
        Actor.sync("ada", Role.MEMBER), () -> box.specs.updateStatus("theirs", SpecStatus.DONE));

    assertEquals(SpecStatus.DONE, box.specs.findById("theirs").orElseThrow().status());
  }

  private Kind refusal(Actor actor, Write write) {
    return assertThrows(
            WriteRefused.class,
            () -> Acting.by(actor, () -> write.act().accept(box)),
            write.name() + " as " + actor)
        .refusal()
        .kind();
  }

  private static Write owned(String name, Consumer<Fixture> act) {
    return write(name, Kind.NOT_OWNER, act);
  }

  private static Write write(String name, Kind memberRefusal, Consumer<Fixture> act) {
    return write(name, Set.of(name), memberRefusal, act);
  }

  private static Write write(
      String name, Set<String> proves, Kind memberRefusal, Consumer<Fixture> act) {
    return new Write(name, proves, memberRefusal, act);
  }

  /**
   * The public methods of {@code store} that record a revision of this box's own: those from which
   * a call to the journal's {@code recordRevision} or {@code recordTombstone} — for messages, the
   * journal entry {@code append} writes — is reachable through the store's own methods and lambdas.
   * The sync engine's entry points are decided at main's commit or adopt what main decided.
   */
  private static Set<String> journaledWrites(Class<?> store) throws IOException {
    var name = store.getName();
    var owner = name.replace('.', '/');
    var calls = new LinkedHashMap<String, Set<String>>();
    var journaling = new LinkedHashSet<String>();
    var open = new LinkedHashSet<String>();
    try (var bytes =
        store.getResourceAsStream(name.substring(name.lastIndexOf('.') + 1) + ".class")) {
      for (var method : ClassFile.of().parse(bytes.readAllBytes()).methods()) {
        var caller = method.methodName().stringValue() + method.methodType().stringValue();
        var callees = calls.computeIfAbsent(caller, key -> new LinkedHashSet<>());
        if ((method.flags().flagsMask() & ClassFile.ACC_PUBLIC) != 0) {
          open.add(caller);
        }
        method
            .code()
            .ifPresent(
                code ->
                    code.forEach(
                        element -> {
                          switch (element) {
                            case InvokeInstruction call ->
                                note(
                                    owner,
                                    caller,
                                    call.owner().asInternalName(),
                                    call.name().stringValue(),
                                    call.type().stringValue(),
                                    callees,
                                    journaling);
                            case InvokeDynamicInstruction dynamic ->
                                dynamic.bootstrapArgs().stream()
                                    .filter(DirectMethodHandleDesc.class::isInstance)
                                    .map(DirectMethodHandleDesc.class::cast)
                                    .forEach(
                                        handle ->
                                            note(
                                                owner,
                                                caller,
                                                handle.owner().descriptorString(),
                                                handle.methodName(),
                                                handle.lookupDescriptor(),
                                                callees,
                                                journaling));
                            default -> {}
                          }
                        }));
      }
    }
    var simple = store.getSimpleName();
    return open.stream()
        .filter(method -> !SYNC_ENGINE.contains(method.substring(0, method.indexOf('('))))
        .filter(method -> reaches(method, calls, journaling))
        .map(method -> simple + "." + method.substring(0, method.indexOf('(')))
        .collect(Collectors.toCollection(TreeSet::new));
  }

  private static final Set<String> SYNC_ENGINE =
      Set.of("commitRevision", "applyRevision", "adoptForSync", "acknowledge", "resolveConflict");

  private static void note(
      String owner,
      String caller,
      String calledOwner,
      String calledName,
      String calledType,
      Set<String> callees,
      Set<String> journaling) {
    var target = calledOwner.replaceAll("^L|;$", "");
    if (target.equals(owner)) {
      callees.add(calledName + calledType);
    } else if (target.equals("ai/singlr/sail/store/RevisionJournal")
            && Set.of("recordRevision", "recordTombstone").contains(calledName)
        || target.equals("ai/singlr/sail/store/ChangeLog") && calledName.equals("appendAuthored")) {
      journaling.add(caller);
    }
  }

  private static boolean reaches(
      String method, Map<String, Set<String>> calls, Set<String> journaling) {
    var seen = new LinkedHashSet<String>();
    var pending = new ArrayDeque<>(List.of(method));
    while (!pending.isEmpty()) {
      var next = pending.pop();
      if (journaling.contains(next)) {
        return true;
      }
      if (seen.add(next)) {
        pending.addAll(calls.getOrDefault(next, Set.of()));
      }
    }
    return false;
  }

  private static SpecStore.SpecRow spec(String id, SpecStatus status) {
    return new SpecStore.SpecRow(
        id, "acme", id, status, "bob", null, null, null, null, 0, null, "", "", null, List.of(),
        List.of());
  }

  private static RoomStore.RoomRow room(String id) {
    return new RoomStore.RoomRow(id, "acme", id, null, null, null, null, null, null, null);
  }

  private static Finding finding(String title) {
    return Finding.create(
        Finding.Severity.HIGH, Finding.Category.LOGIC, "A.java", 1, 2, title, "d", "e", null, 0.9);
  }

  private static ByteArrayInputStream bytes(String text) {
    return new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8));
  }
}
