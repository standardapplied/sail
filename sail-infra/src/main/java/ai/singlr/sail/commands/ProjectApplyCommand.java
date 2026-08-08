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
import ai.singlr.sail.engine.ContainerSailSetup;
import ai.singlr.sail.engine.ContainerState;
import ai.singlr.sail.engine.GitCredentials;
import ai.singlr.sail.engine.LocalIdentity;
import ai.singlr.sail.engine.NameValidator;
import ai.singlr.sail.engine.ProjectApplier;
import ai.singlr.sail.engine.ProjectCatalog;
import ai.singlr.sail.engine.ProjectDefinitions;
import ai.singlr.sail.engine.ProjectPhase;
import ai.singlr.sail.engine.ProjectProvisioner;
import ai.singlr.sail.engine.ProvisionListener;
import ai.singlr.sail.engine.ProvisionTracker;
import ai.singlr.sail.engine.SailPaths;
import ai.singlr.sail.engine.ShellExecutor;
import ai.singlr.sail.engine.WorkstationIdentity;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import picocli.CommandLine.Command;
import picocli.CommandLine.Help.Ansi;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Spec;

/**
 * The one verb that makes a project container match intent, from any starting state. Desired state
 * is the resolved descriptor plus the machinery this binary generates; observed state is the
 * container. Apply converges the difference: an absent container is provisioned, a stopped one is
 * started, and a running one has its services, repos, workspace files, agent tools, git config,
 * agent context, cleanup cron, sail machinery, and hostname reconciled. Idempotent: applying a
 * current project changes nothing.
 */
@Command(
    name = "apply",
    description =
        "Make a project match its descriptor: create it if absent, start it if stopped, and"
            + " converge services, repos, files, agent context, and the sail machinery.",
    mixinStandardHelpOptions = true)
public final class ProjectApplyCommand implements Runnable {

  @Parameters(
      index = "0",
      arity = "0..1",
      description =
          "Project name (uses ~/.sail/projects/<name>/sail.yaml if -f not given)."
              + " Omit when --all is set.")
  private String name;

  @Option(names = "--all", description = "Apply every existing project container.")
  private boolean all;

  @Option(
      names = {"-f", "--file"},
      description = "Path to sail.yaml project descriptor.")
  private String file;

  @Option(names = "--json", description = "Output in JSON format.")
  private boolean json;

  @Option(names = "--dry-run", description = "Print commands instead of executing them.")
  private boolean dryRun;

  @Option(names = "--yes", description = "Skip confirmation prompts (for non-interactive use).")
  private boolean yes;

  @Option(
      names = "--git-token",
      description = "Access token for cloning private repos over HTTPS.",
      defaultValue = "${GITHUB_TOKEN}")
  private String gitToken;

  @Spec private CommandSpec spec;

  enum Action {
    CREATED,
    STARTED,
    CONVERGED
  }

  /** The lifecycle decision, pure in the observed state: absent → create, stopped → start. */
  static Action plan(ContainerState state) {
    return switch (state) {
      case ContainerState.Running ignored -> Action.CONVERGED;
      case ContainerState.Stopped ignored -> Action.STARTED;
      case ContainerState.NotCreated ignored -> Action.CREATED;
      case ContainerState.Error e ->
          throw new IllegalStateException("Container error: " + e.message());
    };
  }

  @Override
  public void run() {
    CliCommand.run(spec, this::execute);
  }

  private void execute() throws Exception {
    if (all && Strings.isNotBlank(name)) {
      throw new IllegalArgumentException("Pass a project name OR --all, not both.");
    }
    if (all && ProjectDefinitions.explicitFile(file) != null) {
      throw new IllegalArgumentException("--file applies to a single project, not --all.");
    }
    if (all) {
      applyAll();
    } else {
      applyOne();
    }
  }

  private void applyOne() throws Exception {
    refreshCanonicalFromCatalog();
    var sailYamlPath = resolveSailYamlPath(name, file);
    if (!Files.exists(sailYamlPath)) {
      throw new IllegalStateException(
          "Project descriptor not found: "
              + sailYamlPath.toAbsolutePath()
              + "\n  Run 'sail project init' to author one, sync it from main with 'sail sync', or"
              + " specify one with --file.");
    }

    var identity =
        new InteractiveIdentity(
            LocalIdentity.detect(),
            InteractiveIdentity.canPrompt(yes, json, dryRun, ConsoleHelper.hasConsole()));
    SailYaml config =
        ProjectDefinitions.resolveForProvisioning(Files.readString(sailYamlPath), identity);
    if (config.name() == null || config.name().isBlank()) {
      throw new IllegalStateException("sail.yaml must have a 'name' field.");
    }
    NameValidator.requireValidProjectName(config.name());

    if (!json) {
      Banner.printBranding(System.out, Ansi.AUTO);
      System.out.println();
    }

    var shell = new ShellExecutor(dryRun);
    var mgr = new ContainerManager(shell);
    var action = plan(mgr.queryState(config.name()));

    if (action == Action.STARTED) {
      if (!json) {
        System.out.println(Ansi.AUTO.string("  @|bold Starting|@ " + config.name() + "..."));
      }
      mgr.start(config.name());
      mgr.waitUntilReady(config.name());
    }

    var tracker =
        new ProvisionTracker<>(ProjectPhase.class, SailPaths.provisionState(config.name()), dryRun);
    tracker.load();
    if (action == Action.CREATED && tracker.hasIncompleteRun()) {
      if (!json) {
        System.out.println(
            Ansi.AUTO.string(
                "  @|faint Stale state detected — container '"
                    + config.name()
                    + "' no longer exists. Starting fresh.|@"));
      }
      tracker.reset();
    }
    if (action == Action.CREATED || tracker.hasIncompleteRun()) {
      provision(config, sailYamlPath, shell, tracker);
      action = Action.CREATED;
    }

    if (!json) {
      System.out.println(Ansi.AUTO.string("  @|bold Applying|@ " + config.name() + "..."));
      System.out.println();
    }

    var outcome = converge(shell, mgr, config, sailYamlPath);

    if (json) {
      System.out.println(YamlUtil.dumpJson(jsonSummary(config.name(), action, outcome)));
      return;
    }
    System.out.println();
    for (var warning : outcome.warnings()) {
      System.out.println(Ansi.AUTO.string("  @|yellow ⚠|@ " + warning));
    }
    System.out.println(Ansi.AUTO.string("  @|bold,green ✓|@ " + summaryLine(action, outcome)));
    if (action == Action.CREATED) {
      printConnectHints(config, shell, mgr);
    }
  }

  /**
   * Converges every existing container. Descriptors resolve from the store; a container without one
   * still gets its machinery and hostname healed, reported with a warning line, never a failure.
   * This is the post-upgrade retrofit: one command brings the whole box current.
   */
  private void applyAll() throws Exception {
    var shell = new ShellExecutor(dryRun);
    var mgr = new ContainerManager(shell);
    var targets = mgr.listAll();

    if (!json) {
      Banner.printBranding(System.out, Ansi.AUTO);
      System.out.println();
      var mode = dryRun ? " (dry run)" : "";
      System.out.println(Ansi.AUTO.string("  @|bold Applying|@ all projects" + mode));
      System.out.println();
    }

    var rows = new ArrayList<LinkedHashMap<String, Object>>();
    var applied = 0;
    var failed = 0;

    for (var target : targets) {
      var project = target.name();
      try {
        var action = plan(target.state());
        if (action == Action.STARTED) {
          mgr.start(project);
          mgr.waitUntilReady(project);
        }
        var definition = ProjectDefinitions.definition(project, null);
        Outcome outcome;
        if (definition.isPresent()) {
          var config = ProjectDefinitions.resolveForProvisioning(definition.get());
          var sailYamlPath = ProjectDefinitions.materialize(project, definition.get());
          outcome = converge(shell, mgr, config, sailYamlPath);
        } else {
          outcome = convergeMachineryOnly(shell, mgr, project);
        }
        applied++;
        if (json) {
          rows.add(jsonSummary(project, action, outcome));
        } else {
          for (var warning : outcome.warnings()) {
            System.out.println(Ansi.AUTO.string("  @|yellow ⚠|@ " + project + ": " + warning));
          }
          System.out.println(
              Ansi.AUTO.string("  @|green ✓|@ " + project + " — " + summaryLine(action, outcome)));
        }
      } catch (Exception e) {
        failed++;
        if (json) {
          var row = new LinkedHashMap<String, Object>();
          row.put("name", project);
          row.put("error", e.getMessage());
          rows.add(row);
        } else {
          System.err.println(
              Banner.errorLine("Could not apply " + project + ": " + e.getMessage(), Ansi.AUTO));
        }
      }
    }

    if (json) {
      var map = new LinkedHashMap<String, Object>();
      map.put("dry_run", dryRun);
      map.put("projects", rows);
      System.out.println(YamlUtil.dumpJson(map));
      return;
    }
    System.out.println();
    System.out.println(
        Ansi.AUTO.string(
            "  @|bold,green ✓|@ Applied "
                + applied
                + (failed > 0 ? ", failed " + failed : "")
                + "."));
  }

  record Outcome(
      int added,
      int removed,
      int skipped,
      ContainerSailSetup.Result machinery,
      boolean hostnameRealigned,
      List<String> warnings) {}

  /** The full convergence pass: descriptor state, sail machinery, agent context, hostname. */
  private Outcome converge(
      ShellExecutor shell, ContainerManager mgr, SailYaml config, Path sailYamlPath)
      throws Exception {
    var project = config.name();
    var hostnameRealigned = mgr.setHostname(project);
    var machinery = ContainerSailSetup.ensureInstalled(shell, project);

    var applier = new ProjectApplier(shell, System.out);
    var info = mgr.queryInfo(project);
    var warnings = new ArrayList<>(applier.checkUnsupportedChanges(config, info.limits()));
    var sshUser = config.sshUser();
    var token = Strings.isNotBlank(gitToken) ? gitToken : null;

    var results = new ArrayList<ProjectApplier.ApplyResult>();
    results.add(applier.applyServices(project, config.services()));
    results.add(applier.reconcileServices(project, config.services()));
    results.add(
        applier.applyRepos(
            project, config.repos(), sshUser, GitCredentials.singleTokenMap(token), config.git()));
    results.add(applier.applyWorkspaceFiles(project, sailYamlPath, sshUser));
    var agentInstall =
        config.agent() != null
            ? Objects.requireNonNullElse(config.agent().install(), List.of(config.agent().type()))
            : null;
    results.add(applier.applyAgentTools(project, agentInstall));
    results.add(applier.applyGitConfig(project, config.git(), sshUser));
    results.add(applier.applyAgentContext(project, config));
    results.add(applier.applyCleanupCron(project, sshUser));

    var added = results.stream().mapToInt(ProjectApplier.ApplyResult::added).sum();
    var removed = results.stream().mapToInt(ProjectApplier.ApplyResult::removed).sum();
    var skipped = results.stream().mapToInt(ProjectApplier.ApplyResult::skipped).sum();
    return new Outcome(added, removed, skipped, machinery, hostnameRealigned, warnings);
  }

  private Outcome convergeMachineryOnly(ShellExecutor shell, ContainerManager mgr, String project)
      throws Exception {
    var hostnameRealigned = mgr.setHostname(project);
    var machinery = ContainerSailSetup.ensureInstalled(shell, project);
    return new Outcome(
        0,
        0,
        0,
        machinery,
        hostnameRealigned,
        List.of("no descriptor in the catalog — converged machinery only"));
  }

  static String summaryLine(Action action, Outcome outcome) {
    var machinery =
        outcome.machinery() == ContainerSailSetup.Result.UPDATED ? "updated" : "current";
    return action.name().toLowerCase(Locale.ROOT)
        + ": "
        + outcome.added()
        + " added, "
        + outcome.removed()
        + " removed, "
        + outcome.skipped()
        + " skipped, machinery "
        + machinery
        + (outcome.hostnameRealigned() ? ", hostname realigned" : "");
  }

  static LinkedHashMap<String, Object> jsonSummary(String project, Action action, Outcome outcome) {
    var map = new LinkedHashMap<String, Object>();
    map.put("name", project);
    map.put("action", action.name().toLowerCase(Locale.ROOT));
    map.put("added", outcome.added());
    map.put("removed", outcome.removed());
    map.put("skipped", outcome.skipped());
    map.put(
        "machinery",
        outcome.machinery() == ContainerSailSetup.Result.UPDATED ? "updated" : "current");
    map.put("hostname_realigned", outcome.hostnameRealigned());
    if (!outcome.warnings().isEmpty()) {
      map.put("warnings", outcome.warnings());
    }
    return map;
  }

  /** Only provisioning needs root; converging a live container does not. */
  static void requireRootToProvision(String name, boolean dryRun, boolean isRoot) {
    if (!dryRun && !isRoot) {
      throw new IllegalStateException(
          "Root privileges required to provision '"
              + name
              + "'. Run with: sudo sail project apply "
              + name);
    }
  }

  /**
   * Provisions from the resolved descriptor with resumable progress: a fresh container from
   * scratch, or the remaining phases of an interrupted earlier run.
   */
  private void provision(
      SailYaml config,
      Path sailYamlPath,
      ShellExecutor shell,
      ProvisionTracker<ProjectPhase> tracker)
      throws Exception {
    requireRootToProvision(config.name(), dryRun, ConsoleHelper.isRoot());
    if (config.resources() == null) {
      throw new IllegalStateException(
          "sail.yaml must have a 'resources' section with cpu, memory, and disk.");
    }

    var projectDir = SailPaths.projectDir(config.name());
    Files.createDirectories(projectDir);
    var canonicalYaml = projectDir.resolve(SailPaths.PROJECT_DESCRIPTOR);
    syncProjectBundle(sailYamlPath, canonicalYaml);
    if (!dryRun) {
      ProjectCatalog.record(config.name(), Files.readString(canonicalYaml), null);
    }

    var hostYamlPath = SailPaths.hostConfigPath();
    if (!Files.exists(hostYamlPath)) {
      throw new IllegalStateException("Server not initialized. Run 'sail host init' first.");
    }
    var hostYaml = HostYaml.fromMap(YamlUtil.parseFile(hostYamlPath));

    if (tracker.hasIncompleteRun()) {
      if (!json) {
        Banner.printResumeInfo(tracker.currentState(), System.out, Ansi.AUTO);
      }
      if (!yes && !json && !ConsoleHelper.confirm("Resume provisioning?")) {
        throw new IllegalStateException("Aborted.");
      }
    }

    if (!json) {
      Banner.printProjectSummary(config, System.out, Ansi.AUTO);
    }
    if (!yes && !dryRun && !json && !tracker.hasIncompleteRun()) {
      if (!ConsoleHelper.confirm("Create project " + config.name() + "?")) {
        throw new IllegalStateException("Aborted.");
      }
    }

    var gitTokens = resolveGitTokens(config);
    var listener = json ? ProvisionListener.NOOP : ConsoleProvisionListener.INSTANCE;
    var provisioner = new ProjectProvisioner(shell, tracker, listener);
    provisioner.provision(config, hostYaml, gitTokens, sailYamlPath);

    if (!json) {
      System.out.println();
      Banner.printProjectCreated(
          config.name(), config.ssh() != null ? config.ssh().user() : null, System.out, Ansi.AUTO);
    }
  }

  private void printConnectHints(SailYaml config, ShellExecutor shell, ContainerManager mgr)
      throws Exception {
    var state = mgr.queryState(config.name());
    if (!(state instanceof ContainerState.Running r) || r.ipv4() == null) {
      return;
    }
    var hostYaml = HostYaml.fromMap(YamlUtil.parseFile(SailPaths.hostConfigPath()));
    var serverIp = hostYaml.serverIp();
    if (serverIp != null) {
      Banner.printSshConfig(
          config.name(),
          serverIp,
          System.getProperty("user.name"),
          r.ipv4(),
          WorkstationIdentity.identityFile(),
          System.out,
          Ansi.AUTO);
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

  private Map<String, String> resolveGitTokens(SailYaml config) {
    if (config.git() == null || !"token".equals(config.git().auth())) {
      return Map.of();
    }
    if (Strings.isNotBlank(gitToken)) {
      return GitCredentials.singleTokenMap(gitToken);
    }
    var hosts = GitCredentials.extractHttpsHosts(config.repos());
    if (hosts.isEmpty()) {
      return Map.of();
    }
    var tokens = new LinkedHashMap<String, String>();
    for (var host : hosts) {
      var envToken = GitCredentials.resolveTokenForHost(host, null);
      if (Strings.isNotBlank(envToken)) {
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
        if (Strings.isNotBlank(prompted)) {
          tokens.put(host, prompted);
        }
      } catch (EchoDisabledUnavailableException e) {
        throw new IllegalArgumentException(
            "Unable to read git access token interactively in this terminal.\n\n"
                + "Provide the token via one of:\n"
                + "  --git-token <token>\n"
                + "  GITHUB_TOKEN environment variable\n\n"
                + "Then re-run: sail project apply <name>");
      }
    }
    return Map.copyOf(tokens);
  }

  /**
   * Refreshes the canonical descriptor from the catalog (the source of truth) before reading it, so
   * apply converges a definition edited or synced on main rather than a stale local copy. A no-op
   * when {@code -f} is given (an explicit override), the project is absent from the catalog (a
   * brand-new local authoring), or no name was supplied.
   */
  private void refreshCanonicalFromCatalog() throws IOException {
    if (ProjectDefinitions.explicitFile(file) != null || name == null) {
      return;
    }
    NameValidator.requireValidProjectName(name);
    var definition = ProjectDefinitions.definition(name, null);
    if (definition.isPresent()) {
      ProjectDefinitions.materialize(name, definition.get());
    }
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
          StandardCopyOption.REPLACE_EXISTING,
          StandardCopyOption.COPY_ATTRIBUTES);
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
              StandardCopyOption.REPLACE_EXISTING,
              StandardCopyOption.COPY_ATTRIBUTES);
        }
      }
    }
  }

  private static void deleteDirectory(Path dir) throws Exception {
    if (!Files.exists(dir)) {
      return;
    }
    try (var walk = Files.walk(dir)) {
      for (var path : walk.sorted(Comparator.reverseOrder()).toList()) {
        Files.deleteIfExists(path);
      }
    }
  }
}
