/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.engine;

import static org.junit.jupiter.api.Assertions.fail;

import java.util.List;
import org.junit.jupiter.api.Test;

class ZfsProbeIT extends AbstractIncusIT {

  private final StringBuilder report = new StringBuilder();

  @Test
  void probe() throws Exception {
    ensureIncusOrSkip();
    run("uname -r");
    run("which zpool zfs; zfs version");
    run("lsmod | grep -w zfs || echo no-zfs-module-loaded");
    run("incus version; incus info | grep -A12 storage_supported_drivers");
    run("incus storage create sail-probe zfs size=10GiB");
    run("incus storage list --format json");
    run("incus query /1.0/storage-pools/sail-probe/resources");
    run("incus launch images:ubuntu/24.04 probe1 --storage sail-probe");
    run("incus config device override probe1 root size=1GiB");
    run("incus exec probe1 -- dd if=/dev/urandom of=/fill bs=1M count=1500");
    run("incus list probe1 --format json | head -c 300");
    run(
        "incus pause probe1; incus list ^probe1$ --format json | grep -o '\"status\":\"[A-Za-z]*\"'");
    run(
        "incus start probe1; incus list ^probe1$ --format json | grep -o '\"status\":\"[A-Za-z]*\"'");
    run("incus exec probe1 -- rm -f /fill; incus snapshot create probe1 s1");
    run("incus copy probe1/s1 probe2");
    run("zfs get -H -o value origin sail-probe/containers/probe2");
    run("sudo -n zfs get -H -o value origin,used sail-probe/containers/probe2");
    run("incus delete --force probe1 probe2; incus storage delete sail-probe");
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
