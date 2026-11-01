/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.sync;

import ai.singlr.sail.common.Remedy;
import ai.singlr.sail.config.YamlUtil;
import ai.singlr.sail.identity.ProjectScope;
import ai.singlr.sail.store.BlobStore;
import ai.singlr.sail.store.ChangeLog;
import ai.singlr.sail.store.FastCdc;
import ai.singlr.sail.store.Snapshots;
import ai.singlr.sail.store.SyncState;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.BiFunction;

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
 * types moved past the node's checkpoints, and which no longer hold what the node read at them.
 *
 * <p>A {@code null} snapshot is a deletion — it crosses the wire as an explicit JSON {@code null},
 * distinct from an absent key, so a tombstone is never mistaken for a missing row. Every page entry
 * names its {@code kind} — a revision, a tombstone, or an erasure, which is terminal: the entity is
 * gone everywhere, and a node applies it outside the engine. An erasure is authored only by main; a
 * node asks for one with an offer carrying {@code erase} and no snapshot.
 */
public final class SyncWire {

  /**
   * The protocol this build speaks; a peer announcing another is refused at the hello, so nothing
   * here reads a message another protocol wrote. Bump it with any change to what a message carries.
   */
  public static final int PROTOCOL = 5;

  /** The byte ceiling for a JSON announcing line, including a whole blob manifest. */
  public static final int MAX_FRAME = 16 * 1024 * 1024;

  /**
   * Room for main's answer to an offer beyond the offer itself: a reason, the author main recorded
   * and the markers a result carries. Every reason main gives names at most the offer's own strings
   * besides a short text, and an author is one handle.
   */
  private static final int RESULT_ALLOWANCE = 256;

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
  private static final String BASES = "bases";
  private static final String ACCEPTED_ONLY = "accepted_only";
  private static final String NEXT = "next";
  private static final String DONE = "done";
  private static final String MAX_SEQ = "maxSeq";
  private static final String RESULTS = "results";
  private static final String ACCEPTED = "accepted";
  private static final String STALE = "stale";
  private static final String REFUSED = "refused";
  private static final String REASON = "reason";
  private static final String REMEDY = "remedy";
  private static final String DENIED = "denied";
  private static final String PROTOCOL_KEY = "protocol";
  private static final String VERSION = "version";
  private static final String BOX = "box";
  private static final String MAIN_ID = "mainId";
  private static final String HANDLE = "handle";
  private static final String FILE_MAX = "file_max";
  private static final String SCOPE = "scope";
  private static final String CATALOG = "catalog";
  private static final String TIPS = "tips";
  private static final String FDES = "fdes";
  private static final String MESSAGE = "message";
  private static final String KIND = "kind";
  private static final String ERASE = "erase";
  private static final String AUTHOR = "author";
  private static final String PROJECT = "project";
  private static final String CREATOR = "creator";
  private static final String CREATED_AT = "created_at";
  private static final String GONE = "gone";
  private static final String MARKS = "marks";
  private static final String CHECKPOINT = "checkpoint";
  private static final String REWOUND = "rewound";

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
  public static String readFramed(InputStream in) throws IOException {
    return readLine(in, MAX_FRAME);
  }

  static String readFramed(InputStream in, int maxBytes) throws IOException {
    return readLine(in, maxBytes);
  }

  public static String readLine(InputStream in) throws IOException {
    return readLine(in, MAX_FRAME);
  }

  static String readLine(InputStream in, int maxBytes) throws IOException {
    var message = new ByteArrayOutputStream();
    for (var c = in.read(); c != -1; c = in.read()) {
      if (c == '\n') {
        try {
          return StandardCharsets.UTF_8
              .newDecoder()
              .decode(ByteBuffer.wrap(message.toByteArray()))
              .toString();
        } catch (CharacterCodingException e) {
          throw new SyncTransportException("protocol", "Malformed UTF-8 in sync line", e);
        }
      }
      if (message.size() >= maxBytes) {
        throw new SyncTransportException(
            "protocol", "Sync message exceeded " + maxBytes + " bytes.", null);
      }
      message.write(c);
    }
    if (message.size() == 0) return null;
    throw new SyncTransportException(
        "unreachable",
        "Sync channel closed mid-message, after " + message.size() + " bytes.",
        null);
  }

  public static byte[] readBytes(InputStream in, int size) throws IOException {
    requireChunkSize(size);
    var bytes = new byte[size];
    var offset = 0;
    while (offset < size) {
      var read = in.read(bytes, offset, size - offset);
      if (read == -1)
        throw new SyncTransportException(
            "unreachable",
            "Sync channel closed mid-chunk, after " + offset + " of " + size + " bytes",
            null);
      if (read == 0) throw new IOException("Sync channel made no progress");
      offset += read;
    }
    return bytes;
  }

  public static void writeChunk(OutputStream out, String hash, byte[] bytes) throws IOException {
    requireChunkSize(bytes.length);
    if (!BlobStore.hash(bytes).equals(hash))
      throw new IllegalArgumentException("Invalid chunk " + hash + ": SHA-256 mismatch");
    out.write((encode(new Chunk(hash, bytes.length)) + "\n").getBytes(StandardCharsets.UTF_8));
    out.write(bytes);
    out.flush();
  }

  private static void requireChunkSize(long size) {
    if (size <= 0 || size > FastCdc.MAX)
      throw new IllegalArgumentException(
          "Invalid chunk size: " + size + "; maximum is " + FastCdc.MAX);
  }

  public sealed interface Request permits Hello, Heads, Pull, Need, Push, FetchFdes, Bye, Content {}

  /** Opens the session: the node's protocol, build and box identity. */
  public record Hello(int protocol, String version, String box) implements Request {
    /** This build's hello for {@code box}. */
    public static Hello of(String version, String box) {
      return new Hello(PROTOCOL, version, box);
    }
  }

  /**
   * Ask main for the high-water of every type, so the node pulls only what moved, naming per type
   * the {@code marks} of what the node read at its checkpoint, so main says which its log no longer
   * holds ({@link Tips#rewound}).
   */
  public record Heads(Map<String, SyncState.Mark> marks) implements Request {
    public Heads {
      marks = Map.copyOf(marks);
    }

    /** Heads naming nothing read: a node with no checkpoint yet. */
    public Heads() {
      this(Map.of());
    }
  }

  /** Ask for one page of {@code type}'s change log after {@code since}, at most {@code limit}. */
  public record Pull(String type, long since, int limit) implements Request {}

  /** Ask for main's current rows of the given ids — what the node changed locally. */
  public record Need(String type, List<String> ids, Map<String, String> bases, boolean acceptedOnly)
      implements Request {
    public Need {
      bases = Map.copyOf(bases);
    }

    /** Main's current rows of {@code ids}. */
    public Need(String type, List<String> ids) {
      this(type, ids, Map.of(), false);
    }

    /**
     * Only what main took from this box of each of {@code ids} after the version of it this box
     * last heard from main, {@code heard}: the question a node asks first, before any page.
     */
    public static Need accepted(String type, List<String> ids, Map<String, String> heard) {
      return new Need(type, ids, heard, true);
    }
  }

  /** Push a batch of compare-and-set offers of one type; main answers one result each. */
  public record Push(String type, List<MainReplica.Offer> offers) implements Request {}

  /** Ask main for its FDE roster, which the node mirrors (main-authoritative, one-way). */
  public record FetchFdes() implements Request {}

  /** End the session; main returns nothing. */
  public record Bye() implements Request {}

  public sealed interface Response
      permits Welcome, Refuse, Tips, Page, Results, Fdes, Failed, Content {}

  public sealed interface Content extends Request, Response
      permits Fetch, FetchChunks, Manifest, Chunk, Done, Announce, Lack {}

  public record Fetch(List<String> hashes) implements Content {
    public Fetch {
      hashes = checkedHashes(hashes);
    }
  }

  public record FetchChunks(List<String> hashes) implements Content {
    public FetchChunks {
      hashes = checkedHashes(hashes);
    }
  }

  public record Announce(List<String> hashes) implements Content {
    public Announce {
      hashes = checkedHashes(hashes);
    }
  }

  public record Lack(List<String> hashes) implements Content {
    public Lack {
      hashes = checkedHashes(hashes);
    }
  }

  public record Manifest(BlobStore.Manifest manifest) implements Content {}

  public record Chunk(String hash, int size) implements Content {
    public Chunk {
      BlobStore.requireHash(hash);
      requireChunkSize(size);
    }
  }

  public record Done() implements Content {}

  private static List<String> checkedHashes(List<String> hashes) {
    hashes.forEach(BlobStore::requireHash);
    return List.copyOf(hashes);
  }

  private static List<String> contentHashes(Map<String, Object> map, String key) {
    if (!(map.get(key) instanceof List<?> values)
        || values.stream().anyMatch(value -> !(value instanceof String))) {
      throw new IllegalArgumentException(key + " must be an array of hashes");
    }
    return checkedHashes(values.stream().map(String.class::cast).toList());
  }

  private static long contentSize(Map<String, Object> map) {
    var value = map.get("size");
    if (!(value instanceof Long) && !(value instanceof Integer)) {
      throw new IllegalArgumentException("size must be an integer");
    }
    return ((Number) value).longValue();
  }

  private static Content content(String op, Map<String, Object> map) {
    return switch (op) {
      case "fetch" -> new Fetch(contentHashes(map, "hashes"));
      case "fetch_chunks" -> new FetchChunks(contentHashes(map, "hashes"));
      case "announce" -> new Announce(contentHashes(map, "hashes"));
      case "lack" -> new Lack(contentHashes(map, "hashes"));
      case "manifest" ->
          new Manifest(
              new BlobStore.Manifest(
                  string(map, "hash"), contentSize(map), contentHashes(map, "chunks")));
      case "chunk" -> {
        var size = contentSize(map);
        requireChunkSize(size);
        yield new Chunk(string(map, "hash"), (int) size);
      }
      case "done" -> new Done();
      default -> throw new IllegalArgumentException("Unknown content op: " + op);
    };
  }

  public static String encode(Content content) {
    return YamlUtil.dumpJson(contentMap(content));
  }

  private static Map<String, Object> contentMap(Content content) {
    return switch (content) {
      case Fetch f -> Map.of(OP, "fetch", "hashes", f.hashes());
      case FetchChunks f -> Map.of(OP, "fetch_chunks", "hashes", f.hashes());
      case Announce a -> Map.of(OP, "announce", "hashes", a.hashes());
      case Lack l -> Map.of(OP, "lack", "hashes", l.hashes());
      case Manifest m ->
          Map.of(
              OP,
              "manifest",
              "hash",
              m.manifest().hash(),
              "size",
              m.manifest().size(),
              "chunks",
              m.manifest().chunkHashes());
      case Chunk c -> Map.of(OP, "chunk", "hash", c.hash(), "size", c.size());
      case Done d -> Map.of(OP, "done");
    };
  }

  /**
   * Main accepted the hello: its protocol, build, the box id the node checkpoints against, the
   * {@code handle} it authenticated the session as — blank for a session that names no FDE — its
   * file ceiling, the {@code scope} of projects that FDE may see, every project unless main scoped
   * it, and the {@code catalog} naming main's database ({@code DatabaseCatalog}), which a node
   * refuses a main to name none of ({@code NodeRound#admit}).
   */
  public record Welcome(
      int protocol,
      String version,
      String mainId,
      String handle,
      long fileMax,
      ProjectScope scope,
      String catalog)
      implements Response {
    public Welcome {
      Objects.requireNonNull(handle, "handle");
      Objects.requireNonNull(scope, "scope");
      if (fileMax <= 0) {
        throw new IllegalArgumentException("A welcome names main's file ceiling, not " + fileMax);
      }
    }

    /** A welcome from a main with no catalog to name. */
    public Welcome(
        int protocol,
        String version,
        String mainId,
        String handle,
        long fileMax,
        ProjectScope scope) {
      this(protocol, version, mainId, handle, fileMax, scope, null);
    }

    /** A welcome of an unscoped FDE from a main with no catalog to name. */
    public Welcome(int protocol, String version, String mainId, String handle, long fileMax) {
      this(protocol, version, mainId, handle, fileMax, ProjectScope.all());
    }
  }

  /** Main refused the session before serving anything, naming the remedy as data. */
  public record Refuse(Remedy reason) implements Response {
    public Refuse(String reason) {
      this(Remedy.of(reason));
    }
  }

  /**
   * Main's high-water per entity type, and the types whose mark ({@link Heads#marks}) names an
   * entry main's log no longer holds: main was restored in place from an older copy, so the node
   * walks every head again however far main's log has grown since.
   */
  public record Tips(Map<String, Long> tips, Set<String> rewound) implements Response {
    public Tips {
      rewound = Set.copyOf(rewound);
    }

    /** Tips naming no type rewound. */
    public Tips(Map<String, Long> tips) {
      this(tips, Set.of());
    }
  }

  /**
   * One change of one entity at its head. A tombstone and an erasure carry {@code deleted} and no
   * snapshot; {@code kind} says which, so no reader infers one from the other. Having no snapshot
   * to name its author in, a tombstone or an erasure carries the {@code author} main recorded, so
   * the node records the same one; an older main sends none. A revision's author rides in its
   * snapshot. Every entry carries the {@code project} main's head files it in, and the node files
   * its own head there: a tombstone or an erasure has no snapshot to name one, and a review or a
   * message names none either, so a node left to work it out from the parent it holds could file
   * the row elsewhere than main does and drop, or keep, what main does not. An older main sends
   * none, and a version outside any page names none.
   */
  public record Entry(
      long seq,
      String id,
      String rev,
      boolean deleted,
      Map<String, Object> snapshot,
      ChangeLog.Kind kind,
      String author,
      String project) {
    public Entry {
      if (kind == null) {
        throw new IllegalArgumentException("Entry " + id + " names no kind");
      }
      if (deleted != (kind != ChangeLog.Kind.REVISION)) {
        throw new IllegalArgumentException(
            "Entry " + id + " is a " + kind.wire() + " yet deleted is " + deleted);
      }
    }

    /**
     * Main's version of {@code id} outside any page, as a verdict hands it over: a revision, or a
     * tombstone when {@code snapshot} is null, made by {@code author}.
     */
    public static Entry version(
        String id, String rev, Map<String, Object> snapshot, String author) {
      return new Entry(
          0,
          id,
          rev,
          snapshot == null,
          snapshot,
          snapshot == null ? ChangeLog.Kind.TOMBSTONE : ChangeLog.Kind.REVISION,
          author);
    }

    /** An entry naming no project: a version outside any page, or what an older main sent. */
    public Entry(
        long seq,
        String id,
        String rev,
        boolean deleted,
        Map<String, Object> snapshot,
        ChangeLog.Kind kind,
        String author) {
      this(seq, id, rev, deleted, snapshot, kind, author, null);
    }

    public boolean erased() {
      return kind == ChangeLog.Kind.ERASURE;
    }
  }

  /**
   * One page of changes. {@code next} is the highest seq included (for a pull) or the count of
   * requested ids consumed (for a need); {@code done} says whether another page follows; {@code
   * maxSeq} is main's high-water for the type. A need's page also names, in {@code accepted}, the
   * latest revision main took from the asking box of each id it answers — the node's own offer as
   * main recorded it — so a node whose answer to that offer was lost knows exactly which of its
   * states main holds. Optional: an older main sends none, and a pull's page never does.
   */
  public record Page(
      List<Entry> entries, long next, boolean done, long maxSeq, List<Entry> accepted)
      implements Response {
    public Page {
      accepted = List.copyOf(accepted);
    }

    /**
     * A page naming nothing main took from the asking box: every pull, and an older main's need.
     */
    public Page(List<Entry> entries, long next, boolean done, long maxSeq) {
      this(entries, next, done, maxSeq, List.of());
    }
  }

  /** Main's verdict on one pushed offer. */
  public sealed interface Result permits Accepted, Stale, Refused, Denied {
    String id();
  }

  /**
   * Main minted {@code rev} for the offer, recording {@code author} as who made it: the author the
   * offer named, or the pusher when it named none; {@code creator} as the creator main holds, which
   * may not be the one the offer named, and may be none; and {@code createdAt} as the creation time
   * main holds, null when the entity carries none. The node records the same ones when it settles
   * its offer; an older main sends none of them.
   */
  public record Accepted(
      String id, String rev, String author, Snapshots.Creator creator, String createdAt)
      implements Result {}

  /**
   * Main moved since the node fetched; the offer was left untouched. An offer that is not the
   * node's to make is answered {@link Denied}, never stale. Main's present state is deliberately
   * not carried here: a batch of results must fit one frame no matter how large the concurrent
   * versions are, so the node fetches them through the bounded {@link Need} path instead.
   */
  public record Stale(String id) implements Result {}

  /**
   * Main could not take the offer on integrity grounds — content it does not hold, a pruned id, a
   * run pushed with no node handle. Not a decision about who may write: that is {@link Denied}.
   */
  public record Refused(String id, Remedy reason) implements Result {
    public Refused(String id, String reason) {
      this(id, Remedy.of(reason));
    }
  }

  /**
   * Main decided this principal may not make this change, naming why, and answers with its current
   * version of the entity: a revision, a tombstone ({@code rev} with a null {@code snapshot}, and
   * the {@code author} main recorded for it), or nothing when main holds none. The node adopts it
   * and keeps its own in history. It travels as a {@code refused} result marked {@code denied}, so
   * a node that predates it reads the {@link Refused} it knows, naming the same reason. A version
   * that would take the answer past its offer's {@link #resultBound} is not {@code carried}: the
   * node fetches it through the bounded {@link Need} path, as it does for a {@link Stale} offer.
   */
  public record Denied(
      String id,
      String reason,
      String rev,
      Map<String, Object> snapshot,
      boolean carried,
      String author,
      boolean gone)
      implements Result {
    public Denied(
        String id, String reason, String rev, Map<String, Object> snapshot, boolean carried) {
      this(id, reason, rev, snapshot, carried, null, false);
    }

    public Denied(
        String id,
        String reason,
        String rev,
        Map<String, Object> snapshot,
        boolean carried,
        String author) {
      this(id, reason, rev, snapshot, carried, author, false);
    }

    /** This denial without main's version, which the node then fetches; its mark is kept. */
    public Denied withheld() {
      return new Denied(id, reason, null, null, false, null, gone);
    }
  }

  /** One result per pushed offer, in order, and main's high-water afterwards. */
  public record Results(List<Result> results, long maxSeq) implements Response {}

  /** Main's FDE roster — one map of identity fields per FDE. */
  public record Fdes(List<Map<String, Object>> fdes) implements Response {}

  /**
   * Main could not serve a request: a store failure, a malformed frame, an unknown type. Its {@code
   * detail} keeps any command it names as data; on the wire its plain reading rides beside it, the
   * message a node that predates remedies reads.
   */
  public record Failed(Remedy detail, String kind) implements Response {
    public Failed(String message, String kind) {
      this(Remedy.of(message), kind);
    }

    public Failed(String message) {
      this(message, "refused");
    }

    public String message() {
      return detail.plain();
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

    /** Whether an item of {@code length} bytes still fits beside what is already in the frame. */
    public boolean admits(int length) {
      return used + length + SEPARATOR <= bound;
    }

    /** Whether an item of {@code length} bytes could ever fit a frame of this bound on its own. */
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

  /** The encoded UTF-8 bytes of an identifier, including quotes and escapes. */
  public static int encodedLength(String id) {
    return YamlUtil.dumpJson(List.of(id)).getBytes(StandardCharsets.UTF_8).length - 2;
  }

  public static int inventoryWeight(BlobStore.Manifest manifest) {
    return (manifest.chunkHashes().size() + 1) * (encodedLength(manifest.hash()) + 2);
  }

  /** The bytes {@code entry} takes inside a page. */
  public static int encodedLength(Entry entry) {
    return YamlUtil.dumpJson(entryMap(entry)).getBytes(StandardCharsets.UTF_8).length;
  }

  /** The bytes {@code result} takes inside a batch of results. */
  public static int encodedLength(Result result) {
    return YamlUtil.dumpJson(resultMap(result)).getBytes(StandardCharsets.UTF_8).length;
  }

  /** The bytes {@code offer} takes inside a push. */
  public static int encodedLength(MainReplica.Offer offer) {
    return YamlUtil.dumpJson(offerMap(offer)).getBytes(StandardCharsets.UTF_8).length;
  }

  /**
   * The most main's answer to {@code offer} may take. The node budgets each offer at no less, so
   * the results for a push never outgrow the frame the push fit in.
   */
  public static int resultBound(MainReplica.Offer offer) {
    return encodedLength(offer) + RESULT_ALLOWANCE;
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
      case Content c -> String.valueOf(contentMap(c).get(OP));
    };
  }

  public static String encode(Request request) {
    var map = new LinkedHashMap<String, Object>();
    switch (request) {
      case Hello hello -> {
        map.put(OP, OP_HELLO);
        map.put(PROTOCOL_KEY, hello.protocol());
        map.put(VERSION, hello.version());
        map.put(BOX, hello.box());
      }
      case Heads heads -> {
        map.put(OP, OP_HEADS);
        if (!heads.marks().isEmpty()) {
          var marks = new TreeMap<String, Object>();
          heads.marks().forEach((type, mark) -> marks.put(type, markMap(mark)));
          map.put(MARKS, marks);
        }
      }
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
        if (!need.bases().isEmpty()) {
          map.put(BASES, new LinkedHashMap<String, Object>(need.bases()));
        }
        if (need.acceptedOnly()) {
          map.put(ACCEPTED_ONLY, true);
        }
      }
      case Push push -> {
        map.put(OP, OP_PUSH);
        map.put(TYPE, push.type());
        map.put(OFFERS, push.offers().stream().map(SyncWire::offerMap).toList());
      }
      case FetchFdes ignored -> map.put(OP, OP_FETCH_FDES);
      case Bye ignored -> map.put(OP, OP_BYE);
      case Content c -> map.putAll(contentMap(c));
    }
    return YamlUtil.dumpJson(map);
  }

  public static String encode(Response response) {
    var map = new LinkedHashMap<String, Object>();
    switch (response) {
      case Content c -> map.putAll(contentMap(c));
      case Welcome welcome -> {
        map.put(OP, OP_WELCOME);
        map.put(PROTOCOL_KEY, welcome.protocol());
        map.put(VERSION, welcome.version());
        map.put(MAIN_ID, welcome.mainId());
        map.put(HANDLE, welcome.handle());
        map.put(FILE_MAX, welcome.fileMax());
        if (!welcome.scope().unrestricted()) {
          map.put(SCOPE, List.copyOf(welcome.scope().projects()));
        }
        if (welcome.catalog() != null) {
          map.put(CATALOG, welcome.catalog());
        }
      }
      case Refuse refuse -> {
        map.put(OP, OP_REFUSE);
        map.put(REASON, refuse.reason().plain());
        putRemedy(map, refuse.reason());
      }
      case Tips tips -> {
        map.put(OP, OP_TIPS);
        map.put(TIPS, new LinkedHashMap<String, Object>(tips.tips()));
        if (!tips.rewound().isEmpty()) {
          map.put(REWOUND, tips.rewound().stream().sorted().toList());
        }
      }
      case Page page -> {
        map.put(OP, OP_PAGE);
        map.put(ENTRIES, page.entries().stream().map(SyncWire::entryMap).toList());
        map.put(NEXT, page.next());
        map.put(DONE, page.done());
        map.put(MAX_SEQ, page.maxSeq());
        if (!page.accepted().isEmpty()) {
          map.put(ACCEPTED, page.accepted().stream().map(SyncWire::entryMap).toList());
        }
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
        putRemedy(map, failed.detail());
      }
    }
    return YamlUtil.dumpJson(map);
  }

  public static Request decodeRequest(String line) {
    var map = YamlUtil.parseJsonLine(line, MAX_FRAME);
    var op = string(map, OP);
    return switch (op) {
      case OP_HELLO ->
          new Hello(intValue(map, PROTOCOL_KEY), string(map, VERSION), string(map, BOX));
      case OP_HEADS -> new Heads(marks(map));
      case OP_PULL -> new Pull(string(map, TYPE), longValue(map, SINCE), intValue(map, LIMIT));
      case OP_NEED ->
          new Need(string(map, TYPE), strings(map, IDS), bases(map), bool(map, ACCEPTED_ONLY));
      case OP_PUSH ->
          new Push(string(map, TYPE), maps(map, OFFERS).stream().map(SyncWire::offer).toList());
      case OP_FETCH_FDES -> new FetchFdes();
      case OP_BYE -> new Bye();
      case "fetch", "fetch_chunks", "announce", "lack", "manifest", "chunk", "done" ->
          content(op, map);
      case null, default -> throw new IllegalArgumentException("Unknown sync op: " + op);
    };
  }

  public static Response decodeResponse(String line) {
    var map = YamlUtil.parseJsonLine(line, MAX_FRAME);
    var op = string(map, OP);
    return switch (op) {
      case OP_WELCOME ->
          new Welcome(
              intValue(map, PROTOCOL_KEY),
              string(map, VERSION),
              string(map, MAIN_ID),
              required(map, HANDLE, SyncWire::string),
              required(map, FILE_MAX, SyncWire::longValue),
              map.containsKey(SCOPE) ? ProjectScope.of(strings(map, SCOPE)) : ProjectScope.all(),
              string(map, CATALOG));
      case OP_REFUSE -> new Refuse(remedy(map, REASON));
      case OP_TIPS -> new Tips(tips(map), Set.copyOf(strings(map, REWOUND)));
      case OP_PAGE ->
          new Page(
              maps(map, ENTRIES).stream().map(SyncWire::entry).toList(),
              longValue(map, NEXT),
              bool(map, DONE),
              longValue(map, MAX_SEQ),
              maps(map, ACCEPTED).stream().map(SyncWire::entry).toList());
      case OP_RESULTS ->
          new Results(
              maps(map, RESULTS).stream().map(SyncWire::result).toList(), longValue(map, MAX_SEQ));
      case OP_FDES -> new Fdes(maps(map, FDES));
      case OP_FAILED -> new Failed(remedy(map, MESSAGE), string(map, KIND));
      case "fetch", "fetch_chunks", "announce", "lack", "manifest", "chunk", "done" ->
          content(op, map);
      case null, default -> throw new IllegalArgumentException("Unknown sync op: " + op);
    };
  }

  private static Map<String, Object> entryMap(Entry entry) {
    var map = new LinkedHashMap<String, Object>();
    map.put(SEQ, entry.seq());
    map.put(ID, entry.id());
    map.put(REV, entry.rev());
    map.put(DELETED, entry.deleted());
    map.put(KIND, entry.kind().wire());
    if (entry.project() != null) {
      map.put(PROJECT, entry.project());
    }
    if (!entry.deleted()) {
      map.put(SNAPSHOT, entry.snapshot());
    } else if (entry.author() != null) {
      map.put(AUTHOR, entry.author());
    }
    return map;
  }

  private static Entry entry(Map<String, Object> map) {
    return new Entry(
        longValue(map, SEQ),
        string(map, ID),
        string(map, REV),
        bool(map, DELETED),
        snapshot(map, SNAPSHOT),
        ChangeLog.Kind.of(string(map, KIND)),
        string(map, AUTHOR),
        string(map, PROJECT));
  }

  private static Map<String, Object> offerMap(MainReplica.Offer offer) {
    var map = new LinkedHashMap<String, Object>();
    map.put(ID, offer.id());
    map.put(SNAPSHOT, offer.snapshot());
    map.put(EXPECTED, offer.expectedRev());
    if (offer.erase()) {
      map.put(ERASE, true);
    }
    return map;
  }

  private static MainReplica.Offer offer(Map<String, Object> map) {
    return new MainReplica.Offer(
        string(map, ID), snapshot(map, SNAPSHOT), string(map, EXPECTED), bool(map, ERASE));
  }

  private static Map<String, Object> resultMap(Result result) {
    var map = new LinkedHashMap<String, Object>();
    map.put(ID, result.id());
    switch (result) {
      case Accepted accepted -> {
        var verdict = new LinkedHashMap<String, Object>();
        verdict.put(REV, accepted.rev());
        if (accepted.author() != null) {
          verdict.put(AUTHOR, accepted.author());
        }
        if (accepted.creator() != null) {
          verdict.put(CREATOR, accepted.creator().handle());
        }
        if (accepted.createdAt() != null) {
          verdict.put(CREATED_AT, accepted.createdAt());
        }
        map.put(ACCEPTED, verdict);
      }
      case Stale _ -> map.put(STALE, true);
      case Refused refused -> {
        var verdict = new LinkedHashMap<String, Object>();
        verdict.put(REASON, refused.reason().plain());
        putRemedy(verdict, refused.reason());
        map.put(REFUSED, verdict);
      }
      case Denied denied -> {
        var refused = new LinkedHashMap<String, Object>();
        refused.put(REASON, denied.reason());
        refused.put(DENIED, true);
        if (denied.gone()) {
          refused.put(GONE, true);
        }
        if (denied.carried()) {
          refused.put(REV, denied.rev());
          refused.put(SNAPSHOT, denied.snapshot());
          if (denied.snapshot() == null && denied.author() != null) {
            refused.put(AUTHOR, denied.author());
          }
        }
        map.put(REFUSED, refused);
      }
    }
    return map;
  }

  private static Result result(Map<String, Object> map) {
    var id = string(map, ID);
    var accepted = snapshot(map, ACCEPTED);
    if (accepted != null) {
      var creator =
          accepted.containsKey(CREATOR) ? new Snapshots.Creator(string(accepted, CREATOR)) : null;
      return new Accepted(
          id,
          string(accepted, REV),
          string(accepted, AUTHOR),
          creator,
          string(accepted, CREATED_AT));
    }
    if (Boolean.TRUE.equals(map.get(STALE))) {
      return new Stale(id);
    }
    var refused = snapshot(map, REFUSED);
    if (refused != null && bool(refused, DENIED)) {
      return new Denied(
          id,
          string(refused, REASON),
          string(refused, REV),
          snapshot(refused, SNAPSHOT),
          refused.containsKey(SNAPSHOT),
          string(refused, AUTHOR),
          bool(refused, GONE));
    }
    if (refused != null) {
      return new Refused(id, remedy(refused, REASON));
    }
    throw new IllegalArgumentException("Result for " + id + " carries no verdict");
  }

  private static Map<String, String> bases(Map<String, Object> map) {
    var bases = new LinkedHashMap<String, String>();
    var raw = snapshot(map, BASES);
    if (raw != null) {
      raw.forEach((id, rev) -> bases.put(id, rev instanceof String text ? text : null));
    }
    bases.values().removeIf(Objects::isNull);
    return bases;
  }

  private static Map<String, Long> tips(Map<String, Object> map) {
    var tips = new LinkedHashMap<String, Long>();
    var raw = snapshot(map, TIPS);
    if (raw != null) {
      raw.forEach((type, value) -> tips.put(type, value instanceof Number n ? n.longValue() : 0L));
    }
    return tips;
  }

  private static Map<String, Object> markMap(SyncState.Mark mark) {
    var map = new LinkedHashMap<String, Object>();
    map.put(CHECKPOINT, mark.checkpoint());
    map.put(SEQ, mark.seq());
    map.put(ID, mark.id());
    map.put(REV, mark.rev());
    return map;
  }

  @SuppressWarnings("unchecked")
  private static Map<String, SyncState.Mark> marks(Map<String, Object> map) {
    var marks = new LinkedHashMap<String, SyncState.Mark>();
    var raw = snapshot(map, MARKS);
    if (raw != null) {
      raw.forEach(
          (type, value) -> {
            if (!(value instanceof Map<?, ?> mark)) {
              throw new IllegalArgumentException(MARKS + " must hold objects");
            }
            var fields = (Map<String, Object>) mark;
            marks.put(
                type,
                new SyncState.Mark(
                    longValue(fields, CHECKPOINT),
                    longValue(fields, SEQ),
                    string(fields, ID),
                    string(fields, REV)));
          });
    }
    return marks;
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

  /** {@code key} as {@code read} reads it, refusing a message that leaves it out. */
  private static <T> T required(
      Map<String, Object> map, String key, BiFunction<Map<String, Object>, String, T> read) {
    if (map.get(key) == null) {
      throw new IllegalArgumentException(map.get(OP) + " names no " + key);
    }
    return read.apply(map, key);
  }

  private static String string(Map<String, Object> map, String key) {
    var value = map.get(key);
    return value == null ? null : value.toString();
  }

  private static void putRemedy(Map<String, Object> map, Remedy remedy) {
    if (!remedy.commands().isEmpty()) {
      map.put(REMEDY, remedy.toMaps());
    }
  }

  /** The remedy a frame carries, or its plain {@code key} as words when it carries none. */
  private static Remedy remedy(Map<String, Object> map, String key) {
    return map.get(REMEDY) instanceof List<?> remedy
        ? Remedy.fromMaps(remedy)
        : Remedy.of(Objects.toString(string(map, key), ""));
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
