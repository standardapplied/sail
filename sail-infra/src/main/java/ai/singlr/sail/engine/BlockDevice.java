/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.engine;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Stream;

/** A block device as {@code lsblk --json --tree} lists it, with what is on it and built on it. */
record BlockDevice(
    String path,
    String kname,
    String type,
    String fstype,
    String pttype,
    String parttype,
    List<String> mountpoints,
    long size,
    String model,
    boolean removable,
    String wwn,
    String serial,
    String majmin,
    List<BlockDevice> children) {

  static final String DISK = "disk";
  static final String PARTITION = "part";

  static List<BlockDevice> listed(Object nodes) {
    return nodes instanceof List<?> list
        ? list.stream().map(node -> of((Map<?, ?>) node)).toList()
        : List.of();
  }

  private static BlockDevice of(Map<?, ?> node) {
    var mounts = node.get("mountpoints") instanceof List<?> listed ? listed : List.of();
    return new BlockDevice(
        (String) node.get("path"),
        (String) node.get("kname"),
        (String) node.get("type"),
        (String) node.get("fstype"),
        (String) node.get("pttype"),
        (String) node.get("parttype"),
        mounts.stream().filter(Objects::nonNull).map(String::valueOf).toList(),
        ((Number) node.get("size")).longValue(),
        stripped(node.get("model")),
        Boolean.TRUE.equals(node.get("rm")),
        stripped(node.get("wwn")),
        stripped(node.get("serial")),
        (String) node.get("maj:min"),
        listed(node.get("children")));
  }

  private static String stripped(Object value) {
    return value == null || value.toString().isBlank() ? null : value.toString().strip();
  }

  /** Its path by kernel name, as {@code realpath} resolves a {@code /dev/mapper} name; or none. */
  String kernelPath() {
    return kname == null ? null : "/dev/" + kname;
  }

  /** Whether lsblk reports it holds no bytes, as an nbd device with nothing attached does. */
  boolean empty() {
    return size == 0;
  }

  boolean partition() {
    return PARTITION.equals(type);
  }

  /** This device and everything on it or built on it. */
  Stream<BlockDevice> subtree() {
    return Stream.concat(Stream.of(this), children.stream().flatMap(BlockDevice::subtree));
  }

  /** The device and, for a disk, each of its partitions. */
  List<BlockDevice> members() {
    return Stream.concat(Stream.of(this), children.stream().filter(BlockDevice::partition))
        .toList();
  }

  /**
   * What udev saw on it, as lsblk reports it: its filesystem and, unless it is a partition, whose
   * partition table lsblk names as the one it sits in, its partition table.
   */
  List<String> signature() {
    return Stream.of(partition() ? null : pttype, fstype).filter(Objects::nonNull).toList();
  }
}
