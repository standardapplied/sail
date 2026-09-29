/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.store;

import ai.singlr.sail.config.ProjectRegistry;
import ai.singlr.sail.config.YamlUtil;
import ai.singlr.sail.engine.NameValidator;
import ai.singlr.sail.engine.NodeIdentity;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.BooleanSupplier;

/**
 * Erases, once, every personal room an older release minted: the room each FDE was given per
 * project on their first rooms read. Rooms are made on purpose now, so none of these remains.
 *
 * <p>A personal room is recognized by its id alone: the id an older release derived from its
 * creator and project, a readable slug and a fingerprint of the exact pair. A room whose id merely
 * shares the prefix is any other room, and is untouched. A personal room deleted before the upgrade
 * is recognized by the state its tombstone kept, and erased with the messages and runs its deletion
 * left behind.
 *
 * <p>An authoritative box — main, or a standalone box — erases each one with its messages and runs,
 * recording an erasure row per entity that every node adopts through its pages. A node removes only
 * the personal rooms main never acknowledged, which exist on that box alone, and leaves no erasure
 * row, so nothing of them ever reaches main; every other one is main's to erase, decided on main's
 * copy as every erasure is, and the report names this box's work it would take. A room with a run
 * that has not finished, or one a spec converses in, is left as an ordinary room and named in the
 * report: erasing it would take work going on, or leave a spec talking into nothing. One room per
 * transaction, re-checked under its lock, so an upgrade killed partway resumes where it stopped and
 * running it twice erases nothing twice. The count is printed.
 */
public final class PersonalRoomErasure implements DataMigration {

  public static final String NAME = "personal-rooms-erased-v1";

  private static final String PREFIX = "fde-";
  private static final int FINGERPRINT_LENGTH = 16;
  private static final int MAX_SLUG_LENGTH =
      NameValidator.MAX_SPEC_ID_LENGTH - PREFIX.length() - 1 - FINGERPRINT_LENGTH;

  private final BooleanSupplier authoritative;

  public PersonalRoomErasure() {
    this(NodeIdentity::authoritative);
  }

  PersonalRoomErasure(BooleanSupplier authoritative) {
    this.authoritative = Objects.requireNonNull(authoritative, "authoritative");
  }

  @Override
  public String name() {
    return NAME;
  }

  @Override
  public boolean resumable() {
    return true;
  }

  @Override
  public Report apply(Sqlite db, ProjectRegistry projects, Prompter prompter) {
    var main = authoritative.getAsBoolean();
    var erasure = new Erasure(db);
    var runs = new RunStore(db);
    var specs = new SpecStore(db);
    var removed = new ArrayList<Erasure.Target>();
    var kept = new ArrayList<String>();
    var mains = new ArrayList<String>();
    var contested = new ArrayList<String>();
    for (var id : candidates(db)) {
      var room = new Erasure.Target(Erasure.ROOM, id);
      var result =
          db.transaction(
              () -> {
                if (!isPersonal(db, id)) {
                  return Erasure.Result.NONE;
                }
                var closure = erasure.closure(List.of(room));
                var inUse = inUse(runs, specs, closure, id);
                if (!main && !erasure.unacknowledged(room)) {
                  mains.add(id);
                  inUse.ifPresent(
                      reason ->
                          contested.add(
                              "Personal room '"
                                  + id
                                  + "' is main's to erase, decided on main's copy, though here "
                                  + reason));
                  return Erasure.Result.NONE;
                }
                if (inUse.isPresent()) {
                  kept.add("Left personal room '" + id + "' as an ordinary room: " + inUse.get());
                  return Erasure.Result.NONE;
                }
                return main ? erasure.erase(closure, "migration") : erasure.discard(List.of(room));
              });
      removed.addAll(result.entities());
    }
    var result = new Erasure.Result(removed, 0);
    var rooms = result.count(Erasure.ROOM);
    var notes = new ArrayList<String>();
    notes.add(
        (main ? "Erased " : "Removed ")
            + rooms
            + " personal rooms"
            + (main ? "" : " this box alone held")
            + ": "
            + result.count(Erasure.MESSAGE)
            + " messages, "
            + result.count(Erasure.RUN)
            + " runs");
    if (!mains.isEmpty()) {
      notes.add(
          mains.size() + " personal rooms are main's to erase; this node adopts its erasures");
    }
    notes.addAll(contested);
    notes.addAll(kept);
    return new Report(rooms, 0, kept.size(), notes);
  }

  /** Every room, live or deleted, whose id could be a personal room's. */
  private static Set<String> candidates(Sqlite db) {
    var ids =
        new LinkedHashSet<>(
            db.query(
                "SELECT id FROM rooms WHERE id LIKE ? ORDER BY rowid",
                row -> row.text(0),
                PREFIX + "%"));
    new ChangeLog(db)
        .tombstonedBy(Erasure.ROOM, "project").values().stream()
            .flatMap(List::stream)
            .filter(id -> id.startsWith(PREFIX))
            .sorted()
            .forEach(ids::add);
    return ids;
  }

  /**
   * Whether room {@code id}, as this box last knew it — its live row, or the state its tombstone
   * kept — is the personal room of its creator in its project.
   */
  private static boolean isPersonal(Sqlite db, String id) {
    return db.queryOne(
            "SELECT created_by, project FROM rooms WHERE id = ?",
            row -> isPersonal(id, row.text(0), row.text(1)),
            id)
        .orElseGet(
            () ->
                new ChangeLog(db)
                    .head(Erasure.ROOM, id)
                    .filter(head -> head.kind() == ChangeLog.Kind.TOMBSTONE)
                    .map(head -> YamlUtil.parseMap(head.snapshot()))
                    .map(
                        last ->
                            isPersonal(
                                id,
                                Snapshots.text(last, "created_by"),
                                Snapshots.text(last, "project")))
                    .orElse(false));
  }

  private static boolean isPersonal(String id, String creator, String project) {
    return creator != null && project != null && id.equals(idOf(creator, project));
  }

  /** Why room {@code id} must not go yet: a run in it that has not finished, or a spec in it. */
  private static Optional<String> inUse(
      RunStore runs, SpecStore specs, List<Erasure.Target> closure, String id) {
    var unfinished = runs.unfinished(idsOf(closure, Erasure.RUN));
    if (!unfinished.isEmpty()) {
      return Optional.of("run '" + unfinished.getFirst() + "' has not finished");
    }
    return specs.inRooms(List.of(id)).getOrDefault(id, Set.of()).stream()
        .findFirst()
        .map(spec -> "spec '" + spec + "' converses in it");
  }

  /**
   * The id an older release gave {@code handle}'s personal room in {@code project}: a readable slug
   * of the pair, then a fingerprint of the exact pair, truncated to fit the shared id space.
   */
  private static String idOf(String handle, String project) {
    var slug = handle.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9-]", "-") + "-" + project;
    var fingerprint = TokenStore.sha256(handle + "\0" + project).substring(0, FINGERPRINT_LENGTH);
    return PREFIX + slug.substring(0, Math.min(slug.length(), MAX_SLUG_LENGTH)) + "-" + fingerprint;
  }

  private static List<String> idsOf(List<Erasure.Target> targets, String type) {
    return targets.stream()
        .filter(target -> target.type().equals(type))
        .map(Erasure.Target::id)
        .toList();
  }
}
