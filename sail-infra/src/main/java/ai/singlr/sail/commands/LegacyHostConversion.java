/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.commands;

import ai.singlr.sail.common.Remedied;
import ai.singlr.sail.common.Remedy;
import ai.singlr.sail.engine.ContainerExec;
import ai.singlr.sail.engine.SailPaths;
import ai.singlr.sail.engine.ShellExec;
import ai.singlr.sail.engine.ShellExecutor;
import ai.singlr.sail.engine.SystemdServiceInstaller;
import ai.singlr.sail.store.LegacyConversion;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import picocli.CommandLine.Help.Ansi;

/**
 * Converts this box's Sail 0.58 database as {@code sail migrate} brings the box to this release
 * ({@link LegacyConversion}), with the box quiet around it: {@code sail-api}, which holds the
 * database while it runs, is stopped for the conversion and started again after it, and no other
 * process may hold the database open while it is rewritten. Deleted with the conversion once every
 * box runs this release.
 */
final class LegacyHostConversion {

  private LegacyHostConversion() {}

  /** The {@code sail-api} service, as far as a conversion stops and starts it. */
  interface Service {
    boolean running() throws Exception;

    void stop() throws Exception;

    void start() throws Exception;
  }

  /**
   * Converts the database at {@code db} when Sail 0.58 made it; does nothing otherwise. {@code
   * box}: the run converges this box, so its {@code sail-api} is stopped around the conversion; a
   * rehearsal on a copy touches no service.
   */
  static void convertIfLegacy(Path db, boolean box, boolean json) {
    var host = new BoxHost(db, Path.of("/proc"), new ShellExecutor(false));
    LegacyConversion.at(db, SailPaths.hostConfigPath(), host)
        .ifPresent(
            conversion -> {
              if (box && !SailPaths.isRoot()) {
                throw Remedied.state(
                    "A 0.58 database is converted as root, which sees every process holding it.",
                    Remedy.of("Run '", Remedy.sail("migrate"), "' as root."));
              }
              convert(
                  conversion,
                  db,
                  box ? sailApi() : Optional.empty(),
                  json ? System.err::println : System.out::println);
            });
  }

  /** The box around a database: its systemd units and the processes that hold the database. */
  record BoxHost(Path db, Path proc, ShellExec shell) implements LegacyConversion.Host {

    private static final Set<String> ENDED = Set.of("inactive", "failed");

    /**
     * Asks the project container's user manager, which 0.58 launched the run's agent under as the
     * dev user, for the unit's state: only inactive or failed says the run has ended, as does a
     * container that is stopped or no longer exists. A manager or command that does not answer
     * leaves the run running.
     */
    @Override
    public boolean running(String project, String unit) {
      try {
        var asked =
            shell.exec(
                ContainerExec.asDevUser(
                    project,
                    List.of(
                        "systemctl",
                        "--user",
                        "show",
                        "--property=ActiveState",
                        "--value",
                        "--",
                        unit + ".service")));
        if (asked.ok()) {
          return !ENDED.contains(asked.stdout().strip());
        }
        return !down(project);
      } catch (Exception e) {
        return true;
      }
    }

    private boolean down(String project) throws Exception {
      var info = shell.exec(List.of("incus", "info", "--", project));
      return info.ok()
          ? info.stdout().lines().anyMatch(line -> line.strip().equals("Status: STOPPED"))
          : info.stderr().contains("Instance not found");
    }

    @Override
    public void requireNoOtherHolder() {
      LegacyHostConversion.requireNoOtherHolder(db, proc);
    }
  }

  /**
   * Converts {@code db} through {@code conversion}: refused first, with nothing stopped, when it
   * must wait; then {@code service} is stopped if it runs and the database is converted, said to
   * {@code out}, after which the service is started again. A conversion that cannot be made changes
   * nothing and says so: this release cannot serve a 0.58 database, so it names what to do and how
   * to keep the box on 0.58 meanwhile.
   */
  static void convert(
      LegacyConversion conversion, Path db, Optional<Service> service, Consumer<String> out) {
    Optional<LegacyConversion.Report> report;
    var stopped = Optional.<Service>empty();
    try {
      conversion.requireConvertible();
      stopped = stopIfRunning(service);
      report = conversion.convert();
    } catch (RuntimeException e) {
      var failure = unchanged(db, e, stopped.isPresent());
      if (!LegacyConversion.isLegacy(db)) {
        try {
          stopped.ifPresent(LegacyHostConversion::start);
        } catch (RuntimeException startFailed) {
          failure.addSuppressed(startFailed);
        }
      }
      throw failure;
    }
    report.ifPresent(converted -> out.accept(described(db, converted)));
    stopped.ifPresent(LegacyHostConversion::start);
  }

  private static RuntimeException unchanged(Path db, RuntimeException e, boolean stopped) {
    if (!LegacyConversion.isLegacy(db)) {
      return e;
    }
    var remedy = Remedied.remedyOf(e);
    return Remedied.state(
        Remedied.reasonOf(e)
            + " Nothing was changed: "
            + db
            + " is still Sail 0.58's, which this release cannot serve"
            + (stopped ? ", so sail-api stays stopped until it is converted." : "."),
        (remedy.isEmpty() ? Remedy.of("Run '", Remedy.sail("migrate"), "' again.") : remedy)
            .then(
                " To keep this box on 0.58 meanwhile, reinstall it: curl -fsSL "
                    + INSTALL
                    + " | sudo bash -s 0.58.0 && sudo systemctl restart sail-api"),
        e);
  }

  private static final String INSTALL =
      "https://raw.githubusercontent.com/standardapplied/sail/main/install.sh";

  private static Optional<Service> stopIfRunning(Optional<Service> service) {
    try {
      if (service.isPresent() && service.get().running()) {
        service.get().stop();
        return service;
      }
      return Optional.empty();
    } catch (Exception e) {
      throw Remedied.state(
          "Could not stop sail-api to convert the 0.58 database: " + e.getMessage() + ".",
          Remedy.of(
              "Stop it with '",
              Remedy.program("systemctl", "stop", "sail-api"),
              "', then run '",
              Remedy.sail("migrate"),
              "' again."),
          e);
    }
  }

  private static void start(Service service) {
    try {
      service.start();
    } catch (Exception e) {
      throw Remedied.state(
          "The 0.58 database was converted, but sail-api did not start again: " + e.getMessage(),
          Remedy.of("Start it with '", Remedy.program("systemctl", "start", "sail-api"), "'."),
          e);
    }
  }

  /**
   * Refuses while a process other than this one holds {@code db} open, as an entry of {@code
   * proc}'s per-process descriptor tables shows: one that opened it as 0.58 could still write 0.58
   * rows into the converted schema. Processes whose tables this one may not read are not seen,
   * which is why {@code migrate} converts a box as root.
   */
  static void requireNoOtherHolder(Path db, Path proc) {
    var target = real(db);
    var self = Long.toString(ProcessHandle.current().pid());
    var holders = new ArrayList<String>();
    for (var process : entries(proc)) {
      var pid = process.getFileName().toString();
      if (!pid.chars().allMatch(Character::isDigit) || pid.equals(self)) {
        continue;
      }
      if (entries(process.resolve("fd")).stream().anyMatch(fd -> holds(fd, target))) {
        holders.add(pid + " (" + commandOf(process) + ")");
      }
    }
    if (!holders.isEmpty()) {
      throw Remedied.state(
          "Other processes hold "
              + db
              + " open, so it is not converted while they could still write to it: "
              + String.join(", ", holders)
              + ".",
          Remedy.of("Stop them, then run '", Remedy.sail("migrate"), "' again."));
    }
  }

  private static Path real(Path path) {
    try {
      return path.toRealPath();
    } catch (IOException e) {
      return path.toAbsolutePath().normalize();
    }
  }

  private static List<Path> entries(Path dir) {
    try (var listing = Files.list(dir)) {
      return listing.toList();
    } catch (IOException | SecurityException e) {
      return List.of();
    }
  }

  private static boolean holds(Path fd, Path target) {
    try {
      return Files.readSymbolicLink(fd).equals(target);
    } catch (IOException | UnsupportedOperationException | SecurityException e) {
      return false;
    }
  }

  private static String commandOf(Path process) {
    try {
      return Files.readString(process.resolve("cmdline")).replace('\0', ' ').trim();
    } catch (IOException e) {
      return "unknown";
    }
  }

  static String described(Path db, LegacyConversion.Report report) {
    var kept =
        report.node()
            ? "this box's own rows; its next sync pulls what main kept"
            : counted(report.kept());
    var lines = new ArrayList<String>();
    lines.add(
        Ansi.AUTO.string(
            "  @|green ✓|@ Converted "
                + db
                + " from Sail 0.58"
                + (report.node() ? " as a node" : "")
                + ". Kept "
                + kept
                + ". Dropped "
                + counted(report.dropped())
                + "."));
    lines.add(
        Ansi.AUTO.string(
            "    @|faint The 0.58 database is backed up at " + report.backup() + "|@"));
    report
        .vacuumFailure()
        .ifPresent(
            failure ->
                lines.add(
                    Ansi.AUTO.string(
                        "  @|yellow ⚠|@ The space the dropped rows held was not reclaimed: "
                            + failure)));
    return String.join("\n", lines);
  }

  private static String counted(Map<String, Long> counts) {
    return String.join(
        ", ",
        counts.entrySet().stream()
            .filter(count -> count.getValue() > 0)
            .map(count -> count.getValue() + " " + count.getKey())
            .toList());
  }

  private static Optional<Service> sailApi() {
    var installer = HostServiceInstallers.create(new ShellExecutor(false));
    return installer.isInstalled() ? Optional.of(new SailApi(installer)) : Optional.empty();
  }

  record SailApi(SystemdServiceInstaller installer) implements Service {
    /**
     * Whether it is meant to run, so is stopped for the conversion and started after it: anything
     * but inactive. A unit systemd keeps restarting holds the database; one that gave up on the
     * 0.58 database (failed) runs again once it is converted.
     */
    @Override
    public boolean running() throws Exception {
      return !installer.status().activeState().equals("inactive");
    }

    @Override
    public void stop() throws Exception {
      installer.stop();
    }

    @Override
    public void start() throws Exception {
      installer.start();
    }
  }
}
