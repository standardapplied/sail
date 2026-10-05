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
import java.util.Optional;
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
   * @param role the {@code SAIL_RUN_ROLE} the unit was launched with, or {@code ""} when unknown;
   *     carried on the synthesized stop so lane-aware reactors (the review pipeline ignoring {@code
   *     room} stops) never need a store lookup to know what stopped
   */
  public record ExitState(
      boolean active, int exitCode, String specId, String agentType, String runId, String role) {}

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
   * dispatch works in rather than every repo in the shared container. {@code role} is the run's
   * lane ({@code build}, {@code adhoc}, {@code fix}, {@code room}) — the stop gate reads it to skip
   * the git protocol for a chat-lane run that owns no repos.
   */
  public void writeSession(
      String containerName,
      String task,
      String branch,
      String specId,
      String agentType,
      String runId,
      String role,
      List<String> repos,
      AgentUnit unit)
      throws IOException, InterruptedException, TimeoutException {
    var map = new LinkedHashMap<String, Object>();
    map.put("task", task);
    map.put("branch", branch);
    map.put("spec_id", Objects.requireNonNullElse(specId, ""));
    map.put("agent_type", Objects.requireNonNullElse(agentType, ""));
    map.put("run_id", Objects.requireNonNullElse(runId, ""));
    map.put("role", Objects.requireNonNullElse(role, ""));
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

  /**
   * Whether a run's agent is there, as far as its container says. Only {@link Gone} says it is not:
   * whoever finishes a run, releases its claim or finalizes its stop for want of an agent does so
   * on that answer alone.
   */
  public sealed interface Presence {

    /** The agent's process is alive; {@code session} is what its files say of it. */
    record Running(SessionInfo session) implements Presence {}

    /**
     * No live process is the run's agent: the container ran the question and answered so, or — as
     * {@code AgentPresence} adds — the container itself is stopped or does not exist.
     */
    record Gone() implements Presence {}

    /**
     * Nothing is known: the container ran no command, or the unit's manager did not say whether the
     * unit has a process and no pid file named one.
     */
    record Unanswered() implements Presence {}
  }

  /**
   * Says whether the run named by a pid file (its first argument) and a unit (its second, blank for
   * a session that never was one) has a live process, {@code alive} or {@code gone}, from inside
   * the container. It names the process as {@link #queryStatus} does: by the pid file when that
   * holds a pid, and otherwise by the unit's manager. It says either word only when it could tell:
   * a unit whose manager does not answer, with no pid file to name its process, ends it with
   * nothing said — as does an exec that never ran it.
   */
  private static final String PRESENCE_SCRIPT =
      """
      pid=""
      if [ -r "$1" ]; then
        pid="$(tr -d '[:space:]' < "$1" 2>/dev/null)"
      fi
      case "$pid" in
        ''|0|*[!0-9]*) pid="" ;;
      esac
      if [ -z "$pid" ] && [ -n "$2" ]; then
        pid="$(systemctl --user show "$2" --property=MainPID --value)" || exit 1
      fi
      case "$pid" in
        ''|0|*[!0-9]*)
          printf gone
          exit 0
          ;;
      esac
      if kill -0 "$pid" 2>/dev/null; then
        printf alive
      else
        printf gone
      fi
      """;

  /**
   * Asks the container whether the run's agent is there. A reading that finds no live process is
   * not believed as it stands: a container that cannot be reached fails every command, and one lost
   * command reads exactly like a process that is gone. The question is put again as one script in
   * the container, and only what that script prints is taken for an answer.
   */
  public Presence presence(String containerName, AgentUnit unit)
      throws IOException, InterruptedException, TimeoutException {
    var read = queryStatus(containerName, unit);
    if (read != null && read.running()) {
      return new Presence.Running(read);
    }
    var asked =
        shell.exec(
            ContainerExec.asDevUser(
                containerName,
                List.of(
                    "bash",
                    "-c",
                    PRESENCE_SCRIPT,
                    "bash",
                    unit.pidPath(),
                    Strings.isBlank(unit.unitName()) ? "" : unit.service())));
    return switch (asked.ok() ? asked.stdout().strip() : "") {
      case "gone" -> new Presence.Gone();
      case "alive" -> {
        var again = queryStatus(containerName, unit);
        yield again != null && again.running()
            ? new Presence.Running(again)
            : new Presence.Unanswered();
      }
      default -> new Presence.Unanswered();
    };
  }

  /**
   * Queries the given role's session status, for whoever shows it. Returns null if nothing names a
   * process for it. Whoever acts on an agent being absent asks {@code AgentPresence} instead: a
   * null, or a session that is not running, is also what a container that ran no command reads as.
   */
  public SessionInfo queryStatus(String containerName, AgentUnit unit)
      throws IOException, InterruptedException, TimeoutException {
    var pidCmd = ContainerExec.asDevUser(containerName, List.of("cat", unit.pidPath()));
    var pidResult = shell.exec(pidCmd);
    var pid = pidResult.ok() ? parsePid(pidResult.stdout()) : null;
    if (pid == null && !Strings.isBlank(unit.unitName())) {
      pid = querySystemdPid(containerName, unit);
    }
    if (pid == null) {
      return null;
    }
    var aliveCmd =
        ContainerExec.asDevUser(containerName, List.of("kill", "-0", String.valueOf(pid)));
    var alive = shell.exec(aliveCmd).ok();
    var meta = sessionFile(containerName, unit);
    return new SessionInfo(
        alive,
        pid,
        Objects.toString(meta.get("task"), ""),
        Objects.toString(meta.get("started_at"), ""),
        Objects.toString(meta.get("branch"), ""),
        unit.logPath());
  }

  /**
   * The run's session file as a map, empty when it is missing or is not one: the agent can write
   * that file, and what it wrote must never stop its watcher or its stop from reading the rest.
   */
  @SuppressWarnings("unchecked")
  private Map<String, Object> sessionFile(String containerName, AgentUnit unit)
      throws IOException, InterruptedException, TimeoutException {
    var result =
        shell.exec(ContainerExec.asDevUser(containerName, List.of("cat", unit.sessionPath())));
    if (!result.ok() || result.stdout().isBlank()) {
      return Map.of();
    }
    try {
      return (Map<String, Object>) YamlUtil.parseMap(result.stdout());
    } catch (RuntimeException unparseable) {
      return Map.of();
    }
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
   * What a halt did to the agent it was sent to end. Only {@link Ended} says the agent is gone:
   * whoever reports a run ended for a limit, or finalizes an operator's stop, does so on that
   * answer alone.
   */
  public sealed interface Halt {

    /** A signal was delivered and the container answered that the agent is gone. */
    record Ended() implements Halt {}

    /** Every signal was sent and the container answered that the agent is still running. */
    record Survived() implements Halt {}

    /**
     * Nothing is known: the signal could not be delivered, or the container did not answer whether
     * the agent is gone. Nothing was removed or reset, so the run still reads as it did.
     */
    record Unanswered() implements Halt {}
  }

  /** How long an agent sent SIGTERM is given to end before it is sent SIGKILL, in seconds. */
  private static final int TERM_GRACE_SECONDS = 3;

  /** How many times an agent sent SIGKILL is asked whether it is gone, a second apart. */
  private static final int KILL_SETTLE_ASKS = 5;

  /**
   * Halts a session and reports what the halt did. A run that owns a systemd unit is killed through
   * the unit's whole cgroup — SIGTERM to every member, a grace period, then SIGKILL to every member
   * — because the pid file names only the launch wrapper: signalling that single pid orphans the
   * agent's children inside the still-active unit. Only a unitless foreground session falls back to
   * pid-file surgery, where the wrapper {@code exec}'d into the agent and the pid names the whole
   * story: its signals and the question of whether the process is gone run as one script in the
   * container, whose printed answer is the only thing believed — a pid file that could not be read,
   * a signal that could not be delivered and an exec that came back with no answer are all {@link
   * Halt.Unanswered}, never a process taken for gone because a command about it failed. The run's
   * pid file is removed, and its unit reset, only once the agent is {@link Halt.Ended}: a run whose
   * halt is {@link Halt.Survived} or {@link Halt.Unanswered} still probes as it did before, so
   * nothing reads a live agent as gone.
   */
  public Halt killAgent(String containerName, AgentUnit unit)
      throws IOException, InterruptedException, TimeoutException {
    return Strings.isBlank(unit.unitName())
        ? killByPidFile(containerName, unit)
        : killUnit(containerName, unit);
  }

  private Halt killUnit(String containerName, AgentUnit unit)
      throws IOException, InterruptedException, TimeoutException {
    if (!signalUnit(containerName, unit, "SIGTERM")) {
      return new Halt.Unanswered();
    }
    pause(containerName, TERM_GRACE_SECONDS);
    var afterTerm = answeredExitStatus(containerName, unit);
    if (afterTerm.isEmpty()) {
      return new Halt.Unanswered();
    }
    if (!afterTerm.get().active()) {
      return ended(containerName, unit);
    }
    signalUnit(containerName, unit, "SIGKILL");
    for (var ask = 1; ; ask++) {
      var afterKill = answeredExitStatus(containerName, unit);
      if (afterKill.isEmpty()) {
        return new Halt.Unanswered();
      }
      if (!afterKill.get().active()) {
        return ended(containerName, unit);
      }
      if (ask == KILL_SETTLE_ASKS) {
        return new Halt.Survived();
      }
      pause(containerName, 1);
    }
  }

  private boolean signalUnit(String containerName, AgentUnit unit, String signal)
      throws IOException, InterruptedException, TimeoutException {
    return shell
        .exec(
            ContainerExec.asDevUser(
                containerName,
                List.of(
                    "systemctl",
                    "--user",
                    "kill",
                    "--kill-who=all",
                    "--signal=" + signal,
                    unit.service())))
        .ok();
  }

  private void pause(String containerName, int seconds)
      throws IOException, InterruptedException, TimeoutException {
    shell.exec(ContainerExec.asDevUser(containerName, List.of("sleep", String.valueOf(seconds))));
  }

  private Halt ended(String containerName, AgentUnit unit)
      throws IOException, InterruptedException, TimeoutException {
    if (!Strings.isBlank(unit.unitName())) {
      shell.exec(
          ContainerExec.asDevUser(
              containerName, List.of("systemctl", "--user", "reset-failed", unit.service())));
    }
    shell.exec(ContainerExec.asDevUser(containerName, List.of("rm", "-f", unit.pidPath())));
    return new Halt.Ended();
  }

  /**
   * Ends the process whose pid is its first argument and says what became of it, {@code gone} or
   * {@code alive}, from inside the container, by the ladder a unit is ended by: SIGTERM, the grace
   * its second argument gives in seconds, SIGKILL when it is still there, and then as many asks a
   * second apart as its third argument allows. It says either only once its SIGTERM was delivered,
   * so a run of it that printed neither — the signal refused, the exec lost on the way in or out —
   * says nothing of the process.
   */
  private static final String HALT_PID_SCRIPT =
      """
      kill "$1" || exit 1
      sleep "$2"
      if kill -0 "$1" 2>/dev/null; then
        kill -9 "$1"
        asks=0
        while [ "$asks" -lt "$3" ] && kill -0 "$1" 2>/dev/null; do
          sleep 1
          asks=$((asks + 1))
        done
      fi
      if kill -0 "$1" 2>/dev/null; then
        printf alive
      else
        printf gone
      fi
      """;

  private Halt killByPidFile(String containerName, AgentUnit unit)
      throws IOException, InterruptedException, TimeoutException {
    var pidResult =
        shell.exec(ContainerExec.asDevUser(containerName, List.of("cat", unit.pidPath())));
    var pid = pidResult.ok() ? parsePid(pidResult.stdout()) : null;
    if (pid == null) {
      return new Halt.Unanswered();
    }
    var halted =
        shell.exec(
            ContainerExec.asDevUser(
                containerName,
                List.of(
                    "bash",
                    "-c",
                    HALT_PID_SCRIPT,
                    "bash",
                    String.valueOf(pid),
                    String.valueOf(TERM_GRACE_SECONDS),
                    String.valueOf(KILL_SETTLE_ASKS))));
    return switch (halted.ok() ? halted.stdout().strip() : "") {
      case "gone" -> ended(containerName, unit);
      case "alive" -> new Halt.Survived();
      default -> new Halt.Unanswered();
    };
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
    return buildBackgroundLaunchCommand(
        containerName,
        sshUser,
        workDir,
        fullPermissions,
        agentCli,
        model,
        reasoningEffort,
        specId,
        agentType,
        logPath,
        runId,
        runCredential,
        "",
        null);
  }

  /**
   * As the twelve-argument overload, additionally exporting {@code role} as {@code SAIL_RUN_ROLE}
   * (the lane marker hooks and synthesized stops carry) and, when {@code resumeSessionId} is
   * non-null, launching the agent as a headless resume of that recorded conversation instead of a
   * fresh one.
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
      String runCredential,
      String role,
      String resumeSessionId) {
    var cli = Objects.requireNonNullElse(agentCli, AgentCli.CLAUDE_CODE);
    warnIfReasoningEffortDropped(cli, specId, reasoningEffort);
    var unit = AgentUnit.forRun(runId);
    var settingsPath = cli == AgentCli.CLAUDE_CODE ? ClaudeCodeHookConfig.SETTINGS_PATH : null;
    var agentCmd =
        agentCommand(
            cli,
            fullPermissions,
            model,
            reasoningEffort,
            settingsPath,
            role,
            resumeSessionId,
            unit);
    var effectiveSpec = Objects.requireNonNullElse(specId, "");
    var effectiveAgent = agentType == null || agentType.isBlank() ? cli.yamlName() : agentType;
    var script =
        """
        mkdir -p "$1"
        mkdir -p "$(dirname "$4")"
        rm -f "$5"
        : > "$4"
        systemctl --user reset-failed @SERVICE@ >/dev/null 2>&1 || true
        systemd-run --user --setenv "SAIL_SPEC_ID=$6" --setenv "SAIL_AGENT=$7" --setenv "SAIL_RUN_ID=$8" --setenv "SAIL_RUN_CREDENTIAL=$9" --setenv "SAIL_RUN_ROLE=${10}" --unit @UNIT@ bash -lc 'printf "%s\\n" "$$" > "$4"; cd "$1" && exec bash -l -c "$2" > "$3" 2>&1' bash "$2" "$3" "$4" "$5"
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
            Objects.toString(runCredential, ""),
            Objects.toString(role, "")));
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
    return buildForegroundTaskCommand(
        containerName,
        sshUser,
        workDir,
        fullPermissions,
        agentCli,
        model,
        reasoningEffort,
        specId,
        agentType,
        logPath,
        runId,
        runCredential,
        "");
  }

  /** As the twelve-argument overload, additionally exporting {@code role} as SAIL_RUN_ROLE. */
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
      String runCredential,
      String role) {
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
            + " SAIL_RUN_CREDENTIAL=\"$8\" SAIL_RUN_ROLE=\"$9\""
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
            Objects.toString(runCredential, ""),
            Objects.toString(role, "")));
  }

  /**
   * The headless invocation for the run's lane. A {@code room} run gets the harness-restricted chat
   * command — no mutating tools, print-mode default-deny, only the {@code spec} CLI and read-only
   * git auto-approved — regardless of {@code fullPermissions}, so no caller can launch a
   * full-permission chat by mispassing a flag. Every other lane keeps the full-permission dispatch
   * command.
   */
  private static String agentCommand(
      AgentCli cli,
      boolean fullPermissions,
      String model,
      String reasoningEffort,
      String settingsPath,
      String role,
      String resumeSessionId,
      AgentUnit unit) {
    if ("room".equals(role)) {
      return resumeSessionId == null
          ? cli.headlessRoomCommand(unit.taskPath(), model, settingsPath, true)
          : cli.headlessRoomResumeCommand(
              resumeSessionId, unit.taskPath(), model, settingsPath, true);
    }
    return resumeSessionId == null
        ? cli.headlessCommand(
            unit.taskPath(), fullPermissions, model, reasoningEffort, settingsPath, true)
        : cli.headlessResumeCommand(
            resumeSessionId,
            unit.taskPath(),
            fullPermissions,
            model,
            reasoningEffort,
            settingsPath,
            true);
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
   * never fired. Empty when the question was not answered — the container is stopped, incus could
   * not reach it, or the user's systemd manager is not there to ask. Only an answer says a unit is
   * gone: whoever is about to report a run ended asks here, so silence is never taken for an
   * agent's death.
   */
  public Optional<ExitState> answeredExitStatus(String containerName, AgentUnit unit)
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
    return result.ok()
        ? Optional.of(exitState(containerName, unit, result.stdout()))
        : Optional.empty();
  }

  private ExitState exitState(String containerName, AgentUnit unit, String show)
      throws IOException, InterruptedException, TimeoutException {
    var state = parseExitState(show);
    if (!state.specId().isBlank() && !state.runId().isBlank() && !state.role().isBlank()) {
      return state;
    }
    var durable = readSessionDescriptor(containerName, unit);
    return new ExitState(
        state.active(),
        state.exitCode(),
        state.specId().isBlank() ? durable.specId() : state.specId(),
        state.agentType().isBlank() ? durable.agentType() : state.agentType(),
        state.runId().isBlank() ? durable.runId() : state.runId(),
        state.role().isBlank() ? durable.role() : state.role());
  }

  private record SessionDescriptor(String specId, String agentType, String runId, String role) {}

  /**
   * Reads {@code spec_id}/{@code agent_type}/{@code run_id}/{@code role} from the durable session
   * file. Used as the fallback when a collected unit no longer reports its environment; returns
   * blanks for an ad-hoc session.
   */
  private SessionDescriptor readSessionDescriptor(String containerName, AgentUnit unit)
      throws IOException, InterruptedException, TimeoutException {
    var meta = sessionFile(containerName, unit);
    return new SessionDescriptor(
        Objects.toString(meta.get("spec_id"), ""),
        Objects.toString(meta.get("agent_type"), ""),
        Objects.toString(meta.get("run_id"), ""),
        Objects.toString(meta.get("role"), ""));
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
        envValue(environment, "SAIL_RUN_ID"),
        envValue(environment, "SAIL_RUN_ROLE"));
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
