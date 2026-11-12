/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.engine;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeoutException;

/**
 * The zpools this box has imported, read pool by pool: every vdev line of {@code zpool list -vHP
 * <pool>} is a member, whatever class (log, cache, spare, special, dedup) it is listed under.
 */
final class ImportedPools {

  private final ShellExec shell;
  private final IncusStorage storage;

  ImportedPools(ShellExec shell) {
    this.shell = shell;
    this.storage = new IncusStorage(shell);
  }

  /**
   * Why each of {@code paths} that is a device of an imported pool, or that is {@code beside} one
   * on the same disk, may not be taken; none where ZFS is not installed.
   */
  List<String> refusals(Set<String> paths, Set<String> beside)
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
}
