/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.commands;

import ai.singlr.sail.engine.Banner;
import ai.singlr.sail.engine.OperatorCommand;
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
      WorkspaceVerbs.requireRoot(dryRun, spec);
      CliCommand.requireJsonOrDryRun(json, dryRun);
      var config = WorkspaceVerbs.buildable(project, operator);
      if (!json) {
        Banner.printBranding(System.out, Ansi.AUTO);
        System.out.println();
      }
      var template =
          WorkspaceVerbs.templates(dryRun)
              .ensure(config, WorkspaceVerbs.source(config, gitToken, dryRun, json, operator));
      if (json) {
        System.out.println(CliJson.stringify(template));
        return;
      }
      System.out.println(
          Ansi.AUTO.string("  @|bold,green ✓|@ " + WorkspaceVerbs.templateLine(template)));
    }
  }
}
