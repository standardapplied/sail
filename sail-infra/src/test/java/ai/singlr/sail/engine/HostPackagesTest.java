/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class HostPackagesTest {

  private static final String POLICY =
      """
      incus:
        Installed: 1:6.17-ubuntu24.04-202510010000
        Candidate: 1:6.18-ubuntu24.04-202511010000
        Version table:
           1:6.18-ubuntu24.04-202511010000 500
      """;

  @Test
  void aZfsBoxRunsOnIncusAndZfsAndADirBoxOnIncusAlone() {
    assertEquals(List.of(HostPackages.INCUS, HostPackages.ZFS), HostPackages.forBackend("zfs"));
    assertEquals(List.of(HostPackages.INCUS), HostPackages.forBackend("dir"));
  }

  @Test
  void theVersionsAreAptsInstalledAndCandidate() throws Exception {
    var shell = new ScriptedShellExecutor().onOk("apt-cache policy incus", POLICY);

    var versions = new HostPackages(shell, detail -> {}).versions();

    assertEquals(
        new HostPackages.Versions(
            "1:6.17-ubuntu24.04-202510010000", "1:6.18-ubuntu24.04-202511010000"),
        versions);
    assertEquals(List.of("env LC_ALL=C apt-cache policy incus"), shell.invocations());
    assertTrue(versions.movable());
    assertEquals("6.18", versions.candidateRelease());
    assertEquals("6.17", versions.from());
    assertEquals("6.18", versions.to());
  }

  @Test
  void aRebuildOfTheSameReleaseNamesBothWholeVersions() {
    var versions = new HostPackages.Versions("1:6.18-ubuntu24.04-1", "1:6.18-ubuntu24.04-2");

    assertEquals("1:6.18-ubuntu24.04-1", versions.from());
    assertEquals("1:6.18-ubuntu24.04-2", versions.to());
    assertEquals("6.18", new HostPackages.Versions("1:6.18-ubuntu24.04-1", null).from());
    assertEquals("6.18", HostPackages.Versions.release("6.18"));
  }

  @Test
  void aPackageAptDoesNotKnowHasNoVersionsAndCannotMove() throws Exception {
    var none =
        new HostPackages(
                new ScriptedShellExecutor()
                    .onOk("apt-cache", "incus:\n  Installed: (none)\n  Candidate: (none)\n"),
                detail -> {})
            .versions();
    var unanswered =
        new HostPackages(new ScriptedShellExecutor().onFail("apt-cache", "no apt"), detail -> {})
            .versions();

    assertEquals(new HostPackages.Versions(null, null), none);
    assertEquals(none, unanswered);
    assertFalse(none.movable());
    assertNull(none.candidateRelease());
    assertFalse(new HostPackages.Versions("1:6.18-1", "1:6.18-1").movable());
    assertFalse(new HostPackages.Versions("1:6.18-1", null).movable());
  }

  @Test
  void anUpgradeMovesOnlyTheInstalledIncusPackagesPastTheirHoldAndHoldsThemAgain()
      throws Exception {
    var shell =
        new ScriptedShellExecutor()
            .onOk(
                "dpkg-query",
                "incus hold installed\nincus-client hold installed\nincus-extra unknown"
                    + " not-installed\n")
            .onOk("apt-get")
            .onOk("apt-mark hold");
    var progress = new ArrayList<String>();

    new HostPackages(shell, progress::add).upgrade();

    assertEquals(
        List.of(
            "dpkg-query -W -f=${Package} ${db:Status-Want} ${db:Status-Status}\\n incus*",
            String.join(" ", Apt.COMMAND)
                + " install -y --only-upgrade --allow-change-held-packages incus incus-client",
            "apt-mark hold incus incus-client"),
        shell.invocations());
  }

  @Test
  void anUpgradeAptFailsHoldsTheIncusPackagesAgainAndSaysAptsFailure() {
    var shell =
        new ScriptedShellExecutor()
            .onOk("dpkg-query", "incus hold installed\nincus-client hold installed\n")
            .onFail("apt-get", "E: Sub-process /usr/bin/dpkg returned an error code (1)\n")
            .onOk("apt-mark hold");

    var failure =
        assertThrows(IOException.class, () -> new HostPackages(shell, detail -> {}).upgrade());

    assertEquals(
        "apt-get install --only-upgrade incus incus-client failed: E: Sub-process /usr/bin/dpkg"
            + " returned an error code (1)",
        failure.getMessage());
    assertEquals(0, failure.getSuppressed().length);
    assertEquals("apt-mark hold incus incus-client", shell.invocations().getLast());
  }

  @Test
  void anUpgradeWhoseHoldAlsoFailsKeepsAptsFailureFirst() {
    var shell =
        new ScriptedShellExecutor()
            .onOk("dpkg-query", "incus hold installed\n")
            .onFail("apt-get", "E: Sub-process /usr/bin/dpkg returned an error code (1)\n")
            .onFail("apt-mark", "E: Could not get lock /var/lib/dpkg/lock\n");

    var failure =
        assertThrows(IOException.class, () -> new HostPackages(shell, detail -> {}).upgrade());

    assertEquals(
        "apt-get install --only-upgrade incus failed: E: Sub-process /usr/bin/dpkg returned an"
            + " error code (1)",
        failure.getMessage());
    assertEquals(
        "apt-mark hold incus failed: E: Could not get lock /var/lib/dpkg/lock",
        failure.getSuppressed()[0].getMessage());
  }

  @Test
  void anUpgradeWithNoIncusInstalledSaysSo() {
    var shell = new ScriptedShellExecutor().on("dpkg-query", new ShellExec.Result(1, "", "none"));

    var failure =
        assertThrows(IOException.class, () -> new HostPackages(shell, detail -> {}).upgrade());

    assertEquals("No Incus package is installed to upgrade.", failure.getMessage());
  }

  @Test
  void aHoldAptRefusesSaysWhatItCouldNotHold() {
    var shell =
        new ScriptedShellExecutor()
            .onOk("dpkg-query", "incus install installed\n")
            .onFail("apt-mark", "E: Could not get lock /var/lib/dpkg/lock\n");

    var failure =
        assertThrows(IOException.class, () -> new HostPackages(shell, detail -> {}).hold());

    assertEquals(
        "apt-mark hold incus failed: E: Could not get lock /var/lib/dpkg/lock",
        failure.getMessage());
  }

  @Test
  void aZabblyKeyThatCannotBeFetchedOrAnUnknownOsInstallsNothing() {
    var keyless =
        new ScriptedShellExecutor()
            .onFail("which incus", "")
            .onFail("curl", "curl: (6) Could not resolve host: pkgs.zabbly.com");
    var nameless =
        new ScriptedShellExecutor()
            .onFail("which incus", "")
            .onOk("curl")
            .onFail("VERSION_CODENAME", "no os-release");

    var noKey =
        assertThrows(
            IOException.class,
            () -> new HostPackages(keyless, detail -> {}).ensure(HostPackages.INCUS));
    var noName =
        assertThrows(
            IOException.class,
            () -> new HostPackages(nameless, detail -> {}).ensure(HostPackages.INCUS));

    assertEquals(
        "Failed to add Zabbly GPG key: curl: (6) Could not resolve host: pkgs.zabbly.com",
        noKey.getMessage());
    assertEquals("Failed to detect OS codename: no os-release", noName.getMessage());
    assertEquals(List.of(), keyless.unbounded());
    assertEquals(List.of(), nameless.unbounded());
  }

  @Test
  void theZabblySourceNamesThisReleaseAndArchitecture() throws Exception {
    var shell =
        new ScriptedShellExecutor()
            .onFail("which incus", "")
            .onOk("curl")
            .onOk("VERSION_CODENAME", "noble\n")
            .onFail("dpkg --print-architecture", "")
            .onOk(HostPackages.ZABBLY_SOURCE)
            .onOk("apt-get");

    new HostPackages(shell, detail -> {}).ensure(HostPackages.INCUS);

    var source =
        new HostDropIn(
            "Zabbly incus source",
            HostPackages.ZABBLY_SOURCE,
            """
            Enabled: yes
            Types: deb
            URIs: https://pkgs.zabbly.com/incus/stable
            Suites: noble
            Components: main
            Architectures: amd64
            Signed-By: /etc/apt/keyrings/zabbly.asc
            """,
            "");
    assertTrue(
        shell.arguments().contains(source.installCommand(HostPackages.ZABBLY_SOURCE)),
        shell.invocations().toString());
    assertEquals(
        List.of(
            "curl",
            "-fsSL",
            "--create-dirs",
            "-o",
            "/etc/apt/keyrings/zabbly.asc",
            "https://pkgs.zabbly.com/key.asc"),
        shell.arguments().get(1));
  }
}
