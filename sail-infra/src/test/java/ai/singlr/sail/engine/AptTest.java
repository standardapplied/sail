/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class AptTest {

  @Test
  void everyCallSpeaksTheCLocaleAsksNothingWaitsOnTheLockRetriesAndReportsItsStatus()
      throws Exception {
    var shell = new ScriptedShellExecutor().onOk("apt-get");

    new Apt(shell, detail -> {}).upgradeHeld(List.of("incus", "incus-base"));

    assertEquals(
        List.of(
            "env LC_ALL=C DEBIAN_FRONTEND=noninteractive apt-get -q -o APT::Status-Fd=1 -o"
                + " Acquire::Retries=3 -o DPkg::Lock::Timeout=600 -o"
                + " Dpkg::Options::=--force-confdef -o Dpkg::Options::=--force-confold install -y"
                + " --only-upgrade --allow-change-held-packages incus incus-base"),
        shell.unbounded());
    assertEquals(shell.unbounded(), shell.invocations());
  }

  @Test
  void aRefreshFailsWhenAnySourceFailsToDownload() throws Exception {
    var shell = new ScriptedShellExecutor().onOk("apt-get");

    new Apt(shell, detail -> {}).update();

    assertEquals(
        List.of(
            "env LC_ALL=C DEBIAN_FRONTEND=noninteractive apt-get -q -o APT::Status-Fd=1 -o"
                + " Acquire::Retries=3 -o DPkg::Lock::Timeout=600 -o"
                + " Dpkg::Options::=--force-confdef -o Dpkg::Options::=--force-confold -o"
                + " APT::Update::Error-Mode=any update"),
        shell.unbounded());
  }

  @Test
  void aWaitThatEndsInTheLockIsNotBlamedOnAFailureAfterIt() {
    var shell =
        new ScriptedShellExecutor()
            .on(
                "apt-get",
                new ShellExec.Result(
                    100,
                    "Waiting for cache lock: Could not get lock /var/lib/dpkg/lock-frontend. It is"
                        + " held by process 812 (unattended-upgr)...\n"
                        + "dlstatus:1:0.0000:Retrieving file 1 of 1\n",
                    "E: Failed to fetch https://pkgs.zabbly.com/x.deb  404  Not Found\n"));
    var progress = new ArrayList<String>();

    var failure =
        assertThrows(
            IOException.class, () -> new Apt(shell, progress::add).install(List.of("incus")));

    assertEquals(
        "apt-get install incus failed: E: Failed to fetch https://pkgs.zabbly.com/x.deb  404  Not"
            + " Found",
        failure.getMessage());
    assertEquals(List.of(Apt.LOCK_WAIT, "0% Retrieving file 1 of 1"), progress);
  }

  @Test
  void aFailureWithoutAnErrorLineReadsAsAptsStderrOrItsExit() {
    assertEquals(
        "dpkg: error processing archive",
        Apt.reason(new ShellExec.Result(1, "", "  dpkg: error processing archive\n"), null));
    assertEquals("exit 100", Apt.reason(new ShellExec.Result(100, "", " \n"), null));
    assertEquals(
        "E: one E: two",
        Apt.reason(new ShellExec.Result(100, "", "W: warned\nE: one\nE: two\n"), null));
  }

  @Test
  void onlyStatusLinesWithAPercentAreProgress() {
    assertEquals(
        Optional.of("40% Installing incus (amd64)"),
        Apt.status("pmstatus:incus:40.0000:Installing incus (amd64)"));
    assertEquals(
        Optional.of("99% Retrieving file 3 of 3: index"),
        Apt.status("dlstatus:3:99.9:Retrieving file 3 of 3: index"));
    assertEquals(Optional.empty(), Apt.status("pmerror:incus:40.0000:dpkg failed"));
    assertEquals(Optional.empty(), Apt.status("dlstatus:1:soon:Retrieving"));
    assertEquals(Optional.empty(), Apt.status("dlstatus:1:12.0"));
    assertEquals(Optional.empty(), Apt.status("Get:1 https://pkgs.zabbly.com incus [9 MB]"));
  }
}
