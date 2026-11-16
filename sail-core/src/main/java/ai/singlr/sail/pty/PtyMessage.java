/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.pty;

import ai.singlr.sail.common.Remedy;
import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Locale;

/**
 * The session-host wire vocabulary — one sealed hierarchy for both directions. Client to host:
 * create, attach, input, resize, write-token, detach, list, kill, yield, a ticket asked for. Host
 * to client: welcome (the host's boot id), output (carrying the last applied input sequence — the
 * one enabler client-side predictive echo needs), replay bracketing, writer and size changes, flow
 * control, endings, listings, a ticket, and errors.
 */
public sealed interface PtyMessage {

  int PAGE_LIMIT = 16;
  int MAX_COMMAND_BYTES = 32 * 1024;

  /** How long a {@link Ticket} may wait to be presented. */
  Duration TICKET_LIFE = Duration.ofSeconds(30);

  record Hello(String token) implements PtyMessage {}

  /**
   * The host's answer to an admitted {@link Hello}: {@code hostBootId} names this run of the host
   * process, minted at start and shared by every connection until the host restarts. A client that
   * remembers the id a session was last seen under can tell a session that died from one the host
   * lost by restarting. Served only after identity, never before.
   */
  record Welcome(String hostBootId) implements PtyMessage {}

  /**
   * Starts a session. {@code room} is the room this session is pinned to — blank for none. The host
   * admits it through its {@link PtyAdmission} gate (the room exists in the session's project and
   * the caller holds its post right) before it rides into the child as {@code SAIL_ROOM_ID}, onto
   * the session's events, and back out on listings. {@code command} is capped at {@link
   * #MAX_COMMAND_BYTES} as encoded on the wire. {@code workspace} names a workspace of the project
   * to run in, its owner's or an admin's to enter; blank (or {@code main}) is the project's own
   * container.
   */
  record Create(
      String session,
      List<String> command,
      String cwd,
      String project,
      String room,
      int cols,
      int rows,
      String workspace)
      implements PtyMessage {

    public Create {
      workspace = workspaceOf(workspace);
    }

    /** A session in the project's own container, or on the host when it names no project. */
    public Create(
        String session,
        List<String> command,
        String cwd,
        String project,
        String room,
        int cols,
        int rows) {
      this(session, command, cwd, project, room, cols, rows, "");
    }
  }

  /**
   * The workspace {@code named} names, blank for the project's own container: none, blank, or the
   * default workspace {@code main}.
   */
  static String workspaceOf(String named) {
    return named == null || named.isBlank() || named.equals("main") ? "" : named;
  }

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

  /** What a {@link Ticket} lets its holder do: open a session in a project, or attach to one. */
  enum TicketAction {
    OPEN,
    ATTACH;

    /** The action as the wire and the API name it. */
    public String wire() {
      return name().toLowerCase(Locale.ROOT);
    }

    /** The action {@code wire} names, refused when it names none. */
    public static TicketAction of(String wire) throws IOException {
      for (var action : values()) {
        if (action.wire().equals(wire)) {
          return action;
        }
      }
      throw new IOException("A ticket opens or attaches; '" + wire + "' is neither.");
    }
  }

  /**
   * Asks for a ticket that lets the holder of {@code credential}, the API caller's own bearer, take
   * {@code action} on {@code target} (a project to open a session in, or a session to attach to)
   * once, over the host's TCP listener. The host resolves the credential itself, for this decision
   * alone, decides it as it decides that open or attach, and answers a {@link Ticket} or a {@link
   * TicketRefused}. The connection that asks admits no one by it.
   */
  record IssueTicket(TicketAction action, String target, String credential, String workspace)
      implements PtyMessage {

    public IssueTicket {
      workspace = workspaceOf(workspace);
    }

    /** A ticket for the project's own container, or to attach to a session. */
    public IssueTicket(TicketAction action, String target, String credential) {
      this(action, target, credential, "");
    }

    @Override
    public String toString() {
      return "IssueTicket[action=" + action + ", target=" + target + "]";
    }
  }

  /**
   * A ticket: presented as the {@link Hello} token of one TCP connection within {@link
   * #TICKET_LIFE}, it admits that connection for the action and target it was issued for, and
   * nothing else.
   */
  record Ticket(String ticket) implements PtyMessage {
    @Override
    public String toString() {
      return "Ticket[]";
    }
  }

  /** What kind of refusal a ticket request met, which the API answers with its own status. */
  enum TicketRefusal {
    INVALID,
    NOT_FOUND,
    FORBIDDEN,
    LIMITED,
    UNAVAILABLE;

    /** The kind as the wire names it. */
    public String wire() {
      return name().toLowerCase(Locale.ROOT);
    }

    /** The kind {@code wire} names, refused when it names none. */
    public static TicketRefusal of(String wire) throws IOException {
      for (var kind : values()) {
        if (kind.wire().equals(wire)) {
          return kind;
        }
      }
      throw new IOException("Corrupt pty frame: a ticket refusal of kind '" + wire + "'.");
    }
  }

  /** A ticket request refused: what kind of refusal, and its words, kept as a refusal's are. */
  record TicketRefused(TicketRefusal kind, Remedy detail) implements PtyMessage {
    public TicketRefused(TicketRefusal kind, String message) {
      this(kind, Remedy.of(message));
    }
  }

  record Output(long lastInputSeq, byte[] bytes) implements PtyMessage {}

  record ReplayBegin(boolean safe) implements PtyMessage {}

  record ReplayEnd() implements PtyMessage {}

  record WriterChanged(String fde) implements PtyMessage {}

  record Resized(int cols, int rows) implements PtyMessage {}

  record Paused() implements PtyMessage {}

  record Continued() implements PtyMessage {}

  record SessionEnded(String reason) implements PtyMessage {}

  /**
   * One listed session. {@code name} is reusable across lives; {@code instanceId} names this life
   * of it (see {@link PtySession.Origin}), so two corpses of one name are distinguishable. {@code
   * command} is the child as requested (the default login shell when none was), so a client can
   * tell an agent session from a plain shell; {@code room} is blank when the session is not
   * room-bound, {@code workspace} when it runs in the project's own container; {@code opener} is
   * the person who opened it.
   */
  record SessionInfo(
      String name,
      String instanceId,
      boolean live,
      int attached,
      String writerFde,
      String room,
      List<String> command,
      String workspace,
      String opener)
      implements PtyMessage {

    /** A session in no workspace, its opener unsaid. */
    public SessionInfo(
        String name,
        String instanceId,
        boolean live,
        int attached,
        String writerFde,
        String room,
        List<String> command) {
      this(name, instanceId, live, attached, writerFde, room, command, "", "");
    }

    /** This session, run in {@code workspace} and opened by {@code opener}. */
    public SessionInfo placed(String workspace, String opener) {
      return new SessionInfo(
          name, instanceId, live, attached, writerFde, room, command, workspace, opener);
    }
  }

  /** One page of sessions; {@code next} is blank on the last page, else the cursor to continue. */
  record Sessions(List<SessionInfo> sessions, String next) implements PtyMessage {}

  record Ok() implements PtyMessage {}

  /**
   * A refusal: what happened and what to do about it, each command kept as data so the client that
   * prints it renders it for its own reader.
   */
  record Err(Remedy detail) implements PtyMessage {
    public Err(String message) {
      this(Remedy.of(message));
    }

    /** The refusal as an account whose own plain {@code sail} runs reads it. */
    public String message() {
      return detail.plain();
    }
  }
}
