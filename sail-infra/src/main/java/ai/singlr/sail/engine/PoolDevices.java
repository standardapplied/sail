/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.engine;

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
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * The one place that decides whether a block device may become a ZFS pool. It reads the whole
 * device tree ({@code lsblk --json --tree}, util-linux 2.37 or newer), then probes the device and
 * each of its partitions directly: {@code wipefs}'s listing for what they hold, {@code zpool list}
 * for an imported pool they belong to, and an exclusive open that writes nothing for any other
 * holder lsblk cannot see (a mount in another namespace, a pool, an array).
 *
 * <p>Refused always, by name: anything but a disk or a partition of a disk (an array, a mapping, a
 * loop device, or a partition of one); a removable, empty, zram or nbd device, or a ZFS volume; a
 * device mounted, swap, or with something built on it; an EFI system or BIOS boot partition, and a
 * disk holding one; a partition of the disk the running system is on; a RAID member, an LVM
 * physical volume, a LUKS volume; a device another holder has open; a device in an imported pool,
 * or a partition beside one on the same disk. A refusal names the filesystem a refused device holds
 * unmounted. Where no device lsblk lists is mounted at {@code /}, which disks carry the system
 * cannot be told, and every device is refused. Anything else a device holds, a filesystem, a
 * partition table, an exported pool, is erased only when the operator names the same device again.
 * Names are resolved first, so a symlink is judged as the device it points at, and what a device is
 * cleared as carries what lsblk says of it that does not move between disks.
 *
 * <p>The exclusive open sees only holders that open a device exclusively: a process that opens it
 * plainly (a virtual machine's raw disk, {@code dd}) is not seen.
 */
public final class PoolDevices {

  static final List<String> LSBLK =
      List.of(
          "lsblk",
          "--json",
          "--tree",
          "--output",
          "PATH,KNAME,TYPE,FSTYPE,PTTYPE,PARTTYPE,MOUNTPOINTS,SIZE,MODEL,RM,WWN,SERIAL,MAJ:MIN");

  /** A wipefs type filter no signature matches: with it the probe can erase nothing. */
  static final String NOTHING = "sail_exclusive_probe";

  private static final String DISK = "disk";
  private static final Set<String> KINDS = Set.of(DISK, "part");
  private static final Set<String> ARRAYS =
      Set.of("raid0", "raid1", "raid4", "raid5", "raid6", "raid10", "linear", "md");
  private static final Map<String, String> KIND =
      Map.of("lvm", "an LVM logical volume", "crypt", "an open LUKS volume");
  private static final Map<String, String> NAMED =
      Map.of("zram", "a zram device", "nbd", "an nbd device", "zd", "a ZFS volume");
  private static final Map<String, String> BOOT =
      Map.of(
          "c12a7328-f81f-11d2-ba4b-00a0c93ec93b", "an EFI system partition",
          "0xef", "an EFI system partition",
          "21686148-6449-6e6f-744e-656564454649", "a BIOS boot partition");
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
        .map(disk -> new Candidate(disk.path(), disk.size(), disk.model()))
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
   * The device {@code named} names, as Incus may be handed it: to be erased only when {@code
   * forceWipe} (the operator's second naming of it, or null) names it too. Refused, with nothing
   * written, otherwise.
   */
  public PoolSource.Device cleared(String named, String forceWipe)
      throws IOException, InterruptedException, TimeoutException {
    var path = resolved("--disk", named);
    var place = locate(tree(), path, label(named, path));
    var label = label(named, place.device().path());
    refuseAny(label, refusals(place.device(), place.disk()));
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
    refuseAny(label, pooled(signatures.keySet(), place.beside()));
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
    return new PoolSource.Device(path, forced, place.identity());
  }

  /**
   * Each use lsblk shows that keeps any pool off {@code device}, a disk or something on {@code
   * disk}.
   */
  static List<String> uses(BlockDevice device, BlockDevice disk) {
    var uses = new LinkedHashSet<String>();
    if (!KINDS.contains(device.type())) {
      uses.add("it is " + kind(device) + ", not a disk or a partition");
    }
    var parent = parent(disk, device);
    if (parent.isPresent() && !(parent.get() == disk && DISK.equals(disk.type()))) {
      uses.add(
          "it is a partition of "
              + parent.get().path()
              + " ("
              + parent.get().type()
              + "), not of a disk");
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
        .filter(node -> node.parttype() != null && BOOT.containsKey(node.parttype()))
        .forEach(node -> uses.add(node.path() + " is " + BOOT.get(node.parttype())));
    if (parent.isPresent() && parent.get() == disk) {
      disk.subtree()
          .filter(node -> device.subtree().noneMatch(own -> own == node))
          .flatMap(
              node ->
                  node.mountpoints().stream()
                      .filter(PoolDevices::system)
                      .map(mount -> beside(node, mount)))
          .findFirst()
          .ifPresent(system -> uses.add("it is a partition of " + disk.path() + system));
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

  /** What a device that is neither a disk nor a partition is, as a refusal names it. */
  private static String kind(BlockDevice device) {
    if (ARRAYS.contains(device.type())) {
      return "an md array (" + device.type() + ")";
    }
    return KIND.getOrDefault(device.type(), "a " + device.type() + " device");
  }

  /** The device {@code device} is a partition of, when it is a partition. */
  private static Optional<BlockDevice> parent(BlockDevice disk, BlockDevice device) {
    if (!PARTITION.equals(device.type())) {
      return Optional.empty();
    }
    return disk.subtree()
        .filter(node -> node.children().stream().anyMatch(child -> child == device))
        .findFirst();
  }

  /** Why a system mount on another part of its disk keeps a partition off it. */
  private static String beside(BlockDevice node, String mount) {
    return SWAP.equals(mount)
        ? ", where " + node.path() + " is in use as swap"
        : ", which carries the running system (" + mounted(node, mount) + ")";
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
   * Every device as lsblk lists it, whole devices at the top. A partition at the top, or a device
   * listed there twice, means lsblk printed a flat list, which cannot say what is built on what. A
   * tree with nothing mounted at {@code /} cannot say which disks carry the system. Both are
   * refused.
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
    var paths = devices.stream().map(BlockDevice::path).toList();
    if (devices.stream().anyMatch(device -> PARTITION.equals(device.type()))
        || Set.copyOf(paths).size() != paths.size()) {
      throw new IOException(
          "lsblk printed a flat list, not a device tree, so what is built on a device cannot be"
              + " read; nothing was wiped.");
    }
    if (devices.stream()
        .flatMap(BlockDevice::subtree)
        .noneMatch(node -> node.mountpoints().contains("/"))) {
      throw new IllegalArgumentException(
          "Sail cannot tell which disks carry the running system: lsblk shows no device mounted at"
              + " / (root on ZFS, or on a filesystem lsblk cannot place), so no disk or partition is"
              + " taken, forced or not. Make the pool in a file instead, with --pool-file-size"
              + " <size>.");
    }
    return devices;
  }

  /**
   * Where {@code path} is in the tree, by its path or its kernel name: its node and the whole disk
   * it is on.
   */
  private static Place locate(List<BlockDevice> tree, String path, String label) {
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

  private record Place(BlockDevice device, BlockDevice disk) {

    /**
     * The other devices whose pool keeps this one off: for a partition of a disk, the disk and its
     * other partitions, which a pool's device on the same disk may be.
     */
    Set<String> beside() {
      return parent(disk, device).filter(parent -> parent == disk).isEmpty()
          ? Set.of()
          : members(disk).stream()
              .map(BlockDevice::path)
              .filter(path -> !path.equals(device.path()))
              .collect(Collectors.toSet());
    }

    /**
     * What lsblk says of the device that does not move between disks: its or its disk's WWN or
     * serial where there is one, with its major:minor and size.
     */
    String identity() {
      var stable =
          Stream.of(device.wwn(), device.serial(), disk.wwn(), disk.serial())
              .filter(Objects::nonNull)
              .findFirst();
      return Stream.concat(stable.stream(), Stream.of(device.majmin(), device.size()))
          .filter(Objects::nonNull)
          .collect(Collectors.joining(" "));
    }
  }

  /**
   * The signature types {@code wipefs} lists on {@code path}: given no {@code --all} and no {@code
   * --offset} it only reads. Its table's third column is the type; no table means none.
   */
  private List<String> signatures(String path)
      throws IOException, InterruptedException, TimeoutException {
    var probed = shell.exec(List.of("wipefs", "--", path));
    if (!probed.ok()) {
      throw new IOException("Cannot read the signatures on " + path + ": " + probed.reason());
    }
    return probed
        .stdout()
        .lines()
        .skip(1)
        .map(line -> line.strip().split("\\s+"))
        .filter(fields -> fields.length > 2)
        .map(fields -> fields[2])
        .distinct()
        .toList();
  }

  /**
   * The open {@link #busy} makes: {@code wipefs --all} opens the device for exclusive use, which
   * fails with EBUSY while anything holds it. {@code --no-act} skips every write, and the type
   * filter matches no signature, so nothing is erased and no partition table is re-read, as an
   * unfiltered {@code --no-act --all} does.
   */
  static List<String> exclusiveOpen(String path) {
    return List.of("wipefs", "--all", "--no-act", "--types", NOTHING, "--", path);
  }

  /** Whether another holder has {@code path} open, so its {@link #exclusiveOpen} fails. */
  private boolean busy(String path) throws IOException, InterruptedException, TimeoutException {
    var opened = shell.exec(exclusiveOpen(path));
    if (opened.ok()) {
      return false;
    }
    if (opened.stderr().contains(BUSY)) {
      return true;
    }
    throw new IOException("Cannot tell whether " + path + " is in use: " + opened.reason());
  }

  /**
   * Why each of {@code paths} that is a device of an imported pool, or that is {@code beside} one
   * on the same disk, may not be taken; none where ZFS is not installed. A pool's devices are read
   * pool by pool, every vdev line of {@code zpool list -vHP <pool>} a member whatever class (log,
   * cache, spare, special, dedup) it is listed under.
   */
  private List<String> pooled(Set<String> paths, Set<String> beside)
      throws IOException, InterruptedException, TimeoutException {
    if (!shell.exec(List.of("which", "zpool")).ok()) {
      return List.of();
    }
    var uses = new ArrayList<String>();
    for (var pool : listed(List.of("zpool", "list", "-H", "-o", "name"))) {
      var members = vdevs(listed(List.of("zpool", "list", "-vHP", pool)));
      if (members.isEmpty()) {
        continue;
      }
      var command = new ArrayList<>(List.of("realpath", "-m", "--"));
      command.addAll(members);
      var resolved = shell.exec(command).stdout().lines().toList();
      var near = resolved.stream().filter(beside::contains).findFirst();
      if (resolved.stream().anyMatch(paths::contains)) {
        uses.add("it " + inPool(pool));
      } else if (near.isPresent()) {
        uses.add(near.get() + ", on the same disk, " + inPool(pool));
      }
    }
    return uses;
  }

  /** The vdev paths of a pool's {@code zpool list -vHP} lines: each line that names a path. */
  static List<String> vdevs(List<String> lines) {
    return lines.stream()
        .map(line -> line.strip().split("\t")[0])
        .filter(name -> name.startsWith("/"))
        .toList();
  }

  private String inPool(String pool) throws InterruptedException {
    var held =
        storage.poolsOrNone().stream().filter(found -> pool.equals(found.zpool())).findFirst();
    return held.isPresent()
        ? "is in zpool " + pool + ", Incus's pool '" + held.get().name() + "'"
        : "is in the imported zpool "
            + pool
            + ", and no pool's device is taken: export or destroy the pool yourself first";
  }

  private List<String> listed(List<String> command)
      throws IOException, InterruptedException, TimeoutException {
    var listed = shell.exec(command);
    if (!listed.ok()) {
      throw new IOException("Cannot list the imported pools: " + listed.reason());
    }
    return listed.stdout().lines().filter(line -> !line.isBlank()).toList();
  }

  private static String label(String named, String path) {
    return path.equals(named) ? path : path + " (" + named + ")";
  }

  /** A block device as {@code lsblk --json --tree} lists it, with what is on it and built on it. */
  record BlockDevice(
      String path,
      String kname,
      String type,
      String fstype,
      String pttype,
      String parttype,
      List<String> mountpoints,
      String size,
      String model,
      boolean removable,
      String wwn,
      String serial,
      String majmin,
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
          (String) node.get("kname"),
          (String) node.get("type"),
          (String) node.get("fstype"),
          (String) node.get("pttype"),
          (String) node.get("parttype"),
          mounts.stream().filter(Objects::nonNull).map(String::valueOf).toList(),
          Objects.toString(node.get("size"), "0B"),
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

    /**
     * Its path by kernel name, as {@code realpath} resolves a {@code /dev/mapper} name; or none.
     */
    String kernelPath() {
      return kname == null ? null : "/dev/" + kname;
    }

    /** Whether lsblk reports it holds no bytes, as an nbd device with nothing attached does. */
    boolean empty() {
      return size.equals("0B");
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
