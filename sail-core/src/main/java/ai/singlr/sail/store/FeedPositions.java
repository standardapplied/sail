/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.store;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;

/**
 * The positions a client of the change feed holds: {@code <catalog>.<seq>.<fingerprint>}, naming
 * this database by its catalog id, a {@code change_log.seq}, and the first 6 bytes of a SHA-256
 * over the type, id and rev of the entry at that seq, as the log holds it or compaction kept it. A
 * position is opaque to the client, which hands it back as it came. One from another database, past
 * the newest seq, or whose entry is no longer the one at its seq (a database restored from an older
 * copy and grown again; an entry erased since) is not this database's to answer from.
 */
public final class FeedPositions {

  /** What a position names when nothing stood at its seq as it was handed out. */
  private static final String NOTHING = "0";

  private final Sqlite db;
  private final ChangeLog log;

  public FeedPositions(Sqlite db) {
    this.db = Objects.requireNonNull(db, "db");
    this.log = new ChangeLog(db);
  }

  /** This database's catalog id: what a position names it by. */
  public String catalog() {
    return new DatabaseCatalog(db).id();
  }

  /** The position of {@code seq} in the database {@code catalog} names. */
  public String at(String catalog, long seq) {
    return catalog + "." + seq + "." + fingerprintAt(seq).orElse(NOTHING);
  }

  /** The newest position of this database ({@link ChangeLog#newest}). */
  public String newest(String catalog) {
    return at(catalog, log.newest());
  }

  /** The position of {@code change}, read from what the change itself names. */
  public static String of(String catalog, ChangeLog.Change change) {
    return catalog
        + "."
        + change.seq()
        + "."
        + fingerprint(change.entityType(), change.entityId(), change.rev());
  }

  /**
   * The seq {@code position} stands on in the database {@code catalog} names, when it is one of its
   * positions and the entry it names is still the one at that seq; empty for anything else, a bare
   * number included. A position on which nothing stands answers only where nothing ever stood to be
   * named: before the first entry, or at the newest seq when its entry is gone.
   */
  public OptionalLong seqOf(String catalog, String position) {
    var parts = position == null ? new String[0] : position.split("\\.", -1);
    if (parts.length != 3 || !parts[0].equals(catalog) || !parts[1].matches("[0-9]{1,18}")) {
      return OptionalLong.empty();
    }
    var seq = Long.parseLong(parts[1]);
    var newest = log.newest();
    if (seq > newest) {
      return OptionalLong.empty();
    }
    var holds =
        fingerprintAt(seq)
            .map(parts[2]::equals)
            .orElseGet(() -> parts[2].equals(NOTHING) && (seq == 0 || seq == newest));
    return holds ? OptionalLong.of(seq) : OptionalLong.empty();
  }

  /**
   * The seq of {@code position}, one this database handed out itself and the caller kept, as a
   * stream keeps its own cursor: of this catalog and no later than the newest seq, its entry not
   * asked after, since one erased after it was handed out leaves the history after it whole.
   */
  public OptionalLong seqOfMinted(String catalog, String position) {
    var parts = position.split("\\.", -1);
    if (parts.length != 3 || !parts[0].equals(catalog) || !parts[1].matches("[0-9]{1,18}")) {
      return OptionalLong.empty();
    }
    var seq = Long.parseLong(parts[1]);
    return seq <= log.newest() ? OptionalLong.of(seq) : OptionalLong.empty();
  }

  /** The fingerprint of the entry at {@code seq}, as the log holds it or compaction kept it. */
  private Optional<String> fingerprintAt(long seq) {
    return db.queryOne(
        """
        SELECT entity_type, entity_id, rev FROM change_log WHERE seq = ?1
        UNION ALL SELECT entity_type, entity_id, rev FROM compacted_entries WHERE seq = ?1
        LIMIT 1""",
        row -> fingerprint(row.text(0), row.text(1), row.text(2)),
        seq);
  }

  private static String fingerprint(String type, String id, String rev) {
    try {
      var digest =
          MessageDigest.getInstance("SHA-256")
              .digest((type + "\u0000" + id + "\u0000" + rev).getBytes(StandardCharsets.UTF_8));
      return HexFormat.of().formatHex(digest, 0, 6);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 is part of every Java platform", e);
    }
  }
}
