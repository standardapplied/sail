/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import org.junit.jupiter.api.Test;

/** The one lsblk call {@link BlockTree} makes. */
class BlockTreeTest {

  @Test
  void theDeviceTreeIsAskedForExplicitlyInBytes() {
    assertEquals(
        List.of(
            "lsblk",
            "--json",
            "--tree",
            "--bytes",
            "--output",
            "PATH,KNAME,TYPE,FSTYPE,PTTYPE,PARTTYPE,MOUNTPOINTS,SIZE,MODEL,RM,WWN,SERIAL,MAJ:MIN"),
        BlockTree.LSBLK);
  }
}
