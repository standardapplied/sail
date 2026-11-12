/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.commands;

import ai.singlr.sail.engine.Banner;
import ai.singlr.sail.engine.OperatorCommand;
import ai.singlr.sail.engine.PairingTrust;
import ai.singlr.sail.engine.SailPaths;
import ai.singlr.sail.engine.Workspaces;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Help.Ansi;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Spec;

/**
 * A project's workspaces on this box: clones of its template, each its own container with its own
 * tree, services and ports. The project's own container is its default workspace, {@code main},
 * which these verbs list and otherwise leave alone. Every verb first reads the project from the
 * catalog as the box's person, as {@code project apply} does.
 */
@Command(
    name = "workspace",
    description = "Run several workspaces of a project on this box, each a clone of its template.",
    mixinStandardHelpOptions = true,
    subcommands = {
      ProjectWorkspaceCommand.Create.class,
      ProjectWorkspaceCommand.ListWorkspaces.class,
      ProjectWorkspaceCommand.Shell.class,
      ProjectWorkspaceCommand.Start.class,
      ProjectWorkspaceCommand.Stop.class,
      ProjectWorkspaceCommand.Delete.class,
    })
public final class ProjectWorkspaceCommand implements Runnable {

  @Override
  public void run() {
    CommandLine.usage(this, System.out);
  }

  private static OperatorCommand operator() {
    return OperatorCommand.here();
  }

  /** {@code sail project workspace create <project> <name>}. */
  @Command(
      name = "create",
      description =
          "Create a workspace: a clone of the project's template, built first when it is missing or"
              + " stale, with every repo on a branch named after the workspace.",
      mixinStandardHelpOptions = true)
  public static final class Create implements Runnable {

    @Spec private CommandSpec spec;

    @Parameters(index = "0", description = "Project name, as the catalog holds it.")
    private String project;

    @Parameters(index = "1", description = "Workspace name, which is also its branch.")
    private String name;

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
      var operator = operator();
      ProjectTemplateCommand.requireOneOutput(json, dryRun);
      var config = ProjectTemplateCommand.buildable(project, operator);
      if (!json) {
        Banner.printBranding(System.out, Ansi.AUTO);
        System.out.println();
      }
      try (var held = dryRun ? null : PairingTrust.hold(SailPaths.controlPlaneDb())) {
        var created =
            ProjectTemplateCommand.workspaces(dryRun)
                .create(
                    config,
                    name,
                    ProjectTemplateCommand.source(config, gitToken, dryRun, json, operator, spec),
                    SailPaths.projectFilesDir(config.name()));
        if (json) {
          System.out.println(CliJson.stringify(created));
          return;
        }
        if (created.template().built()) {
          System.out.println(
              Ansi.AUTO.string(
                  "  @|bold,green ✓|@ " + ProjectTemplateCommand.templateLine(created.template())));
        }
        System.out.println(Ansi.AUTO.string("  @|bold,green ✓|@ " + createdLine(created)));
        System.out.println(
            "    Open a shell in it: "
                + operator.sail("project workspace shell " + project + " " + name));
      }
    }
  }

  /** {@code sail project workspace list <project>}. */
  @Command(
      name = "list",
      description = "List the project's workspaces on this box, its own container first.",
      mixinStandardHelpOptions = true)
  public static final class ListWorkspaces implements Runnable {

    @Parameters(index = "0", description = "Project name, as the catalog holds it.")
    private String project;

    @Option(names = "--json", description = "Output in JSON format.")
    private boolean json;

    @Override
    public void run() {
      CliCommand.run(this::execute);
    }

    private void execute() throws Exception {
      ProjectTemplateCommand.readable(project, operator());
      var listing = ProjectTemplateCommand.workspaces(false).list(project);
      if (json) {
        System.out.println(CliJson.stringify(listing));
        return;
      }
      Banner.printBranding(System.out, Ansi.AUTO);
      System.out.println();
      lines(listing).forEach(System.out::println);
    }
  }

  /** {@code sail project workspace shell <project> <name>}. */
  @Command(
      name = "shell",
      description = "Open an interactive shell inside a workspace, as 'project shell' does.",
      mixinStandardHelpOptions = true)
  public static final class Shell implements Runnable {

    @Parameters(index = "0", description = "Project name, as the catalog holds it.")
    private String project;

    @Parameters(index = "1", description = "Workspace name.")
    private String name;

    @Override
    public void run() {
      CliCommand.run(this::execute);
    }

    private void execute() throws Exception {
      ProjectTemplateCommand.readable(project, operator());
      ShellCommand.open(ProjectTemplateCommand.workspaces(false).running(project, name));
    }
  }

  /** {@code sail project workspace start <project> <name>}. */
  @Command(
      name = "start",
      description = "Start a stopped or frozen workspace, and say how long it took to be ready.",
      mixinStandardHelpOptions = true)
  public static final class Start implements Runnable {

    @Mixin private Target target;

    @Override
    public void run() {
      target.change(Workspaces::start);
    }
  }

  /** {@code sail project workspace stop <project> <name>}. */
  @Command(name = "stop", description = "Stop a workspace.", mixinStandardHelpOptions = true)
  public static final class Stop implements Runnable {

    @Mixin private Target target;

    @Override
    public void run() {
      target.change(Workspaces::stop);
    }
  }

  /** {@code sail project workspace delete <project> <name>}. */
  @Command(
      name = "delete",
      description = "Delete a workspace and everything in it; the default workspace is refused.",
      mixinStandardHelpOptions = true)
  public static final class Delete implements Runnable {

    @Mixin private Target target;

    @Override
    public void run() {
      target.change(Workspaces::delete);
    }
  }

  /** What a lifecycle verb does to the workspace it names. */
  @FunctionalInterface
  interface Verb {
    Workspaces.Changed apply(Workspaces workspaces, String project, String name) throws Exception;
  }

  /** The workspace a lifecycle verb names, and how it says what it did. */
  public static final class Target {

    @Parameters(index = "0", description = "Project name, as the catalog holds it.")
    private String project;

    @Parameters(index = "1", description = "Workspace name.")
    private String name;

    @Option(names = "--json", description = "Output in JSON format.")
    private boolean json;

    @Option(names = "--dry-run", description = "Print commands instead of executing them.")
    private boolean dryRun;

    void change(Verb verb) {
      CliCommand.run(
          () -> {
            ProjectTemplateCommand.requireOneOutput(json, dryRun);
            ProjectTemplateCommand.readable(project, operator());
            var changed = verb.apply(ProjectTemplateCommand.workspaces(dryRun), project, name);
            if (json) {
              System.out.println(CliJson.stringify(changed));
              return;
            }
            System.out.println(
                dryRun
                    ? "  Dry run: nothing was changed."
                    : Ansi.AUTO.string("  @|bold,green ✓|@ " + changedLine(changed)));
          });
    }
  }

  /**
   * What a create says: {@code Created fix-login from v1 in 1.4 s (clone 0.2 s, start 0.9 s, ready
   * 0.3 s)}.
   */
  static String createdLine(Workspaces.Created created) {
    return "Created "
        + created.workspace()
        + " from "
        + created.template().version()
        + " in "
        + seconds(created.timings().totalMs())
        + " (clone "
        + seconds(created.timings().cloneMs())
        + ", start "
        + seconds(created.timings().startMs())
        + ", ready "
        + seconds(created.timings().readyMs())
        + ")";
  }

  /** What a start, stop or delete says it did. */
  static String changedLine(Workspaces.Changed changed) {
    return switch (changed.change()) {
      case STARTED ->
          "Started "
              + changed.workspace()
              + " in "
              + seconds(changed.timings().totalMs())
              + " (start "
              + seconds(changed.timings().startMs())
              + ", ready "
              + seconds(changed.timings().readyMs())
              + ")";
      case ALREADY_RUNNING -> changed.workspace() + " is already running; nothing was done.";
      case STOPPED -> "Stopped " + changed.workspace() + ".";
      case ALREADY_STOPPED -> changed.workspace() + " is already stopped; nothing was done.";
      case DELETED -> "Deleted " + changed.workspace() + " (" + changed.instance() + ").";
    };
  }

  /** A listing as the table {@code workspace list} prints. */
  static List<String> lines(Workspaces.Listing listing) {
    var format = "  %-24s %-11s %-9s %-24s %s";
    var lines = new ArrayList<String>();
    lines.add(String.format(Locale.ROOT, format, "NAME", "STATE", "TEMPLATE", "CREATED", "IPV4"));
    for (var row : listing.workspaces()) {
      lines.add(
          String.format(
              Locale.ROOT,
              format,
              row.name(),
              row.state(),
              Objects.requireNonNullElse(row.template(), "-"),
              Objects.requireNonNullElse(row.createdAt(), "-"),
              Objects.requireNonNullElse(row.ipv4(), "-")));
    }
    return lines;
  }

  private static String seconds(long millis) {
    return String.format(Locale.ROOT, "%.1f s", millis / 1000.0);
  }
}
