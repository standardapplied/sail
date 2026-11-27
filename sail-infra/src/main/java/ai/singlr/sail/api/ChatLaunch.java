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
import ai.singlr.sail.engine.ContainerExec;
import ai.singlr.sail.engine.LaunchEnv;
import ai.singlr.sail.engine.SessionLogin;
import ai.singlr.sail.harness.Harness;
import ai.singlr.sail.harness.HarnessChat;
import ai.singlr.sail.harness.Harnesses;
import ai.singlr.sail.identity.Actor;
import ai.singlr.sail.pty.PtyAdmission;
import ai.singlr.sail.pty.PtyIdentity;
import ai.singlr.sail.pty.PtyMessage;
import ai.singlr.sail.store.ChatStore;
import ai.singlr.sail.store.FdeStore;
import java.io.IOException;
import java.time.Duration;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * How a chat is admitted and its process started, through the pty host's own doors ({@link
 * HostPty#admitWorkspace}, {@link HostPty#admitAgent}, {@link HostPty#enterWorkspace}, {@link
 * HostPty#handOver}, {@link HostPty#withdraw}), so a chat is refused exactly as a pty agent start
 * is, in its words, and signed in as it is: its login handed over as a file its launch reads and
 * removes, never on a command line.
 */
final class ChatLaunch {

  /** Where a chat's process runs, and the only folder its attachments are read from. */
  static final String WORKSPACE = ContainerExec.DEV_WORKSPACE;

  private static final String CHATS = ContainerExec.DEV_HOME + "/.sail/chats/";

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

  ChatLaunch(HostPty doors, FdeStore fdes, ProjectReader definitions, AgentProcess processes) {
    this.doors = Objects.requireNonNull(doors, "doors");
    this.fdes = Objects.requireNonNull(fdes, "fdes");
    this.definitions = Objects.requireNonNull(definitions, "definitions");
    this.processes = Objects.requireNonNull(processes, "processes");
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
   * Starts the process for {@code chat}, as its opener: admitted and signed in as a pty agent start
   * is, its workspace entered (started when stopped), resuming the conversation the harness last
   * named, in the mode it last said.
   */
  Started start(ChatStore.Chat chat) {
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
    var launch = Ids.newId().toString();
    var file = SessionLogin.file(launch);
    var instance = project + "-" + workspace;
    act(
        () ->
            doors.handOver(
                project, workspace, file, login.map(LaunchEnv::loginLines).orElse(""), person));
    try {
      return new Started(
          processes.start(
              instance,
              WORKSPACE,
              Map.of(),
              form.argv(new HarnessChat.Launch(chat.mode(), null, chat.conversation())),
              CHATS + launch + "/agent.pid",
              CHATS + launch + "/agent.log",
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
