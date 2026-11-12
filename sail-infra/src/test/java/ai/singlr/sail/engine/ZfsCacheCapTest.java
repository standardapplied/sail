/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.List;
import org.junit.jupiter.api.Test;

class ZfsCacheCapTest {

  private static final long GIB = 1L << 30;

  @Test
  void theCapIsATenthOfMemoryUpTo16GiB() {
    assertEquals(new ZfsCacheCap(6871947673L, 0), ZfsCacheCap.forMemory(65536));
    assertEquals(new ZfsCacheCap(13743895347L, 0), ZfsCacheCap.forMemory(131072));
    assertEquals(new ZfsCacheCap(16 * GIB, 0), ZfsCacheCap.forMemory(262144));
  }

  @Test
  void whereTheCeilingFallsUnderTheCachesFloorTheFloorIsLowered() {
    assertEquals(new ZfsCacheCap(16 * GIB, 8 * GIB), ZfsCacheCap.forMemory(524288));
    assertEquals(new ZfsCacheCap(16 * GIB, 8 * GIB), ZfsCacheCap.forMemory(1048576));
    assertEquals(new ZfsCacheCap(16 * GIB, 0), ZfsCacheCap.forMemory(524287));
  }

  @Test
  void theCapIsNeverBelowWhatZfsHonours() {
    assertEquals(64L << 20, ZfsCacheCap.forMemory(512).bytes());
    assertEquals(64L << 20, ZfsCacheCap.forMemory(640).bytes());
    assertEquals(67213721L, ZfsCacheCap.forMemory(641).bytes());
  }

  @Test
  void theCapIsAModuleOptionAndIsWrittenToTheLoadedModule() {
    var dropIn = ZfsCacheCap.forMemory(65536).dropIn();

    assertEquals("/etc/modprobe.d/sail-zfs.conf", dropIn.path());
    assertEquals(
        "# Managed by sail: caps the ZFS read cache, so the rest of memory is the containers'.\n"
            + "options zfs zfs_arc_max=6871947673\n",
        dropIn.content());
    assertEquals(
        "\nif [ -e /sys/module/zfs/parameters/zfs_arc_max ]; then printf '%s' 6871947673 >"
            + " /sys/module/zfs/parameters/zfs_arc_max; fi\n",
        dropIn.reload());
    assertEquals("ZFS cache capped at 6.4 GiB", ZfsCacheCap.forMemory(65536).said());
  }

  @Test
  void aLoweredFloorIsWrittenBeforeTheCap() {
    var dropIn = ZfsCacheCap.forMemory(1048576).dropIn();

    assertEquals(
        "# Managed by sail: caps the ZFS read cache, so the rest of memory is the containers'.\n"
            + "options zfs zfs_arc_max=17179869184 zfs_arc_min=8589934592\n",
        dropIn.content());
    assertEquals(
        "\nif [ -e /sys/module/zfs/parameters/zfs_arc_max ]; then printf '%s' 8589934592 >"
            + " /sys/module/zfs/parameters/zfs_arc_min; printf '%s' 17179869184 >"
            + " /sys/module/zfs/parameters/zfs_arc_max; fi\n",
        dropIn.reload());
  }

  @Test
  void aCapTheOperatorSetIsTheirsAndNothingIsWritten() throws Exception {
    var shell =
        new ScriptedShellExecutor()
            .onOk("grep -rsH", "/etc/modprobe.d/zfs.conf:options zfs zfs_arc_max=4294967296\n");

    var said = ZfsCacheCap.forMemory(65536).install(shell);

    assertEquals(
        "ZFS cache cap left as you set it (/etc/modprobe.d/zfs.conf:options zfs"
            + " zfs_arc_max=4294967296)",
        said);
    assertEquals(
        List.of(
            List.of(
                "grep",
                "-rsH",
                "--include=*.conf",
                "--exclude=sail-zfs.conf",
                "-E",
                "^[[:space:]]*options[[:space:]]+zfs[[:space:]].*zfs_arc_max=",
                "/etc/modprobe.d")),
        shell.arguments());
  }

  @Test
  void onlyFilesModprobeReadsCount() throws Exception {
    var shell =
        new ScriptedShellExecutor()
            .onFail("grep -rsH", "")
            .onOk("cat /sys/module/zfs/parameters/zfs_arc_max", "0\n")
            .onOk("bash -c")
            .onOk("findmnt", "ext4\n");

    ZfsCacheCap.forMemory(65536).install(shell);

    assertTrue(
        shell.arguments().getFirst().contains("--include=*.conf"), shell.invocations().toString());
  }

  @Test
  void aCapSailWroteBeforeIsWrittenAgainForThisMemory() throws Exception {
    var cap = ZfsCacheCap.forMemory(131072);
    var shell =
        new ScriptedShellExecutor()
            .onFail("grep -rsH", "")
            .onOk("cat /sys/module/zfs/parameters/zfs_arc_max", "6871947673\n")
            .onOk("grep -ho", "zfs_arc_max=6871947673\n")
            .onOk("bash -c")
            .onOk("findmnt", "ext4\n");

    assertEquals("ZFS cache capped at 12.8 GiB", cap.install(shell));
    assertTrue(shell.arguments().contains(cap.dropIn().installCommand(ZfsCacheCap.PATH)));
  }

  @Test
  void aCapSetOnTheLoadedModuleIsKeptAndNamed() throws Exception {
    var shell =
        new ScriptedShellExecutor()
            .onFail("grep -rsH", "")
            .onOk("cat /sys/module/zfs/parameters/zfs_arc_max", "4294967296\n");

    assertEquals(
        "ZFS cache cap left as set on the loaded module (zfs_arc_max=4294967296)",
        ZfsCacheCap.forMemory(65536).install(shell));
    assertTrue(shell.invocations().stream().noneMatch(c -> c.startsWith("bash -c")));
  }

  @Test
  void theCapIsWrittenWhereTheOperatorSetNone() throws Exception {
    var cap = ZfsCacheCap.forMemory(65536);
    var shell =
        new ScriptedShellExecutor()
            .onFail("grep -rsH", "")
            .onOk("bash -c")
            .onOk("findmnt", "ext4\n");

    assertEquals("ZFS cache capped at 6.4 GiB", cap.install(shell));
    assertTrue(shell.arguments().contains(cap.dropIn().installCommand(ZfsCacheCap.PATH)));
    assertTrue(shell.invocations().stream().noneMatch(c -> c.startsWith("update-initramfs")));
  }

  @Test
  void whereTheRootIsOnZfsTheInitramfsIsRebuiltAfterTheCap() throws Exception {
    var cap = ZfsCacheCap.forMemory(65536);
    var shell =
        new ScriptedShellExecutor()
            .onFail("grep -rsH", "")
            .onOk("bash -c")
            .onOk("findmnt", "zfs\n")
            .onOk("update-initramfs");

    assertEquals(
        "ZFS cache capped at 6.4 GiB; initramfs rebuilt, since / is on ZFS and the module loads"
            + " from it",
        cap.install(shell));
    var written = shell.arguments().indexOf(cap.dropIn().installCommand(ZfsCacheCap.PATH));
    var rebuilt = shell.arguments().indexOf(List.of("update-initramfs", "-u"));
    assertTrue(0 <= written && written < rebuilt, shell.invocations().toString());
  }

  @Test
  void anInitramfsThatCannotBeRebuiltSaysSo() {
    var shell =
        new ScriptedShellExecutor()
            .onFail("grep -rsH", "")
            .onOk("bash -c")
            .onOk("findmnt", "zfs\n")
            .onFail("update-initramfs", "E: /boot is full");

    var failed = assertThrows(IOException.class, () -> ZfsCacheCap.forMemory(65536).install(shell));

    assertEquals(
        "Wrote /etc/modprobe.d/sail-zfs.conf but could not rebuild the initramfs: E: /boot is full",
        failed.getMessage());
  }
}
