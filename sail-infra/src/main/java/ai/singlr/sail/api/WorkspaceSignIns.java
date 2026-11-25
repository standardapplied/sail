/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.api;

import ai.singlr.sail.engine.ContainerExec;
import ai.singlr.sail.engine.ShellExec;
import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeoutException;

/**
 * Which harnesses' own sign-ins a running workspace holds ({@code SignIn.file}), read as its dev
 * user in one command for them all: a regular file that is there and not empty, as {@code test -f}
 * and {@code test -s} stat it, through a link as the harness would read it. No file is opened or
 * read.
 */
final class WorkspaceSignIns {

  /** Prints each file argument, under {@code $HOME}, that is a regular file and not empty. */
  static final String HELD =
      "for file; do [ -f \"$HOME/$file\" ] && [ -s \"$HOME/$file\" ] && echo \"$file\"; done;"
          + " exit 0";

  /** How long the read waits on its workspace before the route answers without it. */
  static final Duration TIMEOUT = Duration.ofSeconds(5);

  private final ShellExec shell;

  WorkspaceSignIns(ShellExec shell) {
    this.shell = Objects.requireNonNull(shell, "shell");
  }

  /**
   * Which of {@code files} running workspace {@code instance} holds; empty when that cannot be
   * read: Incus did not answer in {@link #TIMEOUT}, or the command failed. Asked of no file, it
   * holds none, and nothing runs.
   */
  Optional<Set<String>> held(String instance, List<String> files) throws InterruptedException {
    if (files.isEmpty()) {
      return Optional.of(Set.of());
    }
    var command = new ArrayList<>(List.of("sh", "-c", HELD, "sail-sign-ins"));
    command.addAll(files);
    try {
      var read = shell.exec(ContainerExec.asDevUser(instance, command), null, TIMEOUT);
      return read.ok()
          ? Optional.of(Set.copyOf(read.stdout().lines().filter(files::contains).toList()))
          : Optional.empty();
    } catch (IOException | TimeoutException unanswered) {
      return Optional.empty();
    }
  }
}
