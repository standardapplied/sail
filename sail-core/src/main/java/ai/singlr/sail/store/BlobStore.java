/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */
package ai.singlr.sail.store;

import ai.singlr.sail.config.YamlUtil;
import ai.singlr.sail.sync.SyncWire;
import ai.singlr.sail.sync.SyncedEntities;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Verified, content-addressed blobs and deduplicated chunks, streamed through SQLite. */
public final class BlobStore {
  public static final long MAX_SIZE = 8L * 1024 * 1024 * 1024;
  private static final Map<Path, BlobRetention> RETENTION = new HashMap<>();
  private final Sqlite db;
  private final BlobRetention retention;

  public BlobStore(Sqlite db) {
    this.db = Objects.requireNonNull(db, "db");
    synchronized (RETENTION) {
      retention =
          db.path() == null
              ? db.contentRetention
              : RETENTION.computeIfAbsent(db.path(), BlobRetention::new);
    }
  }

  /**
   * Shares retention across rounds and ingests while excluding collection, across processes. A
   * waiting collector does not block new transfers from joining the active shared leases. Inside a
   * write transaction the lease is not needed: the database's write lock already keeps a collector
   * in any process from committing between a chunk and the row that references it. A read
   * transaction holds no such lock, so it leases like any caller outside one.
   */
  public Scope retain() {
    if (db.holdsWriteLock()) return () -> {};
    return retention.acquireShared();
  }

  @FunctionalInterface
  public interface Scope extends AutoCloseable {
    @Override
    void close();
  }

  public static final class NotHeld extends IllegalStateException {
    public NotHeld(String hash) {
      super("blob " + hash + " not held");
    }
  }

  public record Manifest(String hash, long size, List<String> chunkHashes) {
    public Manifest {
      requireHash(hash);
      chunkHashes = List.copyOf(chunkHashes);
      chunkHashes.forEach(BlobStore::requireHash);
      if (size < 0 || size > MAX_SIZE || chunkHashes.size() > MAX_SIZE / FastCdc.MIN + 1) {
        throw new IllegalArgumentException(
            "Invalid blob manifest size: " + hash + " (" + size + ")");
      }
      if ((size == 0) != chunkHashes.isEmpty()
          || size > (long) chunkHashes.size() * FastCdc.MAX
          || size > 0 && size <= (long) (chunkHashes.size() - 1) * FastCdc.MIN) {
        throw new IllegalArgumentException("Invalid blob manifest chunks: " + hash);
      }
    }
  }

  public String put(InputStream input) {
    try (var scope = retain()) {
      return ingest(input);
    }
  }

  private String ingest(InputStream input) {
    var digest = digest();
    var chunks = new ArrayList<String>();
    var buffer = new byte[FastCdc.MAX];
    var held = 0;
    var size = 0L;
    try {
      var eof = false;
      while (!eof || held > 0) {
        while (!eof && held < buffer.length) {
          var read = input.read(buffer, held, buffer.length - held);
          if (read == -1) eof = true;
          else if (read > 0) held += read;
          else throw new IOException("Content stream made no progress");
        }
        if (held == 0) break;
        var length = FastCdc.cut(buffer, held);
        size += length;
        if (size > MAX_SIZE)
          throw new IllegalArgumentException("Blob exceeds " + MAX_SIZE + " bytes");
        var bytes = Arrays.copyOf(buffer, length);
        digest.update(bytes);
        var hash = hash(bytes);
        putChunk(hash, bytes);
        chunks.add(hash);
        held -= length;
        System.arraycopy(buffer, length, buffer, 0, held);
      }
    } catch (IOException e) {
      throw new UncheckedIOException("Cannot read blob content", e);
    }
    var hash = hex(digest);
    publish(new Manifest(hash, size, chunks));
    return hash;
  }

  public InputStream open(String hash) {
    var manifest = manifest(hash);
    return new InputStream() {
      private int index;
      private ByteArrayInputStream current = new ByteArrayInputStream(new byte[0]);
      private boolean closed;

      private boolean advance() throws IOException {
        if (closed) throw new IOException("Blob stream is closed");
        while (current.available() == 0 && index < manifest.chunkHashes().size()) {
          current = new ByteArrayInputStream(chunk(manifest.chunkHashes().get(index++)));
        }
        return current.available() > 0;
      }

      @Override
      public int read() throws IOException {
        return advance() ? current.read() : -1;
      }

      @Override
      public int read(byte[] bytes, int offset, int length) throws IOException {
        Objects.checkFromIndexSize(offset, length, bytes.length);
        if (length == 0) return 0;
        return advance() ? current.read(bytes, offset, length) : -1;
      }

      @Override
      public void close() {
        closed = true;
        current = new ByteArrayInputStream(new byte[0]);
      }
    };
  }

  public String putText(String text) {
    return put(
        new ByteArrayInputStream(Objects.toString(text, "").getBytes(StandardCharsets.UTF_8)));
  }

  public String text(String hash) {
    try (var input = open(hash);
        var output = new ByteArrayOutputStream()) {
      input.transferTo(output);
      return output.toString(StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new UncheckedIOException("Cannot read blob " + hash, e);
    }
  }

  public void requireHeld(String hash) {
    if (!has(hash)) throw new NotHeld(hash);
  }

  public boolean has(String hash) {
    requireHash(hash);
    return db.queryOne("SELECT 1 FROM blobs WHERE hash = ?", r -> r.integer(0), hash).isPresent();
  }

  public Set<String> missing(Collection<String> hashes) {
    var missing = new LinkedHashSet<String>();
    for (var hash : hashes) if (!has(hash)) missing.add(hash);
    return missing;
  }

  public Set<String> missingChunks(Collection<String> hashes) {
    var missing = new LinkedHashSet<String>();
    for (var hash : hashes) {
      requireHash(hash);
      if (db.queryOne("SELECT 1 FROM chunks WHERE hash = ?", r -> r.integer(0), hash).isEmpty()) {
        missing.add(hash);
      }
    }
    return missing;
  }

  public Manifest manifest(String hash) {
    requireHash(hash);
    return db.queryOne(
            "SELECT size, chunks FROM blobs WHERE hash = ?",
            r -> new Manifest(hash, r.integer(0), chunkHashes(r.text(1))),
            hash)
        .orElseThrow(() -> new NotHeld(hash));
  }

  private static List<String> chunkHashes(String json) {
    var map = YamlUtil.parseJsonLine("{\"chunks\":" + json + "}", SyncWire.MAX_FRAME);
    return ((List<?>) map.get("chunks")).stream().map(Object::toString).toList();
  }

  public byte[] chunk(String hash) {
    requireHash(hash);
    return db.queryOne("SELECT bytes FROM chunks WHERE hash = ?", r -> r.bytes(0), hash)
        .orElseThrow(() -> new IllegalStateException("chunk " + hash + " not held"));
  }

  public void putChunk(String hash, byte[] bytes) {
    requireHash(hash);
    if (bytes.length == 0 || bytes.length > FastCdc.MAX || !hash(bytes).equals(hash)) {
      throw new IllegalArgumentException("Invalid chunk " + hash + ": size or SHA-256 mismatch");
    }
    db.transaction(
        () ->
            db.execute(
                "INSERT OR IGNORE INTO chunks (hash, size, bytes) VALUES (?, ?, ?)",
                hash,
                bytes.length,
                bytes));
  }

  public void assemble(Manifest manifest) {
    try (var scope = retain()) {
      verifyAndPublish(manifest);
    }
  }

  private void verifyAndPublish(Manifest manifest) {
    var digest = digest();
    var size = 0L;
    for (var hash : manifest.chunkHashes()) {
      var bytes = chunk(hash);
      size += bytes.length;
      digest.update(bytes);
    }
    if (size != manifest.size() || !hex(digest).equals(manifest.hash())) {
      throw new IllegalArgumentException(
          "Invalid blob " + manifest.hash() + ": size or SHA-256 mismatch");
    }
    publish(manifest);
  }

  private void publish(Manifest manifest) {
    db.transaction(
        () ->
            db.execute(
                "INSERT OR IGNORE INTO blobs (hash, size, chunks, created_at) VALUES (?, ?, ?, datetime('now'))",
                manifest.hash(),
                manifest.size(),
                YamlUtil.dumpJson(manifest.chunkHashes())));
  }

  public long gc(Set<String> referenced) {
    try (var scope = retention.acquireExclusive()) {
      return collect(referenced);
    }
  }

  private long collect(Set<String> referenced) {
    return db.transaction(
        () -> {
          var retained = new LinkedHashSet<>(referenced);
          retained.addAll(references());
          var liveChunks = new LinkedHashSet<String>();
          for (var hash : db.query("SELECT hash FROM blobs", r -> r.text(0))) {
            if (retained.contains(hash)) liveChunks.addAll(manifest(hash).chunkHashes());
            else db.execute("DELETE FROM blobs WHERE hash = ?", hash);
          }
          var freed = 0L;
          for (var chunk :
              db.query(
                  "SELECT hash, size FROM chunks", r -> new ChunkSize(r.text(0), r.integer(1)))) {
            if (!liveChunks.contains(chunk.hash())) {
              db.execute("DELETE FROM chunks WHERE hash = ?", chunk.hash());
              freed += chunk.size();
            }
          }
          return freed;
        });
  }

  /**
   * Every hash something on this box still needs: each synced store's live rows, every history row
   * still on disk, and all three sides of every open conflict. The store knows no table by name — a
   * store that carries content declares it through {@link SyncedStore#contentFields} and {@link
   * SyncedStore#liveContentHashes}, and a new kind of content joins by implementing both.
   */
  public Set<String> references() {
    var references = new LinkedHashSet<String>();
    for (var entity : SyncedEntities.all()) {
      var store = entity.store(db);
      var fields = store.contentFields();
      if (fields.isEmpty()) continue;
      references.addAll(store.liveContentHashes());
      var after = 0L;
      while (true) {
        var entries =
            db.query(
                "SELECT seq, snapshot FROM change_log WHERE entity_type = ? AND seq > ? ORDER BY seq LIMIT 100",
                row -> new History(row.integer(0), row.text(1)),
                entity.type(),
                after);
        if (entries.isEmpty()) break;
        for (var entry : entries)
          addReferences(references, YamlUtil.parseMap(entry.snapshot()), fields);
        after = entries.getLast().seq();
      }
      for (var snapshot :
          db.query(
              "SELECT base_snapshot, local_snapshot, remote_snapshot FROM sync_conflicts WHERE entity_type = ? AND status = 'pending'",
              row -> Arrays.asList(row.text(0), row.text(1), row.text(2)),
              entity.type())) {
        for (var side : snapshot) addReferences(references, YamlUtil.parseMap(side), fields);
      }
    }
    return references;
  }

  private record History(long seq, String snapshot) {}

  public static Set<String> referenced(
      Collection<Map<String, Object>> snapshots, Set<String> fields) {
    var hashes = new LinkedHashSet<String>();
    for (var snapshot : snapshots) addReferences(hashes, snapshot, fields);
    return hashes;
  }

  private static void addReferences(
      Set<String> hashes, Map<String, Object> snapshot, Set<String> fields) {
    if (snapshot == null) return;
    for (var field : fields) {
      var hash = snapshot.get(field);
      if (hash != null) {
        requireHash(hash.toString());
        hashes.add(hash.toString());
      }
    }
  }

  private record ChunkSize(String hash, long size) {}

  public static String hash(byte[] bytes) {
    return HexFormat.of().formatHex(digest().digest(bytes));
  }

  public static String hash(InputStream input) {
    var digest = digest();
    var buffer = new byte[64 * 1024];
    try {
      for (var read = input.read(buffer); read != -1; read = input.read(buffer)) {
        digest.update(buffer, 0, read);
      }
      return hex(digest);
    } catch (IOException e) {
      throw new UncheckedIOException("Cannot hash content", e);
    }
  }

  private static String hex(MessageDigest digest) {
    return HexFormat.of().formatHex(digest.digest());
  }

  private static MessageDigest digest() {
    try {
      return MessageDigest.getInstance("SHA-256");
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 is unavailable", e);
    }
  }

  public static void requireHash(String hash) {
    if (hash == null || !hash.matches("[0-9a-f]{64}")) {
      throw new IllegalArgumentException("Invalid content hash: " + hash);
    }
  }
}
