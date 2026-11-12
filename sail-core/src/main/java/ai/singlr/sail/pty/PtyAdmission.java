/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.pty;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

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
   * Throws, with the reason, when {@code who} may not pin a session in {@code project} to {@code
   * room}.
   */
  void room(String room, String project, PtyIdentity.Person who) throws IOException;

  /**
   * The folder a session {@code who} opens names, as it runs there, or throws with the reason
   * before any process starts. The server takes it as named ({@link Folder#asNamed}).
   */
  default Folder folder(Path named, PtyIdentity.Person who) throws IOException {
    return Folder.asNamed(named);
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

    /** {@code path} as named, a login {@code bash}, nothing added: the server's sessions. */
    public static Folder asNamed(Path path) {
      return new Folder(path, List.of("bash", "-l"), Map.of());
    }

    /** The command a session that asked for {@code requested} runs here. */
    public List<String> command(List<String> requested) {
      return requested.isEmpty() ? shell : requested;
    }
  }
}
