/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.commands;

import ai.singlr.sail.api.OperationsFactory;
import ai.singlr.sail.api.ProjectSkills;
import ai.singlr.sail.config.HostYaml;
import ai.singlr.sail.config.ProjectDefinition;
import ai.singlr.sail.config.YamlUtil;
import ai.singlr.sail.engine.Banner;
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
import ai.singlr.sail.engine.Workspaces;
import ai.singlr.sail.engine.WorkstationIdentity;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Objects;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Help.Ansi;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Spec;

/**
 * A project's template: the stopped instance {@code <project>--tmpl} its own provisioner builds on
 * the box's ZFS pool, one snapshot per version, which every workspace of the project is cloned
 * from.
 */
@Command(
    name = "template",
    description = "Build a project's template, the image its workspaces are cloned from.",
    mixinStandardHelpOptions = true,
    subcommands = {ProjectTemplateCommand.Build.class})
public final class ProjectTemplateCommand implements Runnable {

  @Override
  public void run() {
    CommandLine.usage(this, System.out);
  }

  /** {@code sail project template build <project>}. */
  @Command(
      name = "build",
      description =
          "Build the project's template on the box's ZFS pool, unless its current version was"
              + " built from the definition as it is.",
      mixinStandardHelpOptions = true)
  public static final class Build implements Runnable {

    @Spec private CommandSpec spec;

    @Parameters(index = "0", description = "Project name, as the catalog holds it.")
    private String project;

    @Option(names = "--json", description = "Output in JSON format.")
    private boolean json;

    @Option(names = "--dry-run", description = "Print commands instead of executing them.")
    private boolean dryRun;

    @Option(
        names = "--git-token",
        description = "Access token for cloning private repos over HTTPS.",
        defaultValue = "${GITHUB_TOKEN}")
    private String gitToken;

    @Override
    public void run() {
      CliCommand.run(this::execute);
    }

    private void execute() throws Exception {
      var operator = OperatorCommand.here();
      requireOneOutput(json, dryRun);
      var config = buildable(project, operator);
      if (!json) {
        Banner.printBranding(System.out, Ansi.AUTO);
        System.out.println();
      }
      try (var held = dryRun ? null : PairingTrust.hold(SailPaths.controlPlaneDb())) {
        var template =
            workspaces(dryRun)
                .template(config, source(config, gitToken, dryRun, json, operator, spec));
        if (json) {
          System.out.println(CliJson.stringify(template));
          return;
        }
        System.out.println(Ansi.AUTO.string("  @|bold,green ✓|@ " + templateLine(template)));
      }
    }
  }

  /** What a template build says it did. */
  static String templateLine(Workspaces.Template template) {
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

  /** Refuses {@code --json} with {@code --dry-run}, whose narration would corrupt the document. */
  static void requireOneOutput(boolean json, boolean dryRun) {
    if (json && dryRun) {
      throw new IllegalArgumentException(
          "--json and --dry-run cannot be combined: dry-run narrates the underlying shell commands"
              + " on stdout, which would corrupt the JSON document. Drop one of the two flags.");
    }
  }

  /**
   * Project {@code name}'s definition, read from the catalog as the box's person reads it, which is
   * what {@code project apply} asks: a project outside their scope reads as absent.
   */
  static ProjectDefinition readable(String name, OperatorCommand operator) {
    NameValidator.requireValidProjectName(name);
    return ProjectCatalog.here()
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

  /** The workspaces of this box, reading Incus for real and changing it unless {@code dryRun}. */
  static Workspaces workspaces(boolean dryRun) {
    return new Workspaces(new ShellExecutor(false), new ShellExecutor(dryRun), System::nanoTime);
  }

  /**
   * What {@code config}'s template is built from on this box: the base profile's pool, the digest
   * of the definition and the workstation key, the catalog's names, and a build that runs the
   * project's own provisioner against the template, as root, with its own progress file.
   */
  static Workspaces.Source source(
      ProjectDefinition config,
      String gitToken,
      boolean dryRun,
      boolean json,
      OperatorCommand operator,
      CommandSpec spec)
      throws IOException {
    var host = HostYaml.fromMap(YamlUtil.parseFile(SailPaths.hostConfigPath()));
    var key = WorkstationIdentity.registered();
    var inputs = ProjectProvisioner.inputs(config, key);
    var catalog = ProjectCatalog.here();
    return new Workspaces.Source(
        Objects.requireNonNullElse(host.pool(), HostYaml.DEFAULT_POOL),
        inputs,
        name -> catalog.read(name).isPresent(),
        (into, fresh) -> {
          ProjectApplyCommand.requireRootToProvision(
              config.name(), dryRun, ConsoleHelper.isRoot(), spec);
          catalog.requireUnpruned(config.name());
          var tracker =
              new ProvisionTracker<>(
                      ProjectPhase.class, SailPaths.templateProvisionState(config.name()), dryRun)
                  .load(inputs);
          if (fresh || tracker.isComplete()) {
            tracker.reset();
          }
          var listener = json ? ProvisionListener.NOOP : ConsoleProvisionListener.INSTANCE;
          try (var operations = OperationsFactory.open()) {
            new ProjectProvisioner(
                    new ShellExecutor(dryRun),
                    tracker,
                    listener,
                    new ProjectSkills(operations::projectFiles),
                    key,
                    operator)
                .provision(
                    into,
                    config,
                    host,
                    GitCredentials.known(config.repos(), gitToken),
                    LocalIdentity.detect().git(),
                    SailPaths.projectFilesDir(config.name()));
          }
        });
  }
}
