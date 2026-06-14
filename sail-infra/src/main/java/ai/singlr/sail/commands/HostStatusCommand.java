/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.commands;

import ai.singlr.sail.config.HostYaml;
import ai.singlr.sail.config.YamlUtil;
import ai.singlr.sail.engine.Banner;
import ai.singlr.sail.engine.ContainerManager;
import ai.singlr.sail.engine.ContainerState;
import ai.singlr.sail.engine.DirQuery;
import ai.singlr.sail.engine.HostDetector;
import ai.singlr.sail.engine.SailPaths;
import ai.singlr.sail.engine.ShellExecutor;
import ai.singlr.sail.engine.ZfsQuery;
import java.nio.file.Files;
import java.util.LinkedHashMap;
import java.util.List;
import picocli.CommandLine.Command;
import picocli.CommandLine.Help.Ansi;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Spec;

@Command(
    name = "status",
    description = "Show server health, resources, and storage usage.",
    mixinStandardHelpOptions = true)
public final class HostStatusCommand implements Runnable {

  @Option(names = "--json", description = "Output in JSON format.")
  private boolean json;

  @Spec private CommandSpec spec;

  @Override
  public void run() {
    CliCommand.run(spec, this::execute);
  }

  private void execute() throws Exception {
    if (!ConsoleHelper.isRoot()) {
      throw new IllegalStateException("Root privileges required. Run with: sudo sail host status");
    }

    var hostYamlPath = SailPaths.hostConfigPath();
    if (!Files.exists(hostYamlPath)) {
      throw new IllegalStateException("Server not initialized. Run 'sail host init' first.");
    }
    var hostYaml = HostYaml.fromMap(YamlUtil.parseFile(hostYamlPath));

    var hostDetector = new HostDetector();
    var hostInfo = hostDetector.detect();

    var shell = new ShellExecutor(false);

    ZfsQuery.PoolUsage poolUsage = null;
    DirQuery.FsUsage fsUsage = null;
    if (hostYaml.isZfs()) {
      poolUsage = ZfsQuery.queryPool(shell, hostYaml.pool());
    } else {
      fsUsage = DirQuery.queryFilesystem(shell);
    }

    var mgr = new ContainerManager(shell);
    var containers = mgr.listAll();

    if (json) {
      printJson(hostYaml, hostInfo, poolUsage, fsUsage, containers);
      return;
    }

    Banner.printBranding(System.out, Ansi.AUTO);
    Banner.printHostStatus(
        hostYaml, hostInfo, poolUsage, fsUsage, containers, System.out, Ansi.AUTO);
  }

  private static void printJson(
      HostYaml hostYaml,
      HostDetector.HostInfo hostInfo,
      ZfsQuery.PoolUsage poolUsage,
      DirQuery.FsUsage fsUsage,
      List<ContainerManager.ContainerInfo> containers) {
    var map = new LinkedHashMap<String, Object>();
    map.put("hostname", hostInfo.hostname());
    map.put("os", hostInfo.osPrettyName());
    map.put("cores", hostInfo.cores());
    map.put("threads", hostInfo.threads());
    map.put("memory_mb", hostInfo.memoryMb());
    map.put("storage_backend", hostYaml.storageBackend());
    map.put("pool", hostYaml.pool());
    if (hostYaml.isZfs()) {
      map.put("pool_disk", hostYaml.poolDisk());
      if (poolUsage != null) {
        map.put("pool_size", poolUsage.size());
        map.put("pool_allocated", poolUsage.allocated());
        map.put("pool_free", poolUsage.free());
        map.put("pool_capacity", poolUsage.capacityPercent());
      }
    } else {
      if (fsUsage != null) {
        map.put("disk_size", fsUsage.size());
        map.put("disk_used", fsUsage.used());
        map.put("disk_available", fsUsage.available());
        map.put("disk_use_percent", fsUsage.usePercent());
      }
    }
    map.put("incus_version", hostYaml.incusVersion());
    map.put("initialized_at", hostYaml.initializedAt());
    var running =
        containers.stream().filter(c -> c.state() instanceof ContainerState.Running).count();
    var stopped =
        containers.stream().filter(c -> c.state() instanceof ContainerState.Stopped).count();
    map.put("containers_total", containers.size());
    map.put("containers_running", running);
    map.put("containers_stopped", stopped);
    System.out.println(YamlUtil.dumpJson(map));
  }
}
