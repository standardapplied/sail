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
import ai.singlr.sail.store.EventStore;
import ai.singlr.sail.store.Sqlite;
import java.nio.file.Path;
import java.util.Map;

/**
 * The production {@link PtyEvents}: each session fact becomes one record-class event row — {@code
 * pty_session_started|attached|ended} — observational only, never driving run or spec state.
 * Failures are swallowed by design: a session must never die because an event row could not be
 * written.
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
  public void sessionStarted(String session, String project, String fde) {
    insert("pty_session_started", project, fde, Map.of("session", session));
  }

  @Override
  public void sessionAttached(String session, String project, String fde) {
    insert("pty_session_attached", project, fde, Map.of("session", session));
  }

  @Override
  public void sessionEnded(String session, String project, String reason) {
    insert("pty_session_ended", project, "sail", Map.of("session", session, "reason", reason));
  }

  private void insert(String type, String project, String agent, Map<String, Object> data) {
    try (var db = Sqlite.open(dbPath)) {
      new EventStore(db)
          .insert(
              new EventStore.EventRow(
                  0,
                  DateTimeUtils.now().toString(),
                  type,
                  project,
                  null,
                  agent,
                  HostInfo.hostname(),
                  YamlUtil.dumpJson(data)));
    } catch (RuntimeException swallowed) {
      var unused = swallowed;
    }
  }
}
