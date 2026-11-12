/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.commands;

import ai.singlr.sail.config.HostYaml;
import ai.singlr.sail.config.ListenConfig;
import ai.singlr.sail.config.YamlUtil;
import ai.singlr.sail.engine.Banner;
import ai.singlr.sail.engine.ContainerManager;
import ai.singlr.sail.engine.ContainerState;
import ai.singlr.sail.engine.DirQuery;
import ai.singlr.sail.engine.HostDetector;
import ai.singlr.sail.engine.HostPackages;
import ai.singlr.sail.engine.HostProvisioner;
import ai.singlr.sail.engine.OperatorCommand;
import ai.singlr.sail.engine.SailPaths;
import ai.singlr.sail.engine.ShellExec;
import ai.singlr.sail.engine.ShellExecutor;
import ai.singlr.sail.engine.ZfsQuery;
import java.io.IOException;
import java.io.PrintStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeoutException;
import java.util.function.Predicate;
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

  @Spec private CommandSpec spec;

  @Option(names = "--json", description = "Output in JSON format.")
  private boolean json;

  @Override
  public void run() {
    CliCommand.run(this::execute);
  }

  private void execute() throws Exception {
    var operator = OperatorCommand.here();
    if (!ConsoleHelper.isRoot()) {
      throw RootRequired.refusal(spec);
    }

    var hostYamlPath = SailPaths.hostConfigPath();
    if (!Files.exists(hostYamlPath)) {
      throw HostInitCommand.notInitialized(operator);
    }
    var hostYaml = HostYaml.fromMap(YamlUtil.parseFile(hostYamlPath));

    report(
        hostYaml,
        new HostDetector().detect(),
        new ShellExecutor(false),
        json,
        operator,
        System.out);
  }

  static void report(
      HostYaml hostYaml,
      HostDetector.HostInfo hostInfo,
      ShellExec shell,
      boolean json,
      OperatorCommand operator,
      PrintStream out)
      throws IOException, InterruptedException, TimeoutException {
    ZfsQuery.PoolUsage poolUsage = null;
    DirQuery.FsUsage fsUsage = null;
    if (hostYaml.isZfs()) {
      poolUsage = ZfsQuery.queryPool(shell, hostYaml.pool());
    } else {
      fsUsage = DirQuery.queryFilesystem(shell);
    }

    var incusVersion = HostProvisioner.incusVersion(shell);
    var versions = new HostPackages(shell, detail -> {}).versions();
    var containers = Containers.of(shell);

    var doors = doors(hostYaml.listen(), HostStatusCommand::answers);
    if (json) {
      printJson(
          hostYaml, incusVersion, versions, hostInfo, poolUsage, fsUsage, containers, doors, out);
      return;
    }

    Banner.printBranding(out, Ansi.AUTO);
    Banner.printHostStatus(
        hostYaml,
        incusRow(incusVersion, versions),
        versions.movable() ? operator.sail("host incus upgrade") : null,
        hostInfo,
        poolUsage,
        fsUsage,
        containers.summary(),
        out,
        Ansi.AUTO);
    for (var door : doors) {
      out.println("  " + door.setting() + "  " + door.address() + "  " + door.state());
    }
  }

  /**
   * A private-network door this box is set to open, and what it is doing now: listening, not
   * listening (its address not up yet, say), or refused at start, with why.
   */
  record Door(String setting, String address, String state) {}

  /** Each door {@code listen} names, its state found by asking {@code answers} of its address. */
  static List<Door> doors(ListenConfig listen, Predicate<InetSocketAddress> answers) {
    var doors = new ArrayList<Door>();
    for (var setting : List.of(ListenConfig.API, ListenConfig.TERMINAL)) {
      var value = ListenConfig.API.equals(setting) ? listen.api() : listen.terminal();
      if (value == null) {
        continue;
      }
      String state;
      try {
        state = answers.test(ListenConfig.address(setting, value)) ? "listening" : "not listening";
      } catch (IllegalArgumentException refused) {
        state = "refused at start: " + refused.getMessage();
      }
      doors.add(new Door(setting, value, state));
    }
    return List.copyOf(doors);
  }

  /** Whether something accepts a TCP connection at {@code address} within a second. */
  private static boolean answers(InetSocketAddress address) {
    try (var socket = new Socket()) {
      socket.connect(address, 1000);
      return true;
    } catch (IOException silent) {
      return false;
    }
  }

  /**
   * The Incus row: the version running, and, when apt would move it, the one it would move to, both
   * named in the one form that tells them apart ({@link HostPackages.Versions#from()}).
   */
  private static String incusRow(String incusVersion, HostPackages.Versions versions) {
    return versions.movable()
        ? versions.from() + " (" + versions.to() + " available)"
        : incusVersion;
  }

  private static void printJson(
      HostYaml hostYaml,
      String incusVersion,
      HostPackages.Versions versions,
      HostDetector.HostInfo hostInfo,
      ZfsQuery.PoolUsage poolUsage,
      DirQuery.FsUsage fsUsage,
      Containers containers,
      List<Door> doors,
      PrintStream out) {
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
    map.put("incus_version", incusVersion);
    map.put("incus_installed", versions.installed());
    map.put("incus_candidate", versions.candidate());
    map.put("initialized_at", hostYaml.initializedAt());
    if (containers.error() != null) {
      map.put("containers_error", containers.error());
    } else {
      map.put("containers_total", containers.listed().size());
      map.put("containers_running", containers.running());
      map.put("containers_stopped", containers.stopped());
    }
    map.put(
        "listen",
        doors.stream()
            .map(
                door ->
                    Map.of(
                        "setting",
                        door.setting(),
                        "address",
                        door.address(),
                        "state",
                        door.state()))
            .toList());
    out.println(YamlUtil.dumpJson(map));
  }

  /**
   * This box's containers as Incus lists them, or why it could not: a daemon that does not answer
   * leaves the rest of the status worth reading, and is never reported as zero containers. Its
   * banner row reads the first line of why, so the row stays one line of the box.
   */
  private record Containers(List<ContainerManager.ContainerInfo> listed, String error) {

    static Containers of(ShellExec shell) throws InterruptedException {
      try {
        return new Containers(new ContainerManager(shell).listAll(), null);
      } catch (IOException | TimeoutException e) {
        return new Containers(List.of(), e.getMessage());
      }
    }

    long running() {
      return listed.stream().filter(c -> c.state() instanceof ContainerState.Running).count();
    }

    long stopped() {
      return listed.stream().filter(c -> c.state() instanceof ContainerState.Stopped).count();
    }

    String summary() {
      if (error != null) {
        return "unavailable (" + error.strip().lines().findFirst().orElse("") + ")";
      }
      return listed.size() + " total (" + running() + " running, " + stopped() + " stopped)";
    }
  }
}
