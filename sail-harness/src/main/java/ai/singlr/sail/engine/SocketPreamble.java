/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.engine;

import ai.singlr.sail.common.Remedy;

/**
 * The bash an in-container helper on the PATH starts from: the project from the container's
 * hostname, the credential it presents, and an {@code api} that calls the host's API over the
 * bind-mounted socket. A sail-launched session presents the run credential injected at launch
 * ({@code SAIL_RUN_CREDENTIAL}); any other session, a person's shell or an agent they run there,
 * falls back to the box's ambient credential beside the socket. Who is acting is decided on the
 * host from the credential; nothing here names an actor. Written once, so every helper finds its
 * credential the same way.
 */
public final class SocketPreamble {

  private static final String PREAMBLE =
      """
      SOCKET="__SAIL_API_SOCKET__"
      PROJECT="$(hostname)"
      CREDENTIAL="${SAIL_RUN_CREDENTIAL:-}"
      BOX_CREDENTIAL_FILE="$(dirname "$SOCKET")/box.credential"

      die() { echo "__TOOL__: $*" >&2; exit 1; }
      [ -S "$SOCKET" ] || die "API socket not found at $SOCKET (is the project running?)"
      if [ -z "$CREDENTIAL" ] && [ -r "$BOX_CREDENTIAL_FILE" ]; then
        CREDENTIAL="$(tr -d '[:space:]' < "$BOX_CREDENTIAL_FILE")"
      fi
      [ -n "$CREDENTIAL" ] || die "no credential: SAIL_RUN_CREDENTIAL is unset and $BOX_CREDENTIAL_FILE is unreadable: this box has no sync handle. Give it one on the host: __SAIL_SET_HANDLE__"

      api() {
        curl --silent --show-error --fail-with-body --max-time 20 --unix-socket "$SOCKET" \\
          -H "Authorization: Bearer $CREDENTIAL" "$@"
      }
      """;

  private SocketPreamble() {}

  /** The preamble of the helper named {@code tool}, whose name prefixes every refusal it prints. */
  public static String of(String tool) {
    return PREAMBLE
        .replace("__TOOL__", tool)
        .replace("__SAIL_API_SOCKET__", SailPaths.apiSocketContainerPath().toString())
        .replace(
            "__SAIL_SET_HANDLE__",
            Remedy.of(Remedy.sail("host config set sync-handle", "<handle>")).plain());
  }
}
