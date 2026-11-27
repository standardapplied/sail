/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.commands;

import ai.singlr.sail.api.HostOperations;
import ai.singlr.sail.api.OperationsFactory;
import ai.singlr.sail.api.ProjectSkills;
import ai.singlr.sail.api.SessionYield;
import ai.singlr.sail.api.WorkspaceDoors;
import ai.singlr.sail.common.DateTimeUtils;
import ai.singlr.sail.common.Remedied;
import ai.singlr.sail.common.Remedy;
import ai.singlr.sail.config.HostYaml;
import ai.singlr.sail.config.ProjectDefinition;
import ai.singlr.sail.config.YamlUtil;
import ai.singlr.sail.engine.CheckedTokens;
import ai.singlr.sail.engine.ContainerSailSetup;
import ai.singlr.sail.engine.NameValidator;
import ai.singlr.sail.engine.OperatorCommand;
import ai.singlr.sail.engine.ProjectCatalog;
import ai.singlr.sail.engine.ProvisionListener;
import ai.singlr.sail.engine.RunPlace;
import ai.singlr.sail.engine.SailPaths;
import ai.singlr.sail.engine.ShellExecutor;
import ai.singlr.sail.engine.TemplateBuild;
import ai.singlr.sail.engine.WorkspaceOwner;
import ai.singlr.sail.engine.WorkspaceTemplate;
import ai.singlr.sail.engine.Workspaces;
import ai.singlr.sail.identity.Actor;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
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
   * resources and {@code host init} has run, refused in the words the route's are ({@link
   * TemplateBuild#requireResources}, {@link TemplateBuild#hostOf}), and sail-api has, since every
   * container mounts its socket.
   */
  static ProjectDefinition buildable(String name, OperatorCommand operator) throws IOException {
    var config = TemplateBuild.requireResources(readable(name, operator));
    TemplateBuild.hostOf(SailPaths.hostConfigPath());
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
   * {@code door} over this box's workspace doors ({@link WorkspaceDoors}), asked as this box's
   * operator, so each verb is decided as Enter a workspace on the workspace's owner, a dry run
   * ({@code dryRun}) too, which changes nothing and ends no terminal.
   */
  static <T> T entering(boolean dryRun, Door<T> door) throws Exception {
    var doors =
        new WorkspaceDoors(
            workspaces(dryRun),
            new ShellExecutor(dryRun),
            WorkspaceVerbs::ownerOf,
            dryRun ? SessionYield.NONE : new PtyHostYield(),
            dryRun ? (project, name) -> {} : WorkspaceVerbs::forgetChats,
            keeper());
    return Actor.call(SailCommandLine.operator(), () -> door.through(doors));
  }

  /** Erases the chats of workspace {@code name} of {@code project}, which this box deleted. */
  static void forgetChats(String project, String name) {
    try (var operations = OperationsFactory.open()) {
      operations.chats().forgetWorkspace(project, name);
    }
  }

  /** Erases every chat of {@code project}, whose workspaces this box destroyed. */
  static void forgetProjectChats(String project) {
    try (var operations = OperationsFactory.open()) {
      operations.chats().forgetProject(project);
    }
  }

  /** This box's own person when it is a node, who keeps the workspaces nobody owns; else null. */
  static String keeper() {
    var sync = HostSync.config();
    return HostSync.isNode(sync) ? sync.handle() : null;
  }

  /** What a verb does through the workspace doors. */
  @FunctionalInterface
  interface Door<T> {
    T through(WorkspaceDoors doors) throws Exception;
  }

  /** Refuses a create of workspace {@code name} of {@code project} its operator may not make. */
  static void requireMaking(String project, String name) throws Exception {
    var actor = SailCommandLine.operator();
    WorkspaceDoors.requireEnter(actor, project, name, actor.handle(), keeper());
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
   * This box's person, by handle, and the git tokens they stored: whose a host command clones with
   * ({@code HostIdentity.gitTokens}). No person when the box names none on its roster. It asks a
   * repo's host whether it wants credentials through {@code asks}, in {@code env}: a command hands
   * it a definition, never a probe of its own.
   */
  record Keeper(
      Optional<String> person,
      Map<String, String> tokens,
      Predicate<String> asks,
      Map<String, String> env) {

    /** With the real probe ({@link ShellExecutor#asksForCredentials}) and this process's env. */
    Keeper(Optional<String> person, Map<String, String> tokens) {
      this(person, tokens, new ShellExecutor(false)::asksForCredentials, System.getenv());
    }

    static Keeper here() {
      try (var operations = OperationsFactory.open()) {
        var identity = operations.identity();
        return identity
            .gitTokens()
            .map(tokens -> new Keeper(Optional.of(identity.box().handle()), tokens))
            .orElseGet(() -> new Keeper(Optional.empty(), Map.of()));
      }
    }

    /**
     * The tokens a host command may write for {@code config}'s repos, or the refusal ({@link
     * CheckedTokens#of}): the one way one has them.
     */
    CheckedTokens check(ProjectDefinition config) {
      return CheckedTokens.of(config, person, tokens, asks, env);
    }

    /**
     * Who a build from this box's command line is, as its record says: the person whose tokens it
     * clones with, else the account that ran it, the one {@code sudo} acts for first.
     */
    String who() {
      return person.orElseGet(
          () -> Objects.requireNonNullElse(env.get("SUDO_USER"), System.getProperty("user.name")));
    }
  }

  /**
   * What {@code config}'s template is built from on this box: the base profile's pool ({@code
   * host.yaml}'s, the default when it names none), the definition's digest, the catalog's names,
   * and a build that runs the project's own provisioner against the template with its own progress
   * file, with {@code tokens} for its clone and no git identity and no trusted key of anyone's,
   * keeping the record of the build ({@link TemplateBuild}) as the route's does.
   */
  static WorkspaceTemplate.Source source(
      ProjectDefinition config,
      CheckedTokens tokens,
      String by,
      boolean dryRun,
      boolean json,
      OperatorCommand operator)
      throws IOException {
    var host = HostYaml.fromMap(YamlUtil.parseFile(SailPaths.hostConfigPath()));
    var catalog = ProjectCatalog.here();
    return source(
        host,
        config,
        tokens,
        name -> catalog.read(name).isPresent(),
        by,
        json ? ProvisionListener.NOOP : ConsoleProvisionListener.INSTANCE,
        (into, tracker, listener) -> {
          catalog.requireUnpruned(config.name());
          try (var operations = OperationsFactory.open()) {
            TemplateBuild.provision(
                    new ShellExecutor(dryRun),
                    host,
                    config,
                    tokens,
                    new ProjectSkills(operations::projectFiles),
                    operator)
                .into(into, tracker, listener);
          }
        },
        dryRun);
  }

  /**
   * {@code config}'s template source on a box whose {@code host.yaml} is {@code host}, built by
   * {@code by}: its digest is the definition's alone (T4), since a template holds no one's key, and
   * the built template is searched for the {@code tokens} its build was given alone, the ones
   * already in it: a token for a host this build never used never enters the template, not even as
   * a search pattern.
   */
  static WorkspaceTemplate.Source source(
      HostYaml host,
      ProjectDefinition config,
      CheckedTokens tokens,
      Predicate<String> held,
      String by,
      ProvisionListener listener,
      TemplateBuild.Provision provision,
      boolean dryRun) {
    return new TemplateBuild(SailPaths.locksDir(), SailPaths.projectsDir(), DateTimeUtils::now)
        .source(
            config,
            TemplateBuild.poolOf(host),
            held,
            tokens.values(),
            progressOf(config.name()),
            by,
            listener,
            provision,
            () -> {},
            dryRun);
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
