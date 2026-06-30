/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.engine;

/**
 * Decides whether the control plane may bind a given address. The API is a single-trust-level
 * surface served over plaintext HTTP — every token can dispatch agents (code execution in
 * containers) — so a non-loopback bind exposes that to the network and must be opted into
 * explicitly. Resolved without I/O so the guard is unit-tested and shared between the install-time
 * check and the daemon entrypoint.
 */
public final class BindPolicy {

  private BindPolicy() {}

  public static boolean isLoopback(String host) {
    return "127.0.0.1".equals(host) || "localhost".equals(host) || "::1".equals(host);
  }

  public static void requireBindable(String host, boolean allowRemote) {
    if (!isLoopback(host) && !allowRemote) {
      throw new IllegalArgumentException(
          "Refusing to bind '"
              + host
              + "': the API is plaintext HTTP and any token can dispatch agents (code execution in"
              + " containers). Bind loopback (the default), or pass --allow-remote to expose it on"
              + " the network behind a TLS reverse proxy with restricted access.");
    }
  }
}
