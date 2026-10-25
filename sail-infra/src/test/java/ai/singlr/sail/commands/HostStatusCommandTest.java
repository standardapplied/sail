/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.commands;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.sail.config.HostYaml;
import ai.singlr.sail.config.YamlUtil;
import ai.singlr.sail.engine.HostDetector;
import ai.singlr.sail.engine.OperatorCommand;
import ai.singlr.sail.engine.ScriptedShellExecutor;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class HostStatusCommandTest {

  private static final HostYaml RECORDED_AT_INIT =
      new HostYaml(
          "dir",
          "devpool",
          null,
          "incusbr0",
          "singlr-base",
          "ubuntu/24.04",
          "6.8",
          null,
          "2026-02-18T01:00:00Z");

  private static final HostDetector.HostInfo HOST =
      new HostDetector.HostInfo("main", "ubuntu", "24.04", "Ubuntu 24.04 LTS", 4, 8, 16384, true);

  @Test
  void theIncusVersionIsTheOneInstalledNowNotTheOneRecordedAtInit() throws Exception {
    var shell =
        new ScriptedShellExecutor()
            .onOk("incus list", "[]")
            .onOk("incus version", "Client version: 6.21\nServer version: 6.21\n");

    assertEquals("6.21", json(shell).get("incus_version"));
    assertTrue(text(shell).contains("│  Incus:       6.21 "));
  }

  @Test
  void anIncusThatDoesNotAnswerReadsUnknown() throws Exception {
    var shell =
        new ScriptedShellExecutor()
            .onOk("incus list", "[]")
            .onFail("incus version", "cannot connect to the incus daemon");

    assertEquals("unknown", json(shell).get("incus_version"));
    assertTrue(text(shell).contains("│  Incus:       unknown "));
  }

  @Test
  void runningAndStoppedContainersAreCounted() throws Exception {
    var shell =
        new ScriptedShellExecutor()
            .onOk(
                "incus list",
                """
                [{"name": "acme", "type": "container", "status": "Running", "state": {}},
                 {"name": "fintech", "type": "container", "status": "Stopped", "state": {}}]
                """)
            .onOk("incus version", "Client version: 6.21\nServer version: 6.21\n");

    var status = json(shell);
    assertEquals(2, status.get("containers_total"));
    assertEquals(1, status.get("containers_running"));
    assertEquals(1, status.get("containers_stopped"));
    assertTrue(text(shell).contains("2 total (1 running, 1 stopped)"));
  }

  @Test
  void aDaemonThatDoesNotAnswerStillReportsAndNeverClaimsZeroContainers() throws Exception {
    var shell =
        new ScriptedShellExecutor()
            .onFail("incus list", "cannot connect to the incus daemon")
            .onFail("incus version", "cannot connect to the incus daemon");

    var status = json(shell);
    assertEquals("unknown", status.get("incus_version"));
    assertEquals(
        "incus list failed: cannot connect to the incus daemon", status.get("containers_error"));
    assertFalse(status.containsKey("containers_total"));
    var text = text(shell);
    assertTrue(text.contains("│  Incus:       unknown "), text);
    assertTrue(
        text.contains("unavailable (incus list failed: cannot connect to the incus daemon)"), text);
    assertFalse(text.contains("0 total"), text);
  }

  @Test
  void aStoppedDaemonsErrorIsCutToFitTheTerminalAndWholeInTheJson() throws Exception {
    var stderr =
        "Error: The incus daemon doesn't appear to be started (socket path:"
            + " /var/lib/incus/unix.socket)\nSee 'incus admin init'.\n";
    var shell =
        new ScriptedShellExecutor().onFail("incus list", stderr).onFail("incus version", stderr);

    assertEquals("incus list failed: " + stderr, json(shell).get("containers_error"));
    var box =
        text(shell)
            .lines()
            .dropWhile(line -> !line.startsWith("  ╭"))
            .takeWhile(line -> !line.isEmpty())
            .toList();
    assertEquals(
        List.of(100), box.stream().map(String::length).distinct().toList(), String.join("\n", box));
    assertEquals(
        List.of(
            "  │  Containers:  unavailable (incus list failed: Error: The incus daemon doesn't"
                + " appear to be sta…│"),
        box.stream().filter(line -> line.contains("Containers:")).toList());
  }

  @Test
  void anIncusAptWouldMoveNamesTheCandidateAndTheCommandThatMovesIt() throws Exception {
    var shell =
        new ScriptedShellExecutor()
            .onOk("incus list", "[]")
            .onOk("incus version", "Client version: 6.17\nServer version: 6.17\n")
            .onOk(
                "apt-cache policy incus",
                "incus:\n  Installed: 1:6.17-ubuntu24.04-202510010000\n"
                    + "  Candidate: 1:6.18-ubuntu24.04-202511010000\n")
            .onOk("dpkg --compare-versions");

    var status = json(shell);
    assertEquals("1:6.17-ubuntu24.04-202510010000", status.get("incus_installed"));
    assertEquals("1:6.18-ubuntu24.04-202511010000", status.get("incus_candidate"));
    assertEquals("6.17", status.get("incus_version"));
    assertEquals(
        List.of(
            "  │  Incus:       6.17 (6.18 available)",
            "  │  Upgrade:     sudo sail host incus upgrade"),
        rows(shell, "Incus:", "Upgrade:"));
  }

  @Test
  void aRebuildOfTheSameReleaseIsNamedWholeSoTheRowShowsTheMove() throws Exception {
    var shell =
        new ScriptedShellExecutor()
            .onOk("incus list", "[]")
            .onOk("incus version", "6.17\n")
            .onOk(
                "apt-cache policy incus",
                "incus:\n  Installed: 1:6.17-ubuntu24.04-202510010000\n"
                    + "  Candidate: 1:6.17-ubuntu24.04-202510150000\n")
            .onOk("dpkg --compare-versions");

    var status = json(shell);
    assertEquals("1:6.17-ubuntu24.04-202510010000", status.get("incus_installed"));
    assertEquals("1:6.17-ubuntu24.04-202510150000", status.get("incus_candidate"));
    assertEquals(
        List.of(
            "  │  Incus:       1:6.17-ubuntu24.04-202510010000 (1:6.17-ubuntu24.04-202510150000"
                + " available)",
            "  │  Upgrade:     sudo sail host incus upgrade"),
        rows(shell, "Incus:", "Upgrade:"));
  }

  @Test
  void aCandidateOlderThanTheInstalledIncusLeavesTheRowAlone() throws Exception {
    var shell =
        new ScriptedShellExecutor()
            .onOk("incus list", "[]")
            .onOk("incus version", "6.19\n")
            .onOk(
                "apt-cache policy incus",
                "incus:\n  Installed: 1:6.19-ubuntu24.04-1\n  Candidate: 1:6.18-ubuntu24.04-1\n")
            .onFail("dpkg --compare-versions", "");

    assertEquals("1:6.18-ubuntu24.04-1", json(shell).get("incus_candidate"));
    assertEquals(List.of("  │  Incus:       6.19"), rows(shell, "Incus:", "Upgrade:"));
  }

  @Test
  void anIncusThatIsCurrentOrUnknownToAptKeepsItsRow() throws Exception {
    var current =
        new ScriptedShellExecutor()
            .onOk("incus list", "[]")
            .onOk("incus version", "6.18\n")
            .onOk(
                "apt-cache policy incus",
                "incus:\n  Installed: 1:6.18-ubuntu24.04-1\n  Candidate: 1:6.18-ubuntu24.04-1\n");
    var unknown =
        new ScriptedShellExecutor()
            .onOk("incus list", "[]")
            .onOk("incus version", "6.18\n")
            .onOk("apt-cache policy incus", "");

    assertEquals("1:6.18-ubuntu24.04-1", json(current).get("incus_installed"));
    assertEquals("1:6.18-ubuntu24.04-1", json(current).get("incus_candidate"));
    assertTrue(json(unknown).containsKey("incus_candidate"));
    assertNull(json(unknown).get("incus_candidate"));
    for (var shell : List.of(current, unknown)) {
      assertEquals(List.of("  │  Incus:       6.18"), rows(shell, "Incus:", "Upgrade:"));
    }
  }

  /** The banner's rows labelled any of {@code labels}, the padding before their border dropped. */
  private static List<String> rows(ScriptedShellExecutor shell, String... labels) throws Exception {
    return text(shell)
        .lines()
        .filter(line -> List.of(labels).stream().anyMatch(line::contains))
        .map(line -> line.replaceAll(" *│$", ""))
        .toList();
  }

  private static Map<String, Object> json(ScriptedShellExecutor shell) throws Exception {
    return YamlUtil.parseMap(report(shell, true));
  }

  private static String text(ScriptedShellExecutor shell) throws Exception {
    return report(shell, false);
  }

  private static String report(ScriptedShellExecutor shell, boolean json) throws Exception {
    var out = new ByteArrayOutputStream();
    HostStatusCommand.report(
        RECORDED_AT_INIT,
        HOST,
        shell,
        json,
        OperatorCommand.SUDO,
        new PrintStream(out, true, StandardCharsets.UTF_8));
    return out.toString(StandardCharsets.UTF_8);
  }
}
