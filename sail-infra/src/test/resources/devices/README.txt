Device fixtures for PoolDevicesTest.

Captured, read-only, from real tools (util-linux 2.39.3, Ubuntu 24.04):
- devbox-raid1-nvme.lsblk.json   this devbox: `lsblk --json --tree --bytes --output NAME,PATH,TYPE,FSTYPE,PTTYPE,MOUNTPOINTS,SIZE,MODEL,RM`
- devbox-flat.lsblk.json         this devbox: the flat list the first version's call printed (`lsblk --json --output PATH,TYPE,FSTYPE,PTTYPE,MOUNTPOINTS,SIZE,MODEL`)
- github-runner.lsblk.json, github-runner-flat.lsblk.json   the same two calls on a GitHub ubuntu-24.04 runner
- github-runner-<dev>.wipefs.json   `wipefs --no-act --json -- /dev/<dev>` on the runner
- incus-file-pool.*, raw-file-pool.*   `zpool list -vHP` and `zfs list -H -r -o name` on the runner, for a pool Incus made and one `zpool create` made

Hand-written (layouts no machine at hand has), kept in the captured format:
- handwritten-runner-with-spares.lsblk.json   the runner's capture plus spare disks sdb..sdk, zram0 and nbd0
