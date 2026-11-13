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
   * The block device at {@code path}, carrying nothing, as {@link PoolDevices#cleared} resolved it,
   * for a ZFS pool; {@code identity} what lsblk said of it that does not move between disks, so the
   * device checked again before the pool is made is the one first cleared.
   */
  record Device(String path, String identity) implements PoolSource {
    @Override
    public String driver() {
      return HostYaml.BACKEND_ZFS;
    }

    @Override
    public String said() {
      return "zfs on " + path + ", one device: no redundancy";
    }
  }

  /**
   * The zpool {@code name} Incus already holds under this pool's name, which a re-run of {@code
   * host init} keeps as it is; never made.
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
