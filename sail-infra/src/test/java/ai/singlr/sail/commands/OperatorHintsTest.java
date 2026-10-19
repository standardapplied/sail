/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.commands;

import static org.junit.jupiter.api.Assertions.assertEquals;

import ai.singlr.sail.Sail;
import ai.singlr.sail.config.SyncConfig;
import ai.singlr.sail.engine.Banner;
import ai.singlr.sail.engine.CliOperator;
import ai.singlr.sail.engine.ContainerStateGuard;
import ai.singlr.sail.engine.MainHostKey;
import ai.singlr.sail.engine.OperatorCommand;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import picocli.CommandLine;
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
        launched.toString(StandardCharsets.UTF_8),
        CliOperator.unplaced(null, operator).failure().action(),
        Ansi.OFF.string(ConnectCommand.noWorkstationKey("gamma", operator)),
        MainHostKey.rebuiltRecovery("sail@main", operator));
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
                + "    Stop:    sudo sail agent stop gamma\n",
            "Name it with 'sudo sail host config set sync-handle <you>', then run 'sudo sail"
                + " sync'.",
            "  ⚠ No workstation key is registered on this box; the snippet below assumes"
                + " ~/.ssh/id_ed25519. If the container rejects your key, register the one you"
                + " connect with, then run 'sudo sail project apply gamma':\n"
                + "    sudo sail host config set ssh-public-key",
            "If main was rebuilt, remove the old key with 'sudo ssh-keygen -R main' and re-run"
                + " 'sudo sail init --main sail@main'; otherwise something between this box and"
                + " main is answering for it."),
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
            "    Server IP not configured. Run: sail host config set server-ip <your-server-ip>\n"
                + "    Then: sail project connect gamma for SSH config snippet.",
            "Server IP not configured. Fix with one of:\n"
                + "  - sail host config set server-ip <your-server-ip>\n"
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
                + "    Stop:    sail agent stop gamma\n",
            "Name it with 'sail host config set sync-handle <you>', then run 'sail sync'.",
            "  ⚠ No workstation key is registered on this box; the snippet below assumes"
                + " ~/.ssh/id_ed25519. If the container rejects your key, register the one you"
                + " connect with, then run 'sail project apply gamma':\n"
                + "    sail host config set ssh-public-key",
            "If main was rebuilt, remove the old key with 'ssh-keygen -R main' and re-run 'sail"
                + " init --main sail@main'; otherwise something between this box and main is"
                + " answering for it."),
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
            "  @|bold Next step:|@ sudo sail init --as-main --handle <you>, or sudo sail init"
                + " --main <main>, installs sail-api and connects this box."),
        HostInitCommand.nextStep(true, false, SUDO));
    assertEquals(
        Optional.of(
            "  @|bold Next step:|@ sail init --as-main --handle <you>, or sail init --main"
                + " <main>, installs sail-api and connects this box."),
        HostInitCommand.nextStep(true, false, PLAIN));
  }

  @Test
  void anotherBoxsOperatorIsToldTheReadmesFormAndAThinClientPlainSail() {
    assertEquals(
        "sudo sail fde add ada --role member --email \"ada@example.com\" --key \"ssh-ed25519"
            + " AAAA\" --main-host-key SHA256:abc",
        JoinCommand.authorizeLine(
            "ada", null, "ada@example.com", "ssh-ed25519 AAAA", "SHA256:abc"));
    assertEquals("sudo sail spec prune", OperatorCommand.elsewhere("spec prune"));
    assertEquals("sail login", OperatorCommand.client("login"));
  }

  @Test
  void hostInitInsideInitTellsTheOperatorNothingToRun() {
    assertEquals(Optional.empty(), HostInitCommand.nextStep(false, false, SUDO));
    assertEquals(Optional.empty(), HostInitCommand.nextStep(false, true, SUDO));
    assertEquals(false, HostInitCommand.initStep().standalone());
    assertEquals(true, new HostInitCommand().standalone());
  }

  @Test
  void theOperatorIsResolvedOnceAtEachCommandsDoorAndNowhereBelow() throws Exception {
    var seam = "OperatorCommand.here()";
    var below = new ArrayList<String>();
    var twice = new ArrayList<String>();
    for (var root :
        List.of(
            Path.of("..", "sail-core", "src", "main"),
            Path.of("..", "sail-harness", "src", "main"),
            Path.of("src", "main"))) {
      try (var sources = Files.walk(root)) {
        for (var file : sources.filter(Files::isRegularFile).toList()) {
          var calls = read(file).split(Pattern.quote(seam), -1).length - 1;
          if (calls == 0) {
            continue;
          }
          if (!atADoor(file)) {
            below.add(file.toString());
          }
          if (calls > 1) {
            twice.add(file.toString());
          }
        }
      }
    }

    assertEquals(List.of(), below, "the operator is asked below a command's door");
    assertEquals(List.of(), twice, "a command asks who its operator is more than once");
  }

  @Test
  void noLiteralSpellsASailCommandOutsideTheSeam() throws Exception {
    var verbs = String.join("|", new CommandLine(new Sail()).getSubcommands().keySet());
    var command = Pattern.compile("\\bsudo sail\\b|\\bsail (" + verbs + ")\\b(?!-)");
    var spelled = new ArrayList<String>();
    for (var root :
        List.of(
            Path.of("..", "sail-core", "src", "main"),
            Path.of("..", "sail-harness", "src", "main"),
            Path.of("src", "main"))) {
      try (var sources = Files.walk(root)) {
        for (var file :
            sources.filter(path -> path.toString().endsWith(".java")).sorted().toList()) {
          if (file.endsWith(Path.of("engine", "OperatorCommand.java"))) {
            continue;
          }
          for (var literal : JavaLiterals.of(read(file))) {
            if (command.matcher(literal).find()) {
              spelled.add(file.getFileName() + ": " + literal);
            }
          }
        }
      }
    }

    assertEquals(List.of(), spelled, "a literal spells a sail command beside OperatorCommand");
  }

  private static boolean atADoor(Path file) {
    var name = file.getFileName().toString();
    var inCommands = file.getParent().endsWith(Path.of("ai", "singlr", "sail", "commands"));
    return inCommands && (name.endsWith("Command.java") || name.equals("CliCommand.java"))
        || file.getParent().endsWith(Path.of("ai", "singlr", "sail")) && name.equals("Main.java");
  }

  private static String read(Path file) {
    try {
      return Files.readString(file);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  @Test
  void theReadmesQuickStartTypesEverySailCommandWithSudo() throws Exception {
    var readme = Files.readString(Path.of("..", "README.md"));
    var start = readme.indexOf("## Quick start");
    var quickStart = readme.substring(start, readme.indexOf("\n## ", start));
    var verbs = String.join("|", new CommandLine(new Sail()).getSubcommands().keySet());
    var command = Pattern.compile("(?<!sudo )\\bsail (" + verbs + ")\\b(?!-)");
    var code = Pattern.compile("```.*?```|`[^`]+`", Pattern.DOTALL);
    var unsudoed = new ArrayList<String>();
    for (var paragraph : quickStart.split("\n\n")) {
      if (paragraph.contains("thin client")) {
        continue;
      }
      var spans = code.matcher(paragraph);
      while (spans.find()) {
        var span = spans.group().replace('\n', ' ');
        if (command.matcher(span).find()) {
          unsudoed.add(span);
        }
      }
    }

    assertEquals(List.of(), unsudoed, "a system box's README command without sudo");
  }

  @Test
  void theInstallerEndsWithTheReadmesTwoSetupLines() throws Exception {
    var installer = Files.readString(Path.of("..", "install.sh"));
    var linux = installer.substring(installer.lastIndexOf("else\n"));

    assertEquals(
        """
        else
          info "Get started:"
          info "  sudo sail init --as-main --handle <you>   # make this box the org's main"
          info "  sudo sail init --main <main>              # or join an existing main"
        fi
        """,
        linux);
  }
}
