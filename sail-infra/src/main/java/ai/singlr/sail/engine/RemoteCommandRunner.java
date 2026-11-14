/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.engine;

import ai.singlr.sail.Main;
import ai.singlr.sail.Sail;
import ai.singlr.sail.commands.SailCommandLine;
import ai.singlr.sail.config.ClientConfig;
import ai.singlr.sail.ssh.SshGateway;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import picocli.CommandLine.Help.Ansi;

/**
 * Executes SAIL commands on a remote host via SSH. Used in client mode when the binary detects it
 * is running on a Mac (or any machine without {@code /etc/sail/host.yaml}).
 *
 * <p>Commands are forwarded as-is to the host over one of two lanes. Commands the FDE gateway
 * accepts target {@code sail@host}, where the engineer's SSH key resolves to their FDE and role —
 * no token handling. Everything else (project lifecycle, interactive shells) targets plain {@code
 * host}, because those run with host privileges the {@code sail} user must never have. SSH handles
 * transport, auth, and TTY allocation; output passes through — the remote host renders ANSI colors
 * and the local terminal displays them.
 */
public final class RemoteCommandRunner {

  private static final Set<String> LOCAL_COMMANDS =
      Set.of("--version", "-V", "upgrade", "init", "client", "login", "enroll");
  private static final Set<String> MACHINE_VERBS = Set.of("_pty-child", "_pty-selftest");
  private static final List<String> LOCAL_TERMINAL_HOST = List.of("_pty-host", "--local");
  private static final Set<String> INTERACTIVE_COMMANDS = Set.of("shell", "exec");
  private static final List<String> LOGIN = List.of("agent", "login");
  private static final Set<String> LOGIN_VERBS = Set.of("list", "use", "remove");
  private static final Set<String> LOGIN_NO_VALUE = Set.of("--help", "-h", "--dry-run");
  private static final Set<String> LOGIN_VALUED_OPTIONS = Set.of("--name", "--kind", "--fde");
  private static final Set<String> HOST_ONLY_COMMANDS = Set.of("host");
  private static final Set<String> GATEWAY_COMMANDS =
      Stream.concat(SshGateway.API_COMMANDS.stream(), SshGateway.ADMIN_COMMANDS.stream())
          .collect(Collectors.toUnmodifiableSet());
  private static final int SSH_CONNECTION_FAILURE = 255;

  private final ClientConfig config;

  public RemoteCommandRunner(ClientConfig config) {
    this.config = config;
  }

  /**
   * Executes a SAIL command. Returns the process exit code.
   *
   * <p>Routes commands into three categories:
   *
   * <ul>
   *   <li>Local: {@code --version}, {@code upgrade}, {@code init}, {@code client}, {@code login},
   *       {@code enroll}, {@code _pty-host --local}, {@code _pty-child}, {@code _pty-selftest} —
   *       run on the client, not forwarded ({@code login} and {@code enroll} drive the client's
   *       browser, a loopback callback, and their own SSH tunnel; {@code client} writes this
   *       machine's client config; the terminal host of this machine, the shim it spawns every
   *       session under, and the binary's self-test have nothing to do on a server)
   *   <li>Host-only: {@code host init}, {@code host config} — error with guidance
   *   <li>Everything else: forwarded to the remote host via SSH, as {@code sail@host} when the FDE
   *       gateway accepts the command and as the plain host otherwise
   * </ul>
   */
  public int execute(String[] args) {
    if (args.length == 0) {
      return executeRemote(args, false);
    }

    var command = args[0];

    if (isLocalCommand(args)) {
      return executeLocal(args);
    }

    if (HOST_ONLY_COMMANDS.contains(command)) {
      System.err.println(
          Banner.errorLine(
              "The '"
                  + command
                  + "' command manages the host server directly."
                  + "\n  SSH to "
                  + config.host()
                  + " and run it there.",
              Ansi.AUTO));
      return 1;
    }

    return executeRemote(args, interactive(args));
  }

  private int executeLocal(String[] args) {
    try {
      return SailCommandLine.of(new Sail())
          .setExecutionExceptionHandler((ex, line, parsed) -> Main.report(ex, line))
          .execute(args);
    } catch (Exception e) {
      System.err.println(
          Banner.errorLine("Failed to run local command: " + e.getMessage(), Ansi.AUTO));
      return 1;
    }
  }

  int executeRemote(String[] args, boolean interactive) {
    var target = sshTarget(args);
    try {
      var cmd = buildSshCommand(args, interactive);
      var pb = new ProcessBuilder(cmd);
      pb.inheritIO();
      var exitCode = pb.start().waitFor();
      if (exitCode == SSH_CONNECTION_FAILURE) {
        System.err.println(Banner.errorLine(connectionFailureMessage(target), Ansi.AUTO));
      }
      return exitCode;
    } catch (IOException e) {
      System.err.println(
          Banner.errorLine("SSH not found. Install OpenSSH: brew install openssh", Ansi.AUTO));
      return 1;
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return 130;
    }
  }

  String connectionFailureMessage(String target) {
    var message =
        "Cannot connect to "
            + target
            + "."
            + "\n  Check that the host is reachable and your SSH key is configured."
            + "\n  Config: "
            + SailPaths.clientConfigPath();
    if (target.equals(config.host())) {
      return message;
    }
    return message
        + "\n  Commands authenticate as your FDE through the '"
        + config.user()
        + "' user. If access was denied:"
        + "\n    - not registered yet? an admin runs: "
        + OperatorCommand.SUDO.sail("fde add <handle> --key \"<your pubkey>\"")
        + "\n    - registered before the host ran 0.13.53+? upgrade the host, or run: "
        + OperatorCommand.SUDO.sail("host keys sync");
  }

  List<String> buildSshCommand(String[] args, boolean interactive) {
    var cmd = new ArrayList<String>();
    cmd.add("ssh");
    if (interactive) {
      cmd.add("-t");
    }
    if (gatewayLane(args)) {
      cmd.add("-o");
      cmd.add("PasswordAuthentication=no");
      cmd.add("-o");
      cmd.add("KbdInteractiveAuthentication=no");
    }
    cmd.add(sshTarget(args));
    cmd.add("sail");
    cmd.addAll(List.of(args));
    return List.copyOf(cmd);
  }

  String sshTarget(String[] args) {
    return gatewayLane(args) ? config.gatewayTarget() : config.host();
  }

  /**
   * Gateway identity is the SSH key by definition, so the gateway lane forbids password fallback —
   * a missing key fails fast with guidance instead of dangling a password prompt for a locked
   * system account.
   */
  private boolean gatewayLane(String[] args) {
    return config.gatewayEnabled() && args.length > 0 && GATEWAY_COMMANDS.contains(args[0]);
  }

  /**
   * Whether {@code args} need the box's terminal: an interactive shell, and storing a login, which
   * reads its value there with echo off rather than echoing it on this machine's terminal.
   */
  static boolean interactive(String... args) {
    return INTERACTIVE_COMMANDS.contains(args[0]) || storesALogin(args);
  }

  /**
   * Whether {@code args} store a login: {@code agent login} whose first positional word, past its
   * options and their values, is not one of its verbs, and that asks for neither its help nor a dry
   * run, so its value is never typed on this machine's echoing terminal. A login named {@code use}
   * is a name, not the verb.
   */
  private static boolean storesALogin(String... args) {
    if (args.length < 3 || !List.of(args).subList(0, 2).equals(LOGIN)) {
      return false;
    }
    var rest = List.of(args).subList(2, args.length);
    if (rest.stream().anyMatch(LOGIN_NO_VALUE::contains)) {
      return false;
    }
    for (var i = 0; i < rest.size(); i++) {
      var word = rest.get(i);
      if (LOGIN_VALUED_OPTIONS.contains(word)) {
        i++;
      } else if (!word.startsWith("-")) {
        return !LOGIN_VERBS.contains(word);
      }
    }
    return false;
  }

  /** Whether {@code args} run on this machine, never forwarded to the server. */
  static boolean isLocalCommand(String... args) {
    return LOCAL_COMMANDS.contains(args[0]) || runsOnThisMachine(args);
  }

  /**
   * Whether {@code args} name what only this machine can run, whoever else holds its state: its own
   * terminal host ({@code _pty-host --local}), the shim that host spawns every session under and
   * the binary's self-test.
   */
  public static boolean runsOnThisMachine(String... args) {
    return args.length > 0
        && (MACHINE_VERBS.contains(args[0]) || List.of(args).equals(LOCAL_TERMINAL_HOST));
  }

  /** Returns the classification of a command for testing. */
  static boolean isHostOnlyCommand(String command) {
    return HOST_ONLY_COMMANDS.contains(command);
  }

  /** Returns the classification of a command for testing. */
  static boolean isInteractiveCommand(String command) {
    return INTERACTIVE_COMMANDS.contains(command);
  }
}
