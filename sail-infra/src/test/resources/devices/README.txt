Device fixtures for PoolDevicesTest and the tests that drive PoolDevices.

The call PoolDevices makes is `lsblk --json --tree --bytes --output
PATH,KNAME,TYPE,FSTYPE,PTTYPE,PARTTYPE,MOUNTPOINTS,SIZE,MODEL,RM,WWN,SERIAL,MAJ:MIN`. Every
lsblk fixture has each of those keys on every device, sizes in bytes, as that call prints them.

Captured, read-only, from real tools (util-linux 2.39.3, Ubuntu 24.04):
- devbox-raid1-nvme.lsblk.json   this devbox (two NVMe drives in RAID1, md2 at /), the call above,
  verbatim. It runs in a container, so udev gives lsblk no filesystem or partition types.
- gcp2/<layout>.lsblk.json   the second GCP proof (of f54c5c60), the call above without --bytes:
  its sizes are lsblk's rounded units turned into bytes, all else verbatim. b-*: a system disk
  (BIOS boot sda14, EFI sda15, XBOOTLDR /boot sda16), a blank disk, an ext4 disk, a disk of
  neighbours (ext4 mounted, swap on and off, a blank partition, ext4 unmounted), Incus pools made,
  and a two-device btrfs mounted; c-*: a dir box and a file pool beside it; d-*: disks swapped
  under one name, a by-id link moved, and the box pivoted onto a root on ZFS and onto a btrfs
  raid1 root, where udev knows nothing and lsblk shows no type of any kind.
- devbox-flat.lsblk.json, github-runner-flat.lsblk.json   the flat list the first version's call
  printed (`lsblk --json --output PATH,TYPE,FSTYPE,PTTYPE,MOUNTPOINTS,SIZE,MODEL`), on this devbox
  and on a GitHub ubuntu-24.04 runner, with the call's other keys added as null and sizes in bytes
- gcp/<layout>.*   the first GCP trial, its system on sda and two spare disks sdb and sdc taken
  through blank, partitioned, RAID1 (md0 swap, md1 ext4, md2 empty) mounted, unmounted, swap off,
  arrays stopped, arrays recreated over stale signatures, blank again, and reassembled degraded by
  udev: `lsblk-tree.json` and `lsblk-flat.json` (the call above without KNAME, PARTTYPE, RM, WWN,
  SERIAL and MAJ:MIN; added here: KNAME as each path's last part, RM false, WWN, SERIAL and
  MAJ:MIN null, sizes in bytes, and PARTTYPE from the record, sda's as gcp2 shows the same image's
  and sdb's and sdc's Linux RAID, the type the trial's sfdisk gave them), `wipefs-list.txt`
  (`wipefs <device>`, the listing PoolDevices reads) and `wipefs-noact-all.txt` (`wipefs --no-act
  --all <device>`, whose busy answer is the probe's: the exclusive open fails before any filter
  counts)
- gcp/handpool-with-cache.zpool-list.txt   `zpool list -vHP handpool` on the first GCP proof's VM:
  a pool on sdd1 with sdg1 as its cache device
- incus-file-pool.zpool-list.txt, raw-file-pool.zpool-list.txt   `zpool list -vHP` on the runner,
  for a pool Incus made and one `zpool create` made
- classes.zpool-list.txt   `zpool list -vHP` on the runner (PoolDevicesIT) for a pool with a data,
  special, log, cache and spare device on loop devices: the class headers are space-padded

Hand-written (layouts no machine at hand has), in the call's format:
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
- handwritten-gcp-proof-layouts.lsblk.json   the devices of the first GCP proof (of 13a8e078, its
  `lsblk -f` and case notes): a loop device with a partition, the system disk with a blank sda2
  beside its BIOS boot and EFI partitions, two disks of RAID1 arrays (md0 swap in use, md1 holding
  ext4, md2 partitioned into md2p1), a pool disk with ZFS's reserved partition sdd9, a blank sdd2
  and its zvol zd0, a disk with an open LUKS volume, an active LVM volume, an ext4 partition and a
  spare, and a disk whose second partition is swap in use beside a blank and an ext4 partition
- handwritten-review3-layouts.lsblk.json   review 3's probes: the root disk seen through a second
  path (sdc, the same serial and WWN as sda, its sdc1 the root's ext4), a bcache disk built on
  sdb, and partition types lsblk may print: none, an EFI type in capitals, XBOOTLDR, and on MBR
  disks 0xef, 0xea, 0x83 and the extended 0x5, 0xf and 0x85
