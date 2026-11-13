/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.engine;

import ai.singlr.sail.config.HostYaml;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeoutException;

/**
 * What a pool is on now. For a zfs pool whose zpool is imported, the devices ZFS lists for it now
 * ({@code zpool list -vHP}), each whole-disk vdev as its disk, which ZFS itself names it without
 * {@code -P} ({@code zpool_vdev_name}: the path's last part, its partition dropped for a whole
 * disk); a device absent at import is its GUID, which names no device. Otherwise the source Incus
 * recorded when it made the pool, said to be as recorded when zpool says its zpool is not imported.
 * What a re-run compares and what {@code host init} and {@code host status} say.
 *
 * @param devices the devices, in the order ZFS or the record lists them; none for a pool Incus made
 *     from a zpool's name
 * @param mirror whether they are a mirror
 * @param source Incus's record of the source, said as it is where no device can be said
 * @param listed whether ZFS listed the devices; else they are Incus's record
 * @param unimported the zpool ZFS says is not imported, when the devices are Incus's record of it
 */
public record PoolMembers(
    List<String> devices, boolean mirror, String source, boolean listed, String unimported) {

  private static final List<String> PLAIN_NAMES =
      List.of(
          "env",
          "-u",
          "ZPOOL_VDEV_NAME_PATH",
          "-u",
          "ZPOOL_VDEV_NAME_GUID",
          "-u",
          "ZPOOL_VDEV_NAME_FOLLOW_LINKS",
          "zpool",
          "list",
          "-vH");

  public PoolMembers {
    devices = List.copyOf(devices);
  }

  /** What {@code pool} is on now: ZFS's members of its imported zpool, or Incus's record. */
  public static PoolMembers of(ShellExec shell, IncusStorage.Pool pool)
      throws InterruptedException {
    var recorded = IncusStorage.devices(pool.source());
    var mirror = pool.source().startsWith("mirror=");
    if (!HostYaml.BACKEND_ZFS.equals(pool.driver())) {
      return new PoolMembers(recorded, mirror, pool.source(), false, null);
    }
    var zpool = pool.zpool() == null ? pool.name() : pool.zpool();
    ShellExec.Result paths;
    ShellExec.Result names;
    try {
      paths = shell.exec(List.of("zpool", "list", "-vHP", zpool));
      if (!paths.ok()) {
        return new PoolMembers(
            recorded,
            mirror,
            pool.source(),
            false,
            paths.stderr().contains("no such pool") ? zpool : null);
      }
      var plain = new ArrayList<>(PLAIN_NAMES);
      plain.add(zpool);
      names = shell.exec(plain);
    } catch (IOException | TimeoutException e) {
      return new PoolMembers(recorded, mirror, pool.source(), false, null);
    }
    var rows = rows(paths.stdout());
    var plainRows = names.ok() ? rows(names.stdout()) : List.<String>of();
    var devices = new ArrayList<String>();
    for (var i = 0; i < rows.size(); i++) {
      var path = rows.get(i);
      if (path.startsWith("/") || path.matches("[0-9]+")) {
        devices.add(device(path, plainRows.size() == rows.size() ? plainRows.get(i) : ""));
      }
    }
    if (devices.isEmpty()) {
      return new PoolMembers(recorded, mirror, pool.source(), false, null);
    }
    return new PoolMembers(
        devices,
        rows.stream().anyMatch(row -> row.startsWith("mirror-")),
        pool.source(),
        true,
        null);
  }

  /** The vdev name of each row after the pool's own, its first field. */
  private static List<String> rows(String listed) {
    return listed.lines().skip(1).map(line -> line.strip().split("\\s+")[0]).toList();
  }

  /**
   * The device a vdev of path {@code path} is on: the disk where ZFS names it {@code plain}, its
   * path's last part with the partition dropped, as for a whole disk; else the path as it is.
   */
  private static String device(String path, String plain) {
    var at = path.lastIndexOf('/');
    var last = path.substring(at + 1);
    return at > 0 && !plain.isEmpty() && !plain.equals(last) && last.startsWith(plain)
        ? path.substring(0, at + 1) + plain
        : path;
  }

  /** What the pool is on, as the operator reads it. */
  public String said() {
    var on =
        devices.size() > 1
            ? (mirror ? "a mirror of " : "")
                + String.join(", ", devices.subList(0, devices.size() - 1))
                + " and "
                + devices.getLast()
            : devices.isEmpty() ? source : devices.getFirst();
    return unimported == null
        ? on
        : on + " (as Incus recorded at creation: zpool " + unimported + " is not imported)";
  }
}
