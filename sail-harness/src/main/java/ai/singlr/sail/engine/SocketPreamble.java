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
 * credential the same way: the lookup is POSIX ({@link #credentialLookup}), so the session report,
 * a {@code sh} hook that fails silently, finds it by the same lines.
 */
public final class SocketPreamble {

  private static final String LOOKUP =
      """
      SOCKET="__SAIL_API_SOCKET__"
      CREDENTIAL="${SAIL_RUN_CREDENTIAL:-}"
      BOX_CREDENTIAL_FILE="$(dirname "$SOCKET")/box.credential"
      if [ -z "$CREDENTIAL" ] && [ -r "$BOX_CREDENTIAL_FILE" ]; then
        CREDENTIAL="$(tr -d '[:space:]' < "$BOX_CREDENTIAL_FILE")"
      fi
      """;

  private static final String PREAMBLE =
      """
      __LOOKUP__PROJECT="$(hostname)"

      die() { echo "__TOOL__: $*" >&2; exit 1; }
      [ -S "$SOCKET" ] || die "API socket not found at $SOCKET (is the project running?)"
      [ -n "$CREDENTIAL" ] || die "no credential: SAIL_RUN_CREDENTIAL is unset and $BOX_CREDENTIAL_FILE is unreadable: this box has no sync handle. Give it one on the host: __SAIL_SET_HANDLE__"

      api() {
        local status=0
        curl --silent --fail-with-body --max-time 20 --unix-socket "$SOCKET" \\
          -H "Authorization: Bearer $CREDENTIAL" "$@" || status=$?
        echo
        case $status in
          0) ;;
          22) exit 1 ;;
          *) die "the sail API at $SOCKET did not answer (curl exit $status)" ;;
        esac
      }
      """;

  private SocketPreamble() {}

  /**
   * The POSIX lines that set {@code SOCKET} and the {@code CREDENTIAL} a helper presents: the run
   * credential injected at launch, else the box's ambient credential beside the socket when it is
   * readable, else none. They fail nothing; each helper decides what a missing one means.
   */
  public static String credentialLookup() {
    return LOOKUP.replace("__SAIL_API_SOCKET__", SailPaths.apiSocketContainerPath().toString());
  }

  /** The preamble of the helper named {@code tool}, whose name prefixes every refusal it prints. */
  public static String of(String tool) {
    return PREAMBLE
        .replace("__LOOKUP__", credentialLookup())
        .replace("__TOOL__", tool)
        .replace(
            "__SAIL_SET_HANDLE__",
            Remedy.of(Remedy.sail("host config set sync-handle", "<handle>")).plain());
  }
}
