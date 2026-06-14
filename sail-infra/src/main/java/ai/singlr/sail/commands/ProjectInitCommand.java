/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.commands;

import ai.singlr.sail.common.Strings;
import ai.singlr.sail.config.SailYaml;
import ai.singlr.sail.config.YamlUtil;
import ai.singlr.sail.engine.Banner;
import ai.singlr.sail.engine.NameValidator;
import ai.singlr.sail.engine.SailPaths;
import ai.singlr.sail.gen.SailYamlGenerator;
import ai.singlr.sail.gen.ServicePresets;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import picocli.CommandLine.Command;
import picocli.CommandLine.Help.Ansi;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Spec;

@Command(
    name = "init",
    description = "Interactively generate a sail.yaml project descriptor.",
    mixinStandardHelpOptions = true)
public final class ProjectInitCommand implements Runnable {

  @Option(names = "--json", description = "Output generated config as JSON instead of writing.")
  private boolean json;

  @Option(
      names = {"-o", "--output"},
      description = "Output file path (default: ~/.sail/projects/<name>/sail.yaml).")
  private String output;

  @Spec private CommandSpec spec;

  @Override
  public void run() {
    CliCommand.run(spec, this::execute);
  }

  private void execute() throws Exception {
    var ansi = Ansi.AUTO;
    var out = System.out;

    if (!json) {
      Banner.printBranding(out, ansi);
      out.println();
      out.println(ansi.string("  @|bold Initializing sail.yaml|@"));
      out.println(ansi.string("  @|faint Press Enter to accept defaults shown in [brackets].|@"));
      out.println();
    }

    var config = collectInputs(out, ansi);

    if (json) {
      out.println(buildJsonOutput(config));
      return;
    }

    var outputPath = output != null ? Path.of(output) : defaultOutputPath(config.name());
    if (outputPath.getParent() != null) {
      Files.createDirectories(outputPath.getParent());
    }

    if (Files.exists(outputPath)) {
      if (!ConsoleHelper.confirm(outputPath + " already exists. Overwrite?")) {
        out.println("  Aborted.");
        return;
      }
    }

    var yamlContent = SailYamlGenerator.generate(config);
    Files.writeString(outputPath, yamlContent);

    out.println();
    out.println(ansi.string("  @|bold,green \u2713 Created|@ " + outputPath));
    out.println(
        ansi.string(
            "    @|faint Next:|@ review the file, then run @|bold "
                + nextCreateCommand(config.name(), outputPath)
                + "|@"));
  }

  static Path defaultOutputPath(String name) {
    return SailPaths.projectDir(name).resolve(SailPaths.PROJECT_DESCRIPTOR);
  }

  static String nextCreateCommand(String name, Path outputPath) {
    if (outputPath
        .toAbsolutePath()
        .normalize()
        .equals(defaultOutputPath(name).toAbsolutePath().normalize())) {
      return "sail project create " + name;
    }
    return "sail project create " + name + " -f " + outputPath;
  }

  private SailYaml collectInputs(PrintStream out, Ansi ansi) {
    var dirName = sanitizeProjectName(Path.of("").toAbsolutePath().getFileName().toString());
    var name = promptWithDefault(out, ansi, "Project name", dirName);
    NameValidator.requireValidProjectName(name);

    var description = promptWithDefault(out, ansi, "Description", "");
    if (description.isEmpty()) description = null;

    out.println();
    out.println(ansi.string("  @|bold Resources|@"));
    var cpu = promptInt(out, ansi, "CPU cores", 2);
    var memory = normalizeSize(promptWithDefault(out, ansi, "Memory", "8GB"));
    var disk = normalizeSize(promptWithDefault(out, ansi, "Disk", "50GB"));
    var resources = new SailYaml.Resources(cpu, memory, disk);

    out.println();
    out.println(ansi.string("  @|bold Runtimes|@"));
    var jdkStr = promptWithDefault(out, ansi, "JDK major version (blank to skip)", "25");
    int jdk;
    try {
      jdk = jdkStr.isEmpty() ? 0 : Integer.parseInt(jdkStr);
    } catch (NumberFormatException e) {
      jdk = 25;
    }
    var nodeStr = promptWithDefault(out, ansi, "Node.js version (blank to skip)", "");
    var maven = promptWithDefault(out, ansi, "Maven version (blank to skip)", "3.9.14");
    SailYaml.Runtimes runtimes = null;
    var nodeVersion = nodeStr.isEmpty() ? null : nodeStr;
    if (jdk > 0 || nodeVersion != null || !maven.isEmpty()) {
      runtimes = new SailYaml.Runtimes(jdk, nodeVersion, maven.isEmpty() ? null : maven);
    }

    out.println();
    SailYaml.Git git = null;
    if (ConsoleHelper.confirm("Configure git identity?")) {
      var gitName = promptRequired(out, ansi, "Git full name (for commits)");
      var gitEmail = promptRequired(out, ansi, "Git email");
      var authInput =
          promptWithDefault(out, ansi, "Auth method for cloning repos (token/ssh)", "token");
      var auth = "ssh".equalsIgnoreCase(authInput) ? "ssh" : "token";
      String sshKey = null;
      if ("ssh".equals(auth)) {
        sshKey =
            promptWithDefault(
                out,
                ansi,
                "Path to SSH private key for git (copied into container)",
                "~/.ssh/id_ed25519");
      }
      git = new SailYaml.Git(gitName, gitEmail, auth, sshKey);
    }

    out.println();
    List<SailYaml.Repo> repos = null;
    if (ConsoleHelper.confirmNo("Add repositories to clone?")) {
      repos = new ArrayList<>();
      do {
        var url = promptRequired(out, ansi, "Repo URL");
        var pathDefault = guessRepoPath(url);
        var path = promptWithDefault(out, ansi, "Local path", pathDefault);
        var branch = promptWithDefault(out, ansi, "Branch (blank for default)", "");
        repos.add(new SailYaml.Repo(url, path, branch.isEmpty() ? null : branch));
      } while (ConsoleHelper.confirmNo("Add another repository?"));
    }

    out.println();
    out.println(ansi.string("  @|bold Infrastructure services (Podman containers)|@"));
    Map<String, SailYaml.Service> services = new LinkedHashMap<>();
    for (var preset : ServicePresets.all()) {
      var ports =
          preset.service().ports().stream()
              .map(String::valueOf)
              .reduce((a, b) -> a + ", " + b)
              .orElse("");
      var label = preset.displayName() + ansi.string(" @|faint (port " + ports + ")|@") + "?";
      if (ConsoleHelper.confirmNo(label)) {
        var version = promptWithDefault(out, ansi, "  Version", preset.defaultVersion());
        var service = customizePresetEnv(preset.withVersion(version), out, ansi);
        services.put(preset.key(), service);
      }
    }
    if (ConsoleHelper.confirmNo("Add a custom service?")) {
      collectCustomServices(out, ansi, services);
    }
    if (services.isEmpty()) {
      services = null;
    }

    out.println();
    out.println(ansi.string("  @|bold SSH access|@"));
    var sshUser = promptWithDefault(out, ansi, "SSH user", "dev");
    List<String> authorizedKeys = null;
    if (ConsoleHelper.confirmNo("Add public key for remote login (SSH into container)?")) {
      authorizedKeys = new ArrayList<>();
      do {
        var key = promptRequired(out, ansi, "Public key (e.g. ssh-ed25519 AAAA...)");
        authorizedKeys.add(key);
      } while (ConsoleHelper.confirmNo("Add another key?"));
    }
    var ssh = new SailYaml.Ssh(sshUser, authorizedKeys);

    out.println();
    var agentType =
        promptWithDefault(out, ansi, "Agent type (claude-code/codex/helios/none)", "claude-code");
    SailYaml.Agent agent = null;
    if (!"none".equalsIgnoreCase(agentType)) {
      List<String> install = null;
      if (ConsoleHelper.confirmNo("Install additional agent CLIs?")) {
        install = new ArrayList<>();
        install.add(agentType);
        do {
          var cli = promptRequired(out, ansi, "Agent CLI name (claude-code/codex)");
          if (!cli.equals(agentType)) {
            install.add(cli);
          }
        } while (ConsoleHelper.confirmNo("Add another?"));
      }
      agent =
          new SailYaml.Agent(
              agentType, true, "agent/", true, install, null, null, null, null, null, null);
    }

    return new SailYaml(
        name,
        description,
        resources,
        "ubuntu/24.04",
        null,
        runtimes,
        git,
        repos,
        services,
        null,
        agent,
        null,
        ssh);
  }

  private static SailYaml.Service customizePresetEnv(
      SailYaml.Service service, PrintStream out, Ansi ansi) {
    var defaultEnv = service.environment();
    var env = new LinkedHashMap<String, String>();
    if (defaultEnv != null && !defaultEnv.isEmpty()) {
      for (var entry : defaultEnv.entrySet()) {
        var value = promptWithDefault(out, ansi, "  " + entry.getKey(), entry.getValue());
        if (!value.isEmpty()) {
          env.put(entry.getKey(), value);
        }
      }
    }
    if (ConsoleHelper.confirmNo("Add environment variables?")) {
      do {
        var kv = promptRequired(out, ansi, "Environment variable as KEY=VALUE");
        var eqIdx = kv.indexOf('=');
        if (eqIdx > 0) {
          env.put(kv.substring(0, eqIdx), kv.substring(eqIdx + 1));
        }
      } while (ConsoleHelper.confirmNo("Add another env var?"));
    }
    return new SailYaml.Service(
        service.image(),
        service.ports(),
        env.isEmpty() ? null : env,
        service.command(),
        service.volumes());
  }

  private static String promptWithDefault(PrintStream out, Ansi ansi, String label, String def) {
    if (!Strings.isEmpty(def)) {
      out.print(ansi.string("  @|bold " + label + "|@ @|faint [" + def + "]|@: "));
    } else {
      out.print(ansi.string("  @|bold " + label + "|@: "));
    }
    out.flush();
    var line = ConsoleHelper.readLine();
    if (Strings.isBlank(line)) {
      return Objects.requireNonNullElse(def, "");
    }
    return line.strip();
  }

  private static String promptRequired(PrintStream out, Ansi ansi, String label) {
    while (true) {
      out.print(ansi.string("  @|bold " + label + "|@: "));
      out.flush();
      var line = ConsoleHelper.readLine();
      if (Strings.isNotBlank(line)) {
        return line.strip();
      }
      out.println(ansi.string("    @|yellow Required field. Please enter a value.|@"));
    }
  }

  private static int promptInt(PrintStream out, Ansi ansi, String label, int def) {
    var raw = promptWithDefault(out, ansi, label, String.valueOf(def));
    try {
      return Integer.parseInt(raw);
    } catch (NumberFormatException e) {
      return def;
    }
  }

  /**
   * Normalizes a size value: bare numbers like "4" become "4GB", values with units like "4GB" or "4
   * GB" are normalized to remove spaces. Handles common suffixes (GB, MB, TB, GiB, MiB, TiB).
   */
  private static String normalizeSize(String value) {
    var trimmed = value.strip();
    if (trimmed.matches("^\\d+$")) {
      return trimmed + "GB";
    }
    return trimmed.replaceAll("\\s+", "");
  }

  private static String sanitizeProjectName(String dirName) {
    var sanitized =
        dirName
            .toLowerCase()
            .replaceAll("[^a-z0-9-]", "-")
            .replaceAll("-+", "-")
            .replaceAll("^-|-$", "");
    return sanitized.isEmpty() ? "my-project" : sanitized;
  }

  private static String guessRepoPath(String url) {
    var lastSlash = url.lastIndexOf('/');
    if (lastSlash < 0) return url;
    var name = url.substring(lastSlash + 1);
    if (name.endsWith(".git")) name = name.substring(0, name.length() - 4);
    return name;
  }

  /**
   * Interactively collects custom service definitions (any Podman image) and adds them to the
   * services map.
   */
  private static void collectCustomServices(
      PrintStream out, Ansi ansi, Map<String, SailYaml.Service> services) {
    do {
      out.println();
      out.println(ansi.string("  @|bold Custom Podman service|@"));
      var svcName =
          promptRequired(
              out, ansi, "Service name \u2014 a label for this service (e.g. mysql, redis)");
      var image = promptRequired(out, ansi, "Podman image (e.g. mysql:8.0, mongo:7, redis:latest)");
      var portsStr =
          promptWithDefault(
              out, ansi, "Host ports to expose (comma-separated, blank for none)", "");
      List<Integer> ports = null;
      if (!portsStr.isEmpty()) {
        ports = new ArrayList<>();
        for (var p : portsStr.split("[,\\s]+")) {
          try {
            ports.add(Integer.parseInt(p.strip()));
          } catch (NumberFormatException ignored) {
          }
        }
        if (ports.isEmpty()) ports = null;
      }
      Map<String, String> env = null;
      if (ConsoleHelper.confirmNo("Add environment variables?")) {
        env = new LinkedHashMap<>();
        do {
          var kv = promptRequired(out, ansi, "Environment variable as KEY=VALUE");
          var eqIdx = kv.indexOf('=');
          if (eqIdx > 0) {
            env.put(kv.substring(0, eqIdx), kv.substring(eqIdx + 1));
          }
        } while (ConsoleHelper.confirmNo("Add another env var?"));
      }
      List<String> volumes = null;
      if (ConsoleHelper.confirmNo("Add volumes?")) {
        volumes = new ArrayList<>();
        do {
          var vol = promptRequired(out, ansi, "Volume as name:path (e.g. mydata:/var/lib/data)");
          volumes.add(vol);
        } while (ConsoleHelper.confirmNo("Add another volume?"));
      }
      services.put(svcName, new SailYaml.Service(image, ports, env, null, volumes));
    } while (ConsoleHelper.confirmNo("Add another custom service?"));
  }

  private static String buildJsonOutput(SailYaml config) {
    var map = new LinkedHashMap<String, Object>();
    map.put("name", config.name());
    if (config.description() != null) map.put("description", config.description());

    var res = new LinkedHashMap<String, Object>();
    res.put("cpu", config.resources().cpu());
    res.put("memory", config.resources().memory());
    res.put("disk", config.resources().disk());
    map.put("resources", res);

    map.put("image", config.image());

    if (config.runtimes() != null) {
      var rt = new LinkedHashMap<String, Object>();
      if (config.runtimes().jdk() > 0) rt.put("jdk", config.runtimes().jdk());
      if (config.runtimes().node() != null) rt.put("node", config.runtimes().node());
      if (config.runtimes().maven() != null) rt.put("maven", config.runtimes().maven());
      if (!rt.isEmpty()) map.put("runtimes", rt);
    }

    if (config.git() != null) {
      var g = new LinkedHashMap<String, Object>();
      g.put("name", config.git().name());
      g.put("email", config.git().email());
      g.put("auth", config.git().auth());
      if (config.git().sshKey() != null) g.put("ssh_key", config.git().sshKey());
      map.put("git", g);
    }

    if (config.repos() != null && !config.repos().isEmpty()) {
      var reposList = new ArrayList<Map<String, Object>>();
      for (var repo : config.repos()) {
        var r = new LinkedHashMap<String, Object>();
        r.put("url", repo.url());
        r.put("path", repo.path());
        if (repo.branch() != null) r.put("branch", repo.branch());
        reposList.add(r);
      }
      map.put("repos", reposList);
    }

    if (config.services() != null && !config.services().isEmpty()) {
      var svcs = new LinkedHashMap<String, Object>();
      for (var entry : config.services().entrySet()) {
        var svc = new LinkedHashMap<String, Object>();
        svc.put("image", entry.getValue().image());
        if (entry.getValue().ports() != null) svc.put("ports", entry.getValue().ports());
        if (entry.getValue().environment() != null)
          svc.put("environment", entry.getValue().environment());
        if (entry.getValue().volumes() != null) svc.put("volumes", entry.getValue().volumes());
        if (entry.getValue().command() != null) svc.put("command", entry.getValue().command());
        svcs.put(entry.getKey(), svc);
      }
      map.put("services", svcs);
    }

    if (config.ssh() != null) {
      var s = new LinkedHashMap<String, Object>();
      s.put("user", config.ssh().user());
      if (config.ssh().authorizedKeys() != null)
        s.put("authorized_keys", config.ssh().authorizedKeys());
      map.put("ssh", s);
    }

    if (config.agent() != null) {
      var a = new LinkedHashMap<String, Object>();
      a.put("type", config.agent().type());
      a.put("auto_snapshot", config.agent().autoSnapshot());
      a.put("auto_branch", config.agent().autoBranch());
      if (config.agent().branchPrefix() != null)
        a.put("branch_prefix", config.agent().branchPrefix());
      if (config.agent().install() != null) a.put("install", config.agent().install());
      map.put("agent", a);
    }

    return YamlUtil.dumpJson(map);
  }
}
