/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.engine;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * One sync session's transport: an {@code ssh sail@main sail _sync} subprocess whose stdio is the
 * RPC pipe. The remote {@code _sync} server reads requests from this channel's {@link #writer} and
 * writes responses to its {@link #reader}; a {@link ai.singlr.sail.sync.SyncSession} drives both
 * ends. Like the rest of the gateway lane, password and keyboard-interactive auth are disabled so a
 * missing key fails fast instead of dangling a prompt for the locked {@code sail} account.
 */
public final class SshSyncChannel implements SyncOperations.Channel {

  private final Process process;
  private final InputStream reader;
  private final OutputStream writer;

  private SshSyncChannel(Process process) {
    this.process = process;
    this.reader = new BufferedInputStream(process.getInputStream());
    this.writer = process.getOutputStream();
  }

  /** Opens a sync channel to {@code target} (e.g. {@code sail@maindevbox}). */
  public static SshSyncChannel open(String target) throws IOException {
    var builder = new ProcessBuilder(sshCommand(target, SyncIdentity.resolve().orElse(null)));
    builder.redirectError(ProcessBuilder.Redirect.INHERIT);
    return new SshSyncChannel(builder.start());
  }

  /**
   * The {@code ssh} argument vector for a sync session. Like the rest of the gateway lane it
   * forbids password and keyboard-interactive auth, so a missing key fails fast rather than
   * dangling a prompt for the locked {@code sail} account. When {@code identity} is non-null the
   * lane pins {@code sail join}'s managed key with {@code IdentitiesOnly} so it never silently
   * falls back to an unrelated agent key.
   */
  static List<String> sshCommand(String target, Path identity) {
    var command =
        new ArrayList<>(
            List.of(
                "ssh",
                "-o",
                "PasswordAuthentication=no",
                "-o",
                "KbdInteractiveAuthentication=no",
                "-o",
                "BatchMode=yes",
                "-o",
                "ConnectTimeout=10",
                "-o",
                "ConnectionAttempts=1",
                "-o",
                "ServerAliveInterval=10",
                "-o",
                "ServerAliveCountMax=2"));
    if (identity != null) {
      command.add("-o");
      command.add("IdentitiesOnly=yes");
      command.add("-i");
      command.add(identity.toString());
    }
    command.add(target);
    command.add("sail");
    command.add("_sync");
    return List.copyOf(command);
  }

  public InputStream reader() {
    return reader;
  }

  public OutputStream writer() {
    return writer;
  }

  /** Closes this node's end of the pipe and waits for the remote {@code _sync} to exit. */
  @Override
  public void close() throws IOException {
    writer.close();
    reader.close();
    try {
      process.waitFor();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      process.destroy();
    }
  }
}
