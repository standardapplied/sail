/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.engine;

import ai.singlr.sail.Sail;
import ai.singlr.sail.config.ClientConfig;
import ai.singlr.sail.ssh.SshGateway;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import picocli.CommandLine;
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
      Set.of("--version", "-V", "upgrade", "init", "login");
  private static final Set<String> INTERACTIVE_COMMANDS = Set.of("shell", "exec");
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
   *   <li>Local: {@code --version}, {@code upgrade}, {@code init}, {@code login} — run on the
   *       client, not forwarded ({@code login} drives the client's browser and a loopback callback)
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

    if (LOCAL_COMMANDS.contains(command)) {
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

    var interactive = INTERACTIVE_COMMANDS.contains(command);
    return executeRemote(args, interactive);
  }

  private int executeLocal(String[] args) {
    try {
      return new CommandLine(new Sail()).execute(args);
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

  private String connectionFailureMessage(String target) {
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
        + "' user. If access was denied, ask an admin to register your key:"
        + "\n    sail fde key add <your-handle> \"<your pubkey>\"";
  }

  List<String> buildSshCommand(String[] args, boolean interactive) {
    var cmd = new ArrayList<String>();
    cmd.add("ssh");
    if (interactive) {
      cmd.add("-t");
    }
    cmd.add(sshTarget(args));
    cmd.add("sail");
    cmd.addAll(List.of(args));
    return List.copyOf(cmd);
  }

  String sshTarget(String[] args) {
    var gateway = config.gatewayEnabled() && args.length > 0 && GATEWAY_COMMANDS.contains(args[0]);
    return gateway ? config.gatewayTarget() : config.host();
  }

  /** Returns the classification of a command for testing. */
  static boolean isLocalCommand(String command) {
    return LOCAL_COMMANDS.contains(command);
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
