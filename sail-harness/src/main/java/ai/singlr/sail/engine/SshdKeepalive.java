/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.engine;

import java.io.IOException;
import java.util.List;
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

  /** Where the drop-in lives; sshd's stock config includes {@code sshd_config.d/*.conf}. */
  public static final String DROP_IN_PATH = "/etc/ssh/sshd_config.d/10-sail.conf";

  private static final String CONTENT =
      """
      # Managed by sail. Notice a peer that vanished (a laptop that slept, a network that
      # changed) within a minute, so no session keeps a keyboard for a connection that died.
      ClientAliveInterval 15
      ClientAliveCountMax 3
      """;

  /**
   * The shell that installs the drop-in and reloads a running sshd: {@code $1} is the content,
   * {@code $2} the path. A box without sshd running keeps the file for when it starts.
   */
  static final String INSTALL_SCRIPT =
      """
      set -eu
      mkdir -p "$(dirname -- "$2")"
      printf '%s' "$1" > "$2"
      chmod 0644 "$2"
      if systemctl is-active --quiet ssh; then systemctl reload ssh; fi
      """;

  private final ShellExec shell;

  public SshdKeepalive(ShellExec shell) {
    this.shell = Objects.requireNonNull(shell, "shell");
  }

  /** The drop-in's content. Pure function. */
  public static String content() {
    return CONTENT;
  }

  /** The command that installs the drop-in at {@code path} on the machine it runs on. */
  public static List<String> installCommand(String path) {
    return List.of("bash", "-c", INSTALL_SCRIPT, "bash", CONTENT, path);
  }

  /** Idempotently installs the drop-in inside {@code container} as root and reloads its sshd. */
  public void install(String container) throws IOException, InterruptedException, TimeoutException {
    NameValidator.requireValidProjectName(container);
    var result = shell.exec(ContainerExec.asRoot(container, installCommand(DROP_IN_PATH)));
    if (!result.ok()) {
      throw new IOException(
          "Failed to install " + DROP_IN_PATH + " in " + container + ": " + result.stderr());
    }
  }
}
