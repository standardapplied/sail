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
import java.util.Objects;

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
   * container. {@code agent} names a harness the host starts there, signed in as the workspace's
   * owner, instead of a command: the host composes its command, so {@code command} is then empty.
   */
  record Create(
      String session,
      List<String> command,
      String cwd,
      String project,
      String room,
      int cols,
      int rows,
      String workspace,
      Agent agent)
      implements PtyMessage {

    public Create {
      workspace = workspaceOf(workspace);
      agent = Objects.requireNonNullElse(agent, Agent.NONE);
    }

    /** A session that runs {@code command}, or the login shell when it is empty. */
    public Create(
        String session,
        List<String> command,
        String cwd,
        String project,
        String room,
        int cols,
        int rows,
        String workspace) {
      this(session, command, cwd, project, room, cols, rows, workspace, Agent.NONE);
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
   * The harness a session runs, by its {@code yamlName}, in {@code mode}, one of the harness's own
   * ({@link ai.singlr.sail.harness.Harness#modes}): signed in with its person's stored login, or,
   * {@code inside}, with none handed over, so the person signs in with the harness's own login
   * there, which Sail never holds. {@link #NONE} for a session that runs a command.
   */
  record Agent(String harness, String mode, boolean inside) {
    public static final Agent NONE = new Agent("", "", false);

    public Agent {
      harness = Objects.toString(harness, "");
      mode = Objects.toString(mode, "");
    }

    /** Whether this names no harness: a session that runs a command. */
    public boolean none() {
      return harness.isEmpty();
    }
  }

  /**
   * The workspace {@code named} names, blank for the project's own container: none, blank, or the
   * default workspace {@code main}.
   */
  static String workspaceOf(String named) {
    return named == null || named.isBlank() || named.equals("main") ? "" : named;
  }

  /**
   * Attaches to {@code session}, asking for the keyboard when {@code write}. {@code client} names
   * the asking client across its own reconnects, so a write request takes the keyboard back from
   * that client's own lost connection; {@code takes} says the person asked for it now (an open, an
   * explicit attach), which takes it from their own other client too. Without {@code takes} a write
   * request (an automatic reconnect, a restore) never takes it from another live client of theirs.
   */
  record Attach(String session, boolean write, String client, boolean takes) implements PtyMessage {
    public Attach(String session, boolean write) {
      this(session, write, "", true);
    }
  }

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
   * status, so the room's ending event names why. {@code record} is what happened, said once in the
   * past tense, which a listing gives while the ended session is kept; blank for the host to say it
   * from {@code reason}.
   */
  record Yield(String session, String reason, String record) implements PtyMessage {
    public Yield(String session, String reason) {
      this(session, reason, "");
    }
  }

  /**
   * Ends every live session in workspace {@code workspace} of {@code project}, each as a {@link
   * Yield} ends one, and every files connection to it, each told {@code record}: the dispatch
   * authority's alone, sent as the workspace is stopped or deleted.
   */
  record YieldWorkspace(String project, String workspace, String reason, String record)
      implements PtyMessage {}

  /**
   * What a {@link Ticket} lets its holder do: open a session in a project, attach to one, or read
   * the files of a workspace.
   */
  enum TicketAction {
    OPEN,
    ATTACH,
    FILES;

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
      throw new IOException(
          "A ticket opens, attaches or reads files; '" + wire + "' is none of them.");
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
    UNAVAILABLE,
    STOPPED;

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

  /**
   * Lists folder {@code path} of a files ticket's workspace, an absolute path under its home: one
   * or more {@link FilesListing} frames, or a {@link FilesRefused}. {@code id} is the client's, one
   * per request under way.
   */
  record FilesList(int id, String path) implements PtyMessage {}

  /** What {@code path} is: a {@link FilesEntry}, or a {@link FilesRefused}. */
  record FilesStat(int id, String path) implements PtyMessage {}

  /**
   * Reads at most {@code length} bytes of file {@code path} from {@code offset}: {@link FilesData}
   * frames of at most {@link #FILE_CHUNK} bytes, the last one at the file's end saying so, or a
   * {@link FilesRefused}.
   */
  record FilesRead(int id, String path, long offset, long length) implements PtyMessage {}

  /** Ends request {@code id}, under way or waiting, unanswered from now on. */
  record FilesCancel(int id) implements PtyMessage {}

  /**
   * Replaces the connection's whole watch set: each path is watched from the version the client
   * holds of it, so a change made before the watch took it is reported all the same.
   */
  record FilesWatch(List<Watched> paths) implements PtyMessage {
    public FilesWatch {
      paths = List.copyOf(paths);
    }
  }

  /** A watched path and the version the client holds of it, blank for one it holds as missing. */
  record Watched(String path, String version) {}

  /**
   * Entries of the folder request {@code id} lists, at {@code version}, in no order: {@code more}
   * while frames of it follow, and on its last frame {@code truncated} when the folder holds more
   * than {@link #LISTING_CAP} entries, of which this is the first {@link #LISTING_CAP}.
   */
  record FilesListing(
      int id, String version, List<FileEntry> entries, boolean more, boolean truncated)
      implements PtyMessage {
    public FilesListing {
      entries = List.copyOf(entries);
    }
  }

  /** What request {@code id}'s path is, and its version. */
  record FilesEntry(int id, FileEntry entry, String version) implements PtyMessage {}

  /**
   * The next bytes request {@code id} reads, at the version the file had when the read began;
   * {@code eof} on the last frame when it reached the file's end. The last frame alone carries
   * {@code digest}, the SHA-256 in hex of every byte the read sent, which the workspace hashed
   * again from the file after sending them and found the same: the read's bytes are the file's at
   * one moment, and the digest is their content version. Blank on every frame before the last.
   * {@code whole}, on the last frame alone, says the read ran from 0 to the file's end, so its
   * digest is the whole file's: the only one a save takes as its base.
   */
  record FilesData(int id, String version, byte[] bytes, boolean eof, boolean whole, String digest)
      implements PtyMessage {}

  /** These watched paths changed: the client reads them again. */
  record FilesChanged(List<String> paths) implements PtyMessage {
    public FilesChanged {
      paths = List.copyOf(paths);
    }
  }

  /** The watch is live: said each second it runs. */
  record FilesTick() implements PtyMessage {}

  /** Request {@code id} refused, in a sentence naming the file and the reason. */
  record FilesRefused(int id, FilesRefusal kind, String message) implements PtyMessage {}

  /**
   * Saves {@code size} bytes as file {@code path}, then sent as {@link PutChunk} frames of the same
   * {@code id}, a window of two unacknowledged ({@link PutAck}), and a {@link PutEnd} with their
   * SHA-256: written over the file only while its whole content is still {@code base}, the SHA-256
   * in lowercase hex a whole read of it answered ({@link FilesData#digest}), or made anew only
   * while no name {@code path} exists when {@code base} is {@link #ABSENT}. {@code seen} is the
   * version that read answered, which lets a save refuse early, never accept; blank with {@link
   * #ABSENT}. Answered {@link FilesWritten}, {@link FilesConflict} or {@link FilesRefused};
   * cancelled by {@link FilesCancel}.
   */
  record FilesWrite(int id, String path, String base, String seen, long size)
      implements PtyMessage {}

  /**
   * Makes {@code path} anew, a folder when {@code folder}, else an empty file: {@link FilesDone}.
   */
  record FilesCreate(int id, String path, boolean folder) implements PtyMessage {}

  /** Moves {@code from} to {@code to}, never over a name already there: {@link FilesDone}. */
  record FilesMove(int id, String from, String to) implements PtyMessage {}

  /** Removes {@code path}, a folder with all it holds: {@link FilesDone}. */
  record FilesRemove(int id, String path) implements PtyMessage {}

  /**
   * Write {@code id} is the file's whole content now: {@code digest} is its SHA-256, the next
   * save's base, and {@code version} its version after the save.
   */
  record FilesWritten(int id, String digest, String version) implements PtyMessage {}

  /**
   * Write {@code id} wrote nothing: the file's content is {@code digest} now, not the one it was
   * based on, or {@link #ABSENT} when it is gone; the person chooses.
   */
  record FilesConflict(int id, String digest) implements PtyMessage {}

  /** Create, move or remove {@code id} is done. */
  record FilesDone(int id) implements PtyMessage {}

  /** The version of a file that is not there: a write's base, or a conflict's present. */
  String ABSENT = "absent";

  /** The most entries a listing carries. */
  int LISTING_CAP = 50_000;

  /** The most bytes one {@link FilesData} frame carries. */
  int FILE_CHUNK = 256 << 10;

  /**
   * One entry of a folder: its {@code name}, its {@code kind}, a link's {@code target} kind ({@link
   * FileKind#NONE} for any other), its own size and modification time in nanoseconds since the
   * epoch, and its permission bits.
   */
  record FileEntry(
      String name, FileKind kind, FileKind target, long size, long mtimeNanos, int mode) {}

  /**
   * What an entry is: a file, a folder, a link, or another kind (a pipe, a socket, a device); and
   * what a link leads to, which may also be nothing or itself.
   */
  enum FileKind {
    NONE,
    FILE,
    FOLDER,
    LINK,
    OTHER,
    MISSING,
    LOOP;

    /** The kind as the wire names it, blank for {@link #NONE}. */
    public String wire() {
      return this == NONE ? "" : name().toLowerCase(Locale.ROOT);
    }

    /** The kind {@code wire} names, refused when it names none. */
    public static FileKind of(String wire) throws IOException {
      for (var kind : values()) {
        if (kind.wire().equals(wire)) {
          return kind;
        }
      }
      throw new IOException("Corrupt pty frame: a file of kind '" + wire + "'.");
    }
  }

  /** Why a files request was refused. */
  enum FilesRefusal {
    NOT_FOUND,
    NOT_PERMITTED,
    OUTSIDE_HOME,
    IS_FOLDER,
    NOT_A_FOLDER,
    NOT_A_FILE,
    LOOP,
    CHANGED,
    EXISTS,
    NO_SPACE,
    TOO_LARGE,
    PARTLY_REMOVED,
    UNSAFE,
    FAILED;

    /** The kind as the wire names it. */
    public String wire() {
      return name().toLowerCase(Locale.ROOT);
    }

    /** The kind {@code wire} names, refused when it names none. */
    public static FilesRefusal of(String wire) throws IOException {
      for (var kind : values()) {
        if (kind.wire().equals(wire)) {
          return kind;
        }
      }
      throw new IOException("Corrupt pty frame: a files refusal of kind '" + wire + "'.");
    }
  }

  record Output(long lastInputSeq, byte[] bytes) implements PtyMessage {}

  record ReplayBegin(boolean safe) implements PtyMessage {}

  record ReplayEnd() implements PtyMessage {}

  /**
   * {@code fde} holds the keyboard now; {@code yours} when it is the receiving connection that
   * holds it, so two clients of one person each know which of them types.
   */
  record WriterChanged(String fde, boolean yours) implements PtyMessage {}

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
   * when, and {@code endedReason} why it ended, blank while it lives. {@code agent} is the harness
   * it runs, {@link Agent#NONE} for a command, whose {@code command} is then the harness's own,
   * never what started it.
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
      String endedReason,
      Agent agent)
      implements PtyMessage {

    public SessionInfo {
      agent = Objects.requireNonNullElse(agent, Agent.NONE);
    }

    /** A session in no workspace, its opener unsaid. */
    public SessionInfo(
        String name,
        String instanceId,
        boolean live,
        int attached,
        String writerFde,
        String room,
        List<String> command) {
      this(
          name,
          instanceId,
          live,
          attached,
          writerFde,
          room,
          command,
          "",
          "",
          "",
          0,
          "",
          Agent.NONE);
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
          endedReason,
          agent);
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
          endedReason,
          agent);
    }

    /** This session, running {@code agent}. */
    public SessionInfo running(Agent agent) {
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
          endedReason,
          agent);
    }
  }

  /** One page of sessions; {@code next} is blank on the last page, else the cursor to continue. */
  record Sessions(List<SessionInfo> sessions, String next) implements PtyMessage {}

  record Ok() implements PtyMessage {}

  /**
   * A refusal: what happened, naming no command, and what to do about it, each command kept as data
   * so the client that prints it renders it for its own reader; never one flattened into the other.
   * {@code code} is its kind as the API's error codes name it ({@code login_not_stored}), blank for
   * a refusal no client acts on by kind.
   */
  record Err(String reason, Remedy remedy, String code) implements PtyMessage {
    public Err {
      code = Objects.toString(code, "");
    }

    public Err(String reason, Remedy remedy) {
      this(reason, remedy, "");
    }

    public Err(String reason) {
      this(reason, Remedy.NONE);
    }

    /** The refusal {@code failure} is: its reason, its remedy and its kind as they are. */
    public static Err of(Throwable failure) {
      return new Err(
          Remedied.reasonOf(failure),
          Remedied.remedyOf(failure),
          failure instanceof Remedied remedied ? remedied.code() : "");
    }

    /** The refusal as an account whose own plain {@code sail} runs reads it. */
    public String message() {
      return Remedied.message(reason, remedy);
    }
  }
}
