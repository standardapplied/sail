/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.engine;

import ai.singlr.sail.common.Strings;
import ai.singlr.sail.config.YamlUtil;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeoutException;
import java.util.stream.Stream;

/**
 * The one place that decides whether a block device may become a ZFS pool. It reads the whole
 * device tree ({@code lsblk --json --tree}, util-linux 2.37 or newer), then probes the device and
 * each of its partitions directly: {@code wipefs --no-act} for what they hold, {@code zpool list}
 * for an imported pool they belong to, and an exclusive open that writes nothing for any other
 * holder lsblk cannot see (a mount in another namespace, a pool, an array).
 *
 * <p>Refused always, by name: anything but a disk or a partition; a removable, empty, zram or nbd
 * device; a device mounted, swap, or with something built on it; a partition of the disk the
 * running system is on; a RAID member, an LVM physical volume, a LUKS volume; a device another
 * holder has open. A device in an imported pool is never wiped: the empty pool is handed to Incus
 * as it is, one with datasets or one Incus holds is refused. Anything else a device holds, a
 * filesystem, a partition table, an exported pool, is erased only when the operator names the same
 * device again. Names are resolved first, so a symlink is judged as the device it points at.
 */
public final class PoolDevices {

  static final List<String> LSBLK =
      List.of(
          "lsblk",
          "--json",
          "--tree",
          "--bytes",
          "--output",
          "NAME,PATH,TYPE,FSTYPE,PTTYPE,MOUNTPOINTS,SIZE,MODEL,RM");

  /** A wipefs type filter no signature matches: with it the probe can erase nothing. */
  static final String NOTHING = "sail_exclusive_probe";

  private static final Set<String> KINDS = Set.of("disk", "part");
  private static final Set<String> WHOLE = Set.of("disk", "loop", "rom");
  private static final String PARTITION = "part";
  private static final String SWAP = "[SWAP]";
  private static final String BUSY = "Device or resource busy";
  private static final Map<String, String> MEMBERS =
      Map.of(
          "linux_raid_member",
          "is a RAID member: stop its array and clear it with mdadm --zero-superblock %s yourself",
          "LVM2_member",
          "is an LVM physical volume: move its volume group off it and clear it with pvremove %s"
              + " yourself",
          "crypto_LUKS",
          "holds a LUKS volume: close it and erase it with cryptsetup erase %s yourself");

  private final ShellExec shell;
  private final IncusStorage storage;

  public PoolDevices(ShellExec shell) {
    this.shell = shell;
    this.storage = new IncusStorage(shell);
  }

  /** An unused whole disk that could hold the pool. */
  public record Candidate(String device, String size, String model) {}

  /**
   * Every whole disk that holds nothing and that nothing uses, as lsblk sees them, for an operator
   * who named none; the one picked is cleared as a named one is.
   */
  public List<Candidate> candidates() throws IOException, InterruptedException, TimeoutException {
    return tree().stream()
        .filter(disk -> uses(disk, disk).isEmpty() && holds(disk, disk.signature()).isEmpty())
        .map(disk -> new Candidate(disk.path(), Strings.gibibytes(disk.size()), disk.model()))
        .toList();
  }

  /** {@code named} resolved to the device path it names, refused when it names nothing. */
  public String resolved(String option, String named)
      throws IOException, InterruptedException, TimeoutException {
    var resolved = shell.exec(List.of("realpath", "-e", "--", named));
    if (!resolved.ok()) {
      throw new IllegalArgumentException(
          option + " " + named + " names no device on this box: " + resolved.reason() + ".");
    }
    return resolved.stdout().strip();
  }

  /**
   * What the device {@code named} names may become: the device itself, to be erased only when
   * {@code forceWipe} (the operator's second naming of it, or null) names it too, or the empty
   * imported pool it belongs to, handed to Incus as it is. Refused, with nothing written,
   * otherwise.
   */
  public PoolSource cleared(String named, String forceWipe)
      throws IOException, InterruptedException, TimeoutException {
    var path = resolved("--disk", named);
    var label = label(named, path);
    var place = locate(tree(), path, label);
    refuseAny(label, uses(place.device(), place.disk()));
    var forced = forceWipe != null;
    var wiped = forced ? resolved("--force-wipe", forceWipe) : path;
    if (!wiped.equals(path)) {
      throw new IllegalArgumentException(
          "--force-wipe names "
              + label(forceWipe, wiped)
              + ", not the pool's device "
              + label
              + ". Name the same device to both.");
    }
    var members = members(place.device());
    var signatures = new LinkedHashMap<String, List<String>>();
    for (var member : members) {
      signatures.put(member.path(), signatures(member.path()));
    }
    refuseAny(label, memberships(signatures));
    var pools = pooled(signatures.keySet());
    if (!pools.isEmpty()) {
      return handed(label, pools, forced);
    }
    var busy = new ArrayList<String>();
    for (var member : members) {
      if (busy(member.path())) {
        busy.add(member.path() + " is held open by something lsblk does not show");
      }
    }
    refuseAny(label, busy);
    var holds = holds(place.device(), signatures);
    if (!holds.isEmpty() && !forced) {
      throw new IllegalArgumentException(
          label
              + " holds "
              + String.join(", ", holds)
              + ". Nothing on it is erased unless you name it again: add --force-wipe "
              + path
              + " to erase it and make the pool there.");
    }
    return new PoolSource.Device(path, forced);
  }

  /**
   * Each use lsblk shows that keeps any pool off {@code device}, a disk or something on {@code
   * disk}.
   */
  static List<String> uses(BlockDevice device, BlockDevice disk) {
    var uses = new LinkedHashSet<String>();
    if (!KINDS.contains(device.type())) {
      uses.add("it is a " + device.type() + " device, not a disk or a partition");
    }
    if (disk.removable()) {
      uses.add(disk.path() + " is removable");
    }
    if (device.size() == 0) {
      uses.add("it has no size");
    }
    if (disk.name().startsWith("zram") || disk.name().startsWith("nbd")) {
      uses.add("it is a " + disk.name().replaceAll("[0-9]+$", "") + " device");
    }
    if (device != disk) {
      disk.subtree()
          .filter(node -> device.subtree().noneMatch(own -> own == node))
          .flatMap(
              node ->
                  node.mountpoints().stream()
                      .filter(PoolDevices::system)
                      .map(mount -> mounted(node, mount)))
          .findFirst()
          .ifPresent(
              system ->
                  uses.add(
                      "it is a partition of "
                          + disk.path()
                          + ", which carries the running system ("
                          + system
                          + ")"));
    }
    device
        .subtree()
        .forEach(
            node -> {
              node.mountpoints().forEach(mount -> uses.add(mounted(node, mount)));
              if (node.fstype() != null && MEMBERS.containsKey(node.fstype())) {
                uses.add(node.path() + " " + MEMBERS.get(node.fstype()).formatted(node.path()));
              }
              if (node != device && !PARTITION.equals(node.type())) {
                uses.add(node.path() + " (" + node.type() + ") is built on it");
              }
            });
    return List.copyOf(uses);
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
   * What erasing {@code device} would destroy: each signature the probes found on it and on its
   * partitions.
   */
  static List<String> holds(BlockDevice device, Map<String, List<String>> signatures) {
    var holds = new LinkedHashSet<String>();
    signatures.forEach(
        (path, types) ->
            types.forEach(
                type -> holds.add(path.equals(device.path()) ? type : type + " on " + path)));
    return List.copyOf(holds);
  }

  private static List<String> holds(BlockDevice disk, List<String> signature) {
    var signatures = new LinkedHashMap<String, List<String>>();
    signatures.put(disk.path(), signature);
    disk.children().forEach(child -> signatures.put(child.path(), child.signature()));
    return holds(disk, signatures);
  }

  private static boolean system(String mount) {
    return mount.equals("/") || mount.startsWith("/boot") || mount.equals(SWAP);
  }

  private static String mounted(BlockDevice node, String mount) {
    if (SWAP.equals(mount)) {
      return node.path() + " is in use as swap";
    }
    return node.path() + " is mounted at " + mount + (system(mount) ? ", the running system" : "");
  }

  private static void refuseAny(String label, List<String> uses) {
    if (!uses.isEmpty()) {
      throw new IllegalArgumentException(
          label + " is refused, even with --force-wipe: " + String.join("; ", uses) + ".");
    }
  }

  /** The device and, for a disk, each of its partitions: what the probes read. */
  private static List<BlockDevice> members(BlockDevice device) {
    return Stream.concat(
            Stream.of(device),
            device.children().stream().filter(child -> PARTITION.equals(child.type())))
        .toList();
  }

  /**
   * Every device as lsblk lists it, whole devices at the top. A partition, an array or a volume at
   * the top means lsblk printed a flat list, which cannot say what is built on what: refused.
   */
  private List<BlockDevice> tree() throws IOException, InterruptedException, TimeoutException {
    var listed = shell.exec(LSBLK);
    if (!listed.ok()) {
      throw new IOException(
          "lsblk failed: "
              + listed.reason()
              + ". Sail reads devices with util-linux 2.37 or newer (lsblk --json --tree with"
              + " MOUNTPOINTS); nothing was wiped.");
    }
    var devices = BlockDevice.listed(YamlUtil.parseMap(listed.stdout()).get("blockdevices"));
    if (devices.stream().anyMatch(device -> !WHOLE.contains(device.type()))) {
      throw new IOException(
          "lsblk printed a flat list, not a device tree, so what is built on a device cannot be"
              + " read; nothing was wiped.");
    }
    return devices;
  }

  /** Where {@code path} is in the tree: its node and the whole disk it is on. */
  private static Place locate(List<BlockDevice> tree, String path, String label) {
    for (var disk : tree) {
      var found = disk.subtree().filter(node -> node.path().equals(path)).findFirst();
      if (found.isPresent()) {
        return new Place(found.get(), disk);
      }
    }
    throw new IllegalArgumentException(label + " is not a block device lsblk lists.");
  }

  private record Place(BlockDevice device, BlockDevice disk) {}

  private List<String> signatures(String path)
      throws IOException, InterruptedException, TimeoutException {
    var probed = shell.exec(List.of("wipefs", "--no-act", "--json", "--", path));
    if (!probed.ok()) {
      throw new IOException("Cannot read the signatures on " + path + ": " + probed.reason());
    }
    var types = new LinkedHashSet<String>();
    if (YamlUtil.parseMap(probed.stdout()).get("signatures") instanceof List<?> found) {
      found.forEach(signature -> types.add(String.valueOf(((Map<?, ?>) signature).get("type"))));
    }
    return List.copyOf(types);
  }

  /**
   * Whether another holder has {@code path} open: the exclusive open {@code wipefs --all} makes
   * fails with EBUSY. {@code --no-act} skips every write and the type filter matches nothing, so no
   * signature is erased and no partition table re-read.
   */
  private boolean busy(String path) throws IOException, InterruptedException, TimeoutException {
    var opened = shell.exec(List.of("wipefs", "--all", "--no-act", "--types", NOTHING, "--", path));
    if (opened.ok()) {
      return false;
    }
    if (opened.stderr().contains(BUSY)) {
      return true;
    }
    throw new IOException("Cannot tell whether " + path + " is in use: " + opened.reason());
  }

  /** The imported pool each of {@code paths} belongs to; none where ZFS is not installed. */
  private Map<String, String> pooled(Set<String> paths)
      throws IOException, InterruptedException, TimeoutException {
    if (!shell.exec(List.of("which", "zpool")).ok()) {
      return Map.of();
    }
    var listed = shell.exec(List.of("zpool", "list", "-vHP"));
    if (!listed.ok()) {
      throw new IOException("Cannot list the imported pools: " + listed.reason());
    }
    var vdevs = new LinkedHashMap<String, String>();
    String pool = null;
    for (var line : listed.stdout().lines().toList()) {
      var name = line.strip().split("\t")[0];
      if (!line.startsWith("\t")) {
        pool = name;
      } else if (name.startsWith("/")) {
        vdevs.put(name, pool);
      }
    }
    if (vdevs.isEmpty()) {
      return Map.of();
    }
    var command = new ArrayList<>(List.of("realpath", "-m", "--"));
    command.addAll(vdevs.keySet());
    var resolved = shell.exec(command).stdout().lines().toList();
    var pools = new LinkedHashMap<String, String>();
    var named = List.copyOf(vdevs.values());
    for (var i = 0; i < resolved.size() && i < named.size(); i++) {
      if (paths.contains(resolved.get(i))) {
        pools.put(resolved.get(i), named.get(i));
      }
    }
    return pools;
  }

  /**
   * The imported pool {@code pools} names, handed to Incus as it is when it is empty and nobody
   * holds it; refused otherwise, since a pool's device is never wiped.
   */
  private PoolSource handed(String label, Map<String, String> pools, boolean forced)
      throws IOException, InterruptedException, TimeoutException {
    var names = new LinkedHashSet<>(pools.values());
    var where = String.join(", ", names);
    if (names.size() > 1) {
      refuseAny(label, List.of("it holds devices of the imported pools " + where));
    }
    var name = names.getFirst();
    var held = storage.poolsOrNone().stream().filter(pool -> name.equals(pool.zpool())).findFirst();
    if (held.isPresent()) {
      refuseAny(
          label, List.of("it is in zpool " + name + ", Incus's pool '" + held.get().name() + "'"));
    }
    if (forced) {
      refuseAny(
          label,
          List.of(
              "it is in the imported zpool "
                  + name
                  + ", which is never wiped: drop --force-wipe to hand the empty pool to Incus, or"
                  + " destroy the pool yourself"));
    }
    var datasets = datasets(name);
    if (!datasets.isEmpty()) {
      refuseAny(
          label,
          List.of(
              "it is in the imported zpool "
                  + name
                  + ", which holds "
                  + String.join(", ", datasets)
                  + ": only an empty pool is handed to Incus, and none is wiped"));
    }
    return new PoolSource.Zpool(name);
  }

  /** What zpool {@code name} holds beside its own root dataset. */
  private List<String> datasets(String name)
      throws IOException, InterruptedException, TimeoutException {
    var listed = shell.exec(List.of("zfs", "list", "-H", "-r", "-o", "name", name));
    if (!listed.ok()) {
      throw new IOException("Cannot list what zpool " + name + " holds: " + listed.reason());
    }
    return listed.stdout().lines().map(String::strip).filter(line -> !line.equals(name)).toList();
  }

  private static String label(String named, String path) {
    return path.equals(named) ? path : path + " (" + named + ")";
  }

  /** A block device as {@code lsblk --json --tree} lists it, with what is on it and built on it. */
  record BlockDevice(
      String name,
      String path,
      String type,
      String fstype,
      String pttype,
      List<String> mountpoints,
      long size,
      String model,
      boolean removable,
      List<BlockDevice> children) {

    static List<BlockDevice> listed(Object nodes) {
      return nodes instanceof List<?> list
          ? list.stream().map(node -> of((Map<?, ?>) node)).toList()
          : List.of();
    }

    private static BlockDevice of(Map<?, ?> node) {
      var mounts = node.get("mountpoints") instanceof List<?> listed ? listed : List.of();
      return new BlockDevice(
          Objects.toString(node.get("name"), ""),
          (String) node.get("path"),
          (String) node.get("type"),
          (String) node.get("fstype"),
          (String) node.get("pttype"),
          mounts.stream().filter(Objects::nonNull).map(String::valueOf).toList(),
          node.get("size") instanceof Number size ? size.longValue() : 0,
          Optional.ofNullable(node.get("model"))
              .map(model -> model.toString().strip())
              .orElse(null),
          Boolean.TRUE.equals(node.get("rm")),
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
