/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.engine;

import ai.singlr.sail.common.Strings;
import ai.singlr.sail.config.YamlUtil;
import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.TimeoutException;

/**
 * Incus's storage pools: the one place that builds the command creating one, and that reads them
 * back as Incus has them. Sail hands Incus the whole source and makes nothing inside a pool itself;
 * Incus creates the zpool on a device or a file ({@code zpool create -m none -O compression=on},
 * never {@code -f}: Sail never passes {@code source.wipe}) and owns every dataset in it.
 */
public final class IncusStorage {

  private static final Duration CREATE_TIMEOUT = Duration.ofMinutes(3);

  private final ShellExec shell;

  public IncusStorage(ShellExec shell) {
    this.shell = shell;
  }

  /**
   * A pool as Incus has it: its {@code source} is what it was made from, the device or file for a
   * zfs pool, the directory for a dir pool; {@code zpool} is the zpool a zfs pool is, null for
   * another driver; {@code space} is empty when Incus cannot measure it.
   */
  public record Pool(
      String name, String driver, String source, String zpool, Optional<Space> space) {

    /** Refuses a pool of {@code driver} under this pool's name, which Incus has with another. */
    public void requireDriver(String driver) {
      if (!this.driver.equals(driver)) {
        throw new IllegalArgumentException(
            "Incus already has a "
                + this.driver
                + " pool named '"
                + name
                + "', so no "
                + driver
                + " pool can take that name. Name the new pool with --pool <name>.");
      }
    }

    /** The pool as one line of {@code host status}. */
    public String summary() {
      var measured =
          space
              .map(
                  s ->
                      Strings.gibibytes(s.total() - s.used())
                          + " free of "
                          + Strings.gibibytes(s.total()))
              .orElse("free space unknown");
      return name + " (" + driver + ", " + source + "): " + measured;
    }
  }

  /** A pool's size and what is used of it, in bytes. */
  public record Space(long used, long total) {}

  /** The command that creates pool {@code name} from {@code source}. */
  static List<String> createCommand(String name, PoolSource source) {
    var command = new ArrayList<>(List.of("incus", "storage", "create", name, source.driver()));
    switch (source) {
      case PoolSource.Dir dir -> {}
      case PoolSource.Device device -> command.add("source=" + device.path());
      case PoolSource.File file -> command.add("size=" + file.sizeMb() + "MiB");
      case PoolSource.Zpool zpool ->
          throw new IllegalStateException(
              "Incus's pool '"
                  + name
                  + "' on zpool "
                  + zpool.name()
                  + " is gone since this run began, and Sail makes no pool from a zpool it did"
                  + " not make: run this command again.");
    }
    return List.copyOf(command);
  }

  /** Creates pool {@code name} from {@code source}, answering as Incus did. */
  public ShellExec.Result create(String name, PoolSource source)
      throws IOException, InterruptedException, TimeoutException {
    return shell.exec(createCommand(name, source), null, CREATE_TIMEOUT);
  }

  /** Whether {@code failed}, a create's answer, says incusd does not see the ZFS tools. */
  static boolean zfsUnseen(ShellExec.Result failed) {
    return failed.stderr().contains("Required tool '");
  }

  /** Every pool Incus has, or why it could not say. */
  public List<Pool> pools() throws IOException, InterruptedException, TimeoutException {
    var listed = shell.exec(List.of("incus", "storage", "list", "--format", "json"));
    if (!listed.ok()) {
      throw new IOException("incus storage list failed: " + listed.reason());
    }
    var pools = new ArrayList<Pool>();
    for (var entry : YamlUtil.parseList(listed.stdout())) {
      var name = String.valueOf(entry.get("name"));
      var config = entry.get("config") instanceof Map<?, ?> map ? map : Map.of();
      pools.add(
          new Pool(
              name,
              String.valueOf(entry.get("driver")),
              source(config),
              (String) config.get("zfs.pool_name"),
              space(name)));
    }
    return pools;
  }

  /** Every pool Incus has; none where Incus does not answer, as on a box it is not on yet. */
  public List<Pool> poolsOrNone() throws InterruptedException {
    try {
      return pools();
    } catch (IOException | TimeoutException e) {
      return List.of();
    }
  }

  /** The pool named {@code name}, when Incus has one. */
  public Optional<Pool> find(String name)
      throws IOException, InterruptedException, TimeoutException {
    return pools().stream().filter(pool -> pool.name().equals(name)).findFirst();
  }

  private static String source(Map<?, ?> config) {
    var initial = config.get("volatile.initial_source");
    return Objects.toString(
        initial != null && Strings.isNotBlank(initial.toString()) ? initial : config.get("source"),
        "unknown");
  }

  private Optional<Space> space(String name)
      throws IOException, InterruptedException, TimeoutException {
    var queried =
        shell.exec(List.of("incus", "query", "/1.0/storage-pools/" + name + "/resources"));
    var space = queried.ok() ? YamlUtil.parseMap(queried.stdout()).get("space") : null;
    if (space instanceof Map<?, ?> measured
        && measured.get("used") instanceof Number used
        && measured.get("total") instanceof Number total) {
      return Optional.of(new Space(used.longValue(), total.longValue()));
    }
    return Optional.empty();
  }
}
