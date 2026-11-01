/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.sync;

import ai.singlr.sail.common.Remedy;
import ai.singlr.sail.identity.ProjectScope;
import ai.singlr.sail.store.Sqlite;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
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
    public Remedy describe() {
      var why = reason.endsWith(".") ? reason : reason + ".";
      return Remedy.of(type + " " + id + ": main denied this change — " + why + " ")
          .then(kept(type, id));
    }

    /** Where this box keeps its own version of {@code id}, a row of {@code type} it withdrew. */
    public static Remedy kept(String type, String id) {
      return "spec".equals(type)
          ? Remedy.of("Yours is in its history: ", Remedy.sail("spec history", id), ".")
          : Remedy.of("Yours stays in this box's change log.");
    }
  }

  /**
   * An offer main could not take yet, naming what it awaits: the node left its row as it is and
   * offers it again next round. One that repeats round after round is the sign of a dependency that
   * will never arrive.
   */
  record Refusal(String type, String id, Remedy reason) {
    public Refusal {
      Objects.requireNonNull(type, "type");
      Objects.requireNonNull(id, "id");
      reason = Objects.requireNonNullElse(reason, Remedy.NONE);
    }

    public Refusal(String type, String id, String reason) {
      this(type, id, Remedy.of(Objects.requireNonNullElse(reason, "")));
    }

    /**
     * The refusal as {@code sail sync --json} and the API report it: its plain reason, and the
     * reason as data when it names a command.
     */
    public Map<String, Object> toMap() {
      var map = new LinkedHashMap<String, Object>();
      map.put("type", type);
      map.put("id", id);
      map.put("reason", reason.plain());
      if (!reason.commands().isEmpty()) {
        map.put("remedy", reason.toMaps());
      }
      return map;
    }

    /** The refusal as the FDE reads it: what main did not take yet, and why. */
    public Remedy describe() {
      return Remedy.of(type + " " + id + ": main did not take this yet — ", reason);
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

  /** The handle main authenticated this session as, blank for a session that names no FDE. */
  String handle();

  /**
   * Main's {@code limits.file_max} as its welcome named it: the ceiling the node enforces together
   * with its own before a file ever reaches a sync.
   */
  long mainFileMax();

  /**
   * The projects main's welcome scoped this node to: every project unless main scoped its FDE. Main
   * alone decides it; the node holds what main sends and drops what main stops sending.
   */
  ProjectScope scope();

  /**
   * Holds {@code projects} whole for this round: nothing filed under one is offered, since main no
   * longer grants them and would deny every offer, and the denial withdraw the rows a run still
   * under way here acts through ({@link NodeRound#kept}).
   */
  void keep(Set<String> projects);

  /** The catalog of main's database as its welcome named it; empty when main names none. */
  Optional<String> catalog();

  /** The box id main welcomed this session as: the peer this node's checkpoints are kept for. */
  String mainId();

  /**
   * Tells the session the round removed rows outside any type's reconcile, so its next collection
   * frees the content they left unreferenced.
   */
  void contentLeft();

  /**
   * What main holds of each of {@code ids} of {@code type}: its current version — a revision, a
   * tombstone or an erasure — and the latest version it took from this box, each in request order;
   * an id main never held is omitted from both. Reads only.
   */
  default Held held(String type, List<String> ids) {
    return held(type, ids, Map.of());
  }

  /**
   * As {@link #held(String, List)}, with {@code accepted} holding only what main took from this box
   * after the version this box last heard of each id named in {@code heard}.
   */
  Held held(String type, List<String> ids, Map<String, String> heard);

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
   * one line of {@code notice} when this node runs ahead of main's version, and each offer main
   * denies handed to {@code denied} as it settles, for the caller to word; a refusal or anything
   * else fails naming it, and an answer that is not a message at all is reported as what it is.
   */
  static SyncSession open(
      InputStream in,
      OutputStream out,
      SyncWire.Hello hello,
      Consumer<Remedy> notice,
      Consumer<Denial> denied,
      Sqlite db) {
    return ((PagedSyncSession) open(in, out, hello, notice, denied)).content(db);
  }

  static SyncSession open(
      InputStream in,
      OutputStream out,
      SyncWire.Hello hello,
      Consumer<Remedy> notice,
      Consumer<Denial> denied) {
    var context = SyncWire.context(hello);
    var line = Rpc.exchange(in, out, SyncWire.encode(hello), context);
    SyncWire.Response response;
    try {
      response = SyncWire.decodeResponse(line);
    } catch (RuntimeException e) {
      throw new SyncTransportException(
          "protocol", context + ": main did not answer with a sync message: " + e.getMessage(), e);
    }
    return switch (response) {
      case SyncWire.Welcome welcome ->
          PagedSyncSession.open(in, out, hello, welcome, notice, denied);
      case SyncWire.Refuse refuse ->
          throw new SyncTransportException("refused", context + ":", refuse.reason(), null);
      case SyncWire.Failed failed ->
          throw new SyncTransportException(failed.kind(), context + ":", failed.detail(), null);
      default ->
          throw new SyncTransportException(context + ": Expected a welcome, got: " + response);
    };
  }
}
