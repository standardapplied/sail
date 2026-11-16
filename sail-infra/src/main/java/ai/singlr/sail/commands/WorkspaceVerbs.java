/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.commands;

import ai.singlr.sail.api.HostOperations;
import ai.singlr.sail.api.OperationsFactory;
import ai.singlr.sail.api.ProjectSkills;
import ai.singlr.sail.common.Remedied;
import ai.singlr.sail.common.Remedy;
import ai.singlr.sail.config.HostYaml;
import ai.singlr.sail.config.ProjectDefinition;
import ai.singlr.sail.config.YamlUtil;
import ai.singlr.sail.engine.ContainerSailSetup;
import ai.singlr.sail.engine.GitCredentials;
import ai.singlr.sail.engine.NameValidator;
import ai.singlr.sail.engine.OperatorCommand;
import ai.singlr.sail.engine.ProjectCatalog;
import ai.singlr.sail.engine.ProjectPhase;
import ai.singlr.sail.engine.ProjectProvisioner;
import ai.singlr.sail.engine.ProvisionListener;
import ai.singlr.sail.engine.ProvisionTracker;
import ai.singlr.sail.engine.RunPlace;
import ai.singlr.sail.engine.SailPaths;
import ai.singlr.sail.engine.ShellExecutor;
import ai.singlr.sail.engine.WorkspaceOwner;
import ai.singlr.sail.engine.WorkspaceTemplate;
import ai.singlr.sail.engine.Workspaces;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Predicate;
import java.util.function.Supplier;
import picocli.CommandLine.Model.CommandSpec;

/**
 * What the template and workspace verbs share: the catalog read every verb asks first, as {@code
 * project apply} does, the box's lanes, and what a template is built from on this box.
 */
final class WorkspaceVerbs {

  private WorkspaceVerbs() {}

  /**
   * Project {@code name}'s definition, read from the catalog as the box's person reads it, which is
   * what {@code project apply} asks: a project outside their scope reads as absent. A catalog this
   * account cannot read is said to be one, never taken for a project that is not there.
   */
  static ProjectDefinition readable(String name, OperatorCommand operator) {
    NameValidator.requireValidProjectName(name);
    var database = SailPaths.controlPlaneDb();
    if (!Files.isReadable(database)) {
      throw Remedied.state(
          "This account reads no catalog ("
              + database
              + " is not there or not readable), so project '"
              + name
              + "' cannot be looked up.",
          Remedy.of("Run it as root, with sudo, on a box set up with: ", Remedy.setUp()));
    }
    return new ProjectCatalog(database)
        .read(name)
        .orElseThrow(() -> ProjectMutations.notFound(name, operator));
  }

  /**
   * Project {@code name}'s definition, when its template can be built on this box: it sets
   * resources, {@code host init} has run and sail-api has, since every container mounts its socket.
   */
  static ProjectDefinition buildable(String name, OperatorCommand operator) {
    var config = ProjectMutations.requireResources(readable(name, operator), operator);
    if (!Files.exists(SailPaths.hostConfigPath())) {
      throw HostInitCommand.notInitialized(operator);
    }
    ContainerSailSetup.requireApi(SailPaths.apiSocketHostDir());
    return config;
  }

  /** Refuses a caller who is not root, unless it only rehearses ({@code dryRun}), as apply does. */
  static void requireRoot(boolean dryRun, CommandSpec spec) {
    if (!dryRun && !ConsoleHelper.isRoot()) {
      throw RootRequired.refusal(spec);
    }
  }

  /** The workspaces of this box, reading Incus for real and changing it unless {@code dryRun}. */
  static Workspaces workspaces(boolean dryRun) {
    return Workspaces.onThisBox(new ShellExecutor(dryRun));
  }

  /**
   * Whether a run of {@code project}'s spec, the one a workspace is, is live on the box whose
   * handle is {@code handle}, as the runs {@code operations} opens say: what refuses a stop or
   * delete of that workspace.
   */
  static Predicate<String> liveRun(
      String project, Supplier<HostOperations> operations, String handle) {
    return spec -> {
      try (var opened = operations.get()) {
        return opened.dispatching().runningRuns(project, handle).stream()
            .anyMatch(run -> run.place().equals(RunPlace.of(project, spec)));
      }
    };
  }

  /** Person {@code handle} as the owner of a workspace of this box ({@link WorkspaceOwner#of}). */
  static WorkspaceOwner ownerOf(String handle) {
    try (var operations = OperationsFactory.open()) {
      return operations.identity().workspaceOwner(handle);
    }
  }

  /**
   * The template lane of this box, reading Incus for real and changing it unless {@code dryRun}.
   */
  static WorkspaceTemplate templates(boolean dryRun) {
    return new WorkspaceTemplate(
        new ShellExecutor(false), new ShellExecutor(dryRun), SailPaths.locksDir());
  }

  /**
   * Where a template build of {@code project} keeps its progress, never where apply keeps its own.
   */
  static Path progressOf(String project) {
    return SailPaths.templateProvisionState(project);
  }

  /**
   * The progress a template build resumes: what {@code progress} records for {@code inputs}, unless
   * the template is {@code fresh} or the record is of a finished build, when every step runs again.
   */
  static ProvisionTracker<ProjectPhase> tracker(
      Path progress, String inputs, boolean fresh, boolean dryRun) throws IOException {
    var tracker = new ProvisionTracker<>(ProjectPhase.class, progress, dryRun).load(inputs);
    if (fresh || tracker.isComplete()) {
      tracker.reset();
    }
    return tracker;
  }

  /**
   * The git tokens this box's person stored for {@code config}'s repos, keyed as each repo URL
   * spells its host ({@link GitCredentials#forRepos}): the one source a host command clones with.
   */
  static Map<String, String> boxTokens(ProjectDefinition config) {
    try (var operations = OperationsFactory.open()) {
      return GitCredentials.forRepos(operations.identity().gitTokens(), config.repos());
    }
  }

  /**
   * Refuses, before anything is provisioned, {@code config} when one of its https repos asks for
   * credentials that {@code tokens} has none for, naming the {@code sail git login} that stores one
   * and, when {@code env} sets a token variable, that Sail does not read it.
   */
  static void requireTokens(
      ProjectDefinition config,
      Map<String, String> tokens,
      Predicate<String> asks,
      Map<String, String> env) {
    var missing = GitCredentials.missing(config.repos(), tokens, asks);
    if (missing.isPresent()) {
      throw Remedied.argument(
          missing.get().reason(config.name(), "this box's person"), missing.get().remedy(env));
    }
  }

  /**
   * What {@code config}'s template is built from on this box: the base profile's pool ({@code
   * host.yaml}'s, the default when it names none), the definition's digest, the catalog's names,
   * and a build that asks first whether a repo needs a token none is stored for, then runs the
   * project's own provisioner against the template with its own progress file, with the box
   * person's stored tokens for its clone and no git identity and no trusted key of anyone's.
   */
  static WorkspaceTemplate.Source source(
      ProjectDefinition config, boolean dryRun, boolean json, OperatorCommand operator)
      throws IOException {
    var host = HostYaml.fromMap(YamlUtil.parseFile(SailPaths.hostConfigPath()));
    var catalog = ProjectCatalog.here();
    var tokens = boxTokens(config);
    return source(
        host,
        config,
        tokens.values(),
        name -> catalog.read(name).isPresent(),
        checked(
            config,
            tokens,
            new ShellExecutor(false)::asksForCredentials,
            (into, fresh) -> {
              catalog.requireUnpruned(config.name());
              var tracker =
                  tracker(
                      progressOf(config.name()),
                      ProjectProvisioner.inputs(config, Optional.empty()),
                      fresh,
                      dryRun);
              var listener = json ? ProvisionListener.NOOP : ConsoleProvisionListener.INSTANCE;
              try (var operations = OperationsFactory.open()) {
                new ProjectProvisioner(
                        new ShellExecutor(dryRun),
                        tracker,
                        listener,
                        new ProjectSkills(operations::projectFiles),
                        Optional.empty(),
                        operator)
                    .provision(
                        into, config, host, tokens, null, SailPaths.projectFilesDir(config.name()));
              }
            }));
  }

  /**
   * A template build that asks first whether a repo needs a token none is stored for ({@link
   * #requireTokens}), then {@code provision}s.
   */
  static WorkspaceTemplate.Build checked(
      ProjectDefinition config,
      Map<String, String> tokens,
      Predicate<String> asks,
      WorkspaceTemplate.Build provision) {
    return (into, fresh) -> {
      requireTokens(config, tokens, asks, System.getenv());
      provision.into(into, fresh);
    };
  }

  /**
   * {@code config}'s template source on a box whose {@code host.yaml} is {@code host}: its digest
   * is the definition's alone (T4), since a template holds no one's key.
   */
  static WorkspaceTemplate.Source source(
      HostYaml host,
      ProjectDefinition config,
      Collection<String> tokens,
      Predicate<String> held,
      WorkspaceTemplate.Build build) {
    return new WorkspaceTemplate.Source(
        Objects.requireNonNullElse(host.pool(), HostYaml.DEFAULT_POOL),
        ProjectProvisioner.inputs(config, Optional.empty()),
        held,
        build,
        tokens);
  }

  /** What a template build says it did. */
  static String templateLine(WorkspaceTemplate.Template template) {
    return template.built()
        ? "Built "
            + template.project()
            + "'s template "
            + template.version()
            + " on "
            + template.pool()
            + " ("
            + template.instance()
            + ")."
        : template.project()
            + "'s template "
            + template.version()
            + " is current: it was built from the definition as it is, so nothing was done.";
  }
}
