/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.commands;

import ai.singlr.sail.config.YamlUtil;
import ai.singlr.sail.engine.Banner;
import ai.singlr.sail.engine.ContainerManager;
import ai.singlr.sail.engine.ContainerManager.ContainerInfo;
import ai.singlr.sail.engine.ContainerState;
import ai.singlr.sail.engine.SailPaths;
import ai.singlr.sail.engine.ShellExecutor;
import ai.singlr.sail.store.ProjectStore;
import ai.singlr.sail.store.Sqlite;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import picocli.CommandLine.Command;
import picocli.CommandLine.Help.Ansi;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Spec;

@Command(
    name = "list",
    description = "List all projects in the catalog and their local container status.",
    mixinStandardHelpOptions = true)
public final class ProjectListCommand implements Runnable {

  @Option(names = "--json", description = "Output in JSON format.")
  private boolean json;

  @Spec private CommandSpec spec;

  @Override
  public void run() {
    CliCommand.run(spec, this::execute);
  }

  private void execute() throws Exception {
    var shell = new ShellExecutor(false);
    var containers = new ContainerManager(shell).listAll();
    var projects = merge(containers, catalogNames());

    if (json) {
      System.out.println(renderJson(projects));
      return;
    }

    Banner.printBranding(System.out, Ansi.AUTO);

    if (projects.isEmpty()) {
      System.out.println();
      System.out.println(
          Ansi.AUTO.string(
              "  @|faint No projects found.|@ Create one with: @|bold sail project create|@,"
                  + " or pull a team project with: @|bold sail sync|@"));
      System.out.println();
      return;
    }

    Banner.printProjectTable(projects, System.out, Ansi.AUTO);
  }

  /**
   * The full project roster: every catalogued project plus any container without a catalog entry,
   * keyed by name so a project that is both catalogued and provisioned shows its live container
   * state rather than a placeholder. Catalogued-but-unprovisioned projects (e.g. just synced from
   * main) surface as {@link ContainerState.NotCreated} so they can be found and provisioned.
   */
  static List<ContainerInfo> merge(List<ContainerInfo> containers, List<String> catalogNames) {
    var byName = new LinkedHashMap<String, ContainerInfo>();
    for (var name : catalogNames) {
      byName.put(name, new ContainerInfo(name, new ContainerState.NotCreated(), null));
    }
    for (var container : containers) {
      byName.put(container.name(), container);
    }
    return byName.values().stream().sorted(Comparator.comparing(ContainerInfo::name)).toList();
  }

  private static List<String> catalogNames() {
    try (var db = Sqlite.open(SailPaths.controlPlaneDb())) {
      return new ProjectStore(db).list().stream().map(ProjectStore.ProjectRow::name).toList();
    } catch (RuntimeException e) {
      return List.of();
    }
  }

  /** Renders the project roster as JSON — pure, so the shape is unit-tested. */
  static String renderJson(List<ContainerInfo> projects) {
    var list = new ArrayList<Map<String, Object>>();
    for (var c : projects) {
      var map = new LinkedHashMap<String, Object>();
      map.put("name", c.name());
      map.put("status", statusOf(c.state()));
      if (c.state() instanceof ContainerState.Running r && r.ipv4() != null) {
        map.put("ip", r.ipv4());
      }
      list.add(map);
    }
    return YamlUtil.dumpJson(list);
  }

  private static String statusOf(ContainerState state) {
    return switch (state) {
      case ContainerState.Running ignored -> "running";
      case ContainerState.Stopped ignored -> "stopped";
      case ContainerState.NotCreated ignored -> "not_provisioned";
      case ContainerState.Error ignored -> "error";
    };
  }
}
