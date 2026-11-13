/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.commands;

import ai.singlr.sail.api.OperationsFactory;
import ai.singlr.sail.api.ProjectSkills;
import ai.singlr.sail.common.Remedied;
import ai.singlr.sail.common.Remedy;
import ai.singlr.sail.config.HostYaml;
import ai.singlr.sail.config.ProjectDefinition;
import ai.singlr.sail.config.YamlUtil;
import ai.singlr.sail.engine.ContainerSailSetup;
import ai.singlr.sail.engine.GitCredentials;
import ai.singlr.sail.engine.LocalIdentity;
import ai.singlr.sail.engine.NameValidator;
import ai.singlr.sail.engine.OperatorCommand;
import ai.singlr.sail.engine.PairingTrust;
import ai.singlr.sail.engine.ProjectCatalog;
import ai.singlr.sail.engine.ProjectPhase;
import ai.singlr.sail.engine.ProjectProvisioner;
import ai.singlr.sail.engine.ProvisionListener;
import ai.singlr.sail.engine.ProvisionTracker;
import ai.singlr.sail.engine.SailPaths;
import ai.singlr.sail.engine.ShellExecutor;
import ai.singlr.sail.engine.WorkspaceTemplate;
import ai.singlr.sail.engine.Workspaces;
import ai.singlr.sail.engine.WorkstationIdentity;
import ai.singlr.sail.ssh.SshPublicKey;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Predicate;
import java.util.function.ToIntFunction;
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
    return new Workspaces(
        new ShellExecutor(false),
        new ShellExecutor(dryRun),
        System::nanoTime,
        SailPaths.locksDir());
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
   * Refuses, before anything is provisioned, a template whose https repos ask for credentials that
   * {@code tokens} has none of, naming the flag that gives one.
   */
  static void requireTokens(
      ProjectDefinition config, Map<String, String> tokens, ToIntFunction<String> status) {
    var asking = GitCredentials.askingForAToken(config.repos(), tokens, status);
    if (!asking.isEmpty()) {
      throw Remedied.argument(
          "The template cannot clone "
              + String.join(", ", asking)
              + ": "
              + (asking.size() == 1 ? "it asks" : "they ask")
              + " for credentials and no token was given for "
              + (asking.size() == 1 ? "its" : "their")
              + " host.",
          Remedy.of(
              "Pass one with --git-token <token>, or keep GITHUB_TOKEN through sudo with sudo -E."));
    }
  }

  /**
   * What {@code config}'s template is built from on this box: the base profile's pool ({@code
   * host.yaml}'s, the default when it names none), the digest of the definition and the workstation
   * key, the catalog's names, and a build that, under the box's pairing lock, runs the project's
   * own provisioner against the template with its own progress file.
   */
  static WorkspaceTemplate.Source source(
      ProjectDefinition config,
      String gitToken,
      boolean dryRun,
      boolean json,
      OperatorCommand operator)
      throws IOException {
    var host = HostYaml.fromMap(YamlUtil.parseFile(SailPaths.hostConfigPath()));
    var key = WorkstationIdentity.registered();
    var catalog = ProjectCatalog.here();
    return source(
        host,
        config,
        key,
        name -> catalog.read(name).isPresent(),
        (into, fresh) -> {
          try (var held = dryRun ? null : PairingTrust.hold(SailPaths.controlPlaneDb())) {
            catalog.requireUnpruned(config.name());
            var tokens = GitCredentials.known(config.repos(), gitToken);
            requireTokens(config, tokens, GitCredentials::httpStatus);
            var tracker =
                tracker(
                    progressOf(config.name()),
                    ProjectProvisioner.inputs(config, WorkstationIdentity.registered()),
                    fresh,
                    dryRun);
            var listener = json ? ProvisionListener.NOOP : ConsoleProvisionListener.INSTANCE;
            try (var operations = OperationsFactory.open()) {
              new ProjectProvisioner(
                      new ShellExecutor(dryRun),
                      tracker,
                      listener,
                      new ProjectSkills(operations::projectFiles),
                      WorkstationIdentity.registered(),
                      operator)
                  .provision(
                      into,
                      config,
                      host,
                      tokens,
                      LocalIdentity.detect().git(),
                      SailPaths.projectFilesDir(config.name()));
            }
          }
        });
  }

  /** {@code config}'s template source on a box whose {@code host.yaml} is {@code host}. */
  static WorkspaceTemplate.Source source(
      HostYaml host,
      ProjectDefinition config,
      Optional<SshPublicKey> key,
      Predicate<String> held,
      WorkspaceTemplate.Build build) {
    return new WorkspaceTemplate.Source(
        Objects.requireNonNullElse(host.pool(), HostYaml.DEFAULT_POOL),
        ProjectProvisioner.inputs(config, key),
        held,
        build);
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
