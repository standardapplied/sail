/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class DiskDetectorTest {

  private final DiskDetector detector = new DiskDetector(new ShellExecutor(true));

  @Test
  void identifiesUnmountedDiskAsCandidate() throws Exception {
    var json =
        """
            {
              "blockdevices": [
                {
                  "name": "sda",
                  "size": "960G",
                  "type": "disk",
                  "mountpoint": "/",
                  "fstype": "ext4",
                  "model": "SAMSUNG"
                },
                {
                  "name": "sdb",
                  "size": "960G",
                  "type": "disk",
                  "mountpoint": null,
                  "fstype": null,
                  "model": "INTEL SSD"
                }
              ]
            }
            """;

    var candidates = detector.parseAndFilter(json);
    assertEquals(1, candidates.size());
    assertEquals("/dev/sdb", candidates.getFirst().device());
    assertEquals("960G", candidates.getFirst().size());
    assertEquals("INTEL SSD", candidates.getFirst().model());
  }

  @Test
  void skipsOsDiskWithPartitions() throws Exception {
    var json =
        """
            {
              "blockdevices": [
                {
                  "name": "sda",
                  "size": "512G",
                  "type": "disk",
                  "mountpoint": null,
                  "fstype": null,
                  "model": "SAMSUNG",
                  "children": [
                    {
                      "name": "sda1",
                      "size": "512M",
                      "type": "part",
                      "mountpoint": "/boot/efi",
                      "fstype": "vfat",
                      "model": null
                    },
                    {
                      "name": "sda2",
                      "size": "511.5G",
                      "type": "part",
                      "mountpoint": "/",
                      "fstype": "ext4",
                      "model": null
                    }
                  ]
                },
                {
                  "name": "sdb",
                  "size": "2T",
                  "type": "disk",
                  "mountpoint": null,
                  "fstype": null,
                  "model": "WD"
                }
              ]
            }
            """;

    var candidates = detector.parseAndFilter(json);
    assertEquals(1, candidates.size());
    assertEquals("/dev/sdb", candidates.getFirst().device());
  }

  @Test
  void skipsOsDiskBehindRaid() throws Exception {
    var json =
        """
            {
              "blockdevices": [
                {
                  "name": "sda",
                  "size": "894.3G",
                  "type": "disk",
                  "mountpoint": null,
                  "fstype": null,
                  "model": "INTEL SSDSC2KG96",
                  "children": [
                    {
                      "name": "sda2",
                      "size": "893.8G",
                      "type": "part",
                      "mountpoint": null,
                      "fstype": "linux_raid_member",
                      "model": null,
                      "children": [
                        {
                          "name": "md0",
                          "size": "893.7G",
                          "type": "raid1",
                          "mountpoint": "/",
                          "fstype": "ext4",
                          "model": null
                        }
                      ]
                    }
                  ]
                },
                {
                  "name": "sdb",
                  "size": "894.3G",
                  "type": "disk",
                  "mountpoint": null,
                  "fstype": null,
                  "model": "INTEL SSDSC2KG96",
                  "children": [
                    {
                      "name": "sdb2",
                      "size": "893.8G",
                      "type": "part",
                      "mountpoint": null,
                      "fstype": "linux_raid_member",
                      "model": null,
                      "children": [
                        {
                          "name": "md0",
                          "size": "893.7G",
                          "type": "raid1",
                          "mountpoint": "/",
                          "fstype": "ext4",
                          "model": null
                        }
                      ]
                    }
                  ]
                }
              ]
            }
            """;

    var candidates = detector.parseAndFilter(json);
    assertEquals(0, candidates.size(), "Both disks are in RAID1 backing /, neither is a candidate");
  }

  @Test
  void detectsExistingZfsPool() throws Exception {
    var json =
        """
            {
              "blockdevices": [
                {
                  "name": "sda",
                  "size": "960G",
                  "type": "disk",
                  "mountpoint": "/",
                  "fstype": "ext4",
                  "model": null
                },
                {
                  "name": "sdb",
                  "size": "960G",
                  "type": "disk",
                  "mountpoint": null,
                  "fstype": null,
                  "model": null,
                  "children": [
                    {
                      "name": "sdb1",
                      "size": "960G",
                      "type": "part",
                      "mountpoint": null,
                      "fstype": "zfs_member",
                      "model": null
                    }
                  ]
                }
              ]
            }
            """;

    var candidates = detector.parseAndFilter(json);
    assertEquals(1, candidates.size());
    assertEquals("/dev/sdb", candidates.getFirst().device());
    assertTrue(candidates.getFirst().reason().contains("ZFS"));
  }

  @Test
  void handlesNvmeDrives() throws Exception {
    var json =
        """
            {
              "blockdevices": [
                {
                  "name": "nvme0n1",
                  "size": "1T",
                  "type": "disk",
                  "mountpoint": null,
                  "fstype": null,
                  "model": "Samsung 990 PRO",
                  "children": [
                    {
                      "name": "nvme0n1p1",
                      "size": "1G",
                      "type": "part",
                      "mountpoint": "/boot",
                      "fstype": "ext4",
                      "model": null
                    },
                    {
                      "name": "nvme0n1p2",
                      "size": "999G",
                      "type": "part",
                      "mountpoint": "/",
                      "fstype": "ext4",
                      "model": null
                    }
                  ]
                },
                {
                  "name": "nvme1n1",
                  "size": "2T",
                  "type": "disk",
                  "mountpoint": null,
                  "fstype": null,
                  "model": "Samsung 990 PRO"
                }
              ]
            }
            """;

    var candidates = detector.parseAndFilter(json);
    assertEquals(1, candidates.size());
    assertEquals("/dev/nvme1n1", candidates.getFirst().device());
    assertEquals("2T", candidates.getFirst().size());
  }

  @Test
  void returnsEmptyWhenNoCandidates() throws Exception {
    var json =
        """
            {
              "blockdevices": [
                {
                  "name": "sda",
                  "size": "512G",
                  "type": "disk",
                  "mountpoint": "/",
                  "fstype": "ext4",
                  "model": null
                }
              ]
            }
            """;

    var candidates = detector.parseAndFilter(json);
    assertTrue(candidates.isEmpty());
  }

  @Test
  void skipsNonDiskTypes() throws Exception {
    var json =
        """
            {
              "blockdevices": [
                {
                  "name": "loop0",
                  "size": "64M",
                  "type": "loop",
                  "mountpoint": "/snap/core",
                  "fstype": "squashfs",
                  "model": null
                },
                {
                  "name": "sda",
                  "size": "960G",
                  "type": "disk",
                  "mountpoint": null,
                  "fstype": null,
                  "model": null
                }
              ]
            }
            """;

    var candidates = detector.parseAndFilter(json);
    assertEquals(1, candidates.size());
    assertEquals("/dev/sda", candidates.getFirst().device());
  }

  @Test
  void detectReturnsEmptyOnBlankOutput() throws Exception {
    var candidates = detector.detect();
    assertTrue(candidates.isEmpty(), "Dry-run returns blank lsblk output");
  }

  @Test
  void skipsDiskWithRecognizedFilesystem() throws Exception {
    var json =
        """
            {
              "blockdevices": [
                {
                  "name": "sda",
                  "size": "500G",
                  "type": "disk",
                  "mountpoint": null,
                  "fstype": "ext4",
                  "model": null
                }
              ]
            }
            """;

    var candidates = detector.parseAndFilter(json);
    assertTrue(candidates.isEmpty());
  }

  @Test
  void skipsDiskWithMountedChild() throws Exception {
    var json =
        """
            {
              "blockdevices": [
                {
                  "name": "sda",
                  "size": "500G",
                  "type": "disk",
                  "mountpoint": null,
                  "fstype": null,
                  "model": null,
                  "children": [
                    {
                      "name": "sda1",
                      "size": "500G",
                      "type": "part",
                      "mountpoint": "/data",
                      "fstype": "xfs",
                      "model": null
                    }
                  ]
                }
              ]
            }
            """;

    var candidates = detector.parseAndFilter(json);
    assertTrue(candidates.isEmpty());
  }

  @Test
  void zfsMemberOnDiskDirectly() throws Exception {
    var json =
        """
            {
              "blockdevices": [
                {
                  "name": "sdb",
                  "size": "1T",
                  "type": "disk",
                  "mountpoint": null,
                  "fstype": "zfs_member",
                  "model": "WD"
                }
              ]
            }
            """;

    var candidates = detector.parseAndFilter(json);
    assertEquals(1, candidates.size());
    assertTrue(candidates.getFirst().reason().contains("ZFS"));
  }

  @Test
  void parseAndFilterReturnsEmptyForNonObjectInput() throws Exception {
    var candidates = detector.parseAndFilter("not json");
    assertTrue(candidates.isEmpty());
  }

  @Test
  void diskWithMountedGrandchildIsNotCandidate() throws Exception {
    var json =
        """
            {
              "blockdevices": [
                {
                  "name": "sda",
                  "size": "1T",
                  "type": "disk",
                  "mountpoint": null,
                  "fstype": null,
                  "model": null,
                  "children": [
                    {
                      "name": "sda1",
                      "size": "1T",
                      "type": "part",
                      "mountpoint": null,
                      "fstype": "linux_raid_member",
                      "model": null,
                      "children": [
                        {
                          "name": "md1",
                          "size": "1T",
                          "type": "raid1",
                          "mountpoint": "/data",
                          "fstype": "ext4",
                          "model": null
                        }
                      ]
                    }
                  ]
                }
              ]
            }
            """;

    var candidates = detector.parseAndFilter(json);
    assertTrue(candidates.isEmpty(), "Disk with mounted grandchild should not be candidate");
  }

  @Test
  void diskWithChildHavingSkipFstypeButNoMountIsNotCandidate() throws Exception {
    var json =
        """
            {
              "blockdevices": [
                {
                  "name": "sda",
                  "size": "500G",
                  "type": "disk",
                  "mountpoint": null,
                  "fstype": null,
                  "model": null,
                  "children": [
                    {
                      "name": "sda1",
                      "size": "500G",
                      "type": "part",
                      "mountpoint": null,
                      "fstype": "btrfs",
                      "model": null
                    }
                  ]
                }
              ]
            }
            """;

    var candidates = detector.parseAndFilter(json);
    assertTrue(candidates.isEmpty(), "Disk with btrfs child should not be candidate");
  }

  @Test
  void diskWithMountpointDirectlyIsNotCandidate() throws Exception {
    var json =
        """
            {
              "blockdevices": [
                {
                  "name": "sda",
                  "size": "500G",
                  "type": "disk",
                  "mountpoint": "/mnt/data",
                  "fstype": null,
                  "model": null
                }
              ]
            }
            """;

    var candidates = detector.parseAndFilter(json);
    assertTrue(candidates.isEmpty(), "Disk with mountpoint should not be candidate");
  }

  @Test
  void lsblkFailureThrowsIOException() {
    var failShell = new ScriptedShellExecutor().onFail("lsblk", "lsblk: not found");
    var failDetector = new DiskDetector(failShell);

    var ex = assertThrows(Exception.class, () -> failDetector.detect());
    assertTrue(ex.getMessage().contains("lsblk failed"));
  }

  @Test
  void diskWithUnknownFstypeAndNoMountIsCandidate() throws Exception {
    var json =
        """
            {
              "blockdevices": [
                {
                  "name": "sda",
                  "size": "500G",
                  "type": "disk",
                  "mountpoint": null,
                  "fstype": "linux_raid_member",
                  "model": null
                }
              ]
            }
            """;

    var candidates = detector.parseAndFilter(json);
    assertEquals(1, candidates.size());
    assertEquals("Unmounted, no filesystem", candidates.getFirst().reason());
  }

  @Test
  void diskWithChildrenHavingGrandchildrenNoMount() throws Exception {
    var json =
        """
            {
              "blockdevices": [
                {
                  "name": "sda",
                  "size": "1T",
                  "type": "disk",
                  "mountpoint": null,
                  "fstype": null,
                  "model": null,
                  "children": [
                    {
                      "name": "sda1",
                      "size": "1T",
                      "type": "part",
                      "mountpoint": null,
                      "fstype": "linux_raid_member",
                      "model": null,
                      "children": [
                        {
                          "name": "md0",
                          "size": "1T",
                          "type": "raid1",
                          "mountpoint": null,
                          "fstype": null,
                          "model": null
                        }
                      ]
                    }
                  ]
                }
              ]
            }
            """;

    var candidates = detector.parseAndFilter(json);
    assertEquals(1, candidates.size());
    assertEquals("Unmounted, no filesystem", candidates.getFirst().reason());
  }

  @Test
  void diskWithChildNullFstypeAndNullChildrenIsCandidate() throws Exception {
    var json =
        """
            {
              "blockdevices": [
                {
                  "name": "sda",
                  "size": "500G",
                  "type": "disk",
                  "mountpoint": null,
                  "fstype": null,
                  "model": null,
                  "children": [
                    {
                      "name": "sda1",
                      "size": "500G",
                      "type": "part",
                      "mountpoint": null,
                      "fstype": null,
                      "model": null
                    }
                  ]
                }
              ]
            }
            """;

    var candidates = detector.parseAndFilter(json);
    assertEquals(1, candidates.size());
    assertEquals("Unmounted, no filesystem", candidates.getFirst().reason());
  }

  @Test
  void detectWithNonBlankLsblkOutput() throws Exception {
    var shell =
        new ScriptedShellExecutor()
            .onOk(
                "lsblk",
                """
            {"blockdevices":[{"name":"sda","size":"500G","type":"disk","mountpoint":null,"fstype":null,"model":"TEST","children":null}]}
            """);

    var det = new DiskDetector(shell);
    var candidates = det.detect();
    assertEquals(1, candidates.size());
    assertEquals("/dev/sda", candidates.getFirst().device());
  }

  @Test
  void osDiskDetectedByDirectMountOnDisk() throws Exception {
    var json =
        """
            {
              "blockdevices": [
                {
                  "name": "sda",
                  "size": "500G",
                  "type": "disk",
                  "mountpoint": "/",
                  "fstype": "ext4",
                  "model": null
                },
                {
                  "name": "sdb",
                  "size": "1T",
                  "type": "disk",
                  "mountpoint": null,
                  "fstype": null,
                  "model": null
                }
              ]
            }
            """;

    var candidates = detector.parseAndFilter(json);
    assertEquals(1, candidates.size());
    assertEquals("/dev/sdb", candidates.getFirst().device());
  }
}
