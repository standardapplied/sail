/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.commands;

import ai.singlr.sail.api.SailApiServer;
import ai.singlr.sail.config.HostYaml;
import ai.singlr.sail.config.ListenConfig;
import ai.singlr.sail.config.YamlUtil;
import ai.singlr.sail.engine.Banner;
import ai.singlr.sail.engine.BoxInstances;
import ai.singlr.sail.engine.ContainerManager;
import ai.singlr.sail.engine.ContainerState;
import ai.singlr.sail.engine.HostDetector;
import ai.singlr.sail.engine.HostPackages;
import ai.singlr.sail.engine.HostProvisioner;
import ai.singlr.sail.engine.IncusStorage;
import ai.singlr.sail.engine.OperatorCommand;
import ai.singlr.sail.engine.PoolHealth;
import ai.singlr.sail.engine.SailPaths;
import ai.singlr.sail.engine.ShellExec;
import ai.singlr.sail.engine.ShellExecutor;
import ai.singlr.sail.pty.PtySessionHost;
import java.io.IOException;
import java.io.PrintStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;
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
    var storage = Storage.of(shell);
    var incusVersion = HostProvisioner.incusVersion(shell);
    var versions = new HostPackages(shell, detail -> {}).versions();
    var containers = Containers.of(shell);

    var doors = doors(hostYaml.listen(), HostStatusCommand::bound, HostStatusCommand::answers);
    if (json) {
      printJson(hostYaml, incusVersion, versions, hostInfo, storage, containers, doors, out);
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
    for (var door : doors) {
      out.println("  " + door.setting() + "  " + door.state());
    }
  }

  /**
   * A private-network door, and what the running service does with it: listening on the address it
   * bound, with the setting it will take at its next restart when that differs; or not listening,
   * with what it is set to, or why that setting is refused at start.
   */
  record Door(String setting, String state) {}

  /**
   * Each door a running service says it listens on, or the settings name: what {@code bound} says
   * the service bound for a setting, confirmed by {@code answers}, else what the setting says.
   */
  static List<Door> doors(
      ListenConfig listen,
      Function<String, Optional<String>> bound,
      Predicate<InetSocketAddress> answers) {
    var doors = new ArrayList<Door>();
    for (var setting : List.of(ListenConfig.API, ListenConfig.TERMINAL)) {
      var set = ListenConfig.API.equals(setting) ? listen.api() : listen.terminal();
      var serving = bound.apply(setting).filter(at -> listening(setting, at, answers));
      if (serving.isPresent()) {
        var pending =
            same(setting, serving.get(), set)
                ? ""
                : "; set to "
                    + Objects.requireNonNullElse(set, ListenConfig.NONE)
                    + ", restart to apply";
        doors.add(new Door(setting, "listening on " + serving.get() + pending));
      } else if (set != null) {
        doors.add(new Door(setting, notListening(listen, setting, set)));
      }
    }
    return List.copyOf(doors);
  }

  /** Whether {@code set} names the address {@code bound} is, however it is written. */
  private static boolean same(String setting, String bound, String set) {
    try {
      return set != null
          && ListenConfig.address(setting, bound).equals(ListenConfig.address(setting, set));
    } catch (IllegalArgumentException unreadable) {
      return false;
    }
  }

  private static boolean listening(
      String setting, String at, Predicate<InetSocketAddress> answers) {
    try {
      return answers.test(ListenConfig.address(setting, at));
    } catch (IllegalArgumentException unreadable) {
      return false;
    }
  }

  private static String notListening(ListenConfig listen, String setting, String set) {
    try {
      if (ListenConfig.API.equals(setting)) {
        listen.apiAddress();
      } else {
        listen.terminalAddress();
      }
      return "not listening (set to " + set + ")";
    } catch (IllegalArgumentException refused) {
      return "refused at start: " + refused.getMessage();
    }
  }

  /** What the running service of {@code setting} says it is bound to. */
  private static Optional<String> bound(String setting) {
    return ListenConfig.said(
        ListenConfig.API.equals(setting)
            ? SailApiServer.listeningOf(SailPaths.apiSocketPath())
            : PtySessionHost.listeningOf(SailPaths.ptySocketPath()));
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
      Storage storage,
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
    if (storage.error() != null) {
      map.put("storage_error", storage.error());
    } else {
      map.put("pools", storage.pools().stream().map(storage::toMap).toList());
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
    if (!doors.isEmpty()) {
      map.put(
          "listen",
          doors.stream()
              .map(door -> Map.of("setting", door.setting(), "state", door.state()))
              .toList());
    }
    out.println(YamlUtil.dumpJson(map));
  }

  /**
   * This box's storage pools as Incus lists them, or why it could not, read as {@link Containers}
   * reads its containers: each row one pool, its driver, its source and its free space, and for a
   * zfs pool its health as zpool says it now, where zpool can say.
   */
  private record Storage(
      List<IncusStorage.Pool> pools, Map<String, PoolHealth> health, String error) {

    static Storage of(ShellExec shell) throws InterruptedException {
      try {
        var pools = new IncusStorage(shell).pools();
        var health = new LinkedHashMap<String, PoolHealth>();
        for (var pool : pools) {
          if (HostYaml.BACKEND_ZFS.equals(pool.driver())) {
            PoolHealth.of(shell, Objects.requireNonNullElse(pool.zpool(), pool.name()))
                .ifPresent(found -> health.put(pool.name(), found));
          }
        }
        return new Storage(pools, health, null);
      } catch (IOException | TimeoutException e) {
        return new Storage(List.of(), Map.of(), e.getMessage());
      }
    }

    List<String> rows() {
      return error != null
          ? List.of("unavailable (" + error.strip().lines().findFirst().orElse("") + ")")
          : pools.stream().map(this::row).toList();
    }

    private String row(IncusStorage.Pool pool) {
      var found = health.get(pool.name());
      if (found == null) {
        return pool.summary();
      }
      var zpool = Objects.requireNonNullElse(pool.zpool(), pool.name());
      var lines = new ArrayList<>(List.of(pool.summary(), "health " + found.state()));
      found.unhealthy().forEach((device, state) -> lines.add(device + " " + state));
      if (found.degraded()) {
        lines.add("replace the missing device: sudo zpool replace " + zpool + " <device> <new>");
      }
      return String.join("\n", lines);
    }

    /** Whether a pool's disk limits are only advisory, as they are on a dir pool. */
    boolean advisory() {
      return pools.stream().anyMatch(pool -> HostYaml.BACKEND_DIR.equals(pool.driver()));
    }

    Map<String, Object> toMap(IncusStorage.Pool pool) {
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
      var found = health.get(pool.name());
      if (found != null) {
        map.put("health", found.state());
        map.put(
            "unhealthy",
            found.unhealthy().entrySet().stream()
                .map(device -> Map.of("device", device.getKey(), "state", device.getValue()))
                .toList());
      }
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
        return new Containers(
            new BoxInstances(shell).all().stream().map(BoxInstances.Instance::info).toList(), null);
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
