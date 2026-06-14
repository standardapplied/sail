/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.commands;

import ai.singlr.sail.common.Strings;
import ai.singlr.sail.config.YamlUtil;
import ai.singlr.sail.engine.Banner;
import ai.singlr.sail.engine.ClaudeCodeHookConfig;
import ai.singlr.sail.engine.CodexHookConfig;
import ai.singlr.sail.engine.ContainerManager;
import ai.singlr.sail.engine.IncusDeviceManager;
import ai.singlr.sail.engine.NameValidator;
import ai.singlr.sail.engine.SailEventHelper;
import ai.singlr.sail.engine.SailPaths;
import ai.singlr.sail.engine.ShellExecutor;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import picocli.CommandLine.Command;
import picocli.CommandLine.Help.Ansi;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Spec;

@Command(
    name = "sync",
    description =
        "Backfill host-side configuration (currently: sail-api event socket) onto existing"
            + " project containers. Idempotent.",
    mixinStandardHelpOptions = true)
public final class ProjectSyncCommand implements Runnable {

  @Parameters(index = "0", arity = "0..1", description = "Project name. Omit when --all is set.")
  private String name;

  @Option(names = "--all", description = "Sync every existing project.")
  private boolean all;

  @Option(names = "--dry-run", description = "Print commands instead of executing them.")
  private boolean dryRun;

  @Option(names = "--json", description = "Output in JSON format.")
  private boolean json;

  @Spec private CommandSpec spec;

  @Override
  public void run() {
    CliCommand.run(spec, this::execute);
  }

  private void execute() throws Exception {
    if (!all && (Strings.isBlank(name))) {
      throw new IllegalArgumentException(
          "Provide a project name or pass --all to sync every project.");
    }
    if (all && Strings.isNotBlank(name)) {
      throw new IllegalArgumentException("Pass a project name OR --all, not both.");
    }

    var shell = new ShellExecutor(dryRun);
    var containers = new ContainerManager(shell);
    var devices = new IncusDeviceManager(shell);
    var helper = new SailEventHelper(shell);
    var claudeHooks = new ClaudeCodeHookConfig(shell);
    var codexHooks = new CodexHookConfig(shell);

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
      System.out.println(Ansi.AUTO.string("  @|bold Syncing|@ " + scope + mode));
      System.out.println();
    }

    var hostSocket = SailPaths.apiSocketHostDir();
    var containerSocket = SailPaths.apiSocketContainerDir();
    var results = new ArrayList<LinkedHashMap<String, Object>>();
    var added = 0;
    var replaced = 0;
    var alreadyPresent = 0;

    for (var project : targets) {
      try {
        var result = devices.ensureEventSocket(project, hostSocket, containerSocket);
        helper.install(project);
        claudeHooks.install(project);
        codexHooks.install(project);
        switch (result) {
          case ADDED -> added++;
          case REPLACED -> replaced++;
          case ALREADY_PRESENT -> alreadyPresent++;
        }
        if (!json) {
          System.out.println(
              Ansi.AUTO.string(
                  "  @|green ✓|@ "
                      + project
                      + " — event socket: "
                      + result.name().toLowerCase()
                      + ", sail-event.sh: installed, claude-settings.json: installed,"
                      + " codex hooks.json: installed"));
        } else {
          var row = new LinkedHashMap<String, Object>();
          row.put("project", project);
          row.put("event_socket", result.name().toLowerCase());
          row.put("sail_event_helper", "installed");
          row.put("claude_settings", "installed");
          row.put("codex_hooks", "installed");
          results.add(row);
        }
      } catch (Exception e) {
        if (!json) {
          System.err.println(
              Banner.errorLine("Could not sync " + project + ": " + e.getMessage(), Ansi.AUTO));
        } else {
          var row = new LinkedHashMap<String, Object>();
          row.put("project", project);
          row.put("error", e.getMessage());
          results.add(row);
        }
      }
    }

    if (json) {
      var map = new LinkedHashMap<String, Object>();
      map.put("dry_run", dryRun);
      map.put("projects", results);
      System.out.println(YamlUtil.dumpJson(map));
      return;
    }

    System.out.println();
    System.out.println(
        Ansi.AUTO.string(
            "  @|bold,green ✓|@ Added "
                + added
                + ", replaced "
                + replaced
                + ", already present "
                + alreadyPresent
                + "."));
  }
}
