/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.engine;

import java.io.IOException;
import java.util.Objects;
import java.util.concurrent.TimeoutException;

/**
 * The sshd drop-in that makes a box notice a vanished peer. Ubuntu's sshd ships with no client
 * keepalive, so a laptop that sleeps or changes networks leaves its SSH connection — and the pty
 * session host attachment riding it, write token included — parked for TCP's two-hour keepalive.
 * With {@code ClientAliveInterval 15} and {@code ClientAliveCountMax 3} the dead connection is
 * closed within a minute. One payload serves both the host ({@code sail migrate} writes it on every
 * upgrade) and every project container (installed with the rest of sail's machinery).
 */
public final class SshdKeepalive {

  /** The drop-in; sshd's stock config includes {@code sshd_config.d/*.conf}. */
  public static final HostDropIn DROP_IN =
      new HostDropIn(
          "sshd keepalive drop-in",
          "/etc/ssh/sshd_config.d/10-sail.conf",
          """
          # Managed by sail. Notice a peer that vanished (a laptop that slept, a network that
          # changed) within a minute, so no session keeps a keyboard for a connection that died.
          ClientAliveInterval 15
          ClientAliveCountMax 3
          """,
          "if systemctl is-active --quiet ssh; then systemctl reload ssh; fi\n");

  private final ShellExec shell;

  public SshdKeepalive(ShellExec shell) {
    this.shell = Objects.requireNonNull(shell, "shell");
  }

  /** Idempotently installs the drop-in inside {@code container} as root and reloads its sshd. */
  public void install(String container) throws IOException, InterruptedException, TimeoutException {
    NameValidator.requireValidProjectName(container);
    var result =
        shell.exec(ContainerExec.asRoot(container, DROP_IN.installCommand(DROP_IN.path())));
    if (!result.ok()) {
      throw new IOException(
          "Failed to install " + DROP_IN.path() + " in " + container + ": " + result.stderr());
    }
  }
}
