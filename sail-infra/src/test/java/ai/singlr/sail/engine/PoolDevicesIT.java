/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@link PoolDevices} against the runner's own disks, read-only: lsblk, wipefs without writing and
 * realpath, as root. Every disk and partition the runner has carries something, the system or the
 * runner's own files, so each must be refused naming what it holds, and none offered. Nothing here
 * creates a pool or erases anything.
 */
class PoolDevicesIT extends AbstractIncusIT {

  @Test
  void everyDiskAndPartitionOfTheRunnerIsRefusedWithAndWithoutTheForceFlag() throws Exception {
    ensureIncusOrSkip();
    var root = new RootShell();
    var devices = new PoolDevices(root);
    System.out.println("LSBLK " + root.exec(BlockTree.LSBLK).stdout());
    var listed = root.exec(List.of("lsblk", "-nrpo", "PATH,TYPE"));
    assertTrue(listed.ok(), listed.reason());
    var paths =
        listed
            .stdout()
            .lines()
            .map(line -> line.split(" "))
            .filter(row -> row[1].equals("disk") || row[1].equals("part"))
            .map(row -> row[0])
            .filter(path -> !path.startsWith("/dev/loop"))
            .toList();
    assertFalse(paths.isEmpty(), listed.stdout());

    var refusals = new ArrayList<String>();
    for (var path : paths) {
      var refused =
          assertThrows(
              IllegalArgumentException.class, () -> devices.cleared(path), path + " was cleared");
      assertTrue(refused.getMessage().startsWith(path + " "), refused.getMessage());
      refusals.add("REFUSED " + path + ": " + refused.getMessage());
    }
    refusals.forEach(System.out::println);

    assertEquals(List.of(), devices.candidates());
  }

  /**
   * The probe's exclusive open on a loop device that something holds: a mounted filesystem, then a
   * device-mapper device built on it. Each fails the open with EBUSY, as {@link PoolDevices} reads
   * it. The mapping, named by its {@code /dev/mapper} path, is refused as what it is.
   */
  @Test
  void theExclusiveOpenFindsARealHolderBusy(@TempDir Path dir) throws Exception {
    ensureIncusOrSkip();
    var root = new RootShell();
    var loop = attach(root, dir.resolve("held.img"), "mkfs.ext4 -qF \"$1\"");
    var mount = dir.resolve("mnt");
    try {
      assertTrue(root.exec(List.of("mkdir", "-p", mount.toString())).ok());
      assertTrue(root.exec(List.of("mount", loop, mount.toString())).ok());
      var mounted = root.exec(PoolDevices.exclusiveOpen(loop));
      assertTrue(root.exec(List.of("umount", mount.toString())).ok());
      var sectors = root.exec(List.of("blockdev", "--getsz", loop)).stdout().strip();
      assertTrue(
          root.exec(
                  List.of(
                      "dmsetup",
                      "create",
                      "sail-it-hold",
                      "--table",
                      "0 " + sectors + " linear " + loop + " 0"))
              .ok());
      var mapped = root.exec(PoolDevices.exclusiveOpen(loop));
      var mapper =
          assertThrows(
                  IllegalArgumentException.class,
                  () -> new PoolDevices(root).cleared("/dev/mapper/sail-it-hold"))
              .getMessage();
      System.out.println("MAPPER [" + mapper + "]");
      assertTrue(
          mapper.startsWith(
              "/dev/mapper/sail-it-hold is refused: it is a dm device, not"
                  + " a disk or a partition"),
          mapper);
      System.out.println(
          "MOUNTED " + loop + " exit " + mounted.exitCode() + " [" + mounted.stderr() + "]");
      System.out.println(
          "MAPPED " + loop + " exit " + mapped.exitCode() + " [" + mapped.stderr() + "]");

      for (var held : List.of(mounted, mapped)) {
        assertEquals(1, held.exitCode());
        assertEquals(
            "wipefs: error: " + loop + ": probing initialization failed: Device or resource busy",
            held.stderr().strip());
      }
    } finally {
      root.exec(List.of("dmsetup", "remove", "sail-it-hold"));
      root.exec(List.of("losetup", "-d", loop));
    }
  }

  /**
   * A pool with a device in every class zpool lists apart (log, cache, spare, special): each is a
   * member as {@link PoolDevices#vdevs} reads {@code zpool list -vHP}.
   */
  @Test
  void everyClassOfAPoolsDevicesIsAMember(@TempDir Path dir) throws Exception {
    ensureIncusOrSkip();
    var root = new RootShell();
    var loops = new ArrayList<String>();
    for (var name : List.of("data", "log", "cache", "spare", "special")) {
      loops.add(attach(root, dir.resolve(name + ".img"), "true"));
    }
    try {
      var made =
          root.exec(
              List.of(
                  "zpool",
                  "create",
                  "sail-it-classes",
                  loops.get(0),
                  "log",
                  loops.get(1),
                  "cache",
                  loops.get(2),
                  "spare",
                  loops.get(3),
                  "special",
                  loops.get(4)));
      assertTrue(made.ok(), made.reason());
      var names = root.exec(List.of("zpool", "list", "-H", "-o", "name"));
      var listed = root.exec(List.of("zpool", "list", "-vHP", "sail-it-classes"));
      System.out.println("NAMES [" + names.stdout() + "]");
      System.out.println("CLASSES [" + listed.stdout() + "]");

      assertTrue(names.stdout().lines().toList().contains("sail-it-classes"), names.stdout());
      assertEquals(
          loops.stream().sorted().toList(),
          ImportedPools.vdevs(listed.stdout().lines().toList()).stream().sorted().toList());
    } finally {
      root.exec(List.of("zpool", "destroy", "sail-it-classes"));
      loops.forEach(
          loop -> {
            try {
              root.exec(List.of("losetup", "-d", loop));
            } catch (Exception ignored) {
            }
          });
    }
  }

  /** A loop device over a new 128 MiB file at {@code file}, made ready by {@code prepare}. */
  /**
   * The pool Sail has Incus make on a device, exactly as {@link IncusStorage#createCommand} builds
   * it: never {@code source.wipe}, so Incus runs {@code zpool create} without {@code -f}. A blank
   * loop device becomes the pool; one that took ext4 after Sail's checks is refused by zpool
   * itself, and its ext4 is left as it was.
   */
  @Test
  void zpoolTakesABlankDeviceAndRefusesOneThatTookAFilesystemAfterTheChecks(@TempDir Path dir)
      throws Exception {
    ensureIncusOrSkip();
    var root = new RootShell();
    var storage = new IncusStorage(shell);
    var blank = attach(root, dir.resolve("blank.img"), "true");
    var taken = attach(root, dir.resolve("taken.img"), "true");
    try {
      var made = storage.create("sail-it-blank", new PoolSource.Device(blank, "checked"));
      var listed = storage.find("sail-it-blank");
      assertTrue(root.exec(List.of("mkfs.ext4", "-qF", taken)).ok());
      var refused = storage.create("sail-it-taken", new PoolSource.Device(taken, "checked"));
      var kept = root.exec(List.of("wipefs", "--", taken));
      System.out.println("BLANK exit " + made.exitCode() + " [" + made.reason() + "] " + listed);
      System.out.println("TAKEN exit " + refused.exitCode() + " [" + refused.reason() + "]");
      System.out.println("KEPT [" + kept.stdout() + "]");

      assertTrue(made.ok(), made.reason());
      assertEquals("zfs", listed.orElseThrow().driver());
      assertEquals(blank, listed.orElseThrow().source());
      assertFalse(refused.ok(), "zpool took a device holding ext4");
      assertTrue(refused.reason().contains("ext4"), refused.reason());
      assertTrue(storage.find("sail-it-taken").isEmpty());
      assertTrue(kept.stdout().contains("ext4"), kept.stdout());
    } finally {
      shell.exec(List.of("incus", "storage", "delete", "sail-it-blank"));
      shell.exec(List.of("incus", "storage", "delete", "sail-it-taken"));
      root.exec(List.of("losetup", "-d", blank));
      root.exec(List.of("losetup", "-d", taken));
    }
  }

  /**
   * A loop device a process holds open read-write through a device node of its own, without the
   * exclusive open the probe sees, is refused by the walk of {@code /proc/<pid>/fd}, matched by
   * device number and naming the process; once it lets go, nothing holds it.
   */
  @Test
  void aDeviceAProcessHoldsOpenPlainlyIsRefusedUntilItLetsGo(@TempDir Path dir) throws Exception {
    ensureIncusOrSkip();
    var root = new RootShell();
    var loop = attach(root, dir.resolve("open.img"), "true");
    var devices = new PoolDevices(root);
    var number = root.exec(List.of("lsblk", "-dno", "MAJ:MIN", loop)).stdout().strip();
    var node = dir.resolve("its-own-node").toString();
    try {
      var parts = number.split(":");
      assertTrue(root.exec(List.of("mknod", node, "b", parts[0], parts[1])).ok());
      var holder =
          new ProcessBuilder(
                  "sudo", "-n", "bash", "-c", "exec 3<>\"$1\"; echo held; read -r _", "bash", node)
              .redirectErrorStream(true)
              .start();
      List<String> held;
      try (var said = new BufferedReader(new InputStreamReader(holder.getInputStream()))) {
        assertEquals("held", said.readLine());
        held = devices.openBy(loop, Map.of(number, loop));
        holder.getOutputStream().close();
        assertTrue(holder.waitFor(60, TimeUnit.SECONDS), "the holder did not let go");
      }
      var released = devices.openBy(loop, Map.of(number, loop));
      System.out.println("HELD " + held + " RELEASED " + released);

      assertEquals(1, held.size(), held.toString());
      assertTrue(
          held.getFirst().matches("process [0-9]+ \\(bash\\) has " + loop + " open"),
          held.toString());
      assertEquals(List.of(), released);
    } finally {
      root.exec(List.of("rm", "-f", node));
      root.exec(List.of("losetup", "-d", loop));
    }
  }

  private static String attach(RootShell root, Path file, String prepare) throws Exception {
    var attached =
        root.exec(
            List.of(
                "bash",
                "-c",
                "truncate -s 128M \"$1\" && " + prepare + " && losetup -f --show \"$1\"",
                "bash",
                file.toString()));
    assertTrue(attached.ok(), attached.reason());
    return attached.stdout().strip();
  }

  /**
   * The probe's exclusive open on a loop device holding a partition table, nothing else holding it:
   * it succeeds and prints nothing, where the unfiltered {@code --no-act --all} lists the table as
   * erased and asks the kernel to re-read it. The loop device's file is the only thing written, and
   * only to make it.
   */
  @Test
  void theExclusiveOpenErasesNothingAndReReadsNoPartitionTable(@TempDir Path dir) throws Exception {
    ensureIncusOrSkip();
    var root = new RootShell();
    var file = dir.resolve("probe.img");
    var attached =
        root.exec(
            List.of(
                "bash",
                "-c",
                "truncate -s 64M \"$1\" && printf 'label: gpt\\n' | sfdisk -q \"$1\" && losetup -fP"
                    + " --show \"$1\"",
                "bash",
                file.toString()));
    assertTrue(attached.ok(), attached.reason());
    var loop = attached.stdout().strip();
    try {
      var probed = root.exec(PoolDevices.exclusiveOpen(loop));
      var unfiltered = root.exec(List.of("wipefs", "--no-act", "--all", "--", loop));
      System.out.println(
          "PROBE "
              + loop
              + " exit "
              + probed.exitCode()
              + " ["
              + probed.stdout()
              + probed.stderr()
              + "]");
      System.out.println(
          "UNFILTERED "
              + loop
              + " exit "
              + unfiltered.exitCode()
              + " ["
              + unfiltered.stdout()
              + unfiltered.stderr()
              + "]");

      assertEquals(new ShellExec.Result(0, "", ""), probed);
      assertTrue(
          unfiltered.stdout().contains("calling ioctl to re-read partition table"),
          unfiltered.stdout());
    } finally {
      root.exec(List.of("losetup", "-d", loop));
    }
  }
}
