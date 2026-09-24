/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.engine;

import ai.singlr.sail.api.ApiException;
import ai.singlr.sail.api.ErrorCode;
import ai.singlr.sail.api.Resolution;
import ai.singlr.sail.common.Strings;
import ai.singlr.sail.config.YamlUtil;
import ai.singlr.sail.store.BlobStore;
import ai.singlr.sail.store.ConflictDetector;
import ai.singlr.sail.store.Sqlite;
import ai.singlr.sail.store.SyncConflicts;
import ai.singlr.sail.sync.ConflictMerge;
import ai.singlr.sail.sync.SyncedEntities;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public final class ConflictOperations {
  private static final int FINGERPRINT_LENGTH = 16;

  private final Sqlite db;
  private final SyncConflicts conflicts;

  public ConflictOperations(Sqlite db) {
    this.db = db;
    this.conflicts = new SyncConflicts(db);
  }

  public List<SyncConflicts.Conflict> list() {
    return conflicts.pending().stream().map(this::display).toList();
  }

  /**
   * The open conflict on {@code entityId}, or {@code null} when there is none. Ids are unique only
   * within a type — a spec's room carries the spec's id — so a blank {@code entityType} is accepted
   * only while the id names a single conflict; several are refused by name, never guessed.
   */
  public SyncConflicts.Conflict find(String entityType, String entityId) {
    var raw = findRaw(entityType, entityId);
    return raw == null ? null : display(raw);
  }

  private SyncConflicts.Conflict findRaw(String entityType, String entityId) {
    if (Strings.isNotBlank(entityType)) {
      return conflicts.pendingFor(entityType, entityId).orElse(null);
    }
    var matches = conflicts.pending().stream().filter(c -> c.entityId().equals(entityId)).toList();
    if (matches.size() > 1) {
      throw new ApiException(
          ErrorCode.BAD_REQUEST,
          "'%s' has open conflicts as %s: pass --type"
              .formatted(
                  entityId,
                  matches.stream()
                      .map(SyncConflicts.Conflict::entityType)
                      .collect(Collectors.joining(" and "))));
    }
    return matches.isEmpty() ? null : matches.getFirst();
  }

  /**
   * The editable record a {@code MERGE} resolution starts from: the three-way merge of the conflict
   * on {@code entityId}, bound to the version it was made from. A stale or unmergeable conflict is
   * refused here, before anyone spends effort merging it.
   */
  public String mergeTemplate(String entityType, String entityId) {
    var conflict = requireOpen(entityType, entityId);
    requireMergeable(conflict);
    requireCurrent(conflict);
    var shown = display(conflict);
    return ConflictMerge.mergeTemplate(
        parse(shown.baseSnapshot()),
        parse(shown.localSnapshot()),
        parse(shown.remoteSnapshot()),
        shown.fields(),
        fingerprint(conflict));
  }

  /**
   * Settles a conflict on the side the engineer chose. The decision was made looking at the
   * recorded snapshots, so it applies only while the row still is what was shown: a local write
   * since then refuses the resolve untouched, whatever the strategy, until a round re-records it. A
   * merge is a full record, so it applies only to the version of the conflict its template was made
   * from: once a round re-records the conflict with main's news, it would revert that news.
   */
  public SyncConflicts.Conflict resolve(String entityType, String entityId, Resolution resolution) {
    return db.transaction(
        () -> {
          var conflict = requireOpen(entityType, entityId);
          requireCurrent(conflict);
          var chosen =
              switch (resolution.strategy()) {
                case MINE -> parse(conflict.localSnapshot());
                case THEIRS -> parse(conflict.remoteSnapshot());
                case MERGE -> {
                  requireMergeable(conflict);
                  var merged =
                      new LinkedHashMap<>(ConflictMerge.parseTemplate(resolution.merged()));
                  requireMadeFrom(conflict, merged.remove(ConflictMerge.CONFLICT));
                  var blobs = new BlobStore(db);
                  for (var field :
                      SyncedEntities.require(conflict.entityType()).store(db).contentFields()) {
                    var text = merged.remove(displayField(field));
                    if (!(text instanceof String))
                      throw new IllegalArgumentException(
                          "Merged " + displayField(field) + " must be text");
                    merged.put(field, blobs.putText((String) text));
                  }
                  yield merged;
                }
              };
          var rev =
              SyncedEntities.require(conflict.entityType())
                  .resolver(db)
                  .resolveConflict(conflict.entityId(), chosen, parse(conflict.remoteSnapshot()));
          conflicts.resolve(conflict.id(), rev);
          return display(
              new SyncConflicts.Conflict(
                  conflict.id(),
                  conflict.entityType(),
                  conflict.entityId(),
                  conflict.baseSnapshot(),
                  conflict.localSnapshot(),
                  conflict.remoteSnapshot(),
                  conflict.fields(),
                  conflict.detectedAt(),
                  "resolved",
                  rev));
        });
  }

  private SyncConflicts.Conflict requireOpen(String entityType, String entityId) {
    var conflict = findRaw(entityType, entityId);
    if (conflict == null) {
      throw new IllegalArgumentException("No open conflict for '" + entityId + "'.");
    }
    return conflict;
  }

  /**
   * A field-level merge needs both sides present and a structure with mergeable fields, so it is
   * offered only for spec conflicts that are not delete-vs-edit. A file's content is an opaque blob
   * and a project's definition is a single descriptor field, so both resolve mine or theirs only.
   */
  private static void requireMergeable(SyncConflicts.Conflict conflict) {
    if (!conflict.entityType().equals("spec")
        || parse(conflict.baseSnapshot()) == null
        || parse(conflict.localSnapshot()) == null
        || parse(conflict.remoteSnapshot()) == null) {
      throw new IllegalArgumentException(
          "Field-level --merge isn't available for this conflict; use --mine or --theirs.");
    }
  }

  private static void requireMadeFrom(SyncConflicts.Conflict conflict, Object madeFrom) {
    if (!(madeFrom instanceof String named) || named.isBlank()) {
      throw new ApiException(
          ErrorCode.BAD_REQUEST,
          "A merged record must start from 'sail conflicts show %s --template'."
              .formatted(conflict.entityId()));
    }
    if (!named.equals(fingerprint(conflict))) {
      throw new ApiException(
          ErrorCode.CONFLICT,
          "'%s' was re-recorded after this merge was started. Start again from the fresh version."
              .formatted(conflict.entityId()));
    }
  }

  /**
   * Names the recorded versions of a conflict. Every round re-records every parked conflict under a
   * new row id, so the name is taken from the raw snapshots instead: a re-record that brings no
   * news keeps it, one that does changes it.
   */
  private static String fingerprint(SyncConflicts.Conflict conflict) {
    var snapshots =
        Stream.of(conflict.baseSnapshot(), conflict.localSnapshot(), conflict.remoteSnapshot())
            .map(snapshot -> (Object) new TreeMap<>(parse(snapshot)))
            .toList();
    return BlobStore.hash(YamlUtil.dumpJson(snapshots).getBytes(StandardCharsets.UTF_8))
        .substring(0, FINGERPRINT_LENGTH);
  }

  private void requireCurrent(SyncConflicts.Conflict conflict) {
    var store = SyncedEntities.require(conflict.entityType()).store(db);
    var drift =
        ConflictDetector.drift(
            parse(conflict.localSnapshot()),
            store.currentForSync(conflict.entityId()),
            store.latestWinsFields());
    if (!drift.isEmpty()) {
      throw new ApiException(
          ErrorCode.CONFLICT,
          """
          '%s' changed on this box after this conflict was recorded (%s).
          Run 'sail sync' to refresh it, then resolve."""
              .formatted(
                  conflict.entityId(),
                  drift.stream()
                      .map(ConflictOperations::displayField)
                      .collect(Collectors.joining(", "))));
    }
  }

  private SyncConflicts.Conflict display(SyncConflicts.Conflict conflict) {
    var fields = SyncedEntities.require(conflict.entityType()).store(db).contentFields();
    return new SyncConflicts.Conflict(
        conflict.id(),
        conflict.entityType(),
        conflict.entityId(),
        displaySnapshot(conflict.baseSnapshot(), fields),
        displaySnapshot(conflict.localSnapshot(), fields),
        displaySnapshot(conflict.remoteSnapshot(), fields),
        conflict.fields().stream().map(ConflictOperations::displayField).toList(),
        conflict.detectedAt(),
        conflict.status(),
        conflict.resolvedRev());
  }

  private String displaySnapshot(String json, java.util.Set<String> fields) {
    if (json == null) return null;
    var snapshot = new LinkedHashMap<>(parse(json));
    var blobs = new BlobStore(db);
    for (var field : fields) {
      var hash = snapshot.remove(field);
      if (hash == null) continue;
      var value = hash.toString();
      var text =
          "binary".equals(snapshot.get("kind"))
              ? "<binary, " + blobs.manifest(value).size() + " bytes>"
              : field.equals("content_hash") ? preview(blobs, value) : blobs.text(value);
      snapshot.put(
          displayField(field), field.equals("content_hash") ? text + "\nSHA-256: " + value : text);
    }
    return YamlUtil.dumpJson(snapshot);
  }

  private static String preview(BlobStore blobs, String hash) {
    var size = blobs.manifest(hash).size();
    var buffer = new byte[(int) Math.min(size, 64 * 1024)];
    try (var input = blobs.open(hash)) {
      var offset = 0;
      while (offset < buffer.length) {
        var read = input.read(buffer, offset, buffer.length - offset);
        if (read == -1) break;
        offset += read;
      }
      return new String(buffer, 0, offset, StandardCharsets.UTF_8)
          + (size > buffer.length ? "\n… (preview; " + size + " bytes total)" : "");
    } catch (java.io.IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private static String displayField(String field) {
    return switch (field) {
      case "body_hash" -> "body";
      case "plan_hash" -> "plan";
      case "content_hash" -> "content";
      default -> field;
    };
  }

  public static Map<String, Object> parse(String snapshot) {
    return snapshot == null || snapshot.isBlank() ? null : YamlUtil.parseMap(snapshot);
  }
}
