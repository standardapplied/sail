/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.sync;

import ai.singlr.sail.store.Sqlite;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * One node-to-main sync session over a channel: the seam the round drives, reconciling one entity
 * type at a time, pulling the FDE roster, and ending the session on {@link #close}. All exchanges
 * share the one reader/writer and run sequentially, so the typed exchanges never interleave on the
 * wire. {@link #open} sends the hello and returns the paged protocol-4 session main welcomed.
 */
public sealed interface SyncSession extends AutoCloseable permits PagedSyncSession {

  /**
   * An offer main denied, naming why: the node settled it to main's version, unless it is work
   * still live here, and its own version stays in its history.
   */
  record Denial(String type, String id, String reason) {
    public Denial {
      Objects.requireNonNull(type, "type");
      Objects.requireNonNull(id, "id");
      reason = Objects.requireNonNullElse(reason, "");
    }

    /** The denial read back from {@link #toMap}, or empty for anything that is not one. */
    public static Optional<Denial> fromMap(Object value) {
      if (!(value instanceof Map<?, ?> map)
          || !(map.get("type") instanceof String type)
          || !(map.get("id") instanceof String id)) {
        return Optional.empty();
      }
      return Optional.of(new Denial(type, id, Objects.toString(map.get("reason"), "")));
    }

    public Map<String, Object> toMap() {
      var map = new LinkedHashMap<String, Object>();
      map.put("type", type);
      map.put("id", id);
      map.put("reason", reason);
      return map;
    }

    /** The denial as the FDE reads it: what main refused, why, and where their version is kept. */
    public String describe() {
      var kept =
          "spec".equals(type)
              ? "Yours is in its history: sail spec history " + id + "."
              : "Yours stays in this box's change log.";
      var why = reason.endsWith(".") ? reason : reason + ".";
      return type + " " + id + ": main denied this change — " + why + " " + kept;
    }
  }

  /**
   * An offer main could not take yet, naming what it awaits: the node left its row as it is and
   * offers it again next round. One that repeats round after round is the sign of a dependency that
   * will never arrive.
   */
  record Refusal(String type, String id, String reason) {
    public Refusal {
      Objects.requireNonNull(type, "type");
      Objects.requireNonNull(id, "id");
      reason = Objects.requireNonNullElse(reason, "");
    }

    public Map<String, Object> toMap() {
      var map = new LinkedHashMap<String, Object>();
      map.put("type", type);
      map.put("id", id);
      map.put("reason", reason);
      return map;
    }

    /** The refusal as the FDE reads it: what main did not take yet, and why. */
    public String describe() {
      return type + " " + id + ": main did not take this yet — " + reason;
    }
  }

  /**
   * How one entity type fared in a round: the engine's counts, how many pages and entries main
   * served for it, whether nothing at all had to move ({@code skipped}), the failure that stopped
   * it, if one did, the content bytes it moved each way, the bytes the collection after it freed,
   * the offers main denied, and the offers main did not take yet.
   */
  record TypeReport(
      String type,
      SyncEngine.Report report,
      int pages,
      int entries,
      boolean skipped,
      String failure,
      long fetchedBytes,
      long sentBytes,
      long freedBytes,
      List<Denial> denials,
      List<Refusal> refusals) {
    public TypeReport {
      denials = List.copyOf(denials);
      refusals = List.copyOf(refusals);
    }

    public TypeReport(
        String type,
        SyncEngine.Report report,
        int pages,
        int entries,
        boolean skipped,
        String failure,
        long fetchedBytes,
        long sentBytes) {
      this(
          type,
          report,
          pages,
          entries,
          skipped,
          failure,
          fetchedBytes,
          sentBytes,
          0,
          List.of(),
          List.of());
    }

    public TypeReport(
        String type,
        SyncEngine.Report report,
        int pages,
        int entries,
        boolean skipped,
        String failure) {
      this(type, report, pages, entries, skipped, failure, 0, 0);
    }

    /** This report with the bytes the collection after it freed. */
    public TypeReport withFreedBytes(long freed) {
      return new TypeReport(
          type,
          report,
          pages,
          entries,
          skipped,
          failure,
          fetchedBytes,
          sentBytes,
          freed,
          denials,
          refusals);
    }

    public static TypeReport failed(String type, String failure) {
      return new TypeReport(type, SyncEngine.Report.NONE, 0, 0, false, failure);
    }
  }

  /**
   * Reconciles every entity of {@code type}: what main changed since this node's checkpoint, then
   * what this node changed itself, pushing and adopting through the engine as it goes.
   */
  TypeReport reconcile(String type, LocalReplica local);

  /**
   * The handle main authenticated this session as, blank for a session that names no FDE; empty
   * against a main that predates saying so.
   */
  Optional<String> handle();

  /**
   * Main's {@code limits.file_max} as its welcome named it, 0 from a main that predates saying so:
   * the ceiling the node enforces together with its own before a file ever reaches a sync.
   */
  long mainFileMax();

  /**
   * What main holds of each of {@code ids} of {@code type}: its current version — a revision, a
   * tombstone or an erasure — and the latest version it took from this box, each in request order;
   * an id main never held is omitted from both. Reads only.
   */
  Held held(String type, List<String> ids);

  /**
   * Main's answer about ids a node asked after: {@code current} is main's version of each it holds,
   * {@code accepted} the latest version of each it took from this box, as main recorded it.
   */
  record Held(List<SyncWire.Entry> current, List<SyncWire.Entry> accepted) {
    public Held {
      current = List.copyOf(current);
      accepted = List.copyOf(accepted);
    }
  }

  /** Pulls main's FDE roster; the node mirrors it main-authoritatively. */
  List<Map<String, Object>> fetchFdes();

  /** Ends the session; main returns nothing. */
  @Override
  void close();

  /**
   * Opens a session with {@code hello}. A {@link SyncWire.Welcome} yields the paged session, with
   * one line of {@code notice} when this node runs ahead of main's version; a refusal or anything
   * else fails naming it. An answer that is a message but names no {@code op} is what every
   * protocol before 4 put on the wire, so it fails naming the remedy — upgrade main. Anything that
   * is not a message at all is reported as what it is, never blamed on main's version.
   */
  static SyncSession open(
      InputStream in, OutputStream out, SyncWire.Hello hello, Consumer<String> notice, Sqlite db) {
    return ((PagedSyncSession) open(in, out, hello, notice)).content(db);
  }

  static SyncSession open(
      InputStream in, OutputStream out, SyncWire.Hello hello, Consumer<String> notice) {
    var context = SyncWire.context(hello);
    var line = Rpc.exchange(in, out, SyncWire.encode(hello), context);
    if (SyncWire.predatesOps(line)) {
      throw new SyncTransportException(
          "refused",
          context
              + ": main is on a sync protocol this node cannot speak: upgrade main ('sail upgrade'"
              + " on main), then sync again",
          null);
    }
    SyncWire.Response response;
    try {
      response = SyncWire.decodeResponse(line);
    } catch (RuntimeException e) {
      throw new SyncTransportException(
          "protocol", context + ": main did not answer with a sync message: " + e.getMessage(), e);
    }
    return switch (response) {
      case SyncWire.Welcome welcome -> PagedSyncSession.open(in, out, hello, welcome, notice);
      case SyncWire.Refuse refuse ->
          throw new SyncTransportException("refused", context + ": " + refuse.reason(), null);
      case SyncWire.Failed failed ->
          throw new SyncTransportException(failed.kind(), context + ": " + failed.message(), null);
      default ->
          throw new SyncTransportException(context + ": Expected a welcome, got: " + response);
    };
  }
}
