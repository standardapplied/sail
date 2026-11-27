/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.api;

import ai.singlr.sail.common.Ids;
import ai.singlr.sail.common.Remedied;
import ai.singlr.sail.common.Strings;
import ai.singlr.sail.config.Guardrails;
import ai.singlr.sail.config.Lane;
import ai.singlr.sail.engine.AgentProcess;
import ai.singlr.sail.engine.AgentSession;
import ai.singlr.sail.engine.AgentUnit;
import ai.singlr.sail.engine.ContainerExec;
import ai.singlr.sail.engine.LaunchEnv;
import ai.singlr.sail.engine.SessionLogin;
import ai.singlr.sail.engine.ShellExec;
import ai.singlr.sail.harness.Harness;
import ai.singlr.sail.harness.HarnessChat;
import ai.singlr.sail.harness.Harnesses;
import ai.singlr.sail.identity.Actor;
import ai.singlr.sail.identity.Role;
import ai.singlr.sail.pty.PtyAdmission;
import ai.singlr.sail.pty.PtyIdentity;
import ai.singlr.sail.pty.PtyMessage;
import ai.singlr.sail.store.ChatStore;
import ai.singlr.sail.store.FdeStore;
import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

/**
 * How a chat is admitted and its process started, through the pty host's own doors ({@link
 * HostPty#admitWorkspace}, {@link HostPty#admitAgent}, {@link HostPty#enterWorkspace}, {@link
 * HostPty#handOver}, {@link HostPty#withdraw}), so a chat is refused exactly as a pty agent start
 * is, in its words, and signed in as it is: its login handed over as a file its launch reads and
 * removes, never on a command line.
 */
final class ChatLaunch {

  /** Where a chat's process runs. */
  static final String WORKSPACE = ContainerExec.DEV_WORKSPACE;

  /**
   * A process started for a chat: its {@code handle}, the chat form it speaks ({@code form}), its
   * harness's name as a person reads it, the idle while it ends after, and what withdraws its login
   * file once it is gone.
   */
  record Started(
      AgentProcess.Handle handle,
      HarnessChat form,
      String agent,
      Duration maxIdle,
      Runnable withdraw) {}

  private final HostPty doors;
  private final FdeStore fdes;
  private final ProjectReader definitions;
  private final AgentProcess processes;
  private final ShellExec shell;

  /**
   * Admits through {@code doors}, as the FDEs {@code fdes} holds, chats of the projects {@code
   * definitions} reads, started as {@code processes}; a process left over is asked after through
   * {@code shell}.
   */
  ChatLaunch(
      HostPty doors,
      FdeStore fdes,
      ProjectReader definitions,
      AgentProcess processes,
      ShellExec shell) {
    this.doors = Objects.requireNonNull(doors, "doors");
    this.fdes = Objects.requireNonNull(fdes, "fdes");
    this.definitions = Objects.requireNonNull(definitions, "definitions");
    this.processes = Objects.requireNonNull(processes, "processes");
    this.shell = Objects.requireNonNull(shell, "shell");
  }

  /**
   * Admits the caller's chat with {@code harness} in {@code mode} in their workspace {@code
   * workspace} of {@code project}, signed in with their stored login or, {@code inside}, the
   * harness's own there: only a harness with a chat form, in one of its chat modes, and as a pty
   * agent start of it is admitted. Returns the caller's FDE id, the chat's opener.
   */
  String admit(String project, String workspace, String harness, String mode, boolean inside) {
    var agent = Harnesses.of(Strings.requireNonBlank(harness, "harness"));
    Harness.Mode.named(form(agent).modes(), mode, agent.displayName());
    var person = person();
    act(() -> doors.admitWorkspace(project, workspace, person));
    ask(
        () ->
            doors.admitAgent(
                project, workspace, new PtyMessage.Agent(harness, mode, inside), person));
    return fdes.byHandle(person.actor().handle()).map(FdeStore.Fde::id).orElseThrow();
  }

  /**
   * Whether {@code chat} is the caller's, its opener's; when it is, the caller must still be one
   * who may enter its workspace, or is refused as the door refuses.
   */
  boolean opens(ChatStore.Chat chat) {
    var caller = fdes.byHandle(Actor.current().handle()).map(FdeStore.Fde::id);
    if (caller.filter(chat.opener()::equals).isEmpty()) {
      return false;
    }
    act(() -> doors.admitWorkspace(chat.project(), chat.workspace(), person()));
    return true;
  }

  /**
   * {@code work} done as {@code chat}'s opener, as a route of theirs is: no one is there to ask.
   */
  <T> T asOpener(ChatStore.Chat chat, Supplier<T> work) {
    var opener =
        fdes.byId(chat.opener())
            .orElseThrow(() -> new IllegalStateException("Chat " + chat.id() + " has no opener."));
    return Actor.call(
        new Actor(opener.handle(), Role.fromAttribute(opener.role()), Actor.Lane.API), work::get);
  }

  /**
   * Starts the process for {@code chat}, as its opener: admitted and signed in as a pty agent start
   * is, its workspace entered (started when stopped), resuming the conversation the harness last
   * named, in the mode it last said. A process started for it before that is not known gone is
   * halted first ({@link #halted}), and refused while it is not proved gone: one conversation, one
   * process. {@code launching} runs as the process is about to start.
   */
  Started start(ChatStore.Chat chat, Runnable launching) {
    var harness = Harnesses.of(chat.harness());
    var form = form(harness);
    var person = person();
    var project = chat.project();
    var workspace = chat.workspace();
    var login =
        ask(
            () ->
                doors.admitAgent(
                    project,
                    workspace,
                    new PtyMessage.Agent(chat.harness(), chat.mode(), chat.inside()),
                    person));
    act(() -> doors.enterWorkspace(project, workspace, person));
    if (chat.launched() && !halted(chat)) {
      throw new ApiException(
          ErrorCode.AGENT_ALREADY_RUNNING,
          harness.displayName()
              + "'s earlier process for this chat in workspace "
              + workspace
              + " has not ended yet, so the message was not sent.",
          "Send it again in a minute: Sail ends that process first.");
    }
    var file = SessionLogin.file(Ids.newId().toString());
    var instance = instance(chat);
    var unit = AgentUnit.chat(chat.id());
    act(
        () ->
            doors.handOver(
                project, workspace, file, login.map(LaunchEnv::loginLines).orElse(""), person));
    try {
      launching.run();
      return new Started(
          processes.start(
              instance,
              WORKSPACE,
              Map.of(),
              form.argv(new HarnessChat.Launch(chat.mode(), null, chat.conversation())),
              unit.pidPath(),
              unit.logPath(),
              file),
          form,
          harness.displayName(),
          maxIdle(project),
          () -> doors.withdraw(instance, file));
    } catch (IOException | RuntimeException failed) {
      doors.withdraw(instance, file);
      throw new ApiException(
          ErrorCode.AGENT_LAUNCH_FAILED,
          harness.displayName() + " did not start in workspace " + workspace + ".",
          "Check that the workspace is running and answers, then send the message again.",
          failed);
    }
  }

  /**
   * Halts the process started for {@code chat} that no pipe of this sail reaches, through the pid
   * file in the chat's own folder, as a live member's left from before a restart is; whether it is
   * known gone. A workspace that does not answer proves nothing.
   */
  boolean halted(ChatStore.Chat chat) {
    var instance = instance(chat);
    var unit = AgentUnit.chat(chat.id());
    return ProcessEnd.halted(
        shell,
        instance,
        unit,
        () ->
            new AgentSession(shell).presence(instance, unit) instanceof AgentSession.Presence.Gone);
  }

  /** Removes {@code chat}'s folder in its workspace, its process gone: nothing reads it again. */
  void forget(ChatStore.Chat chat) {
    try {
      shell.exec(
          ContainerExec.asDevUser(
              instance(chat), List.of("rm", "-rf", "--", AgentUnit.chatDir(chat.id()))));
    } catch (IOException | TimeoutException unanswered) {
      System.err.println(
          "  [chat] the folder of chat " + chat.id() + " stays: " + unanswered.getMessage());
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
    }
  }

  private static String instance(ChatStore.Chat chat) {
    return chat.project() + "-" + chat.workspace();
  }

  /** The project's {@code max_idle}, which a chat's process ends after with no turn open. */
  private Duration maxIdle(String project) {
    var idle = definitions.require(project).guardrailsFor(Lane.ROOM).maxIdle();
    return Objects.requireNonNullElse(
        Guardrails.parseDuration(idle), Guardrails.parseDuration(Guardrails.defaults().maxIdle()));
  }

  private static HarnessChat form(Harness harness) {
    return harness
        .chat()
        .orElseThrow(
            () ->
                new IllegalArgumentException(
                    harness.displayName() + " has no chat form; start it in a terminal."));
  }

  private static PtyIdentity.Person person() {
    return new PtyIdentity.Person(Actor.current());
  }

  /** A door of the pty host's that answers, asked as a route asks. */
  @FunctionalInterface
  private interface Door<T> {
    T ask() throws IOException;
  }

  /** A door of the pty host's that only admits, asked as a route asks. */
  @FunctionalInterface
  private interface Act {
    void run() throws IOException;
  }

  private static <T> T ask(Door<T> door) {
    try {
      return door.ask();
    } catch (IOException refused) {
      throw refusal(refused);
    }
  }

  private static void act(Act act) {
    ask(
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
