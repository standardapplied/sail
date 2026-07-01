/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.commands;

import ai.singlr.sail.config.HostYaml;
import ai.singlr.sail.config.YamlUtil;
import ai.singlr.sail.engine.ContainerManager;
import ai.singlr.sail.engine.ContainerState;
import ai.singlr.sail.engine.NameValidator;
import ai.singlr.sail.engine.ProjectDefinitions;
import ai.singlr.sail.engine.SailPaths;
import ai.singlr.sail.engine.ShellExecutor;
import ai.singlr.sail.engine.WorkstationIdentity;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import picocli.CommandLine.Command;
import picocli.CommandLine.Help.Ansi;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Spec;

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

  @Spec private CommandSpec spec;

  @Override
  public void run() {
    CliCommand.run(spec, this::execute);
  }

  private void execute() throws Exception {
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
      throw new IllegalStateException(
          "Server IP not configured. Fix with one of:\n"
              + "  - sudo sail host config set server-ip <your-server-ip>\n"
              + "  - Or pass: sail project connect "
              + name
              + " --server-ip <your-server-ip>");
    }

    var state = mgr.queryState(name);
    var containerIp =
        switch (state) {
          case ContainerState.Running r -> r.ipv4();
          case ContainerState.Stopped ignored ->
              throw new IllegalStateException(
                  "Project '" + name + "' is stopped. Start it with: sail project start " + name);
          case ContainerState.NotCreated ignored ->
              throw new IllegalStateException(
                  "Project '"
                      + name
                      + "' does not exist. Create it with: sail project create <name>");
          case ContainerState.Error e ->
              throw new IllegalStateException("Container error: " + e.message());
        };

    if (containerIp == null) {
      throw new IllegalStateException(
          "Project '"
              + name
              + "' is running but has no IP address yet. Wait a moment and try again.");
    }

    var resolvedServerUser =
        Objects.requireNonNullElse(serverUser, System.getProperty("user.name"));
    var containerUser = resolveContainerUser(name);

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
      System.out.println(
          Ansi.AUTO.string(
              "  @|yellow ⚠|@ No workstation key is registered on this box; the snippet below"
                  + " assumes ~/.ssh/id_ed25519. If the container rejects your key, register the"
                  + " one you connect with, then re-create the project:"));
      System.out.println(Ansi.AUTO.string("    @|bold " + WorkstationIdentity.SET_KEY_HINT + "|@"));
    }
    System.out.println(
        connectSnippet(
            resolvedServerIp, resolvedServerUser, name, containerIp, containerUser, identityFile));
  }

  /**
   * The container login user from the project definition (catalog-first), defaulting to {@code dev}
   * when the descriptor can't be read — a running container is reason enough to print a snippet, so
   * a missing descriptor degrades to the default rather than failing.
   */
  private static String resolveContainerUser(String name) {
    try {
      return ProjectDefinitions.load(name, null).sshUser();
    } catch (RuntimeException e) {
      return "dev";
    }
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
      String serverIp,
      String serverUser,
      String name,
      String containerIp,
      String containerUser,
      String identityFile) {
    return """
        # Add to ~/.ssh/config on your Mac:

        Host singular-server
            HostName %s
            User %s
            IdentityFile %s

        Host %s
            HostName %s
            User %s
            ProxyJump singular-server
            IdentityFile %s

        # Then connect:
        #   ssh %s
        #   zed ssh://%s@%s/home/%s/workspace
        #
        # Agent auth (port forwarding for subscription login):
        #   ssh -N -L 3000:localhost:3000 %s"""
        .formatted(
            serverIp,
            serverUser,
            identityFile,
            name,
            containerIp,
            containerUser,
            identityFile,
            name,
            containerUser,
            name,
            containerUser,
            name);
  }
}
