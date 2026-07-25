/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.commands;

import ai.singlr.sail.api.Event;
import ai.singlr.sail.api.EventStreamClient;
import ai.singlr.sail.api.SailEventPublisher;
import ai.singlr.sail.api.ServerConnectionConfig;
import ai.singlr.sail.common.DateTimeUtils;
import ai.singlr.sail.common.Strings;
import ai.singlr.sail.config.Guardrails;
import ai.singlr.sail.config.Notifications;
import ai.singlr.sail.config.SailYaml;
import ai.singlr.sail.config.YamlUtil;
import ai.singlr.sail.engine.AgentSession;
import ai.singlr.sail.engine.AgentUnit;
import ai.singlr.sail.engine.ContainerExec;
import ai.singlr.sail.engine.ContainerManager;
import ai.singlr.sail.engine.ContainerStateGuard;
import ai.singlr.sail.engine.GuardrailChecker;
import ai.singlr.sail.engine.HostInfo;
import ai.singlr.sail.engine.NameValidator;
import ai.singlr.sail.engine.SailPaths;
import ai.singlr.sail.engine.ShellExecutor;
import ai.singlr.sail.engine.SnapshotManager;
import ai.singlr.sail.engine.WebhookNotifier;
import java.nio.file.Files;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import picocli.CommandLine.Command;
import picocli.CommandLine.Help.Ansi;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Spec;

/**
 * Long-running watcher that enforces a dispatched agent's guardrails: a wall-clock ceiling ({@code
 * max_duration}) and an idle/stall window ({@code max_idle}). Event-driven: subscribes to the
 * sail-api {@code /v1/events/stream} and, via {@code BlockingQueue.poll}, wakes the moment the next
 * deadline fires — the wall-clock deadline or the stall deadline (last progress event + {@code
 * max_idle}), whichever is sooner. Progress events (tool calls, log chunks) push the stall deadline
 * out, so a long but active build is never mistaken for a hung agent. The stall clock starts at
 * watch launch, not session start: idleness the watcher never observed is not idleness, which
 * matters when the daemon re-arms a watcher onto a session already hours into its run. The
 * wall-clock deadline stays anchored to the session's {@code started_at}, so a re-armed agent keeps
 * only its remaining budget. Supervision is on by default (see {@code Guardrails.defaults()});
 * sail.yaml overrides every threshold and the action.
 *
 * <p>A dispatched run's watcher is run-addressed: {@code --run} names the run whose agent it
 * supervises and {@code --unit} the systemd unit that run was launched as (recorded on the run,
 * never re-derived here). Progress events then reset the stall timer only when they carry this
 * run's id, so a concurrent run's tool calls in the same container never keep a hung agent alive.
 * Without {@code --run} the watcher supervises the fixed ad-hoc identity, the {@code sail agent
 * start} lane.
 */
@Command(
    name = "watch",
    description = "Monitor a running agent and enforce guardrails.",
    mixinStandardHelpOptions = true)
public final class AgentWatchCommand implements Runnable {

  /**
   * Upper bound on how long the loop sleeps between systemd liveness polls. Caps the event wait so
   * an agent that exits without its hook firing is detected within this window rather than at the
   * (possibly hours-away) wall-clock deadline.
   */
  private static final long LIVENESS_POLL_MS = 15_000;

  @Parameters(
      index = "0",
      arity = "0..1",
      description = "Project name (default: the current project).")
  private String name;

  @Option(
      names = "--run",
      description = "Run id of the dispatched agent to supervise (default: the ad-hoc session).")
  private String runId;

  @Option(
      names = "--unit",
      description =
          "Systemd unit the run was launched as, as recorded on the run (default: derived from"
              + " --run).")
  private String unitName;

  @Option(names = "--dry-run", description = "Print actions instead of executing them.")
  private boolean dryRun;

  @Option(names = "--json", description = "Output in JSON format.")
  private boolean json;

  @Option(
      names = {"-f", "--file"},
      description = "Path to sail.yaml project descriptor.",
      defaultValue = "sail.yaml")
  private String file;

  @Option(names = "--host", description = "sail-api host.", defaultValue = "127.0.0.1")
  private String apiHost;

  @Option(names = "--port", description = "sail-api port.", defaultValue = "7070")
  private int apiPort;

  @Spec private CommandSpec commandSpec;

  private AgentUnit unit;

  @Override
  public void run() {
    CliCommand.run(commandSpec, this::execute);
  }

  /**
   * The identity this watcher supervises: the recorded unit of {@code --run} (derived only when a
   * caller omitted {@code --unit}), or the fixed ad-hoc identity when no run was named.
   */
  private AgentUnit resolveUnit() {
    if (Strings.isBlank(runId)) {
      return AgentUnit.BUILD;
    }
    return Strings.isBlank(unitName)
        ? AgentUnit.forRun(runId)
        : AgentUnit.recorded(runId, unitName);
  }

  private void execute() throws Exception {
    name = CurrentProject.require(name);
    NameValidator.requireValidProjectName(name);
    var shell = new ShellExecutor(dryRun);
    requireRunning(shell);

    var config = loadConfig();
    var configured = config.agent() != null ? config.agent().guardrails() : null;
    var guardrails = configured != null ? configured : Guardrails.defaults();
    var notifier = buildNotifier(config.agent() != null ? config.agent().notifications() : null);

    unit = resolveUnit();
    var agentSession = new AgentSession(shell);
    var sessionInfo = agentSession.queryStatus(name, unit);
    if (sessionInfo == null || !sessionInfo.running()) {
      throw new IllegalStateException(
          "No agent session running. Launch one with: sail agent start "
              + name
              + " --background --task '...'");
    }
    var startedAt = parseStartedAt(sessionInfo.startedAt());
    var deadline = computeDeadline(startedAt, guardrails.maxDuration());
    var checker = new GuardrailChecker(shell);

    announceStart(guardrails, deadline);

    var queue = new LinkedBlockingQueue<Event>();
    var token = ServerConnectionConfig.resolve().token();
    var publisher = resolvePublisher();
    try (var ignored = EventStreamClient.subscribe(apiHost, apiPort, token, name, queue)) {
      runLoop(
          queue,
          deadline,
          agentSession,
          shell,
          checker,
          guardrails,
          notifier,
          config.agent() != null ? config.agent().notifications() : null,
          startedAt,
          publisher);
    }
  }

  /** Sink for the watcher's synthetic stop. A seam so the loop is testable without the network. */
  @FunctionalInterface
  interface StopPublisher {
    void publish(Event event) throws Exception;
  }

  /** What a timeout wake-up (no event in the queue) should do next. */
  enum TimeoutDecision {
    SYNTHESIZE_STOP,
    CHECK_GUARDRAILS,
    KEEP_WAITING
  }

  /**
   * Decides what to do when the loop wakes with no event. Pure so the cadence rules are tested
   * directly: a dead unit is always surfaced; otherwise the (container-touching) guardrail check
   * runs only once a deadline is actually reached — the 15s liveness poll must not turn into a 15s
   * guardrail poll.
   */
  static TimeoutDecision onTimeout(
      boolean unitActive, boolean guardrailFired, boolean deadlineReached) {
    if (!unitActive) {
      return TimeoutDecision.SYNTHESIZE_STOP;
    }
    if (guardrailFired || !deadlineReached) {
      return TimeoutDecision.KEEP_WAITING;
    }
    return TimeoutDecision.CHECK_GUARDRAILS;
  }

  private StopPublisher resolvePublisher() {
    try {
      var publisher = SailEventPublisher.localDefault();
      return publisher::publish;
    } catch (Exception e) {
      return null;
    }
  }

  private void runLoop(
      LinkedBlockingQueue<Event> queue,
      Instant deadline,
      AgentSession agentSession,
      ShellExecutor shell,
      GuardrailChecker checker,
      Guardrails guardrails,
      WebhookNotifier notifier,
      Notifications notifications,
      Instant startedAt,
      StopPublisher publisher)
      throws Exception {
    var guardrailFired = false;
    var maxIdle = Guardrails.parseDuration(guardrails.maxIdle());
    var lastProgressAt = DateTimeUtils.now();
    while (true) {
      var stallDeadline = maxIdle != null ? lastProgressAt.plus(maxIdle) : Instant.MAX;
      var deadlineAt = earlier(deadline, stallDeadline);
      var waitMs = Math.min(LIVENESS_POLL_MS, waitMsUntil(deadlineAt, guardrailFired));
      Event event = waitMs <= 0 ? null : queue.poll(waitMs, TimeUnit.MILLISECONDS);

      if (event != null) {
        if (isProgressEvent(event) && matchesRun(event, runId)) {
          lastProgressAt = DateTimeUtils.now();
        }
        continue;
      }

      var exit = agentSession.queryExitStatus(name, unit);
      var decision =
          onTimeout(exit.active(), guardrailFired, !DateTimeUtils.now().isBefore(deadlineAt));
      if (decision == TimeoutDecision.SYNTHESIZE_STOP) {
        emitSyntheticStop(publisher, name, exit);
        handleAgentExited(notifier, notifications);
        return;
      }
      if (decision == TimeoutDecision.KEEP_WAITING) {
        continue;
      }
      var result = checker.check(guardrails, startedAt);
      if (result instanceof GuardrailChecker.GuardrailResult.Ok) {
        result = GuardrailChecker.checkStall(lastProgressAt, guardrails);
      }
      if (!(result instanceof GuardrailChecker.GuardrailResult.Triggered triggered)) {
        continue;
      }
      var elapsed =
          GuardrailChecker.formatDuration(Duration.between(startedAt, DateTimeUtils.now()));
      var snapshotLabel = applyTriggerAction(triggered, shell, agentSession);
      reportTrigger(triggered, elapsed, snapshotLabel);
      notifyTriggered(notifier, notifications, triggered);
      guardrailFired = true;
      if (!"notify".equals(triggered.action())) {
        notifySessionDone(notifier, notifications);
        return;
      }
    }
  }

  private void requireRunning(ShellExecutor shell) throws Exception {
    var mgr = new ContainerManager(shell);
    var state = mgr.queryState(name);
    ContainerStateGuard.requireRunning(state, name);
  }

  private SailYaml loadConfig() throws Exception {
    var singYamlPath = SailPaths.resolveSailYaml(name, file);
    if (!Files.exists(singYamlPath)) {
      throw new IllegalStateException("No sail.yaml found at " + file);
    }
    return SailYaml.fromMap(YamlUtil.parseFile(singYamlPath));
  }

  private static WebhookNotifier buildNotifier(Notifications notifications) {
    if (notifications == null || notifications.url() == null) {
      return null;
    }
    return new WebhookNotifier(notifications.url());
  }

  static Instant parseStartedAt(String iso) {
    if (Strings.isBlank(iso)) {
      return DateTimeUtils.now();
    }
    try {
      return Instant.parse(iso);
    } catch (DateTimeParseException e) {
      return DateTimeUtils.now();
    }
  }

  static Instant computeDeadline(Instant startedAt, String maxDurationStr) {
    if (Strings.isBlank(maxDurationStr)) {
      return Instant.MAX;
    }
    try {
      var d = Guardrails.parseDuration(maxDurationStr);
      return d == null ? Instant.MAX : startedAt.plus(d);
    } catch (IllegalArgumentException e) {
      return Instant.MAX;
    }
  }

  static long waitMsUntil(Instant deadline, boolean guardrailFired) {
    if (guardrailFired || deadline.equals(Instant.MAX)) {
      return Long.MAX_VALUE;
    }
    var remaining = Duration.between(DateTimeUtils.now(), deadline).toMillis();
    return Math.max(0, remaining);
  }

  /** Whether an event signals the agent is actively working — resets the stall timer. */
  static boolean isProgressEvent(Event event) {
    var type = event.type();
    return Event.WellKnownTypes.AGENT_TOOL_STARTED.equals(type)
        || Event.WellKnownTypes.AGENT_TOOL_FINISHED.equals(type)
        || Event.WellKnownTypes.AGENT_LOG_CHUNK.equals(type);
  }

  /**
   * Whether an event belongs to the watched run. A run-addressed watcher accepts only events
   * stamped with its run id — the in-container hooks send {@code SAIL_RUN_ID} on every heartbeat —
   * so a concurrent run's tool calls never reset this run's stall timer. A project-scoped (ad-hoc)
   * watcher accepts everything, the prior behavior.
   */
  static boolean matchesRun(Event event, String watchedRunId) {
    if (Strings.isBlank(watchedRunId)) {
      return true;
    }
    return watchedRunId.equals(
        Objects.toString(event.data().get(Event.WellKnownData.RUN_ID), null));
  }

  /** The earlier of two instants. */
  static Instant earlier(Instant a, Instant b) {
    return a.isBefore(b) ? a : b;
  }

  private void announceStart(Guardrails guardrails, Instant deadline) {
    if (json) {
      return;
    }
    var max = Objects.requireNonNullElse(guardrails.maxDuration(), "-");
    var deadlineDisplay = deadline.equals(Instant.MAX) ? "n/a" : deadline.toString();
    System.out.println(
        Ansi.AUTO.string(
            "  @|bold Watching|@ "
                + name
                + " @|faint (event-driven; max: "
                + max
                + "; deadline: "
                + deadlineDisplay
                + ")|@"));
  }

  private String applyTriggerAction(
      GuardrailChecker.GuardrailResult.Triggered triggered,
      ShellExecutor shell,
      AgentSession agentSession)
      throws Exception {
    writeTriggerFile(shell, name, triggered);
    return switch (triggered.action()) {
      case "snapshot-and-stop" -> snapshotAndStop(shell, agentSession);
      case "stop" -> {
        if (!dryRun) {
          agentSession.killAgent(name, unit);
        }
        yield "";
      }
      default -> "";
    };
  }

  private String snapshotAndStop(ShellExecutor shell, AgentSession agentSession) throws Exception {
    if (dryRun) {
      return "";
    }
    var snapMgr = new SnapshotManager(shell);
    var label = "guardrail-" + SnapshotManager.defaultLabel().substring(5);
    snapMgr.create(name, label);
    agentSession.killAgent(name, unit);
    return label;
  }

  private void reportTrigger(
      GuardrailChecker.GuardrailResult.Triggered triggered, String elapsed, String snapshotLabel) {
    if (json) {
      var map = new LinkedHashMap<String, Object>();
      map.put("name", name);
      map.put("triggered", true);
      map.put("reason", triggered.reason());
      map.put("detail", triggered.detail());
      map.put("action", triggered.action());
      map.put("elapsed", elapsed);
      if (!snapshotLabel.isEmpty()) {
        map.put("snapshot", snapshotLabel);
      }
      System.out.println(YamlUtil.dumpJson(map));
      return;
    }
    System.out.println(
        Ansi.AUTO.string(
            "  @|bold,red ✗|@ @|bold ["
                + elapsed
                + "] Guardrail triggered:|@ "
                + triggered.reason()));
    System.out.println(Ansi.AUTO.string("    " + triggered.detail()));
    System.out.println(Ansi.AUTO.string("    @|bold Action:|@ " + triggered.action()));
    if (!snapshotLabel.isEmpty()) {
      System.out.println(Ansi.AUTO.string("    @|bold Snapshot:|@ " + snapshotLabel));
    }
  }

  static void emitSyntheticStop(
      StopPublisher publisher, String project, AgentSession.ExitState exit) {
    if (publisher == null) {
      return;
    }
    if (Strings.isBlank(exit.specId())) {
      System.out.println(
          Ansi.AUTO.string(
              "  @|faint [watch] no spec id for "
                  + project
                  + "; ad-hoc session, no stop published|@"));
      return;
    }
    try {
      publisher.publish(syntheticStop(project, exit));
      System.out.println(
          Ansi.AUTO.string(
              "  @|faint [watch] published stop for spec "
                  + exit.specId()
                  + " (exit "
                  + exit.exitCode()
                  + ")|@"));
    } catch (Exception e) {
      System.err.println(
          "  [watch] could not publish synthetic stop for " + project + ": " + e.getMessage());
    }
  }

  /**
   * Builds the {@code agent_session_stopped} the watcher emits when it observes the unit exit
   * without a hook-fired stop reaching the bus. Carries the real exit code so consumers can tell a
   * crash from a clean finish; {@code source=watcher} marks it as watcher-synthesized.
   */
  static Event syntheticStop(String project, AgentSession.ExitState exit) {
    var agent = Strings.isBlank(exit.agentType()) ? Event.SAIL_AGENT : exit.agentType();
    var data = new LinkedHashMap<String, Object>();
    data.put(Event.WellKnownData.EXIT_CODE, exit.exitCode());
    data.put(Event.WellKnownData.SOURCE, Event.WellKnownData.SOURCE_WATCHER);
    if (Strings.isNotBlank(exit.runId())) {
      data.put(Event.WellKnownData.RUN_ID, exit.runId());
    }
    return Event.of(
        project,
        exit.specId(),
        Event.WellKnownTypes.AGENT_SESSION_STOPPED,
        agent,
        HostInfo.hostname(),
        data);
  }

  private void handleAgentExited(WebhookNotifier notifier, Notifications notifications) {
    if (json) {
      var map = new LinkedHashMap<String, Object>();
      map.put("name", name);
      map.put("triggered", false);
      map.put("reason", "agent_session_stopped");
      System.out.println(YamlUtil.dumpJson(map));
    } else {
      System.out.println(Ansi.AUTO.string("  @|faint Agent exited. Watch complete.|@"));
    }
    sendNotification(
        notifier,
        notifications,
        "agent_session_stopped",
        name,
        "Agent exited",
        "Agent process is no longer running. Run: sail agent report " + name);
  }

  private void notifyTriggered(
      WebhookNotifier notifier,
      Notifications notifications,
      GuardrailChecker.GuardrailResult.Triggered triggered) {
    sendNotification(
        notifier,
        notifications,
        "guardrail_triggered",
        name,
        "Guardrail: " + triggered.reason(),
        triggered.detail() + ". Action: " + triggered.action());
  }

  private void notifySessionDone(WebhookNotifier notifier, Notifications notifications) {
    sendNotification(
        notifier,
        notifications,
        "agent_session_completed",
        name,
        "Watch complete",
        "Agent session ended. Run: sail agent report " + name);
  }

  private static void writeTriggerFile(
      ShellExecutor shell,
      String containerName,
      GuardrailChecker.GuardrailResult.Triggered triggered)
      throws Exception {
    var map = new LinkedHashMap<String, Object>();
    map.put("triggered_at", DateTimeUtils.now().toString());
    map.put("reason", triggered.reason());
    map.put("detail", triggered.detail());
    map.put("action", triggered.action());
    var yaml = YamlUtil.dumpToString(map);
    var cmd =
        ContainerExec.asDevUser(
            containerName,
            List.of(
                "bash",
                "-c",
                "printf '%s' \"$1\" > /home/dev/guardrail-triggered.yaml",
                "bash",
                yaml));
    shell.exec(cmd);
  }

  private static void sendNotification(
      WebhookNotifier notifier,
      Notifications notifications,
      String event,
      String project,
      String title,
      String message) {
    if (notifier != null && (notifications == null || notifications.shouldNotify(event))) {
      notifier.notify(event, project, title, message);
    }
  }
}
