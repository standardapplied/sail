/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.engine;

import ai.singlr.sail.config.YamlUtil;
import java.io.IOException;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.TimeoutException;
import java.util.stream.Stream;

/**
 * The one place that decides whether a block device may become a ZFS pool, from what {@code lsblk}
 * and {@code wipefs} say of it. A device in use is never taken: one that is mounted (the running
 * system's among them) or swap, a RAID member, an LVM physical volume, one something is built on
 * (an array, a volume group, an open LUKS volume), and anything that is not a disk or a partition.
 * A device that holds anything else, a filesystem, a partition table or an old pool, is taken only
 * when the operator names the same device again to have it erased. A name is resolved first, so a
 * symlink is judged by the device it points at, and two spellings of one device are one device.
 */
public final class PoolDevices {

  private static final List<String> OUTPUT =
      List.of("--json", "--output", "PATH,TYPE,FSTYPE,PTTYPE,MOUNTPOINTS,SIZE,MODEL");
  private static final Set<String> KINDS = Set.of("disk", "part");
  private static final String PARTITION = "part";
  private static final String RAID_MEMBER = "linux_raid_member";
  private static final String LVM_MEMBER = "LVM2_member";
  private static final String SWAP = "[SWAP]";

  private final ShellExec shell;

  public PoolDevices(ShellExec shell) {
    this.shell = shell;
  }

  /** An unused whole disk that could hold the pool. */
  public record Candidate(String device, String size, String model) {}

  /**
   * Every whole disk that holds nothing and that nothing uses, for an operator who named none.
   * lsblk lists a partition only under its disk, so none is offered: one is taken only when named.
   */
  public List<Candidate> candidates() throws IOException, InterruptedException, TimeoutException {
    var listed = shell.exec(lsblk(List.of()));
    if (!listed.ok()) {
      throw new IOException("lsblk failed: " + listed.reason());
    }
    return devices(listed.stdout()).stream()
        .filter(device -> uses(device).isEmpty() && holds(device, device.signature()).isEmpty())
        .map(device -> new Candidate(device.path(), device.size(), device.model()))
        .toList();
  }

  /**
   * The device {@code named} names, resolved, as a pool source Incus may be handed; {@code
   * forceWipe} is the operator's second naming of it, or null. Refused, with nothing written, when
   * the device is in use, or when it holds anything and {@code forceWipe} does not name it.
   */
  public PoolSource.Device cleared(String named, String forceWipe)
      throws IOException, InterruptedException, TimeoutException {
    var path = resolve("--disk", named);
    var device = describe(named, path);
    var uses = uses(device);
    if (!uses.isEmpty()) {
      throw new IllegalArgumentException(
          label(named, path)
              + " is in use, so no pool is made on it, even with --force-wipe: "
              + String.join("; ", uses)
              + ".");
    }
    var forced = forceWipe != null;
    var wiped = forced ? resolve("--force-wipe", forceWipe) : path;
    if (!wiped.equals(path)) {
      throw new IllegalArgumentException(
          "--force-wipe names "
              + label(forceWipe, wiped)
              + ", not the pool's device "
              + label(named, path)
              + ". Name the same device to both.");
    }
    var holds = holds(device, signatures(path));
    if (!holds.isEmpty() && !forced) {
      throw new IllegalArgumentException(
          label(named, path)
              + " holds "
              + String.join(", ", holds)
              + ". Nothing on it is erased unless you name it again: add --force-wipe "
              + path
              + " to erase it and make the pool there.");
    }
    return new PoolSource.Device(path, forced);
  }

  /** Each use of {@code device} or of anything on it that keeps any pool off it. */
  static List<String> uses(BlockDevice device) {
    var uses = new LinkedHashSet<String>();
    if (!KINDS.contains(device.type())) {
      uses.add("it is a " + device.type() + " device, not a disk or a partition");
    }
    device
        .subtree()
        .forEach(
            node -> {
              node.mountpoints().forEach(mount -> uses.add(mounted(node, mount)));
              if (RAID_MEMBER.equals(node.fstype())) {
                uses.add(node.path() + " is a RAID member");
              }
              if (LVM_MEMBER.equals(node.fstype())) {
                uses.add(node.path() + " is an LVM physical volume");
              }
              if (node != device && !PARTITION.equals(node.type())) {
                uses.add(node.path() + " (" + node.type() + ") is built on it");
              }
            });
    return List.copyOf(uses);
  }

  /**
   * What erasing {@code device} would destroy: its own {@code signatures}, and each filesystem on a
   * partition of it.
   */
  static List<String> holds(BlockDevice device, List<String> signatures) {
    var holds = new LinkedHashSet<>(signatures);
    device.children().stream()
        .filter(child -> PARTITION.equals(child.type()) && child.fstype() != null)
        .forEach(child -> holds.add(child.fstype() + " on " + child.path()));
    return List.copyOf(holds);
  }

  private static String mounted(BlockDevice node, String mount) {
    if (SWAP.equals(mount)) {
      return node.path() + " is in use as swap";
    }
    var system = mount.equals("/") || mount.startsWith("/boot");
    return node.path() + " is mounted at " + mount + (system ? ", the running system" : "");
  }

  private String resolve(String option, String named)
      throws IOException, InterruptedException, TimeoutException {
    var resolved = shell.exec(List.of("realpath", "-e", "--", named));
    if (!resolved.ok()) {
      throw new IllegalArgumentException(
          option + " " + named + " names no device on this box: " + resolved.reason() + ".");
    }
    return resolved.stdout().strip();
  }

  private BlockDevice describe(String named, String path)
      throws IOException, InterruptedException, TimeoutException {
    var listed = shell.exec(lsblk(List.of("--", path)));
    var devices = listed.ok() ? devices(listed.stdout()) : List.<BlockDevice>of();
    if (devices.isEmpty()) {
      throw new IllegalArgumentException(
          label(named, path) + " is not a block device: " + listed.reason() + ".");
    }
    return devices.getFirst();
  }

  private List<String> signatures(String path)
      throws IOException, InterruptedException, TimeoutException {
    var probed = shell.exec(List.of("wipefs", "--no-act", "--json", "--", path));
    if (!probed.ok()) {
      throw new IOException("Cannot read the signatures on " + path + ": " + probed.reason());
    }
    var types = new LinkedHashSet<String>();
    if (YamlUtil.parseMap(probed.stdout()).get("signatures") instanceof List<?> signatures) {
      signatures.forEach(
          signature -> types.add(String.valueOf(((Map<?, ?>) signature).get("type"))));
    }
    return List.copyOf(types);
  }

  private static List<String> lsblk(List<String> devices) {
    return Stream.of(List.of("lsblk"), OUTPUT, devices).flatMap(List::stream).toList();
  }

  private static List<BlockDevice> devices(String json) {
    return BlockDevice.listed(YamlUtil.parseMap(json).get("blockdevices"));
  }

  private static String label(String named, String path) {
    return path.equals(named) ? path : path + " (" + named + ")";
  }

  /** A block device as {@code lsblk --json} lists it, with what is on it and built on it. */
  record BlockDevice(
      String path,
      String type,
      String fstype,
      String pttype,
      List<String> mountpoints,
      String size,
      String model,
      List<BlockDevice> children) {

    static List<BlockDevice> listed(Object nodes) {
      return nodes instanceof List<?> list
          ? list.stream().map(node -> of((Map<?, ?>) node)).toList()
          : List.of();
    }

    private static BlockDevice of(Map<?, ?> node) {
      var mounts = node.get("mountpoints") instanceof List<?> listed ? listed : List.of();
      return new BlockDevice(
          (String) node.get("path"),
          (String) node.get("type"),
          (String) node.get("fstype"),
          (String) node.get("pttype"),
          mounts.stream().filter(Objects::nonNull).map(String::valueOf).toList(),
          (String) node.get("size"),
          node.get("model") == null ? null : node.get("model").toString().strip(),
          listed(node.get("children")));
    }

    /** This device and everything on it or built on it. */
    Stream<BlockDevice> subtree() {
      return Stream.concat(Stream.of(this), children.stream().flatMap(BlockDevice::subtree));
    }

    /** What udev saw on it, as lsblk reports it: its partition table and its filesystem. */
    List<String> signature() {
      return Stream.of(pttype, fstype).filter(Objects::nonNull).toList();
    }
  }
}
