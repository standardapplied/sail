/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.commands;

import ai.singlr.sail.config.YamlUtil;
import ai.singlr.sail.engine.Banner;
import ai.singlr.sail.engine.ContainerExec;
import ai.singlr.sail.engine.ContainerManager;
import ai.singlr.sail.engine.ContainerState;
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
    name = "restore",
    description = "Restore a project container to a snapshot.",
    mixinStandardHelpOptions = true)
public final class RestoreCommand implements Runnable {

  @Parameters(index = "0", description = "Project name.")
  private String name;

  @Parameters(index = "1", arity = "0..1", description = "Snapshot label (default: latest).")
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
      var snapshots = snapMgr.list(name);
      if (snapshots.isEmpty()) {
        throw new IllegalStateException(
            "No snapshots exist for '"
                + name
                + "'. Create one with: sail project snapshot create "
                + name);
      }
      label = snapshots.getLast().name();
    } else {
      NameValidator.requireValidSnapshotLabel(label);
    }

    if (!json) {
      Banner.printBranding(System.out, Ansi.AUTO);
      System.out.println();
    }

    if (state instanceof ContainerState.Running) {
      if (!json) {
        System.out.println(Ansi.AUTO.string("  @|bold Stopping|@ " + name + "..."));
      }
      mgr.stop(name);
    }

    if (!json) {
      System.out.println(Ansi.AUTO.string("  @|bold Restoring|@ to snapshot " + label + "..."));
    }
    snapMgr.restore(name, label);

    if (!json) {
      System.out.println(Ansi.AUTO.string("  @|bold Starting|@ " + name + "..."));
    }
    mgr.start(name);

    if (json) {
      var map = new LinkedHashMap<String, Object>();
      map.put("name", name);
      map.put("restored_to", label);
      System.out.println(YamlUtil.dumpJson(map));
      return;
    }

    Banner.printSnapshotRestored(name, label, System.out, Ansi.AUTO);
    Banner.printZedConnect(name, null, System.out, Ansi.AUTO);
    try {
      var ports = ContainerExec.queryServicePorts(shell, name);
      Banner.printSshTunnels(name, null, ports, System.out, Ansi.AUTO);
    } catch (Exception ignored) {
    }
  }
}
