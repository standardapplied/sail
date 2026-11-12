/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.engine;

import ai.singlr.sail.common.Strings;

/**
 * The ceiling on the ZFS read cache (the ARC), held here and nowhere else. Left unset, ZFS lets its
 * cache grow to most of the box's memory, the larger of all of it less 1 GiB and five eighths of it
 * (OpenZFS {@code zfs(4)}, {@code zfs_arc_max}), memory the containers need. Sail caps it at a
 * tenth of memory, the default Proxmox VE sets, and never below the 64 MiB ZFS ignores a cap under.
 * A tenth is always above the cache's floor, a thirty-second of memory, under which ZFS ignores the
 * cap too. The cap is a module option, so it holds from the module's next load and across reboots,
 * and is written to the loaded module as well, which then shrinks its cache under memory pressure.
 */
public record ZfsCacheCap(long bytes) {

  static final String PATH = "/etc/modprobe.d/sail-zfs.conf";
  static final String LOADED = "/sys/module/zfs/parameters/zfs_arc_max";
  static final long FLOOR = 64L << 20;
  private static final long MEGABYTE = 1L << 20;
  private static final int FRACTION = 10;

  /** The cap for a box with {@code memoryMb} megabytes of memory. */
  public static ZfsCacheCap forMemory(long memoryMb) {
    return new ZfsCacheCap(Math.max(memoryMb * MEGABYTE / FRACTION, FLOOR));
  }

  /** The module option file, and the write to the loaded module when ZFS is already in. */
  public HostDropIn dropIn() {
    return new HostDropIn(
        "ZFS cache cap",
        PATH,
        "# Managed by sail: caps the ZFS read cache, so the rest of memory is the containers'.\n"
            + "options zfs zfs_arc_max="
            + bytes
            + "\n",
        "\nif [ -e " + LOADED + " ]; then printf '%s' " + bytes + " > " + LOADED + "; fi\n");
  }

  /** What the step that sets it says. */
  public String said() {
    return "ZFS cache capped at " + Strings.gibibytes(bytes);
  }
}
