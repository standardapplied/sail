/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.sync;

import ai.singlr.sail.config.YamlUtil;
import java.io.IOException;
import java.io.Reader;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The line protocol for one sync session over a Door-2 SSH channel: the node and main exchange one
 * JSON object per line, never spanning lines because {@link YamlUtil#dumpJson} escapes newlines in
 * spec bodies. Both sides share this single definition of the wire so the encode and decode never
 * drift. The node issues {@link Fetch} once (main answers with its whole shared state), a {@link
 * Commit} per pushed entity (main mints and returns the authoritative rev), and {@link Bye} to end
 * the session; main answers each with the matching {@link Response}.
 *
 * <p>A {@code null} snapshot is a deletion — it crosses the wire as an explicit JSON {@code null},
 * distinct from an absent key, so a tombstone is never mistaken for a missing row.
 */
public final class SyncWire {

  private static final String OP = "op";
  private static final String ID = "id";
  private static final String MAX_SEQ = "maxSeq";
  private static final String ENTITIES = "entities";
  private static final String REV = "rev";
  private static final String SNAPSHOT = "snapshot";
  private static final String ERROR = "error";
  private static final String EXPECTED = "expectedRev";
  private static final String STALE = "stale";
  private static final String ENTITY_TYPE = "entityType";

  private static final String OP_FETCH = "fetch";
  private static final String OP_COMMIT = "commit";
  private static final String OP_BYE = "bye";
  private static final String OP_FETCH_FDES = "fetch-fdes";
  private static final String FDES = "fdes";

  /**
   * Hard ceiling on one framed message, bounding the memory a single read can claim. A sync message
   * is one whole-board fetch or one spec at a time, so 64 MiB is far above any legitimate size yet
   * stops an authenticated-but-low-trust peer from starving the receiver with one endless line.
   */
  static final int MAX_MESSAGE_CHARS = 64 * 1024 * 1024;

  private SyncWire() {}

  /**
   * Reads one newline-framed message, bounded by {@link #MAX_MESSAGE_CHARS}. Returns {@code null}
   * at end of stream (a clean session close), the line without its terminator otherwise. Used by
   * both ends so the framing — and its bound — has a single definition.
   */
  public static String readFramed(Reader in) throws IOException {
    return readFramed(in, MAX_MESSAGE_CHARS);
  }

  static String readFramed(Reader in, int maxChars) throws IOException {
    var message = new StringBuilder();
    for (var c = in.read(); c != -1; c = in.read()) {
      if (c == '\n') {
        return message.toString();
      }
      if (message.length() >= maxChars) {
        throw new SyncTransportException("Sync message exceeded " + maxChars + " characters.");
      }
      message.append((char) c);
    }
    return message.isEmpty() ? null : message.toString();
  }

  public sealed interface Request permits Fetch, Commit, Bye, FetchFdes {}

  /**
   * Ask main for its whole shared state of one entity type — specs, files — to reconcile against.
   */
  public record Fetch(String entityType) implements Request {}

  /** Ask main for its FDE roster, which the node mirrors (main-authoritative, one-way). */
  public record FetchFdes() implements Request {}

  /**
   * Push one entity of {@code entityType} to main against the rev the node fetched ({@code
   * expectedRev}, {@code null} for a row new to main); a {@code null} snapshot pushes a deletion.
   */
  public record Commit(
      String entityType, String entityId, Map<String, Object> snapshot, String expectedRev)
      implements Request {}

  /** End the session; main returns nothing. */
  public record Bye() implements Request {}

  public sealed interface Response permits Fetched, Committed, Rejected, Failed, Fdes {}

  /** Main's FDE roster — one map of identity fields per FDE. */
  public record Fdes(List<Map<String, Object>> fdes) implements Response {}

  /** Main's authoritative rev and snapshot for one entity; either may be {@code null}. */
  public record Snapshot(String rev, Map<String, Object> snapshot) {}

  /** Main's answer to {@link Fetch}: its identity, high-water sequence, and every shared entity. */
  public record Fetched(String mainId, long maxSeq, Map<String, Snapshot> entities)
      implements Response {}

  /** Main accepted a {@link Commit}: the minted rev and main's new high-water sequence. */
  public record Committed(String rev, long maxSeq) implements Response {}

  /** Main rejected a stale {@link Commit}: its current rev and snapshot, left untouched. */
  public record Rejected(String currentRev, Map<String, Object> currentSnapshot)
      implements Response {}

  /** Main refused a request — e.g. a read-only FDE attempting to push. */
  public record Failed(String message) implements Response {}

  public static String encode(Request request) {
    var map = new LinkedHashMap<String, Object>();
    switch (request) {
      case Fetch fetch -> {
        map.put(OP, OP_FETCH);
        map.put(ENTITY_TYPE, fetch.entityType());
      }
      case Commit commit -> {
        map.put(OP, OP_COMMIT);
        map.put(ENTITY_TYPE, commit.entityType());
        map.put(ID, commit.entityId());
        map.put(SNAPSHOT, commit.snapshot());
        map.put(EXPECTED, commit.expectedRev());
      }
      case Bye ignored -> map.put(OP, OP_BYE);
      case FetchFdes ignored -> map.put(OP, OP_FETCH_FDES);
    }
    return YamlUtil.dumpJson(map);
  }

  public static String encode(Response response) {
    var map = new LinkedHashMap<String, Object>();
    switch (response) {
      case Fetched fetched -> {
        map.put(ID, fetched.mainId());
        map.put(MAX_SEQ, fetched.maxSeq());
        var entities = new LinkedHashMap<String, Object>();
        fetched
            .entities()
            .forEach(
                (id, snapshot) -> {
                  var entry = new LinkedHashMap<String, Object>();
                  entry.put(REV, snapshot.rev());
                  entry.put(SNAPSHOT, snapshot.snapshot());
                  entities.put(id, entry);
                });
        map.put(ENTITIES, entities);
      }
      case Committed committed -> {
        map.put(REV, committed.rev());
        map.put(MAX_SEQ, committed.maxSeq());
      }
      case Rejected rejected -> {
        map.put(STALE, true);
        map.put(REV, rejected.currentRev());
        map.put(SNAPSHOT, rejected.currentSnapshot());
      }
      case Failed failed -> map.put(ERROR, failed.message());
      case Fdes roster -> map.put(FDES, roster.fdes());
    }
    return YamlUtil.dumpJson(map);
  }

  public static Request decodeRequest(String line) {
    var map = YamlUtil.parseMap(line);
    var op = string(map, OP);
    return switch (op) {
      case OP_FETCH -> new Fetch(string(map, ENTITY_TYPE));
      case OP_COMMIT ->
          new Commit(
              string(map, ENTITY_TYPE),
              string(map, ID),
              snapshot(map, SNAPSHOT),
              string(map, EXPECTED));
      case OP_BYE -> new Bye();
      case OP_FETCH_FDES -> new FetchFdes();
      case null, default -> throw new IllegalArgumentException("Unknown sync op: " + op);
    };
  }

  public static Response decodeResponse(String line) {
    var map = YamlUtil.parseMap(line);
    if (map.containsKey(ERROR)) {
      return new Failed(string(map, ERROR));
    }
    if (map.containsKey(STALE)) {
      return new Rejected(string(map, REV), snapshot(map, SNAPSHOT));
    }
    if (map.containsKey(FDES)) {
      return new Fdes(fdeList(map));
    }
    if (map.containsKey(ENTITIES)) {
      return new Fetched(string(map, ID), longValue(map, MAX_SEQ), entities(map));
    }
    if (map.containsKey(REV)) {
      return new Committed(string(map, REV), longValue(map, MAX_SEQ));
    }
    throw new IllegalArgumentException("Unrecognized sync response: " + line);
  }

  private static Map<String, Snapshot> entities(Map<String, Object> map) {
    var entities = new LinkedHashMap<String, Snapshot>();
    var raw = snapshot(map, ENTITIES);
    if (raw != null) {
      raw.forEach((id, value) -> entities.put(id, snapshot(value)));
    }
    return entities;
  }

  @SuppressWarnings("unchecked")
  private static List<Map<String, Object>> fdeList(Map<String, Object> map) {
    return map.get(FDES) instanceof List<?> list ? (List<Map<String, Object>>) list : List.of();
  }

  @SuppressWarnings("unchecked")
  private static Snapshot snapshot(Object value) {
    var entry = (Map<String, Object>) value;
    return new Snapshot(string(entry, REV), (Map<String, Object>) entry.get(SNAPSHOT));
  }

  private static String string(Map<String, Object> map, String key) {
    var value = map.get(key);
    return value == null ? null : value.toString();
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> snapshot(Map<String, Object> map, String key) {
    return (Map<String, Object>) map.get(key);
  }

  private static long longValue(Map<String, Object> map, String key) {
    return map.get(key) instanceof Number n ? n.longValue() : 0L;
  }
}
