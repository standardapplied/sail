/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.sync;

import ai.singlr.sail.config.YamlUtil;
import java.io.Reader;
import java.io.Writer;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * The protocol-3 session, kept for exactly one release so a node upgraded ahead of its main keeps
 * syncing: main answers a whole table per {@link Fetch}, takes one {@link Commit} per pushed
 * entity, and knows nothing of pages, boxes or heads. Everything protocol 3 needs — its request and
 * response records, their key-sniffing codec, and the whole-table replica — lives in this one file,
 * referenced from {@link SyncSession#open} alone, so {@code sail-sync-drop-v3} deletes it whole.
 *
 * <p>The checkpoint a legacy round advances to is the fetched high-water, never the one main
 * reports after this node's own commits: protocol 4 reads checkpoints, and a node must not carry
 * one past entries it never saw into the release that will honour it.
 */
public final class LegacySyncSession implements SyncSession {

  static final String FLOOR = "0.34.0";
  private static final String HELLO_REFUSAL = "Unknown sync op: hello";

  private final Reader in;
  private final Writer out;
  private final SyncEngine engine = new SyncEngine();

  LegacySyncSession(Reader in, Writer out) {
    this.in = Objects.requireNonNull(in, "in");
    this.out = Objects.requireNonNull(out, "out");
  }

  /** Whether {@code line} is the exact answer a protocol-3 main gives a hello it cannot decode. */
  static boolean answeredHello(String line) {
    try {
      return decodeResponse(line) instanceof Failed failed
          && "protocol".equals(failed.kind())
          && failed.message() != null
          && failed.message().contains(HELLO_REFUSAL);
    } catch (RuntimeException notLegacy) {
      return false;
    }
  }

  @Override
  public TypeReport reconcile(String type, LocalReplica local) {
    var main = new WholeTableReplica(type);
    var report = engine.reconcile(local, main);
    return new TypeReport(type, report, 1, main.entityIds().size(), false, null);
  }

  @Override
  public List<Map<String, Object>> fetchFdes() {
    var response = exchange(new FetchFdes(), "fde");
    if (response instanceof Fdes roster) {
      return roster.fdes();
    }
    if (response instanceof Failed failed) {
      throw new SyncTransportException(failed.kind(), "fde: " + failed.message(), null);
    }
    throw new SyncTransportException("fde: Expected an fde roster, got: " + response);
  }

  @Override
  public void close() {
    Rpc.send(out, encode(new Bye()));
  }

  private Response exchange(Request request, String context) {
    var line = Rpc.exchange(in, out, encode(request), context);
    try {
      return decodeResponse(line);
    } catch (RuntimeException e) {
      throw new SyncTransportException("protocol", context + ": " + e.getMessage(), e);
    }
  }

  sealed interface Request permits Fetch, Commit, FetchFdes, Bye {}

  record Fetch(String entityType, String upgradeFloor) implements Request {}

  record Commit(
      String entityType, String entityId, Map<String, Object> snapshot, String expectedRev)
      implements Request {}

  record FetchFdes() implements Request {}

  record Bye() implements Request {}

  sealed interface Response permits Fetched, Committed, Rejected, Failed, Fdes {}

  record Snapshot(String rev, Map<String, Object> snapshot) {}

  record Fetched(String mainId, long maxSeq, Map<String, Snapshot> entities, String upgradeFloor)
      implements Response {}

  record Committed(String rev, long maxSeq) implements Response {}

  record Rejected(String currentRev, Map<String, Object> currentSnapshot) implements Response {}

  record Failed(String message, String kind) implements Response {}

  record Fdes(List<Map<String, Object>> fdes) implements Response {}

  static String encode(Request request) {
    var map = new LinkedHashMap<String, Object>();
    switch (request) {
      case Fetch fetch -> {
        map.put("op", "fetch");
        map.put("entityType", fetch.entityType());
        map.put("upgradeFloor", fetch.upgradeFloor());
      }
      case Commit commit -> {
        map.put("op", "commit");
        map.put("entityType", commit.entityType());
        map.put("id", commit.entityId());
        map.put("snapshot", commit.snapshot());
        map.put("expectedRev", commit.expectedRev());
      }
      case FetchFdes ignored -> map.put("op", "fetch-fdes");
      case Bye ignored -> map.put("op", "bye");
    }
    return YamlUtil.dumpJson(map);
  }

  static String encode(Response response) {
    var map = new LinkedHashMap<String, Object>();
    switch (response) {
      case Fetched fetched -> {
        map.put("id", fetched.mainId());
        map.put("maxSeq", fetched.maxSeq());
        map.put("upgradeFloor", fetched.upgradeFloor());
        var entities = new LinkedHashMap<String, Object>();
        fetched
            .entities()
            .forEach(
                (id, snapshot) -> {
                  var entry = new LinkedHashMap<String, Object>();
                  entry.put("rev", snapshot.rev());
                  entry.put("snapshot", snapshot.snapshot());
                  entities.put(id, entry);
                });
        map.put("entities", entities);
      }
      case Committed committed -> {
        map.put("rev", committed.rev());
        map.put("maxSeq", committed.maxSeq());
      }
      case Rejected rejected -> {
        map.put("stale", true);
        map.put("rev", rejected.currentRev());
        map.put("snapshot", rejected.currentSnapshot());
      }
      case Failed failed -> {
        map.put("error", failed.message());
        map.put("error_kind", failed.kind());
      }
      case Fdes roster -> map.put("fdes", roster.fdes());
    }
    return YamlUtil.dumpJson(map);
  }

  static Request decodeRequest(String line) {
    var map = YamlUtil.parseJsonLine(line, SyncWire.MAX_FRAME);
    var op = string(map, "op");
    return switch (op) {
      case "fetch" -> new Fetch(string(map, "entityType"), string(map, "upgradeFloor"));
      case "commit" ->
          new Commit(
              string(map, "entityType"),
              string(map, "id"),
              snapshot(map, "snapshot"),
              string(map, "expectedRev"));
      case "fetch-fdes" -> new FetchFdes();
      case "bye" -> new Bye();
      case null, default -> throw new IllegalArgumentException("Unknown sync op: " + op);
    };
  }

  @SuppressWarnings("unchecked")
  static Response decodeResponse(String line) {
    var map = YamlUtil.parseJsonLine(line, SyncWire.MAX_FRAME);
    if (map.containsKey("error")) {
      return new Failed(
          string(map, "error"),
          map.containsKey("error_kind") ? string(map, "error_kind") : "refused");
    }
    if (map.containsKey("stale")) {
      return new Rejected(string(map, "rev"), snapshot(map, "snapshot"));
    }
    if (map.containsKey("fdes")) {
      return new Fdes(
          map.get("fdes") instanceof List<?> list ? (List<Map<String, Object>>) list : List.of());
    }
    if (map.containsKey("entities")) {
      var entities = new LinkedHashMap<String, Snapshot>();
      var raw = snapshot(map, "entities");
      if (raw != null) {
        raw.forEach(
            (id, value) -> {
              var entry = (Map<String, Object>) value;
              entities.put(id, new Snapshot(string(entry, "rev"), snapshot(entry, "snapshot")));
            });
      }
      return new Fetched(
          string(map, "id"), longValue(map, "maxSeq"), entities, string(map, "upgradeFloor"));
    }
    if (map.containsKey("rev")) {
      return new Committed(string(map, "rev"), longValue(map, "maxSeq"));
    }
    throw new IllegalArgumentException("Unrecognized sync response: " + line);
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

  /** Main's whole table of one type, fetched once; commits go one at a time. */
  private final class WholeTableReplica implements MainReplica {
    private final String entityType;
    private Fetched fetched;

    WholeTableReplica(String entityType) {
      this.entityType = entityType;
    }

    @Override
    public String id() {
      return fetched().mainId();
    }

    @Override
    public Set<String> entityIds() {
      return fetched().entities().keySet();
    }

    @Override
    public Map<String, Object> current(String entityId) {
      var snapshot = fetched().entities().get(entityId);
      return snapshot == null ? null : snapshot.snapshot();
    }

    @Override
    public String currentRev(String entityId) {
      var snapshot = fetched().entities().get(entityId);
      return snapshot == null ? null : snapshot.rev();
    }

    @Override
    public long maxSeq() {
      return fetched().maxSeq();
    }

    @Override
    public CommitOutcome commit(String entityId, Map<String, Object> snapshot, String expectedRev) {
      var context = entityType + " " + entityId;
      return switch (exchange(new Commit(entityType, entityId, snapshot, expectedRev), context)) {
        case Committed committed -> new CommitOutcome.Accepted(committed.rev());
        case Rejected rejected ->
            new CommitOutcome.Rejected(rejected.currentRev(), rejected.currentSnapshot());
        case Failed failed ->
            throw new SyncTransportException(
                failed.kind(), context + ": " + failed.message(), null);
        default ->
            throw new SyncTransportException(context + ": Unexpected response to commit: " + this);
      };
    }

    private Fetched fetched() {
      if (fetched == null) {
        var response = exchange(new Fetch(entityType, FLOOR), entityType);
        if (response instanceof Failed failed) {
          throw new SyncTransportException(
              failed.kind(), entityType + ": " + failed.message(), null);
        }
        if (!(response instanceof Fetched f)) {
          throw new SyncTransportException(
              entityType + ": Expected a fetch response, got: " + response);
        }
        if (!FLOOR.equals(f.upgradeFloor())) {
          throw new SyncTransportException(
              entityType
                  + ": Sync requires Sail "
                  + FLOOR
                  + " or newer on every box. Run 'sail upgrade' on main, then sync again.");
        }
        fetched = f;
      }
      return fetched;
    }
  }
}
