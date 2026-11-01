/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.commands;

import ai.singlr.sail.common.Strings;
import ai.singlr.sail.config.ProjectDefinition;
import ai.singlr.sail.config.ProjectDefinitionJson;
import ai.singlr.sail.engine.Banner;
import ai.singlr.sail.engine.NameValidator;
import ai.singlr.sail.engine.OperatorCommand;
import ai.singlr.sail.gen.ServicePresets;
import ai.singlr.sail.harness.Harnesses;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import picocli.CommandLine.Command;
import picocli.CommandLine.Help.Ansi;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

@Command(
    name = "create",
    description =
        "Create a project in the catalog: from flags, from a definition on stdin, or by"
            + " answering questions on a terminal.",
    mixinStandardHelpOptions = true)
public final class ProjectCreateCommand implements Runnable {

  private static final String DEFAULT_IMAGE = "ubuntu/24.04";

  @Parameters(
      index = "0",
      arity = "0..1",
      description = "Project name. Asked for on a terminal; read from --definition when given.")
  private String name;

  @Option(
      names = "--definition",
      description = "Read the whole definition, a JSON document, from stdin (-).")
  private String definition;

  @Option(names = "--description", description = "What the project is.")
  private String description;

  @Option(names = "--image", description = "The container image (default ubuntu/24.04).")
  private String image;

  @Option(names = "--cpu", description = "CPU cores (default 2).")
  private Integer cpu;

  @Option(names = "--memory", description = "Memory (default 8GB).")
  private String memory;

  @Option(names = "--disk", description = "Disk (default 50GB).")
  private String disk;

  @Option(names = "--jdk", description = "JDK major version.")
  private Integer jdk;

  @Option(names = "--node", description = "Node.js version.")
  private String node;

  @Option(names = "--maven", description = "Maven version.")
  private String maven;

  @Option(
      names = "--repo",
      description = "An https repository to clone, its folder named after it. Repeatable.")
  private List<String> repos;

  @Option(
      names = "--service",
      description = "A service preset, optionally at a version (postgres, redis:7). Repeatable.")
  private List<String> services;

  @Option(
      names = "--agent",
      description =
          "The harness the project's agent runs (default claude-code; none for no agent).")
  private String agent;

  @Option(names = "--dry-run", description = "Print the definition instead of creating it.")
  private boolean dryRun;

  @Option(names = "--json", description = "Output in JSON format.")
  private boolean json;

  private final Supplier<InputStream> stdin;
  private final BooleanSupplier console;
  private final ProjectMutations mutations;

  public ProjectCreateCommand() {
    this(() -> System.in, ConsoleHelper::hasConsole, ProjectMutations.here());
  }

  ProjectCreateCommand(
      Supplier<InputStream> stdin, BooleanSupplier console, ProjectMutations mutations) {
    this.stdin = stdin;
    this.console = console;
    this.mutations = mutations;
  }

  @Override
  public void run() {
    CliCommand.run(this::execute);
  }

  private void execute() throws Exception {
    var operator = OperatorCommand.here();
    var ansi = Ansi.AUTO;
    var out = System.out;
    var created = ProjectDefinitionJson.validated(definition(out, ansi));
    var catalog = mutations.catalog();
    catalog.requireInScope(created.name());
    catalog.requireUnpruned(created.name());
    if (dryRun) {
      out.println(ProjectDefinitionJson.write(created));
      return;
    }
    catalog.create(created, mutations.writer(created.name(), false));
    if (json) {
      out.println(ProjectDefinitionJson.write(created));
      return;
    }
    out.println();
    out.println(
        ansi.string("  @|bold,green \u2713|@ Created " + created.name() + " in the catalog."));
    out.println(
        ansi.string(
            "    @|faint Next:|@ make its container with @|bold "
                + operator.sail("project apply " + created.name())
                + "|@"));
  }

  /** The definition this create was given, by stdin, by its questions, or by its flags. */
  ProjectDefinition definition(PrintStream out, Ansi ansi) throws Exception {
    if (definition != null) {
      return fromStdin();
    }
    if (!flagsGiven() && !json && console.getAsBoolean()) {
      Banner.printBranding(out, ansi);
      out.println();
      out.println(ansi.string("  @|bold Creating a project|@"));
      out.println(ansi.string("  @|faint Press Enter to accept defaults shown in [brackets].|@"));
      out.println();
      return collectInputs(out, ansi);
    }
    return fromFlags();
  }

  private ProjectDefinition fromStdin() throws Exception {
    if (!"-".equals(definition)) {
      throw new IllegalArgumentException(
          "--definition reads the definition from stdin: pass --definition -.");
    }
    if (flagsGiven()) {
      throw new IllegalArgumentException(
          "--definition carries the whole definition: drop the section flags, or the definition.");
    }
    var read =
        ProjectDefinitionJson.read(new String(stdin.get().readAllBytes(), StandardCharsets.UTF_8));
    if (Strings.isNotBlank(name) && !name.equals(read.name())) {
      throw new IllegalArgumentException(
          "The definition names project '"
              + read.name()
              + "', not '"
              + name
              + "'. Name the one it names, or none.");
    }
    return read;
  }

  private boolean flagsGiven() {
    return description != null
        || image != null
        || cpu != null
        || memory != null
        || disk != null
        || jdk != null
        || node != null
        || maven != null
        || repos != null
        || services != null
        || agent != null;
  }

  private ProjectDefinition fromFlags() {
    if (Strings.isBlank(name)) {
      throw new IllegalArgumentException(
          "Name the project to create, or pass its definition with --definition -.");
    }
    NameValidator.requireValidProjectName(name);
    var runtimes =
        jdk != null || node != null || maven != null
            ? new ProjectDefinition.Runtimes(jdk == null ? 0 : jdk, node, maven)
            : null;
    return new ProjectDefinition(
        name,
        description,
        new ProjectDefinition.Resources(
            cpu == null ? 2 : cpu,
            memory == null ? "8GB" : ProjectDefinition.normalizeSize(memory),
            disk == null ? "50GB" : ProjectDefinition.normalizeSize(disk)),
        image == null ? DEFAULT_IMAGE : image,
        null,
        runtimes,
        repos == null
            ? null
            : repos.stream()
                .map(
                    url ->
                        new ProjectDefinition.Repo(url, ProjectDefinition.Repo.pathOf(url), null))
                .toList(),
        services == null ? null : presets(services),
        null,
        agentNamed(agent == null ? Harnesses.DEFAULT.yamlName() : agent),
        null);
  }

  /** The services {@code named} presets give, each at its version when one is named. */
  static Map<String, ProjectDefinition.Service> presets(List<String> named) {
    var chosen = new LinkedHashMap<String, ProjectDefinition.Service>();
    for (var entry : named) {
      var colon = entry.indexOf(':');
      var key = colon < 0 ? entry : entry.substring(0, colon);
      var preset =
          ServicePresets.all().stream()
              .filter(candidate -> candidate.key().equals(key))
              .findFirst()
              .orElseThrow(
                  () ->
                      new IllegalArgumentException(
                          "'"
                              + key
                              + "' is not a service preset. The presets are: "
                              + String.join(
                                  ", ",
                                  ServicePresets.all().stream()
                                      .map(ServicePresets.Preset::key)
                                      .toList())
                              + "."));
      chosen.put(
          preset.key(),
          colon < 0 ? preset.service() : preset.withVersion(entry.substring(colon + 1)));
    }
    return chosen;
  }

  /** The agent a project gets for the harness {@code type}; null for {@code none}. */
  static ProjectDefinition.Agent agentNamed(String type) {
    if ("none".equalsIgnoreCase(type)) {
      return null;
    }
    return new ProjectDefinition.Agent(type, true, "agent/", true, null, null, null, null);
  }

  /**
   * Asks which harness the project's agent runs, offering the default, and which others to install
   * beside it. Answers {@code null} for a project that wants no agent.
   */
  static ProjectDefinition.Agent promptAgent(PrintStream out, Ansi ansi) {
    var agentType =
        ConsoleHelper.promptWithDefault(out, ansi, agentTypePrompt(), Harnesses.DEFAULT.yamlName());
    if ("none".equalsIgnoreCase(agentType)) {
      return null;
    }
    List<String> install = null;
    if (ConsoleHelper.confirmNo("Install additional agent CLIs?")) {
      install = new ArrayList<>();
      install.add(agentType);
      do {
        var other = ConsoleHelper.promptRequired(out, ansi, agentCliPrompt());
        if (!other.equals(agentType)) {
          install.add(other);
        }
      } while (ConsoleHelper.confirmNo("Add another?"));
    }
    return new ProjectDefinition.Agent(agentType, true, "agent/", true, install, null, null, null);
  }

  static String agentTypePrompt() {
    return "Agent type (" + String.join("/", Harnesses.names()) + "/none)";
  }

  static String agentCliPrompt() {
    return "Agent CLI name (" + String.join("/", Harnesses.names()) + ")";
  }

  private ProjectDefinition collectInputs(PrintStream out, Ansi ansi) {
    var name =
        Strings.isNotBlank(this.name)
            ? this.name
            : ConsoleHelper.promptWithDefault(
                out,
                ansi,
                "Project name",
                sanitizeProjectName(Path.of("").toAbsolutePath().getFileName().toString()));
    NameValidator.requireValidProjectName(name);

    var description = ConsoleHelper.promptWithDefault(out, ansi, "Description", "");
    if (description.isEmpty()) description = null;

    out.println();
    out.println(ansi.string("  @|bold Resources|@"));
    var cpu = promptInt(out, ansi, "CPU cores", 2);
    var memory =
        ProjectDefinition.normalizeSize(
            ConsoleHelper.promptWithDefault(out, ansi, "Memory", "8GB"));
    var disk =
        ProjectDefinition.normalizeSize(ConsoleHelper.promptWithDefault(out, ansi, "Disk", "50GB"));
    var resources = new ProjectDefinition.Resources(cpu, memory, disk);

    out.println();
    out.println(ansi.string("  @|bold Runtimes|@"));
    var jdkStr =
        ConsoleHelper.promptWithDefault(out, ansi, "JDK major version (blank to skip)", "25");
    int jdk;
    try {
      jdk = jdkStr.isEmpty() ? 0 : Integer.parseInt(jdkStr);
    } catch (NumberFormatException e) {
      jdk = 25;
    }
    var nodeStr = ConsoleHelper.promptWithDefault(out, ansi, "Node.js version (blank to skip)", "");
    var maven =
        ConsoleHelper.promptWithDefault(out, ansi, "Maven version (blank to skip)", "3.9.14");
    ProjectDefinition.Runtimes runtimes = null;
    var nodeVersion = nodeStr.isEmpty() ? null : nodeStr;
    if (jdk > 0 || nodeVersion != null || !maven.isEmpty()) {
      runtimes = new ProjectDefinition.Runtimes(jdk, nodeVersion, maven.isEmpty() ? null : maven);
    }

    out.println();
    List<ProjectDefinition.Repo> repos = null;
    if (ConsoleHelper.confirmNo("Add repositories to clone?")) {
      repos = new ArrayList<>();
      do {
        var url = ConsoleHelper.promptRequired(out, ansi, "Repo URL");
        var pathDefault = ProjectDefinition.Repo.pathOf(url);
        var path = ConsoleHelper.promptWithDefault(out, ansi, "Local path", pathDefault);
        var branch = ConsoleHelper.promptWithDefault(out, ansi, "Branch (blank for default)", "");
        repos.add(new ProjectDefinition.Repo(url, path, branch.isEmpty() ? null : branch));
      } while (ConsoleHelper.confirmNo("Add another repository?"));
    }

    out.println();
    out.println(ansi.string("  @|bold Infrastructure services (Podman containers)|@"));
    Map<String, ProjectDefinition.Service> services = new LinkedHashMap<>();
    for (var preset : ServicePresets.all()) {
      var ports =
          preset.service().ports().stream()
              .map(String::valueOf)
              .reduce((a, b) -> a + ", " + b)
              .orElse("");
      var label = preset.displayName() + ansi.string(" @|faint (port " + ports + ")|@") + "?";
      if (ConsoleHelper.confirmNo(label)) {
        var version =
            ConsoleHelper.promptWithDefault(out, ansi, "  Version", preset.defaultVersion());
        var service =
            ProjectAddServiceCommand.customizePresetEnv(preset.withVersion(version), out, ansi);
        services.put(preset.key(), service);
      }
    }
    if (ConsoleHelper.confirmNo("Add a custom service?")) {
      collectCustomServices(out, ansi, services);
    }
    if (services.isEmpty()) {
      services = null;
    }

    var processes = promptApp(out, ansi, runtimes, repos);

    out.println();
    var agent = promptAgent(out, ansi);

    return new ProjectDefinition(
        name,
        description,
        resources,
        DEFAULT_IMAGE,
        null,
        runtimes,
        repos,
        services,
        processes,
        agent,
        null);
  }

  /**
   * Asks for the app the project runs, when a runtime implies one: the command that starts it and
   * the port it listens on, which the portal forwards and opens. It runs from the first repo's
   * folder. Null when no runtime implies an app or the command is left blank.
   */
  static Map<String, ProjectDefinition.Process> promptApp(
      PrintStream out,
      Ansi ansi,
      ProjectDefinition.Runtimes runtimes,
      List<ProjectDefinition.Repo> repos) {
    if (runtimes == null || (runtimes.node() == null && runtimes.jdk() == 0)) {
      return null;
    }
    out.println();
    out.println(ansi.string("  @|bold App|@ @|faint (the portal brings it up and opens it)|@"));
    var command =
        ConsoleHelper.promptWithDefault(out, ansi, "Command that starts it (blank to skip)", "");
    if (command.isBlank()) {
      return null;
    }
    var conventional = runtimes.node() != null ? 3000 : 8080;
    var port = promptInt(out, ansi, "Port it listens on", conventional);
    var workdir = repos == null || repos.isEmpty() ? null : repos.getFirst().path();
    return Map.of("app", new ProjectDefinition.Process(command, workdir, port, Map.of()));
  }

  private static int promptInt(PrintStream out, Ansi ansi, String label, int def) {
    var raw = ConsoleHelper.promptWithDefault(out, ansi, label, String.valueOf(def));
    try {
      return Integer.parseInt(raw);
    } catch (NumberFormatException e) {
      return def;
    }
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

  /**
   * Interactively collects custom service definitions (any Podman image) and adds them to the
   * services map.
   */
  private static void collectCustomServices(
      PrintStream out, Ansi ansi, Map<String, ProjectDefinition.Service> services) {
    do {
      out.println();
      out.println(ansi.string("  @|bold Custom Podman service|@"));
      var svcName =
          ConsoleHelper.promptRequired(
              out, ansi, "Service name \u2014 a label for this service (e.g. mysql, redis)");
      var image =
          ConsoleHelper.promptRequired(
              out, ansi, "Podman image (e.g. mysql:8.0, mongo:7, redis:latest)");
      var portsStr =
          ConsoleHelper.promptWithDefault(
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
          var kv = ConsoleHelper.promptRequired(out, ansi, "Environment variable as KEY=VALUE");
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
          var vol =
              ConsoleHelper.promptRequired(
                  out, ansi, "Volume as name:path (e.g. mydata:/var/lib/data)");
          volumes.add(vol);
        } while (ConsoleHelper.confirmNo("Add another volume?"));
      }
      services.put(svcName, new ProjectDefinition.Service(image, ports, env, null, volumes));
    } while (ConsoleHelper.confirmNo("Add another custom service?"));
  }
}
