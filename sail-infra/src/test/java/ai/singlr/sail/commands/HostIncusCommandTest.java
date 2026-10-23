/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.commands;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import ai.singlr.sail.config.RunStatus;
import ai.singlr.sail.engine.OperatorCommand;
import ai.singlr.sail.engine.ScriptedShellExecutor;
import ai.singlr.sail.pty.PtyEvents;
import ai.singlr.sail.pty.PtyIdentity;
import ai.singlr.sail.pty.PtyMessage;
import ai.singlr.sail.pty.PtyRooms;
import ai.singlr.sail.pty.PtySessionHost;
import ai.singlr.sail.store.RunStore;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.channels.ServerSocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.function.Supplier;
import java.util.regex.MatchResult;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

class HostIncusCommandTest {

  private static final String APT_UPDATE = "Dpkg::Options::=--force-confold update";
  private static final String UPGRADE = "--only-upgrade --allow-change-held-packages";

  private static final String MOVABLE =
      """
      incus:
        Installed: 1:6.17-ubuntu24.04-202510010000
        Candidate: 1:6.18-ubuntu24.04-202511010000
      """;

  private static final HostIncusCommand.LiveWork BUSY =
      new HostIncusCommand.LiveWork(
          List.of(session("design", "claude"), session("ops", "bash")),
          null,
          List.of(run("01a1", "acme", "spec-x", "build")));

  private static final Duration DEADLINE = Duration.ofSeconds(5);

  private static final HostIncusCommand.LiveWork QUIET =
      new HostIncusCommand.LiveWork(List.of(), null, List.of());

  private final ByteArrayOutputStream terminal = new ByteArrayOutputStream();
  private final PrintStream originalOut = System.out;

  @BeforeEach
  void questionsAskedOnTheTerminal() {
    System.setOut(new PrintStream(terminal, true, StandardCharsets.UTF_8));
  }

  @AfterEach
  void restore() {
    System.setOut(originalOut);
    ConsoleStub.restore();
  }

  @Test
  void aCurrentIncusIsSaidSoAndNothingIsInstalledOrAsked() throws Exception {
    var shell =
        box("incus:\n  Installed: 1:6.18-ubuntu24.04-1\n  Candidate: 1:6.18-ubuntu24.04-1\n");

    var out =
        upgrade(shell, () -> fail("nothing is listed for an Incus that does not move"), false);

    assertEquals("  ✓ Incus 6.18 is current.\n", out);
    assertEquals(List.of(), installs(shell));
    assertTrue(shell.unbounded().getFirst().endsWith(" update"), shell.unbounded().toString());
  }

  @Test
  void aNewerIncusListsWhatTheRestartEndsAndAnsweringNoInstallsNothing() {
    var shell = box(MOVABLE);
    ConsoleStub.answering("n\n", true);

    var listed = new StringBuilder();

    var refused =
        assertThrows(
            CliCommand.Refused.class, () -> upgrade(shell, () -> BUSY, false, false, listed));

    assertEquals("Aborted.", refused.getMessage());
    assertEquals(
        "  Upgrade Incus 6.17 to 6.18? incusd restarts and ends the 2 sessions and 1 agent run"
            + " above. [y/N] ",
        printed());
    assertEquals(
        """
          Live on this box:
            session    design  claude
            session    ops  bash
            agent run  01a1  acme spec-x (build)
        """,
        listed.toString());
    assertEquals(List.of(), installs(shell));
  }

  @Test
  void withNoTerminalAndNoYesNothingIsUpgraded() {
    var shell = box(MOVABLE);
    ConsoleStub.answering("y\n", false);

    var refused = assertThrows(CliCommand.Refused.class, () -> upgrade(shell, () -> BUSY, false));

    assertEquals(
        "Incus not upgraded: no terminal to confirm on. Re-run with --yes.", refused.getMessage());
    assertEquals(List.of(), installs(shell));
  }

  @Test
  void yesUpgradesOnlyTheHeldIncusPackagesAndHoldsThemAgain() throws Exception {
    var shell = box(MOVABLE);

    var out = upgrade(shell, () -> BUSY, true);

    assertEquals(
        List.of(
            "env LC_ALL=C DEBIAN_FRONTEND=noninteractive apt-get -q -o APT::Status-Fd=1 -o"
                + " Acquire::Retries=3 -o DPkg::Lock::Timeout=600 -o"
                + " Dpkg::Options::=--force-confdef -o Dpkg::Options::=--force-confold install -y "
                + UPGRADE
                + " incus incus-client",
            "apt-mark hold incus incus-client"),
        installs(shell));
    assertTrue(out.endsWith("  ✓ Incus upgraded from 6.17 to 6.18.\n"), out);
  }

  @Test
  void withNothingLiveItAsksWithADefaultOfYes() throws Exception {
    var shell = box(MOVABLE);
    ConsoleStub.answering("\n", true);

    var out = upgrade(shell, () -> QUIET, false);

    assertEquals(
        "  Upgrade Incus 6.17 to 6.18? No session or agent run is live to end. [Y/n] ", printed());
    assertEquals(2, installs(shell).size());
    assertTrue(out.endsWith("  ✓ Incus upgraded from 6.17 to 6.18.\n"), out);
  }

  @Test
  void aPtyHostThatDoesNotAnswerIsSaidAndTheQuestionDefaultsToNo(@TempDir Path dir) {
    var shell = box(MOVABLE);
    var live =
        HostIncusCommand.LiveWork.of(
            dir.resolve("absent.sock"), List.of(run("01a2", "acme", null, "room")), DEADLINE);
    assertNotNull(live.sessionsUnread());
    ConsoleStub.answering("\n", true);

    var listed = new StringBuilder();

    var refused =
        assertThrows(
            CliCommand.Refused.class, () -> upgrade(shell, () -> live, false, false, listed));

    assertEquals("Aborted.", refused.getMessage());
    assertEquals(
        "  ⚠ Could not list terminal sessions ("
            + live.sessionsUnread()
            + ").\n"
            + "  Live on this box:\n"
            + "    agent run  01a2  acme (room)\n",
        listed.toString());
    assertEquals(
        "  Upgrade Incus 6.17 to 6.18? incusd restarts and ends every terminal session and 1"
            + " agent run. [y/N] ",
        printed());
    assertEquals(List.of(), installs(shell));
  }

  @Test
  void aDryRunReadsTheLocalIndexListsTheWorkAndChangesNothing() throws Exception {
    var shell = box(MOVABLE);

    var out = upgrade(shell, () -> QUIET, false, true, new StringBuilder());

    assertEquals("  Would upgrade Incus 6.17 to 6.18 (--dry-run: nothing installed).\n", out);
    assertEquals(List.of(), shell.unbounded());
    assertEquals(List.of(), installs(shell));
  }

  @Test
  void aBoxWithoutIncusIsToldToProvisionFirst() {
    var shell = box("incus:\n  Installed: (none)\n  Candidate: 1:6.18-1\n");

    var refused =
        assertThrows(IllegalStateException.class, () -> upgrade(shell, () -> QUIET, true));

    assertEquals("Server not initialized. Run 'sudo sail host init' first.", refused.getMessage());
  }

  @Test
  @EnabledOnOs(OS.LINUX)
  void theLiveSessionsAreEveryOneThePtyHostRunsWhoeverOpenedIt(@TempDir Path dir) throws Exception {
    var socket = dir.resolve("pty.sock");
    try (var host =
            PtyHostCommand.startHost(
                socket,
                dir.resolve("sessions"),
                token -> new PtyIdentity(token, false),
                PtyRooms.NONE,
                PtyEvents.NONE);
        var alice = SessionClient.connect(socket, "alice");
        var bob = SessionClient.connect(socket, "bob")) {
      alice.create("alice-live", List.of("sh", "-c", "read a"), "/tmp", "", "", 80, 24);
      bob.create("bob-live", List.of("sh", "-c", "read a"), "/tmp", "", "", 80, 24);

      var live = HostIncusCommand.LiveWork.of(socket, List.of(), DEADLINE);

      assertNull(live.sessionsUnread());
      assertEquals(
          List.of("alice-live", "bob-live"),
          live.sessions().stream().map(PtyMessage.SessionInfo::name).sorted().toList());
    }
  }

  @Test
  @EnabledOnOs(OS.LINUX)
  void aPtyHostThatAcceptsButNeverRepliesReadsAsUnlisted(@TempDir Path dir) throws Exception {
    var socket = dir.resolve("pty.sock");
    Files.writeString(PtySessionHost.dispatchCredentialOf(socket), "credential");
    try (var silent = ServerSocketChannel.open(StandardProtocolFamily.UNIX)) {
      silent.bind(UnixDomainSocketAddress.of(socket));

      var live =
          HostIncusCommand.LiveWork.of(
              socket, List.of(run("01a3", "acme", null, "room")), Duration.ofSeconds(1));

      assertEquals("the session host did not answer within 1s", live.sessionsUnread());
      assertEquals(List.of(), live.sessions());
      assertEquals(1, live.runs().size());
    }
  }

  @Test
  void hostHelpListsIncusAndIncusHelpListsUpgrade() {
    var host = SailCli.run("host", "--help");
    var incus = SailCli.run("host", "incus");

    assertTrue(
        host.out()
            .contains("  incus         Manage the Incus sail installs and holds on this host."),
        host.out());
    assertTrue(
        incus.out().contains("  upgrade  Move Incus to the version apt offers, after listing the"),
        incus.out());
  }

  @Test
  void theReadmeSaysSailHoldsIncusAndMovesItOnlyWhenAskedAndTheOsIsTheOperators() throws Exception {
    var readme = Files.readString(Path.of("..", "README.md"));
    var start = readme.indexOf("Sail installs what it runs on");

    assertEquals(
        """
        Sail installs what it runs on and nothing else: Incus from Zabbly's stable repository, and
        zfsutils-linux for a zfs pool. It holds the incus packages, so neither `apt upgrade` nor
        unattended-upgrades restarts Incus, which would end every terminal and agent run in every
        container. `sudo sail host incus upgrade` moves Incus when you choose: it lists the sessions and
        agent runs the restart ends, then asks. The rest of the OS is yours to keep current; sail never
        upgrades it. The needrestart policy sail installs (`/etc/needrestart/conf.d/50-sail.conf`) keeps
        a library update from restarting Incus.""",
        readme.substring(start, readme.indexOf("\n\n", start)));
    assertEquals(
        List.of(),
        Pattern.compile("(?i)upgrades? (ubuntu and )?incus with apt")
            .matcher(readme)
            .results()
            .map(MatchResult::group)
            .toList());
  }

  private String upgrade(
      ScriptedShellExecutor shell, Supplier<HostIncusCommand.LiveWork> live, boolean yes)
      throws Exception {
    return upgrade(shell, live, yes, false, new StringBuilder());
  }

  /**
   * Runs the upgrade, its questions asked on {@link #terminal}; what it printed before it returned
   * or threw lands in {@code printed}, which it also returns, spinners cleared.
   */
  private String upgrade(
      ScriptedShellExecutor shell,
      Supplier<HostIncusCommand.LiveWork> live,
      boolean yes,
      boolean dryRun,
      StringBuilder printed)
      throws Exception {
    var out = new ByteArrayOutputStream();
    try {
      HostIncusCommand.upgrade(
          shell,
          live,
          yes,
          dryRun,
          OperatorCommand.SUDO,
          new PrintStream(out, true, StandardCharsets.UTF_8));
    } finally {
      printed.append(withoutSpinners(out.toString(StandardCharsets.UTF_8)));
    }
    return printed.toString();
  }

  private String printed() {
    return terminal.toString(StandardCharsets.UTF_8);
  }

  /** Everything a spinner drew, which it clears itself, is gone from {@code text}. */
  private static String withoutSpinners(String text) {
    return text.replaceAll("(\\r[^\\r\\n]*)*\\r\\033\\[2K", "");
  }

  private static ScriptedShellExecutor box(String policy) {
    return new ScriptedShellExecutor()
        .onOk(APT_UPDATE)
        .onOk("apt-cache policy incus", policy)
        .onOk("dpkg-query", "incus hold installed\nincus-client hold installed\n")
        .onOk(UPGRADE)
        .onOk("apt-mark hold");
  }

  private static List<String> installs(ScriptedShellExecutor shell) {
    return shell.invocations().stream()
        .filter(command -> command.contains(" install ") || command.startsWith("apt-mark"))
        .toList();
  }

  private static PtyMessage.SessionInfo session(String name, String command) {
    return new PtyMessage.SessionInfo(name, "i-" + name, true, 1, "uday", "", List.of(command));
  }

  private static RunStore.RunRow run(String id, String project, String specId, String role) {
    return new RunStore.RunRow(
        id,
        project,
        specId,
        "uday",
        role,
        "claude-code",
        null,
        null,
        null,
        null,
        RunStatus.RUNNING.wire(),
        null,
        null,
        null,
        null,
        null,
        List.of(),
        null,
        null,
        null);
  }
}
