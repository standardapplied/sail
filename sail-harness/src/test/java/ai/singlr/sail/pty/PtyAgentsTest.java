/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.pty;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.sail.common.Remedied;
import ai.singlr.sail.common.Remedy;
import ai.singlr.sail.engine.ContainerExec;
import ai.singlr.sail.engine.LaunchEnv;
import ai.singlr.sail.engine.SessionLogin;
import ai.singlr.sail.harness.Harnesses;
import ai.singlr.sail.identity.Actor;
import ai.singlr.sail.identity.Role;
import ai.singlr.sail.store.LoginStore;
import java.io.IOException;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

/**
 * An agent session through a real host whose children record what they were started with: the host
 * composes the command, asks whose login before the workspace is entered, hands the login over
 * before the session starts and withdraws it when the session does not, and keeps the login off
 * every line and record it writes. Whose login it is, is {@code HostPtyTest}'s; what the
 * workspace's shell does with it, {@code SessionLoginTest}'s.
 */
@EnabledOnOs(OS.LINUX)
@Timeout(value = 60, unit = TimeUnit.SECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
class PtyAgentsTest {

  private static final String VALUE = "fake-key-agents-test-value";
  private static final LoginStore.Login LOGIN =
      new LoginStore.Login(
          new LoginStore.Row("claude-code", "work", "api-key", true, Instant.EPOCH, "0123abcd"),
          Harnesses.of("claude-code").loginKind("api-key"),
          VALUE);

  @TempDir Path dir;

  private final List<String> calls = new CopyOnWriteArrayList<>();
  private final List<List<String>> handed = new CopyOnWriteArrayList<>();
  private final BlockingQueue<List<String>> withdrawn = new LinkedBlockingQueue<>();
  private volatile Optional<LoginStore.Login> login = Optional.of(LOGIN);
  private volatile IOException refusal;
  private volatile IOException handOverFails;
  private volatile Runnable duringHandOver = () -> {};

  private final PtyAdmission admission =
      new PtyAdmission() {
        @Override
        public void box(PtyIdentity.Person who) {}

        @Override
        public void project(String project, PtyIdentity.Person who) {}

        @Override
        public void room(String room, String project, PtyIdentity.Person who) {}

        @Override
        public void workspace(String project, String workspace, PtyIdentity.Person who) {}

        @Override
        public void enter(String project, String workspace, PtyIdentity.Person who) {
          calls.add("enter " + project + " " + workspace);
        }

        @Override
        public Optional<LoginStore.Login> agent(
            String project, String workspace, PtyMessage.Agent agent, PtyIdentity.Person who)
            throws IOException {
          calls.add("agent " + agent.harness() + " for " + who.fde());
          if (refusal != null) {
            throw refusal;
          }
          return agent.inside() ? Optional.empty() : login;
        }

        @Override
        public void handOver(
            String project, String workspace, String file, String lines, PtyIdentity.Person who)
            throws IOException {
          calls.add("hand over");
          handed.add(List.of(project + "-" + workspace, file, lines, who.fde()));
          duringHandOver.run();
          if (handOverFails != null) {
            throw handOverFails;
          }
        }

        @Override
        public void withdraw(String instance, String file) {
          withdrawn.add(List.of(instance, file));
        }
      };

  private Path argv() {
    return dir.resolve("argv");
  }

  private PtySessionHost startHost() throws IOException {
    return startHost(new PtySessionHost.Limits(32, 16, 256, 256));
  }

  private PtySessionHost startHost(PtySessionHost.Limits limits) throws IOException {
    var host =
        new PtySessionHost(
            dir.resolve("host.sock"),
            dir.resolve("sessions"),
            64 * 1024,
            token -> new PtyIdentity.Person(new Actor("uday", Role.MEMBER, Actor.Lane.API)),
            admission,
            PtyEvents.NONE,
            "0.0.0-test",
            limits,
            new PtySessionHost.Handoff(
                ai.singlr.sail.pty.SdNotify.NONE,
                java.util.Map.of(),
                exit ->
                    Stream.concat(
                            PtyChildShim.onClassPath(exit).stream(),
                            Stream.of(
                                "sh",
                                "-c",
                                "printf '%s\\n' \"$@\" > " + argv() + "; exec cat",
                                "container"))
                        .toList()),
            PtySessionHost.Network.NONE);
    host.start();
    return host;
  }

  private SocketChannel connect() throws IOException {
    var channel = SocketChannel.open(StandardProtocolFamily.UNIX);
    channel.connect(UnixDomainSocketAddress.of(dir.resolve("host.sock")));
    PtyWire.handshake(channel, channel);
    PtyWire.write(channel, new PtyMessage.Hello("tok-uday"));
    assertInstanceOf(PtyMessage.Welcome.class, PtyWire.read(channel));
    return channel;
  }

  private static PtyMessage.Create agent(
      String session, List<String> command, String workspace, PtyMessage.Agent agent) {
    return new PtyMessage.Create(session, command, "/tmp", "acme", "", 80, 24, workspace, agent);
  }

  private static PtyMessage ask(SocketChannel channel, PtyMessage message) throws IOException {
    PtyWire.write(channel, message);
    return PtyWire.read(channel);
  }

  private static List<PtyMessage.SessionInfo> listed(SocketChannel channel) throws IOException {
    return ((PtyMessage.Sessions)
            ask(channel, new PtyMessage.ListSessions("", PtyMessage.PAGE_LIMIT)))
        .sessions();
  }

  /** L3 and scenarios 4 and 5: the client names a harness, never its command, nor one it lacks. */
  @Test
  void aClientNamesAHarnessAndAModeItTakesNeverItsCommand() throws Exception {
    try (var ignored = startHost();
        var channel = connect()) {
      var full = new PtyMessage.Agent("claude-code", "bypassPermissions", false);
      assertEquals(
          new PtyMessage.Err("A session runs a harness or a command, not both."),
          ask(channel, agent("s", List.of("claude"), "dock", full)));
      assertEquals(
          new PtyMessage.Err(
              "An agent starts in a workspace of your own; name the workspace to start it in."),
          ask(channel, agent("s", List.of(), "", full)));
      var unknown =
          (PtyMessage.Err)
              ask(
                  channel,
                  agent("s", List.of(), "dock", new PtyMessage.Agent("vim", "full", false)));
      assertTrue(unknown.reason().startsWith("Unknown agent CLI: 'vim'."), unknown.reason());
      assertEquals(
          new PtyMessage.Err(
              "Codex CLI takes no mode 'default' in a workspace; it takes full-access."),
          ask(
              channel,
              agent("s", List.of(), "dock", new PtyMessage.Agent("codex", "default", false))));
      assertEquals(List.of(), calls, "nothing was asked, entered or handed over");
      assertEquals(List.of(), listed(channel));
    }
  }

  /** L6: a refusal is the admission's words and remedy, before the workspace is entered. */
  @Test
  void anAgentRefusedStartsNothingAndLeavesItsWorkspaceAsItWas() throws Exception {
    var remedy = Remedy.of("Store one: ", Remedy.sail("agent login", "codex"), ".");
    refusal =
        Remedied.io(
            "No Codex CLI login stored for uday, so a Codex CLI session cannot start in dock.",
            remedy,
            "login_not_stored");
    try (var ignored = startHost();
        var channel = connect()) {
      assertEquals(
          new PtyMessage.Err(
              "No Codex CLI login stored for uday, so a Codex CLI session cannot start in dock.",
              remedy,
              "login_not_stored"),
          ask(
              channel,
              agent("s", List.of(), "dock", new PtyMessage.Agent("codex", "full-access", false))));
      assertEquals(List.of("agent codex for uday"), calls);
      assertEquals(List.of(), listed(channel));
    }
  }

  /**
   * L1, L4 and scenarios 1 and 2: the person's login is handed over to the workspace after it is
   * entered and before the session starts, and is on no line the host runs and in no record it
   * keeps; the session shows the harness's own command.
   */
  @Test
  void anAgentStartsWithItsPersonsLoginHandedOverAndNothingOfItOnALineOrARecord() throws Exception {
    try (var ignored = startHost();
        var channel = connect()) {
      var full = new PtyMessage.Agent("claude-code", "bypassPermissions", false);

      assertInstanceOf(PtyMessage.Ok.class, ask(channel, agent("s", List.of(), "dock", full)));

      assertEquals(List.of("agent claude-code for uday", "enter acme dock", "hand over"), calls);
      var info = listed(channel).getFirst();
      var file = SessionLogin.file(info.instanceId());
      assertEquals(
          List.of("acme-dock", file, LaunchEnv.loginLines(LOGIN), "uday"), handed.getFirst());
      assertEquals(List.of("claude", "--permission-mode", "bypassPermissions"), info.command());
      assertEquals(full, info.agent());
      var run = awaitArgv();
      assertEquals(
          ContainerExec.asDevUserTty(
              "acme-dock",
              PtySessionHost.childEnv("", "0.0.0-test"),
              SessionLogin.command(
                  Harnesses.of("claude-code"), "bypassPermissions", Optional.of(LOGIN), file)),
          run);
      assertFalse(String.join("\n", run).contains(VALUE), "the login is on no command line");
      try (var files = Files.walk(dir.resolve("sessions"))) {
        for (var kept : files.filter(Files::isRegularFile).toList()) {
          assertFalse(
              new String(Files.readAllBytes(kept), StandardCharsets.ISO_8859_1).contains(VALUE),
              "the login is in none of the session's records: " + kept.getFileName());
        }
      }
    }
  }

  /** Q1: a session that signs in inside is handed no login. */
  @Test
  void anAgentThatSignsInInsideIsHandedNoLogin() throws Exception {
    try (var ignored = startHost();
        var channel = connect()) {
      var inside = new PtyMessage.Agent("claude-code", "default", true);

      assertInstanceOf(PtyMessage.Ok.class, ask(channel, agent("s", List.of(), "dock", inside)));

      assertEquals(List.of("agent claude-code for uday", "enter acme dock"), calls);
      assertEquals(inside, listed(channel).getFirst().agent());
      assertFalse(String.join("\n", awaitArgv()).contains("login.env"));
    }
  }

  /**
   * A login that cannot be handed over starts no session, said in a person's words, and what the
   * hand-over may have left is withdrawn (Minor 1).
   */
  @Test
  void aLoginThatCannotBeHandedOverStartsNoSessionAndIsWithdrawn() throws Exception {
    handOverFails = new IOException("the hand-over ended 1");
    try (var ignored = startHost();
        var channel = connect()) {
      assertEquals(
          new PtyMessage.Err(
              "Could not start session 's': its login could not be handed to workspace dock.",
              PtyAdmission.Unavailable.REMEDY),
          ask(
              channel,
              agent(
                  "s",
                  List.of(),
                  "dock",
                  new PtyMessage.Agent("claude-code", "bypassPermissions", false))));
      assertEquals(List.of(), listed(channel));
      assertEquals(handed.getFirst().subList(0, 2), withdrawn.poll(30, TimeUnit.SECONDS));
    }
  }

  /** Minor 3: a workspace that changed hands while it was entered is said so, in its words. */
  @Test
  void aWorkspaceThatChangedHandsRefusesTheHandOverInItsWords() throws Exception {
    handOverFails =
        Remedied.io(
            "workspace dock of acme changed hands while it was entered.",
            Remedy.of("Start it again in a workspace of your own."));
    try (var ignored = startHost();
        var channel = connect()) {
      assertEquals(
          new PtyMessage.Err(
              "Could not start session 's': workspace dock of acme changed hands while it was"
                  + " entered.",
              Remedy.of("Start it again in a workspace of your own.")),
          ask(
              channel,
              agent(
                  "s",
                  List.of(),
                  "dock",
                  new PtyMessage.Agent("claude-code", "bypassPermissions", false))));
      assertEquals(handed.getFirst().subList(0, 2), withdrawn.poll(30, TimeUnit.SECONDS));
    }
  }

  /**
   * Minor 2: what refuses a create whatever its workspace holds refuses it before the workspace is
   * entered or a login handed over: a live session of its name, the person's session cap.
   */
  @Test
  void aCreateTheHostWouldRefuseEntersNothingAndHandsNothingOver() throws Exception {
    try (var ignored = startHost(new PtySessionHost.Limits(1, 16, 256, 256));
        var channel = connect()) {
      assertInstanceOf(
          PtyMessage.Ok.class,
          ask(channel, new PtyMessage.Create("s", List.of(), "/tmp", "", "", 80, 24)));
      var full = new PtyMessage.Agent("claude-code", "bypassPermissions", false);

      assertEquals(
          new PtyMessage.Err("Session 's' is already running; attach or kill it."),
          ask(channel, agent("s", List.of(), "dock", full)));
      assertEquals(
          new PtyMessage.Err("You are at your session cap of 1; kill one first."),
          ask(channel, agent("t", List.of(), "dock", full)));
      assertEquals(List.of(), calls, "nothing was asked, entered or handed over");
    }
  }

  /**
   * L5: a login handed over for a session the host then refused, here one of its name made while
   * the login was handed over, is withdrawn.
   */
  @Test
  void aLoginHandedOverForASessionThatDidNotStartIsWithdrawn() throws Exception {
    try (var ignored = startHost();
        var channel = connect();
        var other = connect()) {
      duringHandOver =
          () -> {
            try {
              assertInstanceOf(
                  PtyMessage.Ok.class,
                  ask(other, new PtyMessage.Create("s", List.of(), "/tmp", "", "", 80, 24)));
            } catch (IOException e) {
              throw new java.io.UncheckedIOException(e);
            }
          };

      var answer =
          ask(
              channel,
              agent(
                  "s",
                  List.of(),
                  "dock",
                  new PtyMessage.Agent("claude-code", "bypassPermissions", false)));

      assertEquals(
          new PtyMessage.Err("Session 's' is already running; attach or kill it."), answer);
      assertEquals(handed.getFirst().subList(0, 2), withdrawn.poll(30, TimeUnit.SECONDS));
    }
  }

  /**
   * Minor 1: an agent session's login file is withdrawn when the session ends, whether or not its
   * wrapper read it; a session that signed in inside, ended first, has none to withdraw.
   */
  @Test
  void anAgentSessionsLoginIsWithdrawnWhenItEnds() throws Exception {
    try (var ignored = startHost();
        var channel = connect()) {
      assertInstanceOf(
          PtyMessage.Ok.class,
          ask(
              channel,
              agent(
                  "s",
                  List.of(),
                  "dock",
                  new PtyMessage.Agent("claude-code", "bypassPermissions", false))));
      assertInstanceOf(
          PtyMessage.Ok.class,
          ask(
              channel,
              agent(
                  "t",
                  List.of(),
                  "dock",
                  new PtyMessage.Agent("claude-code", "bypassPermissions", true))));
      var file = SessionLogin.file(listed(channel).getFirst().instanceId());
      assertEquals(file, handed.getFirst().get(1));

      assertInstanceOf(PtyMessage.Ok.class, ask(channel, new PtyMessage.Kill("t")));
      assertInstanceOf(PtyMessage.Ok.class, ask(channel, new PtyMessage.Kill("s")));

      assertEquals(
          List.of("acme-dock", file),
          withdrawn.poll(30, TimeUnit.SECONDS),
          "the inside session, ended first, had none to withdraw");
    }
  }

  /** Scenario 16: a shell runs no harness. */
  @Test
  void aShellRunsNoHarness() throws Exception {
    try (var ignored = startHost();
        var channel = connect()) {
      assertInstanceOf(
          PtyMessage.Ok.class,
          ask(channel, new PtyMessage.Create("s", List.of(), "/tmp", "acme", "", 80, 24, "dock")));

      var info = listed(channel).getFirst();
      assertEquals(PtyMessage.Agent.NONE, info.agent());
      assertEquals(List.of("bash", "-l"), info.command());
      assertEquals(List.of("enter acme dock"), calls);
    }
  }

  /** The argv the session's child was started with, one per line: the shell's text is one. */
  private List<String> awaitArgv() throws Exception {
    var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
    while (System.nanoTime() < deadline) {
      if (Files.exists(argv())) {
        var lines = Files.readAllLines(argv());
        if (!lines.isEmpty() && lines.getLast().contains("exec claude")) {
          return lines;
        }
      }
      Thread.onSpinWait();
    }
    throw new AssertionError("the session's child never started");
  }
}
