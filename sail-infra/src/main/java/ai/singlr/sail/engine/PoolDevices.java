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
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.TimeoutException;
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
 * <p>A holder is found two ways: the exclusive open fails while anything holds the device
 * exclusively, and every process's {@code /proc/<pid>/fd} names the devices it has open, plainly
 * too (a virtual machine's raw disk, {@code dd}). A consumer that does not keep the device open
 * while Sail looks is not seen.
 */
public final class PoolDevices {

  /** A wipefs type filter no signature matches: with it the probe can erase nothing. */
  static final String NOTHING = "sail_exclusive_probe";

  /**
   * Lists, for each process, the block devices it has open: {@code pid<TAB>device} per open device,
   * and {@code unreadable<TAB>pid} for a process whose open files Sail may not read. An entry or a
   * process gone while it is listed, as a busy process's descriptors come and go, is skipped. A
   * constant script: it takes no input.
   */
  static final String OPEN_FILES =
      """
      for d in /proc/[0-9]*; do
        p=${d#/proc/}
        l=$(ls -l "$d/fd" 2>&1)
        case $l in *'Permission denied'*) [ -e "$d" ] && printf 'unreadable\\t%s\\n' "$p"; continue ;; esac
        printf '%s\\n' "$l" | while IFS= read -r line; do
          case $line in *' -> /dev/'*) printf '%s\\t%s\\n' "$p" "${line##* -> }" ;; esac
        done
      done
      """;

  private static final String BUSY = "Device or resource busy";

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

  /**
   * The device {@code named} names, as Incus may be handed it: one that carries nothing. Refused,
   * with nothing written, otherwise.
   */
  public PoolSource.Device cleared(String named)
      throws IOException, InterruptedException, TimeoutException {
    var path = resolved("--disk", named);
    var place = BlockTree.locate(BlockTree.read(shell), path, label(named, path));
    var label = label(named, place.device().path());
    refuseAny(label, DeviceRules.refusals(place.device(), place.disk()));
    var members = place.device().members();
    var signatures = new LinkedHashMap<String, List<String>>();
    var labels = new LinkedHashMap<String, String>();
    for (var member : members) {
      var rows = listing(member.path());
      signatures.put(member.path(), rows.stream().map(row -> row[2]).distinct().toList());
      rows.stream()
          .filter(row -> row[2].equals("zfs_member") && row.length > 4)
          .forEach(row -> labels.put(member.path(), row[4]));
    }
    refuseAny(label, pools.refusals(signatures.keySet(), place.beside()));
    refuseAny(label, DeviceRules.memberships(place.device(), signatures, labels));
    var busy = new ArrayList<String>();
    for (var member : members) {
      if (busy(member.path())) {
        var held = DeviceRules.holds(member, signatures);
        busy.add(
            member.path()
                + (held.isEmpty() ? "" : " holds " + String.join(", ", held) + " and")
                + " is held open by something lsblk does not show");
      }
    }
    refuseAny(label, busy);
    refuseAny(
        label,
        openBy(
            place.device().path(),
            members.stream()
                .flatMap(member -> Stream.of(member.path(), member.kernelPath()))
                .filter(Objects::nonNull)
                .collect(Collectors.toSet())));
    var holds = DeviceRules.holds(place.device(), signatures);
    if (!holds.isEmpty()) {
      throw new IllegalArgumentException(
          label
              + " holds "
              + String.join(", ", holds)
              + ". Sail erases nothing: if nothing on it is wanted, erase it yourself with sudo"
              + " wipefs --all "
              + path
              + ", then run this again.");
    }
    return new PoolSource.Device(path, place.identity());
  }

  /**
   * The open {@link #busy} makes: {@code wipefs --all} opens the device for exclusive use, which
   * fails with EBUSY while anything holds it. {@code --no-act} skips every write, and the type
   * filter matches no signature, so nothing is erased and no partition table is re-read by wipefs,
   * as an unfiltered {@code --no-act --all} does; udev may re-read one when the device is closed,
   * which writes nothing.
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
   * Why {@code device} may be in use by a process that opened one of {@code paths} without holding
   * it exclusively, as a virtual machine's raw disk or {@code dd} does, read from {@code
   * /proc/<pid>/fd}; a process whose open files cannot be read refuses it too.
   */
  List<String> openBy(String device, Set<String> paths)
      throws IOException, InterruptedException, TimeoutException {
    var walked = shell.exec(List.of("bash", "-c", OPEN_FILES));
    if (!walked.ok()) {
      throw new IOException("Cannot read which processes have devices open: " + walked.reason());
    }
    var uses = new LinkedHashSet<String>();
    for (var line : walked.stdout().lines().toList()) {
      var fields = line.split("\t", 2);
      if (fields.length < 2 || !fields[fields[0].equals("unreadable") ? 1 : 0].matches("[0-9]+")) {
        continue;
      }
      if (fields[0].equals("unreadable")) {
        uses.add(
            "Sail cannot read the files process "
                + fields[1]
                + " has open, so whether it holds "
                + device
                + " cannot be told: run this as root");
      } else if (paths.contains(fields[1])) {
        uses.add("process " + fields[0] + command(fields[0]) + " has " + fields[1] + " open");
      }
    }
    return List.copyOf(uses);
  }

  private String command(String pid) throws IOException, InterruptedException, TimeoutException {
    var named = shell.exec(List.of("cat", "/proc/" + pid + "/comm"));
    return named.ok() ? " (" + named.stdout().strip() + ")" : "";
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
}
