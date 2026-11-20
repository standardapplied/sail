/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.commands;

import ai.singlr.sail.api.ApiException;
import ai.singlr.sail.api.HostOperations;
import ai.singlr.sail.api.LiveMembers;
import ai.singlr.sail.api.OperationsFactory;
import ai.singlr.sail.api.RunReservation;
import ai.singlr.sail.api.SessionYield;
import ai.singlr.sail.api.WorkspaceDoors;
import ai.singlr.sail.common.Strings;
import ai.singlr.sail.config.Lane;
import ai.singlr.sail.config.ProjectDefinition;
import ai.singlr.sail.config.YamlUtil;
import ai.singlr.sail.engine.AgentPresence;
import ai.singlr.sail.engine.AgentSession;
import ai.singlr.sail.engine.AgentUnit;
import ai.singlr.sail.engine.ContainerStateGuard;
import ai.singlr.sail.engine.NameValidator;
import ai.singlr.sail.engine.NodeIdentity;
import ai.singlr.sail.engine.OperatorCommand;
import ai.singlr.sail.engine.RunPlace;
import ai.singlr.sail.engine.ShellExec;
import ai.singlr.sail.engine.ShellExecutor;
import ai.singlr.sail.engine.Stty;
import ai.singlr.sail.engine.WorkspaceOwner;
import ai.singlr.sail.engine.Workspaces;
import ai.singlr.sail.harness.Harness;
import ai.singlr.sail.harness.Harnesses;
import ai.singlr.sail.store.DispatchGate;
import ai.singlr.sail.store.RoomStore;
import ai.singlr.sail.store.RunStore;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import picocli.CommandLine.Command;
import picocli.CommandLine.Help.Ansi;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

/**
 * Opens the project's latest agent conversation. {@code --resume} is conversation resurrection, not
 * process attachment: it spawns a fresh agent process loading the saved conversation, which is
 * exactly right for a completed run and exactly wrong for a live one.
 *
 * <ul>
 *   <li><b>Completed run with a recorded session:</b> resumes that session exactly ({@code claude
 *       --resume <id>} / {@code codex resume <id>}) inside a host-owned session named for the run
 *       ({@link SessionYield#resumeSession}), pinned to the run's room, and attaches this terminal
 *       to it. The conversation outlives the terminal: {@code Ctrl-]} detaches, running attach
 *       again joins the live session rather than forking a second agent, and a dispatch that
 *       reserves the run's repos ends it with a reason in the stream.
 *   <li><b>Completed run of a spec with a recorded session:</b> resumes it the same way in the
 *       spec's own workspace, where its run lived and its conversation is, over a raw tty: the
 *       host's sessions open only in the project's container (W10).
 *   <li><b>Completed run without a session:</b> attaches a fresh conversation over a raw tty in the
 *       place the run lived, loudly.
 *   <li><b>Live run:</b> refuses — attaching would fork a second concurrent agent over the same
 *       conversation and worktree. The refusal names the real lanes: reply in the spec room to
 *       steer it mid-run, or stop it first.
 * </ul>
 */
@Command(
    name = "attach",
    description = "Resume the latest run's recorded agent conversation.",
    mixinStandardHelpOptions = true)
public final class AgentAttachCommand implements Runnable {

  private static final Set<String> LIVE_STATUSES = Set.of("running", "stopping");

  static final String MEMBER_DISPLACED = "displaced by a terminal on its conversation";

  @Parameters(
      index = "0",
      arity = "0..1",
      description = "Project name (default: the current project).")
  private String name;

  @Mixin private RunSelector selector = new RunSelector();

  @Option(names = "--dry-run", description = "Print the exact command instead of attaching.")
  private boolean dryRun;

  @Option(names = "--json", description = "Print the resolved attach plan as JSON; do not attach.")
  private boolean json;

  @Option(names = "--socket", hidden = true, description = "Host socket override.")
  private Path socket;

  private final Function<OperatorCommand, HostOperations> operations;

  public AgentAttachCommand() {
    this(operator -> OperationsFactory.open(operator::warn));
  }

  AgentAttachCommand(Function<OperatorCommand, HostOperations> operations) {
    this.operations = operations;
  }

  /** What the resume lane opens: the host session, its child, and the room it is pinned to. */
  record ResumePlan(String session, List<String> command, String project, String room) {

    /** The {@code sail session} verbs this attach is made of — what {@code --dry-run} prints. */
    String asSessionCommands() {
      var open = new ArrayList<>(List.of("sail", "session", "new", session, "--project", project));
      if (!room.isBlank()) {
        open.addAll(List.of("--room", room));
      }
      open.add("--command");
      open.addAll(command);
      return open.stream().map(Strings::shellWord).collect(Collectors.joining(" "))
          + "\nsail session attach "
          + session;
    }
  }

  /**
   * The latest run, its room when that room exists on this box, and every run live on this box in
   * the project — all lanes, since a full room turn holds repos exactly as a build does.
   */
  record Latest(RunStore.RunRow run, String room, List<DispatchGate.RunningRun> running) {
    static final Latest NONE = new Latest(null, "", List.of());
  }

  @Override
  public void run() {
    CliCommand.run(this::execute);
  }

  private void execute() throws Exception {
    var operator = OperatorCommand.here();
    name = CurrentProject.require(name);
    NameValidator.requireValidProjectName(name);

    var latest = latest(operator);
    var run = latest.run();
    var sessionId = validatedSessionId(run, operator);
    var member = takesFromMember(run, sessionId);
    if (run != null && LIVE_STATUSES.contains(run.status()) && !member) {
      throw new IllegalStateException(refusal(run, name, operator));
    }
    var harness = run != null ? Harnesses.of(run.agent()) : resolveHarness(operator);
    var command = buildResumeCommand(harness, sessionId);
    var place = placeOf(run, name);
    if (sessionId != null) {
      requireNoLiveConflict(run, besides(run, latest.running()), place);
    }
    var direct = direct(run, name, sessionId, command);
    if (direct.isPresent()) {
      attachDirect(run, harness, sessionId, place, direct.get(), member, operator);
      return;
    }
    var plan = new ResumePlan(SessionYield.resumeSession(run.id()), command, name, latest.room());
    if (json) {
      System.out.println(YamlUtil.dumpJson(plan(run, harness, sessionId, plan)));
      return;
    }
    if (dryRun) {
      System.out.println(plan.asSessionCommands());
      return;
    }
    if (Stty.saved().isEmpty()) {
      throw new IllegalStateException(
          "Attaching needs an interactive terminal to open session '" + plan.session() + "'.");
    }
    announceResume(run, harness, sessionId, plan);
    requireRunning(place);
    if (member) {
      endMember(run, operator);
    }
    var size = Stty.size(new int[] {24, 80});
    var hostSocket = SessionCommand.socketOrDefault(socket);
    try (var client = SessionClient.connect(hostSocket)) {
      var opened =
          openLocked(
              new PtyHostYield(hostSocket),
              () -> latest(operator),
              run,
              client,
              plan,
              size[1],
              size[0],
              operator);
      System.out.println(
          Ansi.AUTO.string(
              opened
                  ? "  @|faint Opened session " + plan.session() + " (Ctrl-] detaches).|@"
                  : "  @|faint Joined the live session "
                      + plan.session()
                      + " (Ctrl-] detaches).|@"));
      SessionCommand.attachTerminal(client, plan.session(), true, operator);
    }
  }

  /**
   * Whether attaching to {@code run} takes its conversation from the room's live member: the run is
   * a live member's, still up, and has recorded the session a terminal would resume. Such a run is
   * not refused as a live run is; its member is ended first.
   */
  static boolean takesFromMember(RunStore.RunRow run, String sessionId) {
    return run != null && sessionId != null && LiveMembers.liveRun(run);
  }

  /** Every run of {@code running} but {@code run} itself: what else is live beside it. */
  static List<DispatchGate.RunningRun> besides(
      RunStore.RunRow run, List<DispatchGate.RunningRun> running) {
    return running.stream().filter(other -> !other.runId().equals(run.id())).toList();
  }

  /**
   * Ends the room's live member before a terminal takes its conversation: two processes on one
   * conversation would corrupt it, and the member loses nothing — it resumes on the room's next
   * message once the terminal is closed.
   */
  private void endMember(RunStore.RunRow run, OperatorCommand operator) {
    try (var operations = this.operations.apply(operator)) {
      if (!operations.dispatching().endLiveMember(run.id(), MEMBER_DISPLACED)) {
        throw new IllegalStateException(
            "Run "
                + run.id()
                + " is the room's live member and could not be ended, so its conversation is not"
                + " free to resume. Dismiss the member from the room, then attach again.");
      }
    }
    System.out.println(
        Ansi.AUTO.string(
            "  @|faint Ended the room's live member; it resumes on the room's next message once"
                + " this session is closed.|@"));
  }

  /**
   * Opens or joins under the project's claim lock — the lock a dispatch holds from its run-row
   * insert through the yield of displaced sessions — re-reading the run rows first. The plan was
   * built from an unlocked read, so a claim that landed in between shows up here as a live run and
   * refuses: as the project's latest session when it is live, or through the dispatch gate over
   * every live row when an older run still holds repos. A dispatch that reserves after this returns
   * finds the session live and yields it. Either way no resumed agent ever works repos a claim
   * holds. Returns whether this call opened the session.
   */
  static boolean openLocked(
      SessionYield hostYield,
      Supplier<Latest> latest,
      RunStore.RunRow planned,
      SessionClient client,
      ResumePlan plan,
      int cols,
      int rows,
      OperatorCommand operator)
      throws IOException {
    try (var hold = hostYield.lock(plan.project())) {
      requireStillLatestAndIdle(planned, latest.get(), plan.project(), operator);
      return openOrJoin(client, plan, cols, rows);
    }
  }

  static void requireStillLatestAndIdle(
      RunStore.RunRow planned, Latest latest, String project, OperatorCommand operator) {
    var current = latest.run();
    if (current != null && LIVE_STATUSES.contains(current.status())) {
      throw new IllegalStateException(refusal(current, project, operator));
    }
    if (current == null || !current.id().equals(planned.id())) {
      throw new IllegalStateException(
          "The latest run of '"
              + project
              + "' changed while opening the session; run "
              + operator.sail("agent attach")
              + " again.");
    }
    requireNoLiveConflict(planned, latest.running(), placeOf(planned, project));
  }

  /**
   * The dispatch gate read from the conversation's side: resuming {@code run} puts a working agent
   * on its repos, so any live run the gate would refuse a build of that spec over those repos — a
   * build, a full room turn — refuses the resume. This is the exact mirror of the rule a
   * reservation uses to yield a live resume session, so the two sides can never disagree. A resumed
   * conversation runs in {@code place}, so only the runs that live there are in its way.
   */
  static void requireNoLiveConflict(
      RunStore.RunRow run, List<DispatchGate.RunningRun> running, RunPlace place) {
    DispatchGate.decide(place, run.conversationId(), Lane.BUILD.wire(), run.repos(), running)
        .ifPresent(
            conflict -> {
              throw new IllegalStateException(conflictRefusal(run, conflict, place));
            });
  }

  private static String conflictRefusal(
      RunStore.RunRow run, DispatchGate.Conflict conflict, RunPlace place) {
    var live = conflict.run();
    return "Run "
        + live.runId()
        + " ("
        + live.role()
        + ") is live in "
        + (conflict.overlap().isEmpty()
            ? "container '" + place.instance() + "'"
            : "repo(s) " + conflict.overlap())
        + " — resuming run "
        + run.id()
        + " would put a second agent on what it holds. Wait for it to finish, or stop it first.";
  }

  /**
   * Opens the resume session, or joins it when it is already live: a create the host refuses
   * because the session runs — a reattach after detach, or the losing side of two simultaneous
   * attaches — is the join case, never a second agent. Any other refusal is the error it was.
   * Returns whether this call opened the session.
   */
  static boolean openOrJoin(SessionClient client, ResumePlan plan, int cols, int rows)
      throws IOException {
    try {
      client.create(
          plan.session(),
          plan.command(),
          System.getProperty("user.home", "/home/dev"),
          plan.project(),
          plan.room(),
          cols,
          rows);
      return true;
    } catch (IOException refused) {
      if (client.list().stream()
          .anyMatch(info -> info.live() && info.name().equals(plan.session()))) {
        return false;
      }
      throw refused;
    }
  }

  /**
   * Where {@code run} of {@code project} lived, and so where its conversation is: none, the
   * project's container.
   */
  static RunPlace placeOf(RunStore.RunRow run, String project) {
    return run == null ? RunPlace.of(project, null) : run.place();
  }

  /**
   * The raw-tty command that attaches to {@code run}'s conversation, {@code command}, in the place
   * it lived, when the attach does not go through a host session: a fresh conversation (no {@code
   * sessionId}), or a spec's run resumed in its workspace, where no host session opens (W10), its
   * process named in the workspace's terminal pid file ({@link #inTheWorkspace}). Empty for a
   * recorded conversation of the project's container, which a host session resumes. What a dry run
   * prints is this command, as a real attach runs it.
   */
  static Optional<List<String>> direct(
      RunStore.RunRow run, String project, String sessionId, List<String> command) {
    var place = placeOf(run, project);
    if (place instanceof RunPlace.SpecWorkspace) {
      return Optional.of(buildIncusExecWithTty(place.instance(), inTheWorkspace(command)));
    }
    return sessionId == null
        ? Optional.of(buildIncusExecWithTty(place.instance(), command))
        : Optional.empty();
  }

  /**
   * {@code command}, a {@code bash -lc} line, run as a spec's workspace's terminal: it writes its
   * process into {@link AgentUnit#TERMINAL}'s pid file first, so that a claim of the workspace ends
   * it ({@link ai.singlr.sail.api.RunReservation}), as a claim of the project's container ends a
   * resumed host session, and a live member never starts beside it.
   */
  static List<String> inTheWorkspace(List<String> command) {
    return List.of(
        command.get(0),
        command.get(1),
        "echo $$ > " + AgentUnit.TERMINAL.pidPath() + " && " + command.get(2));
  }

  /**
   * Attaches this terminal to {@code exec}, a raw tty in {@code place}'s instance: a fresh
   * conversation, or a spec run's recorded one resumed in its workspace, its room's live member
   * ended first.
   */
  private void attachDirect(
      RunStore.RunRow run,
      Harness harness,
      String sessionId,
      RunPlace place,
      List<String> exec,
      boolean member,
      OperatorCommand operator)
      throws Exception {
    if (json) {
      var map = plan(run, harness, sessionId, exec);
      map.put("instance", place.instance());
      System.out.println(YamlUtil.dumpJson(map));
      return;
    }
    if (dryRun) {
      System.out.println(printed(exec));
      return;
    }
    try (var operations = this.operations.apply(operator)) {
      reach(
          place,
          Workspaces.onThisBox(new ShellExecutor(false)),
          operations.catalog().definitions()::require,
          operations.identity()::workspaceOwner,
          WorkspaceVerbs.keeper(),
          () -> {
            if (sessionId == null) {
              announceFresh(run);
            } else {
              announceResume(run, harness, sessionId, place.instance());
            }
          });
    }

    if (member) {
      endMember(run, operator);
    }
    if (!(place instanceof RunPlace.SpecWorkspace) || run == null) {
      exitedWith(new ProcessBuilder(exec).inheritIO().start().waitFor());
      return;
    }
    var workspace = (RunPlace.SpecWorkspace) place;
    var shell = new ShellExecutor(false);
    var before = terminalPid(shell, workspace);
    Process terminal;
    try (var hold = new PtyHostYield(SessionCommand.socketOrDefault(socket)).lock(name)) {
      requireStillLatestAndIdle(run, latest(operator), name, operator);
      terminal = new ProcessBuilder(exec).inheritIO().start();
    }
    var started = terminal;
    var mine = new AtomicReference<Integer>();
    if (!awaitNamed(
        () -> named(shell, workspace, before, mine),
        started::isAlive,
        System::nanoTime,
        TERMINAL_NAMED_WAIT,
        () -> Thread.sleep(POLL.toMillis()))) {
      started.destroyForcibly();
      throw new IllegalStateException(
          "The terminal in "
              + workspace.instance()
              + " did not name its process within "
              + TERMINAL_NAMED_WAIT.toSeconds()
              + " s, so it is ended rather than left where a claim of the workspace cannot end it."
              + " Attach again.");
    }
    var claims = (Supplier<List<String>>) () -> claimsOf(run, workspace, running(operator));
    var yielded = RunReservation.yieldToAClaim(shell, workspace, claims);
    var code = terminal.waitFor();
    if (mine.get() != null) {
      new AgentSession(shell).forgetPid(workspace.instance(), AgentUnit.TERMINAL, mine.get());
    }
    yielded
        .or(() -> claims.get().stream().findFirst())
        .ifPresentOrElse(
            claim -> System.err.println(yielded(run, claim, name, operator)),
            () -> exitedWith(code));
  }

  /**
   * The command a terminal runs, as a shell reads it back: each word quoted where the shell would
   * otherwise read it, so the line pasted into a shell runs what the attach runs.
   */
  static String printed(List<String> exec) {
    return exec.stream().map(Strings::shellWord).collect(Collectors.joining(" "));
  }

  /** The runs live on this box of the project the attach is of, read as this box's operator. */
  private List<DispatchGate.RunningRun> running(OperatorCommand operator) {
    return orRefuse(
        name,
        () -> {
          try (var operations = this.operations.apply(operator)) {
            return operations.dispatching().runningRuns(name, NodeIdentity.handle());
          }
        });
  }

  /** How long a terminal in a spec's workspace has to name its process before it is ended. */
  static final Duration TERMINAL_NAMED_WAIT = Duration.ofSeconds(10);

  private static final Duration POLL = Duration.ofMillis(100);

  /** A wait between two asks. */
  @FunctionalInterface
  interface Pause {
    void pause() throws InterruptedException;
  }

  /**
   * Waits until {@code named} says the terminal's process is named in its pid file, or the terminal
   * has ended ({@code alive} false), asking again after each {@code pause} until {@code wait} has
   * passed on {@code nanos}: false then, a terminal a claim could not end.
   */
  static boolean awaitNamed(
      BooleanSupplier named, BooleanSupplier alive, LongSupplier nanos, Duration wait, Pause pause)
      throws InterruptedException {
    var deadline = nanos.getAsLong() + wait.toNanos();
    while (alive.getAsBoolean() && !named.getAsBoolean()) {
      if (nanos.getAsLong() - deadline >= 0) {
        return false;
      }
      pause.pause();
    }
    return true;
  }

  /**
   * The runs other than {@code run} that claim {@code workspace} in a lane that ends its terminal,
   * of the box's live runs, {@code running}: read as they are, never through a selector, so other
   * runs live beside them never stand in the way of saying which.
   */
  static List<String> claimsOf(
      RunStore.RunRow run,
      RunPlace.SpecWorkspace workspace,
      List<DispatchGate.RunningRun> running) {
    return running.stream()
        .filter(other -> !other.runId().equals(run.id()))
        .filter(other -> workspace.equals(other.place()))
        .filter(other -> RunReservation.endsTheTerminal(other.role()))
        .map(DispatchGate.RunningRun::runId)
        .toList();
  }

  /** What a terminal that a claim ended says, in the words a claim gives every terminal it ends. */
  static String yielded(
      RunStore.RunRow run, String claim, String project, OperatorCommand operator) {
    return Ansi.AUTO.string(
        "  @|yellow ⚠|@ This terminal ended: "
            + RunReservation.yieldReason(claim, run.specId())
            + ". Its conversation is resumable once that run ends: "
            + operator.sail("agent attach " + project + RunSelector.of(run)));
  }

  /** The pid {@code workspace}'s terminal pid file names, alive or not; none when it names none. */
  private static Integer terminalPid(ShellExec shell, RunPlace.SpecWorkspace workspace)
      throws Exception {
    var info = new AgentSession(shell).queryStatus(workspace.instance(), AgentUnit.TERMINAL);
    return info == null ? null : info.pid();
  }

  /**
   * Whether {@code workspace}'s terminal pid file names a live process other than {@code before},
   * kept in {@code mine} once it does.
   */
  private static boolean named(
      ShellExec shell,
      RunPlace.SpecWorkspace workspace,
      Integer before,
      AtomicReference<Integer> mine) {
    try {
      var info = new AgentSession(shell).queryStatus(workspace.instance(), AgentUnit.TERMINAL);
      var named = info != null && info.running() && !Objects.equals(info.pid(), before);
      if (named) {
        mine.set(info.pid());
      }
      return named;
    } catch (Exception unanswered) {
      if (unanswered instanceof InterruptedException) {
        Thread.currentThread().interrupt();
      }
      return false;
    }
  }

  private static void exitedWith(int exitCode) {
    if (exitCode != 0) {
      System.err.println(
          Ansi.AUTO.string("  @|yellow ⚠|@ Agent session exited with code " + exitCode));
    }
  }

  private static void requireRunning(RunPlace place) throws Exception {
    var state = new AgentPresence(new ShellExecutor(false)).state(place);
    ContainerStateGuard.requireRunning(state, place);
  }

  /**
   * Puts {@code place} within an attach's reach: a spec's workspace through its own door ({@link
   * Workspaces#startExisting}), started when stopped and given its owner's credentials; any other
   * place must be running. On a node ({@code keeper}, its person; null on main), a spec's workspace
   * labelled for a person the node does not hold by id is refused first, running or stopped (N1):
   * no terminal opens on that person's credentials, and only the box's pass strips it. {@code
   * reached} runs once the place is within reach, so what an attach says it attaches is never said
   * of one it refuses.
   */
  static void reach(
      RunPlace place,
      Workspaces workspaces,
      Function<String, ProjectDefinition> definitions,
      Function<String, WorkspaceOwner> owners,
      String keeper,
      Runnable reached)
      throws Exception {
    if (place instanceof RunPlace.SpecWorkspace workspace) {
      if (keeper != null) {
        var found = workspaces.find(workspace.project(), workspace.workspace());
        if (found.isPresent()) {
          var refused =
              WorkspaceDoors.labelledForAnother(
                  workspace.project(),
                  workspace.workspace(),
                  found.get(),
                  owners,
                  WorkspaceDoors.TERMINAL,
                  true);
          if (refused.isPresent()) {
            throw new IllegalStateException(refused.get().getMessage());
          }
        }
      }
      workspaces.startExisting(workspace, definitions.apply(workspace.project()), owners);
      reached.run();
      return;
    }
    requireRunning(place);
    reached.run();
  }

  /**
   * The latest local agent session (build or ad-hoc) of the project with its room, read directly
   * from the control-plane database like the status and log lanes, as this box's operator, who must
   * read the project and may operate the run, decided in stop's order. A run whose room is not on
   * this box resolves to no room — the session then opens unbound, announced — so a legacy
   * conversation is never unreachable for want of a room row. An unreadable database fails closed
   * through {@link #orRefuse}.
   */
  private Latest latest(OperatorCommand operator) {
    return orRefuse(
        name,
        () -> {
          try (var operations = this.operations.apply(operator)) {
            var node = NodeIdentity.handle();
            return selector
                .resolve(operations.dispatching(), "agent attach", name, node, run -> true)
                .map(
                    run ->
                        new Latest(
                            run,
                            operations.catalog().roomKnown(run.conversationId())
                                ? run.conversationId()
                                : "",
                            operations.dispatching().runningRuns(name, node)))
                .orElse(Latest.NONE);
          }
        });
  }

  static String knownRoom(RoomStore rooms, RunStore.RunRow run) {
    var conversation = run.conversationId();
    return conversation != null && rooms.findById(conversation).isPresent() ? conversation : "";
  }

  /**
   * The fail-closed policy, pure: unknown is never absent. A successful query may find no run —
   * only then may attach fall back to a fresh conversation. A query failure of any kind refuses the
   * attach, because treating an unreadable database as "no run" would bypass the live-run refusal
   * and fork a second agent over the same worktree. A refusal the query decided is said as it is.
   */
  static <T> T orRefuse(String project, Supplier<T> query) {
    try {
      return query.get();
    } catch (ApiException decided) {
      throw decided;
    } catch (RuntimeException e) {
      throw new IllegalStateException(
          "Could not read run state for project '" + project + "'; refusing to attach.", e);
    }
  }

  /**
   * The recorded session id, validated before it is spliced into a shell command: the value arrives
   * from an agent-reported hook payload and replicates between boxes, so only a plain token shape
   * ever reaches the argv. The first character must be alphanumeric — an id starting with {@code -}
   * would be parsed by the agent CLI as an option, not a session id. A malformed id fails loud
   * rather than resuming something else.
   */
  private static String validatedSessionId(RunStore.RunRow run, OperatorCommand operator) {
    if (run == null || run.sessionId() == null) {
      return null;
    }
    if (!Harness.isSafeSessionId(run.sessionId())) {
      throw new IllegalStateException(
          "Run "
              + run.id()
              + " recorded a malformed session id; refusing to build a resume command from it."
              + " Attach fresh with: "
              + operator.sail("agent attach --json")
              + " to inspect, or start a new session inside the container.");
    }
    return run.sessionId();
  }

  private static String refusal(RunStore.RunRow run, String project, OperatorCommand operator) {
    var room =
        run.specId() == null
            ? "its spec room"
            : "its spec room (spec comment " + run.specId() + ")";
    return "Run "
        + run.id()
        + " is live — attaching would fork a second concurrent agent over the same conversation"
        + " and worktree. Reply in "
        + room
        + " to steer it (delivered mid-run), or stop it first with: "
        + operator.sail("agent stop " + project + RunSelector.of(run))
        + ".";
  }

  private void announceFresh(RunStore.RunRow run) {
    if (run == null) {
      System.out.println(
          Ansi.AUTO.string(
              "  @|yellow ⚠|@ No recorded run for '" + name + "'; attaching a fresh session."));
      return;
    }
    System.out.println(
        Ansi.AUTO.string(
            "  @|yellow ⚠|@ Run " + run.id() + " recorded no session; attaching fresh."));
  }

  private void announceResume(
      RunStore.RunRow run, Harness harness, String sessionId, ResumePlan plan) {
    if (run.conversationId() != null && plan.room().isBlank()) {
      System.out.println(
          Ansi.AUTO.string(
              "  @|yellow ⚠|@ Room "
                  + run.conversationId()
                  + " is not on this box; the session opens without a room."));
    }
    announceResume(run, harness, sessionId, name);
  }

  private static void announceResume(
      RunStore.RunRow run, Harness harness, String sessionId, String instance) {
    System.out.println(
        Ansi.AUTO.string(
            "  @|faint Resuming "
                + harness.yamlName()
                + " session "
                + sessionId
                + " of run "
                + run.id()
                + " in "
                + instance
                + "...|@"));
  }

  private LinkedHashMap<String, Object> plan(
      RunStore.RunRow run, Harness harness, String sessionId, ResumePlan resume) {
    var map = plan(run, harness, sessionId, resume.command());
    map.put("session", resume.session());
    if (!resume.room().isBlank()) {
      map.put("room", resume.room());
    }
    return map;
  }

  private LinkedHashMap<String, Object> plan(
      RunStore.RunRow run, Harness harness, String sessionId, List<String> command) {
    var map = new LinkedHashMap<String, Object>();
    map.put("project", name);
    map.put("agent", harness.yamlName());
    map.put("mode", sessionId != null ? "resume" : "fresh");
    if (run != null) {
      map.put("run_id", run.id());
      map.put("run_status", run.status());
      if (sessionId != null) {
        map.put("session_id", sessionId);
      }
      if (run.sessionSource() != null) {
        map.put("session_source", run.sessionSource());
      }
    }
    map.put("command", command);
    return map;
  }

  Harness resolveHarness(OperatorCommand operator) {
    try (var operations = this.operations.apply(operator)) {
      return operations
          .catalog()
          .definitions()
          .read(name)
          .map(ProjectDefinition::agent)
          .map(ProjectDefinition.Agent::type)
          .map(Harnesses::of)
          .orElse(Harnesses.DEFAULT);
    }
  }

  /**
   * The in-container command: the recorded session resumed exactly by id, or a fresh conversation
   * when there is nothing to resume — never an interactive picker. Runs from the workspace root,
   * where sail-launched sessions run, so the CLI's per-directory session lookup finds the recorded
   * conversation.
   */
  public static List<String> buildResumeCommand(Harness harness, String sessionId) {
    return List.of("bash", "-lc", "cd ~/workspace && exec " + harness.attach(sessionId));
  }

  static List<String> buildIncusExecWithTty(String container, List<String> args) {
    var cmd = new ArrayList<String>();
    cmd.add("incus");
    cmd.add("exec");
    cmd.add(container);
    cmd.add("--user");
    cmd.add("1000");
    cmd.add("--group");
    cmd.add("1000");
    cmd.add("--env");
    cmd.add("HOME=/home/dev");
    cmd.add("-t");
    cmd.add("--");
    cmd.addAll(args);
    return List.copyOf(cmd);
  }
}
