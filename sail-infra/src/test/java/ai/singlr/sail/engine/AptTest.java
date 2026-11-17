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
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class AptTest {

  private static final String MIRROR = "http://us-central1.gce.archive.ubuntu.com/ubuntu/pool/main";
  private static final String NOT_SERVED =
      "E: Failed to fetch "
          + MIRROR
          + "/i/iw/iw_6.7-1build1_amd64.deb  404  Not Found [IP: 35.184.25.42 80]\n"
          + "E: Failed to fetch "
          + MIRROR
          + "/libx/libxrender/libxrender1_0.9.10-1.1build1_amd64.deb  404  Not Found [IP:"
          + " 35.184.213.5 80]\n"
          + "E: Unable to fetch some archives, maybe run apt-get update or try with"
          + " --fix-missing?\n";
  private static final String INSTALL = "install -y incus";
  private static final String REFRESH = "Error-Mode=any update";

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
  void aHoldSpeaksTheCLocaleAsksNothingAndWaitsOnTheLockLikeEveryCall() throws Exception {
    var shell = new ScriptedShellExecutor().onOk("apt-mark");

    new Apt(shell, detail -> {}).hold(List.of("incus", "incus-base"));

    assertEquals(
        List.of(
            "env LC_ALL=C DEBIAN_FRONTEND=noninteractive apt-mark -o DPkg::Lock::Timeout=600 hold"
                + " incus incus-base"),
        shell.unbounded());
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
                    "E: Sub-process /usr/bin/dpkg returned an error code (1)\n"));
    var progress = new ArrayList<String>();

    var failure =
        assertThrows(
            IOException.class, () -> new Apt(shell, progress::add).install(List.of("incus")));

    assertEquals(
        "apt-get install incus failed: E: Sub-process /usr/bin/dpkg returned an error code (1)",
        failure.getMessage());
    assertEquals(List.of(Apt.LOCK_WAIT, "0% Retrieving file 1 of 1"), progress);
  }

  @Test
  void aFailureWithoutAnErrorLineReadsAsAptsStderrOrItsExit() {
    assertEquals(
        "dpkg: error processing archive",
        Apt.reason(new ShellExec.Result(1, "", "  dpkg: error processing archive\n"), null));
    assertEquals("exited 100", Apt.reason(new ShellExec.Result(100, "", " \n"), null));
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

  @Test
  void anInstallTheMirrorAnswered404ForIsSaidAndTriedAgainAfterOneRefresh() throws Exception {
    var shell =
        new ScriptedShellExecutor().onOk(REFRESH).onceOnFail(INSTALL, NOT_SERVED).onOk("incus");
    var progress = new ArrayList<String>();

    new Apt(shell, progress::add).install(List.of("incus"));

    assertEquals(List.of("install", "update", "install"), verbs(shell));
    assertEquals(
        List.of(
            "the mirror us-central1.gce.archive.ubuntu.com answered 404 for 2 of its files; trying"
                + " again (2 of 4)"),
        progress);
  }

  @Test
  void aSecondTryTheMirrorAnswered404ForIsTriedAgainWithoutAnotherRefresh() throws Exception {
    var shell =
        new ScriptedShellExecutor()
            .onOk(REFRESH)
            .onceOnFail(INSTALL, NOT_SERVED)
            .onceOnFail("-y incus", NOT_SERVED.lines().skip(1).findFirst().orElseThrow())
            .onOk("incus");
    var progress = new ArrayList<String>();

    new Apt(shell, progress::add).install(List.of("incus"));

    assertEquals(List.of("install", "update", "install", "install"), verbs(shell));
    assertEquals(
        List.of(
            "the mirror us-central1.gce.archive.ubuntu.com answered 404 for 2 of its files; trying"
                + " again (2 of 4)",
            "the mirror us-central1.gce.archive.ubuntu.com answered 404 for 1 of its files; trying"
                + " again (3 of 4)"),
        progress);
  }

  @Test
  @Timeout(value = 10, unit = TimeUnit.SECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
  void aMirrorThatKeepsAnswering404StopsAfterFourTriesSayingItIsTheMirrorsFault() {
    var shell =
        new ScriptedShellExecutor()
            .onOk(REFRESH)
            .on(INSTALL, new ShellExec.Result(100, "", NOT_SERVED));
    var progress = new ArrayList<String>();

    var failure =
        assertThrows(
            IOException.class, () -> new Apt(shell, progress::add).install(List.of("incus")));

    assertEquals(
        "apt-get install incus failed: the mirror us-central1.gce.archive.ubuntu.com is not"
            + " serving files its own index names (4 tries; the last answered 404 for"
            + " iw_6.7-1build1_amd64.deb, libxrender1_0.9.10-1.1build1_amd64.deb). That is the"
            + " mirror's fault, not this box's: run the same command again, and apt fetches only"
            + " the files still missing.",
        failure.getMessage());
    assertEquals(List.of("install", "update", "install", "install", "install"), verbs(shell));
    assertEquals(3, progress.size());
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "E: Failed to fetch http://mirror/pool/main/i/iw/iw_6.7_amd64.deb  Hash Sum mismatch\n",
        "E: Failed to fetch http://mirror/pool/p404/p404_1_amd64.deb  Could not connect to mirror:80"
            + " (10.0.0.1), connection timed out [IP: 10.0.0.1 80]\n",
        "E: Sub-process /usr/bin/dpkg returned an error code (1)\n"
      })
  void anyOtherFailureIsNotTriedAgain(String stderr) {
    var shell = new ScriptedShellExecutor().onOk(REFRESH).onceOnFail(INSTALL, stderr).onOk("incus");
    var progress = new ArrayList<String>();

    var failure =
        assertThrows(
            IOException.class, () -> new Apt(shell, progress::add).install(List.of("incus")));

    assertEquals("apt-get install incus failed: " + stderr.strip(), failure.getMessage());
    assertEquals(List.of("install"), verbs(shell));
    assertEquals(List.of(), progress);
  }

  @Test
  void aMoveOfHeldPackagesTheMirrorAnswered404ForIsTriedAgainToo() throws Exception {
    var shell =
        new ScriptedShellExecutor()
            .onOk(REFRESH)
            .onceOnFail("--only-upgrade", NOT_SERVED)
            .onOk("incus");

    new Apt(shell, detail -> {}).upgradeHeld(List.of("incus"));

    assertEquals(List.of("install", "update", "install"), verbs(shell));
  }

  private static List<String> verbs(ScriptedShellExecutor shell) {
    return shell.unbounded().stream()
        .map(command -> command.endsWith(" update") ? "update" : "install")
        .toList();
  }
}
