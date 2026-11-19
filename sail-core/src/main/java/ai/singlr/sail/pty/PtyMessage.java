/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.pty;

import ai.singlr.sail.common.Remedied;
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
   * bytes, so a full page always fits one frame however many sessions exist. {@code credential},
   * blank for the connection's own, is the one an API request carries: the box's own process asks
   * for its caller, and the host resolves it and decides as it decides that caller's attach.
   */
  record ListSessions(String after, int limit, String credential) implements PtyMessage {
    public ListSessions(String after, int limit) {
      this(after, limit, "");
    }
  }

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

  /**
   * A ticket request refused: what kind of refusal, what happened, and what to do about it, kept
   * apart as a refusal's are, so the API answers the reason and the typed remedy as they are.
   */
  record TicketRefused(TicketRefusal kind, String reason, Remedy remedy) implements PtyMessage {
    public TicketRefused(TicketRefusal kind, String reason) {
      this(kind, reason, Remedy.NONE);
    }

    /** {@code err}'s reason and remedy, heard as {@code kind}. */
    public TicketRefused(TicketRefusal kind, Err err) {
      this(kind, err.reason(), err.remedy());
    }
  }

  /**
   * Starts upload {@code id} (unique per connection, one at a time) of a file the person pasted or
   * dropped, {@code size} bytes, which the host stores in the attached session's own workspace
   * under a name it makes from {@code name}.
   */
  record PutBegin(int id, String name, long size) implements PtyMessage {}

  /** {@code bytes} of upload {@code id} at {@code offset}, in order, at most 64 KiB. */
  record PutChunk(int id, long offset, byte[] bytes) implements PtyMessage {}

  /** Upload {@code id} is whole: its SHA-256, which the host checks before it stores it. */
  record PutEnd(int id, byte[] sha256) implements PtyMessage {}

  /** Upload {@code id}, its end not yet sent, is abandoned: the host keeps nothing of it. */
  record PutCancel(int id) implements PtyMessage {}

  /** The host holds {@code received} bytes of upload {@code id}: the client may send more. */
  record PutAck(int id, long received) implements PtyMessage {}

  /** Upload {@code id} is stored at {@code path} in the session's own workspace. */
  record PutStored(int id, String path) implements PtyMessage {}

  /**
   * Upload {@code id} is refused, and nothing of it is kept: why, and the bound it met (0 when it
   * met none). The client words it; the host sends only facts.
   */
  record PutRefused(int id, PutRefusal kind, long limit) implements PtyMessage {}

  /** Why an upload was refused. */
  enum PutRefusal {
    TOO_LARGE,
    TOO_MANY,
    SESSION_FULL,
    NOT_WRITER,
    NO_CONTAINER,
    SHARED_CONTAINER,
    SESSION_ENDED,
    NO_SPACE,
    FAILED,
    STALLED,
    BUSY;

    /** The kind as the wire names it. */
    public String wire() {
      return name().toLowerCase(Locale.ROOT);
    }

    /** The kind {@code wire} names, refused when it names none. */
    public static PutRefusal of(String wire) throws IOException {
      for (var kind : values()) {
        if (kind.wire().equals(wire)) {
          return kind;
        }
      }
      throw new IOException("Corrupt pty frame: an upload refusal of kind '" + wire + "'.");
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
   * room-bound, {@code project} when it is in none, and {@code workspace} when it runs in the
   * project's own container; {@code opener} is the person who opened it, {@code createdAtMillis}
   * when, and {@code endedReason} why it ended, blank while it lives.
   */
  record SessionInfo(
      String name,
      String instanceId,
      boolean live,
      int attached,
      String writerFde,
      String room,
      List<String> command,
      String project,
      String workspace,
      String opener,
      long createdAtMillis,
      String endedReason)
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
      this(name, instanceId, live, attached, writerFde, room, command, "", "", "", 0, "");
    }

    /**
     * This session, run in workspace {@code workspace} of {@code project} (blank for the project's
     * own container) and opened by {@code opener}.
     */
    public SessionInfo placed(String project, String workspace, String opener) {
      return new SessionInfo(
          name,
          instanceId,
          live,
          attached,
          writerFde,
          room,
          command,
          project,
          workspace,
          opener,
          createdAtMillis,
          endedReason);
    }

    /** This session, opened at {@code createdAtMillis} and ended for {@code endedReason}. */
    public SessionInfo lived(long createdAtMillis, String endedReason) {
      return new SessionInfo(
          name,
          instanceId,
          live,
          attached,
          writerFde,
          room,
          command,
          project,
          workspace,
          opener,
          createdAtMillis,
          endedReason);
    }
  }

  /** One page of sessions; {@code next} is blank on the last page, else the cursor to continue. */
  record Sessions(List<SessionInfo> sessions, String next) implements PtyMessage {}

  record Ok() implements PtyMessage {}

  /**
   * A refusal: what happened, naming no command, and what to do about it, each command kept as data
   * so the client that prints it renders it for its own reader; never one flattened into the other.
   */
  record Err(String reason, Remedy remedy) implements PtyMessage {
    public Err(String reason) {
      this(reason, Remedy.NONE);
    }

    /** The refusal {@code failure} is: its reason and its remedy as they are. */
    public static Err of(Throwable failure) {
      return new Err(Remedied.reasonOf(failure), Remedied.remedyOf(failure));
    }

    /** The refusal as an account whose own plain {@code sail} runs reads it. */
    public String message() {
      return Remedied.message(reason, remedy);
    }
  }
}
