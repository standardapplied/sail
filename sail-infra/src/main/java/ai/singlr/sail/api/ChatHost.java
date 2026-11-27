/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.api;

import ai.singlr.sail.common.Ids;
import ai.singlr.sail.common.Strings;
import ai.singlr.sail.engine.ChatWire;
import ai.singlr.sail.store.ChatStore;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The chats this box holds: a person talks to an agent in their own workspace, the agent run as one
 * process over pipes ({@link AgentProcess}) that holds the chat's conversation, and every item of
 * the chat is logged ({@link ChatStore}) before anyone reads it. A chat is started as a pty agent
 * is, through the same doors ({@link HostPty#admitAgent}, {@link HostPty#handOver}), and every
 * route to it is its opener's alone, decided as entering its workspace is. At most one process
 * holds a chat's conversation: only a message, or one left waiting, starts one, under the chat's
 * lock, and a process started before that is not known gone is halted first and must be proved
 * gone. It ends after the project's {@code max_idle} with no turn open, and the next message
 * resumes the conversation by id, in the mode in force; a message left waiting when a process ends,
 * or when sail restarts, is delivered by the next. A request the agent makes is logged and held;
 * nothing here answers one, so after {@link ChatTiming#requestBound} its turn is stopped, said in
 * plain words.
 */
public final class ChatHost implements AutoCloseable {

  /** What a read from no item's seq is told. */
  static final String AFTER = "after is the seq of the last item read, 0 for none.";

  /** The most items one read answers. */
  static final int PAGE = 500;

  /** The most characters of items one read answers, beyond its first item. */
  static final int PAGE_CHARS = 1 << 20;

  /** How long a chat's log is kept once it ended. */
  static final Duration RETENTION = Duration.ofDays(30);

  /** What a turn that sail stopping cut says. */
  static final String STOPPED =
      "Sail stopped while this turn was open, so it was cut; your next message starts it again.";

  /** What a turn that a restart of sail cut says. */
  static final String RESTARTED =
      "Sail restarted while this turn was open, so it was cut; your next message starts it again.";

  private static final String MESSAGE = ChatWire.kind(ChatWire.Message.class);

  /** A chat opened: its session id. */
  record Opened(String session) implements Mappable {
    @Override
    public Map<String, Object> toMap() {
      return Map.of("session", session);
    }
  }

  /**
   * What a read answers: the items, oldest first, and whether the chat has ended, said only with
   * its last item: a reader that stops there has everything.
   */
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
  private final ChatTiming timing;
  private final Map<String, ChatProcess> live = new ConcurrentHashMap<>();
  private final Map<String, Object> locks = new ConcurrentHashMap<>();
  private final Map<String, AtomicLong> arrivals = new ConcurrentHashMap<>();
  private final PeriodicPass sweepPass = new PeriodicPass("chats", this::sweep);
  private volatile boolean closing;

  /**
   * The chats {@code store} holds, admitted and started through {@code launch}, on {@code timing}.
   */
  ChatHost(ChatStore store, ChatLaunch launch, ChatTiming timing) {
    this.store = Objects.requireNonNull(store, "store");
    this.launch = Objects.requireNonNull(launch, "launch");
    this.timing = Objects.requireNonNull(timing, "timing");
  }

  /**
   * Takes up the processes a sail before this one left ({@link #reclaim}), then holds every chat to
   * its bounds at {@code interval}. Returns how many turns the restart cut. The messages they left
   * waiting are delivered once sail serves ({@link #deliverWaiting}).
   */
  public int start(Duration interval) {
    var cut = reclaim();
    sweepPass.start(interval);
    return cut;
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
            false,
            0,
            timing.clock().get(),
            null));
    return new Opened(session);
  }

  /**
   * The items of the caller's chat {@code session} after {@code after}: at once when there are any,
   * else once one is logged or {@link ChatTiming#poll} passes, whichever is first; at most {@link
   * #PAGE} of them and, past the first, {@link #PAGE_CHARS}. It says the chat ended only once its
   * process is known gone, with the last of its items. Never starts a process.
   */
  public Read read(String session, long after) {
    if (after < 0) {
      throw new IllegalArgumentException(AFTER);
    }
    yours(session);
    var deadline = timing.clock().get().plus(timing.poll());
    var arrived = arrivals(session);
    while (true) {
      var seen = arrived.get();
      var ended = store.find(session).map(chat -> chat.ended() && !chat.launched()).orElse(true);
      var items = store.after(session, after, PAGE);
      var page = page(items);
      var now = timing.clock().get();
      if (!page.isEmpty() || ended || !now.isBefore(deadline)) {
        return new Read(
            page.stream().map(ChatLog::wire).toList(),
            ended && page.size() == items.size() && items.size() < PAGE);
      }
      try {
        awaitArrival(arrived, seen, Duration.between(now, deadline));
      } catch (InterruptedException interrupted) {
        Thread.currentThread().interrupt();
        return new Read(List.of(), false);
      }
    }
  }

  /**
   * Waits up to {@code within} for an entry of the chat {@code arrived} counts, unless one arrived
   * since it counted {@code seen}. The log is read outside this wait, so a write that wakes it
   * never waits on a reader.
   */
  private void awaitArrival(AtomicLong arrived, long seen, Duration within)
      throws InterruptedException {
    synchronized (arrived) {
      if (arrived.get() == seen) {
        timing.patience().await(arrived, within);
      }
    }
  }

  /** The first of {@code items}, and as many after it as fit {@link #PAGE_CHARS}. */
  private static List<ChatStore.Item> page(List<ChatStore.Item> items) {
    var page = new ArrayList<ChatStore.Item>();
    var left = PAGE_CHARS;
    for (var item : items) {
      if (!page.isEmpty() && item.payload().length() > left) {
        break;
      }
      page.add(item);
      left -= item.payload().length();
    }
    return page;
  }

  /**
   * Sends {@code text} to the caller's chat {@code session}: logged, then handed to the process
   * that holds its conversation, started when none does; held, and said to be, while a turn is open
   * or the process is ending.
   */
  public Sent send(String session, String text) {
    Strings.requireNonBlank(text, "text");
    var chat = yours(session);
    synchronized (lock(session)) {
      if (ended(session)) {
        throw ended(chat);
      }
      var process = live.get(session);
      if (process == null || process.end().over()) {
        process = startProcess(store.find(session).orElseThrow());
      }
      return process.send(text, ended(chat));
    }
  }

  private boolean ended(String session) {
    return store.find(session).map(ChatStore.Chat::ended).orElse(true);
  }

  private static ApiException ended(ChatStore.Chat chat) {
    return new ApiException(
        ErrorCode.CONFLICT,
        "This chat has ended, so it takes no message.",
        "Open a new chat in workspace " + chat.workspace() + ".");
  }

  /**
   * Ends the caller's chat {@code session}: it is marked ended, and its process, if one holds it,
   * ended; its log stays readable for {@link #RETENTION}, and its folder in the workspace goes once
   * its process is known gone, or now when none holds it nor may. It is marked under the chat's
   * lock, so a process starting for it is found and ended; the end is waited for outside it.
   */
  public void end(String session) {
    yours(session);
    ChatProcess process;
    synchronized (lock(session)) {
      store.ended(session, timing.clock().get());
      process = live.get(session);
      if (process == null) {
        store.find(session).filter(chat -> !chat.launched()).ifPresent(launch::forget);
      }
    }
    arrived(session);
    if (process != null) {
      process.end().endChat(timing.grace().afterInt());
    }
  }

  /**
   * Takes up what a sail before this one left: each process started for a chat that no process of
   * this host holds and that is not known gone is halted and proved gone, each chat on a thread of
   * its own, and a turn it held cut, said so; one its workspace does not answer for is asked again
   * by the sweep, and before the chat's next start. It is safe at any time: a chat's process is
   * looked for under its lock. Returns how many turns it cut.
   */
  int reclaim() {
    var cut = new AtomicInteger();
    try (var each = Executors.newVirtualThreadPerTaskExecutor()) {
      for (var session : store.launched()) {
        each.execute(
            () -> {
              if (settled(session)) {
                cut.incrementAndGet();
              }
            });
      }
    }
    return cut.get();
  }

  /**
   * Delivers each chat's messages that a sail before this one left waiting, by a process started as
   * its opener, each chat on a thread of its own, so one that waits on its workspace holds up no
   * other.
   */
  public void deliverWaiting() {
    try (var each = Executors.newVirtualThreadPerTaskExecutor()) {
      for (var session : store.queued(MESSAGE)) {
        each.execute(
            () -> {
              synchronized (lock(session)) {
                resumeQueued(session);
              }
            });
      }
    }
  }

  /** Erases the chats of workspace {@code workspace} of {@code project}, which was deleted. */
  public void forgetWorkspace(String project, String workspace) {
    store.eraseWorkspace(project, workspace);
  }

  /** Erases every chat of {@code project}, whose workspaces were destroyed. */
  public void forgetProject(String project) {
    store.eraseProject(project);
  }

  /**
   * One pass: erases the chats that ended more than {@link #RETENTION} ago, settles each process
   * left over, and holds each live one to its bounds, each on a thread of its own: one whose chat
   * was erased is ended, one whose turn holds a request unanswered past {@link
   * ChatTiming#requestBound} has its turn stopped, and ended if it does not stop, and one idle past
   * its project's {@code max_idle} is ended.
   */
  void sweep() {
    var now = timing.clock().get();
    store.expire(now.minus(RETENTION));
    reclaim();
    try (var each = Executors.newVirtualThreadPerTaskExecutor()) {
      for (var process : List.copyOf(live.values())) {
        each.execute(() -> holdToBounds(process, now));
      }
    }
  }

  private void holdToBounds(ChatProcess process, Instant now) {
    if (store.find(process.session()).isEmpty()) {
      process.end().end(timing.grace().afterInt(), null);
    } else if (process.overBound(now, timing.requestBound())) {
      process
          .end()
          .end(
              timing.grace().afterInt(),
              process.agent()
                  + "'s turn could not be stopped, so Sail ended its process; your next message"
                  + " starts it again.");
    } else if (process.endIfIdle(now)) {
      Thread.ofVirtual().name("sail-chat-idle-" + process.session()).start(process::awaitIdleEnd);
    }
  }

  /**
   * Ends every process: sail is stopping. The chats stay open, and resume on their next message.
   */
  @Override
  public void close() {
    closing = true;
    sweepPass.close();
    try (var ending = Executors.newVirtualThreadPerTaskExecutor()) {
      live.values()
          .forEach(
              process -> ending.execute(() -> process.end().end(timing.grace().result(), STOPPED)));
    }
  }

  /**
   * Under the chat's lock, halts the process started for chat {@code session} that no process of
   * this host holds, and once it is proved gone, cuts the turn it held; returns whether it cut one.
   */
  private boolean settled(String session) {
    synchronized (lock(session)) {
      var chat = store.find(session).orElse(null);
      if (chat == null || !chat.launched() || live.containsKey(session) || !launch.halted(chat)) {
        return false;
      }
      log(session).cut(RESTARTED);
      released(session);
      return chat.turnOpen();
    }
  }

  /**
   * Starts the process that holds {@code chat}'s conversation, and reads it: a turn a process
   * before it left open, proved gone in the start, is cut first. Called under the chat's lock.
   */
  private ChatProcess startProcess(ChatStore.Chat chat) {
    var session = chat.id();
    var started = launch.start(chat, launched -> store.launched(session, launched));
    var log = log(session);
    log.cut(RESTARTED);
    var process =
        new ChatProcess(
            session,
            started.agent(),
            log,
            started.form(),
            started.handle(),
            timing,
            started.maxIdle(),
            ended -> gone(ended, started.withdraw()));
    live.put(session, process);
    process.read();
    return process;
  }

  /**
   * {@code ended}'s process is over: its login file withdrawn, the chat released when its exit is
   * known, and, unless sail is stopping, messages left waiting delivered by the next process, if it
   * took any; one that took none is not started again for them, which is said.
   */
  private void gone(ChatProcess ended, Runnable withdraw) {
    Thread.ofVirtual().name("sail-chat-withdraw").start(withdraw);
    var session = ended.session();
    synchronized (lock(session)) {
      live.remove(session, ended);
      if (ended.end().known()) {
        released(session);
      }
      if (closing) {
        return;
      }
      if (ended.took()) {
        resumeQueued(session);
      } else if (!ended(session) && !store.unhanded(session, MESSAGE).isEmpty()) {
        log(session)
            .log(
                new ChatWire.Told(
                    ended.agent()
                        + "'s process ended before it took your message; it waits for your next"
                        + " one."));
      }
    }
  }

  /**
   * Delivers chat {@code session}'s messages left waiting, when it is open and has any: a process
   * is started for it as its opener, admitted as any start is, and handed the oldest, the rest as
   * each turn ends. One that is refused now is said in the refusal's words, and its messages keep
   * waiting. Called under the chat's lock.
   */
  private void resumeQueued(String session) {
    var chat = store.find(session).filter(found -> !found.ended()).orElse(null);
    if (chat == null || store.unhanded(session, MESSAGE).isEmpty()) {
      return;
    }
    try {
      launch.asOpener(chat, () -> startProcess(chat)).resume();
    } catch (ApiException | IllegalArgumentException refused) {
      log(session)
          .log(
              new ChatWire.Told(
                  refused.getMessage()
                      + " Your waiting message was not sent; it goes with your next one."));
    }
  }

  /** Chat {@code session}'s process is known gone: an ended chat's folder goes with it. */
  private void released(String session) {
    store.launched(session, false);
    store.find(session).filter(ChatStore.Chat::ended).ifPresent(launch::forget);
    arrived(session);
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
    return store
        .find(session)
        .filter(launch::opens)
        .orElseThrow(
            () ->
                new ApiException(
                    ErrorCode.NOT_FOUND, "There is no chat " + session + " of yours here."));
  }

  private ChatLog log(String session) {
    return new ChatLog(store, session, timing, () -> arrived(session));
  }

  private Object lock(String session) {
    return locks.computeIfAbsent(session, id -> new Object());
  }

  private AtomicLong arrivals(String session) {
    return arrivals.computeIfAbsent(session, id -> new AtomicLong());
  }

  private void arrived(String session) {
    var arrived = arrivals(session);
    synchronized (arrived) {
      arrived.incrementAndGet();
      arrived.notifyAll();
    }
  }
}
