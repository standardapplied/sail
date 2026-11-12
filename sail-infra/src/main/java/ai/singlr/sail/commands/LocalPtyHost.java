/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.commands;

import ai.singlr.sail.common.Remedy;
import ai.singlr.sail.pty.Account;
import ai.singlr.sail.pty.PtyEvents;
import ai.singlr.sail.pty.PtySessionHost;
import ai.singlr.sail.pty.SdNotify;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFileAttributes;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * The terminal host on a person's own machine ({@code sail _pty-host --local}): the server's {@link
 * PtySessionHost}, wired with {@link LocalPtyAccess} for its one person and their folders, events
 * that go nowhere, no descriptor store to outlive it, no TCP door, and the binary's own {@code
 * _pty-child} as every session's shim. It opens no database, runs no {@code incus} and binds no
 * port.
 *
 * <p>It serves {@code ~/.sail/local} in the home the account database names. That directory and
 * {@code ~/.sail} above it are made owner-only when absent, and must each be a directory of the
 * person's own that no one else may open, read without following a link, before anything is made in
 * them: whoever could replace either could take the socket the app connects to.
 *
 * <p>The host holds a lock in its directory ({@link #LOCK}) for as long as it runs, which the
 * system lets go of when it dies: a second host for the same person finds it held and leaves the
 * first alone, and a host that finds it free replaces the socket a dead one left. The lock is taken
 * before the socket is touched and let go only after the socket and the sessions' files are gone,
 * so a successor never loses its socket to the host it replaced. Within one process a second claim
 * is refused before the file is opened again, since closing any channel on it would let the first
 * claim's lock go.
 */
final class LocalPtyHost implements AutoCloseable {

  /**
   * The longest socket path the host binds on every system it runs on: macOS's {@code sun_path}
   * holds 104 bytes, of which the JDK keeps two.
   */
  static final int MAX_SOCKET_PATH_BYTES = 102;

  static final String LOCK = "pty.lock";

  /** What a ticket request hears: this host has no network door, and tickets are a server's. */
  static final Remedy NO_DOOR =
      Remedy.of(
          "This terminal host is this machine's own and listens on no network: a ticket to a"
              + " terminal comes from a server's terminal host.");

  private static final Set<PosixFilePermission> OWNER_ONLY =
      PosixFilePermissions.fromString("rwx------");

  private static final Set<Path> HELD = ConcurrentHashMap.newKeySet();

  private final PtySessionHost host;
  private final Path dir;
  private final FileChannel claim;

  private LocalPtyHost(PtySessionHost host, Path dir, FileChannel claim) {
    this.host = host;
    this.dir = dir;
    this.claim = claim;
  }

  /** Where the local host of {@code account} keeps its socket and sessions. */
  static Path dirOf(Account account) {
    return account.home().resolve(".sail").resolve("local");
  }

  /** The socket the local host of {@code account} listens on. */
  static Path socketOf(Account account) {
    return dirOf(account).resolve("pty.sock");
  }

  /**
   * Starts the host for {@code access}'s person, every session spawned under {@code shim}. Refused
   * before anything is made when the socket's path is too long for every system or the account's
   * login shell cannot be run; refused before anything is made in a directory that is not the
   * person's alone; refused when another host holds the directory.
   */
  static LocalPtyHost start(LocalPtyAccess access, Function<Path, List<String>> shim)
      throws IOException {
    var account = access.account();
    var dir = dirOf(account);
    var socket = socketOf(account);
    var bytes = socket.toString().getBytes(StandardCharsets.UTF_8).length;
    if (bytes > MAX_SOCKET_PATH_BYTES) {
      throw new IOException(
          "This machine's terminal host cannot listen at "
              + socket
              + ": the path is "
              + bytes
              + " bytes, and a unix socket's path may be at most "
              + MAX_SOCKET_PATH_BYTES
              + " bytes on every system the host runs on. Run it as an account whose home"
              + " directory is shorter.");
    }
    if (!Files.isRegularFile(account.shell()) || !Files.isExecutable(account.shell())) {
      throw new IOException(
          account.name()
              + "'s login shell, "
              + account.shell()
              + " as the account database names it, is not a program that can be run, so this"
              + " machine's terminal host has nothing to open a session with. Set one with 'chsh"
              + " -s <shell>'.");
    }
    requirePrivate(account, dir, dir.getParent());
    requirePrivate(account, dir, dir);
    var claim = claim(dir);
    if (claim == null) {
      throw new IOException(
          "A terminal host already serves "
              + account.name()
              + " on this machine, at "
              + socket
              + ": connect to it there, or stop it before starting another.");
    }
    try {
      return new LocalPtyHost(
          PtyHostCommand.startHost(
              socket,
              dir.resolve("sessions"),
              access,
              access,
              PtyEvents.NONE,
              new PtySessionHost.Handoff(SdNotify.NONE, Map.of(), shim),
              PtySessionHost.Network.closed(NO_DOOR)),
          dir,
          claim);
    } catch (IOException | RuntimeException failed) {
      release(dir, claim);
      throw failed;
    }
  }

  /**
   * Makes {@code path} owner-only when it is absent, then refuses it unless it is, read without
   * following a link, a directory {@code account} owns that no one else may open.
   */
  private static void requirePrivate(Account account, Path dir, Path path) throws IOException {
    try {
      Files.createDirectory(path, PosixFilePermissions.asFileAttribute(OWNER_ONLY));
    } catch (FileAlreadyExistsException present) {
      var unused = present;
    }
    var found = Files.readAttributes(path, PosixFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
    var owner = found.owner().getName();
    var shared = !OWNER_ONLY.containsAll(found.permissions());
    if (found.isDirectory() && owner.equals(account.name()) && !shared) {
      return;
    }
    var kind =
        found.isSymbolicLink() ? "a symbolic link" : found.isDirectory() ? "a directory" : "a file";
    var fix =
        !found.isDirectory()
            ? "Remove it, and the host makes the directory itself."
            : owner.equals(account.name())
                ? "Run 'chmod 700 " + path + "'."
                : "Remove it, or make it "
                    + account.name()
                    + "'s and run 'chmod 700 "
                    + path
                    + "'.";
    throw new IOException(
        "This machine's terminal host keeps its socket in "
            + dir
            + ", so "
            + path
            + " must be a directory that "
            + account.name()
            + " owns and no one else may open, and it is "
            + kind
            + " owned by "
            + owner
            + " with mode "
            + PosixFilePermissions.toString(found.permissions())
            + ". "
            + fix);
  }

  /** The lock on {@code dir}, held open; null when another host holds it. */
  private static FileChannel claim(Path dir) throws IOException {
    var lock = dir.resolve(LOCK).toAbsolutePath().normalize();
    if (!HELD.add(lock)) {
      return null;
    }
    FileChannel channel = null;
    try {
      channel = FileChannel.open(lock, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
      if (channel.tryLock() != null) {
        return channel;
      }
      channel.close();
      HELD.remove(lock);
      return null;
    } catch (IOException | RuntimeException failed) {
      if (channel != null) {
        channel.close();
      }
      HELD.remove(lock);
      throw failed;
    }
  }

  private static void release(Path dir, FileChannel claim) throws IOException {
    try {
      claim.close();
    } finally {
      HELD.remove(dir.resolve(LOCK).toAbsolutePath().normalize());
    }
  }

  PtySessionHost host() {
    return host;
  }

  Path socket() {
    return dir.resolve("pty.sock");
  }

  /**
   * Stops for good: every session ends, the socket goes, the sessions' transcripts and sidecars are
   * deleted, and then the lock is let go.
   */
  @Override
  public void close() {
    try {
      host.stop(PtyHostCommand.STOPPED_REASON);
      var sessions = dir.resolve("sessions");
      if (Files.isDirectory(sessions, LinkOption.NOFOLLOW_LINKS)) {
        try (var files = Files.walk(sessions)) {
          for (var file : files.sorted(Comparator.reverseOrder()).toList()) {
            Files.deleteIfExists(file);
          }
        }
      }
    } catch (IOException e) {
      throw new UncheckedIOException("removing the local host's sessions failed", e);
    } finally {
      try {
        release(dir, claim);
      } catch (IOException e) {
        throw new UncheckedIOException("letting go of the local host's lock failed", e);
      }
    }
  }
}
