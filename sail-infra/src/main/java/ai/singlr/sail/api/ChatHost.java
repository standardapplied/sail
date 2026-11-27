/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.api;

import ai.singlr.sail.common.Ids;
import ai.singlr.sail.common.Strings;
import ai.singlr.sail.engine.LiveStream;
import ai.singlr.sail.store.ChatStore;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;

/**
 * The chats this box holds: a person talks to an agent in their own workspace, the agent run as one
 * process over pipes ({@link AgentProcess}) that holds the chat's conversation, and every item of
 * the chat is logged ({@link ChatStore}) before anyone reads it. A chat is started as a pty agent
 * is, through the same doors ({@link HostPty#admitAgent}, {@link HostPty#handOver}), and every
 * route to it is its opener's alone, decided as entering its workspace is. At most one process
 * holds a chat's conversation, and only a message starts one: it ends after the project's {@code
 * max_idle} with no turn open, and the next message resumes the conversation by id, in the mode in
 * force. A request the agent makes is logged and held; nothing here answers one, so after {@link
 * ChatTiming#requestBound} the process is ended and the request withdrawn, said in plain words.
 */
public final class ChatHost implements AutoCloseable {

  /** What a read from no item's seq is told. */
  static final String AFTER = "after is the seq of the last item read, 0 for none.";

  /** The most items one read answers. */
  static final int PAGE = 500;

  /** How long a chat's log is kept once it ended. */
  static final Duration RETENTION = Duration.ofDays(30);

  /** What a turn that sail stopping cut says. */
  static final String STOPPED =
      "Sail stopped while this turn was open, so it was cut; your next message starts it again.";

  /** What a turn that a restart of sail cut says. */
  static final String RESTARTED =
      "Sail restarted while this turn was open, so it was cut; your next message starts it again.";

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
  private final ChatLaunch launch;
  private final ChatAttachments attachments;
  private final ChatTiming timing;
  private final Map<String, ChatProcess> live = new ConcurrentHashMap<>();
  private final Map<String, Object> locks = new ConcurrentHashMap<>();
  private final Object arrivals = new Object();
  private final PeriodicPass sweepPass = new PeriodicPass("chats", this::sweep);

  /**
   * The chats {@code store} holds, admitted and started through {@code launch}, their images read
   * through {@code attachments}, on {@code timing}.
   */
  ChatHost(ChatStore store, ChatLaunch launch, ChatAttachments attachments, ChatTiming timing) {
    this.store = Objects.requireNonNull(store, "store");
    this.launch = Objects.requireNonNull(launch, "launch");
    this.attachments = Objects.requireNonNull(attachments, "attachments");
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
    var opener = launch.admit(project, workspace, harness, mode, inside);
    var session = Ids.newId().toString();
    store.open(
        new ChatStore.Chat(
            session,
            opener,
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
   * else once one is logged or {@link ChatTiming#poll} passes, whichever is first. Never starts a
   * process.
   */
  public Read read(String session, long after) {
    if (after < 0) {
      throw new IllegalArgumentException(AFTER);
    }
    yours(session);
    var deadline = timing.clock().get().plus(timing.poll());
    synchronized (arrivals) {
      while (true) {
        var items = store.after(session, after, PAGE);
        var ended = store.find(session).map(ChatStore.Chat::ended).orElse(true);
        var now = timing.clock().get();
        if (!items.isEmpty() || ended || !now.isBefore(deadline)) {
          return new Read(items.stream().map(ChatLog::wire).toList(), ended);
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
   * Sends {@code text}, with the workspace images {@code paths} names, to the caller's chat {@code
   * session}: logged, then handed to the process that holds its conversation, started when none
   * does; held, and said to be, while a turn is open.
   */
  public Sent send(String session, String text, List<String> paths) {
    Strings.requireNonBlank(text, "text");
    ChatAttachments.requireFew(paths);
    var chat = yours(session);
    synchronized (lock(session)) {
      chat = store.find(session).orElseThrow(() -> unknown(session));
      if (chat.ended()) {
        throw ended(chat);
      }
      var images = attachments.images(chat, paths);
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
      return process.send(text, List.copyOf(paths), images);
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
    cut.forEach(session -> new ChatLog(store, session, timing, this::arrived).cut(RESTARTED));
    return cut.size();
  }

  /** Erases the chats of workspace {@code workspace} of {@code project}, which was deleted. */
  public void forgetWorkspace(String project, String workspace) {
    store.eraseWorkspace(project, workspace);
  }

  /** Erases every chat of {@code project}, whose workspaces were destroyed. */
  public void forgetProject(String project) {
    store.eraseProject(project);
  }

  /** Holds every chat to its bounds at {@code interval}. */
  public void startSweep(Duration interval) {
    sweepPass.start(interval);
  }

  /**
   * One pass: ends each process idle past its project's {@code max_idle}, and each that held a
   * request unanswered past {@link ChatTiming#requestBound}, saying so; erases the chats that ended
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

  /** Starts the process that holds {@code chat}'s conversation, and reads it. */
  private ChatProcess start(ChatStore.Chat chat) {
    var started = launch.start(chat);
    var process =
        new ChatProcess(
            chat.id(),
            started.agent(),
            new ChatLog(store, chat.id(), timing, this::arrived),
            started.form(),
            started.handle(),
            timing,
            started.maxIdle(),
            new ChatProcess.Host() {
              @Override
              public List<LiveStream.Image> images(List<String> paths) {
                return attachments.images(chat, paths);
              }

              @Override
              public void gone(ChatProcess ended) {
                live.remove(ended.session(), ended);
                Thread.ofVirtual().name("sail-chat-withdraw").start(started.withdraw());
              }
            });
    live.put(chat.id(), process);
    process.read();
    return process;
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
    return store.find(session).filter(launch::opens).orElseThrow(() -> unknown(session));
  }

  private static ApiException unknown(String session) {
    return new ApiException(ErrorCode.NOT_FOUND, "There is no chat " + session + " of yours here.");
  }

  private Object lock(String session) {
    return locks.computeIfAbsent(session, id -> new Object());
  }

  private void arrived() {
    synchronized (arrivals) {
      arrivals.notifyAll();
    }
  }
}
