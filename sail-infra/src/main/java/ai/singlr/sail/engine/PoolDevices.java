/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.engine;

import ai.singlr.sail.common.Strings;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeoutException;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * The one place that decides whether a block device may become a ZFS pool. Sail erases nothing, so
 * a device is taken only when it carries nothing. It reads the whole device tree ({@link
 * BlockTree}), judges what lsblk shows ({@link DeviceRules}), then probes the device and each of
 * its partitions directly: {@code wipefs}'s listing for what they hold, {@code zpool list} for an
 * imported pool they belong to ({@link ImportedPools}), and an exclusive open that writes nothing
 * for any other holder lsblk cannot see (a mount in another namespace, a pool, an array).
 *
 * <p>Taken: a plain disk (at the top of the tree, of type disk) or a partition of one, whose
 * partitions are each of the Linux filesystem data or ZFS type, that holds no signature of any
 * kind, that nothing holds open, and that neither it nor another partition of its disk is a device
 * of an imported pool. Where it sits, the system's disk included, does not matter. Anything else is
 * refused by name; a member of anything names the tool that frees it, and a device that only holds
 * a filesystem, swap or a bare partition table names the {@code wipefs --all} that would erase it,
 * for the operator to run. Names are resolved first, so a symlink is judged as the device it points
 * at, and what a device is cleared as carries what lsblk says of it that does not move between
 * disks. A disk with no partition table and no signature is blank.
 *
 * <p>A holder is found two ways: every process's {@code /proc/<pid>/fd} names the devices it has
 * open, plainly too (a virtual machine's raw disk, {@code dd}), and the exclusive open fails while
 * anything holds the device exclusively. The walk of {@code /proc} takes seconds, so it runs before
 * the device is probed, and the probes are the last look at the device before Incus is handed it. A
 * consumer that does not keep the device open while Sail looks is not seen.
 *
 * <p>Two devices for a mirror are each cleared by the same rules, in the same order, read together:
 * lsblk and the walk once for both, then the reads of each, then the probes of each, so the last
 * look at both comes right before the pool is made. They are taken only on two physical disks and
 * of sizes {@code zpool create} mirrors without force ({@link DeviceRules#mirrorable}).
 */
public final class PoolDevices {

  /** A wipefs type filter no signature matches: with it the probe can erase nothing. */
  static final String NOTHING = "sail_exclusive_probe";

  /**
   * Lists, for each process, the block devices it has open by device number, so a holder is seen
   * through any path or node it opened the device by, in any mount namespace: {@code
   * pid<TAB>major:minor} in hex per open device, and {@code unreadable<TAB>pid} for a process whose
   * open files Sail may not read. A descriptor or a process gone while it is listed, as a busy
   * process's descriptors come and go, is skipped. A constant script: it takes no input.
   */
  static final String OPEN_FILES =
      """
      for d in /proc/[0-9]*; do
        p=${d#/proc/}
        l=$(stat -L -c '%F %t:%T' "$d"/fd/* 2>&1)
        case $l in *'Permission denied'*) [ -e "$d" ] && printf 'unreadable\\t%s\\n' "$p"; continue ;; esac
        printf '%s\\n' "$l" | while IFS= read -r line; do
          case $line in 'block special file '*) printf '%s\\t%s\\n' "$p" "${line##* }" ;; esac
        done
      done
      """;

  private static final String BUSY = "Device or resource busy";
  private static final Pattern NVME = Pattern.compile("(eui|nguid|nvme)\\.");
  private static final String GONE = "No such file or directory";

  /** Waits, at most 10 seconds, for udev to finish what a probe's close set off. */
  static final List<String> SETTLE = List.of("udevadm", "settle", "--timeout=10");

  private final ShellExec shell;
  private final ImportedPools pools;

  public PoolDevices(ShellExec shell) {
    this.shell = shell;
    this.pools = new ImportedPools(shell);
  }

  /** An unused whole disk that could hold the pool. */
  public record Candidate(String device, String size, String model) {}

  /**
   * Every whole disk that holds nothing and that nothing uses, as lsblk sees them, for an operator
   * who named none; the one picked is cleared as a named one is.
   */
  public List<Candidate> candidates() throws IOException, InterruptedException, TimeoutException {
    return BlockTree.read(shell).stream()
        .filter(disk -> DeviceRules.uses(disk, disk).isEmpty() && disk.signature().isEmpty())
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

  /** The device {@code path} resolves to on this box, or none when it names nothing. */
  public Optional<String> device(String path)
      throws IOException, InterruptedException, TimeoutException {
    var resolved = shell.exec(List.of("realpath", "-e", "--", path));
    return resolved.ok() ? Optional.of(resolved.stdout().strip()) : Optional.empty();
  }

  /**
   * The device {@code named} names, as Incus may be handed it: one that carries nothing. Refused,
   * with nothing written, otherwise.
   */
  public PoolSource.Device cleared(String named)
      throws IOException, InterruptedException, TimeoutException {
    return cleared(List.of(named)).getFirst().device();
  }

  /**
   * The devices {@code one} and {@code other} name, as Incus may be handed them for a mirror: each
   * cleared as {@link #cleared(String)} clears one, on two physical disks, of sizes {@code zpool
   * create} mirrors without force. Refused, with nothing written to either, otherwise.
   */
  public PoolSource.Mirror mirror(String one, String other)
      throws IOException, InterruptedException, TimeoutException {
    var cleared = cleared(List.of(one, other));
    return new PoolSource.Mirror(
        cleared.getFirst().device(),
        cleared.getLast().device(),
        cleared.getFirst().size(),
        cleared.getLast().size());
  }

  /** A device cleared, and its size in bytes. */
  private record Cleared(PoolSource.Device device, long size) {}

  /** A device named, where it sits, and how a refusal names it. */
  private record Named(String path, BlockTree.Place place, String label) {}

  /**
   * Each of {@code named} cleared, every rule asked once of each device, in phases: lsblk is read
   * once and its rules, then the pair's, judge every device before the {@code /proc} walk, read
   * once; the probes of every device follow, so the last look at each device comes after the slow
   * reads of all of them, right before the pool is made.
   */
  private List<Cleared> cleared(List<String> named)
      throws IOException, InterruptedException, TimeoutException {
    var paths = new ArrayList<String>();
    for (var one : named) {
      paths.add(resolved("--disk", one));
    }
    var tree = BlockTree.read(shell);
    var devices = new ArrayList<Named>();
    for (var i = 0; i < named.size(); i++) {
      var place = BlockTree.locate(tree, paths.get(i), label(named.get(i), paths.get(i)));
      var device = new Named(paths.get(i), place, label(named.get(i), place.device().path()));
      refuseAny(device.label(), DeviceRules.refusals(place.device(), place.disk()));
      devices.add(device);
    }
    if (devices.size() == 2) {
      refusePair(devices.getFirst(), devices.getLast());
    }
    var walked = walk();
    for (var device : devices) {
      refuseAny(
          device.label(), openBy(device.place().device().path(), numbers(device.place()), walked));
    }
    var signatures = new ArrayList<Map<String, List<String>>>();
    for (var device : devices) {
      signatures.add(signatures(device));
    }
    for (var i = 0; i < devices.size(); i++) {
      refuseAny(devices.get(i).label(), held(devices.get(i).place().device(), signatures.get(i)));
    }
    for (var i = 0; i < devices.size(); i++) {
      refuseHolding(devices.get(i), signatures.get(i));
    }
    var cleared = new ArrayList<Cleared>();
    for (var device : devices) {
      var place = device.place();
      cleared.add(
          new Cleared(
              new PoolSource.Device(persistent(place, device.path()), place.identity()),
              place.device().size()));
    }
    return cleared;
  }

  /**
   * What {@code wipefs} lists on {@code device} and each of its partitions, refused when any is a
   * device of an imported pool or a member of anything.
   */
  private Map<String, List<String>> signatures(Named device)
      throws IOException, InterruptedException, TimeoutException {
    var place = device.place();
    var signatures = new LinkedHashMap<String, List<String>>();
    var labels = new LinkedHashMap<String, String>();
    for (var member : place.device().members()) {
      var rows = listing(member.path());
      signatures.put(member.path(), rows.stream().map(row -> row[2]).distinct().toList());
      rows.stream()
          .filter(row -> row[2].equals("zfs_member") && row.length > 4)
          .forEach(row -> labels.put(member.path(), row[4]));
    }
    refuseAny(device.label(), pools.refusals(signatures.keySet(), place.beside()));
    refuseAny(
        device.label(),
        DeviceRules.memberships(
            place.device(), signatures, labels, labels.isEmpty() ? Set.of() : pools.importable()));
    return signatures;
  }

  /**
   * Refuses {@code device} for a partition type a pool may not take, or for anything the probes
   * found on it, naming the erase the operator may run.
   */
  private static void refuseHolding(Named device, Map<String, List<String>> signatures) {
    refuseAny(device.label(), DeviceRules.partitionTypes(device.place().device()));
    var holds = DeviceRules.holds(device.place().device(), signatures);
    if (!holds.isEmpty()) {
      throw new IllegalArgumentException(
          device.label()
              + " holds "
              + String.join(", ", holds)
              + ". Sail erases nothing: if nothing on it is wanted, erase it yourself with sudo"
              + " wipefs --all "
              + device.path()
              + ", then run this again.");
    }
  }

  /** Refuses two devices that cannot be a mirror: not on two disks, or sizes zpool refuses. */
  private static void refusePair(Named one, Named other) {
    var reasons = DeviceRules.mirrorable(one.place(), other.place());
    if (!reasons.isEmpty()) {
      throw new IllegalArgumentException(
          one.label()
              + " and "
              + other.label()
              + " cannot be a mirror: "
              + String.join("; ", reasons)
              + ".");
    }
  }

  /**
   * The devices a process holding any of them open could write over the one at {@code place}, by
   * major:minor: the device, each of its partitions, and the device it is a partition of, whose
   * every byte a holder of the whole disk reaches.
   */
  static Map<String, String> numbers(BlockTree.Place place) {
    return Stream.concat(
            place.device().members().stream(),
            BlockTree.parent(place.disk(), place.device()).stream())
        .filter(device -> device.majmin() != null)
        .collect(
            Collectors.toMap(
                BlockDevice::majmin, BlockDevice::path, (one, other) -> one, LinkedHashMap::new));
  }

  /**
   * Why something lsblk and the walk cannot see holds {@code device} or a partition of it, from
   * each one's {@link #exclusiveOpen}: partitions first, then the disk, whose probe makes udev
   * re-read its partition table when it closes, so udev is let settle after it.
   */
  private List<String> held(BlockDevice device, Map<String, List<String>> signatures)
      throws IOException, InterruptedException, TimeoutException {
    var members = device.members();
    var held = new ArrayList<String>();
    for (var member : Stream.concat(members.stream().skip(1), Stream.of(device)).toList()) {
      exclusive(member, device, signatures).ifPresent(held::add);
    }
    if (members.size() > 1 && !shell.exec(SETTLE).ok()) {
      held.add(
          "udev did not settle within 10 s after Sail probed "
              + device.path()
              + ", so its partition table is changing: run this again");
    }
    return held;
  }

  /**
   * The path Incus is handed for the device at {@code path}: the one udev names from its disk's
   * hardware identity, a WWN or an NVMe EUI, NGUID or vendor id, with its partition's suffix, so no
   * other disk can stand under it when the pool is made; the kernel path where the disk has none,
   * or where that name does not resolve to the device.
   */
  private String persistent(BlockTree.Place place, String path)
      throws IOException, InterruptedException, TimeoutException {
    var wwn = place.disk().wwn();
    var number =
        place.device().kname() == null
            ? ""
            : place.device().kname().replaceAll(".*?([0-9]+)$", "$1");
    if (wwn == null || (place.device() != place.disk() && !number.matches("[0-9]+"))) {
      return path;
    }
    var named =
        "/dev/disk/by-id/"
            + (NVME.matcher(wwn).lookingAt() ? "nvme-" : "wwn-")
            + wwn
            + (place.device() == place.disk() ? "" : "-part" + number);
    var resolved = shell.exec(List.of("realpath", "-e", "--", named));
    return resolved.ok() && resolved.stdout().strip().equals(path) ? named : path;
  }

  /**
   * The open {@link #exclusive} makes: {@code wipefs --all} opens the device for exclusive use,
   * which fails with EBUSY while anything holds it. {@code --no-act} skips every write, and the
   * type filter matches no signature, so nothing is erased and no partition table is re-read by
   * wipefs, as an unfiltered {@code --no-act --all} does; udev may re-read one when the device is
   * closed, which writes nothing.
   */
  static List<String> exclusiveOpen(String path) {
    return List.of("wipefs", "--all", "--no-act", "--types", NOTHING, "--", path);
  }

  private static void refuseAny(String label, List<String> uses) {
    if (!uses.isEmpty()) {
      throw new IllegalArgumentException(label + " is refused: " + String.join("; ", uses) + ".");
    }
  }

  private static String label(String named, String path) {
    return path.equals(named) ? path : path + " (" + named + ")";
  }

  /**
   * The rows of the signatures {@code wipefs} lists on {@code path}, given no {@code --all} and no
   * {@code --offset}, so it only reads: device, offset, type, then the UUID and label when the
   * signature has them. No table means none.
   */
  private List<String[]> listing(String path)
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
        .toList();
  }

  /**
   * Why {@code device} may be in use by a process that has one of the devices {@code numbers} maps
   * by major:minor to its path open, exclusively or not, as a virtual machine's raw disk or {@code
   * dd} does, read from {@code /proc/<pid>/fd}; a process whose open files cannot be read refuses
   * it too.
   */
  List<String> openBy(String device, Map<String, String> numbers)
      throws IOException, InterruptedException, TimeoutException {
    return openBy(device, numbers, walk());
  }

  /** The lines {@link #OPEN_FILES} prints: every block device each process has open. */
  private List<String> walk() throws IOException, InterruptedException, TimeoutException {
    var walked = shell.exec(List.of("bash", "-c", OPEN_FILES));
    if (!walked.ok()) {
      throw new IOException("Cannot read which processes have devices open: " + walked.reason());
    }
    return walked.stdout().lines().toList();
  }

  /** As {@link #openBy(String, Map)}, from the lines of a walk already made. */
  private List<String> openBy(String device, Map<String, String> numbers, List<String> walked)
      throws IOException, InterruptedException, TimeoutException {
    var uses = new LinkedHashSet<String>();
    for (var line : walked) {
      var fields = line.split("\t", 2);
      if (fields.length < 2) {
        continue;
      }
      if (fields[0].equals("unreadable") && fields[1].matches("[0-9]+")) {
        uses.add(
            "Sail cannot read the files process "
                + fields[1]
                + " has open, so whether it holds "
                + device
                + " cannot be told: run this as root");
      } else if (fields[0].matches("[0-9]+") && fields[1].matches("[0-9a-f]+:[0-9a-f]+")) {
        var number = fields[1].split(":");
        var held = numbers.get(Long.parseLong(number[0], 16) + ":" + Long.parseLong(number[1], 16));
        if (held != null) {
          uses.add("process " + fields[0] + command(fields[0]) + " has " + held + " open");
        }
      }
    }
    return List.copyOf(uses);
  }

  private String command(String pid) throws IOException, InterruptedException, TimeoutException {
    var named = shell.exec(List.of("cat", "/proc/" + pid + "/comm"));
    return named.ok() ? " (" + named.stdout().strip() + ")" : "";
  }

  /**
   * Why {@code member} of {@code device} is held, from its {@link #exclusiveOpen}: it fails while
   * anything holds it exclusively, and finds no node for a partition the kernel dropped while udev
   * re-read the table.
   */
  private Optional<String> exclusive(
      BlockDevice member, BlockDevice device, Map<String, List<String>> signatures)
      throws IOException, InterruptedException, TimeoutException {
    var opened = shell.exec(exclusiveOpen(member.path()));
    if (opened.ok()) {
      return Optional.empty();
    }
    if (opened.stderr().contains(BUSY)) {
      var holds = DeviceRules.holds(member, signatures);
      return Optional.of(
          member.path()
              + (holds.isEmpty() ? "" : " holds " + String.join(", ", holds) + " and")
              + " is held exclusively by something Sail cannot name: a mount in another"
              + " namespace, an array, a pool, or a process that opened it exclusively");
    }
    if (member != device && opened.stderr().contains(GONE)) {
      return Optional.of(
          member.path()
              + " vanished while Sail probed it, so the partition table of "
              + device.path()
              + " is changing: run this again");
    }
    throw new IOException(
        "Cannot tell whether " + member.path() + " is in use: " + opened.reason());
  }
}
