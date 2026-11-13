/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.engine;

import ai.singlr.sail.config.HostYaml;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeoutException;

/**
 * What a pool is on now. For a zfs pool whose zpool is imported, the data vdevs ZFS lists for it
 * now ({@code zpool list -vHP}, the rows before its first class header), each whole-disk vdev as
 * its disk, which ZFS itself names it without {@code -P} ({@code zpool_vdev_name}: the path's last
 * part, its partition dropped for a whole disk); a device absent at import is its GUID, which names
 * no device. Its log, cache, spare, special and dedup devices, which zpool lists under their own
 * headers and Incus never makes (an operator adds them by hand, {@code zpool add}), are apart from
 * them, as {@link #auxiliary}; they stay devices in use all the same ({@link ImportedPools}).
 * Otherwise the source Incus recorded when it made the pool, said to be as recorded when zpool says
 * its zpool is not imported or does not answer within {@link Zpool#BOUND}; after no answer zpool is
 * asked nothing more. What a re-run compares and what {@code host init} and {@code host status}
 * say.
 *
 * @param devices the data devices, in the order ZFS or the record lists them; none for a pool Incus
 *     made from a zpool's name
 * @param mirror whether they are a mirror
 * @param source Incus's record of the source, said as it is where no device can be said
 * @param listed whether ZFS listed the devices; else they are Incus's record
 * @param unimported the zpool ZFS says is not imported, when the devices are Incus's record of it
 * @param unanswered the zpool that did not answer, when the devices are Incus's record of it
 * @param auxiliary the other devices ZFS lists, by the class header zpool lists them under, in its
 *     order; none unless ZFS listed the devices
 */
public record PoolMembers(
    List<String> devices,
    boolean mirror,
    String source,
    boolean listed,
    String unimported,
    String unanswered,
    Map<String, List<String>> auxiliary) {

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

  private static final Map<String, String> KINDS = Map.of("logs", "log device", "spare", "spare");

  public PoolMembers {
    devices = List.copyOf(devices);
    auxiliary = Collections.unmodifiableMap(new LinkedHashMap<>(auxiliary));
  }

  /** What {@code pool} is on now: ZFS's members of its imported zpool, or Incus's record. */
  public static PoolMembers of(ShellExec shell, IncusStorage.Pool pool)
      throws InterruptedException {
    if (!HostYaml.BACKEND_ZFS.equals(pool.driver())) {
      return recorded(pool, null, null);
    }
    var zpool = pool.zpool() == null ? pool.name() : pool.zpool();
    ShellExec.Result paths;
    ShellExec.Result names;
    try {
      paths = Zpool.read(shell, List.of("zpool", "list", "-vHP", zpool));
      if (!paths.ok()) {
        return recorded(pool, paths.stderr().contains("no such pool") ? zpool : null, null);
      }
      var plain = new ArrayList<>(PLAIN_NAMES);
      plain.add(zpool);
      names = Zpool.read(shell, plain);
    } catch (TimeoutException e) {
      return recorded(pool, null, zpool);
    } catch (IOException e) {
      return recorded(pool, null, null);
    }
    var classes = ImportedPools.classes(paths.stdout().lines().toList());
    var plainClasses =
        names.ok()
            ? ImportedPools.classes(names.stdout().lines().toList())
            : Map.<String, List<String>>of();
    var auxiliary = new LinkedHashMap<String, List<String>>();
    classes.forEach(
        (kind, rows) ->
            auxiliary.put(kind, devices(rows, plainClasses.getOrDefault(kind, List.of()))));
    var data = auxiliary.remove(ImportedPools.DATA);
    if (data.isEmpty()) {
      return recorded(pool, null, null);
    }
    return new PoolMembers(
        data,
        classes.get(ImportedPools.DATA).stream().anyMatch(row -> row.startsWith("mirror-")),
        pool.source(),
        true,
        null,
        null,
        auxiliary);
  }

  /**
   * {@code pool} on the source Incus recorded, {@code unimported} the zpool not imported and {@code
   * unanswered} the one that did not answer, if so.
   */
  private static PoolMembers recorded(
      IncusStorage.Pool pool, String unimported, String unanswered) {
    return new PoolMembers(
        IncusStorage.devices(pool.source()),
        pool.source().startsWith("mirror="),
        pool.source(),
        false,
        unimported,
        unanswered,
        Map.of());
  }

  /**
   * The devices of a class's vdev rows: each that is a path or a GUID, a group such as {@code
   * mirror-0} left out, named as {@link #device} names it from the class's rows without {@code -P}.
   */
  private static List<String> devices(List<String> rows, List<String> plain) {
    var devices = new ArrayList<String>();
    for (var i = 0; i < rows.size(); i++) {
      var path = rows.get(i);
      if (path.startsWith("/") || path.matches("[0-9]+")) {
        devices.add(device(path, plain.size() == rows.size() ? plain.get(i) : ""));
      }
    }
    return devices;
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

  /** What the pool is on, as the operator reads it: its data devices. */
  public String said() {
    var on =
        devices.isEmpty()
            ? source
            : (mirror && devices.size() > 1 ? "a mirror of " : "") + listing(devices);
    var unread =
        unimported != null
            ? "zpool " + unimported + " is not imported"
            : "zpool " + unanswered + " " + Zpool.NO_ANSWER;
    return unimported == null && unanswered == null
        ? on
        : on + " (as Incus recorded at creation: " + unread + ")";
  }

  /**
   * These members, refused where zpool did not answer: what the pool is on, and so whether a device
   * is in use, cannot be told, and nothing is to change on a guess.
   */
  public PoolMembers requireAnswered() {
    if (unanswered == null) {
      return this;
    }
    throw new IllegalStateException(
        "zpool "
            + Zpool.NO_ANSWER
            + " for zpool "
            + unanswered
            + ", so Sail cannot tell what the pool is on or whether a device is in use. Nothing was"
            + " changed; run this again once zpool answers.");
  }

  /**
   * The {@link #auxiliary} devices, said apart from what the pool is on, as in {@code plus a log
   * device /dev/sde}; empty for a pool with none.
   */
  public String besides() {
    var kinds = new ArrayList<String>();
    auxiliary.forEach(
        (kind, held) -> {
          var noun = KINDS.getOrDefault(kind, kind + " device");
          kinds.add((held.size() == 1 ? "a " + noun : noun + "s") + " " + listing(held));
        });
    return kinds.isEmpty() ? "" : "plus " + listing(kinds);
  }

  /** {@code items} as a list read aloud: {@code a}, {@code a and b}, {@code a, b and c}. */
  private static String listing(List<String> items) {
    return items.size() == 1
        ? items.getFirst()
        : String.join(", ", items.subList(0, items.size() - 1)) + " and " + items.getLast();
  }
}
