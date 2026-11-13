Device fixtures for PoolDevicesTest and the tests that drive PoolDevices.

The call PoolDevices makes is `lsblk --json --tree --bytes --output
PATH,KNAME,TYPE,FSTYPE,PTTYPE,PARTTYPE,MOUNTPOINTS,SIZE,START,MODEL,RM,WWN,SERIAL,MAJ:MIN`. Every
lsblk fixture has each of those keys on every device, sizes in bytes, as that call prints them.
START (a partition's first sector) is the column added last: the devbox capture, every gcp4
capture and gcp3/g-layout-overlapping-mbr carry real starts (the latter from the proof's own
`lsblk -o START` of that layout, 2048 and 6293504). Elsewhere a partition's start is laid out from its disk's
first sector (gcp3: from 1 MiB) end to end in listed order, so starts are consistent but not the
real ones; the hand-written layouts' partitions were sized to fit their disks.

Captured, read-only, from real tools (util-linux 2.39.3, Ubuntu 24.04):
- gcp4/<case or layout>.lsblk.json   the fourth GCP proof (of 21e9a482), the call above verbatim,
  START included. The proof took 105 captures, of which 24 differ: each file kept is named by the
  first case or layout that printed it, the byte-identical captures of later cases left out, and
  four are renamed for what they hold: a-overlap-and-past-end (a-001 and a-101..a-115: MBR and GPT
  tables with an overlapping pair, a third partition beside one, an MBR partition the kernel cut at
  its disk's end, a loop partition past its device's end, an ext4 disk of three adjacent
  partitions), a-holders (a-201..a-206: a GPT disk of ten blank partitions), c-nvme-pd (c-01, c-02:
  an NVMe persistent disk, its id of the nvme.<vendor>-<serial>-<model>-<namespace> form) and
  b-local-nvme-ssd (b-120: a local NVMe SSD, its id of that form).
- gcp3/<layout>.lsblk.json   the third GCP proof (of c11ae7ac), the call above without START,
  verbatim (sizes in bytes): a-* and f-* blank, ext4 and swap disks, pools made; c-* RAID arrays
  active, stopped and partitioned, imported and exported pools, zvols, a loop partition, LUKS and
  LVM; d-* every partition type on GPT and MBR, holders, LUKS with a detached header; g-* an MBR
  table whose second partition lies inside the first (mounted); s-* disks swapped under one name.
- devbox-raid1-nvme.lsblk.json   this devbox (two NVMe drives in RAID1, md2 at /), the call above,
  verbatim. It runs in a container, so udev gives lsblk no filesystem or partition types.
- gcp2/<layout>.lsblk.json   the second GCP proof (of f54c5c60), the call above without --bytes:
  its sizes are lsblk's rounded units turned into bytes, all else verbatim, so a disk's partitions
  may add up to more than the disk; tests that weigh extents use gcp3. a-*: a system disk
  with a blank sda2 beside BIOS boot sda14, EFI sda15 and XBOOTLDR /boot sda16, two disks of
  RAID1 arrays (md0 swap, md1 and md2 ext4) active, unmounted, stopped and partitioned (md2p1), a
  partitioned loop device, a zvol whole and partitioned, imported pools with a cache device, an
  open LUKS volume, an active LVM volume, an ext4 partition and a spare, and second disks with
  EFI, BIOS boot and XBOOTLDR partitions on GPT and 0xef and 0x83 on MBR; b-*: a blank disk, an
  ext4 disk, a disk of neighbours (ext4 mounted, swap on and off, a blank partition, ext4
  unmounted), Incus pools made, and a two-device btrfs mounted; c-*: a dir box and a file pool
  beside it; d-*: disks swapped under one name and a by-id link moved; e-*: the box pivoted onto
  a root on a ZFS mirror and onto a btrfs raid1 root. The proof's two captures taken where udev
  had no database are left out.
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
- handwritten-gcp-blank-with-dm-zero.lsblk.json   the GCP blank-disks capture plus a
  device-mapper device with no parent at the top of the tree
- handwritten-flat-whole-disk-raid.lsblk.json   the flat list the first version's call prints for
  an array built on two whole disks: no partition at the top, the array listed once per member
- handwritten-gcp-proof-layouts.lsblk.json   (for a blank partition beside a pool's device on
  one disk, which no capture has) the devices of the first GCP proof (of 13a8e078, its
  `lsblk -f` and case notes): a loop device with a partition, the system disk with a blank sda2
  beside its BIOS boot and EFI partitions, two disks of RAID1 arrays (md0 swap in use, md1 holding
  ext4, md2 partitioned into md2p1), a pool disk with ZFS's reserved partition sdd9, a blank sdd2
  and its zvol zd0, a disk with an open LUKS volume, an active LVM volume, an ext4 partition and a
  spare, and a disk whose second partition is swap in use beside a blank and an ext4 partition
- handwritten-review3-layouts.lsblk.json   review 3's probes: the root disk seen through a second
  path (sdc, the same serial and WWN as sda, its sdc1 the root's ext4), a bcache disk built on
  sdb, and partition types lsblk may print: none, an EFI type in capitals, XBOOTLDR, and on MBR
  disks 0xef, 0xea, 0x83 and the extended 0x5, 0xf and 0x85, and a disk with an empty GPT
- handwritten-review4-layouts.lsblk.json   review 4's probes (its layout, extended): beside a root
  partition a Microsoft Reserved, a PReP boot and a never-formatted Linux swap partition, data
  partitions the listings make members of a RAID, an LVM1 group, bcache, ceph or an exported
  pool, a disk whose one data partition belongs to such a pool, a RAM disk and an eMMC boot area,
  and a GPT disk with a ZFS-typed partition (its GUID in capitals), an MBR disk with 0xbf and
  0x83 partitions, an MBR partition reaching past its 1 GiB disk, and an NVMe disk with an EUI
- handwritten-mirror-layouts.lsblk.json   pairs no machine at hand had, for a mirror: the fourth
  GCP proof's NVMe persistent-disk capture (c-nvme-pd) with a third blank namespace nvme0n3 of the
  same controller, two blank disks with one WWN and serial (two paths to one disk), two with one
  serial and no WWN, disks of 20 GiB and of 20 GiB + 8 MiB, + 17 MiB and + 100 MiB, two of 40 and 60 GiB, and a blank 20 GiB disk beside a 21 GiB disk whose one partition is 20 GiB
- handwritten-mirror-*.zpool-status.txt   `zpool status -P devpool` of a mirror online, degraded
  with a device removed, degraded at import with a device absent (listed by its GUID, `was` its
  path), and faulted with both unopenable, in the output OpenZFS 2.2.2 prints (`zpool_main.c`
  `status_callback` and `print_status_config`); the proof could not build a real FAULTED pool
- gcp5/e-540-mirror-suspended.zpool-status.txt, gcp5/e-501-mirror-offline.zpool-status.txt   the
  proof's real `zpool status -P devpool` of a mirror suspended with both disks detached under I/O
  (its rows read DEGRADED, the device still ONLINE with errors, the other REMOVED; the harness's
  trailing `zpool status rc=0` line dropped) and of one with a device the operator took offline
- gcp5/<case>.lsblk.json   the mirror's real-device proof (of 9cff06c3), the call above verbatim:
  d-225 two whole disks after the destroyed mirror zpend (GPT, data partition with zpend's labels,
  8 MiB reserved partition 9), d-226 the same once the labels were cleared; gcp5/d-225-wipefs-*.txt
  the `wipefs <device>` listings of those disks and data partitions, from the proof's log.
  gcp5/handwritten-d-225-after-the-printed-remedy.lsblk.json is d-225 with sdf and sdg left as
  `wipefs --all` leaves them, no table and no partitions (the commands verified on a loop device by
  PoolDevicesIT)
