/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.engine;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeoutException;

/**
 * The one door through which sail writes a project container's Incus config and devices. The incus
 * CLI reads an instance's config with its ETag and puts it back whole, so a write landing between
 * another writer's read and put is refused. Every write here holds the container's lock file, the
 * one {@link ContainerSailSetup} reconciles under, for that write alone or for one
 * read-modify-write ({@link #atomically}), never across a long step: the API server's boot sweep
 * waits for one write at most, and different containers are written in parallel. A snapshot restore
 * is such a write, putting the config and devices back whole with the filesystem. A dry run changes
 * nothing and takes no lock.
 */
public final class ContainerConfig {

  private final ShellExec shell;
  private final Path locks;

  /**
   * @param locks the box's lock directory, where the API server and a root CLI meet
   */
  public ContainerConfig(ShellExec shell, Path locks) {
    this.shell = Objects.requireNonNull(shell, "shell");
    this.locks = Objects.requireNonNull(locks, "locks");
  }

  /** Takes {@code container}'s lock under {@code locks}, waiting while another holds it. */
  public static FileMutex hold(Path locks, String container) throws IOException {
    NameValidator.requireValidProjectName(container);
    return FileMutex.acquire(locks.resolve(container + ".machinery.lock"));
  }

  /** {@code incus config set <container> <assignments…>}, each a {@code key=value}. */
  public ShellExec.Result set(String container, List<String> assignments)
      throws IOException, InterruptedException, TimeoutException {
    var command = new ArrayList<>(List.of("incus", "config", "set", container));
    command.addAll(assignments);
    return atomically(container, () -> shell.exec(command));
  }

  /** {@code incus config device <verb> <container> <args…>}. */
  public ShellExec.Result device(String container, String verb, List<String> args)
      throws IOException, InterruptedException, TimeoutException {
    var command = new ArrayList<>(List.of("incus", "config", "device", verb, container));
    command.addAll(args);
    return atomically(container, () -> shell.exec(command));
  }

  /**
   * {@code incus snapshot restore <container> <snapshot>}, waiting up to {@code timeout} for it.
   */
  public ShellExec.Result restore(String container, String snapshot, Duration timeout)
      throws IOException, InterruptedException, TimeoutException {
    return atomically(
        container,
        () ->
            shell.exec(
                List.of("incus", "snapshot", "restore", container, snapshot), null, timeout));
  }

  /** Runs {@code write}, one read-modify-write of {@code container}'s config, under its lock. */
  public <T> T atomically(String container, Write<T> write)
      throws IOException, InterruptedException, TimeoutException {
    NameValidator.requireValidProjectName(container);
    if (shell.isDryRun()) {
      return write.run();
    }
    try (var held = hold(locks, container)) {
      return write.run();
    }
  }

  /** One write of a container's config, or a read and the writes that follow from it. */
  @FunctionalInterface
  public interface Write<T> {
    T run() throws IOException, InterruptedException, TimeoutException;
  }
}
