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

  /**
   * The one way a helper sends {@code $CREDENTIAL}: {@code sail_curl} runs {@code curl} with its
   * arguments and hands it the {@code Authorization} header as a config on file descriptor 3, fed
   * by the shell's built-in {@code printf} through a pipe, so the credential is in no process's
   * argv and in no file; the caller's stdin stays curl's, for a body sent as {@code @-}. A
   * credential with anything but letters, digits and {@code _} (every one Sail mints) is refused
   * before curl runs, so none can carry a config line of its own.
   */
  private static final String CREDENTIAL_CURL =
      """
      sail_curl() {
        case $CREDENTIAL in *[!A-Za-z0-9_]*) echo "sail_curl: a malformed credential" >&2; return 2 ;; esac
        { printf 'header = "Authorization: Bearer %s"\\n' "$CREDENTIAL" \\
          | curl --config /dev/fd/3 "$@" 3<&0 <&4 4<&-; } 4<&0
      }
      """;

  private static final String LOOKUP =
      """
      SOCKET="__SAIL_API_SOCKET__"
      CREDENTIAL="${SAIL_RUN_CREDENTIAL:-}"
      BOX_CREDENTIAL_FILE="$(dirname "$SOCKET")/box.credential"
      if [ -z "$CREDENTIAL" ] && [ -r "$BOX_CREDENTIAL_FILE" ]; then
        CREDENTIAL="$(tr -d '[:space:]' < "$BOX_CREDENTIAL_FILE")"
      fi
      """
          + CREDENTIAL_CURL;

  private static final String PREAMBLE =
      """
      __LOOKUP__PROJECT="$(hostname)"

      die() { echo "__TOOL__: $*" >&2; exit 1; }
      [ -S "$SOCKET" ] || die "API socket not found at $SOCKET (is the project running?)"
      [ -n "$CREDENTIAL" ] || die "no credential: SAIL_RUN_CREDENTIAL is unset and $BOX_CREDENTIAL_FILE is unreadable: this box has no sync handle. Give it one on the host: __SAIL_SET_HANDLE__"

      api() {
        local status=0
        sail_curl --silent --fail-with-body --max-time 20 --unix-socket "$SOCKET" "$@" \\
          || status=$?
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
   * readable, else none; then {@link #credentialCurl}. They fail nothing; each helper decides what
   * a missing one means.
   */
  public static String credentialLookup() {
    return LOOKUP.replace("__SAIL_API_SOCKET__", SailPaths.apiSocketContainerPath().toString());
  }

  /**
   * The POSIX function {@code sail_curl}, every helper's only way to send {@code $CREDENTIAL}: it
   * never reaches an argv, an environment variable of a process it starts, or a file.
   */
  public static String credentialCurl() {
    return CREDENTIAL_CURL;
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
