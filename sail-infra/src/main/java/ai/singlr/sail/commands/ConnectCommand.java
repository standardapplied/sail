/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.commands;

import ai.singlr.sail.config.HostYaml;
import ai.singlr.sail.config.YamlUtil;
import ai.singlr.sail.engine.Banner;
import ai.singlr.sail.engine.ContainerManager;
import ai.singlr.sail.engine.ContainerState;
import ai.singlr.sail.engine.ContainerStateGuard;
import ai.singlr.sail.engine.HostInfo;
import ai.singlr.sail.engine.NameValidator;
import ai.singlr.sail.engine.OperatorCommand;
import ai.singlr.sail.engine.ProjectDefinitions;
import ai.singlr.sail.engine.SailPaths;
import ai.singlr.sail.engine.ShellExecutor;
import ai.singlr.sail.engine.WorkstationIdentity;
import ai.singlr.sail.harness.Harness;
import ai.singlr.sail.harness.Harnesses;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalInt;
import java.util.stream.Collectors;
import picocli.CommandLine.Command;
import picocli.CommandLine.Help.Ansi;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

@Command(
    name = "connect",
    description = "Print SSH config snippet for connecting to a project container from your Mac.",
    mixinStandardHelpOptions = true)
public final class ConnectCommand implements Runnable {

  @Parameters(index = "0", description = "Project name.")
  private String name;

  @Option(
      names = "--server-ip",
      description = "Override server IP (instead of reading from host.yaml).")
  private String serverIp;

  @Option(names = "--server-user", description = "Server SSH user (default: current system user).")
  private String serverUser;

  @Option(names = "--json", description = "Output in JSON format.")
  private boolean json;

  @Override
  public void run() {
    CliCommand.run(this::execute);
  }

  private void execute() throws Exception {
    var operator = OperatorCommand.here();
    NameValidator.requireValidProjectName(name);
    var shell = new ShellExecutor(false);
    var mgr = new ContainerManager(shell);

    var hostYamlPath = SailPaths.hostConfigPath();
    var resolvedServerIp = serverIp;
    if (resolvedServerIp == null) {
      var hostMap = YamlUtil.parseFile(hostYamlPath);
      var hostYaml = HostYaml.fromMap(hostMap);
      resolvedServerIp = hostYaml.serverIp();
    }
    if (resolvedServerIp == null) {
      throw new IllegalStateException(serverIpUnset(name, operator));
    }

    var state = mgr.queryState(name);
    ContainerStateGuard.requireRunning(state, name, operator);
    var containerIp = state instanceof ContainerState.Running running ? running.ipv4() : null;

    if (containerIp == null) {
      throw new IllegalStateException(
          "Project '"
              + name
              + "' is running but has no IP address yet. Wait a moment and try again.");
    }

    var resolvedServerUser =
        Objects.requireNonNullElse(serverUser, System.getProperty("user.name"));
    var containerUser = resolveContainerUser(name, operator);

    var identityFile = WorkstationIdentity.identityFile();

    if (json) {
      System.out.println(
          YamlUtil.dumpJson(
              connectJson(
                  name,
                  resolvedServerIp,
                  resolvedServerUser,
                  containerIp,
                  containerUser,
                  identityFile,
                  WorkstationIdentity.registered().isPresent())));
      return;
    }

    if (WorkstationIdentity.registered().isEmpty()) {
      System.out.println(Ansi.AUTO.string(noWorkstationKey(name, operator)));
    }
    System.out.println(
        connectSnippet(
            HostInfo.hostname(),
            resolvedServerIp,
            resolvedServerUser,
            name,
            containerIp,
            containerUser,
            identityFile));
  }

  /**
   * The container login user from the project definition (catalog-first), defaulting to {@code dev}
   * when the descriptor can't be read — a running container is reason enough to print a snippet, so
   * a missing descriptor degrades to the default rather than failing.
   */
  private static String resolveContainerUser(String name, OperatorCommand operator) {
    try {
      return ProjectDefinitions.load(name, null, operator).sshUser();
    } catch (RuntimeException e) {
      return "dev";
    }
  }

  /**
   * Connect's warning on a box with no workstation key: the next apply puts the key it registers
   * into the container.
   */
  static String noWorkstationKey(String name, OperatorCommand operator) {
    return "  @|yellow ⚠|@ No workstation key is registered on this box; the snippet below assumes"
        + " ~/.ssh/id_ed25519. If the container rejects your key, register the one you connect"
        + " with, then apply the project to put it in the container:\n    @|bold "
        + WorkstationIdentity.setKeyHint(operator)
        + "|@\n    @|bold "
        + operator.sail("project apply " + name)
        + "|@";
  }

  /** Connect's refusal on a box with no server IP, naming both ways to give it one. */
  static String serverIpUnset(String name, OperatorCommand operator) {
    return "Server IP not configured. Fix with one of:\n"
        + "  - "
        + operator.sail("host config set server-ip <your-server-ip>")
        + "\n"
        + "  - Or pass: "
        + operator.sail("project connect " + name + " --server-ip <your-server-ip>");
  }

  static Map<String, Object> connectJson(
      String name,
      String serverIp,
      String serverUser,
      String containerIp,
      String containerUser,
      String identityFile,
      boolean workstationKeySet) {
    var map = new LinkedHashMap<String, Object>();
    map.put("project", name);
    map.put("server_ip", serverIp);
    map.put("server_user", serverUser);
    map.put("container_ip", containerIp);
    map.put("container_user", containerUser);
    map.put("identity_file", identityFile);
    map.put("workstation_key_set", workstationKeySet);
    return map;
  }

  static String connectSnippet(
      String boxHost,
      String serverIp,
      String serverUser,
      String name,
      String containerIp,
      String containerUser,
      String identityFile) {
    var alias = Banner.projectSshAlias(name, boxHost);
    var snippet =
        """
        # Add to ~/.ssh/config on your Mac:

        Host %s
            HostName %s
            User %s
            IdentityFile %s

        Host %s
            HostName %s
            User %s
            ProxyJump %s
            IdentityFile %s

        # Then connect:
        #   ssh %s
        #   zed ssh://%s@%s/home/%s/workspace"""
            .formatted(
                boxHost,
                serverIp,
                serverUser,
                identityFile,
                alias,
                containerIp,
                containerUser,
                boxHost,
                identityFile,
                alias,
                containerUser,
                alias,
                containerUser);
    return snippet + agentAuth(Harnesses.all(), alias);
  }

  /**
   * How to forward the port each of {@code harnesses} logs in through, once per port, and nothing
   * for one that needs none. A box can run any harness sail knows, so the snippet names the port of
   * each that needs one.
   */
  static String agentAuth(List<Harness> harnesses, String name) {
    return harnesses.stream()
        .map(Harness::loginTunnelPort)
        .flatMapToInt(OptionalInt::stream)
        .distinct()
        .mapToObj(
            port ->
                "\n#\n# Agent auth (port forwarding for subscription login):\n#   ssh -N -L "
                    + port
                    + ":localhost:"
                    + port
                    + " "
                    + name)
        .collect(Collectors.joining());
  }
}
