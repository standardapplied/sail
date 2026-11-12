/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.engine;

import static org.junit.jupiter.api.Assertions.fail;

import java.util.List;
import org.junit.jupiter.api.Test;

class DeviceCaptureIT extends AbstractIncusIT {

  private final StringBuilder report = new StringBuilder();

  @Test
  void capture() throws Exception {
    ensureIncusOrSkip();
    run("lsblk --version; findmnt -n -o SOURCE,TARGET,FSTYPE /; cat /proc/swaps");
    run(
        "lsblk --json --tree --bytes --output NAME,PATH,TYPE,FSTYPE,PTTYPE,MOUNTPOINTS,SIZE,MODEL,RM");
    run("lsblk --json --output PATH,TYPE,FSTYPE,PTTYPE,MOUNTPOINTS,SIZE,MODEL");
    run(
        "for d in $(lsblk -nrpo PATH,TYPE | awk '$2==\"disk\"||$2==\"part\"{print $1}'); do echo \"== $d\"; sudo -n wipefs --no-act --json -- $d; echo \"exit $?\"; done");
    run(
        "for d in $(lsblk -nrpo PATH,TYPE | awk '$2==\"disk\"||$2==\"part\"{print $1}'); do echo \"== $d\"; sudo -n wipefs --all --no-act --types sail_exclusive_probe -- $d; echo \"exit $?\"; done");
    run(
        "incus storage create sail-capture zfs size=2GiB && sudo -n zpool list -vHP && sudo -n zfs list -H -r -o name sail-capture; incus storage delete sail-capture");
    run(
        "truncate -s 1G /tmp/sail-raw.img && sudo -n zpool create sail-raw /tmp/sail-raw.img && sudo -n zpool list -vHP sail-raw; sudo -n zfs list -H -r -o name sail-raw; sudo -n zpool destroy sail-raw; rm -f /tmp/sail-raw.img");
    run(
        "ls /etc/modprobe.d; grep -rH zfs_arc /etc/modprobe.d || true; dpkg -L zfsutils-linux | grep -i initramfs || true; ls /usr/share/initramfs-tools/hooks | head -40");
    fail(report.toString());
  }

  private void run(String script) throws Exception {
    var result = shell.exec(List.of("bash", "-c", script));
    report
        .append("\n$ ")
        .append(script)
        .append("\n[exit ")
        .append(result.exitCode())
        .append("]\n")
        .append(result.stdout())
        .append(result.stderr());
  }
}
