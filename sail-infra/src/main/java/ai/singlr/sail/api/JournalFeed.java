/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.api;

import ai.singlr.sail.access.Access;
import ai.singlr.sail.access.Policy;
import ai.singlr.sail.identity.Actor;
import ai.singlr.sail.store.ChangeLog;
import ai.singlr.sail.store.FeedFloors;
import ai.singlr.sail.store.FeedPositions;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;

/**
 * The change feed this box's change log answers ({@link ChangeLog#changesAfter}): every page is one
 * read snapshot. Its position is checked first, against this database's catalog and the entry it
 * stands on ({@link FeedPositions}) and the reader's floor ({@link FeedFloors}); its heads are
 * read, in seq order, from the projects the reader may read now; and its rows in one read per type,
 * each by the type's own read rule and shaped as its list route shapes it, less the fields that
 * list derives from other rows. A head is told as the row it now is, as {@code gone} when it is a
 * deletion, or as {@code erased}; one whose row the reader may not read is passed over, and only
 * moves the page's next position past it. A change to one row never tells another.
 */
final class JournalFeed implements ChangeFeed {

  /** The rows of one fed type its reader reads, by id, as the type's fed record shapes them. */
  @FunctionalInterface
  interface Rows {
    Map<String, ? extends Mappable> readable(List<String> ids);
  }

  /**
   * One fed type: its {@code rows}, and whether it is {@code steered}, so a reader who hears only
   * its steering voices ({@link Policy#hearsOnlySteering}) is told nothing of one it cannot read,
   * deleted or erased, whose voice is no longer there to weigh.
   */
  record Fed(Rows rows, boolean steered) {}

  private final ChangeLog log;
  private final FeedPositions positions;
  private final FeedFloors floors;
  private final Map<String, Fed> fed;
  private final Runnable freshen;

  /**
   * A feed over {@code log} serving {@code fed}, one entry per type of {@link #TYPES}, which runs
   * {@code freshen} before each read, outside its snapshot, as every read of the box does; a stream
   * binds {@link SyncControl#NO_SYNC} so its wakes never do.
   */
  JournalFeed(
      ChangeLog log,
      FeedPositions positions,
      FeedFloors floors,
      Map<String, Fed> fed,
      Runnable freshen) {
    this.log = Objects.requireNonNull(log, "log");
    this.positions = Objects.requireNonNull(positions, "positions");
    this.floors = Objects.requireNonNull(floors, "floors");
    this.fed = Map.copyOf(fed);
    this.freshen = Objects.requireNonNull(freshen, "freshen");
    if (!this.fed.keySet().equals(Set.copyOf(TYPES))) {
      throw new IllegalArgumentException("A journal feed serves exactly " + TYPES);
    }
  }

  @Override
  public String newest() {
    return log.read(() -> positions.newest(positions.catalog()));
  }

  @Override
  public ChangeLog.Watch watch(Runnable wake) {
    return log.watch(wake);
  }

  @Override
  public void wake() {
    log.wake();
  }

  @Override
  public Page page(Ask ask) {
    freshen.run();
    return log.read(() -> read(ask));
  }

  @Override
  public <T> Positioned<T> at(Supplier<T> read) {
    freshen.run();
    return log.read(
        () -> {
          var value = ScopedValue.where(SyncControl.NO_SYNC, true).call(read::get);
          return new Positioned<>(value, positions.newest(positions.catalog()));
        });
  }

  private Page read(Ask ask) {
    var catalog = positions.catalog();
    var newest = positions.newest(catalog);
    var since =
        (ask.minted()
                ? positions.seqOfMinted(catalog, ask.since())
                : positions.seqOf(catalog, ask.since()))
            .orElseThrow(
                () ->
                    new Resync(
                        newest,
                        "Position '"
                            + ask.since()
                            + "' is not one this box can answer from: it names another database,"
                            + " an entry this one no longer holds as it stood, or no position at"
                            + " all."));
    var reader = Actor.reader();
    var audience = Access.audience(reader);
    if (since < floors.floor(reader.actingFde(), audience)) {
      throw new Resync(
          newest,
          "Position '"
              + ask.since()
              + "' is from before a change of what this reader may read: a row it held may have"
              + " gone, or one it may now read may be older than the position.");
    }
    var heads = log.changesAfter(ask.types(), since, ask.limit() + 1, audience);
    var more = heads.size() > ask.limit();
    var page = more ? heads.subList(0, ask.limit()) : heads;
    var rows = rowsOf(page);
    var hearsOnlySteering = Policy.hearsOnlySteering(reader);
    var changes = new ArrayList<Change>();
    for (var head : page) {
      var told =
          told(
              head,
              FeedPositions.of(catalog, head),
              fed.get(head.entityType()),
              rows.get(head.entityType()),
              hearsOnlySteering);
      if (told != null) {
        changes.add(told);
      }
    }
    return new Page(changes, more ? FeedPositions.of(catalog, page.getLast()) : newest, more);
  }

  /** The live rows of {@code page}'s revisions, by type and id, each type read once. */
  private Map<String, Map<String, ? extends Mappable>> rowsOf(List<ChangeLog.Change> page) {
    var revised = new HashMap<String, List<String>>();
    for (var head : page) {
      if (head.kind() == ChangeLog.Kind.REVISION) {
        revised.computeIfAbsent(head.entityType(), type -> new ArrayList<>()).add(head.entityId());
      }
    }
    var rows = new HashMap<String, Map<String, ? extends Mappable>>();
    revised.forEach((type, ids) -> rows.put(type, fed.get(type).rows().readable(ids)));
    return rows;
  }

  /** What the reader is told of {@code head}, at {@code position}, or null when nothing. */
  private static Change told(
      ChangeLog.Change head,
      String position,
      Fed type,
      Map<String, ? extends Mappable> rows,
      boolean hearsOnlySteering) {
    var row = rows == null ? null : rows.get(head.entityId());
    if (row != null) {
      return change(head, position, row, Fate.ROW);
    }
    if (head.kind() == ChangeLog.Kind.REVISION || (type.steered() && hearsOnlySteering)) {
      return null;
    }
    return change(
        head, position, null, head.kind() == ChangeLog.Kind.ERASURE ? Fate.ERASED : Fate.GONE);
  }

  private static Change change(ChangeLog.Change head, String position, Mappable row, Fate fate) {
    return new Change(head.seq(), head.entityType(), head.entityId(), row, fate, position);
  }
}
