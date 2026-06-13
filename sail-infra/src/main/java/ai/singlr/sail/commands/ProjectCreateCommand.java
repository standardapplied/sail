/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.commands;

import ai.singlr.sail.config.HostYaml;
import ai.singlr.sail.config.SailYaml;
import ai.singlr.sail.config.YamlUtil;
import ai.singlr.sail.engine.Banner;
import ai.singlr.sail.engine.ContainerExec;
import ai.singlr.sail.engine.ContainerManager;
import ai.singlr.sail.engine.ContainerState;
import ai.singlr.sail.engine.GitCredentials;
import ai.singlr.sail.engine.NameValidator;
import ai.singlr.sail.engine.ProjectCatalog;
import ai.singlr.sail.engine.ProjectPhase;
import ai.singlr.sail.engine.ProjectProvisioner;
import ai.singlr.sail.engine.ProvisionListener;
import ai.singlr.sail.engine.ProvisionTracker;
import ai.singlr.sail.engine.SailPaths;
import ai.singlr.sail.engine.ShellExecutor;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import picocli.CommandLine.Command;
import picocli.CommandLine.Help.Ansi;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Spec;

@Command(
    name = "create",
    description = "Create a project environment from sail.yaml.",
    mixinStandardHelpOptions = true)
public final class ProjectCreateCommand implements Runnable {

  @Parameters(
      index = "0",
      arity = "0..1",
      description = "Project name (uses ~/.sail/projects/<name>/sail.yaml if -f not given).")
  private String name;

  @Option(names = "--dry-run", description = "Print commands instead of executing them.")
  private boolean dryRun;

  @Option(
      names = {"-f", "--file"},
      description = "Path to sail.yaml project descriptor.")
  private String file;

  @Option(names = "--json", description = "Output in JSON format.")
  private boolean json;

  @Option(names = "--yes", description = "Skip confirmation prompts (for non-interactive use).")
  private boolean yes;

  @Option(names = "--git-token", description = "Access token for cloning private repos over HTTPS.")
  private String gitToken;

  @Spec private CommandSpec spec;

  @Override
  public void run() {
    CliCommand.run(
        spec,
        "  If this is unexpected, re-run with --dry-run to see what would execute.",
        this::execute);
  }

  private void execute() throws Exception {
    if (!json) {
      Banner.printBranding(System.out, Ansi.AUTO);
    }

    if (!dryRun && !ConsoleHelper.isRoot()) {
      throw new IllegalStateException(
          "Root privileges required. Run with: sudo sail project create");
    }

    var singYamlPath = resolveSailYamlPath();
    if (!Files.exists(singYamlPath)) {
      var hint = new StringBuilder();
      hint.append("Project descriptor not found: ").append(singYamlPath.toAbsolutePath());
      if (name != null) {
        hint.append("\n  Run 'sail project init' to create ")
            .append(defaultDescriptorPath(name))
            .append(", or specify one with --file.");
      } else {
        hint.append(
            "\n  Run 'sail project init' to generate a sail.yaml, or specify one with --file.");
      }
      throw new IllegalStateException(hint.toString());
    }

    SailYaml config = SailYaml.fromMap(YamlUtil.parseFile(singYamlPath));

    if (config.name() == null || config.name().isBlank()) {
      throw new IllegalStateException("sail.yaml must have a 'name' field.");
    }
    NameValidator.requireValidProjectName(config.name());
    if (config.resources() == null) {
      throw new IllegalStateException(
          "sail.yaml must have a 'resources' section with cpu, memory, and disk.");
    }

    if (yes || json) {
      config = resolveNodeDependencyAuto(config);
    } else if (!dryRun) {
      config = resolveNodeDependencyInteractive(config);
    }

    var projectDir = SailPaths.projectDir(config.name());
    Files.createDirectories(projectDir);
    var canonicalYaml = projectDir.resolve(SailPaths.PROJECT_DESCRIPTOR);
    syncProjectBundle(singYamlPath, canonicalYaml);
    if (!dryRun) {
      ProjectCatalog.record(config.name(), Files.readString(canonicalYaml), null);
    }

    var hostYamlPath = SailPaths.hostConfigPath();
    if (!Files.exists(hostYamlPath)) {
      throw new IllegalStateException("Server not initialized. Run 'sail host init' first.");
    }
    var hostYaml = HostYaml.fromMap(YamlUtil.parseFile(hostYamlPath));

    var shell = new ShellExecutor(dryRun);

    var stateFile = SailPaths.provisionState(config.name());
    var tracker = new ProvisionTracker<>(ProjectPhase.class, stateFile, dryRun);
    tracker.load();

    if (tracker.hasIncompleteRun()) {
      var mgr = new ContainerManager(shell);
      var containerState = mgr.queryState(config.name());
      if (containerState instanceof ContainerState.NotCreated) {
        if (!json) {
          System.out.println(
              Ansi.AUTO.string(
                  "  @|faint Stale state detected — container '"
                      + config.name()
                      + "' no longer exists. Starting fresh.|@"));
        }
        tracker.reset();
      }
    }

    if (tracker.hasIncompleteRun()) {
      if (!json) {
        Banner.printResumeInfo(tracker.currentState(), System.out, Ansi.AUTO);
      }
      if (!yes && !json && !ConsoleHelper.confirm("Resume provisioning?")) {
        System.out.println("  Aborted.");
        return;
      }
    }

    if (!json) {
      Banner.printProjectSummary(config, System.out, Ansi.AUTO);
    }

    if (!yes && !dryRun && !json && !tracker.hasIncompleteRun()) {
      if (!ConsoleHelper.confirm("Create project " + config.name() + "?")) {
        System.out.println("  Aborted.");
        return;
      }
    }

    var gitTokens = resolveGitTokens(config);

    var listener = json ? ProvisionListener.NOOP : ConsoleProvisionListener.INSTANCE;
    var provisioner = new ProjectProvisioner(shell, tracker, listener);
    provisioner.provision(config, hostYaml, gitTokens, singYamlPath);

    if (json) {
      var map = new LinkedHashMap<String, Object>();
      map.put("name", config.name());
      map.put("status", "created");
      System.out.println(YamlUtil.dumpJson(map));
      return;
    }

    System.out.println();
    Banner.printProjectCreated(
        config.name(), config.ssh() != null ? config.ssh().user() : null, System.out, Ansi.AUTO);

    var mgr = new ContainerManager(shell);
    var state = mgr.queryState(config.name());
    if (state instanceof ContainerState.Running r && r.ipv4() != null) {
      var serverIp = hostYaml.serverIp();
      var serverUser = System.getProperty("user.name");
      if (serverIp != null) {
        Banner.printSshConfig(config.name(), serverIp, serverUser, r.ipv4(), System.out, Ansi.AUTO);
      } else {
        System.out.println();
        System.out.println(
            Ansi.AUTO.string(
                "    @|yellow Server IP not configured.|@"
                    + " Run: sudo sail host config set server-ip <your-server-ip>"));
        System.out.println(
            Ansi.AUTO.string(
                "    Then: sail project connect " + config.name() + " for SSH config snippet."));
      }

      var ports = ContainerExec.queryServicePorts(shell, config.name());
      if (!ports.isEmpty()) {
        Banner.printSshTunnels(
            config.name(),
            config.ssh() != null ? config.ssh().user() : null,
            ports,
            System.out,
            Ansi.AUTO);
      }
    }
  }

  private Map<String, String> resolveGitTokens(SailYaml config) {
    if (config.git() == null || !"token".equals(config.git().auth())) {
      return Map.of();
    }

    if (gitToken != null && !gitToken.isBlank()) {
      return GitCredentials.singleTokenMap(gitToken);
    }

    var hosts = GitCredentials.extractHttpsHosts(config.repos());
    if (hosts.isEmpty()) {
      return Map.of();
    }

    var tokens = new LinkedHashMap<String, String>();
    for (var host : hosts) {
      var envToken = GitCredentials.resolveTokenForHost(host, null);
      if (envToken != null && !envToken.isBlank()) {
        tokens.put(host, envToken);
      }
    }

    var missingHosts = hosts.stream().filter(h -> !tokens.containsKey(h)).toList();
    if (missingHosts.isEmpty() || yes || json || dryRun) {
      return Map.copyOf(tokens);
    }

    for (var host : missingHosts) {
      try {
        var prompted =
            ConsoleHelper.readPassword("  Git access token for " + host + " (blank to skip): ");
        if (prompted != null && !prompted.isBlank()) {
          tokens.put(host, prompted);
        }
      } catch (EchoDisabledUnavailableException e) {
        throw new IllegalArgumentException(
            "Unable to read git access token interactively in this terminal.\n\n"
                + "Provide the token via one of:\n"
                + "  --git-token <token>\n"
                + "  GITHUB_TOKEN environment variable\n\n"
                + "Then re-run: sail project create <name>");
      }
    }

    return Map.copyOf(tokens);
  }

  private SailYaml resolveNodeDependencyAuto(SailYaml config) {
    var resolution = NodeDependencyCheck.resolve(config, true);
    return switch (resolution) {
      case NodeDependencyCheck.Resolution.NodeAdded r -> r.config();
      default -> config;
    };
  }

  private SailYaml resolveNodeDependencyInteractive(SailYaml config) {
    var resolution = NodeDependencyCheck.resolve(config, false);
    return switch (resolution) {
      case NodeDependencyCheck.Resolution.Unchanged r -> r.config();
      case NodeDependencyCheck.Resolution.NodeAdded r -> r.config();
      case NodeDependencyCheck.Resolution.AgentsDropped r -> r.config();
      case NodeDependencyCheck.Resolution.Aborted ignored -> {
        System.out.println("  Aborted.");
        throw new IllegalStateException(
            "Aborted: Node-dependent agents require Node.js in the project runtimes.");
      }
    };
  }

  private Path resolveSailYamlPath() {
    return resolveSailYamlPath(name, file);
  }

  static Path defaultDescriptorPath(String name) {
    return SailPaths.projectDir(name).resolve(SailPaths.PROJECT_DESCRIPTOR);
  }

  static Path resolveSailYamlPath(String name, String file) {
    if (file != null) {
      return Path.of(file);
    }
    if (name != null) {
      var canonicalPath = defaultDescriptorPath(name);
      if (Files.exists(canonicalPath)) {
        return canonicalPath;
      }
      var namedPath = Path.of(name, SailPaths.PROJECT_DESCRIPTOR);
      if (Files.exists(namedPath)) {
        return namedPath;
      }
    }
    var cwdPath = Path.of(SailPaths.PROJECT_DESCRIPTOR);
    if (Files.exists(cwdPath)) {
      return cwdPath;
    }
    if (name != null) {
      return defaultDescriptorPath(name);
    }
    return cwdPath;
  }

  static void syncProjectBundle(Path sourceSailYamlPath, Path canonicalYamlPath) throws Exception {
    var sourceYaml = sourceSailYamlPath.toAbsolutePath().normalize();
    var targetYaml = canonicalYamlPath.toAbsolutePath().normalize();
    if (targetYaml.getParent() != null) {
      Files.createDirectories(targetYaml.getParent());
    }
    if (!sourceYaml.equals(targetYaml)) {
      Files.copy(
          sourceYaml,
          targetYaml,
          java.nio.file.StandardCopyOption.REPLACE_EXISTING,
          java.nio.file.StandardCopyOption.COPY_ATTRIBUTES);
    }
    syncFilesDirectory(
        sourceYaml.getParent().resolve("files"), targetYaml.getParent().resolve("files"));
  }

  private static void syncFilesDirectory(Path sourceDir, Path targetDir) throws Exception {
    var source = sourceDir.toAbsolutePath().normalize();
    var target = targetDir.toAbsolutePath().normalize();
    if (source.equals(target)) {
      return;
    }
    if (!Files.isDirectory(source)) {
      deleteDirectory(target);
      return;
    }
    deleteDirectory(target);
    try (var walk = Files.walk(source)) {
      for (var path : walk.toList()) {
        var relative = source.relativize(path);
        var destination = target.resolve(relative);
        if (Files.isDirectory(path)) {
          Files.createDirectories(destination);
        } else {
          if (destination.getParent() != null) {
            Files.createDirectories(destination.getParent());
          }
          Files.copy(
              path,
              destination,
              java.nio.file.StandardCopyOption.REPLACE_EXISTING,
              java.nio.file.StandardCopyOption.COPY_ATTRIBUTES);
        }
      }
    }
  }

  private static void deleteDirectory(Path dir) throws Exception {
    if (!Files.exists(dir)) {
      return;
    }
    try (var walk = Files.walk(dir)) {
      for (var path : walk.sorted(java.util.Comparator.reverseOrder()).toList()) {
        Files.deleteIfExists(path);
      }
    }
  }
}
