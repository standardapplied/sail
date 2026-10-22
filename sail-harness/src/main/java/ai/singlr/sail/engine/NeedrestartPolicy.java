/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.engine;

import java.util.List;

/**
 * The needrestart snippet that keeps a library update from restarting Incus. Ubuntu's apt hook runs
 * needrestart in automatic mode after every unattended upgrade, and it restarts each service that
 * maps a replaced library: {@code incus.service} among them, and incusd kills every interactive
 * {@code incus exec} on shutdown, so every Sail terminal into a container ends. An {@code
 * override_rc} entry of 0 makes needrestart list the service as deferred and never run its restart.
 * {@code ^incus} also covers {@code incus-user} and {@code incus-lxcfs}; sail-api and the pty host
 * are left to restart onto the patched library, as they hand off by design. Written by {@code sail
 * host init} and by {@code sail migrate} on every upgrade; harmless on a box without needrestart.
 */
public final class NeedrestartPolicy {

  /** Read after the distro's own snippets, so none of theirs shadows it. */
  public static final String DROP_IN_PATH = "/etc/needrestart/conf.d/50-sail.conf";

  private static final String CONTENT =
      """
      # Managed by sail. A library update must never restart the container daemon: incusd kills
      # every interactive `incus exec` on shutdown, and every Sail terminal into a container is one.
      # Restart incus on purpose, when no terminal into a container is open.
      $nrconf{override_rc}{qr(^incus)} = 0;
      """;

  /**
   * Writes {@code $1} to {@code $2} atomically with mode 0644, creating the directory. The content
   * is an argument, never part of the script text.
   */
  static final String INSTALL_SCRIPT =
      """
      set -eu
      mkdir -p "$(dirname -- "$2")"
      printf '%s' "$1" > "$2.tmp"
      chmod 0644 "$2.tmp"
      mv -f -- "$2.tmp" "$2"
      """;

  private NeedrestartPolicy() {}

  /** The snippet's content. Pure function. */
  public static String content() {
    return CONTENT;
  }

  /** The command that installs the snippet at {@code path} on the machine it runs on. */
  public static List<String> installCommand(String path) {
    return List.of("bash", "-c", INSTALL_SCRIPT, "bash", CONTENT, path);
  }
}
