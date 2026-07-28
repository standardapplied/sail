/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.engine;

import ai.singlr.sail.common.Strings;
import ai.singlr.sail.config.SailYaml;
import ai.singlr.sail.config.YamlUtil;
import java.io.IOException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeoutException;

/**
 * Manages headless agent session state inside a container. All operations go through {@link
 * ContainerExec#asDevUser} to execute commands as the dev user.
 */
public final class AgentSession {

  private static final String SAIL_DIR = "/home/dev/.sail";

  private final ShellExec shell;

  public AgentSession(ShellExec shell) {
    this.shell = shell;
  }

  /** Session status information. */
  public record SessionInfo(
      boolean running, int pid, String task, String startedAt, String branch, String logPath) {}

  /**
   * Terminal state of the agent's systemd unit, read straight from systemd — the authoritative
   * source independent of whether the agent's own lifecycle hook fired.
   *
   * @param active whether the unit is still running ({@code false} only once it is inactive/failed)
   * @param exitCode the unit's {@code ExecMainStatus} (the agent process's exit code); meaningful
   *     once {@code active} is {@code false}
   * @param specId the {@code SAIL_SPEC_ID} the unit was launched with, or {@code ""} for an ad-hoc
   *     non-spec session
   * @param agentType the {@code SAIL_AGENT} the unit was launched with, or {@code ""} when unknown
   * @param runId the {@code SAIL_RUN_ID} the unit was launched with, or {@code ""} for an ad-hoc
   *     session that minted no run; carried so a synthesized stop can address the exact run
   */
  public record ExitState(
      boolean active, int exitCode, String specId, String agentType, String runId) {}

  /** Ensures the ~/.sail directory exists inside the container. */
  public void ensureDirectory(String containerName)
      throws IOException, InterruptedException, TimeoutException {
    var cmd = ContainerExec.asDevUser(containerName, List.of("mkdir", "-p", SAIL_DIR));
    var result = shell.exec(cmd);
    if (!result.ok()) {
      throw new IOException("Failed to create " + SAIL_DIR + ": " + result.stderr());
    }
  }

  /**
   * Writes the task/prompt file for the given role's unit (build task or review prompt). Uses
   * printf with a positional argument to avoid heredoc injection (content containing the delimiter
   * could escape the heredoc). Creates the file's parent directory first: a run-scoped unit lives
   * under {@code ~/.sail/runs/<runId>/}, which does not exist yet when the launcher stages the task
   * before launch.
   */
  public void writeTaskFile(String containerName, String task, AgentUnit unit)
      throws IOException, InterruptedException, TimeoutException {
    var cmd =
        ContainerExec.asDevUser(
            containerName,
            List.of(
                "bash",
                "-c",
                "mkdir -p \"$(dirname \"$2\")\" && printf '%s' \"$1\" > \"$2\"",
                "bash",
                task,
                unit.taskPath()));
    var result = shell.exec(cmd);
    if (!result.ok()) {
      throw new IOException("Failed to write task file: " + result.stderr());
    }
  }

  /**
   * Writes session metadata JSON for the given role's unit (its own session file and log path). The
   * {@code specId}, {@code agentType}, and {@code runId} are the durable record of what this launch
   * executes: the systemd unit's environment carries them too, but a successfully-exited transient
   * unit is garbage-collected within seconds, taking its environment with it. The watcher therefore
   * recovers them from this file when the unit is already gone, so a clean agent exit still
   * produces a run-addressed stop signal. {@code repos} is the spec's resolved repo set (empty for
   * an ad-hoc session), so the stop gate can scope its readiness checks to exactly the repos a
   * dispatch works in rather than every repo in the shared container.
   */
  public void writeSession(
      String containerName,
      String task,
      String branch,
      String specId,
      String agentType,
      String runId,
      List<String> repos,
      AgentUnit unit)
      throws IOException, InterruptedException, TimeoutException {
    var map = new LinkedHashMap<String, Object>();
    map.put("task", task);
    map.put("branch", branch);
    map.put("spec_id", Objects.requireNonNullElse(specId, ""));
    map.put("agent_type", Objects.requireNonNullElse(agentType, ""));
    map.put("run_id", Objects.requireNonNullElse(runId, ""));
    map.put("repos", Objects.requireNonNullElse(repos, List.<String>of()));
    map.put("started_at", Instant.now().toString());
    map.put("log_path", unit.logPath());
    var json = YamlUtil.dumpJson(map);
    var cmd =
        ContainerExec.asDevUser(
            containerName,
            List.of(
                "bash",
                "-c",
                "mkdir -p \"$(dirname \"$2\")\" && printf '%s' \"$1\" > \"$2\"",
                "bash",
                json,
                unit.sessionPath()));
    var result = shell.exec(cmd);
    if (!result.ok()) {
      throw new IOException("Failed to write session metadata: " + result.stderr());
    }
  }

  /** Queries the given role's session status. Returns null if no session exists for it. */
  @SuppressWarnings("unchecked")
  public SessionInfo queryStatus(String containerName, AgentUnit unit)
      throws IOException, InterruptedException, TimeoutException {
    var pidCmd = ContainerExec.asDevUser(containerName, List.of("cat", unit.pidPath()));
    var pidResult = shell.exec(pidCmd);
    var parsedPid = pidResult.ok() ? parsePid(pidResult.stdout()) : null;
    if (parsedPid == null) {
      parsedPid = querySystemdPid(containerName, unit);
    }
    if (parsedPid == null) {
      return null;
    }

    var pid = parsedPid;

    var aliveCmd =
        ContainerExec.asDevUser(containerName, List.of("kill", "-0", String.valueOf(pid)));
    var alive = shell.exec(aliveCmd).ok();

    var sessionCmd = ContainerExec.asDevUser(containerName, List.of("cat", unit.sessionPath()));
    var sessionResult = shell.exec(sessionCmd);
    var task = "";
    var startedAt = "";
    var branch = "";
    if (sessionResult.ok() && !sessionResult.stdout().isBlank()) {
      var meta = (Map<String, Object>) YamlUtil.parseMap(sessionResult.stdout());
      task = Objects.toString(meta.get("task"), "");
      startedAt = Objects.toString(meta.get("started_at"), "");
      branch = Objects.toString(meta.get("branch"), "");
    }

    return new SessionInfo(alive, pid, task, startedAt, branch, unit.logPath());
  }

  /**
   * Reads a container process's non-reusable start fingerprint: the {@code starttime} field of
   * {@code /proc/<pid>/stat}, in clock ticks since boot. The kernel reuses numeric pids, so a pid
   * alone can later name an unrelated process; two processes assigned the same pid can never share
   * a start time, so a fingerprint persisted at launch distinguishes the process a run launched
   * from any later occupant of its pid. Returns null when the process is gone or unreadable.
   */
  public Long readProcessStartTicks(String containerName, int pid)
      throws IOException, InterruptedException, TimeoutException {
    var cmd = ContainerExec.asDevUser(containerName, List.of("cat", "/proc/" + pid + "/stat"));
    var result = shell.exec(cmd);
    return result.ok() ? parseStartTicks(result.stdout()) : null;
  }

  /**
   * Field 22 of the stat line, located after the last {@code ')'} because the comm field may itself
   * contain spaces or parentheses.
   */
  static Long parseStartTicks(String stat) {
    var close = stat.lastIndexOf(')');
    if (close < 0) {
      return null;
    }
    var fields = stat.substring(close + 1).trim().split("\\s+");
    if (fields.length < 20) {
      return null;
    }
    try {
      return Long.parseLong(fields[19]);
    } catch (NumberFormatException e) {
      return null;
    }
  }

  /**
   * Whether the unit is active in the container's user systemd manager. Unlike {@link #queryStatus}
   * this never falls back to the run's pid file, so it distinguishes launch modes: a background
   * session runs as its recorded unit, a foreground session only writes the pid file.
   */
  public boolean unitActive(String containerName, AgentUnit unit)
      throws IOException, InterruptedException, TimeoutException {
    var cmd =
        ContainerExec.asDevUser(
            containerName, List.of("systemctl", "--user", "--quiet", "is-active", unit.service()));
    return shell.exec(cmd).ok();
  }

  /**
   * Kills the given role's running agent inside the container. SIGTERM first, then SIGKILL. A
   * SIGKILL that fails against a still-live process throws instead of returning normally, so a
   * caller can never record a successful stop for an agent that survived the signal.
   */
  /**
   * Halts a session. A run that owns a systemd unit is killed through the unit's whole cgroup —
   * SIGTERM to every member, a grace period, then SIGKILL to every member — because the pid file
   * names only the launch wrapper: signalling that single pid orphans the agent's children inside
   * the still-active unit, which is exactly the incomplete halt the verified stop then correctly
   * refuses. Only a unitless foreground session falls back to pid-file surgery, where the wrapper
   * {@code exec}'d into the agent and the pid names the whole story.
   */
  public void killAgent(String containerName, AgentUnit unit)
      throws IOException, InterruptedException, TimeoutException {
    if (!Strings.isBlank(unit.unitName())) {
      killUnit(containerName, unit);
      return;
    }
    killByPidFile(containerName, unit);
  }

  private void killUnit(String containerName, AgentUnit unit)
      throws IOException, InterruptedException, TimeoutException {
    var service = unit.service();
    shell.exec(
        ContainerExec.asDevUser(
            containerName,
            List.of("systemctl", "--user", "kill", "--kill-who=all", "--signal=SIGTERM", service)));

    shell.exec(ContainerExec.asDevUser(containerName, List.of("sleep", "3")));

    if (unitActive(containerName, unit)) {
      var kill =
          shell.exec(
              ContainerExec.asDevUser(
                  containerName,
                  List.of(
                      "systemctl",
                      "--user",
                      "kill",
                      "--kill-who=all",
                      "--signal=SIGKILL",
                      service)));
      if (!kill.ok() && unitActive(containerName, unit)) {
        throw new IOException(
            "SIGKILL for unit "
                + service
                + " in "
                + containerName
                + " failed: "
                + kill.stderr().trim()
                + ". Check the unit in the container and retry the stop.");
      }
    }

    shell.exec(
        ContainerExec.asDevUser(
            containerName, List.of("systemctl", "--user", "reset-failed", service)));
    shell.exec(ContainerExec.asDevUser(containerName, List.of("rm", "-f", unit.pidPath())));
  }

  private void killByPidFile(String containerName, AgentUnit unit)
      throws IOException, InterruptedException, TimeoutException {
    var pidCmd = ContainerExec.asDevUser(containerName, List.of("cat", unit.pidPath()));
    var pidResult = shell.exec(pidCmd);
    if (!pidResult.ok() || pidResult.stdout().isBlank()) {
      return;
    }

    var pidStr = pidResult.stdout().trim();
    try {
      Integer.parseInt(pidStr);
    } catch (NumberFormatException e) {
      return;
    }

    shell.exec(ContainerExec.asDevUser(containerName, List.of("kill", pidStr)));

    shell.exec(ContainerExec.asDevUser(containerName, List.of("sleep", "3")));

    var aliveCmd = ContainerExec.asDevUser(containerName, List.of("kill", "-0", pidStr));
    if (shell.exec(aliveCmd).ok()) {
      var kill = shell.exec(ContainerExec.asDevUser(containerName, List.of("kill", "-9", pidStr)));
      if (!kill.ok() && shell.exec(aliveCmd).ok()) {
        throw new IOException(
            "SIGKILL for agent PID "
                + pidStr
                + " in "
                + containerName
                + " failed: "
                + kill.stderr().trim()
                + ". Check the process in the container and retry the stop.");
      }
    }

    shell.exec(ContainerExec.asDevUser(containerName, List.of("rm", "-f", unit.pidPath())));
  }

  public static String launchWorkDir(String sshUser, List<SailYaml.Repo> targetRepos) {
    var workspace = "/home/" + sshUser + "/workspace";
    if (targetRepos.size() == 1) {
      return workspace + "/" + targetRepos.getFirst().path();
    }
    return workspace;
  }

  /**
   * Builds the {@code incus exec} command launching a headless agent in detached/background mode
   * under the run's own identity: unit {@code sail-agent-<runId>}, stdout/stderr redirected to
   * {@code logPath} ({@code ~/.sail/runs/<runId>/agent.log}), and pid/task files under the run
   * directory — so concurrent executions never collide on a unit name or clobber a shared file, and
   * a log address names exactly one execution. The task is read from a file inside the container to
   * avoid shell escaping issues, and the log's parent directory is created before the redirect.
   * {@code specId} flows into the spawned agent's environment as {@code SAIL_SPEC_ID} (blank for an
   * ad-hoc session, which makes the in-container hook script no-op); {@code agentType} flows in as
   * {@code SAIL_AGENT}, defaulting to the CLI's yaml name when blank; {@code runId} flows in as
   * {@code SAIL_RUN_ID} so the agent's hooks and the watcher can address terminal events at the
   * exact run; {@code runCredential} flows in as {@code SAIL_RUN_CREDENTIAL}, the credential the
   * in-container helpers present to the local API so every request resolves to this run's minted
   * principal.
   */
  public static List<String> buildBackgroundLaunchCommand(
      String containerName,
      String sshUser,
      String workDir,
      boolean fullPermissions,
      AgentCli agentCli,
      String model,
      String reasoningEffort,
      String specId,
      String agentType,
      String logPath,
      String runId,
      String runCredential) {
    var cli = Objects.requireNonNullElse(agentCli, AgentCli.CLAUDE_CODE);
    warnIfReasoningEffortDropped(cli, specId, reasoningEffort);
    var unit = AgentUnit.forRun(runId);
    var settingsPath = cli == AgentCli.CLAUDE_CODE ? ClaudeCodeHookConfig.SETTINGS_PATH : null;
    var agentCmd =
        cli.headlessCommand(
            unit.taskPath(), fullPermissions, model, reasoningEffort, settingsPath, true);
    var effectiveSpec = Objects.requireNonNullElse(specId, "");
    var effectiveAgent = agentType == null || agentType.isBlank() ? cli.yamlName() : agentType;
    var script =
        """
        mkdir -p "$1"
        mkdir -p "$(dirname "$4")"
        rm -f "$5"
        : > "$4"
        systemctl --user reset-failed @SERVICE@ >/dev/null 2>&1 || true
        systemd-run --user --setenv "SAIL_SPEC_ID=$6" --setenv "SAIL_AGENT=$7" --setenv "SAIL_RUN_ID=$8" --setenv "SAIL_RUN_CREDENTIAL=$9" --unit @UNIT@ bash -lc 'printf "%s\\n" "$$" > "$4"; cd "$1" && exec bash -l -c "$2" > "$3" 2>&1' bash "$2" "$3" "$4" "$5"
        for i in $(seq 1 25); do
          test -s "$5" && exit 0
          pid="$(systemctl --user show @SERVICE@ --property=MainPID --value 2>/dev/null || true)"
          case "$pid" in
            ''|0|*[!0-9]*) ;;
            *) printf '%s\\n' "$pid" > "$5"; exit 0 ;;
          esac
          sleep 0.2
        done
        systemctl --user status @SERVICE@ --no-pager || true
        exit 1
        """
            .replace("@SERVICE@", unit.service())
            .replace("@UNIT@", unit.unitName());
    return ContainerExec.asDevUser(
        containerName,
        List.of(
            "bash",
            "-lc",
            script,
            "bash",
            SAIL_DIR,
            workDir,
            agentCmd,
            logPath,
            unit.pidPath(),
            effectiveSpec,
            effectiveAgent,
            runId,
            Objects.toString(runCredential, "")));
  }

  /**
   * The foreground launcher: like {@link #buildBackgroundLaunchCommand} but blocking, with {@code
   * runId} carried in as {@code SAIL_RUN_ID} and the agent's stdout/stderr redirected to the
   * run-scoped {@code logPath}, so a foreground session's recorded log address names a file that
   * actually holds its output and its terminal hook events can address the exact run. The wrapper
   * writes its own pid to the run's pid file and {@code exec}s into the agent, so a foreground
   * session — which owns no systemd unit — is still probeable and stoppable through the same
   * run-scoped identity as a background one.
   */
  public static List<String> buildForegroundTaskCommand(
      String containerName,
      String sshUser,
      String workDir,
      boolean fullPermissions,
      AgentCli agentCli,
      String model,
      String reasoningEffort,
      String specId,
      String agentType,
      String logPath,
      String runId,
      String runCredential) {
    var cli = Objects.requireNonNullElse(agentCli, AgentCli.CLAUDE_CODE);
    warnIfReasoningEffortDropped(cli, specId, reasoningEffort);
    var unit = AgentUnit.forRun(runId);
    var settingsPath = cli == AgentCli.CLAUDE_CODE ? ClaudeCodeHookConfig.SETTINGS_PATH : null;
    var agentCmd =
        cli.headlessCommand(unit.taskPath(), fullPermissions, model, reasoningEffort, settingsPath);
    var effectiveSpec = Objects.requireNonNullElse(specId, "");
    var effectiveAgent = agentType == null || agentType.isBlank() ? cli.yamlName() : agentType;
    var script =
        "mkdir -p \"$(dirname \"$5\")\"; printf '%s\\n' \"$$\" > \"$7\"; cd \"$1\" && "
            + "SAIL_SPEC_ID=\"$3\" SAIL_AGENT=\"$4\" SAIL_RUN_ID=\"$6\""
            + " SAIL_RUN_CREDENTIAL=\"$8\""
            + " exec bash -l -c \"$2\" > \"$5\" 2>&1";
    return ContainerExec.asDevUser(
        containerName,
        List.of(
            "bash",
            "-l",
            "-c",
            script,
            "bash",
            workDir,
            agentCmd,
            effectiveSpec,
            effectiveAgent,
            logPath,
            runId,
            unit.pidPath(),
            Objects.toString(runCredential, "")));
  }

  private static void warnIfReasoningEffortDropped(
      AgentCli cli, String specId, String reasoningEffort) {
    if (cli != AgentCli.CLAUDE_CODE || Strings.isBlank(reasoningEffort)) {
      return;
    }
    var spec = Strings.isBlank(specId) ? "this launch" : "spec " + specId;
    System.err.println(
        "  ⚠ Claude Code has no reasoning_effort setting; dropping reasoning_effort='"
            + reasoningEffort
            + "' for "
            + spec
            + ". Only Codex honors reasoning_effort.");
  }

  /**
   * Reads the given role's unit terminal state from systemd in a single call: liveness, exit code,
   * and the spec/agent it was launched for (parsed from the unit's recorded environment). Lets the
   * watcher detect an exit and synthesize a reliable stop signal even when the agent's own hook
   * never fired.
   */
  public ExitState queryExitStatus(String containerName, AgentUnit unit)
      throws IOException, InterruptedException, TimeoutException {
    var cmd =
        ContainerExec.asDevUser(
            containerName,
            List.of(
                "systemctl",
                "--user",
                "show",
                unit.service(),
                "--property=ActiveState",
                "--property=ExecMainStatus",
                "--property=Environment"));
    var result = shell.exec(cmd);
    var state = parseExitState(result.ok() ? result.stdout() : "");
    if (!state.specId().isBlank() && !state.runId().isBlank()) {
      return state;
    }
    var durable = readSessionDescriptor(containerName, unit);
    return new ExitState(
        state.active(),
        state.exitCode(),
        state.specId().isBlank() ? durable.specId() : state.specId(),
        state.agentType().isBlank() ? durable.agentType() : state.agentType(),
        state.runId().isBlank() ? durable.runId() : state.runId());
  }

  private record SessionDescriptor(String specId, String agentType, String runId) {}

  /**
   * Reads {@code spec_id}/{@code agent_type}/{@code run_id} from the durable session file. Used as
   * the fallback when a collected unit no longer reports its environment; returns blanks for an
   * ad-hoc session.
   */
  @SuppressWarnings("unchecked")
  private SessionDescriptor readSessionDescriptor(String containerName, AgentUnit unit)
      throws IOException, InterruptedException, TimeoutException {
    var cmd = ContainerExec.asDevUser(containerName, List.of("cat", unit.sessionPath()));
    var result = shell.exec(cmd);
    if (!result.ok() || result.stdout().isBlank()) {
      return new SessionDescriptor("", "", "");
    }
    var meta = (Map<String, Object>) YamlUtil.parseMap(result.stdout());
    return new SessionDescriptor(
        Objects.toString(meta.get("spec_id"), ""),
        Objects.toString(meta.get("agent_type"), ""),
        Objects.toString(meta.get("run_id"), ""));
  }

  static ExitState parseExitState(String show) {
    var activeState = "";
    var exitCode = 0;
    var environment = "";
    for (var line : show.split("\n")) {
      var eq = line.indexOf('=');
      if (eq < 0) {
        continue;
      }
      var key = line.substring(0, eq);
      var value = line.substring(eq + 1).trim();
      switch (key) {
        case "ActiveState" -> activeState = value;
        case "ExecMainStatus" -> exitCode = parseIntOrZero(value);
        case "Environment" -> environment = value;
        default -> {}
      }
    }
    var active = !("inactive".equals(activeState) || "failed".equals(activeState));
    return new ExitState(
        active,
        exitCode,
        envValue(environment, "SAIL_SPEC_ID"),
        envValue(environment, "SAIL_AGENT"),
        envValue(environment, "SAIL_RUN_ID"));
  }

  private static String envValue(String environment, String key) {
    var prefix = key + "=";
    for (var token : environment.split(" ")) {
      if (token.startsWith(prefix)) {
        return token.substring(prefix.length());
      }
    }
    return "";
  }

  private static int parseIntOrZero(String value) {
    try {
      return Integer.parseInt(value.trim());
    } catch (NumberFormatException e) {
      return 0;
    }
  }

  private Integer querySystemdPid(String containerName, AgentUnit unit)
      throws IOException, InterruptedException, TimeoutException {
    var cmd =
        ContainerExec.asDevUser(
            containerName,
            List.of(
                "systemctl", "--user", "show", unit.service(), "--property=MainPID", "--value"));
    var result = shell.exec(cmd);
    return result.ok() ? parsePid(result.stdout()) : null;
  }

  private static Integer parsePid(String value) {
    try {
      var pid = Integer.parseInt(value.trim());
      return pid > 0 ? pid : null;
    } catch (NumberFormatException e) {
      return null;
    }
  }
}
