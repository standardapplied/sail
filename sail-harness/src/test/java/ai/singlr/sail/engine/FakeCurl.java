/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.engine;

/**
 * A {@code curl} stand-in for the in-container helpers' tests. Each call appends to {@code
 * $HOME/curl.argv} its whole argv, and to {@code $HOME/curl.log} its arguments after the {@code
 * --config} that {@code sail_curl} passes, then {@code " | "} and the config read from that
 * descriptor, so a test sees both what any process listing shows and the header that reached curl
 * outside it.
 */
final class FakeCurl {

  private static final String RECORD =
      """
      #!/bin/sh
      printf '%s\\n' "$0 $*" >> "$HOME/curl.argv"
      CONFIG=""
      if [ "${1:-}" = "--config" ]; then
        CONFIG="$(cat "$2")"
        shift 2
      fi
      printf '%s | %s\\n' "$*" "$CONFIG" >> "$HOME/curl.log"
      """;

  private FakeCurl() {}

  /** The stand-in, then {@code rest}: what it answers. */
  static String script(String rest) {
    return RECORD + rest;
  }

  /**
   * What {@code curl.log} shows after the arguments for a call that presented {@code credential}.
   */
  static String presenting(String credential) {
    return " | header = \"Authorization: Bearer " + credential + "\"";
  }
}
