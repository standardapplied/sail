/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.sync;

import ai.singlr.sail.config.YamlUtil;
import java.io.IOException;
import java.io.Reader;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The line protocol of one sync session over a Door-2 SSH channel, protocol 4: the node and main
 * exchange one JSON object per line, never spanning lines because {@link YamlUtil#dumpJson} escapes
 * newlines. Every message, request or response, carries {@code op}, and each decodes into exactly
 * one record here — nothing else sniffs keys. Both sides share this single definition so the encode
 * and decode never drift.
 *
 * <p>A session opens with {@link Hello} and is answered {@link Welcome} or {@link Refuse}. The node
 * then reads main's change log since its checkpoint one {@link Page} at a time ({@link Pull}), asks
 * for main's current rows of what it changed itself ({@link Need}), pushes in batches ({@link
 * Push}, answered by {@link Results}), mirrors the FDE roster ({@link FetchFdes}) and ends with
 * {@link Bye}. {@link Heads} lets an idle round cost one exchange: main's {@link Tips} say which
 * types moved past the node's checkpoints.
 *
 * <p>A {@code null} snapshot is a deletion — it crosses the wire as an explicit JSON {@code null},
 * distinct from an absent key, so a tombstone is never mistaken for a missing row.
 */
public final class SyncWire {

  /** The protocol this build speaks; a peer announcing another is refused. */
  public static final int PROTOCOL = 4;

  /**
   * The fleet floor both sides must advertise before exchanging rows: the release that introduced
   * protocol 4 (paged pulls, batched pushes, box identity). Bump again only when a change makes
   * older peers unsafe, never for a routine release; patch releases above the floor are
   * wire-compatible with each other.
   */
  public static final String UPGRADE_FLOOR = "0.44.0";

  /**
   * The one bound on a framed message, on both ends and in both directions: what {@link
   * #readFramed} accepts, what the parser admits, and what a page or a push batch grows to. 16 MiB
   * because one entry must always fit a page, and the largest entry — a shared file of {@code
   * ProjectFilesCommand.MAX_SHARE_BYTES} — encodes to under 7 MiB.
   */
  public static final int MAX_FRAME = 16 * 1024 * 1024;

  private static final String OP = "op";
  private static final String TYPE = "type";
  private static final String ID = "id";
  private static final String IDS = "ids";
  private static final String REV = "rev";
  private static final String SEQ = "seq";
  private static final String SINCE = "since";
  private static final String LIMIT = "limit";
  private static final String DELETED = "deleted";
  private static final String SNAPSHOT = "snapshot";
  private static final String EXPECTED = "expectedRev";
  private static final String OFFERS = "commits";
  private static final String ENTRIES = "entries";
  private static final String NEXT = "next";
  private static final String DONE = "done";
  private static final String MAX_SEQ = "maxSeq";
  private static final String RESULTS = "results";
  private static final String ACCEPTED = "accepted";
  private static final String STALE = "stale";
  private static final String REFUSED = "refused";
  private static final String REASON = "reason";
  private static final String PROTOCOL_KEY = "protocol";
  private static final String VERSION = "version";
  private static final String FLOOR = "floor";
  private static final String BOX = "box";
  private static final String MAIN_ID = "mainId";
  private static final String TIPS = "tips";
  private static final String FDES = "fdes";
  private static final String MESSAGE = "message";
  private static final String KIND = "kind";
  private static final String LEGACY_ERROR = "error";
  private static final String LEGACY_ERROR_KIND = "error_kind";

  private static final String OP_HELLO = "hello";
  private static final String OP_HEADS = "heads";
  private static final String OP_PULL = "pull";
  private static final String OP_NEED = "need";
  private static final String OP_PUSH = "push";
  private static final String OP_FETCH_FDES = "fetch-fdes";
  private static final String OP_BYE = "bye";
  private static final String OP_WELCOME = "welcome";
  private static final String OP_REFUSE = "refuse";
  private static final String OP_TIPS = "tips";
  private static final String OP_PAGE = "page";
  private static final String OP_RESULTS = "results";
  private static final String OP_FDES = "fdes";
  private static final String OP_FAILED = "failed";

  private SyncWire() {}

  /**
   * Reads one newline-framed message, bounded by {@link #MAX_FRAME}. Returns {@code null} at end of
   * stream (a clean session close), the line without its terminator otherwise. A stream that ends
   * inside a message is a lost channel, never a message: both ends terminate every line, so the
   * fragment is what a dropped connection left behind. Used by both ends so the framing — and its
   * bound — has a single definition.
   */
  public static String readFramed(Reader in) throws IOException {
    return readFramed(in, MAX_FRAME);
  }

  static String readFramed(Reader in, int maxChars) throws IOException {
    var message = new StringBuilder();
    for (var c = in.read(); c != -1; c = in.read()) {
      if (c == '\n') {
        return message.toString();
      }
      if (message.length() >= maxChars) {
        while (c != -1 && c != '\n') {
          c = in.read();
        }
        throw new SyncTransportException("Sync message exceeded " + maxChars + " characters.");
      }
      message.append((char) c);
    }
    if (message.isEmpty()) {
      return null;
    }
    throw new SyncTransportException(
        "unreachable",
        "Sync channel closed mid-message, after " + message.length() + " characters.",
        null);
  }

  public sealed interface Request permits Hello, Heads, Pull, Need, Push, FetchFdes, Bye {}

  /** Opens the session: the node's protocol, build, fleet floor and box identity. */
  public record Hello(int protocol, String version, String floor, String box) implements Request {
    /** This build's hello for {@code box}. */
    public static Hello of(String version, String box) {
      return new Hello(PROTOCOL, version, UPGRADE_FLOOR, box);
    }
  }

  /** Ask main for the high-water of every type, so the node pulls only what moved. */
  public record Heads() implements Request {}

  /** Ask for one page of {@code type}'s change log after {@code since}, at most {@code limit}. */
  public record Pull(String type, long since, int limit) implements Request {}

  /** Ask for main's current rows of the given ids — what the node changed locally. */
  public record Need(String type, List<String> ids) implements Request {}

  /** Push a batch of compare-and-set offers of one type; main answers one result each. */
  public record Push(String type, List<MainReplica.Offer> offers) implements Request {}

  /** Ask main for its FDE roster, which the node mirrors (main-authoritative, one-way). */
  public record FetchFdes() implements Request {}

  /** End the session; main returns nothing. */
  public record Bye() implements Request {}

  public sealed interface Response permits Welcome, Refuse, Tips, Page, Results, Fdes, Failed {}

  /** Main accepted the hello: its protocol, build, and the box id the node checkpoints against. */
  public record Welcome(int protocol, String version, String mainId) implements Response {}

  /**
   * Main refused the session before serving anything, naming the remedy. Encoded with the legacy
   * {@code error}/{@code error_kind} keys beside {@code reason}, because the peer most likely to be
   * refused is a protocol-3 node that opened with a fetch and only reads those keys.
   */
  public record Refuse(String reason) implements Response {}

  /** Main's high-water per entity type. */
  public record Tips(Map<String, Long> tips) implements Response {}

  /** One change of one entity at its head: a tombstone carries {@code deleted} and no snapshot. */
  public record Entry(
      long seq, String id, String rev, boolean deleted, Map<String, Object> snapshot) {}

  /**
   * One page of changes. {@code next} is the highest seq included (for a pull) or the count of
   * requested ids consumed (for a need); {@code done} says whether another page follows; {@code
   * maxSeq} is main's high-water for the type.
   */
  public record Page(List<Entry> entries, long next, boolean done, long maxSeq)
      implements Response {}

  /** Main's verdict on one pushed offer. */
  public sealed interface Result permits Accepted, Stale, Refused {
    String id();
  }

  /** Main minted {@code rev} for the offer. */
  public record Accepted(String id, String rev) implements Result {}

  /**
   * Main moved since the node fetched, or the offer is not the node's to make; the offer was left
   * untouched. Main's present state is deliberately not carried here: a batch of results must fit
   * one frame no matter how large the concurrent versions are, so the node fetches them through the
   * bounded {@link Need} path instead.
   */
  public record Stale(String id) implements Result {}

  /** Main would not take the offer at all — a read-only role, an unattributable run. */
  public record Refused(String id, String reason) implements Result {}

  /** One result per pushed offer, in order, and main's high-water afterwards. */
  public record Results(List<Result> results, long maxSeq) implements Response {}

  /** Main's FDE roster — one map of identity fields per FDE. */
  public record Fdes(List<Map<String, Object>> fdes) implements Response {}

  /** Main could not serve a request: a store failure, a malformed frame, an unknown type. */
  public record Failed(String message, String kind) implements Response {
    public Failed(String message) {
      this(message, "refused");
    }
  }

  /**
   * Grows one frame item by item against the bound, reserving room for the envelope around the
   * items, so a page or a push batch is cut before it could exceed what the other end will read.
   */
  public static final class Frame {
    private static final int ENVELOPE = 256;
    private static final int SEPARATOR = 2;

    private final int bound;
    private int used = ENVELOPE;
    private int items;

    public Frame() {
      this(MAX_FRAME);
    }

    public Frame(int bound) {
      this.bound = bound;
    }

    /** Whether an item of {@code length} chars still fits beside what is already in the frame. */
    public boolean admits(int length) {
      return used + length + SEPARATOR <= bound;
    }

    /** Whether an item of {@code length} chars could ever fit a frame of this bound on its own. */
    public boolean canEverAdmit(int length) {
      return ENVELOPE + length + SEPARATOR <= bound;
    }

    public void add(int length) {
      used += length + SEPARATOR;
      items++;
    }

    public boolean isEmpty() {
      return items == 0;
    }
  }

  /** The chars {@code entry} takes inside a page. */
  public static int encodedLength(Entry entry) {
    return YamlUtil.dumpJson(entryMap(entry)).length();
  }

  /** The chars {@code offer} takes inside a push. */
  public static int encodedLength(MainReplica.Offer offer) {
    return YamlUtil.dumpJson(offerMap(offer)).length();
  }

  public static String context(Request request) {
    return switch (request) {
      case Hello ignored -> OP_HELLO;
      case Heads ignored -> OP_HEADS;
      case Pull pull -> String.valueOf(pull.type());
      case Need need -> String.valueOf(need.type());
      case Push push -> String.valueOf(push.type());
      case FetchFdes ignored -> "fde";
      case Bye ignored -> "session";
    };
  }

  public static String encode(Request request) {
    var map = new LinkedHashMap<String, Object>();
    switch (request) {
      case Hello hello -> {
        map.put(OP, OP_HELLO);
        map.put(PROTOCOL_KEY, hello.protocol());
        map.put(VERSION, hello.version());
        map.put(FLOOR, hello.floor());
        map.put(BOX, hello.box());
      }
      case Heads ignored -> map.put(OP, OP_HEADS);
      case Pull pull -> {
        map.put(OP, OP_PULL);
        map.put(TYPE, pull.type());
        map.put(SINCE, pull.since());
        map.put(LIMIT, pull.limit());
      }
      case Need need -> {
        map.put(OP, OP_NEED);
        map.put(TYPE, need.type());
        map.put(IDS, need.ids());
      }
      case Push push -> {
        map.put(OP, OP_PUSH);
        map.put(TYPE, push.type());
        map.put(OFFERS, push.offers().stream().map(SyncWire::offerMap).toList());
      }
      case FetchFdes ignored -> map.put(OP, OP_FETCH_FDES);
      case Bye ignored -> map.put(OP, OP_BYE);
    }
    return YamlUtil.dumpJson(map);
  }

  public static String encode(Response response) {
    var map = new LinkedHashMap<String, Object>();
    switch (response) {
      case Welcome welcome -> {
        map.put(OP, OP_WELCOME);
        map.put(PROTOCOL_KEY, welcome.protocol());
        map.put(VERSION, welcome.version());
        map.put(MAIN_ID, welcome.mainId());
      }
      case Refuse refuse -> {
        map.put(OP, OP_REFUSE);
        map.put(REASON, refuse.reason());
        map.put(LEGACY_ERROR, refuse.reason());
        map.put(LEGACY_ERROR_KIND, "refused");
      }
      case Tips tips -> {
        map.put(OP, OP_TIPS);
        map.put(TIPS, new LinkedHashMap<String, Object>(tips.tips()));
      }
      case Page page -> {
        map.put(OP, OP_PAGE);
        map.put(ENTRIES, page.entries().stream().map(SyncWire::entryMap).toList());
        map.put(NEXT, page.next());
        map.put(DONE, page.done());
        map.put(MAX_SEQ, page.maxSeq());
      }
      case Results results -> {
        map.put(OP, OP_RESULTS);
        map.put(RESULTS, results.results().stream().map(SyncWire::resultMap).toList());
        map.put(MAX_SEQ, results.maxSeq());
      }
      case Fdes roster -> {
        map.put(OP, OP_FDES);
        map.put(FDES, roster.fdes());
      }
      case Failed failed -> {
        map.put(OP, OP_FAILED);
        map.put(MESSAGE, failed.message());
        map.put(KIND, failed.kind());
      }
    }
    return YamlUtil.dumpJson(map);
  }

  public static Request decodeRequest(String line) {
    var map = YamlUtil.parseJsonLine(line, MAX_FRAME);
    var op = string(map, OP);
    return switch (op) {
      case OP_HELLO ->
          new Hello(
              intValue(map, PROTOCOL_KEY),
              string(map, VERSION),
              string(map, FLOOR),
              string(map, BOX));
      case OP_HEADS -> new Heads();
      case OP_PULL -> new Pull(string(map, TYPE), longValue(map, SINCE), intValue(map, LIMIT));
      case OP_NEED -> new Need(string(map, TYPE), strings(map, IDS));
      case OP_PUSH ->
          new Push(string(map, TYPE), maps(map, OFFERS).stream().map(SyncWire::offer).toList());
      case OP_FETCH_FDES -> new FetchFdes();
      case OP_BYE -> new Bye();
      case null, default -> throw new IllegalArgumentException("Unknown sync op: " + op);
    };
  }

  public static Response decodeResponse(String line) {
    var map = YamlUtil.parseJsonLine(line, MAX_FRAME);
    var op = string(map, OP);
    return switch (op) {
      case OP_WELCOME ->
          new Welcome(intValue(map, PROTOCOL_KEY), string(map, VERSION), string(map, MAIN_ID));
      case OP_REFUSE -> new Refuse(string(map, REASON));
      case OP_TIPS -> new Tips(tips(map));
      case OP_PAGE ->
          new Page(
              maps(map, ENTRIES).stream().map(SyncWire::entry).toList(),
              longValue(map, NEXT),
              bool(map, DONE),
              longValue(map, MAX_SEQ));
      case OP_RESULTS ->
          new Results(
              maps(map, RESULTS).stream().map(SyncWire::result).toList(), longValue(map, MAX_SEQ));
      case OP_FDES -> new Fdes(maps(map, FDES));
      case OP_FAILED -> new Failed(string(map, MESSAGE), string(map, KIND));
      case null, default -> throw new IllegalArgumentException("Unknown sync op: " + op);
    };
  }

  private static Map<String, Object> entryMap(Entry entry) {
    var map = new LinkedHashMap<String, Object>();
    map.put(SEQ, entry.seq());
    map.put(ID, entry.id());
    map.put(REV, entry.rev());
    map.put(DELETED, entry.deleted());
    if (!entry.deleted()) {
      map.put(SNAPSHOT, entry.snapshot());
    }
    return map;
  }

  private static Entry entry(Map<String, Object> map) {
    return new Entry(
        longValue(map, SEQ),
        string(map, ID),
        string(map, REV),
        bool(map, DELETED),
        snapshot(map, SNAPSHOT));
  }

  private static Map<String, Object> offerMap(MainReplica.Offer offer) {
    var map = new LinkedHashMap<String, Object>();
    map.put(ID, offer.id());
    map.put(SNAPSHOT, offer.snapshot());
    map.put(EXPECTED, offer.expectedRev());
    return map;
  }

  private static MainReplica.Offer offer(Map<String, Object> map) {
    return new MainReplica.Offer(string(map, ID), snapshot(map, SNAPSHOT), string(map, EXPECTED));
  }

  private static Map<String, Object> resultMap(Result result) {
    var map = new LinkedHashMap<String, Object>();
    map.put(ID, result.id());
    switch (result) {
      case Accepted accepted -> map.put(ACCEPTED, Map.of(REV, accepted.rev()));
      case Stale _ -> map.put(STALE, true);
      case Refused refused -> map.put(REFUSED, Map.of(REASON, refused.reason()));
    }
    return map;
  }

  private static Result result(Map<String, Object> map) {
    var id = string(map, ID);
    var accepted = snapshot(map, ACCEPTED);
    if (accepted != null) {
      return new Accepted(id, string(accepted, REV));
    }
    if (Boolean.TRUE.equals(map.get(STALE))) {
      return new Stale(id);
    }
    var refused = snapshot(map, REFUSED);
    if (refused != null) {
      return new Refused(id, string(refused, REASON));
    }
    throw new IllegalArgumentException("Result for " + id + " carries no verdict");
  }

  private static Map<String, Long> tips(Map<String, Object> map) {
    var tips = new LinkedHashMap<String, Long>();
    var raw = snapshot(map, TIPS);
    if (raw != null) {
      raw.forEach((type, value) -> tips.put(type, value instanceof Number n ? n.longValue() : 0L));
    }
    return tips;
  }

  @SuppressWarnings("unchecked")
  private static List<Map<String, Object>> maps(Map<String, Object> map, String key) {
    if (!(map.get(key) instanceof List<?> list)) {
      return List.of();
    }
    var maps = new ArrayList<Map<String, Object>>(list.size());
    for (var item : list) {
      if (!(item instanceof Map<?, ?> m)) {
        throw new IllegalArgumentException(key + " must hold objects");
      }
      maps.add((Map<String, Object>) m);
    }
    return maps;
  }

  private static List<String> strings(Map<String, Object> map, String key) {
    return map.get(key) instanceof List<?> list
        ? list.stream().map(String::valueOf).toList()
        : List.of();
  }

  private static String string(Map<String, Object> map, String key) {
    var value = map.get(key);
    return value == null ? null : value.toString();
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> snapshot(Map<String, Object> map, String key) {
    return map.get(key) instanceof Map<?, ?> m ? (Map<String, Object>) m : null;
  }

  private static long longValue(Map<String, Object> map, String key) {
    return map.get(key) instanceof Number n ? n.longValue() : 0L;
  }

  private static int intValue(Map<String, Object> map, String key) {
    return map.get(key) instanceof Number n ? n.intValue() : 0;
  }

  private static boolean bool(Map<String, Object> map, String key) {
    return Boolean.TRUE.equals(map.get(key));
  }
}
