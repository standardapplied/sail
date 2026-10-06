/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.commands;

import ai.singlr.sail.api.Event;
import ai.singlr.sail.api.EventStreamClient;
import ai.singlr.sail.api.HostOperations;
import ai.singlr.sail.api.OperationsFactory;
import ai.singlr.sail.api.RunWatch;
import ai.singlr.sail.api.SailEventPublisher;
import ai.singlr.sail.api.ServerConnectionConfig;
import ai.singlr.sail.common.DateTimeUtils;
import ai.singlr.sail.common.Strings;
import ai.singlr.sail.config.Guardrails;
import ai.singlr.sail.config.Notifications;
import ai.singlr.sail.config.YamlUtil;
import ai.singlr.sail.engine.AgentPresence;
import ai.singlr.sail.engine.AgentSession;
import ai.singlr.sail.engine.AgentUnit;
import ai.singlr.sail.engine.ContainerManager;
import ai.singlr.sail.engine.ContainerStateGuard;
import ai.singlr.sail.engine.GuardrailChecker;
import ai.singlr.sail.engine.NameValidator;
import ai.singlr.sail.engine.ShellExec;
import ai.singlr.sail.engine.ShellExecutor;
import ai.singlr.sail.engine.WebhookNotifier;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.Objects;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import picocli.CommandLine.Command;
import picocli.CommandLine.Help.Ansi;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Spec;

/**
 * {@code sail agent watch}: the long-running watcher of one agent run, whichever lane launched it —
 * a build, an ad-hoc run, a chat turn, a reviewer, a fix agent. The watch itself is {@link
 * RunWatch}; this command resolves what it watches, opens the event feed it waits on, and says what
 * happens on the terminal and through the project's notifications.
 *
 * <p>The limits are the run's lane's, handed over on the command line by whoever spawned the
 * watcher ({@code --max-duration}, {@code --max-idle}, {@code --action}); the watcher never reads a
 * project's guardrails itself. A limit left off is not enforced.
 *
 * <p>Every watcher is run-addressed: {@code --run} names the run whose agent it supervises, {@code
 * --unit} the systemd unit that run was launched as (recorded on the run, never re-derived here),
 * and {@code --started-at} when the run's row says it started — the anchor of its wall clock,
 * handed over by whoever spawned the watcher and never read from the run's session file, which the
 * agent can write.
 */
@Command(
    name = "watch",
    description = "Monitor a running agent and enforce guardrails.",
    mixinStandardHelpOptions = true)
public final class AgentWatchCommand implements Runnable {

  @Parameters(
      index = "0",
      arity = "0..1",
      description = "Project name (default: the current project).")
  private String name;

  @Option(
      names = "--run",
      required = true,
      description = "Run id of the agent session to supervise.")
  private String runId;

  @Option(
      names = "--unit",
      description =
          "Systemd unit the run was launched as, as recorded on the run (default: derived from"
              + " --run).")
  private String unitName;

  @Option(
      names = "--started-at",
      required = true,
      description =
          "When the run started, as recorded on its run row (ISO-8601 instant); anchors"
              + " --max-duration.")
  private String startedAt;

  @Option(
      names = "--max-duration",
      description = "Wall-clock limit of the run (e.g. 4h, 45m); none when omitted.")
  private String maxDuration;

  @Option(
      names = "--max-idle",
      description = "Stall limit: time without a progress event (e.g. 20m); none when omitted.")
  private String maxIdle;

  @Option(
      names = "--action",
      description = "What a crossed limit does: stop, snapshot-and-stop, notify.",
      defaultValue = "stop")
  private String action;

  @Option(names = "--dry-run", description = "Print actions instead of executing them.")
  private boolean dryRun;

  @Option(names = "--json", description = "Output in JSON format.")
  private boolean json;

  @Mixin private IgnoredFileOption file;

  @Option(names = "--host", description = "sail-api host.", defaultValue = "127.0.0.1")
  private String apiHost;

  @Option(names = "--port", description = "sail-api port.", defaultValue = "7070")
  private int apiPort;

  @Spec private CommandSpec commandSpec;

  private final Supplier<HostOperations> operations;

  public AgentWatchCommand() {
    this(OperationsFactory::open);
  }

  AgentWatchCommand(Supplier<HostOperations> operations) {
    this.operations = operations;
  }

  @Override
  public void run() {
    CliCommand.run(commandSpec, this::execute);
  }

  /**
   * The identity this watcher supervises: the recorded unit of {@code --run}, derived only when a
   * caller omitted {@code --unit}.
   */
  private AgentUnit resolveUnit() {
    return Strings.isBlank(unitName)
        ? AgentUnit.forRun(runId)
        : AgentUnit.recorded(runId, unitName);
  }

  private Guardrails guardrails() {
    return Guardrails.of(maxDuration, maxIdle, action);
  }

  /**
   * Whether there is a run to watch: there is unless its agent is known gone, since a container
   * that gave no answer about it may hold an agent at work, and the watch asks again at every poll.
   * A run whose agent is gone has its stop published when its unit still says how it ended, and
   * then nothing is left to watch; one whose unit says nothing of it never ran.
   */
  boolean watchable(ShellExec shell, RunWatch.StopPublisher publisher) throws Exception {
    if (!new AgentPresence(shell).gone(name, resolveUnit())) {
      return true;
    }
    if (RunWatch.stopIfAlreadyEnded(
        name, runId, resolveUnit(), new AgentSession(shell), publisher)) {
      return false;
    }
    throw new IllegalStateException(
        "No agent session running. Launch one with: sail agent start "
            + name
            + " --background --task '...'");
  }

  private void execute() throws Exception {
    name = CurrentProject.require(name);
    NameValidator.requireValidProjectName(name);
    var guardrails = guardrails();
    var started = parseStartedAt(startedAt);
    var shell = new ShellExecutor(dryRun);
    requireRunning(shell);

    var notifications = notifications();
    var notifier = buildNotifier(notifications);

    var publisher = resolvePublisher();
    if (!watchable(shell, publisher)) {
      return;
    }
    announceStart(guardrails, RunWatch.deadline(started, guardrails.maxDuration()));

    try (var feed = new StreamFeed(ServerConnectionConfig.resolve().token())) {
      watch(shell, feed, publisher, narrator(notifier, notifications), DateTimeUtils::now).run();
    }
  }

  /**
   * The watch this command runs: of {@code --run}, as its recorded unit, under the limits it was
   * given, its wall clock anchored to {@code --started-at} — the run row's start, which the spawner
   * passes — and to nothing the container holds, since the agent can write there.
   */
  RunWatch watch(
      ShellExec shell,
      RunWatch.Feed feed,
      RunWatch.StopPublisher publisher,
      RunWatch.Narrator narrator,
      Supplier<Instant> clock) {
    return new RunWatch(
        name,
        runId,
        resolveUnit(),
        guardrails(),
        parseStartedAt(startedAt),
        dryRun,
        shell,
        feed,
        publisher,
        narrator,
        clock);
  }

  /**
   * The project's event stream as the watch waits on it: one subscription, opened again whenever
   * the one before it ended — the daemon it listened to restarted.
   */
  private final class StreamFeed implements RunWatch.Feed, AutoCloseable {

    private final LinkedBlockingQueue<Event> queue = new LinkedBlockingQueue<>();
    private final String token;
    private EventStreamClient stream;

    private StreamFeed(String token) throws Exception {
      this.token = token;
      this.stream = EventStreamClient.subscribe(apiHost, apiPort, token, name, queue);
    }

    @Override
    public Event poll(Duration wait) throws InterruptedException {
      return queue.poll(wait.toMillis(), TimeUnit.MILLISECONDS);
    }

    @Override
    public boolean live() {
      return !stream.ended();
    }

    @Override
    public boolean reopen() {
      stream.close();
      try {
        stream = EventStreamClient.subscribe(apiHost, apiPort, token, name, queue);
        return true;
      } catch (InterruptedException interrupted) {
        Thread.currentThread().interrupt();
        return false;
      } catch (Exception unreachable) {
        return false;
      }
    }

    @Override
    public void close() {
      stream.close();
    }
  }

  private RunWatch.StopPublisher resolvePublisher() {
    try {
      var publisher = SailEventPublisher.localDefault();
      return publisher::publish;
    } catch (Exception e) {
      return null;
    }
  }

  private RunWatch.Narrator narrator(WebhookNotifier notifier, Notifications notifications) {
    return new RunWatch.Narrator() {
      @Override
      public void tripped(
          GuardrailChecker.GuardrailResult.Triggered limit, Duration elapsed, String snapshot) {
        reportTrigger(limit, GuardrailChecker.formatDuration(elapsed), snapshot);
        notifyTriggered(notifier, notifications, limit);
      }

      @Override
      public void exited() {
        handleAgentExited(notifier, notifications);
      }

      @Override
      public void ended() {
        notifySessionDone(notifier, notifications);
      }

      @Override
      public void endedBy(String type) {
        System.err.println(
            "  [watch] run "
                + runId
                + " was ended by its "
                + type
                + "; the watch ends with it, publishing nothing");
      }

      @Override
      public void leftToTheReconciler() {
        System.err.println(
            "  [watch] nothing in "
                + name
                + " says how run "
                + runId
                + " ended: its container is stopped, or its unit's manager is gone and its"
                + " process with it; the run is left to the missed-stop reconciler");
      }

      @Override
      public void stillRunning(GuardrailChecker.GuardrailResult.Triggered limit, boolean survived) {
        System.err.println(
            "  [watch] "
                + resolveUnit().unitName()
                + (survived ? " survived the kill for " : " gave no answer to the kill for ")
                + limit.cause()
                + "; no stop published, trying again at the next poll");
      }
    };
  }

  private void requireRunning(ShellExecutor shell) throws Exception {
    var mgr = new ContainerManager(shell);
    var state = mgr.queryState(name);
    ContainerStateGuard.requireRunning(state, name);
  }

  /** The project's notifications as the catalog holds them when the watch starts. */
  Notifications notifications() {
    try (var operations = this.operations.get()) {
      var config = operations.catalog().definitions().require(name);
      return config.agent() != null ? config.agent().notifications() : null;
    }
  }

  private static WebhookNotifier buildNotifier(Notifications notifications) {
    if (notifications == null || notifications.url() == null) {
      return null;
    }
    return new WebhookNotifier(notifications.url());
  }

  static Instant parseStartedAt(String iso) {
    try {
      return Instant.parse(Objects.toString(iso, ""));
    } catch (DateTimeParseException e) {
      throw new IllegalArgumentException(
          "--started-at '"
              + iso
              + "' is not an ISO-8601 instant; pass the run row's started_at, e.g."
              + " 2026-10-04T12:00:00Z.");
    }
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

  private void handleAgentExited(WebhookNotifier notifier, Notifications notifications) {
    if (json) {
      var map = new LinkedHashMap<String, Object>();
      map.put("name", name);
      map.put("triggered", false);
      map.put("reason", Event.WellKnownTypes.AGENT_SESSION_STOPPED);
      System.out.println(YamlUtil.dumpJson(map));
    } else {
      System.out.println(Ansi.AUTO.string("  @|faint Agent exited. Watch complete.|@"));
    }
    sendNotification(
        notifier,
        notifications,
        Event.WellKnownTypes.AGENT_SESSION_STOPPED,
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
        Event.WellKnownTypes.GUARDRAIL_TRIGGERED,
        name,
        "Guardrail: " + triggered.reason(),
        triggered.detail() + ". Action: " + triggered.action());
  }

  private void notifySessionDone(WebhookNotifier notifier, Notifications notifications) {
    sendNotification(
        notifier,
        notifications,
        Event.WellKnownTypes.AGENT_SESSION_COMPLETED,
        name,
        "Watch complete",
        "Agent session ended. Run: sail agent report " + name);
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
