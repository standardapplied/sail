/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.api;

import ai.singlr.sail.common.DateTimeUtils;
import ai.singlr.sail.common.Ids;
import ai.singlr.sail.common.Remedied;
import ai.singlr.sail.common.Strings;
import ai.singlr.sail.config.Guardrails;
import ai.singlr.sail.config.Lane;
import ai.singlr.sail.config.YamlUtil;
import ai.singlr.sail.engine.AgentProcess;
import ai.singlr.sail.engine.ChatWire;
import ai.singlr.sail.engine.ContainerExec;
import ai.singlr.sail.engine.LaunchEnv;
import ai.singlr.sail.engine.LiveStream;
import ai.singlr.sail.engine.SessionLogin;
import ai.singlr.sail.engine.ShellExec;
import ai.singlr.sail.harness.Harness;
import ai.singlr.sail.harness.HarnessChat;
import ai.singlr.sail.harness.Harnesses;
import ai.singlr.sail.identity.Actor;
import ai.singlr.sail.pty.FileScripts;
import ai.singlr.sail.pty.PtyAdmission;
import ai.singlr.sail.pty.PtyIdentity;
import ai.singlr.sail.pty.PtyMessage;
import ai.singlr.sail.store.ChatStore;
import ai.singlr.sail.store.FdeStore;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

/**
 * The chats this box holds: a person talks to an agent in their own workspace, the agent run as one
 * process over pipes ({@link AgentProcess}) that holds the chat's conversation, and every item of
 * the chat is logged ({@link ChatStore}) before anyone reads it. A chat is started as a pty agent
 * is, through the same doors ({@link HostPty#admitAgent}, {@link HostPty#handOver}), and every
 * route to it is its opener's alone, decided as entering its workspace is. At most one process
 * holds a chat's conversation, and only a message starts one: it ends after the project's {@code
 * max_idle} with no turn open, and the next message resumes the conversation by id, in the mode in
 * force. A request the agent makes is logged and held; nothing here answers one, so after {@link
 * Timing#requestBound} the process is ended and the request withdrawn, said in plain words.
 */
public final class ChatHost implements AutoCloseable {

  /** The most items one read answers. */
  static final int PAGE = 500;

  /** The most files a message attaches. */
  static final int ATTACHMENTS = 8;

  /** The largest image a message attaches: what Claude takes, 5 MiB once in base64. */
  static final long IMAGE_BYTES = 3_932_160;

  /** How long a chat's log is kept once it ended. */
  static final Duration RETENTION = Duration.ofDays(30);

  /** What a turn that sail stopping cut says. */
  static final String STOPPED =
      "Sail stopped while this turn was open, so it was cut; your next message starts it again.";

  /** What a turn that a restart of sail cut says. */
  static final String RESTARTED =
      "Sail restarted while this turn was open, so it was cut; your next message starts it again.";

  /** Where a chat's process runs, and the only folder its attachments are read from. */
  static final String WORKSPACE = ContainerExec.DEV_WORKSPACE;

  private static final String CHATS = ContainerExec.DEV_HOME + "/.sail/chats/";
  private static final Duration READ_IMAGE = Duration.ofSeconds(30);
  private static final Map<String, String> IMAGE_TYPES =
      Map.of(
          "png", "image/png",
          "jpg", "image/jpeg",
          "jpeg", "image/jpeg",
          "gif", "image/gif",
          "webp", "image/webp");

  /**
   * Time as the host reads it, injectable so tests never sleep: the clock, how a pause is waited
   * (the text window), how a read waits for an item ({@code patience}) and the most it waits
   * ({@code poll}), how long a request is held unanswered, and the ending grace.
   */
  record Timing(
      Supplier<Instant> clock,
      RoomWakeReactor.Delay delay,
      Patience patience,
      Duration poll,
      Duration requestBound,
      ProcessEnd.Grace grace) {

    static Timing real() {
      return new Timing(
          DateTimeUtils::now,
          Thread::sleep,
          (arrivals, within) -> arrivals.wait(Math.max(1, within.toMillis())),
          Duration.ofSeconds(25),
          Duration.ofMinutes(5),
          ProcessEnd.Grace.DEFAULT);
    }
  }

  /**
   * How a read waits, holding {@code arrivals}, for an item to be logged, at most {@code within}.
   */
  @FunctionalInterface
  interface Patience {
    void await(Object arrivals, Duration within) throws InterruptedException;
  }

  /** A chat opened: its session id. */
  record Opened(String session) implements Mappable {
    @Override
    public Map<String, Object> toMap() {
      return Map.of("session", session);
    }
  }

  /** What a read answers: the items, oldest first, and whether the chat has ended. */
  record Read(List<Map<String, Object>> items, boolean ended) implements Mappable {
    @Override
    public Map<String, Object> toMap() {
      var map = new LinkedHashMap<String, Object>();
      map.put("items", items);
      map.put("ended", ended);
      return map;
    }
  }

  /** A message sent: the number it was logged as, and whether it waits for the turn to end. */
  record Sent(long seq, boolean queued) implements Mappable {
    @Override
    public Map<String, Object> toMap() {
      var map = new LinkedHashMap<String, Object>();
      map.put("seq", seq);
      map.put("queued", queued);
      return map;
    }
  }

  private final ChatStore store;
  private final FdeStore fdes;
  private final HostPty doors;
  private final ProjectReader definitions;
  private final ShellExec shell;
  private final AgentProcess processes;
  private final Timing timing;
  private final Map<String, ChatProcess> live = new ConcurrentHashMap<>();
  private final Map<String, Object> locks = new ConcurrentHashMap<>();
  private final Object arrivals = new Object();
  private final PeriodicPass sweepPass = new PeriodicPass("chats", this::sweep);

  ChatHost(
      ChatStore store,
      FdeStore fdes,
      HostPty doors,
      ProjectReader definitions,
      ShellExec shell,
      AgentProcess processes,
      Timing timing) {
    this.store = Objects.requireNonNull(store, "store");
    this.fdes = Objects.requireNonNull(fdes, "fdes");
    this.doors = Objects.requireNonNull(doors, "doors");
    this.definitions = Objects.requireNonNull(definitions, "definitions");
    this.shell = Objects.requireNonNull(shell, "shell");
    this.processes = Objects.requireNonNull(processes, "processes");
    this.timing = Objects.requireNonNull(timing, "timing");
  }

  /**
   * Opens a chat with {@code harness} in the caller's workspace {@code workspace} of {@code
   * project}, in {@code mode}, signed in with their stored login or, {@code inside}, with the
   * harness's own sign-in there: refused as a pty agent start of it is, word for word. No process
   * starts until a message is sent.
   */
  public Opened open(
      String project, String workspace, String harness, String mode, boolean inside) {
    var agent = Harnesses.of(Strings.requireNonBlank(harness, "harness"));
    Harness.Mode.named(form(agent).modes(), mode, agent.displayName());
    var person = person();
    admit(() -> doors.admitWorkspace(project, workspace, person));
    door(() -> doors.admitAgent(project, workspace, agent(harness, mode, inside), person));
    var session = Ids.newId().toString();
    store.open(
        new ChatStore.Chat(
            session,
            openerId(),
            project,
            workspace,
            harness,
            mode,
            inside,
            null,
            false,
            0,
            timing.clock().get(),
            null));
    return new Opened(session);
  }

  /**
   * The items of the caller's chat {@code session} after {@code after}: at once when there are any,
   * else once one is logged or {@link Timing#poll} passes, whichever is first. Never starts a
   * process.
   */
  public Read read(String session, long after) {
    if (after < 0) {
      throw new IllegalArgumentException("after is the seq of the last item read, 0 for none.");
    }
    yours(session);
    var deadline = timing.clock().get().plus(timing.poll());
    synchronized (arrivals) {
      while (true) {
        var items = store.after(session, after, PAGE);
        var ended = store.find(session).map(ChatStore.Chat::ended).orElse(true);
        var now = timing.clock().get();
        if (!items.isEmpty() || ended || !now.isBefore(deadline)) {
          return new Read(items.stream().map(ChatHost::wire).toList(), ended);
        }
        try {
          timing.patience().await(arrivals, Duration.between(now, deadline));
        } catch (InterruptedException interrupted) {
          Thread.currentThread().interrupt();
          return new Read(List.of(), false);
        }
      }
    }
  }

  /**
   * Sends {@code text}, with the workspace images {@code attachments} names, to the caller's chat
   * {@code session}: logged, then handed to the process that holds its conversation, started when
   * none does; held, and said to be, while a turn is open.
   */
  public Sent send(String session, String text, List<String> attachments) {
    Strings.requireNonBlank(text, "text");
    if (attachments.size() > ATTACHMENTS) {
      throw new IllegalArgumentException("A message attaches at most " + ATTACHMENTS + " files.");
    }
    var chat = yours(session);
    synchronized (lock(session)) {
      chat = store.find(session).orElseThrow(() -> unknown(session));
      if (chat.ended()) {
        throw ended(chat);
      }
      var images = images(chat, attachments);
      var process = live.get(session);
      if (process == null || process.over()) {
        process = start(chat);
      }
      if (store.find(session).map(ChatStore.Chat::ended).orElse(true)) {
        var ended = process;
        Thread.ofVirtual()
            .name("sail-chat-ended-" + session)
            .start(() -> ended.end(timing.grace().afterInt(), null));
        throw ended(chat);
      }
      return process.send(text, List.copyOf(attachments), images);
    }
  }

  private static ApiException ended(ChatStore.Chat chat) {
    return new ApiException(
        ErrorCode.CONFLICT,
        "This chat has ended, so it takes no message.",
        "Open a new chat in workspace " + chat.workspace() + ".");
  }

  /**
   * Ends the caller's chat {@code session}: its process, if one holds it, is ended, and the chat is
   * marked ended; its log stays readable for {@link #RETENTION}.
   */
  public void end(String session) {
    yours(session);
    store.ended(session, timing.clock().get());
    locks.remove(session);
    var process = live.get(session);
    if (process != null) {
      process.end(timing.grace().afterInt(), null);
    }
    arrived();
  }

  /**
   * Closes the turns left open by the processes a restart of sail ended: each chat whose turn was
   * open is told it was cut, and its next message resumes it. Returns how many.
   */
  public int reclaim() {
    var cut = store.openTurns();
    cut.forEach(
        session -> ChatProcess.cut(store, session, RESTARTED, timing.clock().get(), this::arrived));
    return cut.size();
  }

  /** Holds every chat to its bounds at {@code interval}. */
  public void startSweep(Duration interval) {
    sweepPass.start(interval);
  }

  /**
   * One pass: ends each process idle past its project's {@code max_idle}, and each that held a
   * request unanswered past {@link Timing#requestBound}, saying so; erases the chats that ended
   * more than {@link #RETENTION} ago.
   */
  void sweep() {
    var now = timing.clock().get();
    for (var process : List.copyOf(live.values())) {
      if (process.unanswered(now, timing.requestBound())) {
        process.end(
            timing.grace().afterInt(),
            "Nobody can answer the agent's request in this version of Sail, so after "
                + timing.requestBound().toMinutes()
                + " minutes Sail ended its turn and withdrew the request. Your next message"
                + " starts it again.");
      } else if (process.idle(now)) {
        process.end(timing.grace().idleExit(), null);
      }
    }
    store.expire(now.minus(RETENTION));
  }

  /**
   * Ends every process: sail is stopping. The chats stay open, and resume on their next message.
   */
  @Override
  public void close() {
    sweepPass.close();
    try (var ending = Executors.newVirtualThreadPerTaskExecutor()) {
      live.values()
          .forEach(process -> ending.execute(() -> process.end(timing.grace().result(), STOPPED)));
    }
  }

  /**
   * Starts the process for {@code chat}, as its opener: admitted and signed in as a pty agent start
   * is, its workspace entered (started when stopped), its login handed over as a file its launch
   * reads and removes, resuming the conversation the harness last named, in the mode it last said.
   */
  private ChatProcess start(ChatStore.Chat chat) {
    var harness = Harnesses.of(chat.harness());
    var form = form(harness);
    var person = person();
    var project = chat.project();
    var workspace = chat.workspace();
    var login =
        door(
            () ->
                doors.admitAgent(
                    project, workspace, agent(chat.harness(), chat.mode(), chat.inside()), person));
    admit(() -> doors.enterWorkspace(project, workspace, person));
    var launch = Ids.newId().toString();
    var file = SessionLogin.file(launch);
    var instance = project + "-" + workspace;
    admit(
        () ->
            doors.handOver(
                project, workspace, file, login.map(LaunchEnv::loginLines).orElse(""), person));
    AgentProcess.Handle handle;
    try {
      handle =
          processes.start(
              instance,
              WORKSPACE,
              Map.of(),
              form.argv(new HarnessChat.Launch(chat.mode(), null, chat.conversation())),
              CHATS + launch + "/agent.pid",
              CHATS + launch + "/agent.log",
              file);
    } catch (IOException | RuntimeException failed) {
      doors.withdraw(instance, file);
      throw new ApiException(
          ErrorCode.AGENT_LAUNCH_FAILED,
          harness.displayName() + " did not start in workspace " + workspace + ".",
          "Check that the workspace is running and answers, then send the message again.",
          failed);
    }
    var process =
        new ChatProcess(
            chat.id(),
            harness.displayName(),
            store,
            form,
            handle,
            timing,
            maxIdle(project),
            new ChatProcess.Host() {
              @Override
              public void arrived() {
                ChatHost.this.arrived();
              }

              @Override
              public List<LiveStream.Image> images(List<String> attachments) {
                return ChatHost.this.images(chat, attachments);
              }

              @Override
              public void gone(ChatProcess ended) {
                live.remove(ended.session(), ended);
                Thread.ofVirtual()
                    .name("sail-chat-withdraw")
                    .start(() -> doors.withdraw(instance, file));
              }
            });
    live.put(chat.id(), process);
    process.read();
    return process;
  }

  /** The project's {@code max_idle}, which a chat's process ends after with no turn open. */
  private Duration maxIdle(String project) {
    var idle = definitions.require(project).guardrailsFor(Lane.ROOM).maxIdle();
    return Objects.requireNonNullElse(
        Guardrails.parseDuration(idle), Guardrails.parseDuration(Guardrails.defaults().maxIdle()));
  }

  /**
   * The images {@code attachments} names in {@code chat}'s workspace, each read through the files
   * door's rules: a file within the workspace, a link's target included, of a type the model reads
   * and at most {@link #IMAGE_BYTES}. The path reaches the workspace on the read's input, never a
   * command line.
   */
  List<LiveStream.Image> images(ChatStore.Chat chat, List<String> attachments) {
    var instance = chat.project() + "-" + chat.workspace();
    return attachments.stream().map(path -> image(instance, chat.workspace(), path)).toList();
  }

  private LiveStream.Image image(String instance, String workspace, String path) {
    if (Strings.isBlank(path) || path.indexOf('\0') >= 0 || path.length() > 4096) {
      throw new IllegalArgumentException("An attachment is a path in the workspace.");
    }
    var extension = path.substring(path.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT);
    var type = IMAGE_TYPES.get(extension);
    if (type == null) {
      throw new IllegalArgumentException(
          "Attachment " + path + " is not an image: a chat attaches png, jpeg, gif or webp.");
    }
    ShellExec.Result read;
    try {
      read =
          shell.execWithInput(
              ContainerExec.asDevUser(
                  instance, WORKSPACE, Map.of(), FileScripts.image(WORKSPACE, IMAGE_BYTES)),
              path + "\0",
              READ_IMAGE);
    } catch (IOException | TimeoutException | InterruptedException failed) {
      if (failed instanceof InterruptedException) {
        Thread.currentThread().interrupt();
      }
      throw new ApiException(
          ErrorCode.CONTAINER_ERROR,
          "Attachment " + path + " could not be read in workspace " + workspace + ".",
          failed);
    }
    if (!read.ok()) {
      throw new ApiException(
          ErrorCode.INVALID_REQUEST, unreadable(path, workspace, read.exitCode()));
    }
    return new LiveStream.Image(type, read.stdout().strip());
  }

  private static String unreadable(String path, String workspace, int exit) {
    return switch (exit) {
      case FileScripts.OUTSIDE_HOME ->
          "Attachment " + path + " is outside workspace " + workspace + ".";
      case FileScripts.TOO_LARGE ->
          "Attachment " + path + " is larger than " + IMAGE_BYTES / 1024 + " KiB.";
      default ->
          "Attachment " + path + " is not a file Sail may read in workspace " + workspace + ".";
    };
  }

  /**
   * The caller's chat {@code session}, which they may still enter the workspace of; refused as no
   * chat at all when there is none or it is another person's, so an id says nothing of a chat that
   * is not the caller's.
   */
  private ChatStore.Chat yours(String session) {
    try {
      Ids.requireUuid(session);
    } catch (IllegalArgumentException malformed) {
      throw new IllegalArgumentException(
          "'" + session + "' is not a chat's id: use the session its open answered.", malformed);
    }
    var opener = fdes.byHandle(Actor.current().handle()).map(FdeStore.Fde::id).orElse(null);
    var chat =
        store
            .find(session)
            .filter(found -> found.opener().equals(opener))
            .orElseThrow(() -> unknown(session));
    admit(() -> doors.admitWorkspace(chat.project(), chat.workspace(), person()));
    return chat;
  }

  private static ApiException unknown(String session) {
    return new ApiException(ErrorCode.NOT_FOUND, "There is no chat " + session + " of yours here.");
  }

  /**
   * The FDE id of the caller, whose chat it opens: on the roster, as the person an agent start
   * admitted in their own workspace is.
   */
  private String openerId() {
    return fdes.byHandle(Actor.current().handle()).map(FdeStore.Fde::id).orElseThrow();
  }

  private static HarnessChat form(Harness harness) {
    return harness
        .chat()
        .orElseThrow(
            () ->
                new IllegalArgumentException(
                    harness.displayName() + " has no chat form; start it in a terminal."));
  }

  private static PtyMessage.Agent agent(String harness, String mode, boolean inside) {
    return new PtyMessage.Agent(harness, mode, inside);
  }

  private static PtyIdentity.Person person() {
    return new PtyIdentity.Person(Actor.current());
  }

  private Object lock(String session) {
    return locks.computeIfAbsent(session, id -> new Object());
  }

  private void arrived() {
    synchronized (arrivals) {
      arrivals.notifyAll();
    }
  }

  private static Map<String, Object> wire(ChatStore.Item item) {
    var wire = new LinkedHashMap<String, Object>();
    wire.put("seq", item.seq());
    wire.put("at", DateTimeUtils.format(item.at()));
    wire.put(ChatWire.TYPE, item.kind());
    wire.putAll(YamlUtil.parseJsonLine(item.payload(), LiveStream.MAX_LINE_CHARS));
    return wire;
  }

  /** A door of the pty host's, asked as a route asks: what it refuses, refused in its words. */
  @FunctionalInterface
  private interface Door<T> {
    T ask() throws IOException;
  }

  @FunctionalInterface
  private interface Act {
    void run() throws IOException;
  }

  private static <T> T door(Door<T> door) {
    try {
      return door.ask();
    } catch (IOException refused) {
      throw refusal(refused);
    }
  }

  private static void admit(Act act) {
    door(
        () -> {
          act.run();
          return null;
        });
  }

  /** {@code refused} as the API says it: its own code when it names one, its words as they are. */
  private static ApiException refusal(IOException refused) {
    return switch (refused) {
      case PtyAdmission.Absent absent -> ApiException.of(ErrorCode.NOT_FOUND, absent);
      case PtyAdmission.Unavailable unavailable ->
          ApiException.of(ErrorCode.CONTAINER_ERROR, unavailable);
      case Remedied remedied when Strings.isNotBlank(remedied.code()) ->
          ApiException.of(ErrorCode.valueOf(remedied.code().toUpperCase(Locale.ROOT)), refused);
      default -> ApiException.of(ErrorCode.FORBIDDEN, refused);
    };
  }
}
