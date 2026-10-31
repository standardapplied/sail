/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.engine;

import ai.singlr.sail.common.Strings;
import ai.singlr.sail.config.SailYaml;
import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeoutException;

/**
 * Brings a project's stack up in its container and says what listens where: its services, the
 * Podman containers {@code apply} already starts, and its processes, each a {@code systemd --user}
 * unit {@code sail-process-<name>} the dev user runs. The one verb that starts a process: {@code
 * apply} never does.
 *
 * <p>A process is written as a script and a unit beside it; a unit whose text changed is restarted,
 * an unchanged one only started, and a unit of a process no longer declared is stopped and removed.
 * Its command is the script's last line, so systemd's own expansion of {@code $} and {@code %}
 * never touches it. Then every declared port is watched, one container command a look, until each
 * listens or its process exits, or the looks run out.
 *
 * <p>Names, paths and environment were checked when the definition was parsed; here each reaches a
 * shell only as an argument or, in the script, as one quoted shell word.
 */
public final class ProjectServer {

  /** How long a look waits for the next one. */
  public static final Duration LOOK_EVERY = Duration.ofMillis(500);

  static final String UNIT_PREFIX = "sail-process-";
  static final String UNITS_DIR = ContainerExec.DEV_HOME + "/.config/systemd/user";
  static final String PROCESSES_DIR = ContainerExec.DEV_HOME + "/.sail/processes";

  private static final String WRITE =
      "mkdir -p \"$(dirname \"$2\")\" && { [ -f \"$2\" ] && [ \"$(cat \"$2\")\" = \"$1\" ] && echo"
          + " same || { printf '%s' \"$1\" > \"$2\" && echo written; }; }";
  private static final String LOOK =
      """
      ss -ltnH
      echo ---
      [ "$#" -eq 0 ] || systemctl --user show -p Id -p ActiveState -p SubState -p ExecMainStatus "$@"
      """;
  private static final String DECLARED_UNITS =
      "for f in \"$1\"/"
          + UNIT_PREFIX
          + "*.service; do [ -f \"$f\" ] && basename \"$f\" .service; done; :";

  /** What a declared port belongs to. */
  public enum Kind {
    SERVICE,
    PROCESS
  }

  /** Where a declared port stands. */
  public enum State {
    /** Something accepts connections on it in the container. */
    LISTENING,
    /** Its process is running and does not listen yet. */
    STARTING,
    /** Its process exited, or keeps exiting, before it listened. */
    EXITED,
    /** Nothing runs that would listen on it. */
    STOPPED,
    /** {@code serve} looked as long as it was told and it never listened. */
    TIMEOUT;

    /** The state as the wire and the operator read it. */
    public String word() {
      return name().toLowerCase(Locale.ROOT);
    }
  }

  /**
   * One declared port and where it stands.
   *
   * @param exitCode the process's exit status when it exited; otherwise null
   * @param detail the last line its process wrote when it exited; otherwise empty
   */
  public record Port(
      String name, int port, Kind kind, State state, Integer exitCode, String detail) {

    /** The line {@code serve} prints for it: {@code web 3000 exited 1: <line>}. */
    public String line(int nameWidth) {
      var said =
          state == State.EXITED
              ? "exited " + exitCode + (detail.isEmpty() ? "" : ": " + detail)
              : state.word();
      return ("%-" + Math.max(1, nameWidth) + "s  %d  %s").formatted(name, port, said);
    }
  }

  /** Every declared port, services first, each in the order the definition declares it. */
  public record Report(List<Port> ports) {

    public Report {
      ports = List.copyOf(ports);
    }

    /** Whether every declared port listens. */
    public boolean allListening() {
      return ports.stream().allMatch(port -> port.state() == State.LISTENING);
    }

    /** One line per port, its name padded to the widest. */
    public List<String> lines() {
      var width = ports.stream().mapToInt(port -> port.name().length()).max().orElse(0);
      return ports.stream().map(port -> port.line(width)).toList();
    }
  }

  /** Waits between looks; a test's returns at once. */
  public interface Pause {
    void pause(Duration duration) throws InterruptedException;
  }

  private final ShellExec shell;
  private final ProjectApplier applier;
  private final Pause pause;

  public ProjectServer(ShellExec shell, ProjectApplier applier, Pause pause) {
    this.shell = shell;
    this.applier = applier;
    this.pause = pause;
  }

  /**
   * Brings {@code config}'s stack up in {@code project}'s running container and looks until every
   * declared port listens or its process exits, for at most {@code timeout}.
   */
  public Report serve(String project, SailYaml config, Duration timeout)
      throws IOException, InterruptedException, TimeoutException {
    startServices(project, config);
    startProcesses(project, config);
    var looks = Math.max(1, (int) Math.ceil(timeout.toMillis() / (double) LOOK_EVERY.toMillis()));
    Report report = null;
    for (var look = 0; look < looks; look++) {
      report = look(project, config);
      if (settled(report)) {
        return report;
      }
      if (look + 1 < looks) {
        pause.pause(LOOK_EVERY);
      }
    }
    return timedOut(report);
  }

  /** Where each declared port of {@code config} stands now, starting nothing. */
  public Report ports(String project, SailYaml config)
      throws IOException, InterruptedException, TimeoutException {
    return look(project, config);
  }

  /** Stops and disables every process unit of {@code project}, declared or not; services stay. */
  public void stop(String project) throws IOException, InterruptedException, TimeoutException {
    var units = units(project);
    if (!units.isEmpty()) {
      var command = new ArrayList<>(List.of("systemctl", "--user", "disable", "--now"));
      units.forEach(unit -> command.add(unit + ".service"));
      run(project, "stop the processes", command);
    }
  }

  private void startServices(String project, SailYaml config)
      throws IOException, InterruptedException, TimeoutException {
    if (config.services() == null || config.services().isEmpty()) {
      return;
    }
    applier.applyServices(project, config.services());
    var start = new ArrayList<>(List.of("podman", "start"));
    start.addAll(config.services().keySet());
    run(project, "start the services", start);
  }

  private void startProcesses(String project, SailYaml config)
      throws IOException, InterruptedException, TimeoutException {
    var declared = processes(config);
    var changed = new ArrayList<String>();
    var unchanged = new ArrayList<String>();
    for (var entry : declared.entrySet()) {
      var name = entry.getKey();
      var wrote =
          write(project, scriptPath(name), script(config, entry.getValue()))
              | write(project, unitPath(name), unit(name));
      (wrote ? changed : unchanged).add(unitOf(name));
    }
    var gone = new ArrayList<String>();
    for (var unit : units(project)) {
      if (!declared.containsKey(unit.substring(UNIT_PREFIX.length()))) {
        gone.add(unit);
      }
    }
    if (!gone.isEmpty()) {
      var disable = new ArrayList<>(List.of("systemctl", "--user", "disable", "--now"));
      gone.forEach(unit -> disable.add(unit + ".service"));
      run(project, "stop the processes no longer declared", disable);
      var remove = new ArrayList<>(List.of("rm", "-f"));
      for (var unit : gone) {
        remove.add(UNITS_DIR + "/" + unit + ".service");
        remove.add(PROCESSES_DIR + "/" + unit.substring(UNIT_PREFIX.length()) + ".sh");
      }
      run(project, "remove the processes no longer declared", remove);
    }
    if (declared.isEmpty()) {
      return;
    }
    run(project, "reload the user units", List.of("systemctl", "--user", "daemon-reload"));
    var all = new ArrayList<String>();
    all.addAll(changed);
    all.addAll(unchanged);
    run(project, "enable the processes", withUnits(List.of("systemctl", "--user", "enable"), all));
    if (!changed.isEmpty()) {
      run(
          project,
          "restart the changed processes",
          withUnits(List.of("systemctl", "--user", "restart"), changed));
    }
    if (!unchanged.isEmpty()) {
      run(
          project,
          "start the processes",
          withUnits(List.of("systemctl", "--user", "start"), unchanged));
    }
  }

  private Report look(String project, SailYaml config)
      throws IOException, InterruptedException, TimeoutException {
    var declared = processes(config);
    var command = new ArrayList<>(List.of("sh", "-c", LOOK, "sh"));
    declared.keySet().forEach(name -> command.add(unitOf(name) + ".service"));
    var looked = shell.exec(ContainerExec.asDevUser(project, command));
    if (!looked.ok()) {
      throw new IOException("Failed to look at the ports of " + project + ": " + looked.reason());
    }
    var halves = looked.stdout().split("(?m)^---$", 2);
    var listening = listening(halves[0]);
    var units = unitStates(halves.length > 1 ? halves[1] : "");
    var ports = new ArrayList<Port>();
    if (config.services() != null) {
      for (var service : config.services().entrySet()) {
        var declaredPorts = service.getValue() == null ? null : service.getValue().ports();
        for (var port : declaredPorts == null ? List.<Integer>of() : declaredPorts) {
          ports.add(
              new Port(
                  service.getKey(),
                  port,
                  Kind.SERVICE,
                  listening.contains(port) ? State.LISTENING : State.STOPPED,
                  null,
                  ""));
        }
      }
    }
    for (var process : declared.entrySet()) {
      if (process.getValue().port() == null) {
        continue;
      }
      var port = process.getValue().port();
      var unit = units.getOrDefault(unitOf(process.getKey()) + ".service", Map.of());
      ports.add(processPort(project, process.getKey(), port, listening.contains(port), unit));
    }
    return new Report(ports);
  }

  private Port processPort(
      String project, String name, int port, boolean listens, Map<String, String> unit)
      throws IOException, InterruptedException, TimeoutException {
    if (listens) {
      return new Port(name, port, Kind.PROCESS, State.LISTENING, null, "");
    }
    var active = unit.getOrDefault("ActiveState", "inactive");
    var sub = unit.getOrDefault("SubState", "");
    var status =
        Strings.isBlank(unit.get("ExecMainStatus"))
            ? 0
            : Integer.parseInt(unit.get("ExecMainStatus"));
    var exited =
        active.equals("failed")
            || sub.equals("auto-restart")
            || (active.equals("inactive") && status != 0);
    if (exited) {
      return new Port(name, port, Kind.PROCESS, State.EXITED, status, lastLine(project, name));
    }
    var running =
        active.equals("active") || active.equals("activating") || active.equals("reloading");
    return new Port(name, port, Kind.PROCESS, running ? State.STARTING : State.STOPPED, null, "");
  }

  private String lastLine(String project, String name)
      throws IOException, InterruptedException, TimeoutException {
    var tail =
        shell.exec(
            ContainerExec.asDevUser(
                project,
                List.of(
                    "sh",
                    "-c",
                    "[ -f \"$1\" ] && grep -v '^[[:space:]]*$' \"$1\" | tail -n 1; :",
                    "sh",
                    logPath(name))));
    return tail.ok() ? tail.stdout().strip() : "";
  }

  private static boolean settled(Report report) {
    return report.ports().stream()
        .allMatch(port -> port.state() == State.LISTENING || port.state() == State.EXITED);
  }

  private static Report timedOut(Report report) {
    return new Report(
        report.ports().stream()
            .map(
                port ->
                    port.state() == State.STARTING || port.state() == State.STOPPED
                        ? new Port(port.name(), port.port(), port.kind(), State.TIMEOUT, null, "")
                        : port)
            .toList());
  }

  /** The ports something listens on, from {@code ss -ltnH}: the local address's last field. */
  static Set<Integer> listening(String ss) {
    var ports = new HashSet<Integer>();
    for (var line : ss.lines().toList()) {
      var fields = line.strip().split("\\s+");
      if (fields.length < 4) {
        continue;
      }
      var local = fields[3];
      var colon = local.lastIndexOf(':');
      if (colon >= 0) {
        try {
          ports.add(Integer.parseInt(local.substring(colon + 1)));
        } catch (NumberFormatException notAPort) {
          continue;
        }
      }
    }
    return ports;
  }

  /** Each unit's properties from {@code systemctl show}, by its {@code Id}. */
  static Map<String, Map<String, String>> unitStates(String shown) {
    var units = new LinkedHashMap<String, Map<String, String>>();
    var current = new LinkedHashMap<String, String>();
    for (var line : (shown + "\n\n").lines().toList()) {
      if (line.isBlank()) {
        if (current.containsKey("Id")) {
          units.put(current.get("Id"), Map.copyOf(current));
        }
        current = new LinkedHashMap<>();
        continue;
      }
      var equals = line.indexOf('=');
      if (equals > 0) {
        current.put(line.substring(0, equals), line.substring(equals + 1));
      }
    }
    return units;
  }

  /**
   * The script that runs {@code process}: from its workdir, with its environment, its command as
   * its last line, read by a login shell so the project's runtimes are on the PATH.
   */
  static String script(SailYaml config, SailYaml.Process process) {
    var script = new StringBuilder("# Generated by sail: sail project serve writes it again.\n");
    script
        .append("cd ")
        .append(Strings.shellWord(workdirOf(config, process)))
        .append(" || exit 1\n");
    process
        .env()
        .forEach(
            (name, value) ->
                script
                    .append("export ")
                    .append(name)
                    .append('=')
                    .append(Strings.shellWord(value))
                    .append('\n'));
    script.append(process.command()).append('\n');
    return script.toString();
  }

  /** The unit that runs {@code name}'s script, appending what it writes to its log. */
  static String unit(String name) {
    return """
        [Unit]
        Description=sail process %1$s

        [Service]
        ExecStart=/bin/bash -l %2$s
        Restart=on-failure
        RestartSec=2
        StandardOutput=append:%3$s
        StandardError=append:%3$s

        [Install]
        WantedBy=default.target
        """
        .formatted(name, scriptPath(name), logPath(name));
  }

  /** Where {@code process} runs: its workdir under the workspace, else the first repo's path. */
  static String workdirOf(SailYaml config, SailYaml.Process process) {
    if (process.workdir() != null) {
      var path = SailYaml.Process.inWorkspace(process.workdir());
      return path.isEmpty()
          ? ContainerExec.DEV_WORKSPACE
          : ContainerExec.DEV_WORKSPACE + "/" + path;
    }
    var repos = config.repos();
    return repos == null || repos.isEmpty()
        ? ContainerExec.DEV_WORKSPACE
        : ContainerExec.DEV_WORKSPACE + "/" + repos.getFirst().path();
  }

  /** The log a process's unit appends to. */
  public static String logPath(String name) {
    return PROCESSES_DIR + "/" + name + ".log";
  }

  static String scriptPath(String name) {
    return PROCESSES_DIR + "/" + name + ".sh";
  }

  static String unitPath(String name) {
    return UNITS_DIR + "/" + unitOf(name) + ".service";
  }

  static String unitOf(String name) {
    return UNIT_PREFIX + name;
  }

  private static Map<String, SailYaml.Process> processes(SailYaml config) {
    return config.processes() == null ? Map.of() : config.processes();
  }

  private List<String> units(String project)
      throws IOException, InterruptedException, TimeoutException {
    var listed =
        shell.exec(
            ContainerExec.asDevUser(project, List.of("sh", "-c", DECLARED_UNITS, "sh", UNITS_DIR)));
    if (!listed.ok()) {
      throw new IOException(
          "Failed to list the process units of " + project + ": " + listed.reason());
    }
    return listed.stdout().lines().map(String::strip).filter(unit -> !unit.isEmpty()).toList();
  }

  /** Writes {@code content} to {@code path} unless it already holds it; whether it wrote. */
  private boolean write(String project, String path, String content)
      throws IOException, InterruptedException, TimeoutException {
    var wrote =
        shell.exec(
            ContainerExec.asDevUser(project, List.of("sh", "-c", WRITE, "sh", content, path)));
    if (!wrote.ok()) {
      throw new IOException("Failed to write " + path + " in " + project + ": " + wrote.reason());
    }
    return wrote.stdout().strip().equals("written");
  }

  private void run(String project, String what, List<String> command)
      throws IOException, InterruptedException, TimeoutException {
    var result = shell.exec(ContainerExec.asDevUser(project, command));
    if (!result.ok()) {
      throw new IOException("Failed to " + what + " in " + project + ": " + result.reason());
    }
  }

  private static List<String> withUnits(List<String> command, List<String> units) {
    var all = new ArrayList<>(command);
    units.forEach(unit -> all.add(unit + ".service"));
    return all;
  }
}
