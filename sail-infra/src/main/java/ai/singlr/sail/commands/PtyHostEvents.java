/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.commands;

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
 * shows who opened a terminal there and with what. Failures are swallowed by design: a session must
 * never die because an event row could not be written.
 */
final class PtyHostEvents implements PtyEvents {

  private final Path dbPath;

  PtyHostEvents() {
    this(SailPaths.controlPlaneDb());
  }

  PtyHostEvents(Path dbPath) {
    this.dbPath = dbPath;
  }

  @Override
  public void sessionStarted(PtySession.Origin origin) {
    var data = dataFor(origin);
    data.put("command", origin.command());
    insert("pty_session_started", origin, origin.ownerFde(), data);
  }

  @Override
  public void sessionAttached(PtySession.Origin origin, String fde) {
    insert("pty_session_attached", origin, fde, dataFor(origin));
  }

  @Override
  public void sessionEnded(PtySession.Origin origin, String reason) {
    var data = dataFor(origin);
    data.put("reason", reason);
    insert("pty_session_ended", origin, "sail", data);
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
    } catch (RuntimeException swallowed) {
      var unused = swallowed;
    }
  }
}
