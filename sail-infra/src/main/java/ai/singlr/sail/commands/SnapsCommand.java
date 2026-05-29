/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.commands;

import ai.singlr.sail.config.YamlUtil;
import ai.singlr.sail.engine.Banner;
import ai.singlr.sail.engine.ContainerManager;
import ai.singlr.sail.engine.ContainerStateGuard;
import ai.singlr.sail.engine.NameValidator;
import ai.singlr.sail.engine.ShellExecutor;
import ai.singlr.sail.engine.SnapshotManager;
import java.util.LinkedHashMap;
import java.util.List;
import picocli.CommandLine.Command;
import picocli.CommandLine.Help.Ansi;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Spec;

@Command(
    name = "list",
    description = "List snapshots for a project container.",
    mixinStandardHelpOptions = true)
public final class SnapsCommand implements Runnable {

  @Parameters(index = "0", description = "Project name.")
  private String name;

  @Option(names = "--json", description = "Output in JSON format.")
  private boolean json;

  @Spec private CommandSpec spec;

  @Override
  public void run() {
    CliCommand.run(spec, this::execute);
  }

  private void execute() throws Exception {
    NameValidator.requireValidProjectName(name);
    var shell = new ShellExecutor(false);
    var mgr = new ContainerManager(shell);
    var snapMgr = new SnapshotManager(shell);

    var state = mgr.queryState(name);
    ContainerStateGuard.requireCreated(state, name);

    var snapshots = snapMgr.list(name);

    if (json) {
      printJson(snapshots);
      return;
    }

    Banner.printBranding(System.out, Ansi.AUTO);

    if (snapshots.isEmpty()) {
      System.out.println();
      System.out.println(
          Ansi.AUTO.string(
              "  @|faint No snapshots for "
                  + name
                  + ". Create one with:|@ @|bold sail project snapshot create "
                  + name
                  + "|@"));
      return;
    }

    Banner.printSnapshotTable(name, snapshots, System.out, Ansi.AUTO);
  }

  private static void printJson(List<SnapshotManager.SnapshotInfo> snapshots) {
    var list =
        snapshots.stream()
            .map(
                s -> {
                  var map = new LinkedHashMap<String, Object>();
                  map.put("name", s.name());
                  map.put("created_at", s.createdAt());
                  return (Object) map;
                })
            .toList();
    System.out.println(YamlUtil.dumpJson(list));
  }
}
