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
import ai.singlr.sail.engine.HostDetector;
import ai.singlr.sail.engine.HostPackages;
import ai.singlr.sail.engine.HostProvisioner;
import ai.singlr.sail.engine.IncusStorage;
import ai.singlr.sail.engine.OperatorCommand;
import ai.singlr.sail.engine.SailPaths;
import ai.singlr.sail.engine.ShellExec;
import ai.singlr.sail.engine.ShellExecutor;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeoutException;
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
    var storage = Storage.of(shell);
    var incusVersion = HostProvisioner.incusVersion(shell);
    var versions = new HostPackages(shell, detail -> {}).versions();
    var containers = Containers.of(shell);

    if (json) {
      printJson(hostYaml, incusVersion, versions, hostInfo, storage, containers, out);
      return;
    }

    Banner.printBranding(out, Ansi.AUTO);
    Banner.printHostStatus(
        hostYaml,
        incusRow(incusVersion, versions),
        versions.movable() ? operator.sail("host incus upgrade") : null,
        hostInfo,
        storage.rows(),
        storage.advisory(),
        containers.summary(),
        out,
        Ansi.AUTO);
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
      Storage storage,
      Containers containers,
      PrintStream out) {
    var map = new LinkedHashMap<String, Object>();
    map.put("hostname", hostInfo.hostname());
    map.put("os", hostInfo.osPrettyName());
    map.put("cores", hostInfo.cores());
    map.put("threads", hostInfo.threads());
    map.put("memory_mb", hostInfo.memoryMb());
    map.put("storage_backend", hostYaml.storageBackend());
    map.put("pool", hostYaml.pool());
    if (storage.error() != null) {
      map.put("storage_error", storage.error());
    } else {
      map.put("pools", storage.pools().stream().map(Storage::toMap).toList());
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
      map.put("containers_frozen", containers.frozen());
      map.put("containers_stopped", containers.stopped());
    }
    out.println(YamlUtil.dumpJson(map));
  }

  /**
   * This box's storage pools as Incus lists them, or why it could not, read as {@link Containers}
   * reads its containers: each row one pool, its driver, its source and its free space.
   */
  private record Storage(List<IncusStorage.Pool> pools, String error) {

    static Storage of(ShellExec shell) throws InterruptedException {
      try {
        return new Storage(new IncusStorage(shell).pools(), null);
      } catch (IOException | TimeoutException e) {
        return new Storage(List.of(), e.getMessage());
      }
    }

    List<String> rows() {
      return error != null
          ? List.of("unavailable (" + error.strip().lines().findFirst().orElse("") + ")")
          : pools.stream().map(IncusStorage.Pool::summary).toList();
    }

    /** Whether a pool's disk limits are only advisory, as they are on a dir pool. */
    boolean advisory() {
      return pools.stream().anyMatch(pool -> HostYaml.BACKEND_DIR.equals(pool.driver()));
    }

    static Map<String, Object> toMap(IncusStorage.Pool pool) {
      var map = new LinkedHashMap<String, Object>();
      map.put("name", pool.name());
      map.put("driver", pool.driver());
      map.put("source", pool.source());
      pool.space()
          .ifPresent(
              space -> {
                map.put("used_bytes", space.used());
                map.put("total_bytes", space.total());
              });
      return map;
    }
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

    long frozen() {
      return listed.stream().filter(c -> c.state() instanceof ContainerState.Frozen).count();
    }

    long stopped() {
      return listed.stream().filter(c -> c.state() instanceof ContainerState.Stopped).count();
    }

    String summary() {
      if (error != null) {
        return "unavailable (" + error.strip().lines().findFirst().orElse("") + ")";
      }
      var frozen = frozen() > 0 ? frozen() + " frozen, " : "";
      return listed.size()
          + " total ("
          + running()
          + " running, "
          + frozen
          + stopped()
          + " stopped)";
    }
  }
}
