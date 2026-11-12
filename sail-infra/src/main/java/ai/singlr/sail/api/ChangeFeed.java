/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.api;

import ai.singlr.sail.common.Remedied;
import ai.singlr.sail.common.Remedy;
import ai.singlr.sail.common.Strings;
import ai.singlr.sail.store.ChangeLog;
import ai.singlr.sail.store.Erasure;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * The rows that changed after a position, as {@code GET /v1/changes} answers them and the event
 * stream sends them: the one read of row changes both serve (F7). A change is ordered by the {@code
 * seq} the box's change log gave its revision (F1), and is served once a page, at its newest seq.
 * The feed serves a reader three things: a row it may read now, shaped as the row's own list route
 * shapes it less the fields that list derives from other rows, which the client derives; the
 * deletion ({@code gone}) or erasure ({@code erased}) of a row filed, at its head, where it may
 * read now, with no content; and nothing else. A change of what the reader may read is never
 * derived from where a row used to be filed: it raises a floor, and the reader is answered {@link
 * Resync} (F4). A position a client holds is opaque: it names the database, the seq and the entry
 * standing at it, so one from another database or a restored copy is refused, never answered in
 * part.
 *
 * <p>A change to one row never tells another.
 *
 * <p>A client follows the feed only from the position of the reads it holds, never an older one,
 * and applies each change in the order it is told; a change whose {@code seq} is not newer than the
 * last it applied to the row is one it was already told. Told to resync, it reads the rows afresh
 * and follows the feed from the position those reads answer.
 */
public interface ChangeFeed {

  /** The changes a page answers when the reader names no limit. */
  int DEFAULT_LIMIT = 100;

  /** The most a page answers, however many the reader asks for (F6). */
  int MAX_LIMIT = 500;

  /** The types the feed serves: the rows a client keeps of the board and its conversations. */
  List<String> TYPES = List.of(Erasure.SPEC, Erasure.ROOM, Erasure.MESSAGE);

  /**
   * The header every list and detail read of a fed type answers: the position it was read at
   * ({@link #at}).
   */
  String SEQ_HEADER = "Seq";

  /** The feed of a box that keeps no change log: nothing ever changed there. */
  ChangeFeed NONE = new None();

  /** The newest position of the box: where a reader that names none starts. */
  String newest();

  /**
   * Calls {@code wake} as each change this process commits lands, until the returned handle is
   * closed: what a waiting stream reads the next page on, so a change reaches it as it commits.
   */
  ChangeLog.Watch watch(Runnable wake);

  /**
   * Wakes every waiting stream as a commit of this process would ({@link #watch}), when the box's
   * change log grew since the last wake: how another process that wrote the box's database tells
   * the server so. It carries nothing, and every page it wakes is read as its reader.
   */
  void wake();

  /**
   * The changes {@code ask} names that the bound reader may be told of, in seq order.
   *
   * @throws Resync when {@code ask}'s position is not one this box can answer this reader from
   */
  Page page(Ask ask);

  /**
   * What {@code read} answers, with the position it was read at: the newest, read in the same
   * snapshot as {@code read}'s rows, so reading the feed from it misses nothing the answer does not
   * hold.
   */
  <T> Positioned<T> at(Supplier<T> read);

  /**
   * What a door answers {@code query}, a {@code GET /v1/changes}: a page of {@code feed}, or the
   * refusal of a position it cannot answer from, naming where to read afresh from in its {@link
   * #SEQ_HEADER}.
   */
  static ApiResponse answer(ChangeFeed feed, Map<String, String> query) {
    var ask = Ask.of(query);
    try {
      return ApiResponse.ok(feed.page(ask));
    } catch (Resync resync) {
      return ApiResponse.error(
              new ApiException(ErrorCode.RESYNC_REQUIRED, resync.getMessage(), Resync.ACTION)
                  .failure())
          .withHeader(SEQ_HEADER, resync.newest());
    }
  }

  /** An answer and the position it was read at. */
  record Positioned<T>(T value, String position) {}

  /**
   * What a reader asks of the feed: the changes of {@code types}, in {@link #TYPES}' order, after
   * the position {@code since}, at most {@code limit} of them. Refused whole when any part is not
   * one the feed answers, naming what to send instead.
   */
  record Ask(String since, Set<String> types, int limit, boolean minted) {
    public Ask {
      if (Strings.isBlank(since)) {
        throw new IllegalArgumentException(
            "Name the position to read from: since=<position>, the seq header of the read the"
                + " client holds, or the next of the page it read last.");
      }
      types = served(types);
      requireLimit(limit);
    }

    /**
     * The ask {@code query} makes of {@code GET /v1/changes}: {@code since}, which it must name,
     * {@code types}, every fed type when it names none, and {@code limit}, {@link #DEFAULT_LIMIT}
     * when it names none.
     */
    public static Ask of(Map<String, String> query) {
      return new Ask(query.get("since"), types(query.get("types")), limit(query.get("limit")));
    }

    /** {@code types} as a query names them, comma-separated; every fed type when it names none. */
    public static Set<String> types(String types) {
      return served(
          Strings.isBlank(types)
              ? Set.copyOf(TYPES)
              : Arrays.stream(types.split(",")).map(String::strip).collect(Collectors.toSet()));
    }

    /** {@code types} in {@link #TYPES}' order, refused when one is not a type the feed serves. */
    private static Set<String> served(Set<String> types) {
      if (types.isEmpty() || !Set.copyOf(TYPES).containsAll(types)) {
        throw new IllegalArgumentException(
            "types names the rows the feed serves, among " + String.join(", ", TYPES) + ".");
      }
      var ordered = new LinkedHashSet<>(TYPES);
      ordered.retainAll(types);
      return Collections.unmodifiableSet(ordered);
    }

    /** The ask of a client, which names a position this box must check before it answers. */
    public Ask(String since, Set<String> types, int limit) {
      this(since, types, limit, false);
    }

    /**
     * The ask of a stream reading on from {@code since}, the next position a page of this box
     * answered it: the box's own, so its entry is not checked again, and one erased since the
     * stream read it is no reason to end the stream. Its reader's floor is checked as for any ask.
     */
    public static Ask minted(String since, Set<String> types, int limit) {
      return new Ask(since, types, limit, true);
    }

    /** The changes after {@code since} of the same types, as many as this ask's limit. */
    public Ask after(String since) {
      return new Ask(since, types, limit, minted);
    }

    private static int limit(String limit) {
      if (Strings.isBlank(limit)) {
        return DEFAULT_LIMIT;
      }
      try {
        return (int) requireLimit(Long.parseLong(limit.strip()));
      } catch (NumberFormatException e) {
        throw new IllegalArgumentException("limit must be a whole number, not '" + limit + "'.", e);
      }
    }

    private static long requireLimit(long limit) {
      if (limit < 1 || limit > MAX_LIMIT) {
        throw new IllegalArgumentException(
            "limit must be between 1 and " + MAX_LIMIT + ", not " + limit + ".");
      }
      return limit;
    }
  }

  /**
   * One page: its changes, the position to ask from next, and whether more changes follow it. With
   * nothing more to tell, the next position is the newest, past whatever was read and not told.
   */
  record Page(List<Change> changes, String next, boolean more) implements Mappable {
    public Page {
      changes = List.copyOf(changes);
    }

    @Override
    public Map<String, Object> toMap() {
      var map = new LinkedHashMap<String, Object>();
      map.put("changes", changes);
      map.put("next", next);
      map.put("more", more);
      return map;
    }
  }

  /**
   * One row's change at {@code seq}: the row as its read route shapes it, or, when there is none to
   * show, why: {@link Fate#GONE} for a row deleted, {@link Fate#ERASED} for one erased for good.
   * {@code position} is where a stream resumes after it: its SSE {@code id}, never in its data.
   */
  record Change(long seq, String type, String id, Mappable row, Fate fate, String position)
      implements Mappable {
    public Change {
      Objects.requireNonNull(fate, "fate");
      Objects.requireNonNull(position, "position");
      if ((row == null) != (fate != Fate.ROW)) {
        throw new IllegalArgumentException("A change carries a row exactly when its fate is ROW");
      }
    }

    @Override
    public Map<String, Object> toMap() {
      var map = new LinkedHashMap<String, Object>();
      map.put("seq", seq);
      map.put("type", type);
      map.put("id", id);
      switch (fate) {
        case ROW -> map.put("row", row);
        case GONE -> map.put("gone", true);
        case ERASED -> map.put("erased", true);
      }
      return map;
    }
  }

  /** What a change tells of its row. */
  enum Fate {
    ROW,
    GONE,
    ERASED
  }

  /**
   * A position this box cannot answer a reader from: not one of its positions at all, of another
   * database, of an entry this one no longer holds as it stood, or below the reader's floor. The
   * reader reads afresh from {@code newest}.
   */
  final class Resync extends RuntimeException implements Remedied {

    /** What a reader told to resync does. */
    public static final String ACTION =
        "Read the rows afresh, then follow the feed from the seq header those reads answer.";

    private final String newest;

    public Resync(String newest, String message) {
      super(message);
      this.newest = Objects.requireNonNull(newest, "newest");
    }

    /** The position to read afresh from: the newest when the refusal was decided. */
    public String newest() {
      return newest;
    }

    @Override
    public String reason() {
      return getMessage();
    }

    @Override
    public Remedy remedy() {
      return Remedy.of(ACTION);
    }
  }

  /** {@link #NONE}: its one position is its newest, from which nothing ever follows. */
  final class None implements ChangeFeed {
    private static final String NEWEST = "none.0.0";

    private None() {}

    @Override
    public String newest() {
      return NEWEST;
    }

    @Override
    public ChangeLog.Watch watch(Runnable wake) {
      return () -> {};
    }

    @Override
    public void wake() {}

    @Override
    public Page page(Ask ask) {
      if (!NEWEST.equals(ask.since())) {
        throw new Resync(NEWEST, "This box keeps no change log, so no position is one of its own.");
      }
      return new Page(List.of(), NEWEST, false);
    }

    @Override
    public <T> Positioned<T> at(Supplier<T> read) {
      return new Positioned<>(read.get(), NEWEST);
    }
  }
}
