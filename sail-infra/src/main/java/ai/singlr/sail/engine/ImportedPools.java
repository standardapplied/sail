/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.engine;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;

/**
 * The zpools this box has imported, read pool by pool: every vdev line of {@code zpool list -vHP
 * <pool>} is a member, whatever class (log, cache, spare, special, dedup) it is listed under. A
 * zpool that does not answer within {@link Zpool#BOUND} refuses, never reads as no pools, since
 * what it would have said decides whether a device is free.
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

  /** The names of the zpools this box has imported; none where ZFS is not installed. */
  Set<String> names() throws IOException, InterruptedException, TimeoutException {
    if (!shell.exec(List.of("which", "zpool")).ok()) {
      return Set.of();
    }
    return Set.copyOf(listed(List.of("zpool", "list", "-H", "-o", "name")));
  }

  /**
   * The pools {@code zpool import} lists as there to import, from what it reads on this box's
   * devices; it imports nothing. None where ZFS is not installed or it lists none.
   */
  Set<String> importable() throws IOException, InterruptedException, TimeoutException {
    if (!shell.exec(List.of("which", "zpool")).ok()) {
      return Set.of();
    }
    var listed = read(List.of("zpool", "import"));
    return (listed.stdout() + "\n" + listed.stderr())
        .lines()
        .map(String::strip)
        .filter(line -> line.startsWith("pool: "))
        .map(line -> line.substring("pool: ".length()).strip())
        .collect(Collectors.toSet());
  }

  /** The class {@link #classes} lists a pool's data vdevs under: those before any header. */
  static final String DATA = "";

  /**
   * The vdev names of a pool's {@code zpool list -vH} lines by the class each is listed under, in
   * zpool's order: its data vdevs under {@link #DATA}, and each other under the header zpool prints
   * above it ({@code special}, {@code logs}, {@code cache}, {@code spare}, {@code dedup}), a line
   * that, unlike a vdev's row, does not start with a tab, its name padded with spaces. A vdev's
   * name is its row's first tab-separated field; the pool's own line, first, is no vdev.
   */
  static Map<String, List<String>> classes(List<String> lines) {
    var classes = new LinkedHashMap<String, List<String>>();
    var listed = new ArrayList<String>();
    classes.put(DATA, listed);
    for (var line : lines.stream().filter(line -> !line.isBlank()).skip(1).toList()) {
      if (line.startsWith("\t")) {
        listed.add(line.strip().split("\t")[0]);
      } else {
        listed = new ArrayList<>();
        classes.put(line.strip().split("\\s+")[0], listed);
      }
    }
    return classes;
  }

  /** The vdev paths of a pool's {@code zpool list -vHP} lines, of every class. */
  static List<String> vdevs(List<String> lines) {
    return classes(lines).values().stream()
        .flatMap(List::stream)
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
    var listed = read(command);
    if (!listed.ok()) {
      throw new IOException("Cannot list the imported pools: " + listed.reason());
    }
    return listed.stdout().lines().filter(line -> !line.isBlank()).toList();
  }

  /** What zpool answers to {@code command}, refused where it does not answer in time. */
  private ShellExec.Result read(List<String> command) throws IOException, InterruptedException {
    try {
      return Zpool.read(shell, command);
    } catch (TimeoutException e) {
      throw new IllegalStateException(
          "zpool "
              + Zpool.NO_ANSWER
              + " ("
              + String.join(" ", command)
              + "), so Sail cannot tell whether a device is in use or a zpool's name is taken."
              + " Nothing was written; run this again once zpool answers.");
    }
  }
}
