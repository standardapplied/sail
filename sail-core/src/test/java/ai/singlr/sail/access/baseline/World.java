/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.access.baseline;

import ai.singlr.sail.authority.WriteRefused;
import ai.singlr.sail.common.DateTimeUtils;
import ai.singlr.sail.config.SpecStatus;
import ai.singlr.sail.config.TestDefinitions;
import ai.singlr.sail.identity.Acting;
import ai.singlr.sail.identity.Actor;
import ai.singlr.sail.store.ChangeLog;
import ai.singlr.sail.store.FdeStore;
import ai.singlr.sail.store.FileStore;
import ai.singlr.sail.store.Finding;
import ai.singlr.sail.store.HeldProjects;
import ai.singlr.sail.store.MessageStore;
import ai.singlr.sail.store.ProjectStore;
import ai.singlr.sail.store.ReviewStore;
import ai.singlr.sail.store.RoomStore;
import ai.singlr.sail.store.RunStore;
import ai.singlr.sail.store.SpecStore;
import ai.singlr.sail.store.Sqlite;
import ai.singlr.sail.store.SyncConflicts;
import ai.singlr.sail.store.SyncState;
import ai.singlr.sail.store.SyncedStore;
import ai.singlr.sail.sync.StoreReplica;
import ai.singlr.sail.sync.SyncBox;
import ai.singlr.sail.sync.SyncedEntities;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/**
 * The box every core corpus asks: main's database, holding projects {@code acme} and {@code beta}
 * and nothing of {@code omega}, with the cast's work in each state a rule tells apart. Every row is
 * written through its store, so the journal holds it as a rule reads it; what the corpus then asks
 * is rehearsed ({@link #rehearse}) and leaves the box as it was.
 *
 * <p>Specs, all in {@code acme} but {@code s-beta}: {@code s-ada} assigned to ada and created by
 * bob, {@code s-bob} the other way round, {@code s-open-ada} and {@code s-open-bob} unassigned,
 * {@code s-lobby} and {@code s-den} unassigned and born in ada's and bob's room, {@code s-draft} a
 * draft, {@code s-done}, {@code s-archived} and {@code s-archived-bob} finished, {@code s-gone}
 * deleted, {@code s-busy} in progress and {@code s-archived-busy} archived, each with a run going.
 * Rooms: {@code lobby} ada's, {@code den} bob's, {@code hall} no one's, {@code attic} ada's and
 * deleted, {@code b-room} ada's in beta, and {@code s-ada}'s own room. Runs: the cast's ({@link
 * Cast}), ada's ad-hoc {@link #ADHOC_RUN}, her runs on {@code s-busy} and {@code s-archived-busy},
 * her run in beta, and her run of bob's {@code s-bob}. A review of {@code s-ada} and one of {@code
 * s-bob}, each with an agent's stage and its finding and a human's stage awaiting approval; a
 * shared file in each project; messages by each kind of author in {@code lobby}, {@code den},
 * {@code b-room} and {@code s-ada}'s conversation. Projects {@code acme} and {@code beta} are the
 * catalog every box starts with ({@link HeldProjects}), each journaled since by root; {@code delta}
 * was created by ada.
 */
public final class World implements AutoCloseable {

  /**
   * An FDE the roster makes a viewer, and one it scopes to beta: the doors that read the roster.
   */
  public static final String VIEWER_FDE = "vic";

  public static final String SCOPED_FDE = "sam";

  public static final String ADHOC_RUN = "019fee00-0000-7000-8000-0000000ada03";
  private static final String BUSY_RUN = "019fee00-0000-7000-8000-0000000ada04";
  private static final String ARCHIVED_BUSY_RUN = "019fee00-0000-7000-8000-0000000ada07";
  public static final String BETA_RUN = "019fee00-0000-7000-8000-0000000ada05";

  /** A run ada's box executed of {@code s-bob}, before the spec became bob's. */
  public static final String HANDED_OVER_RUN = "019fee00-0000-7000-8000-0000000ada06";

  /** Every spec, in the order written. */
  public static final List<String> SPECS =
      List.of(
          "s-ada",
          "s-bob",
          "s-open-ada",
          "s-open-bob",
          "s-lobby",
          "s-den",
          "s-draft",
          "s-done",
          "s-archived",
          "s-archived-bob",
          "s-gone",
          "s-busy",
          "s-archived-busy",
          "s-beta");

  public final SyncBox box = new SyncBox("main");
  public final Sqlite db = box.db;
  public final RoomStore rooms = new RoomStore(db);
  public final RunStore runs = new RunStore(db);
  public final ReviewStore reviews = new ReviewStore(db);
  public final FileStore files = new FileStore(db);
  public final ProjectStore projects = new ProjectStore(db);
  public final MessageStore messages = new MessageStore(db);

  /** Each id minted at random, keyed to the name the corpus spells it by. */
  private final Map<String, String> minted = new LinkedHashMap<>();

  /** What {@code s-gone} and {@code attic} held when they were deleted. */
  private final Map<String, Map<String, Object>> deleted = new LinkedHashMap<>();

  public World() {
    HeldProjects.hold(db, "beta");
    Acting.system(this::roster);
    Acting.as(Cast.ADMIN, this::projects);
    Acting.as(Cast.OWNER, () -> projects.upsert(TestDefinitions.named("delta")));
    rooms();
    specs();
    Acting.system(this::runs);
    Acting.system(this::reviews);
    Acting.system(this::files);
    Acting.system(this::messages);
  }

  private void roster() {
    var fdes = new FdeStore(db);
    fdes.add(Cast.OWNER, "Ada", "ada@x", "member");
    fdes.add(Cast.OTHER, "Bob", "bob@x", "member");
    fdes.add(Cast.ADMIN, "Root", "root@x", "admin");
    fdes.add(VIEWER_FDE, "Vic", "vic@x", "viewer");
    fdes.add(SCOPED_FDE, "Sam", "sam@x", "member");
    fdes.restrict(SCOPED_FDE);
    fdes.grant(SCOPED_FDE, "beta");
  }

  private void projects() {
    projects.upsert(TestDefinitions.named("acme"));
    projects.upsert(TestDefinitions.named("beta"));
  }

  private void rooms() {
    room("lobby", "acme", Cast.OWNER);
    room("den", "acme", Cast.OTHER);
    room("hall", "acme", null);
    room("attic", "acme", Cast.OWNER);
    room("b-room", "beta", Cast.OWNER);
    deleted.put("attic", snapshot("room", "attic"));
    Acting.as(Cast.OWNER, () -> rooms.delete("attic"));
  }

  private void room(String id, String project, String owner) {
    Acting.as(
        owner == null ? Cast.ADMIN : owner,
        () ->
            rooms.createJournaled(
                new RoomStore.RoomRow(
                    id,
                    project,
                    id,
                    owner,
                    null,
                    null,
                    owner,
                    DateTimeUtils.now(),
                    DateTimeUtils.now(),
                    null)));
  }

  private void specs() {
    spec("s-ada", "acme", Cast.OWNER, Cast.OTHER, SpecStatus.PENDING, null);
    spec("s-bob", "acme", Cast.OTHER, Cast.OWNER, SpecStatus.PENDING, null);
    spec("s-open-ada", "acme", null, Cast.OWNER, SpecStatus.PENDING, null);
    spec("s-open-bob", "acme", null, Cast.OTHER, SpecStatus.PENDING, null);
    spec("s-lobby", "acme", null, Cast.OWNER, SpecStatus.PENDING, "lobby");
    spec("s-den", "acme", null, Cast.OTHER, SpecStatus.PENDING, "den");
    spec("s-draft", "acme", Cast.OWNER, Cast.OWNER, SpecStatus.DRAFT, null);
    spec("s-done", "acme", Cast.OWNER, Cast.OWNER, SpecStatus.DONE, null);
    spec("s-archived", "acme", Cast.OWNER, Cast.OWNER, SpecStatus.ARCHIVED, null);
    spec("s-archived-bob", "acme", Cast.OTHER, Cast.OTHER, SpecStatus.ARCHIVED, null);
    spec("s-gone", "acme", Cast.OWNER, Cast.OWNER, SpecStatus.CANCELLED, null);
    spec("s-busy", "acme", Cast.OWNER, Cast.OWNER, SpecStatus.IN_PROGRESS, null);
    spec("s-archived-busy", "acme", Cast.OWNER, Cast.OWNER, SpecStatus.ARCHIVED, null);
    spec("s-beta", "beta", Cast.OWNER, Cast.OWNER, SpecStatus.PENDING, null);
    deleted.put("s-gone", snapshot("spec", "s-gone"));
    Acting.as(Cast.OWNER, () -> box.specs.delete("s-gone"));
    Acting.as(Cast.OWNER, () -> rooms.ensureFor("s-ada", "acme", "s-ada", Cast.OWNER, null));
  }

  private void spec(
      String id, String project, String assignee, String creator, SpecStatus status, String room) {
    Acting.as(
        creator,
        () ->
            box.specs.create(
                new SpecStore.SpecRow(
                    id, project, id, status, assignee, null, null, null, null, 0, creator, null,
                    null, creator, List.of(), List.of(), room)));
  }

  private void runs() {
    run(Cast.ADA_RUN, "acme", "s-ada", Cast.OWNER);
    run(Cast.BOB_RUN, "acme", "s-bob", Cast.OTHER);
    run(ADHOC_RUN, "acme", null, Cast.OWNER);
    run(BUSY_RUN, "acme", "s-busy", Cast.OWNER);
    run(ARCHIVED_BUSY_RUN, "acme", "s-archived-busy", Cast.OWNER);
    run(BETA_RUN, "beta", "s-beta", Cast.OWNER);
    run(HANDED_OVER_RUN, "acme", "s-bob", Cast.OWNER);
    roomRun(Cast.ADA_ROOM_RUN, "lobby", Cast.OWNER);
    roomRun(Cast.BOB_ROOM_RUN, "den", Cast.OTHER);
  }

  private void run(String id, String project, String spec, String fde) {
    runs.create(id, project, spec, fde, "build", "claude-code", "b", "t", null, null, "/l", "u");
  }

  private void roomRun(String id, String room, String fde) {
    runs.reserveDispatch(
        id, "acme", null, room, fde, "room", List.of(), "claude-code", "b", "t", "/l", "u", null);
  }

  private void reviews() {
    review("s-ada", "review-ada", "finding-ada");
    review("s-bob", "review-bob", "finding-bob");
  }

  private void review(String spec, String review, String finding) {
    var id = reviews.createReview(spec, 1);
    var stage = reviews.createStage(id, "codex", "agent");
    var found =
        Finding.create(
            Finding.Severity.HIGH,
            Finding.Category.LOGIC,
            "A.java",
            1,
            2,
            "t",
            "d",
            "e",
            new Finding.Suggestion("a", "b", "why"),
            0.9);
    reviews.addFinding(stage, found);
    var human = reviews.createStage(id, "human", "human");
    reviews.startStage(human, Cast.OWNER);
    minted.put(id, review);
    minted.put(stage, review + "-stage");
    minted.put(human, review + "-human-stage");
    minted.put(found.id(), finding);
  }

  private void files() {
    for (var project : List.of("acme", "beta")) {
      files.put(
          project,
          "notes.md",
          new ByteArrayInputStream("notes".getBytes(StandardCharsets.UTF_8)),
          0644);
    }
  }

  private void messages() {
    for (var room : List.of("lobby", "den", "s-ada", "b-room")) {
      for (var author :
          List.of(
              Cast.OWNER,
              Cast.OTHER,
              Cast.ADMIN,
              Actor.SYSTEM_HANDLE,
              Cast.ADA_AGENT,
              Cast.BOB_AGENT,
              Cast.ADA_ROOM_AGENT)) {
        var said = messages.append(room, author, room + " by " + author, null);
        minted.put(said.id(), "msg(" + room + " by " + author + ")");
      }
    }
  }

  /** The id this world minted for {@code name}. */
  public String id(String name) {
    return minted.entrySet().stream()
        .filter(entry -> entry.getValue().equals(name))
        .map(Map.Entry::getKey)
        .findFirst()
        .orElseThrow(() -> new IllegalArgumentException("Nothing minted as " + name));
  }

  /** Every id minted at random, keyed to its name. */
  public Map<String, String> minted() {
    return minted;
  }

  /** The store of synced {@code type}. */
  public SyncedStore store(String type) {
    return SyncedEntities.require(type).store(db);
  }

  /** The rev main's deletion of a row is adopted at ({@link #adoptDeletion}). */
  public static final String ADOPTED_REV = "9-main";

  /**
   * Adopts main's deletion of {@code type} {@code id}, filed under {@code project} as main sends
   * it: the tombstone keeps this box's last state, the head main's project.
   */
  public void adoptDeletion(String type, String id, String project) {
    var replica =
        new StoreReplica(
            "node", store(type), new ChangeLog(db), new SyncConflicts(db), new SyncState(db));
    Actor.call(
        Actor.main(Cast.ADMIN),
        () -> {
          replica.adopt(id, null, ADOPTED_REV, project);
          return null;
        });
  }

  /** Forgets catalog row {@code project}, leaving every row filed under it as it is. */
  public void uncatalog(String project) {
    db.execute("DELETE FROM projects WHERE name = ?", project);
  }

  /** Forgets the journal's head of {@code type} {@code id}, leaving its row as it is. */
  public void dropHead(String type, String id) {
    db.execute("DELETE FROM change_heads WHERE entity_type = ? AND entity_id = ?", type, id);
  }

  /** The comparable state of {@code type} {@code id} as this box holds it; null if absent. */
  public Map<String, Object> snapshot(String type, String id) {
    var snapshot = store(type).comparableSnapshot(id);
    return snapshot == null ? deleted.get(id) : new LinkedHashMap<>(snapshot);
  }

  /**
   * What {@code work} answers as {@code actor}, rehearsed: the box is left as it was. A refusal the
   * work caught and answered for itself dooms the transaction, which the rehearsal rolls back
   * anyway, so that answer stands; one the work let through is thrown.
   */
  public <T> T rehearse(Actor actor, Supplier<T> work) {
    var answered = new AtomicReference<T>();
    try {
      return db.rehearse(
          () ->
              Actor.call(
                  actor,
                  () -> {
                    answered.set(work.get());
                    return answered.get();
                  }));
    } catch (WriteRefused doomed) {
      if (answered.get() == null) {
        throw doomed;
      }
      return answered.get();
    }
  }

  /**
   * What {@code work} printed to standard error: what the box says of a rehearsed write it could
   * not finish, which a corpus checks rather than lets reach the build's log.
   */
  public static String stderrOf(Runnable work) {
    var printed = new ByteArrayOutputStream();
    var original = System.err;
    try (var stream = new PrintStream(printed, true, StandardCharsets.UTF_8)) {
      System.setErr(stream);
      work.run();
    } finally {
      System.setErr(original);
    }
    return printed.toString(StandardCharsets.UTF_8);
  }

  @Override
  public void close() {
    box.close();
  }
}
