/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.pty;

import java.util.List;

/**
 * The session-host wire vocabulary — one sealed hierarchy for both directions. Client to host:
 * create, attach, input, resize, write-token, detach, list, kill. Host to client: output (carrying
 * the last applied input sequence — the one enabler client-side predictive echo needs), replay
 * bracketing, writer and size changes, flow control, endings, listings, and errors.
 */
public sealed interface PtyMessage {

  record Hello(String token) implements PtyMessage {}

  /**
   * Starts a session. {@code room} is the room this session is pinned to — blank for none — and is
   * opaque to the host: it rides into the child as {@code SAIL_ROOM_ID}, onto the session's events,
   * and back out on listings; the API layer, which owns rooms, is where it is validated.
   */
  record Create(
      String session,
      List<String> command,
      String cwd,
      String project,
      String room,
      int cols,
      int rows)
      implements PtyMessage {}

  record Attach(String session, boolean write) implements PtyMessage {}

  record Input(long seq, byte[] bytes) implements PtyMessage {}

  record Resize(int cols, int rows) implements PtyMessage {}

  record TakeWrite() implements PtyMessage {}

  record Detach() implements PtyMessage {}

  record ListSessions() implements PtyMessage {}

  record Kill(String session) implements PtyMessage {}

  record Output(long lastInputSeq, byte[] bytes) implements PtyMessage {}

  record ReplayBegin(boolean safe) implements PtyMessage {}

  record ReplayEnd() implements PtyMessage {}

  record WriterChanged(String fde) implements PtyMessage {}

  record Resized(int cols, int rows) implements PtyMessage {}

  record Paused() implements PtyMessage {}

  record Continued() implements PtyMessage {}

  record SessionEnded(String reason) implements PtyMessage {}

  /**
   * One listed session. {@code command} is the child as requested (the default login shell when
   * none was), so a client can tell an agent session from a plain shell; {@code room} is blank when
   * the session is not room-bound.
   */
  record SessionInfo(
      String name, boolean live, int attached, String writerFde, String room, List<String> command)
      implements PtyMessage {}

  record Sessions(List<SessionInfo> sessions) implements PtyMessage {}

  record Ok() implements PtyMessage {}

  record Err(String message) implements PtyMessage {}
}
