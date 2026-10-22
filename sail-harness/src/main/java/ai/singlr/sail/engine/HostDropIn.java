/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.engine;

import java.util.List;
import java.util.Objects;

/**
 * A config file sail owns under {@code /etc}, and what takes it in once written: {@code reload} is
 * shell that runs after the write, empty when the reader reads the file on its own. The content
 * reaches bash as an argument, never as script text, and lands atomically with mode 0644.
 */
public record HostDropIn(String name, String path, String content, String reload) {

  /** {@code $1} is the content, {@code $2} the path. */
  static final String WRITE_SCRIPT =
      """
      set -eu
      mkdir -p "$(dirname -- "$2")"
      printf '%s' "$1" > "$2.tmp"
      chmod 0644 "$2.tmp"
      mv -f -- "$2.tmp" "$2"
      """;

  public HostDropIn {
    Objects.requireNonNull(name, "name");
    Objects.requireNonNull(path, "path");
    Objects.requireNonNull(content, "content");
    Objects.requireNonNull(reload, "reload");
  }

  /** The command that installs this file at {@code target} on the machine it runs on. */
  public List<String> installCommand(String target) {
    return List.of("bash", "-c", WRITE_SCRIPT + reload, "bash", content, target);
  }
}
