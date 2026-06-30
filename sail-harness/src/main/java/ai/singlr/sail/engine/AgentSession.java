/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.engine;

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
  private static final String PID_FILE = SAIL_DIR + "/agent.pid";
  private static final String LOG_FILE = SAIL_DIR + "/agent.log";
  private static final String SESSION_FILE = SAIL_DIR + "/agent-session.json";
  private static final String TASK_FILE = SAIL_DIR + "/agent-task.txt";
  private static final String SYSTEMD_UNIT = "sail-agent.service";

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
   */
  public record ExitState(boolean active, int exitCode, String specId, String agentType) {}

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
   * Writes the task text to a file inside the container. Uses printf with a positional argument to
   * avoid heredoc injection (content containing the delimiter could escape the heredoc).
   */
  public void writeTaskFile(String containerName, String task)
      throws IOException, InterruptedException, TimeoutException {
    var cmd =
        ContainerExec.asDevUser(
            containerName,
            List.of("bash", "-c", "printf '%s' \"$1\" > \"$2\"", "bash", task, TASK_FILE));
    var result = shell.exec(cmd);
    if (!result.ok()) {
      throw new IOException("Failed to write task file: " + result.stderr());
    }
  }

  /** Writes session metadata JSON inside the container for an ad-hoc (non-spec) launch. */
  public void writeSession(String containerName, String task, String branch)
      throws IOException, InterruptedException, TimeoutException {
    writeSession(containerName, task, branch, "", "");
  }

  /**
   * Writes session metadata JSON inside the container. The {@code specId} and {@code agentType} are
   * the durable record of which spec this dispatch is for: the systemd unit's environment carries
   * them too, but a successfully-exited transient unit is garbage-collected within seconds, taking
   * its environment with it. The watcher therefore recovers them from this file when the unit is
   * already gone, so a clean agent exit still produces a spec-attributed stop signal.
   */
  public void writeSession(
      String containerName, String task, String branch, String specId, String agentType)
      throws IOException, InterruptedException, TimeoutException {
    var map = new LinkedHashMap<String, Object>();
    map.put("task", task);
    map.put("branch", branch);
    map.put("spec_id", Objects.requireNonNullElse(specId, ""));
    map.put("agent_type", Objects.requireNonNullElse(agentType, ""));
    map.put("started_at", Instant.now().toString());
    map.put("log_path", LOG_FILE);
    var json = YamlUtil.dumpJson(map);
    var cmd =
        ContainerExec.asDevUser(
            containerName,
            List.of("bash", "-c", "printf '%s' \"$1\" > \"$2\"", "bash", json, SESSION_FILE));
    var result = shell.exec(cmd);
    if (!result.ok()) {
      throw new IOException("Failed to write session metadata: " + result.stderr());
    }
  }

  /** Queries the current agent session status. Returns null if no session exists. */
  @SuppressWarnings("unchecked")
  public SessionInfo queryStatus(String containerName)
      throws IOException, InterruptedException, TimeoutException {
    var pidCmd = ContainerExec.asDevUser(containerName, List.of("cat", PID_FILE));
    var pidResult = shell.exec(pidCmd);
    var parsedPid = pidResult.ok() ? parsePid(pidResult.stdout()) : null;
    if (parsedPid == null) {
      parsedPid = querySystemdPid(containerName);
    }
    if (parsedPid == null) {
      return null;
    }

    var pid = parsedPid;

    var aliveCmd =
        ContainerExec.asDevUser(containerName, List.of("kill", "-0", String.valueOf(pid)));
    var alive = shell.exec(aliveCmd).ok();

    var sessionCmd = ContainerExec.asDevUser(containerName, List.of("cat", SESSION_FILE));
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

    return new SessionInfo(alive, pid, task, startedAt, branch, LOG_FILE);
  }

  /** Kills a running agent process inside the container. SIGTERM first, then SIGKILL. */
  public void killAgent(String containerName)
      throws IOException, InterruptedException, TimeoutException {
    var pidCmd = ContainerExec.asDevUser(containerName, List.of("cat", PID_FILE));
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
      shell.exec(ContainerExec.asDevUser(containerName, List.of("kill", "-9", pidStr)));
    }

    shell.exec(ContainerExec.asDevUser(containerName, List.of("rm", "-f", PID_FILE)));
  }

  /**
   * Builds an {@code incus exec} command for launching an agent in detached/background mode. The
   * task is read from a file inside the container to avoid shell escaping issues.
   *
   * @param agentCli the agent CLI enum (determines headless command syntax)
   */
  public static List<String> buildBackgroundLaunchCommand(
      String containerName,
      String sshUser,
      String workDir,
      boolean fullPermissions,
      AgentCli agentCli) {
    return buildBackgroundLaunchCommand(
        containerName, sshUser, workDir, fullPermissions, agentCli, null, null, null, null);
  }

  public static String launchWorkDir(String sshUser, List<SailYaml.Repo> targetRepos) {
    var workspace = "/home/" + sshUser + "/workspace";
    if (targetRepos.size() == 1) {
      return workspace + "/" + targetRepos.getFirst().path();
    }
    return workspace;
  }

  public static List<String> buildBackgroundLaunchCommand(
      String containerName,
      String sshUser,
      String workDir,
      boolean fullPermissions,
      AgentCli agentCli,
      String model,
      String reasoningEffort) {
    return buildBackgroundLaunchCommand(
        containerName,
        sshUser,
        workDir,
        fullPermissions,
        agentCli,
        model,
        reasoningEffort,
        null,
        null);
  }

  /**
   * Same as the simpler overload, with two extra inputs used to correlate hook events back to a
   * specific spec dispatch:
   *
   * <ul>
   *   <li>{@code specId} — flows into the spawned agent's environment as {@code SAIL_SPEC_ID}; an
   *       empty or {@code null} value signals a non-spec ad-hoc launch, in which case the in-
   *       container hook script no-ops instead of publishing events.
   *   <li>{@code agentType} — flows in as {@code SAIL_AGENT}; defaults to the CLI's yaml name when
   *       blank.
   * </ul>
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
      String agentType) {
    var cli = Objects.requireNonNullElse(agentCli, AgentCli.CLAUDE_CODE);
    var settingsPath = cli == AgentCli.CLAUDE_CODE ? ClaudeCodeHookConfig.SETTINGS_PATH : null;
    var agentCmd =
        cli.headlessCommand(TASK_FILE, fullPermissions, model, reasoningEffort, settingsPath, true);
    var effectiveSpec = Objects.requireNonNullElse(specId, "");
    var effectiveAgent = agentType == null || agentType.isBlank() ? cli.yamlName() : agentType;
    var script =
        """
        mkdir -p "$1"
        rm -f "$5"
        : > "$4"
        systemctl --user reset-failed sail-agent.service >/dev/null 2>&1 || true
        systemd-run --user --setenv "SAIL_SPEC_ID=$6" --setenv "SAIL_AGENT=$7" --unit sail-agent bash -lc 'printf "%s\\n" "$$" > "$4"; cd "$1" && exec bash -l -c "$2" > "$3" 2>&1' bash "$2" "$3" "$4" "$5"
        for i in $(seq 1 25); do
          test -s "$5" && exit 0
          pid="$(systemctl --user show sail-agent.service --property=MainPID --value 2>/dev/null || true)"
          case "$pid" in
            ''|0|*[!0-9]*) ;;
            *) printf '%s\\n' "$pid" > "$5"; exit 0 ;;
          esac
          sleep 0.2
        done
        systemctl --user status sail-agent.service --no-pager || true
        exit 1
        """;
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
            LOG_FILE,
            PID_FILE,
            effectiveSpec,
            effectiveAgent));
  }

  /**
   * Builds an {@code incus exec} command for launching an agent in interactive headless mode
   * (foreground, with task). The task is read from a file to avoid escaping issues.
   *
   * @param agentCli the agent CLI enum (determines headless command syntax)
   */
  public static List<String> buildForegroundTaskCommand(
      String containerName,
      String sshUser,
      String workDir,
      boolean fullPermissions,
      AgentCli agentCli) {
    return buildForegroundTaskCommand(
        containerName, sshUser, workDir, fullPermissions, agentCli, null, null, null, null);
  }

  public static List<String> buildForegroundTaskCommand(
      String containerName,
      String sshUser,
      String workDir,
      boolean fullPermissions,
      AgentCli agentCli,
      String model,
      String reasoningEffort) {
    return buildForegroundTaskCommand(
        containerName,
        sshUser,
        workDir,
        fullPermissions,
        agentCli,
        model,
        reasoningEffort,
        null,
        null);
  }

  /**
   * Same as the simpler overload, plus {@code specId} and {@code agentType} for hook attribution.
   * See {@link #buildBackgroundLaunchCommand(String, String, String, boolean, AgentCli, String,
   * String, String, String)}.
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
      String agentType) {
    var cli = Objects.requireNonNullElse(agentCli, AgentCli.CLAUDE_CODE);
    var settingsPath = cli == AgentCli.CLAUDE_CODE ? ClaudeCodeHookConfig.SETTINGS_PATH : null;
    var agentCmd =
        cli.headlessCommand(TASK_FILE, fullPermissions, model, reasoningEffort, settingsPath);
    var effectiveSpec = Objects.requireNonNullElse(specId, "");
    var effectiveAgent = agentType == null || agentType.isBlank() ? cli.yamlName() : agentType;
    var script = "cd \"$1\" && SAIL_SPEC_ID=\"$3\" SAIL_AGENT=\"$4\" bash -l -c \"$2\"";
    return ContainerExec.asDevUser(
        containerName,
        List.of(
            "bash", "-l", "-c", script, "bash", workDir, agentCmd, effectiveSpec, effectiveAgent));
  }

  /** Returns the path to the agent log file inside the container. */
  public static String logPath() {
    return LOG_FILE;
  }

  /**
   * Reads the agent unit's terminal state from systemd in a single call: liveness, exit code, and
   * the spec/agent it was launched for (parsed from the unit's recorded environment). Lets the
   * watcher detect an exit and synthesize a reliable stop signal even when the agent's own hook
   * never fired.
   */
  public ExitState queryExitStatus(String containerName)
      throws IOException, InterruptedException, TimeoutException {
    var cmd =
        ContainerExec.asDevUser(
            containerName,
            List.of(
                "systemctl",
                "--user",
                "show",
                SYSTEMD_UNIT,
                "--property=ActiveState",
                "--property=ExecMainStatus",
                "--property=Environment"));
    var result = shell.exec(cmd);
    var state = parseExitState(result.ok() ? result.stdout() : "");
    if (!state.specId().isBlank()) {
      return state;
    }
    var durable = readSessionDescriptor(containerName);
    return new ExitState(
        state.active(),
        state.exitCode(),
        durable.specId(),
        state.agentType().isBlank() ? durable.agentType() : state.agentType());
  }

  private record SessionDescriptor(String specId, String agentType) {}

  /**
   * Reads {@code spec_id}/{@code agent_type} from the durable session file. Used as the fallback
   * when a collected unit no longer reports its environment; returns blanks for an ad-hoc session.
   */
  @SuppressWarnings("unchecked")
  private SessionDescriptor readSessionDescriptor(String containerName)
      throws IOException, InterruptedException, TimeoutException {
    var cmd = ContainerExec.asDevUser(containerName, List.of("cat", SESSION_FILE));
    var result = shell.exec(cmd);
    if (!result.ok() || result.stdout().isBlank()) {
      return new SessionDescriptor("", "");
    }
    var meta = (Map<String, Object>) YamlUtil.parseMap(result.stdout());
    return new SessionDescriptor(
        Objects.toString(meta.get("spec_id"), ""), Objects.toString(meta.get("agent_type"), ""));
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
        envValue(environment, "SAIL_AGENT"));
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

  private Integer querySystemdPid(String containerName)
      throws IOException, InterruptedException, TimeoutException {
    var cmd =
        ContainerExec.asDevUser(
            containerName,
            List.of("systemctl", "--user", "show", SYSTEMD_UNIT, "--property=MainPID", "--value"));
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
