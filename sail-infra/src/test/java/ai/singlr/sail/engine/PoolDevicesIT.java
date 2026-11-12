/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@link PoolDevices} against the runner's own disks, read-only: lsblk, wipefs without writing and
 * realpath, as root. The runner has one disk and the system runs from it, so every disk and
 * partition must be refused, with the force flag and without, and none offered. Nothing here
 * creates a pool or passes a wipe to anything.
 */
class PoolDevicesIT extends AbstractIncusIT {

  @Test
  void everyDiskAndPartitionOfTheRunnerIsRefusedWithAndWithoutTheForceFlag() throws Exception {
    ensureIncusOrSkip();
    var root = new RootShell();
    var devices = new PoolDevices(root);
    System.out.println("LSBLK " + root.exec(PoolDevices.LSBLK).stdout());
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
      for (var force : new String[] {null, path}) {
        var refused =
            assertThrows(
                IllegalArgumentException.class,
                () -> devices.cleared(path, force),
                path + " was cleared with force=" + force);
        assertTrue(
            refused.getMessage().startsWith(path + " is refused, even with --force-wipe: "),
            refused.getMessage());
        refusals.add("REFUSED " + path + " force=" + force + ": " + refused.getMessage());
      }
    }
    refusals.forEach(System.out::println);

    assertEquals(List.of(), devices.candidates());
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
