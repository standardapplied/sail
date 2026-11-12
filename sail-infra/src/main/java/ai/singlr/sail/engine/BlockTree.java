/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.engine;

import ai.singlr.sail.config.YamlUtil;
import java.io.IOException;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/** The box's block devices as one lsblk call lists them, and where a device sits among them. */
final class BlockTree {

  static final List<String> LSBLK =
      List.of(
          "lsblk",
          "--json",
          "--tree",
          "--bytes",
          "--output",
          "PATH,KNAME,TYPE,FSTYPE,PTTYPE,PARTTYPE,MOUNTPOINTS,SIZE,START,MODEL,RM,WWN,SERIAL,MAJ:MIN");

  private BlockTree() {}

  /**
   * Every device as lsblk lists it, whole devices at the top. A partition at the top, or a device
   * listed there twice, means lsblk printed a flat list, which cannot say what is built on what,
   * and is refused.
   */
  static List<BlockDevice> read(ShellExec shell)
      throws IOException, InterruptedException, TimeoutException {
    var listed = shell.exec(LSBLK);
    if (!listed.ok()) {
      throw new IOException(
          "lsblk failed: "
              + listed.reason()
              + ". Sail reads devices with util-linux 2.37 or newer (lsblk --json --tree with"
              + " MOUNTPOINTS); nothing was written.");
    }
    var devices = BlockDevice.listed(YamlUtil.parseMap(listed.stdout()).get("blockdevices"));
    var paths = devices.stream().map(BlockDevice::path).toList();
    if (devices.stream().anyMatch(BlockDevice::partition)
        || Set.copyOf(paths).size() != paths.size()) {
      throw new IOException(
          "lsblk printed a flat list, not a device tree, so what is built on a device cannot be"
              + " read; nothing was written.");
    }
    return devices;
  }

  /**
   * Where {@code path} is in {@code tree}, by its path or its kernel name: its node and the whole
   * disk it is on.
   */
  static Place locate(List<BlockDevice> tree, String path, String label) {
    for (var disk : tree) {
      var found =
          disk.subtree()
              .filter(node -> node.path().equals(path) || path.equals(node.kernelPath()))
              .findFirst();
      if (found.isPresent()) {
        return new Place(found.get(), disk);
      }
    }
    throw new IllegalArgumentException(label + " is not a block device lsblk lists.");
  }

  /** The device {@code device} is a partition of, on {@code disk}, when it is a partition. */
  static Optional<BlockDevice> parent(BlockDevice disk, BlockDevice device) {
    if (!device.partition()) {
      return Optional.empty();
    }
    return disk.subtree()
        .filter(node -> node.children().stream().anyMatch(child -> child == device))
        .findFirst();
  }

  /** A device, and the whole disk at the top of the tree it is on. */
  record Place(BlockDevice device, BlockDevice disk) {

    /**
     * The other devices whose pool keeps this one off: for a partition of a disk, the disk and its
     * other partitions, which a pool's device on the same disk may be.
     */
    Set<String> beside() {
      return parent(disk, device).filter(parent -> parent == disk).isEmpty()
          ? Set.of()
          : disk.members().stream()
              .map(BlockDevice::path)
              .filter(path -> !path.equals(device.path()))
              .collect(Collectors.toSet());
    }

    /**
     * What lsblk says of the device that does not move between disks: its or its disk's WWN or
     * serial where there is one, with its major:minor and size in bytes.
     */
    String identity() {
      var stable =
          Stream.of(device.wwn(), device.serial(), disk.wwn(), disk.serial())
              .filter(Objects::nonNull)
              .findFirst();
      return Stream.concat(
              stable.stream(), Stream.of(device.majmin(), String.valueOf(device.size())))
          .filter(Objects::nonNull)
          .collect(Collectors.joining(" "));
    }
  }
}
