/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.engine;

import ai.singlr.sail.common.Strings;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.concurrent.TimeoutException;

/**
 * The one place that decides whether a block device may become a ZFS pool. Sail erases nothing, so
 * a device is taken only when it carries nothing. It reads the whole device tree ({@link
 * BlockTree}), judges what lsblk shows ({@link DeviceRules}), then probes the device and each of
 * its partitions directly: {@code wipefs}'s listing for what they hold, {@code zpool list} for an
 * imported pool they belong to ({@link ImportedPools}), and an exclusive open that writes nothing
 * for any other holder lsblk cannot see (a mount in another namespace, a pool, an array).
 *
 * <p>Taken: a plain disk (at the top of the tree, of type disk) or a partition of one, whose
 * partition type lsblk reads and is no boot, extended or ZFS-reserved type, that holds no signature
 * of any kind, that no holder has open, and that neither it nor another partition of its disk is a
 * device of an imported pool. Where it sits, the system's disk included, does not matter. Anything
 * else is refused by name; a device that only holds something names the {@code wipefs --all} that
 * would erase it, for the operator to run. Names are resolved first, so a symlink is judged as the
 * device it points at, and what a device is cleared as carries what lsblk says of it that does not
 * move between disks. A disk with no partition table and no signature is blank.
 *
 * <p>The exclusive open sees only holders that open a device exclusively: a process that opens it
 * plainly (a virtual machine's raw disk, {@code dd}) is not seen.
 */
public final class PoolDevices {

  /** A wipefs type filter no signature matches: with it the probe can erase nothing. */
  static final String NOTHING = "sail_exclusive_probe";

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
    for (var member : members) {
      signatures.put(member.path(), signatures(member.path()));
    }
    refuseAny(label, DeviceRules.memberships(signatures));
    refuseAny(label, pools.refusals(signatures.keySet(), place.beside()));
    var busy = new ArrayList<String>();
    for (var member : members) {
      if (busy(member.path())) {
        busy.add(member.path() + " is held open by something lsblk does not show");
      }
    }
    refuseAny(label, busy);
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
   * filter matches no signature, so nothing is erased and no partition table is re-read, as an
   * unfiltered {@code --no-act --all} does.
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
