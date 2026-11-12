/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** The one lsblk call {@link BlockTree} makes. */
class BlockTreeTest {

  @Test
  void aDeviceListedWithoutAKernelNameHasNoKernelPath() {
    var listed = BlockDevice.listed(List.of(Map.of("path", "/dev/sdb", "type", "disk", "size", 1)));

    assertNull(listed.getFirst().kernelPath());
  }

  @Test
  void theDeviceTreeIsAskedForExplicitlyInBytes() {
    assertEquals(
        List.of(
            "lsblk",
            "--json",
            "--tree",
            "--bytes",
            "--output",
            "PATH,KNAME,TYPE,FSTYPE,PTTYPE,PARTTYPE,MOUNTPOINTS,SIZE,START,MODEL,RM,WWN,SERIAL,MAJ:MIN"),
        BlockTree.LSBLK);
  }
}
