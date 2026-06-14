/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.commands;

import ai.singlr.sail.common.Strings;
import ai.singlr.sail.config.HostYaml;
import ai.singlr.sail.config.SailYaml;
import ai.singlr.sail.config.YamlUtil;
import ai.singlr.sail.engine.Banner;
import ai.singlr.sail.engine.ContainerExec;
import ai.singlr.sail.engine.ContainerManager;
import ai.singlr.sail.engine.ContainerState;
import ai.singlr.sail.engine.GitCredentials;
import ai.singlr.sail.engine.GitHubFetcher;
import ai.singlr.sail.engine.ProjectPhase;
import ai.singlr.sail.engine.ProjectProvisioner;
import ai.singlr.sail.engine.ProvisionListener;
import ai.singlr.sail.engine.ProvisionTracker;
import ai.singlr.sail.engine.SailPaths;
import ai.singlr.sail.engine.ShellExecutor;
import ai.singlr.sail.engine.WorkspaceFiles;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import picocli.CommandLine.Command;
import picocli.CommandLine.Help.Ansi;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Spec;

@Command(
    name = "demo",
    description = "Set up a demo project with zero configuration.",
    mixinStandardHelpOptions = true)
public final class ProjectDemoCommand implements Runnable {

  static final String DEMO_REPO = "singlr-ai/sing-demo";
  static final String DEMO_PROJECT = "demo";
  static final String DEMO_REF = "main";

  @Option(names = "--dry-run", description = "Print commands instead of executing them.")
  private boolean dryRun;

  @Option(names = "--json", description = "Output in JSON format.")
  private boolean json;

  @Option(names = "--git-token", description = "Access token for cloning private repos over HTTPS.")
  private String gitToken;

  @Spec private CommandSpec spec;

  @Override
  public void run() {
    CliCommand.run(spec, this::execute);
  }

  private void execute() throws Exception {
    var ansi = Ansi.AUTO;
    var out = System.out;

    if (!dryRun && !ConsoleHelper.isRoot()) {
      throw new IllegalStateException("Root privileges required. Run with: sudo sail project demo");
    }

    var hostYamlPath = SailPaths.hostConfigPath();
    if (!dryRun && !Files.exists(hostYamlPath)) {
      throw new IllegalStateException("Server not initialized. Run 'sail host init' first.");
    }

    if (!dryRun) {
      var shell = new ShellExecutor(false);
      var mgr = new ContainerManager(shell);
      var state = mgr.queryState(DEMO_PROJECT);
      if (state instanceof ContainerState.Running) {
        throw new IllegalStateException(
            "Demo project is already running."
                + "\n  Get a shell:    sudo sail project shell "
                + DEMO_PROJECT
                + "\n  Destroy first:  sudo sail project destroy "
                + DEMO_PROJECT);
      }
      if (state instanceof ContainerState.Stopped) {
        throw new IllegalStateException(
            "Demo project exists but is stopped."
                + "\n  Start it:       sudo sail project start "
                + DEMO_PROJECT
                + "\n  Destroy first:  sudo sail project destroy "
                + DEMO_PROJECT);
      }
    }

    if (!json) {
      Banner.printBranding(out, ansi);
      out.println();
      out.println(ansi.string("  @|bold Setting up demo project...|@"));
      out.println();
    }

    var yamlContent = fetchDemoYaml(out, ansi);
    var resolvedYaml = resolveAutoDetected(yamlContent, out, ansi);

    var outputDir = Path.of(DEMO_PROJECT);
    var singYamlPath = outputDir.resolve(SailPaths.PROJECT_DESCRIPTOR);
    Files.createDirectories(outputDir);
    Files.writeString(singYamlPath, resolvedYaml);
    if (!json) {
      out.println(ansi.string("  @|green \u2713|@ " + singYamlPath + " written"));
    }

    var filesPulled = fetchDemoFiles(outputDir, out, ansi);

    if (dryRun) {
      if (json) {
        var map = new LinkedHashMap<String, Object>();
        map.put("name", DEMO_PROJECT);
        map.put("repo", DEMO_REPO);
        map.put("output", singYamlPath.toAbsolutePath().toString());
        map.put("files_pulled", filesPulled);
        map.put("dry_run", true);
        out.println(YamlUtil.dumpJson(map));
      } else {
        out.println();
        out.println(ansi.string("  @|faint [dry-run] Would provision project 'demo'|@"));
      }
      return;
    }

    var config = SailYaml.fromMap(YamlUtil.parseMap(resolvedYaml));
    var hostYaml = HostYaml.fromMap(YamlUtil.parseFile(hostYamlPath));
    var shell = new ShellExecutor(false);

    if (!json) {
      out.println();
      Banner.printProjectSummary(config, out, ansi);
    }

    var stateFile = SailPaths.provisionState(config.name());
    var tracker = new ProvisionTracker<>(ProjectPhase.class, stateFile, false);
    tracker.load();

    var listener = json ? ProvisionListener.NOOP : ConsoleProvisionListener.INSTANCE;
    var provisioner = new ProjectProvisioner(shell, tracker, listener);
    provisioner.provision(config, hostYaml, GitCredentials.singleTokenMap(gitToken), singYamlPath);

    if (json) {
      var map = new LinkedHashMap<String, Object>();
      map.put("name", config.name());
      map.put("status", "created");
      map.put("files_pulled", filesPulled);
      out.println(YamlUtil.dumpJson(map));
      return;
    }

    out.println();
    Banner.printProjectCreated(
        config.name(), config.ssh() != null ? config.ssh().user() : null, out, ansi);

    var mgr = new ContainerManager(shell);
    var currentState = mgr.queryState(config.name());
    if (currentState instanceof ContainerState.Running r && r.ipv4() != null) {
      var serverIp = hostYaml.serverIp();
      var serverUser = System.getProperty("user.name");
      if (serverIp != null) {
        Banner.printSshConfig(config.name(), serverIp, serverUser, r.ipv4(), out, ansi);
      }

      var ports = ContainerExec.queryServicePorts(shell, config.name());
      if (!ports.isEmpty()) {
        Banner.printSshTunnels(
            config.name(), config.ssh() != null ? config.ssh().user() : null, ports, out, ansi);
      }
    }
  }

  private String fetchDemoYaml(PrintStream out, Ansi ansi) throws Exception {
    var projectPath = DEMO_PROJECT + "/" + SailPaths.PROJECT_DESCRIPTOR;
    if (!json) {
      out.println(ansi.string("  @|bold Fetching|@ " + projectPath + " from " + DEMO_REPO + "..."));
    }
    var content = GitHubFetcher.fetchRawFile(DEMO_REPO, projectPath, null, DEMO_REF);
    if (content == null) {
      throw new IllegalStateException(
          "Demo project not found at "
              + DEMO_REPO
              + "/"
              + projectPath
              + "\n  Check https://github.com/"
              + DEMO_REPO);
    }
    if (!json) {
      out.println(ansi.string("  @|green \u2713|@ " + projectPath + " loaded"));
    }
    return content;
  }

  private int fetchDemoFiles(Path outputDir, PrintStream out, Ansi ansi) throws Exception {
    var filesPath = DEMO_PROJECT + "/files";
    if (!json) {
      out.println(ansi.string("  @|bold Fetching|@ workspace files..."));
    }
    var entries = GitHubFetcher.fetchDirectoryTree(DEMO_REPO, filesPath, null, DEMO_REF);
    if (entries.isEmpty()) {
      if (!json) {
        out.println(ansi.string("  @|faint \u2192 No files/ directory found (skipped)|@"));
      }
      return 0;
    }
    var filesDir = outputDir.resolve("files");
    for (var entry : entries) {
      var localPath = filesDir.resolve(entry.relativePath());
      if (localPath.getParent() != null) {
        Files.createDirectories(localPath.getParent());
      }
      var content = GitHubFetcher.fetchRawFile(DEMO_REPO, entry.path(), null, DEMO_REF);
      if (content != null) {
        Files.writeString(localPath, content);
        WorkspaceFiles.setExecutableIfNeeded(localPath);
        if (!json) {
          out.println(ansi.string("  @|green \u2713|@ files/" + entry.relativePath()));
        }
      }
    }
    return entries.size();
  }

  private String resolveAutoDetected(String yamlContent, PrintStream out, Ansi ansi) {
    var gitName = detectGitConfig("user.name");
    var gitEmail = detectGitConfig("user.email");
    var sshKey = detectSshPublicKey();

    if (!json) {
      out.println();
      out.println(ansi.string("  @|bold Auto-detected configuration:|@"));
      out.println(
          ansi.string(
              "    Git name:  " + (gitName != null ? gitName : "@|yellow not configured|@")));
      out.println(
          ansi.string(
              "    Git email: " + (gitEmail != null ? gitEmail : "@|yellow not configured|@")));
      out.println(
          ansi.string(
              "    SSH key:   "
                  + (sshKey != null
                      ? "@|green found|@"
                      : "@|yellow not found \u2014 remote access won't work|@")));
      out.println();
    }

    if (gitName == null && !json) {
      out.print(ansi.string("  @|bold Git name (for commits)|@: "));
      out.flush();
      gitName = ConsoleHelper.readLine();
      if (Strings.isBlank(gitName)) {
        throw new IllegalArgumentException(
            "Git name is required."
                + "\n  Set it with: git config --global user.name \"Your Name\"");
      }
      gitName = gitName.strip();
    }
    if (gitEmail == null && !json) {
      out.print(ansi.string("  @|bold Git email|@: "));
      out.flush();
      gitEmail = ConsoleHelper.readLine();
      if (Strings.isBlank(gitEmail)) {
        throw new IllegalArgumentException(
            "Git email is required."
                + "\n  Set it with: git config --global user.email you@example.com");
      }
      gitEmail = gitEmail.strip();
    }

    if (gitName == null || gitEmail == null) {
      throw new IllegalArgumentException(
          "Git name and email are required."
              + "\n  Set them with:"
              + "\n    git config --global user.name \"Your Name\""
              + "\n    git config --global user.email you@example.com");
    }

    var resolved = yamlContent.replace("${GIT_NAME}", gitName).replace("${GIT_EMAIL}", gitEmail);

    if (sshKey != null) {
      resolved = resolved.replace("${SSH_PUBLIC_KEY}", sshKey);
    } else {
      resolved = resolved.replaceAll("\\s*- \\$\\{SSH_PUBLIC_KEY}", "");
    }

    return resolved;
  }

  String detectGitConfig(String key) {
    try {
      var sudoUser = System.getenv("SUDO_USER");
      var pb =
          Strings.isNotBlank(sudoUser)
              ? new ProcessBuilder("su", "-", sudoUser, "-c", "git config --global " + key)
              : new ProcessBuilder("git", "config", "--global", key);
      pb.redirectErrorStream(true);
      var process = pb.start();
      var output =
          new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).strip();
      var exitCode = process.waitFor();
      return exitCode == 0 && !output.isEmpty() ? output : null;
    } catch (Exception e) {
      return null;
    }
  }

  static String detectSshPublicKey() {
    var sudoUser = System.getenv("SUDO_USER");
    Path sshDir;
    if (Strings.isNotBlank(sudoUser)) {
      sshDir = Path.of("/home", sudoUser, ".ssh");
    } else {
      sshDir = Path.of(System.getProperty("user.home"), ".ssh");
    }

    for (var name : List.of("id_ed25519.pub", "id_rsa.pub", "id_ecdsa.pub")) {
      var keyFile = sshDir.resolve(name);
      if (Files.exists(keyFile)) {
        try {
          return Files.readString(keyFile).strip();
        } catch (IOException ignored) {
        }
      }
    }
    return null;
  }
}
