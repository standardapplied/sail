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
import java.util.Objects;
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
        cleared.getLast().size(),
        cleared.getFirst().whole() != cleared.getLast().whole());
  }

  /** A device cleared, its size in bytes, and whether it is a whole disk. */
  private record Cleared(PoolSource.Device device, long size, boolean whole) {}

  /** A device named, where it sits, and how a refusal names it. */
  private record Named(String path, BlockTree.Place place, String label) {}

  /**
   * Each of {@code named} cleared, every rule asked once of each device, in phases: lsblk is read
   * once and its rules, then the pair's, judge every device before the {@code /proc} walk, read
   * once; the probes of every device follow, so the last look at each device comes after the slow
   * reads of all of them, right before the pool is made. A device refused at one phase is asked
   * nothing more, while the other goes on, so one refusal names what is wrong with each, in the
   * order named, with every command for both.
   */
  private List<Cleared> cleared(List<String> named)
      throws IOException, InterruptedException, TimeoutException {
    var refusals = new Refusals(named.size());
    var paths = new ArrayList<String>();
    for (var i = 0; i < named.size(); i++) {
      var one = named.get(i);
      paths.add(refusals.ask(i, () -> resolved("--disk", one)));
    }
    var devices = new ArrayList<Named>();
    var tree = refusals.anyAlive() ? BlockTree.read(shell) : List.<BlockDevice>of();
    for (var i = 0; i < named.size(); i++) {
      var one = named.get(i);
      var path = paths.get(i);
      var device =
          refusals.ask(
              i,
              () -> {
                var place = BlockTree.locate(tree, path, label(one, path));
                return new Named(path, place, label(one, place.device().path()));
              });
      devices.add(device);
      if (device != null) {
        refusals.ask(
            i,
            () -> {
              refuseAny(
                  device.label(),
                  DeviceRules.refusals(device.place().device(), device.place().disk()));
              return device;
            });
      }
    }
    if (devices.size() == 2 && devices.getFirst() != null && devices.getLast() != null) {
      refusals.pair(pairRefusal(devices.getFirst(), devices.getLast()));
    }
    var walked = refusals.anyAlive() ? walk() : List.<String>of();
    for (var i = 0; i < devices.size(); i++) {
      var device = devices.get(i);
      refusals.check(
          i,
          () ->
              refuseAny(
                  device.label(),
                  openBy(device.place().device().path(), numbers(device.place()), walked)));
    }
    var probed = new ArrayList<Probed>();
    for (var i = 0; i < devices.size(); i++) {
      var device = devices.get(i);
      probed.add(refusals.ask(i, () -> probe(device)));
    }
    for (var i = 0; i < devices.size(); i++) {
      var device = devices.get(i);
      var signatures = probed.get(i) == null ? null : probed.get(i).signatures();
      refusals.check(i, () -> refuseAny(device.label(), held(device.place().device(), signatures)));
    }
    for (var i = 0; i < devices.size(); i++) {
      var device = devices.get(i);
      var found = probed.get(i);
      refusals.check(i, () -> refuseHolding(device, found));
    }
    refusals.refuseAny();
    var cleared = new ArrayList<Cleared>();
    for (var device : devices) {
      var place = device.place();
      cleared.add(
          new Cleared(
              new PoolSource.Device(persistent(place, device.path()), place.identity()),
              place.device().size(),
              place.device() == place.disk()));
    }
    return cleared;
  }

  /** A check of one device that refuses it by throwing, or answers what the next one needs. */
  @FunctionalInterface
  private interface Check<T> {
    T ask() throws IOException, InterruptedException, TimeoutException;
  }

  /** A check of one device that answers nothing. */
  @FunctionalInterface
  private interface Rule {
    void check() throws IOException, InterruptedException, TimeoutException;
  }

  /**
   * Why each device named is refused, kept per device so the other goes on; a device refused is
   * asked nothing more. A pair that cannot be a mirror ends every check at once.
   */
  private static final class Refusals {
    private final String[] reasons;
    private String pair;

    Refusals(int devices) {
      this.reasons = new String[devices];
    }

    /** What {@code check} answers for device {@code i}; null once the device is refused. */
    <T> T ask(int i, Check<T> check) throws IOException, InterruptedException, TimeoutException {
      if (reasons[i] != null || pair != null) {
        return null;
      }
      try {
        return check.ask();
      } catch (IllegalArgumentException refused) {
        reasons[i] = refused.getMessage();
        return null;
      }
    }

    void check(int i, Rule rule) throws IOException, InterruptedException, TimeoutException {
      ask(
          i,
          () -> {
            rule.check();
            return Boolean.TRUE;
          });
    }

    void pair(Optional<String> refused) {
      pair = refused.orElse(null);
      refuseAny();
    }

    boolean anyAlive() {
      return pair == null && Stream.of(reasons).anyMatch(Objects::isNull);
    }

    /** Refuses with what is wrong with each device, in the order named, and with the pair. */
    void refuseAny() {
      var all =
          Stream.concat(Stream.of(reasons), Stream.ofNullable(pair))
              .filter(Objects::nonNull)
              .toList();
      if (!all.isEmpty()) {
        throw new IllegalArgumentException(String.join("\n", all));
      }
    }
  }

  /** What the probes read of a device, and what returns it to blank when a zpool left it. */
  private record Probed(Map<String, List<String>> signatures, Optional<String> leftovers) {}

  /**
   * What {@code wipefs} lists on {@code device} and each of its partitions, refused when any is a
   * device of an imported pool or a member of anything other than a zpool whose whole-disk layout
   * is all the device holds ({@link DeviceRules#zpoolLeftovers}).
   */
  private Probed probe(Named device) throws IOException, InterruptedException, TimeoutException {
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
    var importable = labels.isEmpty() ? Set.<String>of() : pools.importable();
    var leftovers = DeviceRules.zpoolLeftovers(place.device(), signatures, labels, importable);
    if (leftovers.isEmpty()) {
      refuseAny(
          device.label(), DeviceRules.memberships(place.device(), signatures, labels, importable));
    }
    return new Probed(signatures, leftovers);
  }

  /**
   * Refuses {@code device} for what a zpool left on it, naming the commands that return it to
   * blank; else for a partition type a pool may not take, or for anything the probes found on it,
   * naming the erase the operator may run.
   */
  private static void refuseHolding(Named device, Probed probed) {
    if (probed.leftovers().isPresent()) {
      throw new IllegalArgumentException(device.label() + " " + probed.leftovers().get() + ".");
    }
    refuseAny(device.label(), DeviceRules.partitionTypes(device.place().device()));
    var holds = DeviceRules.holds(device.place().device(), probed.signatures());
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

  /** Why two devices cannot be a mirror: not on two disks, or of sizes zpool refuses. */
  private static Optional<String> pairRefusal(Named one, Named other) {
    var reasons = DeviceRules.mirrorable(one.place(), other.place());
    return reasons.isEmpty()
        ? Optional.empty()
        : Optional.of(
            one.label()
                + " and "
                + other.label()
                + " cannot be a mirror: "
                + String.join("; ", reasons)
                + ".");
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
