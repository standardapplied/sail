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
import ai.singlr.sail.engine.IncusDeviceManager;
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
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
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
import java.util.Optional;
import java.util.concurrent.TimeoutException;
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
    if (json && dryRun) {
      throw new IllegalArgumentException(
          "--json and --dry-run cannot be combined: dry-run narrates the underlying shell"
              + " commands on stdout, which would corrupt the JSON document. Drop one of the two"
              + " flags.");
    }
    if (all && Strings.isNotBlank(name)) {
      throw new IllegalArgumentException("Pass a project name OR --all, not both.");
    }
    if (all && file != null) {
      throw new IllegalArgumentException("--file applies to a single project, not --all.");
    }
    if (all) {
      applyAll();
    } else {
      applyOne();
    }
  }

  private void applyOne() throws Exception {
    var definition = loadDefinition();
    var sailYamlPath = definition.path();

    var identity =
        new InteractiveIdentity(
            LocalIdentity.detect(),
            InteractiveIdentity.canPrompt(yes, json, dryRun, ConsoleHelper.hasConsole()));
    SailYaml config = ProjectDefinitions.resolveForProvisioning(definition.text(), identity);
    if (config.name() == null || config.name().isBlank()) {
      throw new IllegalStateException("sail.yaml must have a 'name' field.");
    }
    NameValidator.requireValidProjectName(config.name());
    requireMatchingName(name, config.name(), sailYamlPath);
    if (config.resources() == null) {
      throw new IllegalStateException(
          "sail.yaml must have a 'resources' section with cpu, memory, and disk.");
    }

    if (!json) {
      Banner.printBranding(System.out, Ansi.AUTO);
      System.out.println();
    }

    var observer = new ShellExecutor(false);
    var shell = new ShellExecutor(dryRun);
    var observed = new ContainerManager(observer);
    var mgr = new ContainerManager(shell);
    var action = plan(observed.queryState(config.name()));

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

    var outcome = converge(observer, shell, config, sailYamlPath);

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
      printConnectHints(config, observer, observed);
    }
  }

  /**
   * Converges every Sail-managed container: one with a definition in the store, or a legacy
   * descriptor-less one an earlier release provisioned (recognizable by its API-socket device) —
   * the latter still gets its machinery and hostname healed, reported with a warning line, never a
   * failure. Unrelated Incus instances on the same host are never targets: starting one or
   * attaching the API-socket mount would hand the box credential to a container Sail did not
   * provision. Each target rides the same resumable lifecycle as single-project apply, so an
   * interrupted provisioning run finishes its remaining phases instead of being skipped. This is
   * the post-upgrade retrofit: one command brings the whole box current.
   */
  private void applyAll() throws Exception {
    var observer = new ShellExecutor(false);
    var shell = new ShellExecutor(dryRun);
    var mgr = new ContainerManager(shell);
    var devices = new IncusDeviceManager(observer);
    var targets = new ContainerManager(observer).listAll();

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
      var definition = definitionFor(project);
      try {
        if (!sailManaged(project, devices)) {
          var warning = unmanagedSkipWarning(project, definition.isPresent());
          if (warning != null) {
            if (json) {
              var row = new LinkedHashMap<String, Object>();
              row.put("name", project);
              row.put("skipped", warning);
              rows.add(row);
            } else {
              System.out.println(Ansi.AUTO.string("  @|yellow ⚠|@ " + warning));
            }
          }
          continue;
        }
        var targetPlan = planTarget(project, target.state(), definition.orElse(null));
        var action = targetPlan.action();
        if (action == Action.STARTED) {
          mgr.start(project);
          mgr.waitUntilReady(project);
        }
        Outcome outcome;
        if (targetPlan.config() != null) {
          var sailYamlPath = materializeUnlessDryRun(project, definition.get(), dryRun);
          var tracker =
              new ProvisionTracker<>(ProjectPhase.class, SailPaths.provisionState(project), dryRun);
          tracker.load();
          if (tracker.hasIncompleteRun()) {
            provision(targetPlan.config(), sailYamlPath, shell, tracker);
            action = Action.CREATED;
          }
          outcome = converge(observer, shell, targetPlan.config(), sailYamlPath);
        } else {
          outcome = convergeMachineryOnly(observer, shell, project);
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
    } else {
      System.out.println();
      System.out.println(Ansi.AUTO.string(allSummaryLine(applied, failed)));
    }
    requireAllApplied(failed);
  }

  /**
   * The trust gate for {@code --all}: a container is Sail-managed only when it carries the
   * API-socket device Sail itself attached — instance-level provenance. A catalog row is name-level
   * intent, never provenance: {@code destroy} without {@code --purge} retains the row, and a
   * foreign container later created under the same name must not inherit the API mount and box
   * credential from a stale entry. An unmarked container the operator does own is claimed through
   * explicit single-project apply, where naming it is the authorization.
   */
  static boolean sailManaged(String project, IncusDeviceManager devices)
      throws IOException, InterruptedException, TimeoutException {
    return NameValidator.isValidProjectName(project)
        && devices.currentEventSocketSource(project) != null;
  }

  /**
   * A catalog row pointing at a container the trust gate refused is a disagreement worth narrating,
   * with the explicit escape hatch; a foreign container with no row is not Sail's to mention.
   */
  static String unmanagedSkipWarning(String project, boolean hasDefinition) {
    if (!hasDefinition) {
      return null;
    }
    return project
        + ": catalog entry exists but the container carries no sail-api-sock device — possibly a"
        + " foreign container reusing the name. Skipped; claim it explicitly with 'sail project"
        + " apply "
        + project
        + "' if it is yours.";
  }

  record TargetPlan(Action action, SailYaml config) {}

  /**
   * Resolves and validates a bulk-apply target before any lifecycle mutation: a descriptor that
   * fails to parse or names a different project must fail while the container's observed state is
   * still untouched — the same fail-fast contract the single-project path keeps. A target without a
   * descriptor plans a machinery-only convergence ({@code config} null).
   */
  static TargetPlan planTarget(String project, ContainerState state, String definitionText) {
    var action = plan(state);
    if (definitionText == null) {
      return new TargetPlan(action, null);
    }
    var config = ProjectDefinitions.resolveForProvisioning(definitionText);
    requireMatchingName(project, config.name(), null);
    return new TargetPlan(action, config);
  }

  private static Optional<String> definitionFor(String project) {
    return NameValidator.isValidProjectName(project)
        ? ProjectDefinitions.definition(project, null)
        : Optional.empty();
  }

  static String allSummaryLine(int applied, int failed) {
    var style = failed == 0 ? "bold,green ✓" : "bold,red ✗";
    return "  @|"
        + style
        + "|@ Applied "
        + applied
        + (failed > 0 ? ", failed " + failed : "")
        + ".";
  }

  static void requireAllApplied(int failed) {
    if (failed > 0) {
      throw new IllegalStateException("Failed to apply " + failed + " project(s).");
    }
  }

  /**
   * The requested name selects the target; the descriptor must agree. Silently letting the
   * descriptor's name win would redirect a destructive converge — undeclared-service removal
   * included — at a container the operator never named.
   */
  static void requireMatchingName(String requested, String declared, Path descriptorPath) {
    if (Strings.isNotBlank(requested) && !requested.equals(declared)) {
      var source = descriptorPath != null ? descriptorPath.toString() : "its catalog descriptor";
      throw new IllegalStateException(
          "Requested project '"
              + requested
              + "' but "
              + source
              + " declares name '"
              + declared
              + "'.\n  Fix the 'name' field or apply the project the descriptor names.");
    }
  }

  private static final PrintStream DISCARD =
      new PrintStream(OutputStream.nullOutputStream(), true, StandardCharsets.UTF_8);

  /** JSON mode promises one parseable document on stdout, so progress narration is discarded. */
  static PrintStream progressOut(boolean json) {
    return json ? DISCARD : System.out;
  }

  record Outcome(
      int added,
      int removed,
      int skipped,
      ContainerSailSetup.Result machinery,
      boolean hostnameRealigned,
      List<String> warnings) {}

  /**
   * The full convergence pass: descriptor state, sail machinery, agent context, hostname. The
   * {@code observer} always executes, so every state probe sees the live container; only mutations
   * ride {@code shell}, which a dry run swaps for command narration.
   */
  private Outcome converge(
      ShellExecutor observer, ShellExecutor shell, SailYaml config, Path sailYamlPath)
      throws Exception {
    var project = config.name();
    var observed = new ContainerManager(observer);
    var mgr = new ContainerManager(shell);
    var hostnameRealigned = !observed.hostnameMatches(project) && mgr.setHostname(project);
    var machinery = ContainerSailSetup.ensureInstalled(observer, shell, project);

    var applier = new ProjectApplier(observer, shell, progressOut(json));
    var info = observed.queryInfo(project);
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

  private Outcome convergeMachineryOnly(ShellExecutor observer, ShellExecutor shell, String project)
      throws Exception {
    var observed = new ContainerManager(observer);
    var mgr = new ContainerManager(shell);
    var hostnameRealigned = !observed.hostnameMatches(project) && mgr.setHostname(project);
    var machinery = ContainerSailSetup.ensureInstalled(observer, shell, project);
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
    persistCanonicalBundle(config.name(), sailYamlPath, dryRun);

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

  private record Definition(String text, Path path) {}

  /**
   * Loads the definition text catalog-first (the source of truth), so apply converges a definition
   * edited or synced on main rather than a stale local copy. Falls back to the resolved descriptor
   * file when {@code -f} is given (an explicit override), the project is absent from the catalog (a
   * brand-new local authoring), or no name was supplied. The plan always reads the same desired
   * state; only a real apply re-materializes the canonical descriptor from the catalog.
   */
  /**
   * Whether apply reads the catalog: only when no {@code -f} was given and a project was named.
   * This command's {@code -f} has no default value, so any non-null path — including the literal
   * {@code sail.yaml} that older commands' default-value handling maps to absent — is an explicit
   * operator override and must win over the catalog.
   */
  static boolean usesCatalog(String file, String name) {
    return file == null && name != null;
  }

  private Definition loadDefinition() throws IOException {
    if (usesCatalog(file, name)) {
      NameValidator.requireValidProjectName(name);
      var catalog = ProjectDefinitions.definition(name, null);
      if (catalog.isPresent()) {
        return new Definition(catalog.get(), materializeUnlessDryRun(name, catalog.get(), dryRun));
      }
    }
    var sailYamlPath = resolveSailYamlPath(name, file);
    if (!Files.exists(sailYamlPath)) {
      throw new IllegalStateException(
          "Project descriptor not found: "
              + sailYamlPath.toAbsolutePath()
              + "\n  Run 'sail project init' to author one, sync it from main with 'sail sync', or"
              + " specify one with --file.");
    }
    return new Definition(Files.readString(sailYamlPath), sailYamlPath);
  }

  /**
   * A dry run promises to leave the host untouched, so it plans against the in-memory definition
   * and only resolves where the canonical descriptor would live; a real apply materializes it.
   */
  static Path materializeUnlessDryRun(String name, String definition, boolean dryRun)
      throws IOException {
    if (dryRun) {
      return defaultDescriptorPath(name);
    }
    return ProjectDefinitions.materialize(name, definition);
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

  /**
   * Copies the descriptor and its {@code files/} directory into the canonical project bundle and
   * records it in the catalog. A dry run must not touch the host filesystem at all — the plan is
   * computed from the source descriptor, and the sync starts by deleting the canonical files
   * directory, so running it under dry-run would destroy locally authored project files.
   */
  static void persistCanonicalBundle(String name, Path sailYamlPath, boolean dryRun)
      throws Exception {
    if (dryRun) {
      return;
    }
    var projectDir = SailPaths.projectDir(name);
    Files.createDirectories(projectDir);
    var canonicalYaml = projectDir.resolve(SailPaths.PROJECT_DESCRIPTOR);
    syncProjectBundle(sailYamlPath, canonicalYaml);
    ProjectCatalog.record(name, Files.readString(canonicalYaml), null);
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
