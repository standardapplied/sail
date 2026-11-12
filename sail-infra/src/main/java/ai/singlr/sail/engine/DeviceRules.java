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
  private static final String EXTENDED = "an extended partition, which holds the logical ones";
  private static final Map<String, String> RESERVED =
      Map.ofEntries(
          Map.entry("c12a7328-f81f-11d2-ba4b-00a0c93ec93b", "an EFI system partition"),
          Map.entry("0xef", "an EFI system partition"),
          Map.entry("21686148-6449-6e6f-744e-656564454649", "a BIOS boot partition"),
          Map.entry("bc13c2ff-59e6-4262-a352-b275fd6f7172", "an XBOOTLDR boot partition"),
          Map.entry("0xea", "an XBOOTLDR boot partition"),
          Map.entry("6a945a3b-1dd2-11b2-99a6-080020736631", "ZFS's reserved partition"),
          Map.entry("0x5", EXTENDED),
          Map.entry("0xf", EXTENDED),
          Map.entry("0x85", EXTENDED));
  private static final String SWAP = "[SWAP]";
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
    if (device.partition() && device.parttype() == null) {
      uses.add("lsblk shows no partition type for it: run sudo udevadm settle, then this again");
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
              if (node.parttype() != null) {
                var reserved = RESERVED.get(node.parttype().toLowerCase(Locale.ROOT));
                if (reserved != null) {
                  uses.add(node.path() + " is " + reserved);
                }
              }
              node.mountpoints().forEach(mount -> uses.add(mounted(node, mount)));
              if (node.fstype() != null && MEMBERS.containsKey(node.fstype())) {
                uses.add(node.path() + " " + MEMBERS.get(node.fstype()).formatted(node.path()));
              }
              if (node != device && !node.partition()) {
                uses.add(node.path() + " (" + node.type() + ") is built on it");
              }
            });
    return List.copyOf(uses);
  }

  /**
   * The {@link #uses} that refuse {@code device}, with the filesystem it holds unmounted, which a
   * refusal names so the operator knows what the refused device keeps.
   */
  static List<String> refusals(BlockDevice device, BlockDevice disk) {
    var uses = uses(device, disk);
    if (uses.isEmpty()
        || device.fstype() == null
        || MEMBERS.containsKey(device.fstype())
        || !device.mountpoints().isEmpty()) {
      return uses;
    }
    return Stream.concat(uses.stream(), Stream.of("it holds " + device.fstype())).toList();
  }

  /** Each RAID, LVM or LUKS signature the probes found, which keeps any pool off its device. */
  static List<String> memberships(Map<String, List<String>> signatures) {
    var uses = new ArrayList<String>();
    signatures.forEach(
        (path, types) ->
            types.stream()
                .filter(MEMBERS::containsKey)
                .forEach(type -> uses.add(path + " " + MEMBERS.get(type).formatted(path))));
    return uses;
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
