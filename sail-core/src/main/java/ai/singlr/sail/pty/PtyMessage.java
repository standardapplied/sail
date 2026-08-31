/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.pty;

import java.util.List;

/**
 * The session-host wire vocabulary — one sealed hierarchy for both directions. Client to host:
 * create, attach, input, resize, write-token, detach, list, kill, yield. Host to client: output
 * (carrying the last applied input sequence — the one enabler client-side predictive echo needs),
 * replay bracketing, writer and size changes, flow control, endings, listings, and errors.
 */
public sealed interface PtyMessage {

  int PAGE_LIMIT = 16;
  int MAX_COMMAND_BYTES = 32 * 1024;

  record Hello(String token) implements PtyMessage {}

  /**
   * Starts a session. {@code room} is the room this session is pinned to — blank for none. The host
   * admits it through its {@link PtyRooms} gate (the room exists in the session's project and the
   * caller holds its post right) before it rides into the child as {@code SAIL_ROOM_ID}, onto the
   * session's events, and back out on listings. {@code command} is capped at {@link
   * #MAX_COMMAND_BYTES} as encoded on the wire.
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

  /**
   * Asks for one page of the caller's sessions, in name order, strictly after {@code after} (blank
   * for the first page) and at most {@code limit} long. The host clamps {@code limit} to {@link
   * #PAGE_LIMIT} and bounds a session's command at creation to {@link #MAX_COMMAND_BYTES} of wire
   * bytes, so a full page always fits one frame however many sessions exist.
   */
  record ListSessions(String after, int limit) implements PtyMessage {}

  record Kill(String session) implements PtyMessage {}

  /**
   * Ends a live session on behalf of something that displaced it — a dispatch reserving the repos
   * its conversation works in. Admitted only for the host's dispatch authority — the credential the
   * host mints at start, the one principal that may yield and do nothing else — never for an owner
   * or admin, whose verb is {@link Kill}. Idempotent: a session that is not live has nothing to end
   * and answers {@code Ok}. Unlike a kill, every attached client first sees {@code reason} as a
   * terminal line in the stream, and the session ends with that reason rather than the child's exit
   * status, so the room's ending event names why.
   */
  record Yield(String session, String reason) implements PtyMessage {}

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

  /** One page of sessions; {@code next} is blank on the last page, else the cursor to continue. */
  record Sessions(List<SessionInfo> sessions, String next) implements PtyMessage {}

  record Ok() implements PtyMessage {}

  record Err(String message) implements PtyMessage {}
}
