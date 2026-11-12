Device fixtures for PoolDevicesTest and the tests that drive PoolDevices.

Captured, read-only, from real tools (util-linux 2.39.3, Ubuntu 24.04):
- devbox-raid1-nvme.lsblk.json   this devbox (two NVMe drives in RAID1, md2 at /), the call
  PoolDevices makes: `lsblk --json --tree --output
  PATH,TYPE,FSTYPE,PTTYPE,PARTTYPE,MOUNTPOINTS,SIZE,MODEL,RM,WWN,SERIAL,MAJ:MIN`
- devbox-flat.lsblk.json, github-runner-flat.lsblk.json   the flat list the first version's call
  printed (`lsblk --json --output PATH,TYPE,FSTYPE,PTTYPE,MOUNTPOINTS,SIZE,MODEL`), on this devbox
  and on a GitHub ubuntu-24.04 runner
- gcp/<layout>.*   a GCP Ubuntu 24.04 VM, its system on sda and two spare disks sdb and sdc taken
  through blank, partitioned, RAID1 (md0 swap, md1 ext4, md2 empty) mounted, unmounted, swap off,
  arrays stopped, arrays recreated over stale signatures, blank again, and reassembled degraded by
  udev: `lsblk-tree.json` (the call above without PARTTYPE, RM, WWN, SERIAL and MAJ:MIN, so its
  devices carry no partition type, are not removable and are known by their size alone), `lsblk-flat.json`,
  `wipefs-list.txt` (`wipefs <device>`, the listing PoolDevices reads) and `wipefs-noact-all.txt`
  (`wipefs --no-act --all <device>`, whose busy answer is the probe's: the exclusive open fails
  before any filter counts)
- incus-file-pool.*, raw-file-pool.*   `zpool list -vHP` and `zfs list -H -r -o name` on the
  runner, for a pool Incus made and one `zpool create` made
- classes.zpool-list.txt   `zpool list -vHP` on the runner (PoolDevicesIT) for a pool with a data,
  special, log, cache and spare device on loop devices: the class headers are space-padded

Hand-written (layouts no machine at hand has), kept in the captured format:
- handwritten-gcp-blank-with-spares.lsblk.json   the GCP blank-disks capture plus spare disks sdd
  (ext4), sde (removable), sdf (an LVM physical volume in use), sdg (an open LUKS volume), sdh,
  sdi and sdj (zfs members), sdk, sdl (swap in use beside a free partition), zram0 and nbd0
- handwritten-root-on-zfs-mirror.lsblk.json   root on a ZFS mirror over two NVMe disks, nothing
  mounted at / as lsblk shows it, the second disk's EFI partition unmounted, and a spare sdb
- handwritten-btrfs-raid1-root.lsblk.json   btrfs raid1 root shown at / on sda2 only, sdb's EFI
  partition unmounted, a BIOS boot partition on sdc, and a blank sdd
- handwritten-gcp-blank-with-dm-zero.lsblk.json   the GCP blank-disks capture plus a
  device-mapper device with no parent at the top of the tree
- handwritten-flat-whole-disk-raid.lsblk.json   the flat list the first version's call prints for
  an array built on two whole disks: no partition at the top, the array listed once per member
