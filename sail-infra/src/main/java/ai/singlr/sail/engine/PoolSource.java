/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.engine;

import ai.singlr.sail.common.Strings;
import ai.singlr.sail.config.HostYaml;

/**
 * What an Incus storage pool is made from: the host's filesystem, a block device (a whole disk or a
 * partition) that ZFS takes whole, an existing zpool, or a file ZFS runs on. {@link IncusStorage}
 * turns each into the one {@code incus storage create} that makes it.
 */
public sealed interface PoolSource {

  /** The Incus storage driver this source is made with. */
  String driver();

  /** This source as the operator reads it. */
  String said();

  /** A directory on the host's filesystem; disk limits on it are advisory. */
  record Dir() implements PoolSource {
    @Override
    public String driver() {
      return HostYaml.BACKEND_DIR;
    }

    @Override
    public String said() {
      return "dir";
    }
  }

  /**
   * The block device at {@code path}, as {@link PoolDevices#cleared} resolved it, for a ZFS pool;
   * {@code wipe} when the operator named it again to have what it holds erased.
   */
  record Device(String path, boolean wipe) implements PoolSource {
    @Override
    public String driver() {
      return HostYaml.BACKEND_ZFS;
    }

    @Override
    public String said() {
      return "zfs on " + path + (wipe ? ", erased first" : "");
    }
  }

  /**
   * The zpool {@code name}, handed to Incus as it is: one Incus already holds under this pool's
   * name, or an empty imported pool a device belongs to (an older Sail made one with {@code zpool
   * create}). Incus requires it empty; nothing on it is erased.
   */
  record Zpool(String name) implements PoolSource {
    @Override
    public String driver() {
      return HostYaml.BACKEND_ZFS;
    }

    @Override
    public String said() {
      return "zpool " + name + " as it is";
    }
  }

  /**
   * A sparse file of {@code sizeMb} megabytes that Incus makes for a ZFS pool: slower than a disk,
   * for trying ZFS on a box with no spare one.
   */
  record File(long sizeMb) implements PoolSource {
    @Override
    public String driver() {
      return HostYaml.BACKEND_ZFS;
    }

    @Override
    public String said() {
      return "zfs in a " + Strings.gibibytes(sizeMb << 20) + " file";
    }
  }
}
