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
 * A config file sail owns under {@code /etc}, and what takes it in once written: {@code reload} is
 * shell that runs after the write, empty when the reader reads the file on its own. The content
 * reaches bash as an argument, never as script text, and lands atomically with mode 0644: each
 * writer renames its own temporary file, so concurrent installs never publish a partial one.
 */
public record HostDropIn(String name, String path, String content, String reload) {

  /** {@code $1} is the content, {@code $2} the path. */
  static final String WRITE_SCRIPT =
      """
      set -eu
      mkdir -p "$(dirname -- "$2")"
      tmp=$(mktemp -- "$2.tmp.XXXXXX")
      trap 'rm -f -- "$tmp"' EXIT
      printf '%s' "$1" > "$tmp"
      chmod 0644 "$tmp"
      mv -f -- "$tmp" "$2"
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

  /** Installs this file at its own path on the machine {@code shell} runs on, or throws. */
  public void install(ShellExec shell) throws IOException, InterruptedException, TimeoutException {
    install(shell, path);
  }

  /** Installs this file at {@code target}, written and taken in, or throws naming the target. */
  public void install(ShellExec shell, String target)
      throws IOException, InterruptedException, TimeoutException {
    var result = shell.exec(installCommand(target));
    if (!result.ok()) {
      throw new IOException("Failed to install " + target + ": " + result.reason());
    }
  }
}
