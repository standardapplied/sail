/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.pty;

import ai.singlr.sail.common.Remedied;
import ai.singlr.sail.common.Remedy;
import ai.singlr.sail.store.LoginStore;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Where the host lets a person open a terminal: on this box, when it is theirs to operate ({@link
 * #box}). A project and a room a client names are claims until this gate has checked them: the
 * project must be one the caller holds on this box — its catalog holds it and the caller's scope
 * admits it — before the session runs in its container, and a room must exist in the session's
 * project with the caller holding its post right — the same gate a room message passes — before it
 * may ride into the child as {@code SAIL_ROOM_ID} or onto the room's event history.
 */
public interface PtyAdmission {

  /**
   * Throws, with the reason, when {@code who} may not open a terminal on this box: opening one
   * needs operating the box, as attaching to a session needs operating its opener's, so the box's
   * own person and an admin open, and nobody else, at the unix socket and with a ticket alike.
   */
  void box(PtyIdentity.Person who) throws IOException;

  /** Throws, with the reason, when {@code who} may not open a session in {@code project}. */
  void project(String project, PtyIdentity.Person who) throws IOException;

  /**
   * Throws, with the reason, when the instance a session in {@code project} would run in is not
   * that project's container. Asked once per open, of an open the caller may make, and never of an
   * attach, which joins a session an open already admitted. Nothing to refuse by default.
   */
  default void instance(String project) throws IOException {}

  /**
   * Throws, with the reason, when {@code who} may not enter workspace {@code workspace} of {@code
   * project}: {@link Absent} when the project has none of that name here; any other refusal when it
   * is not theirs and they are no admin. Asked of a ticket and of an open alike, after the project.
   * A host with no workspaces refuses every one.
   */
  default void workspace(String project, String workspace, PtyIdentity.Person who)
      throws IOException {
    throw Remedied.io(
        "This terminal host has no workspaces: a workspace lives on a runner.",
        Remedy.of(
            "Open the project's own terminal here, or ask the runner for a ticket to the"
                + " workspace."));
  }

  /**
   * Readies workspace {@code workspace} of {@code project} for a session of {@code who}: its own
   * instance, found by name and keys, started when stopped and given its owner's credentials, as an
   * attach does. Throws, with the reason, when it cannot.
   */
  default void enter(String project, String workspace, PtyIdentity.Person who) throws IOException {
    workspace(project, workspace, who);
  }

  /**
   * Throws, with the reason, when {@code who} may not read the files of workspace {@code workspace}
   * of {@code project}: decided as entering it is ({@link #workspace}), then {@link Stopped} when
   * it is not running, which nothing here starts. Asked of a files ticket, and again when a script
   * in it fails for no reason of its own.
   */
  default void files(String project, String workspace, PtyIdentity.Person who) throws IOException {
    workspace(project, workspace, who);
  }

  /**
   * The stored login {@code agent} starts with in workspace {@code workspace} of {@code project}
   * for {@code who}, empty when it signs in inside; or throws, with the reason and a remedy, when
   * it may not start there: the workspace is not {@code who}'s own (an admin who may enter it opens
   * a shell there, never an agent), the project does not install the harness, or {@code who} has no
   * default login for it. Asked after the open decision and before the workspace is entered, and
   * changes nothing. A host with no workspaces starts no agent.
   */
  default Optional<LoginStore.Login> agent(
      String project, String workspace, PtyMessage.Agent agent, PtyIdentity.Person who)
      throws IOException {
    throw Remedied.io(
        "This terminal host starts no agents: an agent runs in a workspace on a runner.",
        Remedy.NONE);
  }

  /**
   * Hands {@code lines}, {@code who}'s login, to the dev user of workspace {@code workspace} of
   * {@code project} as the file {@code file}, {@code 0600} in a directory of its own made for it,
   * written by the dev user from its input: never on a command line. The workspace is read again
   * under its lock first, and refused unless it is still {@code who}'s. Throws, with the reason,
   * when it cannot; what a hand-over that failed may have left is the caller's to withdraw.
   */
  default void handOver(
      String project, String workspace, String file, String lines, PtyIdentity.Person who)
      throws IOException {
    throw new IOException("This terminal host hands no logins over.");
  }

  /**
   * Removes what {@link #handOver} wrote as {@code file} in {@code instance}, for a session that
   * did not start; nothing when it is gone.
   */
  default void withdraw(String instance, String file) {}

  /**
   * A workspace the project does not have here: not found, never refused; its reason and remedy
   * kept apart, so a door answers the remedy typed.
   */
  final class Absent extends IOException implements Remedied {
    private final String reason;
    private final transient Remedy remedy;

    public Absent(String reason, Remedy remedy) {
      super(Remedied.message(reason, remedy));
      this.reason = reason;
      this.remedy = remedy;
    }

    @Override
    public String reason() {
      return reason;
    }

    @Override
    public Remedy remedy() {
      return remedy;
    }
  }

  /**
   * A workspace that is stopped, which a files ticket never starts: its reason and the remedy that
   * starts it, kept apart, and the code {@link #CODE}.
   */
  final class Stopped extends IOException implements Remedied {
    /** The code a stopped workspace is refused with, as the API's error codes name it. */
    public static final String CODE = "workspace_stopped";

    private final String reason;
    private final transient Remedy remedy;

    public Stopped(String reason, Remedy remedy) {
      super(Remedied.message(reason, remedy));
      this.reason = reason;
      this.remedy = remedy;
    }

    @Override
    public String reason() {
      return reason;
    }

    @Override
    public Remedy remedy() {
      return remedy;
    }

    @Override
    public String code() {
      return CODE;
    }
  }

  /**
   * The runner could not do what was asked (its tool failed, or did not answer in time):
   * unavailable, never refused. Its words are a person's, one of two sentences, with who looks;
   * what the tool said is its cause, for the log only.
   */
  final class Unavailable extends IOException implements Remedied {
    /** The runner could not say what a workspace or an instance is. */
    public static final String UNREAD =
        "The runner could not read its workspaces, so no terminal was opened.";

    /** The runner could not finish what was asked of a workspace: its start, say. */
    public static final String UNFINISHED = "The runner could not finish that.";

    /** Who looks, said with either. */
    public static final Remedy REMEDY = Remedy.of("An admin looks at the runner.");

    private final String reason;

    /** The runner could not read its workspaces, for {@code cause}. */
    public Unavailable(Throwable cause) {
      this(UNREAD, cause);
    }

    /** The runner could not do what {@code reason} says, for {@code cause}. */
    public Unavailable(String reason, Throwable cause) {
      super(Remedied.message(reason, REMEDY), cause);
      this.reason = reason;
    }

    @Override
    public String reason() {
      return reason;
    }

    @Override
    public Remedy remedy() {
      return REMEDY;
    }
  }

  /**
   * Throws, with the reason, when {@code who} may not pin a session in {@code project} to {@code
   * room}.
   */
  void room(String room, String project, PtyIdentity.Person who) throws IOException;

  /**
   * How a session {@code who} opens on this box itself, in the folder {@code named}, runs there, or
   * throws with the reason, for a person, before any process starts. The server opens it in the
   * folder named, or in its own home when none is ({@link Folder#onThisBox}).
   */
  default Folder folder(Path named, PtyIdentity.Person who) throws IOException {
    return Folder.onThisBox(named);
  }

  /**
   * Where a session runs ({@code path}), what it runs there when it names no command ({@code
   * shell}), and what its environment holds beyond the host's own and the terminal's ({@code env}).
   */
  record Folder(Path path, List<String> shell, Map<String, String> env) {

    public Folder {
      shell = List.copyOf(shell);
      env = Map.copyOf(env);
    }

    /**
     * Where a session in a container or a workspace runs on this box: its {@code incus exec} starts
     * in {@code /}, and its shell in the instance's own {@code /home/dev/workspace}, as {@code sail
     * project workspace shell}'s does; no folder a client names is used for it.
     */
    public static final Folder INSTANCE = asNamed(Path.of("/"));

    /** {@code path} as named, a login {@code bash}, nothing added: the server's sessions. */
    public static Folder asNamed(Path path) {
      return new Folder(path, List.of("bash", "-l"), Map.of());
    }

    /**
     * A session on this box itself, in {@code named}: this process's home when nothing is named,
     * else the folder named by its absolute path, which must be one here; refused for a person
     * otherwise, the folder named and nothing else.
     */
    public static Folder onThisBox(Path named) throws IOException {
      if (named.toString().isEmpty()) {
        return asNamed(Path.of(System.getProperty("user.home")));
      }
      if (!named.isAbsolute() || !Files.isDirectory(named)) {
        throw Remedied.io(
            "There is no folder '" + named + "' on this box to open the terminal in.",
            Remedy.of("Open it in a folder that exists here, or name none to open it at home."));
      }
      return asNamed(named);
    }

    /** The command a session that asked for {@code requested} runs here. */
    public List<String> command(List<String> requested) {
      return requested.isEmpty() ? shell : requested;
    }
  }
}
