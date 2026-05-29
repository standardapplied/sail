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
import picocli.CommandLine.Command;
import picocli.CommandLine.Help.Ansi;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Spec;

@Command(
    name = "create",
    description = "Create a snapshot of a project container.",
    mixinStandardHelpOptions = true)
public final class SnapCommand implements Runnable {

  @Parameters(index = "0", description = "Project name.")
  private String name;

  @Parameters(
      index = "1",
      arity = "0..1",
      description = "Snapshot label (default: timestamp-based).")
  private String label;

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
    NameValidator.requireValidProjectName(name);
    var shell = new ShellExecutor(dryRun);
    var mgr = new ContainerManager(shell);
    var snapMgr = new SnapshotManager(shell);

    var state = mgr.queryState(name);
    ContainerStateGuard.requireCreated(state, name);

    if (label == null) {
      label = SnapshotManager.defaultLabel();
    } else {
      NameValidator.requireValidSnapshotLabel(label);
    }

    if (!json) {
      Banner.printBranding(System.out, Ansi.AUTO);
      System.out.println();
      System.out.println(Ansi.AUTO.string("  @|bold Creating snapshot|@ " + label + "..."));
    }
    snapMgr.create(name, label);

    if (json) {
      var map = new LinkedHashMap<String, Object>();
      map.put("name", name);
      map.put("snapshot", label);
      System.out.println(YamlUtil.dumpJson(map));
      return;
    }
    Banner.printSnapshotCreated(name, label, System.out, Ansi.AUTO);
  }
}
