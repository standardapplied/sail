/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.commands;

import ai.singlr.sail.api.Event;
import ai.singlr.sail.common.DateTimeUtils;
import ai.singlr.sail.config.YamlUtil;
import ai.singlr.sail.engine.HostInfo;
import ai.singlr.sail.engine.SailPaths;
import ai.singlr.sail.pty.PtyEvents;
import ai.singlr.sail.pty.PtySession;
import ai.singlr.sail.store.EventStore;
import ai.singlr.sail.store.Sqlite;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The production {@link PtyEvents}: each session fact becomes one record-class event row — {@code
 * pty_session_started|attached|ended} — observational only, never driving run or spec state. A
 * room-bound session's rows are scoped to the room (the indexed {@code spec_id} column, which a
 * room's history query filters on) and carry {@code room_id} in their data, so a room timeline
 * shows who opened a terminal there and with what. "With what" is the executable alone — {@code
 * claude} versus {@code bash} — never the full argv: arguments carry tokens, signed URLs, and
 * inline scripts, and an event row is durable, room-readable history. Failures are swallowed by
 * design — a session must never die because an event row could not be written — but never silently:
 * each drop leaves one structured stderr line (journald, via the pty host service) and bumps the
 * {@link PtyEventDrops} meter that {@code sail session ls --json} surfaces.
 */
final class PtyHostEvents implements PtyEvents {

  private final Path dbPath;
  private final Path dropsPath;

  PtyHostEvents() {
    this(SailPaths.controlPlaneDb(), PtyEventDrops.fileOf(SailPaths.ptySocketPath()));
  }

  PtyHostEvents(Path dbPath, Path dropsPath) {
    this.dbPath = dbPath;
    this.dropsPath = dropsPath;
  }

  @Override
  public void sessionStarted(PtySession.Origin origin) {
    var data = dataFor(origin);
    data.put("executable", origin.command().getFirst());
    insert(Event.WellKnownTypes.PTY_SESSION_STARTED, origin, origin.ownerFde(), data);
  }

  @Override
  public void sessionAttached(PtySession.Origin origin, String fde) {
    insert(Event.WellKnownTypes.PTY_SESSION_ATTACHED, origin, fde, dataFor(origin));
  }

  @Override
  public void sessionEnded(PtySession.Origin origin, String reason) {
    var data = dataFor(origin);
    data.put("reason", reason);
    insert(Event.WellKnownTypes.PTY_SESSION_ENDED, origin, "sail", data);
  }

  private static Map<String, Object> dataFor(PtySession.Origin origin) {
    var data = new LinkedHashMap<String, Object>();
    data.put("session", origin.name());
    if (origin.roomBound()) {
      data.put("room_id", origin.room());
    }
    return data;
  }

  private void insert(
      String type, PtySession.Origin origin, String agent, Map<String, Object> data) {
    try (var db = Sqlite.open(dbPath)) {
      new EventStore(db)
          .insert(
              new EventStore.EventRow(
                  0,
                  DateTimeUtils.now().toString(),
                  type,
                  origin.project(),
                  origin.roomBound() ? origin.room() : null,
                  agent,
                  HostInfo.hostname(),
                  YamlUtil.dumpJson(data)));
    } catch (RuntimeException failed) {
      System.err.println(
          "pty-events: dropped "
              + type
              + " session="
              + origin.name()
              + " project="
              + origin.project()
              + " cause="
              + failed);
      PtyEventDrops.record(dropsPath, type, failed.toString());
    }
  }
}
