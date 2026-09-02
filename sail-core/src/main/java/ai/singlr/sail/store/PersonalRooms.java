/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.store;

import ai.singlr.sail.config.Engagement;
import ai.singlr.sail.config.EngagementMode;
import ai.singlr.sail.config.Roster;
import ai.singlr.sail.config.SailYaml;
import ai.singlr.sail.config.YamlUtil;
import ai.singlr.sail.engine.NameValidator;
import java.util.Locale;

/**
 * The FDE's personal room in a project — the new-FDE on-ramp: one per FDE per project, titled by
 * the handle, assigned to the FDE, with the project's default agent seated as its first member and
 * no explicit wake mode (so it answers a plain message, the derived default for a solo roster).
 *
 * <p>Minted lazily and idempotently: every field derives from the FDE row and the project row,
 * timestamps included, so two boxes minting the same room write byte-identical LOCAL revisions with
 * identical content-hash revs and converge as a no-op on their first sync — the rooms backfill
 * precedent. A room the FDE deleted stays deleted: the journal's tombstone refuses the re-mint
 * until the FDE recreates it explicitly.
 */
public final class PersonalRooms {

  private static final String PREFIX = "fde-";
  private static final int FINGERPRINT_LENGTH = 16;
  private static final int MAX_SLUG_LENGTH =
      NameValidator.MAX_SPEC_ID_LENGTH - PREFIX.length() - 1 - FINGERPRINT_LENGTH;

  private PersonalRooms() {}

  /**
   * The deterministic room id for {@code handle} in {@code project}, valid in the shared id space:
   * a readable slug of the pair, then a fingerprint of the exact pair. The fingerprint is what
   * keeps the id injective — the slug lower-cases and folds punctuation, so {@code M.Day} and
   * {@code m-day} share one, and a hyphenated handle can shift the handle/project boundary — and
   * what keeps the truncated slug of a long pair unique within the id length.
   */
  public static String idOf(String handle, String project) {
    NameValidator.requireValidFdeHandle(handle);
    NameValidator.requireValidProjectName(project);
    return derive(handle, project);
  }

  private static String derive(String handle, String project) {
    var slug = handle.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9-]", "-") + "-" + project;
    var fingerprint = TokenStore.sha256(handle + "\0" + project).substring(0, FINGERPRINT_LENGTH);
    return PREFIX + slug.substring(0, Math.min(slug.length(), MAX_SLUG_LENGTH)) + "-" + fingerprint;
  }

  /**
   * The handle whose personal room {@code row} is, or null for any other room. Derived from the row
   * alone — a personal room is minted by its FDE, so the id rule over the creator and project
   * identifies it — and rendered on the wire so Mast pins the reader's room without re-deriving the
   * id. Never throws: a creator that is no FDE handle (an agent principal) simply derives no match.
   */
  public static String ownerOf(RoomStore.RoomRow row) {
    if (row.createdBy() == null || !row.id().startsWith(PREFIX)) {
      return null;
    }
    return row.id().equals(derive(row.createdBy(), row.project())) ? row.createdBy() : null;
  }

  /**
   * Mints {@code fde}'s personal room in {@code project} unless it exists, was deleted, or the id
   * is already a spec's. Returns whether a room was minted.
   */
  public static boolean ensure(
      RoomStore rooms, SpecStore specs, FdeStore.Fde fde, ProjectStore.ProjectRow project) {
    var id = idOf(fde.handle(), project.name());
    return rooms.atomically(
        () -> {
          if (rooms.findById(id).isPresent()
              || rooms.isTombstoned(id)
              || (specs != null && specs.findById(id).isPresent())) {
            return false;
          }
          rooms.createJournaled(row(id, fde, project));
          return true;
        });
  }

  static RoomStore.RoomRow row(String id, FdeStore.Fde fde, ProjectStore.ProjectRow project) {
    var agent = defaultAgent(project.definition());
    var roster =
        agent == null
            ? null
            : Roster.solo(Engagement.of(agent, EngagementMode.FULL.wire(), null, fde.createdAt()))
                .toJson();
    return new RoomStore.RoomRow(
        id,
        project.name(),
        fde.handle(),
        fde.handle(),
        null,
        roster,
        fde.handle(),
        fde.createdAt(),
        fde.createdAt(),
        fde.handle());
  }

  private static String defaultAgent(String definition) {
    var config = SailYaml.fromMap(YamlUtil.parseMap(definition));
    return config.agent() == null ? null : config.agent().type();
  }
}
