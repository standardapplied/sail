/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.engine;

import ai.singlr.sail.common.Strings;
import java.io.IOException;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeoutException;

/**
 * The ceiling on the ZFS read cache (the ARC), held here and nowhere else. Left unset, ZFS lets its
 * cache grow to most of the box's memory, the larger of all of it less 1 GiB and five eighths of it
 * (OpenZFS {@code zfs(4)}, {@code zfs_arc_max}), memory the containers need. Sail caps it as
 * Proxmox VE does, at a tenth of memory and at most 16 GiB, and never below the 64 MiB ZFS ignores
 * a cap under. ZFS also ignores a cap at or under the cache's floor, a thirty-second of memory by
 * default, so on a box where the ceiling falls under it the floor is set to half the cap.
 *
 * <p>The cap is a module option, so it holds from the module's next load and across reboots, and is
 * written to the loaded module as well. Where the root filesystem is on ZFS the module loads from
 * the initramfs, which carries a copy of {@code /etc/modprobe.d} (Ubuntu's {@code mkinitramfs}), so
 * the initramfs is rebuilt. A cap the operator set, in any file modprobe reads, is theirs: Sail
 * writes nothing, removes the cap it wrote before so theirs is the one modprobe applies, and says
 * so.
 */
public record ZfsCacheCap(long bytes, long floor) {

  static final String PATH = "/etc/modprobe.d/sail-zfs.conf";
  static final String LOADED = "/sys/module/zfs/parameters/zfs_arc_";
  static final long FLOOR = 64L << 20;
  static final long CEILING = 16L << 30;
  private static final long MEGABYTE = 1L << 20;
  private static final int FRACTION = 10;
  private static final int DEFAULT_FLOOR_FRACTION = 32;
  private static final Duration INITRAMFS_TIMEOUT = Duration.ofMinutes(5);

  /** The cap for a box with {@code memoryMb} megabytes of memory. */
  public static ZfsCacheCap forMemory(long memoryMb) {
    var memory = memoryMb * MEGABYTE;
    var cap = Math.min(Math.max(memory / FRACTION, FLOOR), CEILING);
    return new ZfsCacheCap(cap, memory / DEFAULT_FLOOR_FRACTION >= cap ? cap / 2 : 0);
  }

  /** The module option file, and the write to the loaded module when ZFS is already in. */
  public HostDropIn dropIn() {
    var floorOption = floor > 0 ? " zfs_arc_min=" + floor : "";
    var floorWrite = floor > 0 ? "printf '%s' " + floor + " > " + LOADED + "min; " : "";
    return new HostDropIn(
        "ZFS cache cap",
        PATH,
        "# Managed by sail: caps the ZFS read cache, so the rest of memory is the containers'.\n"
            + "options zfs zfs_arc_max="
            + bytes
            + floorOption
            + "\n",
        "\nif [ -e "
            + LOADED
            + "max ]; then "
            + floorWrite
            + "printf '%s' "
            + bytes
            + " > "
            + LOADED
            + "max; fi\n");
  }

  /**
   * Writes the cap on the machine {@code shell} runs on, unless the operator set one, and says what
   * holds. The operator's cap is one modprobe applies ({@code modprobe --showconfig}, every file it
   * reads) that Sail did not write, or one the loaded module holds that Sail did not write (set on
   * the kernel command line, or by hand).
   */
  public String install(ShellExec shell)
      throws IOException, InterruptedException, TimeoutException {
    var ours = shell.exec(List.of("grep", "-ho", "zfs_arc_max=[0-9]*", PATH));
    var written = ours.ok() ? ours.stdout().strip().replace("zfs_arc_max=", "") : "";
    var theirs = applied(shell).stream().filter(value -> !value.equals(written)).toList();
    if (!theirs.isEmpty()) {
      return "ZFS cache cap left as you set it (zfs_arc_max="
          + theirs.getLast()
          + ")"
          + (written.isEmpty() ? "" : removed(shell));
    }
    var live = shell.exec(List.of("cat", LOADED + "max"));
    var held = live.ok() ? live.stdout().strip() : "0";
    if (!held.equals("0") && !held.equals(written) && !held.equals(String.valueOf(bytes))) {
      return "ZFS cache cap left as set on the loaded module (zfs_arc_max=" + held + ")";
    }
    dropIn().install(shell);
    var root = shell.exec(List.of("findmnt", "-n", "-o", "FSTYPE", "/"));
    if (!root.ok() || !root.stdout().strip().equals("zfs")) {
      return said();
    }
    var rebuilt = shell.exec(List.of("update-initramfs", "-u"), null, INITRAMFS_TIMEOUT);
    if (!rebuilt.ok()) {
      throw new IOException(
          "Wrote " + PATH + " but could not rebuild the initramfs: " + rebuilt.reason());
    }
    return said() + "; initramfs rebuilt, since / is on ZFS and the module loads from it";
  }

  /**
   * The {@code zfs_arc_max} values modprobe applies, in the order it reads them, the last the one
   * the module takes; none where modprobe cannot say.
   */
  private static List<String> applied(ShellExec shell)
      throws IOException, InterruptedException, TimeoutException {
    var config = shell.exec(List.of("modprobe", "--showconfig"));
    if (!config.ok()) {
      return List.of();
    }
    return config
        .stdout()
        .lines()
        .map(String::strip)
        .filter(line -> line.startsWith("options zfs "))
        .flatMap(line -> Arrays.stream(line.split("\\s+")))
        .filter(word -> word.startsWith("zfs_arc_max="))
        .map(word -> word.substring("zfs_arc_max=".length()))
        .toList();
  }

  /** Removes the cap Sail wrote before, so the operator's is the only one modprobe applies. */
  private static String removed(ShellExec shell)
      throws IOException, InterruptedException, TimeoutException {
    var removed = shell.exec(List.of("rm", "-f", PATH));
    if (!removed.ok()) {
      throw new IOException(
          "Cannot remove " + PATH + ", the cap Sail wrote before yours: " + removed.reason());
    }
    return "; removed the cap Sail wrote before ("
        + PATH
        + "), so yours is the one modprobe applies";
  }

  /** What the step that sets it says. */
  public String said() {
    return "ZFS cache capped at " + Strings.gibibytes(bytes);
  }
}
