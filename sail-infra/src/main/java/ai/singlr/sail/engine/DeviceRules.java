/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.engine;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

/** What lsblk and the probes show that keeps a pool off a device, each said as a reason. */
final class DeviceRules {

  private static final Set<String> KINDS = Set.of(BlockDevice.DISK, BlockDevice.PARTITION);
  private static final Set<String> ARRAYS =
      Set.of("raid0", "raid1", "raid4", "raid5", "raid6", "raid10", "linear", "md");
  private static final Map<String, String> KIND =
      Map.of("lvm", "an LVM logical volume", "crypt", "an open LUKS volume");
  private static final Map<String, String> NAMED =
      Map.of("zram", "a zram device", "nbd", "an nbd device", "zd", "a ZFS volume");
  private static final Set<String> ACCEPTED =
      Set.of(
          "0fc63daf-8483-4772-8e79-3d69d8477de4",
          "0x83",
          "6a898cc3-1dd2-11b2-99a6-080020736631",
          "0xbf");
  private static final String SWAP = "[SWAP]";
  private static final long SECTOR = 512;
  private static final String ZFS = "zfs_member";
  private static final Set<String> HELD = Set.of("bcache", "ceph_bluestore", "crypto_LUKS");
  private static final Map<String, String> MEMBERS =
      Map.of(
          "linux_raid_member",
          "is a RAID member: stop its array and clear it with mdadm --zero-superblock %s yourself",
          "LVM2_member",
          "is an LVM physical volume: move its volume group off it and clear it with pvremove %s"
              + " yourself",
          "crypto_LUKS",
          "holds a LUKS volume: close it and erase it with cryptsetup erase %s yourself");

  private DeviceRules() {}

  /**
   * Each use lsblk shows that keeps any pool off {@code device}, a disk or something on {@code
   * disk}.
   */
  static List<String> uses(BlockDevice device, BlockDevice disk) {
    var uses = new LinkedHashSet<String>();
    if (!KINDS.contains(device.type())) {
      uses.add("it is " + kind(device) + ", not a disk or a partition");
    }
    if (BlockDevice.DISK.equals(device.type()) && device != disk) {
      uses.add("it is a disk built on " + disk.path() + ", not a plain disk");
    }
    var parent = BlockTree.parent(disk, device);
    if (parent.isPresent() && !(parent.get() == disk && BlockDevice.DISK.equals(disk.type()))) {
      uses.add(
          "it is a partition of "
              + parent.get().path()
              + " ("
              + parent.get().type()
              + "), not of a disk");
    }
    if (parent.map(found -> found == disk).orElse(device == disk)) {
      uses.addAll(extents(device, disk));
    }
    if (disk.removable()) {
      uses.add(disk.path() + " is removable");
    }
    if (device.empty()) {
      uses.add("it has no size");
    }
    var name = disk.path().substring(disk.path().lastIndexOf('/') + 1);
    var named = NAMED.get(name.replaceAll("[0-9]+$", ""));
    if (named != null) {
      uses.add("it is " + named);
    }
    device
        .subtree()
        .forEach(
            node -> {
              if (node == device && parent.isPresent()) {
                typed(node, parent.get()).ifPresent(uses::add);
              }
              node.mountpoints().forEach(mount -> uses.add(mounted(node, mount)));
              if (member(node.fstype()) && !ZFS.equals(node.fstype())) {
                uses.add(membership(node.path(), node.fstype(), null, Set.of()));
              }
              if (node != device && !node.partition()) {
                uses.add(node.path() + " (" + node.type() + ") is built on it");
              }
            });
    return List.copyOf(uses);
  }

  /**
   * Why where a partition lies on {@code disk} keeps a pool off {@code device}, the disk or a
   * partition of it: no start lsblk reads, an extent past the end of the disk, or one that overlaps
   * another partition of it, which the disk and any partition of a disk with an overlapping pair
   * share, since writing either destroys the other.
   */
  static List<String> extents(BlockDevice device, BlockDevice disk) {
    if (device != disk && device.start() == null) {
      return List.of(
          device.path()
              + " has no start lsblk reads: run sudo udevadm settle, then run this again");
    }
    var reasons = new ArrayList<String>();
    var partitions = disk.children().stream().filter(BlockDevice::partition).toList();
    for (var one : partitions) {
      if ((one == device || device == disk) && pastTheEnd(one, disk)) {
        reasons.add(one.path() + " reaches past the end of " + disk.path());
      }
    }
    for (var one : partitions) {
      for (var other : partitions) {
        var reported = one == device || (other != device && one.path().compareTo(other.path()) < 0);
        if (one != other && reported && overlap(one, other)) {
          reasons.add(one.path() + " overlaps " + other.path() + " on " + disk.path());
        }
      }
    }
    return reasons;
  }

  private static boolean pastTheEnd(BlockDevice partition, BlockDevice disk) {
    return partition.start() != null && partition.start() * SECTOR + partition.size() > disk.size();
  }

  /**
   * Whether {@code partition} ends at the last byte of {@code disk}, as the kernel cuts one whose
   * table entry reaches past it; no GPT partition ends there, under the table's backup copy.
   */
  private static boolean atTheEnd(BlockDevice partition, BlockDevice disk) {
    return partition.start() != null
        && partition.start() * SECTOR + partition.size() == disk.size();
  }

  private static boolean overlap(BlockDevice one, BlockDevice other) {
    if (one.start() == null || other.start() == null) {
      return false;
    }
    var from = one.start() * SECTOR;
    var to = other.start() * SECTOR;
    return from < to + other.size() && to < from + one.size();
  }

  /**
   * Why a partition of the disk {@code device} keeps a pool off the whole disk by its type: none
   * lsblk reads, or one that is neither Linux filesystem data nor ZFS. Judged after what the disk
   * holds, so a pool's disk is named by its pool.
   */
  static List<String> partitionTypes(BlockDevice device) {
    if (device.partition()) {
      return List.of();
    }
    return device
        .subtree()
        .filter(node -> node != device && node.partition())
        .flatMap(node -> typed(node, device).stream())
        .toList();
  }

  /**
   * The {@link #uses} that refuse {@code device}, with the filesystem it holds unmounted, which a
   * refusal names so the operator knows what the refused device keeps.
   */
  static List<String> refusals(BlockDevice device, BlockDevice disk) {
    var uses = uses(device, disk);
    if (uses.isEmpty()
        || device.fstype() == null
        || member(device.fstype())
        || !device.mountpoints().isEmpty()) {
      return uses;
    }
    return Stream.concat(uses.stream(), Stream.of("it holds " + device.fstype())).toList();
  }

  /**
   * Each member of something, a pool, an array, a volume group, a cache or a cluster, that the
   * probes or lsblk found on {@code device} or its partitions; {@code pools} names the zpool a
   * {@code zfs_member} listing gives, and {@code importable} the pools {@code zpool import} lists,
   * so a pool's stale labels are never offered an import. Such a device is never offered an erase.
   */
  static List<String> memberships(
      BlockDevice device,
      Map<String, List<String>> signatures,
      Map<String, String> pools,
      Set<String> importable) {
    var uses = new LinkedHashSet<String>();
    for (var member : device.members()) {
      Stream.concat(
              signatures.getOrDefault(member.path(), List.of()).stream(),
              Stream.ofNullable(member.fstype()))
          .filter(DeviceRules::member)
          .forEach(
              type ->
                  uses.add(membership(member.path(), type, pools.get(member.path()), importable)));
    }
    return List.copyOf(uses);
  }

  /** Whether {@code type} marks a device as part of something Sail must not take apart. */
  static boolean member(String type) {
    return type != null && (type.endsWith("_member") || HELD.contains(type));
  }

  private static String membership(String path, String type, String pool, Set<String> importable) {
    if (MEMBERS.containsKey(type)) {
      return path + " " + MEMBERS.get(type).formatted(path);
    }
    if (ZFS.equals(type) && pool != null && !importable.contains(pool)) {
      return path
          + " carries the labels of zpool "
          + pool
          + ", which cannot be imported (destroyed, or its other devices are gone): clear them"
          + " yourself with sudo zpool labelclear "
          + path;
    }
    if (ZFS.equals(type)) {
      return path
          + " is a device of "
          + (pool == null ? "a zpool" : "zpool " + pool)
          + ", which is not imported: import it with sudo zpool import"
          + (pool == null ? "" : " " + pool)
          + ", or clear it yourself with sudo zpool labelclear "
          + path;
    }
    return path
        + " carries "
        + type
        + " metadata: free it with the tool that made it, then run"
        + " this again";
  }

  /**
   * Why a partition's type keeps a pool off it, a partition of {@code parent}: none lsblk reads, or
   * one that is neither Linux filesystem data nor ZFS. A boot, firmware, reserved or extended
   * partition may carry no signature at all, so only those two are taken. With no type, one that
   * ends at its disk's last byte is one the kernel cut there, whose entry udev cannot read.
   */
  private static Optional<String> typed(BlockDevice node, BlockDevice parent) {
    if (node.parttype() == null && atTheEnd(node, parent)) {
      return Optional.of(
          node.path()
              + " reaches past the end of "
              + parent.path()
              + ": the kernel cut it at the disk's end, so udev reads no type for it");
    }
    if (node.parttype() == null) {
      return Optional.of(
          node.path()
              + " has no partition type lsblk reads: run sudo udevadm settle, then run this again");
    }
    if (ACCEPTED.contains(node.parttype().toLowerCase(Locale.ROOT))) {
      return Optional.empty();
    }
    return Optional.of(
        node.path()
            + " has partition type "
            + node.parttype()
            + ", not Linux filesystem data (0fc63daf-8483-4772-8e79-3d69d8477de4, 0x83) or ZFS"
            + " (6a898cc3-1dd2-11b2-99a6-080020736631, 0xbf)");
  }

  /**
   * What {@code device} carries: each signature the probes found and lsblk reports on it, and for a
   * disk each partition, whatever lsblk or the probes can read of it.
   */
  static List<String> holds(BlockDevice device, Map<String, List<String>> signatures) {
    var holds = new LinkedHashSet<String>();
    for (var member : device.members()) {
      var on = member == device ? "" : " on " + member.path();
      if (member != device) {
        holds.add("partition " + member.path());
      }
      Stream.concat(
              signatures.getOrDefault(member.path(), List.of()).stream(),
              member.signature().stream())
          .forEach(type -> holds.add(type + on));
    }
    return List.copyOf(holds);
  }

  /** What a device that is neither a disk nor a partition is, as a refusal names it. */
  private static String kind(BlockDevice device) {
    if (ARRAYS.contains(device.type())) {
      return "an md array (" + device.type() + ")";
    }
    return KIND.getOrDefault(device.type(), "a " + device.type() + " device");
  }

  private static String mounted(BlockDevice node, String mount) {
    if (SWAP.equals(mount)) {
      return node.path() + " is in use as swap";
    }
    return node.path() + " is mounted at " + mount;
  }
}
