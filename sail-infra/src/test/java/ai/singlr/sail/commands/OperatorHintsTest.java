/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.commands;

import static org.junit.jupiter.api.Assertions.assertEquals;

import ai.singlr.sail.config.SyncConfig;
import ai.singlr.sail.engine.Banner;
import ai.singlr.sail.engine.ContainerStateGuard;
import ai.singlr.sail.engine.OperatorCommand;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import picocli.CommandLine.Help.Ansi;

/**
 * Every message a box prints that names a {@code sail} command for its operator to type, rendered
 * for a {@code sudo} caller on a root box and for an account whose plain {@code sail} runs.
 */
class OperatorHintsTest {

  private static final OperatorCommand SUDO = new OperatorCommand(true);
  private static final OperatorCommand PLAIN = OperatorCommand.PLAIN;

  private static List<String> hints(OperatorCommand operator) {
    var zed = new ByteArrayOutputStream();
    Banner.printZedConnect("gamma", operator, new PrintStream(zed), Ansi.OFF);
    var launched = new ByteArrayOutputStream();
    Banner.printAgentLaunched("gamma", null, null, operator, new PrintStream(launched), Ansi.OFF);
    return List.of(
        HostSyncCommand.roleLines(new SyncConfig(null, null, null), operator, Ansi.OFF),
        Ansi.OFF.string(HostSshIdentityCommand.enabledLine(operator)),
        zed.toString(StandardCharsets.UTF_8),
        Ansi.OFF.string(ProjectApplyCommand.serverIpUnsetLines("gamma", operator)),
        ConnectCommand.serverIpUnset("gamma", operator),
        Ansi.OFF.string(HostInitCommand.nextStep(true, true, operator).orElseThrow()),
        ProjectApplyCommand.descriptorNotFound(Path.of("/srv/gamma/sail.yaml"), operator),
        HostInitCommand.notInitialized(operator).getMessage(),
        ContainerStateGuard.notCreated("gamma", operator),
        launched.toString(StandardCharsets.UTF_8));
  }

  @Test
  void aSudoCallerOnARootBoxIsToldEveryCommandWithSudo() {
    assertEquals(
        List.of(
            "  No sync role set. Run sudo sail host sync --as-main or sudo sail host sync --main"
                + " <target>.\n"
                + "  No FDE set — dispatch can't pick this box's specs. Set it: sudo sail host"
                + " config set sync-handle <handle>",
            "  ✓ SSH-key login enabled. Register engineers with 'sudo sail fde add <handle> --key"
                + " \"<pubkey>\"'.",
            "    → Connect: sudo sail project connect gamma\n"
                + "    → Shell:   sudo sail project shell gamma\n",
            "    Server IP not configured. Run: sudo sail host config set server-ip"
                + " <your-server-ip>\n"
                + "    Then: sudo sail project connect gamma for SSH config snippet.",
            "Server IP not configured. Fix with one of:\n"
                + "  - sudo sail host config set server-ip <your-server-ip>\n"
                + "  - Or pass: sudo sail project connect gamma --server-ip <your-server-ip>",
            "  ✓ sail API service already installed — nothing to do here; 'sudo sail upgrade'"
                + " keeps it current.",
            "Project descriptor not found: /srv/gamma/sail.yaml\n"
                + "  Run 'sudo sail project init' to author one, sync it from main with 'sudo sail"
                + " sync', or specify one with --file.",
            "Server not initialized. Run 'sudo sail host init' first.",
            "Project 'gamma' does not exist. Run 'sudo sail project apply <name>' first.",
            "  ✓ Agent launched in background\n"
                + "    Log:     sudo sail agent log gamma --follow\n"
                + "    Stop:    sudo sail agent stop gamma\n"),
        hints(SUDO));
  }

  @Test
  void rootOrABoxsOwnAccountIsToldEveryCommandAsToday() {
    assertEquals(
        List.of(
            "  No sync role set. Run sail host sync --as-main or sail host sync --main <target>.\n"
                + "  No FDE set — dispatch can't pick this box's specs. Set it: sail host config"
                + " set sync-handle <handle>",
            "  ✓ SSH-key login enabled. Register engineers with 'sail fde add <handle> --key"
                + " \"<pubkey>\"'.",
            "    → Connect: sail project connect gamma\n    → Shell:   sail project shell gamma\n",
            "    Server IP not configured. Run: sudo sail host config set server-ip"
                + " <your-server-ip>\n"
                + "    Then: sail project connect gamma for SSH config snippet.",
            "Server IP not configured. Fix with one of:\n"
                + "  - sudo sail host config set server-ip <your-server-ip>\n"
                + "  - Or pass: sail project connect gamma --server-ip <your-server-ip>",
            "  ✓ sail API service already installed — nothing to do here; 'sail upgrade' keeps it"
                + " current.",
            "Project descriptor not found: /srv/gamma/sail.yaml\n"
                + "  Run 'sail project init' to author one, sync it from main with 'sail sync', or"
                + " specify one with --file.",
            "Server not initialized. Run 'sail host init' first.",
            "Project 'gamma' does not exist. Run 'sail project apply <name>' first.",
            "  ✓ Agent launched in background\n"
                + "    Log:     sail agent log gamma --follow\n"
                + "    Stop:    sail agent stop gamma\n"),
        hints(PLAIN));
  }

  @Test
  void aBoxWithARoleAndAnFdeNamesNoCommand() {
    assertEquals(
        "  ✓ This box is main.\n  FDE: ada",
        HostSyncCommand.roleLines(new SyncConfig("main", null, "ada"), SUDO, Ansi.OFF));
    assertEquals(
        "  ✓ This box is a node syncing to sail@main.\n  FDE: ada",
        HostSyncCommand.roleLines(new SyncConfig("node", "sail@main", "ada"), SUDO, Ansi.OFF));
  }

  @Test
  void aStandaloneHostInitNamesTheSetupItIsAStepOf() {
    assertEquals(
        Optional.of(
            "  @|bold Next step:|@ sudo sail init --as-main, or sudo sail init --main <main>,"
                + " installs sail-api and connects this box."),
        HostInitCommand.nextStep(true, false, PLAIN));
  }

  @Test
  void hostInitInsideInitTellsTheOperatorNothingToRun() {
    assertEquals(Optional.empty(), HostInitCommand.nextStep(false, false, SUDO));
    assertEquals(Optional.empty(), HostInitCommand.nextStep(false, true, SUDO));
    assertEquals(false, HostInitCommand.initStep().standalone());
    assertEquals(true, new HostInitCommand().standalone());
  }

  @Test
  void theInstallerEndsWithTheReadmesTwoSetupLines() throws Exception {
    var installer = Files.readString(Path.of("..", "install.sh"));
    var linux = installer.substring(installer.lastIndexOf("else\n"));

    assertEquals(
        """
        else
          info "Get started:"
          info "  sudo sail init --as-main        # make this box the org's main"
          info "  sudo sail init --main <main>    # or join an existing main"
        fi
        """,
        linux);
  }
}
