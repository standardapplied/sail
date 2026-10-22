/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.engine;

/**
 * The needrestart snippet that keeps a library update from restarting Incus. Ubuntu's apt hook runs
 * needrestart in automatic mode after every unattended upgrade, and it restarts each service that
 * maps a replaced library: {@code incus.service} among them, and incusd kills every interactive
 * {@code incus exec} on shutdown, so every Sail terminal into a container ends. An {@code
 * override_rc} entry of 0 makes needrestart list the service as deferred and never run its restart.
 * {@code ^incus} also covers {@code incus-user} and {@code incus-lxcfs}; sail-api and the pty host
 * are left to restart onto the patched library, as they hand off by design. The file sorts after
 * the distro's own snippets, so none of theirs shadows it. Harmless on a box without needrestart.
 */
public final class NeedrestartPolicy {

  public static final HostDropIn DROP_IN =
      new HostDropIn(
          "needrestart policy",
          "/etc/needrestart/conf.d/50-sail.conf",
          """
          # Managed by sail. A library update must never restart the container daemon: incusd kills
          # every interactive `incus exec` on shutdown, and every Sail terminal into a container is one.
          # Restart incus on purpose, when no terminal into a container is open.
          $nrconf{override_rc}{qr(^incus)} = 0;
          """,
          "");

  private NeedrestartPolicy() {}
}
