/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.api;

import ai.singlr.sail.common.DateTimeUtils;
import ai.singlr.sail.common.Strings;
import ai.singlr.sail.config.Engagement;
import ai.singlr.sail.config.Guardrails;
import ai.singlr.sail.config.Roster;
import ai.singlr.sail.config.SailYaml;
import ai.singlr.sail.engine.AgentPresence;
import ai.singlr.sail.engine.AgentProcess;
import ai.singlr.sail.engine.AgentSession;
import ai.singlr.sail.engine.AgentUnit;
import ai.singlr.sail.engine.ContainerAgentProcess;
import ai.singlr.sail.engine.ContainerExec;
import ai.singlr.sail.engine.DispatchRepos;
import ai.singlr.sail.engine.HostInfo;
import ai.singlr.sail.engine.LiveMemberPrompt;
import ai.singlr.sail.engine.LiveStream;
import ai.singlr.sail.engine.PromptConversation;
import ai.singlr.sail.engine.ShellExec;
import ai.singlr.sail.harness.Harness;
import ai.singlr.sail.harness.Harnesses;
import ai.singlr.sail.identity.Actor;
import ai.singlr.sail.identity.Ownership;
import ai.singlr.sail.store.DispatchGate;
import ai.singlr.sail.store.MessageStore;
import ai.singlr.sail.store.RoomStore;
import ai.singlr.sail.store.RunStore;
import ai.singlr.sail.store.SpecStore;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Supplier;

/**
 * The live members of this box's rooms: for each room with a member whose harness has a live mode,
 * at most one process that stays up, is prompted the moment a human message lands, and is read by
 * sail as it works. Sail is the only thing that speaks for it: every line the process writes
 * becomes an event, and the text of each turn becomes the member's message in the room, posted as
 * the run's principal through the door the {@code spec} CLI uses.
 *
 * <p>A live member is one run for the life of its process: one row, one credential, one
 * reservation, one pid file, under a unit name systemd never knows ({@link AgentUnit#live}). It
 * ends when it has been idle for its lane's {@code max_idle}, when it reaches its lane's {@code
 * max_duration}, when a claim needs its repos or a terminal its conversation, when its membership
 * changes, or when sail stops — gracefully where it can be, by closing its stdin so it finishes its
 * turn and exits, and by signal where it cannot. Ending loses nothing: the next human message
 * starts a process that resumes the recorded conversation.
 *
 * <p>The process is owned by whichever sail process started it. Another process on the box — a CLI
 * dispatch, {@code sail agent attach} — cannot close its stdin, so from there a member is ended by
 * signal through the container, and whoever sees it gone first finishes its run.
 *
 * <p>What a run reserved is freed only on a fact. A member is prompted only once the pid its
 * wrapper wrote is recorded on its run, so a run that records none was never given work, and is
 * ended by a compare that recording loses to ({@link RunStore#transitionUnstarted}); a run that
 * records one has a pid file to be asked through, and is ended by anyone but its owner only when
 * the container answers that the process is gone.
 */
public final class LiveMembers implements RoomWakeReactor.Live, AutoCloseable {

  /** The {@code session_source} of a session a live member recorded off its own stream. */
  public static final String SESSION_SOURCE = "stream-json";

  /** Text fragments written within this window of one another are told as one event. */
  public static final Duration DELTA_WINDOW = Duration.ofMillis(50);

  /** How often a live member's activity is stamped on its run, at most. */
  static final Duration ACTIVITY_FLOOR = Duration.ofSeconds(30);

  /** How long a process whose output ended is given to report its exit code. */
  private static final Duration EXIT_GRACE = Duration.ofSeconds(5);

  /** How long a started process is given to write its pid. */
  static final Duration PID_PATIENCE = Duration.ofSeconds(30);

  /** How long sail waits between two asks for that pid. */
  private static final Duration PID_ASK_GAP = Duration.ofMillis(100);

  private static final String MEMBERSHIP_CHANGED = "membership changed";
  private static final int ROOM_TAIL = 20;
  private static final int DELTA_CODE_POINTS = 8_000;
  private static final int REASON_CODE_POINTS = 500;
  private static final String LOG_TAIL_BYTES = "2000";

  /** The door a member's reply is posted through: returns the id of the message it became. */
  @FunctionalInterface
  public interface Room {
    String post(RunStore.RunRow run, String roomId, String text);
  }

  /**
   * How long each step of ending a member waits: for a process that was only idle to exit once its
   * stdin closes, for an open turn's result, and after each signal.
   */
  record Grace(Duration idleExit, Duration result, Duration afterInt, Duration afterTerm) {

    static final Grace DEFAULT =
        new Grace(
            Duration.ofSeconds(60),
            Duration.ofSeconds(30),
            Duration.ofSeconds(10),
            Duration.ofSeconds(10));

    /** The longest an end can take, start to finish. */
    Duration whole() {
      return idleExit.plus(afterInt).plus(afterTerm);
    }
  }

  /** Time as a member reads it, injectable so tests never sleep. */
  record Timing(Supplier<Instant> clock, RoomWakeReactor.Delay delay, Grace grace) {

    static Timing real() {
      return new Timing(DateTimeUtils::now, Thread::sleep, Grace.DEFAULT);
    }
  }

  /**
   * What a restart made of the live members left from before it: how many runs it finished, and the
   * runs it could not, whose processes nothing proved gone.
   */
  public record Reclaimed(int ended, List<String> held) {}

  private final ProjectLoader projects;
  private final SpecStore specStore;
  private final Supplier<RoomStore> rooms;
  private final Supplier<MessageStore> messages;
  private final RunStore runStore;
  private final RunReservation reservation;
  private final RunLauncher launcher;
  private final RoomCommitGuard guard;
  private final ShellExec shell;
  private final DispatchOperations.EventSink events;
  private final SessionYield terminals;
  private final Map<String, Member> members = new ConcurrentHashMap<>();
  private final Map<String, Object> roomLocks = new ConcurrentHashMap<>();
  private final ExecutorService timers = Executors.newVirtualThreadPerTaskExecutor();
  private final PeriodicPass sweepPass = new PeriodicPass("live-members", this::sweep);
  private volatile AgentProcess processes;
  private volatile Room room;
  private volatile Timing timing = Timing.real();

  /**
   * Run ids are time-ordered and strictly increasing within a process, so every run this sail
   * reserves has an id after this one, and a live run with an earlier id was reserved by the sail
   * before it.
   */
  private final String born = DateTimeUtils.newId().toString();

  LiveMembers(
      ProjectLoader projects,
      SpecStore specStore,
      Supplier<RoomStore> rooms,
      Supplier<MessageStore> messages,
      RunStore runStore,
      RunReservation reservation,
      RunLauncher launcher,
      RoomCommitGuard guard,
      ShellExec shell,
      DispatchOperations.EventSink events,
      SessionYield terminals) {
    this.projects = projects;
    this.specStore = specStore;
    this.rooms = rooms;
    this.messages = messages;
    this.runStore = runStore;
    this.reservation = reservation;
    this.launcher = launcher;
    this.guard = guard;
    this.shell = shell;
    this.events = events;
    this.terminals = terminals;
    this.processes = new ContainerAgentProcess(shell);
  }

  /** Wires the door replies are posted through; returns {@code this}. */
  public LiveMembers useRoom(Room room) {
    this.room = Objects.requireNonNull(room, "room");
    return this;
  }

  /** Wires how a member's process is started; returns {@code this}. */
  public LiveMembers useProcesses(AgentProcess processes) {
    this.processes = Objects.requireNonNull(processes, "processes");
    return this;
  }

  LiveMembers useTiming(Timing timing) {
    this.timing = Objects.requireNonNull(timing, "timing");
    return this;
  }

  /** Whether a member running {@code agent} is a live one: its harness has the mode. */
  @Override
  public boolean serves(String agent) {
    return Harnesses.of(agent).liveSettings().isPresent();
  }

  /** Holds every member to its lane's limits at {@code interval}. */
  public void startSweep(Duration interval) {
    sweepPass.start(interval);
  }

  /**
   * The room a conversation id names and who sits in it: a spec's room when the id is a spec's, the
   * room row itself otherwise.
   */
  private record Seat(
      String roomId,
      String project,
      SpecStore.SpecRow spec,
      String title,
      Engagement member,
      String owner) {

    /** What the run serves, as its stop names it: the spec, or the room when there is none. */
    String conversation() {
      return spec != null ? spec.id() : roomId;
    }
  }

  private Optional<Seat> seat(String conversation) {
    var store = rooms.get();
    var spec = specStore.findById(conversation).orElse(null);
    if (spec != null) {
      return Optional.of(
          new Seat(
              spec.roomIdOrIdentity(),
              spec.project(),
              spec,
              spec.title(),
              MembershipService.stateOf(store, spec).standing(),
              spec.owner()));
    }
    return store
        .findById(conversation)
        .map(
            row ->
                new Seat(
                    conversation,
                    row.project(),
                    null,
                    row.title(),
                    Roster.fromJson(row.roster()).standing(),
                    store.ownerOf(conversation)));
  }

  /**
   * Hands the room's unread human messages to its live member, starting one when none is up: a
   * fresh session is told who it is and shown the room, a resumed one gets the new messages alone.
   * A message that arrives while a turn is open is written at once and starts the next turn. A room
   * with no member, or whose member's conversation a terminal holds, is left alone; so is one with
   * nothing unread, which is what a replayed announcement finds.
   *
   * @throws ApiException when the member could not be started: a claim on its repos, or a session
   *     host that could not say whether a terminal holds its conversation
   */
  @Override
  public void deliver(String conversation, String localHandle) {
    Actor.run(
        Actor.system(), () -> seat(conversation).ifPresent(seat -> deliver(seat, localHandle)));
  }

  private void deliver(Seat seat, String localHandle) {
    if (seat.member() == null || !serves(seat.member().agent())) {
      return;
    }
    synchronized (roomLocks.computeIfAbsent(seat.roomId(), id -> new Object())) {
      var live = members.get(seat.roomId());
      if (live != null) {
        var unread = unread(tail(seat.roomId()), live.runId);
        if (unread.isEmpty()) {
          return;
        }
        if (hand(live, LiveMemberPrompt.messages(unread))) {
          return;
        }
        if (!live.over(timing.grace().whole())) {
          return;
        }
      }
      start(seat, localHandle);
    }
  }

  private List<MessageStore.MessageRow> tail(String roomId) {
    return messages.get().list(roomId, null, ROOM_TAIL);
  }

  /** The human messages of {@code tail} that the run {@code runId} was never handed. */
  private List<MessageStore.MessageRow> unread(List<MessageStore.MessageRow> tail, String runId) {
    var delivered = runStore.deliveredMessageIds(runId);
    return tail.stream()
        .filter(message -> RoomWakePolicy.humanAuthor(message.author()))
        .filter(message -> !delivered.contains(message.id()))
        .toList();
  }

  private void markDelivered(String runId, List<MessageStore.MessageRow> delivered) {
    runStore.markDelivered(runId, delivered.stream().map(MessageStore.MessageRow::id).toList());
  }

  /**
   * Writes {@code pages} to {@code member} in order, recording as delivered what each page ended
   * once it is written: a message a page left unfinished, or one in a page the process never took,
   * stays unread for the next prompt. Answers whether every page was written.
   */
  private boolean hand(
      Member member, List<PromptConversation.Rendered<MessageStore.MessageRow>> pages) {
    for (var page : pages) {
      if (!member.prompt(page.text())) {
        return false;
      }
      markDelivered(member.runId, page.fullyRendered());
    }
    return true;
  }

  /**
   * The conversation a new process picks up: the newest run of this room's member, in the same
   * lane, that recorded a session off its own stream. A wake's session, or one from before the
   * member was live, was told to post its own answers, and one from the other lane was told its
   * tools were different, so none of those is resumed; a member with no such run starts fresh.
   */
  private Optional<RunStore.RunRow> resumable(Seat seat, String role, String localHandle) {
    var runs =
        seat.spec() != null
            ? runStore.listForSpec(seat.spec().id())
            : runStore.listForRoom(seat.roomId());
    return runs.stream()
        .filter(run -> role.equals(run.role()))
        .filter(run -> run.ownedBy(localHandle))
        .filter(run -> SESSION_SOURCE.equals(run.sessionSource()))
        .filter(run -> seat.member().agent().equals(run.agent()))
        .filter(run -> Harness.isSafeSessionId(run.sessionId()))
        .findFirst();
  }

  /**
   * Whether a terminal resumed on {@code run}'s conversation is open: it holds that session. A host
   * that could not be asked has not said the conversation is free, and a second process on a held
   * one would corrupt it, so that is a refusal to start, never a no.
   */
  private boolean terminalHolds(RunStore.RunRow run) {
    try {
      return terminals.live(SessionYield.resumeSession(run.id()));
    } catch (IOException unreachable) {
      throw new ApiException(
          ErrorCode.COMMAND_FAILED,
          "Could not ask the session host whether a terminal holds the conversation of run "
              + run.id()
              + ", so no process was started over it.",
          "Check the sail-pty-host service on this box; the room's next message tries again.",
          unreachable);
    }
  }

  private void start(Seat seat, String localHandle) {
    var config = projects.loadRunning(seat.project()).config();
    if (config.agent() == null) {
      throw new ApiException(
          ErrorCode.AGENT_NOT_CONFIGURED, "No agent configured in sail.yaml's agent block.");
    }
    if (!Ownership.owns(localHandle, seat.owner())) {
      throw RoomWakeLauncher.notThisBoxs(
          (seat.spec() != null ? "Spec '" : "Room '") + seat.conversation() + "'",
          seat.owner(),
          localHandle);
    }
    var member = seat.member();
    var full = member.full();
    var role = full ? DispatchGate.ROOM_FULL_ROLE : DispatchGate.ROOM_ROLE;
    var tail = tail(seat.roomId());
    var resumed = resumable(seat, role, localHandle);
    var unread = resumed.map(run -> unread(tail, run.id())).orElse(tail);
    if (resumed.isPresent() && (unread.isEmpty() || terminalHolds(resumed.get()))) {
      return;
    }
    var pages = resumed.isPresent() ? LiveMemberPrompt.messages(unread) : fresh(seat, tail, full);
    var prompt = pages.getFirst().text();
    var command =
        Harnesses.of(member.agent())
            .live(
                new Harness.LiveLaunch(
                    full, member.model(), resumed.map(RunStore.RunRow::sessionId).orElse(null)))
            .orElseThrow();
    var repos = full ? reposOf(seat, config) : List.<SailYaml.Repo>of();
    var repoPaths = repos.stream().map(SailYaml.Repo::path).toList();
    var branch =
        full && seat.spec() != null && Strings.isNotBlank(seat.spec().branch())
            ? seat.spec().branch()
            : null;
    var workDir =
        full
            ? AgentSession.launchWorkDir(config.sshUser(), repos)
            : "/home/" + config.sshUser() + "/workspace";
    var runId = DateTimeUtils.newId().toString();
    var unit = AgentUnit.live(runId);
    var credential =
        reservation.reserve(
            runId,
            seat.project(),
            seat.spec() != null ? seat.spec().id() : null,
            seat.spec() != null ? null : seat.roomId(),
            localHandle,
            role,
            repoPaths,
            member.agent(),
            branch,
            prompt,
            unit,
            config);
    AgentProcess.Handle handle;
    try {
      launcher.stage(
          new RunLauncher.LaunchSpec(
              seat.project(),
              config,
              workDir,
              full,
              member.model(),
              null,
              seat.conversation(),
              member.agent(),
              prompt,
              branch,
              repoPaths,
              true,
              runId,
              credential,
              role,
              null));
      if (!full) {
        guard.captureRoomBaseline(seat.project(), config, runId);
      }
      if (resumed.isPresent() && terminalHolds(resumed.get())) {
        reservation.releaseIfAbsent(runId, seat.project(), unit);
        return;
      }
      handle =
          processes.start(
              seat.project(),
              workDir,
              environment(seat.conversation(), member.agent(), runId, credential, role),
              command,
              unit.pidPath(),
              unit.logPath());
    } catch (IOException | RuntimeException failed) {
      reservation.releaseIfAbsent(runId, seat.project(), unit);
      throw failed instanceof RuntimeException refused
          ? refused
          : new ApiException(
              ErrorCode.AGENT_LAUNCH_FAILED, "Failed to start the room's live member.", failed);
    }
    var started =
        new Member(seat, config, runStore.findById(runId).orElseThrow(), unit, handle, full);
    members.put(seat.roomId(), started);
    Thread.ofVirtual().name("sail-live-" + runId).start(started::read);
    Optional<String> lost;
    try {
      lost = lostInLaunch(seat, started);
    } catch (RuntimeException failed) {
      var why = "not started: " + failed.getMessage();
      if (runStore.transitionUnstarted(runId, "stopped")) {
        tellStopped(started.run, null, why);
      }
      started.end(why, timing.grace().result());
      throw failed;
    }
    if (lost.isPresent()) {
      started.end(lost.get(), timing.grace().result());
      return;
    }
    started.admit();
    markDelivered(runId, tail.stream().filter(message -> !unread.contains(message)).toList());
    hand(started, pages);
  }

  /**
   * Why a member that has just started is ended before it is prompted, or empty when it may work:
   * its run was ended while it was starting, by a claim on its repos or a terminal on its
   * conversation, or its seat was vacated or taken. Asked once the member is registered, so
   * whatever ends a run or changes a seat was either seen here or finds the member. Stamping the
   * process on the run is the same compare a launched run is verified by ({@link
   * RunStore#updateProcess}): it lands only while the run is still {@code running}. The pid is
   * waited for because the stamp is what the rest of the box reads: before it the run was never
   * prompted, and after it the pid file the wrapper wrote is there to ask the container through.
   *
   * @throws ApiException when the process wrote no pid: it did not start
   */
  private Optional<String> lostInLaunch(Seat seat, Member started) {
    var pid = started.awaitPid();
    if (pid.isEmpty()) {
      throw new ApiException(
          ErrorCode.AGENT_LAUNCH_FAILED,
          "The room's live member did not start: its process wrote no pid.",
          "Check that the project's container is running and answers; the room's next message"
              + " starts the member again.");
    }
    if (!runStore.updateProcess(started.runId, pid.getAsInt(), null, null)) {
      return Optional.of("run ended during launch");
    }
    var seated = seat(seat.conversation()).map(Seat::member).orElse(null);
    return seat.member().equals(seated) ? Optional.empty() : Optional.of(MEMBERSHIP_CHANGED);
  }

  /** Waits out {@code pause}; {@code false} when interrupted instead, the interrupt kept. */
  private boolean waited(Duration pause) {
    try {
      timing.delay().pause(pause);
      return true;
    } catch (InterruptedException stopping) {
      Thread.currentThread().interrupt();
      return false;
    }
  }

  private List<PromptConversation.Rendered<MessageStore.MessageRow>> fresh(
      Seat seat, List<MessageStore.MessageRow> tail, boolean full) {
    if (seat.spec() == null) {
      return LiveMemberPrompt.freshForRoom(seat.roomId(), seat.title(), tail, full);
    }
    var body = specStore.getContent(seat.spec().id()).map(SpecStore.SpecContent::body).orElse("");
    return LiveMemberPrompt.fresh(
        seat.spec(), body.isBlank() ? seat.spec().title() : body, tail, full);
  }

  /** A spec's repos for its room, the project's whole set for a room with no spec to narrow it. */
  private static List<SailYaml.Repo> reposOf(Seat seat, SailYaml config) {
    return seat.spec() != null
        ? DispatchRepos.resolve(config, seat.spec().toSpec(), List.of())
        : config.repos();
  }

  /** What every run exports, so the {@code spec} CLI speaks for this one. */
  private static Map<String, String> environment(
      String conversation, String agent, String runId, String credential, String role) {
    var env = new LinkedHashMap<String, String>();
    env.put("SAIL_SPEC_ID", conversation);
    env.put("SAIL_AGENT", agent);
    env.put("SAIL_RUN_ID", runId);
    env.put("SAIL_RUN_CREDENTIAL", credential);
    env.put("SAIL_RUN_ROLE", role);
    return env;
  }

  /**
   * Ends the member of the room {@code conversation} names because its membership changed: it was
   * dismissed, or another agent, mode or model took the seat. The next message starts whoever sits
   * there now.
   */
  @Override
  public void membershipChanged(String conversation) {
    Actor.run(
        Actor.system(),
        () ->
            seat(conversation)
                .map(seat -> members.get(seat.roomId()))
                .ifPresent(member -> member.end(MEMBERSHIP_CHANGED, timing.grace().result())));
  }

  /**
   * Ends the live member whose run is {@code runId} so that what it holds is free: at its open
   * turn's result where this process holds its stdin, by signal where another does. Returns whether
   * the run is over; a run that is not a live member's is left alone and answers {@code false}.
   */
  public boolean endHolder(String runId, String why) {
    return Actor.call(
        Actor.system(),
        () -> {
          var mine =
              members.values().stream().filter(member -> member.runId.equals(runId)).findFirst();
          return mine.isPresent()
              ? mine.get().end(why, timing.grace().result())
              : endUnheld(runId, why);
        });
  }

  /**
   * Ends the live member's run {@code runId} that no member of this process is, and answers whether
   * it is over. A run that records no process was never prompted, so nothing in its container needs
   * asking: it is ended as unstarted, a compare its owner's stamp loses to. A run that compare did
   * not end is read after it. One that is no longer {@code running} is over, or is whoever claimed
   * it's to finish. One that still is records its process: its pid file was written before the
   * stamp, so what the container says of it is the truth, and only its answer that the process is
   * gone ends the run.
   */
  private boolean endUnheld(String runId, String why) {
    var run = runStore.findById(runId).filter(row -> AgentUnit.isLive(row.unit())).orElse(null);
    if (run == null) {
      return false;
    }
    if (runStore.transitionUnstarted(runId, "stopped")) {
      tellStopped(run, null, why);
      return true;
    }
    var recorded = runStore.findById(runId).orElseThrow();
    if (liveRun(recorded) && !halted(recorded)) {
      return false;
    }
    finish(recorded, "stopped", null, why);
    return true;
  }

  /** Whether {@code run} is a live member's that has not ended. */
  public static boolean liveRun(RunStore.RunRow run) {
    return "running".equals(run.status()) && AgentUnit.isLive(run.unit());
  }

  /**
   * Finishes the live members' runs this box left running from before this sail started: what a
   * restart finds. A run reserved by this sail, registered or not yet, is its own and is left
   * alone. A run that records no process was never prompted and is ended without a look. The others
   * lost their stdin with the sail that held it, so most have exited, and any that has not is
   * signalled; one whose process nothing proved gone — its container did not answer, or the process
   * outlived its signals — is left {@code running}: a turn it has open may still be writing the
   * repos it reserved. Such a run is finished by the sweep of runs whose process is gone, once its
   * container says so.
   */
  public Reclaimed reclaim(String localHandle) {
    return Actor.call(
        Actor.system(),
        () -> {
          var orphans =
              runStore.running().stream()
                  .filter(run -> run.ownedBy(localHandle) && run.chatRole() && liveRun(run))
                  .map(RunStore.RunRow::id)
                  .filter(runId -> runId.compareTo(born) < 0)
                  .toList();
          var held = new ArrayList<String>();
          for (var runId : orphans) {
            if (!endUnheld(runId, "sail restarted")) {
              held.add(runId);
            }
          }
          return new Reclaimed(orphans.size() - held.size(), List.copyOf(held));
        });
  }

  /** Signals {@code run}'s process through its pid file; whether it is then known gone. */
  private boolean halted(RunStore.RunRow run) {
    var unit = AgentUnit.recorded(run.id(), run.unit());
    try {
      return new AgentSession(shell).killAgent(run.project(), unit)
              instanceof AgentSession.Halt.Ended
          || new AgentPresence(shell).gone(run.project(), unit);
    } catch (Exception unanswered) {
      return false;
    }
  }

  /**
   * The one end of a live member's run: {@code running} to {@code status} as a compare-and-set, and
   * the stop its watcher would have published, for whoever wins it. Finishing the row is what
   * revokes its credential and frees what it reserved.
   */
  private void finish(RunStore.RunRow run, String status, Integer exitCode, String reason) {
    if (runStore.transition(run.id(), "running", status, exitCode)) {
      tellStopped(run, exitCode, reason);
    }
  }

  private void tellStopped(RunStore.RunRow run, Integer exitCode, String reason) {
    events.publish(
        RunStops.of(
            Event.WellKnownData.SOURCE_LIVE,
            run.project(),
            run.conversationId(),
            run.agent(),
            run.id(),
            run.role(),
            exitCode,
            reason));
  }

  /** Ends every member that reached a limit of its lane: its lifetime, or its idle window. */
  public void sweep() {
    var now = timing.clock().get();
    List.copyOf(members.values()).forEach(member -> member.holdToLimits(now));
  }

  /** Ends every member: sail is stopping, and nothing would read them after it. */
  @Override
  public void close() {
    sweepPass.close();
    try (var ending = Executors.newVirtualThreadPerTaskExecutor()) {
      members
          .values()
          .forEach(
              member -> ending.execute(() -> member.end("sail stopping", timing.grace().result())));
    }
    timers.shutdownNow();
  }

  /** One live process and the run it is. Every field a line or a prompt moves is guarded by it. */
  private final class Member {

    private final Seat seat;
    private final SailYaml config;
    private final RunStore.RunRow run;
    private final String runId;
    private final AgentUnit unit;
    private final AgentProcess.Handle handle;
    private final boolean full;
    private final Guardrails limits;
    private final Instant startedAt;
    private final Object pipe = new Object();
    private final StringBuilder said = new StringBuilder();
    private final StringBuilder unsent = new StringBuilder();
    private final Map<String, String> calls = new LinkedHashMap<>();
    private Instant lastLine;
    private Instant lastDelta = Instant.MIN;
    private Instant lastStamp = Instant.MIN;
    private String sessionId;
    private int turn;
    private boolean turnOpen;
    private boolean prompted;
    private boolean admitted;
    private boolean guarded;
    private boolean flushing;
    private boolean ending;
    private String endReason;
    private boolean over;

    private Member(
        Seat seat,
        SailYaml config,
        RunStore.RunRow run,
        AgentUnit unit,
        AgentProcess.Handle handle,
        boolean full) {
      this.seat = seat;
      this.config = config;
      this.run = run;
      this.runId = run.id();
      this.unit = unit;
      this.handle = handle;
      this.full = full;
      this.limits = config.agent().guardrailsFor(run.lane().orElseThrow());
      this.startedAt = timing.clock().get();
      this.lastLine = startedAt;
      this.guarded = !full;
    }

    /**
     * Writes {@code text} to the process as its next prompt, the lane's guard armed first. Answers
     * {@code false} when the process is ending or gone, so the prompt is for whoever comes next.
     *
     * <p>The write is made outside this member's lock, under {@code pipe} alone: a process that has
     * stopped reading blocks it, and its lines must still be read and its end still be reached
     * while it does. The prompt is owed from before the write, so one that blocks for the idle
     * window is a stall, and is owed no longer when the write failed. The reader finishes the run
     * under {@code pipe} too, so a process's end is judged with every prompt taken or refused,
     * never with one half written.
     */
    private boolean prompt(String text) {
      synchronized (pipe) {
        boolean owed;
        synchronized (this) {
          if (ending || over) {
            return false;
          }
          arm();
          owed = prompted;
          prompted = true;
          lastLine = timing.clock().get();
        }
        if (written(text)) {
          return true;
        }
        synchronized (this) {
          prompted = owed && prompted;
        }
        return false;
      }
    }

    private boolean written(String text) {
      try {
        handle.stdin().write(LiveStream.userMessage(text));
        return true;
      } catch (IOException gone) {
        return false;
      }
    }

    /** The run records the process: from here the member is one that started, and is prompted. */
    private synchronized void admit() {
      admitted = true;
    }

    /**
     * The pid the process's wrapper wrote, asked for until it is there, the process is over, or
     * {@link #PID_PATIENCE} has passed since it started.
     */
    private OptionalInt awaitPid() {
      var deadline = startedAt.plus(PID_PATIENCE);
      var pid = handle.pid();
      while (pid.isEmpty()
          && !over(Duration.ZERO)
          && timing.clock().get().isBefore(deadline)
          && waited(PID_ASK_GAP)) {
        pid = handle.pid();
      }
      return pid;
    }

    /**
     * Saves a read-only member's commit baseline unless one is already held: before a prompt, and
     * again as a turn opens that no prompt of its own armed — the one a message queued during the
     * turn before it starts — so a change is always judged within the turn it happened in.
     */
    private void arm() {
      if (!full && !guarded) {
        guard.captureRoomBaseline(seat.project(), config, runId);
        guarded = true;
      }
    }

    private void read() {
      try {
        String line;
        while ((line = handle.stdout().read()) != null) {
          var read = line;
          attempt(() -> step(read));
        }
      } catch (IOException broken) {
        System.err.println("  [live] run " + runId + " stopped answering: " + broken.getMessage());
      }
      synchronized (pipe) {
        attempt(this::finished);
      }
    }

    /** Runs {@code work} as sail; a failure is this line's, never the reader's. */
    private void attempt(Runnable work) {
      try {
        Actor.run(Actor.system(), work);
      } catch (RuntimeException failed) {
        System.err.println("  [live] run " + runId + ": " + failed.getMessage());
      }
    }

    private synchronized void step(String line) {
      lastLine = timing.clock().get();
      if (!lastLine.isBefore(lastStamp.plus(ACTIVITY_FLOOR))) {
        runStore.stampActivity(runId, ACTIVITY_FLOOR);
        lastStamp = lastLine;
      }
      switch (LiveStream.read(line)) {
        case LiveStream.Step.Init init -> session(init.sessionId());
        case LiveStream.Step.TextStarted started -> {
          open();
          if (!said.isEmpty()) {
            say("\n\n");
          }
        }
        case LiveStream.Step.Text text -> {
          open();
          say(text.text());
        }
        case LiveStream.Step.ToolCalls called -> {
          open();
          flush();
          called.calls().forEach(this::called);
        }
        case LiveStream.Step.ToolResults returned -> {
          open();
          flush();
          returned.callIds().forEach(this::returned);
        }
        case LiveStream.Step.Working working -> open();
        case LiveStream.Step.Result result -> {
          open();
          answered(result);
        }
        case LiveStream.Step.Other other -> {}
      }
    }

    private void session(String named) {
      if (named.equals(sessionId)) {
        return;
      }
      var first = sessionId == null;
      sessionId = named;
      runStore.recordSession(runId, named, SESSION_SOURCE, null);
      if (!first) {
        return;
      }
      var data = new LinkedHashMap<String, Object>();
      handle.pid().ifPresent(pid -> data.put("pid", pid));
      data.put(Event.WellKnownData.RUN_ID, runId);
      data.put(Event.WellKnownData.RUN_ROLE, run.role());
      events.publish(
          Event.of(
              seat.project(),
              seat.conversation(),
              Event.WellKnownTypes.AGENT_SESSION_STARTED,
              run.agent(),
              HostInfo.hostname(),
              data));
    }

    private void open() {
      if (!turnOpen) {
        begin();
        arm();
      }
    }

    private void begin() {
      turnOpen = true;
      prompted = false;
      turn++;
    }

    /**
     * Takes {@code fragment} as part of the turn's reply and tells it, now or within the window.
     */
    private void say(String fragment) {
      if (said.length() <= MessageStore.MAX_BODY_BYTES) {
        said.append(fragment);
      }
      unsent.append(fragment);
      if (!lastLine.isBefore(lastDelta.plus(DELTA_WINDOW))) {
        flush();
      } else if (!flushing) {
        flushing = true;
        timers.execute(this::flushLater);
      }
    }

    private void flushLater() {
      if (!waited(DELTA_WINDOW)) {
        return;
      }
      synchronized (this) {
        flushing = false;
        flush();
      }
    }

    private void flush() {
      if (unsent.isEmpty()) {
        return;
      }
      lastDelta = timing.clock().get();
      tell(
          Event.WellKnownTypes.AGENT_TEXT_DELTA,
          Map.of("text", Strings.clip(unsent.toString(), DELTA_CODE_POINTS)));
      unsent.setLength(0);
    }

    private void called(LiveStream.ToolCall call) {
      calls.put(call.id(), call.tool());
      var data = new LinkedHashMap<String, Object>();
      data.put("tool", call.tool());
      data.put("summary", call.summary());
      data.put("status", "started");
      tell(Event.WellKnownTypes.AGENT_TOOL_CALL, data);
    }

    private void returned(String callId) {
      var tool = calls.remove(callId);
      if (tool == null) {
        return;
      }
      var data = new LinkedHashMap<String, Object>();
      data.put("tool", tool);
      data.put("status", "finished");
      tell(Event.WellKnownTypes.AGENT_TOOL_CALL, data);
    }

    /** The turn's result: its text becomes the member's message, and the lane's guard looks. */
    private void answered(LiveStream.Step.Result result) {
      flush();
      closeTurn(said.isEmpty() ? result.text() : said.toString(), result.error(), result.reason());
      disarm();
      notifyAll();
    }

    /**
     * The guard's look at what the turn left: it consumes the baseline the turn was armed with. A
     * look the container would not answer is this turn's loss alone, told on the server's stderr:
     * the turn still ends, and a process whose output ended is still finished.
     */
    private void disarm() {
      if (!guarded) {
        return;
      }
      guarded = false;
      try {
        guard.guardRoomRun(seat.project(), runId);
      } catch (RuntimeException unanswered) {
        System.err.println(
            "  [live] run "
                + runId
                + ": the lane's guard could not look: "
                + unanswered.getMessage());
      }
    }

    /** Posts {@code text} as the turn's reply, when there is any, and says the turn ended. */
    private void closeTurn(String text, boolean error, String reason) {
      var data = new LinkedHashMap<String, Object>();
      var why = reason;
      var failed = error;
      if (!text.isBlank()) {
        try {
          data.put(
              Event.WellKnownData.MESSAGE_ID,
              room.post(run, seat.roomId(), MessageStore.fitted(text)));
        } catch (RuntimeException refused) {
          failed = true;
          why = "reply not posted: " + refused.getMessage();
        }
      }
      data.put("is_error", failed);
      if (why != null) {
        data.put(Event.WellKnownData.REASON, Strings.clip(why, REASON_CODE_POINTS));
      }
      tell(Event.WellKnownTypes.AGENT_TURN_ENDED, data);
      turnOpen = false;
      said.setLength(0);
      calls.clear();
    }

    private void tell(String type, Map<String, Object> more) {
      var data = new LinkedHashMap<String, Object>();
      data.put(Event.WellKnownData.RUN_ID, runId);
      data.put(Event.WellKnownData.ROOM_ID, seat.roomId());
      data.put(Event.WellKnownData.PRINCIPAL, run.principal());
      data.put(Event.WellKnownData.TURN, turn);
      data.putAll(more);
      events.publish(
          Event.of(seat.project(), seat.roomId(), type, run.agent(), HostInfo.hostname(), data));
    }

    /**
     * The process's output ended, so the run is over: a turn it left open — a prompt it never began
     * to answer is one — is posted as far as it got and told as cut short, the lane's guard takes
     * its last look, and the run is finished: {@code failed} when it died owing an answer, or
     * before its run recorded it, with nobody having asked it to end, {@code stopped} otherwise.
     * Whatever that meets, the member is over and gone from its room: a run a store refused to
     * finish is left to the claim that displaces it or the sweep that finds its process gone, never
     * behind a member that takes no prompt.
     */
    private synchronized void finished() {
      try {
        flush();
        if (prompted && !turnOpen) {
          begin();
        }
        var exit = exitCode();
        var died = (turnOpen || !admitted) && !ending;
        var why = ending ? endReason : died ? diagnosis(exit) : null;
        if (turnOpen) {
          closeTurn(said.toString(), true, why);
        }
        disarm();
        finish(run, died ? "failed" : "stopped", exit.isPresent() ? exit.getAsInt() : null, why);
      } finally {
        members.remove(seat.roomId(), this);
        over = true;
        notifyAll();
      }
    }

    private OptionalInt exitCode() {
      try {
        return handle.waitFor(EXIT_GRACE);
      } catch (InterruptedException interrupted) {
        Thread.currentThread().interrupt();
        return OptionalInt.empty();
      }
    }

    /** Why a process died, in its own words: the end of what it wrote to its log. */
    private String diagnosis(OptionalInt exit) {
      var logged = logTail();
      if (!logged.isEmpty()) {
        return Strings.clip(logged, REASON_CODE_POINTS);
      }
      return exit.isPresent() ? "exited with code " + exit.getAsInt() : "ended without a result";
    }

    private String logTail() {
      try {
        return shell
            .exec(
                ContainerExec.asDevUser(
                    seat.project(), List.of("tail", "-c", LOG_TAIL_BYTES, unit.logPath())))
            .stdout()
            .strip();
      } catch (Exception unanswered) {
        return "";
      }
    }

    private void holdToLimits(Instant now) {
      String why;
      Duration first;
      synchronized (this) {
        var lifetime = Guardrails.parseDuration(limits.maxDuration());
        var idle = Guardrails.parseDuration(limits.maxIdle());
        if (lifetime != null && !now.isBefore(startedAt.plus(lifetime))) {
          why = "time limit (" + limits.maxDuration() + ")";
          first = timing.grace().result();
        } else if (idle != null && !now.isBefore(lastLine.plus(idle))) {
          var owing = turnOpen || prompted;
          why = owing ? "stall (" + limits.maxIdle() + ")" : null;
          first = owing ? timing.grace().result() : timing.grace().idleExit();
        } else {
          return;
        }
      }
      end(why, first);
    }

    /**
     * Ends the process: its stdin is closed, so it finishes what it is doing and exits; it is given
     * {@code first} to do that, or to answer the turn it has open, and is then interrupted and, if
     * that does not end it either, terminated. Returns whether it is over. The close is made on a
     * thread of its own: it waits behind a write the process is not reading, and the signals that
     * end such a process must not wait with it.
     */
    private boolean end(String why, Duration first) {
      boolean answering;
      synchronized (this) {
        if (over) {
          return true;
        }
        if (!ending) {
          ending = true;
          endReason = why;
          Thread.ofVirtual().name("sail-live-end-" + runId).start(handle.stdin()::close);
        }
        answering = turnOpen;
      }
      if (!settled(first, answering)) {
        handle.signal(AgentProcess.Signal.INT);
        if (!over(timing.grace().afterInt())) {
          handle.signal(AgentProcess.Signal.TERM);
        }
      }
      return over(timing.grace().afterTerm());
    }

    /** Whether the process is over within {@code within}. */
    private boolean over(Duration within) {
      return settled(within, false);
    }

    /**
     * Waits up to {@code within} for the process to be over or, when {@code answering}, for its
     * open turn to end; returns whether it is over.
     */
    private synchronized boolean settled(Duration within, boolean answering) {
      var deadline = System.nanoTime() + within.toNanos();
      while (!over && !(answering && !turnOpen)) {
        var left = deadline - System.nanoTime();
        if (left <= 0) {
          break;
        }
        try {
          wait(Math.max(1, left / 1_000_000));
        } catch (InterruptedException interrupted) {
          Thread.currentThread().interrupt();
          break;
        }
      }
      return over;
    }
  }
}
