/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.commands;

import ai.singlr.sail.pty.PtyEvents;
import ai.singlr.sail.pty.PtySessionHost;
import ai.singlr.sail.pty.SdNotify;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.channels.FileChannel;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Map;

/**
 * The terminal host on a person's own machine ({@code sail _pty-host --local}): the server's {@link
 * PtySessionHost}, wired with {@link LocalPtyAccess} for its one person and their folders, events
 * that go nowhere, no descriptor store to outlive it, no TCP door, and the binary's own {@code
 * _pty-child} as every session's shim. It opens no database, runs no {@code incus} and binds no
 * port.
 *
 * <p>It serves one owner-only directory, and holds a lock there ({@link #LOCK}) for as long as it
 * runs, which the system lets go of when it dies: a second host for the same person finds the lock
 * held and leaves the first alone, and a host that finds it free replaces the socket a dead one
 * left. The lock is taken before the socket is touched and let go only after the socket is gone, so
 * a successor never loses its socket to the host it replaced.
 */
final class LocalPtyHost implements AutoCloseable {

  /**
   * The longest socket path the host binds on every system it runs on: macOS's {@code sun_path}
   * holds 104 bytes, of which the JDK keeps two.
   */
  static final int MAX_SOCKET_PATH_BYTES = 102;

  static final String LOCK = "pty.lock";

  private final PtySessionHost host;
  private final FileChannel claim;

  private LocalPtyHost(PtySessionHost host, FileChannel claim) {
    this.host = host;
    this.claim = claim;
  }

  /** The socket a local host serving {@code dir} listens on. */
  static Path socketIn(Path dir) {
    return dir.resolve("pty.sock");
  }

  /**
   * Starts the host for {@code access}'s person in {@code dir}, refused before anything is made
   * when its socket's path is too long for every system, and when another host holds {@code dir}.
   */
  static LocalPtyHost start(Path dir, LocalPtyAccess access) throws IOException {
    var socket = socketIn(dir);
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
    Files.createDirectories(
        dir, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
    var claim = claim(dir);
    if (claim == null) {
      throw new IOException(
          "A terminal host already serves "
              + access.account().name()
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
              new PtySessionHost.Handoff(SdNotify.NONE, Map.of(), PtyHostCommand::shimCommand),
              PtySessionHost.Network.NONE),
          claim);
    } catch (IOException | RuntimeException failed) {
      claim.close();
      throw failed;
    }
  }

  /** The lock on {@code dir}, held open; null when another host holds it. */
  private static FileChannel claim(Path dir) throws IOException {
    var channel =
        FileChannel.open(dir.resolve(LOCK), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
    try {
      if (channel.tryLock() != null) {
        return channel;
      }
    } catch (OverlappingFileLockException heldInThisProcess) {
      var unused = heldInThisProcess;
    } catch (IOException | RuntimeException failed) {
      channel.close();
      throw failed;
    }
    channel.close();
    return null;
  }

  PtySessionHost host() {
    return host;
  }

  /** Stops for good: every session ends, the socket goes, and then the lock. */
  @Override
  public void close() {
    host.stop(PtyHostCommand.STOPPED_REASON);
    try {
      claim.close();
    } catch (IOException e) {
      throw new UncheckedIOException("letting go of the local host's lock failed", e);
    }
  }
}
