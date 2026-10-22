/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.commands;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import ai.singlr.sail.Sail;
import ai.singlr.sail.common.Remedied;
import ai.singlr.sail.config.SyncConfig;
import ai.singlr.sail.engine.Banner;
import ai.singlr.sail.engine.CliOperator;
import ai.singlr.sail.engine.ContainerStateGuard;
import ai.singlr.sail.engine.MainHostKey;
import ai.singlr.sail.engine.OperatorCommand;
import ai.singlr.sail.engine.SshSyncChannel;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import picocli.CommandLine;
import picocli.CommandLine.Help.Ansi;

/**
 * Every message a box prints that names a {@code sail} command for its operator to type, rendered
 * for a {@code sudo} caller on a root box and for an account whose plain {@code sail} runs.
 */
class OperatorHintsTest {

  private static final OperatorCommand SUDO = OperatorCommand.SUDO;
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
        operator.render((Remedied) ContainerStateGuard.notCreated("gamma")),
        launched.toString(StandardCharsets.UTF_8),
        operator.render(CliOperator.unplaced(null).remedy()),
        Ansi.OFF.string(ConnectCommand.noWorkstationKey("gamma", operator)),
        operator.render(MainHostKey.rebuiltRecovery("sail@main")));
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
            "Project 'gamma' has no container on this box. Create it: sudo sail project apply gamma",
            "  ✓ Agent launched in background\n"
                + "    Log:     sudo sail agent log gamma --follow\n"
                + "    Stop:    sudo sail agent stop gamma\n",
            "Name it with 'sudo sail host config set sync-handle <you>', then run 'sudo sail"
                + " sync'.",
            "  ⚠ No workstation key is registered on this box; the snippet below assumes"
                + " ~/.ssh/id_ed25519. If the container rejects your key, register the one you"
                + " connect with, then apply the project to put it in the container:\n"
                + "    sudo sail host config set ssh-public-key\n"
                + "    sudo sail project apply gamma",
            "If main was rebuilt from a copy of its database, remove the old key with 'sudo"
                + " ssh-keygen -R main', re-run 'sudo sail init --main sail@main' and compare the"
                + " fingerprint it prints with main's ('sudo ssh-keygen -lf"
                + " /etc/ssh/ssh_host_ed25519_key.pub' on main); otherwise something between this"
                + " box and main is answering for it."),
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
            "Project 'gamma' has no container on this box. Create it: sail project apply gamma",
            "  ✓ Agent launched in background\n"
                + "    Log:     sail agent log gamma --follow\n"
                + "    Stop:    sail agent stop gamma\n",
            "Name it with 'sail host config set sync-handle <you>', then run 'sail sync'.",
            "  ⚠ No workstation key is registered on this box; the snippet below assumes"
                + " ~/.ssh/id_ed25519. If the container rejects your key, register the one you"
                + " connect with, then apply the project to put it in the container:\n"
                + "    sail host config set ssh-public-key\n"
                + "    sail project apply gamma",
            "If main was rebuilt from a copy of its database, remove the old key with 'ssh-keygen"
                + " -R main', re-run 'sail init --main sail@main' and compare the fingerprint it"
                + " prints with main's ('sudo ssh-keygen -lf /etc/ssh/ssh_host_ed25519_key.pub' on"
                + " main); otherwise something between this box and main is answering for it."),
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
  void aSudoCallerIsToldEveryRootOnlyRemedyWithSudo() {
    var sudoCaller = OperatorCommand.SUDO;

    assertEquals(
        "  Next step: sudo sail init --as-main --handle <you>, or sudo sail init --main <main>,"
            + " installs sail-api and connects this box.",
        Ansi.OFF.string(HostInitCommand.nextStep(true, false, sudoCaller).orElseThrow()));
    assertEquals(
        "Server not initialized. Run 'sudo sail host init' first.",
        HostInitCommand.notInitialized(sudoCaller).getMessage());
    assertEquals(
        "The sail user is not provisioned. Run 'sudo sail host ssh-identity' first.",
        HostKeysCommand.Sync.notProvisioned(sudoCaller));
    assertEquals(
        "The sail user is not provisioned. Run 'sail host ssh-identity' first.",
        HostKeysCommand.Sync.notProvisioned(PLAIN));
  }

  @Test
  void everyRootOnlyVerbIsASailCommand() {
    var sail = new CommandLine(new Sail());
    for (var verb : OperatorCommand.ROOT_ONLY) {
      var command = sail;
      for (var word : verb.split(" ")) {
        command = command.getSubcommands().get(word);
        assertNotNull(command, verb);
      }
    }
  }

  @Test
  void everyCommandThatRefusesANonRootCallerIsARootOnlyVerb() {
    var unlisted = new ArrayList<String>();
    rootGuarded(new CommandLine(new Sail()), "", unlisted);

    assertEquals(List.of(), unlisted, "a command refuses non-root callers but is not ROOT_ONLY");
  }

  /**
   * Walks the command tree collecting every verb whose class, a nested one read from its own
   * declaration on, refuses a non-root caller in any of the words sail uses for it.
   */
  private static void rootGuarded(CommandLine command, String path, List<String> unlisted) {
    for (var sub : command.getSubcommands().entrySet()) {
      var verb = (path + " " + sub.getKey()).strip();
      var type = sub.getValue().getCommandSpec().userObject().getClass();
      var source =
          Path.of("src", "main", "java", type.getNestHost().getName().replace('.', '/') + ".java");
      if (Files.exists(source)
          && refusesNonRoot(read(source), type.getSimpleName())
          && !OperatorCommand.ROOT_ONLY.contains(verb)) {
        unlisted.add(verb);
      }
      rootGuarded(sub.getValue(), verb, unlisted);
    }
  }

  private static boolean refusesNonRoot(String source, String simpleName) {
    var start = source.indexOf("class " + simpleName + " ");
    var nested = source.indexOf("\n  @Command(", start);
    return REFUSES_NON_ROOT
        .matcher(source.substring(start, nested < 0 ? source.length() : nested))
        .find();
  }

  private static final Pattern REFUSES_NON_ROOT =
      Pattern.compile("Root privileges required|requires root|must run as root");

  /**
   * Every command rendered through the seam is one sail has: a verb that does not exist, or a word
   * a command does not take, is a hint nobody can run. The whole argument is read, its literals
   * joined and every value it is built from standing as {@code x}, each way a condition in it can
   * go; a placeholder stands for a value too, and a command cut short of a required parameter
   * passes when every word before the cut is taken.
   */
  @Test
  void everyRenderedCommandIsOneSailHas() throws Exception {
    var rendered = Pattern.compile("\\.(?:sail|root|client)\\(\\s*(?=\")");
    var unknown = new ArrayList<String>();
    for (var root :
        List.of(
            Path.of("..", "sail-core", "src", "main"),
            Path.of("..", "sail-harness", "src", "main"),
            Path.of("src", "main"))) {
      try (var sources = Files.walk(root)) {
        for (var file :
            sources.filter(path -> path.toString().endsWith(".java")).sorted().toList()) {
          var source = read(file);
          var matcher = rendered.matcher(source);
          while (matcher.find()) {
            var call = source.substring(matcher.end(), closingParen(source, matcher.end()));
            for (var args : rendered(file, call)) {
              if (!parses(args)) {
                unknown.add(file.getFileName() + ": " + args);
              }
            }
          }
        }
      }
    }

    assertEquals(List.of(), unknown, "a rendered command is not one sail has");
  }

  private static List<String> rendered(Path file, String call) {
    try {
      return renderedArgs(spaced(call));
    } catch (IllegalStateException unsplit) {
      throw new IllegalStateException(file.getFileName() + ": " + unsplit.getMessage(), unsplit);
    }
  }

  /**
   * A remedy's verb and arguments, {@code Remedy.sail("spec update", id)}, as one expression: each
   * top-level comma stands for the space the rendering puts between its words.
   */
  private static String spaced(String call) {
    var spaced = new StringBuilder();
    var depth = 0;
    for (var i = 0; i < call.length(); i++) {
      var c = call.charAt(i);
      if (c == '"') {
        var end = i + 1;
        while (call.charAt(end) != '"') {
          end += call.charAt(end) == '\\' ? 2 : 1;
        }
        spaced.append(call, i, end + 1);
        i = end;
      } else if (c == '(') {
        depth++;
        spaced.append(c);
      } else if (c == ')') {
        depth--;
        spaced.append(c);
      } else if (c == ',' && depth == 0) {
        spaced.append(" + \" \" + ");
      } else {
        spaced.append(c);
      }
    }
    return spaced.toString();
  }

  /** The index of the {@code )} closing the call whose arguments start at {@code from}. */
  private static int closingParen(String source, int from) {
    var depth = 1;
    for (var i = from; i < source.length(); i++) {
      var c = source.charAt(i);
      if (c == '"') {
        for (i++; source.charAt(i) != '"'; i++) {
          if (source.charAt(i) == '\\') {
            i++;
          }
        }
      } else if (c == '\'') {
        i += source.charAt(i + 1) == '\\' ? 3 : 2;
      } else if (c == '(') {
        depth++;
      } else if (c == ')' && --depth == 0) {
        return i;
      }
    }
    throw new IllegalStateException("unclosed call at " + from);
  }

  private static final Pattern TERNARY =
      Pattern.compile(
          "\\((?:[^()?]|\\([^()]*\\))*\\?\\s*([^()]*(?:\\([^()]*\\)[^()]*)*?)\\s*:\\s*([^()]*(?:\\([^()]*\\)[^()]*)*)\\)");
  private static final Pattern OPERAND =
      Pattern.compile("\"((?:[^\"\\\\]|\\\\.)*)\"|[A-Za-z_][\\w.]*(?:\\([^()]*\\))?|\\d+");

  /**
   * The commands an argument expression renders, one for each way its conditions can go; a
   * condition the splitter cannot read fails the test rather than pass as one word.
   */
  private static List<String> renderedArgs(String expression) {
    var ternary = TERNARY.matcher(expression);
    if (!ternary.find()) {
      if (OPERAND.matcher(expression).replaceAll("").contains("?")) {
        throw new IllegalStateException("a condition this test cannot split: " + expression);
      }
      return List.of(joined(expression));
    }
    var rendered = new ArrayList<String>();
    for (var branch : List.of(ternary.group(1), ternary.group(2))) {
      rendered.addAll(
          renderedArgs(
              expression.substring(0, ternary.start())
                  + branch
                  + expression.substring(ternary.end())));
    }
    return rendered;
  }

  /** An expression's literals joined, every other operand standing as x. */
  private static String joined(String expression) {
    var rendered = new StringBuilder();
    var operands = OPERAND.matcher(expression);
    while (operands.find()) {
      rendered.append(operands.group(1) != null ? operands.group(1) : "x");
    }
    return rendered.toString();
  }

  private static boolean parses(String args) {
    var words =
        Arrays.stream(
                args.replaceAll("\"?<[^>]*>\"?|'[^']*'", "x")
                    .replaceAll("\\|\\S*", "")
                    .strip()
                    .split("\\s+"))
            .filter(word -> !word.isEmpty() && !word.endsWith("..."))
            .toArray(String[]::new);
    var sail = new CommandLine(new Sail()).setUnmatchedArgumentsAllowed(true);
    try {
      for (var parsed = sail.parseArgs(words); parsed != null; parsed = parsed.subcommand()) {
        if (!parsed.unmatched().isEmpty()) {
          return false;
        }
      }
      return true;
    } catch (CommandLine.MissingParameterException cutShort) {
      for (var line = cutShort.getCommandLine(); line != null; line = line.getParent()) {
        if (!line.getUnmatchedArguments().isEmpty()) {
          return false;
        }
      }
      return true;
    } catch (CommandLine.ParameterException unknown) {
      return false;
    }
  }

  @Test
  void anotherBoxsOperatorIsToldTheReadmesFormAndAThinClientPlainSail() {
    assertEquals(
        "sudo sail fde add ada --role member --email \"ada@example.com\" --key \"ssh-ed25519"
            + " AAAA\" --main-host-key SHA256:abc",
        SshSyncChannel.authorizeLine(
            "ada", null, "ada@example.com", "ssh-ed25519 AAAA", "SHA256:abc"));
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
    var seam = Pattern.compile("OperatorCommand(?:\\.here\\(\\)|::here)");
    var below = new ArrayList<String>();
    var twice = new ArrayList<String>();
    for (var root :
        List.of(
            Path.of("..", "sail-core", "src", "main"),
            Path.of("..", "sail-harness", "src", "main"),
            Path.of("src", "main"))) {
      try (var sources = Files.walk(root)) {
        for (var file : sources.filter(Files::isRegularFile).toList()) {
          var calls = (int) seam.matcher(read(file)).results().count();
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

  /**
   * The stores, the sync lane, the harness, sail-api and the daemons cannot see who reads what they
   * say, so none renders a command: each hands its remedy on as data, and the door that prints it
   * renders it for its reader.
   */
  @Test
  void codeThatCannotSeeItsReaderNeverRendersACommand() throws Exception {
    var daemons =
        List.of(
            Path.of("src/main/java/ai/singlr/sail/commands/ServerStartCommand.java"),
            Path.of("src/main/java/ai/singlr/sail/commands/AgentWatchCommand.java"),
            Path.of("src/main/java/ai/singlr/sail/commands/PtyHostCommand.java"),
            Path.of("src/main/java/ai/singlr/sail/commands/PtyHostEvents.java"),
            Path.of("src/main/java/ai/singlr/sail/commands/PtyHostIdentity.java"),
            Path.of("src/main/java/ai/singlr/sail/commands/PtyHostRooms.java"),
            Path.of("src/main/java/ai/singlr/sail/commands/SyncServerCommand.java"),
            Path.of("src/main/java/ai/singlr/sail/commands/GatewayCommand.java"),
            Path.of("src/main/java/ai/singlr/sail/engine/SyncOperations.java"),
            Path.of("src/main/java/ai/singlr/sail/engine/MainHostKey.java"),
            Path.of("src/main/java/ai/singlr/sail/engine/ConflictOperations.java"),
            Path.of("src/main/java/ai/singlr/sail/engine/HostAccess.java"),
            Path.of("src/main/java/ai/singlr/sail/engine/CliOperator.java"),
            Path.of("src/main/java/ai/singlr/sail/engine/ConnectEnvironment.java"),
            Path.of("src/main/java/ai/singlr/sail/engine/WorkstationIdentity.java"),
            Path.of("src/main/java/ai/singlr/sail/engine/RoomWakePrompt.java"));
    var sources = new ArrayList<Path>(daemons);
    for (var root :
        List.of(
            Path.of("..", "sail-core", "src", "main"),
            Path.of("..", "sail-harness", "src", "main"),
            Path.of("src", "main", "java", "ai", "singlr", "sail", "api"))) {
      try (var walk = Files.walk(root)) {
        walk.filter(path -> path.toString().endsWith(".java")).forEach(sources::add);
      }
    }
    var rendering = new ArrayList<String>();
    for (var file : sources) {
      if (read(file).contains("OperatorCommand")) {
        rendering.add(file.toString());
      }
    }

    assertEquals(List.of(), rendering, "code that cannot see its reader renders a command");
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
  @EnabledOnOs(OS.LINUX)
  void theInstallerEndsWithTheReadmesTwoSetupLinesTypedAsItsReaderTypesThem() throws Exception {
    assertEquals(
        """
        Get started:
          sudo sail init --as-main --handle <you>   # make this box the org's main
          sudo sail init --main <main>              # or join an existing main
        """,
        installerEnding(1000));
    assertEquals(
        """
        Get started:
          sail init --as-main --handle <you>   # make this box the org's main
          sail init --main <main>              # or join an existing main
        """,
        installerEnding(0));
  }

  /** What the installer prints after installing on Linux, run as the account {@code uid}. */
  private static String installerEnding(int uid) throws Exception {
    var installer = Files.readString(Path.of("..", "install.sh"));
    var ending = installer.substring(installer.lastIndexOf("if [ \"$OS\" = \"Darwin\" ]"));
    var script = "info() { echo \"$1\"; }\nid() { echo \"$UID_UNDER_TEST\"; }\nOS=Linux\n" + ending;
    var process = new ProcessBuilder("bash", "-c", script).redirectErrorStream(true);
    process.environment().put("UID_UNDER_TEST", String.valueOf(uid));
    var running = process.start();
    var printed = new String(running.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    assertEquals(0, running.waitFor(), printed);
    return printed;
  }

  /**
   * A command sail renders with the value in hand names it: {@code <name>} is a placeholder for a
   * project or session the reader picks, never for one the code holds, so no command renders it.
   */
  @Test
  void noRenderedCommandLeavesTheNameAsAPlaceholder() throws Exception {
    var rendered = Pattern.compile("\\.(?:sail|root|client)\\(");
    var placeholders = new ArrayList<String>();
    for (var root :
        List.of(
            Path.of("..", "sail-core", "src", "main"),
            Path.of("..", "sail-harness", "src", "main"),
            Path.of("src", "main"))) {
      try (var sources = Files.walk(root)) {
        for (var file :
            sources.filter(path -> path.toString().endsWith(".java")).sorted().toList()) {
          var source = read(file);
          var matcher = rendered.matcher(source);
          while (matcher.find()) {
            var call = source.substring(matcher.end(), closingParen(source, matcher.end()));
            if (call.contains("<name>")) {
              placeholders.add(file.getFileName() + ": " + call);
            }
          }
        }
      }
    }

    assertEquals(List.of(), placeholders, "a rendered command leaves <name> for the reader");
  }
}
