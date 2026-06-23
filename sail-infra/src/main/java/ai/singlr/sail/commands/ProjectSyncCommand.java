/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.commands;

import ai.singlr.sail.common.Strings;
import ai.singlr.sail.config.SailYaml;
import ai.singlr.sail.config.YamlUtil;
import ai.singlr.sail.engine.AgentContextInstaller;
import ai.singlr.sail.engine.Banner;
import ai.singlr.sail.engine.ContainerManager;
import ai.singlr.sail.engine.ContainerSailSetup;
import ai.singlr.sail.engine.NameValidator;
import ai.singlr.sail.engine.ProjectDefinitions;
import ai.singlr.sail.engine.ShellExecutor;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeoutException;
import picocli.CommandLine.Command;
import picocli.CommandLine.Help.Ansi;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Spec;

/**
 * Brings an existing project container fully up to date with the current sail: the in-container
 * machinery — the {@code sail-api} event socket, the {@code spec} CLI, and the Claude/Codex agent
 * hooks (delegated to {@link ContainerSailSetup}) — plus the regenerated agent context, including
 * the database-backed spec-board skill ({@link AgentContextInstaller}). This is the one command an
 * operator runs after {@code sail upgrade} to retrofit containers provisioned by an older release.
 * Idempotent: a container already current is left untouched.
 */
@Command(
    name = "reconfigure",
    aliases = "sync",
    description =
        "Bring existing project containers fully up to date: the in-container sail machinery"
            + " (event socket, spec CLI, agent hooks) and the regenerated agent context."
            + " Idempotent.",
    mixinStandardHelpOptions = true)
public final class ProjectSyncCommand implements Runnable {

  @Parameters(index = "0", arity = "0..1", description = "Project name. Omit when --all is set.")
  private String name;

  @Option(names = "--all", description = "Reconfigure every existing project.")
  private boolean all;

  @Option(names = "--dry-run", description = "Print commands instead of executing them.")
  private boolean dryRun;

  @Option(names = "--json", description = "Output in JSON format.")
  private boolean json;

  @Option(
      names = "--force",
      description =
          "When regenerating context, overwrite engineer-owned files (CLAUDE.md, AGENTS.md,"
              + " SECURITY.md) instead of keeping them.")
  private boolean force;

  @Spec private CommandSpec spec;

  @Override
  public void run() {
    CliCommand.run(spec, this::execute);
  }

  private void execute() throws Exception {
    if (!all && Strings.isBlank(name)) {
      throw new IllegalArgumentException(
          "Provide a project name or pass --all to reconfigure every project.");
    }
    if (all && Strings.isNotBlank(name)) {
      throw new IllegalArgumentException("Pass a project name OR --all, not both.");
    }

    var shell = new ShellExecutor(dryRun);
    var containers = new ContainerManager(shell);

    List<String> targets;
    if (all) {
      targets = containers.listAll().stream().map(ContainerManager.ContainerInfo::name).toList();
    } else {
      NameValidator.requireValidProjectName(name);
      targets = List.of(name);
    }

    if (!json) {
      Banner.printBranding(System.out, Ansi.AUTO);
      System.out.println();
      var scope = all ? "all projects" : name;
      var mode = dryRun ? " (dry run)" : "";
      System.out.println(Ansi.AUTO.string("  @|bold Reconfiguring|@ " + scope + mode));
      System.out.println();
    }

    var rows = new ArrayList<LinkedHashMap<String, Object>>();
    var backfilled = 0;
    var alreadyCurrent = 0;
    var failed = 0;

    for (var project : targets) {
      try {
        var setup = ContainerSailSetup.ensureInstalled(shell, project);
        var context = regenerateContext(shell, project);
        if (setup == ContainerSailSetup.Result.BACKFILLED) {
          backfilled++;
        } else {
          alreadyCurrent++;
        }
        if (json) {
          rows.add(jsonRow(project, setup, context));
        } else {
          System.out.println(humanLine(project, setup, context));
        }
      } catch (Exception e) {
        failed++;
        if (json) {
          var row = new LinkedHashMap<String, Object>();
          row.put("project", project);
          row.put("error", e.getMessage());
          rows.add(row);
        } else {
          System.err.println(
              Banner.errorLine(
                  "Could not reconfigure " + project + ": " + e.getMessage(), Ansi.AUTO));
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
            "  @|bold,green ✓|@ Backfilled "
                + backfilled
                + ", already current "
                + alreadyCurrent
                + (failed > 0 ? ", failed " + failed : "")
                + "."));
  }

  /**
   * Regenerates the agent context for a project, loading its definition database-first. Returns
   * {@code null} when the container has no catalogued or on-disk descriptor — a non-project
   * container caught by {@code --all} still gets its machinery reconciled, it just has no context
   * to regenerate.
   */
  private AgentContextInstaller.Result regenerateContext(ShellExecutor shell, String project)
      throws IOException, InterruptedException, TimeoutException {
    SailYaml config;
    try {
      config = ProjectDefinitions.load(project, null);
    } catch (IllegalStateException noDescriptor) {
      return null;
    }
    return AgentContextInstaller.install(shell, project, config, force);
  }

  static String humanLine(
      String project, ContainerSailSetup.Result setup, AgentContextInstaller.Result context) {
    var setupLabel =
        setup == ContainerSailSetup.Result.BACKFILLED ? "backfilled" : "already current";
    return Ansi.AUTO.string(
        "  @|green ✓|@ " + project + " — setup: " + setupLabel + ", " + contextLabel(context));
  }

  static String contextLabel(AgentContextInstaller.Result context) {
    if (context == null) {
      return "context: skipped (no descriptor in catalog)";
    }
    if (context.isEmpty()) {
      return "context: none (no agent configured)";
    }
    var count = context.pushed().size();
    return "context: " + count + (count == 1 ? " file regenerated" : " files regenerated");
  }

  static LinkedHashMap<String, Object> jsonRow(
      String project, ContainerSailSetup.Result setup, AgentContextInstaller.Result context) {
    var row = new LinkedHashMap<String, Object>();
    row.put("project", project);
    row.put("setup", setup.name().toLowerCase(Locale.ROOT));
    if (context == null) {
      row.put("context", "no_descriptor");
    } else {
      row.put("context_pushed", context.pushed());
    }
    return row;
  }
}
