/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.sail.config.SyncConfig;
import ai.singlr.sail.config.TestDefinitions;
import ai.singlr.sail.engine.AbstractIncusIT;
import ai.singlr.sail.engine.BoxInstances;
import ai.singlr.sail.engine.WorkspaceOwner;
import ai.singlr.sail.engine.Workspaces;
import ai.singlr.sail.harness.Harnesses;
import ai.singlr.sail.identity.Acting;
import ai.singlr.sail.identity.Actor;
import ai.singlr.sail.identity.Role;
import ai.singlr.sail.pty.PtyAdmission;
import ai.singlr.sail.pty.PtyEvents;
import ai.singlr.sail.pty.PtyIdentity;
import ai.singlr.sail.pty.PtyMessage;
import ai.singlr.sail.pty.PtySessionHost;
import ai.singlr.sail.pty.PtyWire;
import ai.singlr.sail.store.FdeStore;
import ai.singlr.sail.store.HeldProjects;
import ai.singlr.sail.store.LoginStore;
import ai.singlr.sail.store.SchemaManager;
import ai.singlr.sail.store.Sqlite;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * An agent started in a person's workspace on a real Incus instance labelled for them: whose login
 * it is decided as production decides it, the login handed over by the dev user's stdin, and stub
 * harnesses on the instance's {@code PATH} that write down the fingerprint of the login they were
 * handed. Its value is then searched for, by a pattern in a {@code 0600} file and counted, never
 * printed, in every process's arguments here and in the instance, the session's records and the
 * listing. Self-cleaning: the instance is removed.
 */
class PtyAgentIT extends AbstractIncusIT {

  private static final String PROJECT = "sail-it-agent";
  private static final String WORKSPACE = "w";
  private static final String INSTANCE = PROJECT + "-" + WORKSPACE;
  private static final String TOKEN = "sk-ant-oat01-pty-agent-it-" + System.nanoTime();
  private static final String KEY = "sk-proj-pty-agent-it-" + System.nanoTime();

  /**
   * A stub harness: it writes down its arguments, the fingerprint of the credential it was handed
   * (its variable's, or for Codex what its own login stored) and the names of its credential
   * variables that hold anything, then waits on its terminal.
   */
  private static final String STUB =
      """
      #!/bin/sh
      fp() { printf %s "$1" | sha256sum | cut -c1-8; }
      if [ "$1" = login ]; then
        mkdir -p "$HOME/.codex" && (umask 077; cat > "$HOME/.codex/auth.json"); exit 0
      fi
      if [ -e "$HOME/.codex/auth.json" ]; then given=$(cat "$HOME/.codex/auth.json")
      else given=$CLAUDE_CODE_OAUTH_TOKEN; fi
      set=$(env | sed -n 's/^\\(CODEX_API_KEY\\|CODEX_ACCESS_TOKEN\\|ANTHROPIC_[A-Z_]*\\|CLAUDE_CODE_OAUTH_TOKEN\\)=..*/\\1/p' | sort | tr '\\n' ' ')
      printf '%s\\n%s\\nset:%s\\n' "$(basename "$0") $*" "$(fp "$given")" "$set" > /tmp/report.part
      mv /tmp/report.part /tmp/report
      exec cat
      """;

  @TempDir Path dir;

  private String adaId;
  private FdeStore fdes;

  private HostLanes.Pty lane(Sqlite db) {
    fdes = new FdeStore(db);
    return new HostLanes.Pty(
        db,
        TestAuth.roles(db, new SyncConfig("main", null, "root", "box")),
        null,
        new BoxInstances(shell),
        Workspaces.onThisBox(shell),
        project ->
            Optional.of(
                TestDefinitions.yaml(
                    "name: "
                        + project
                        + "\nagent:\n  type: claude-code\n  install: [claude-code, codex]\n")),
        handle -> WorkspaceOwner.onThisBox(fdes, handle, "root"),
        shell);
  }

  /** A host that admits whom its token names, deciding each agent as production's door does. */
  private PtySessionHost startHost(HostLanes.Pty lane) throws Exception {
    var admission =
        new PtyAdmission() {
          @Override
          public void box(PtyIdentity.Person who) {}

          @Override
          public void project(String project, PtyIdentity.Person who) {}

          @Override
          public void workspace(String project, String workspace, PtyIdentity.Person who) {}

          @Override
          public void enter(String project, String workspace, PtyIdentity.Person who) {}

          @Override
          public void room(String room, String project, PtyIdentity.Person who) {}

          @Override
          public Optional<LoginStore.Login> agent(
              String project, String workspace, PtyMessage.Agent agent, PtyIdentity.Person who)
              throws IOException {
            return lane.admitAgent(project, workspace, agent, who);
          }

          @Override
          public void handOver(String instance, String file, String lines) throws IOException {
            lane.handOver(instance, file, lines);
          }

          @Override
          public void withdraw(String instance, String file) {
            lane.withdraw(instance, file);
          }
        };
    var host =
        new PtySessionHost(
            dir.resolve("h.sock"),
            dir.resolve("s"),
            64 * 1024,
            token ->
                new PtyIdentity.Person(
                    new Actor(
                        token, token.equals("root") ? Role.ADMIN : Role.MEMBER, Actor.Lane.API)),
            admission,
            PtyEvents.NONE,
            "0.0.0-test");
    host.start();
    return host;
  }

  private void prepare() throws Exception {
    launch(
        INSTANCE,
        "-c",
        BoxInstances.PROJECT_KEY + "=" + PROJECT,
        "-c",
        BoxInstances.WORKSPACE_KEY + "=" + WORKSPACE,
        "-c",
        BoxInstances.READY_KEY + "=1762941601000",
        "-c",
        BoxInstances.OWNER_KEY + "=ada",
        "-c",
        BoxInstances.OWNER_ID_KEY + "=" + adaId);
    inInstance("mkdir -p /home/dev/workspace && chown -R 1000:1000 /home/dev");
    for (var name : List.of("claude", "codex")) {
      var pushed =
          shell.execWithInput(
              List.of(
                  "incus",
                  "exec",
                  INSTANCE,
                  "--",
                  "sh",
                  "-c",
                  "cat > /usr/local/bin/" + name + " && chmod 755 /usr/local/bin/" + name),
              STUB,
              Duration.ofSeconds(60));
      assertTrue(pushed.ok(), pushed.stderr());
    }
    var pattern =
        shell.execWithInput(
            List.of("incus", "exec", INSTANCE, "--", "sh", "-c", "umask 077; cat > /root/pattern"),
            TOKEN + "\n" + KEY + "\n",
            Duration.ofSeconds(60));
    assertTrue(pattern.ok(), pattern.stderr());
  }

  private String inInstance(String script) throws Exception {
    var ran = exec(INSTANCE, List.of("sh", "-c", script));
    assertTrue(ran.ok(), script + " failed: " + ran.stderr());
    return ran.stdout().strip();
  }

  private SocketHolder connect(String token) throws IOException {
    return new SocketHolder(dir.resolve("h.sock"), token);
  }

  /** What the stub harness wrote down, once it has: its line, its fingerprint, its variables. */
  private List<String> report() throws Exception {
    var deadline = System.nanoTime() + Duration.ofSeconds(60).toNanos();
    while (System.nanoTime() < deadline) {
      var said = inInstance("cat /tmp/report 2>/dev/null || true");
      if (!said.isEmpty()) {
        inInstance("rm -f /tmp/report");
        return List.of(said.split("\n", -1));
      }
    }
    throw new AssertionError("the stub harness never started");
  }

  /** How many times the login values appear in what every process in the instance runs. */
  private int inInstanceArguments() throws Exception {
    return Integer.parseInt(inInstance("ps -eo args | grep -cF -f /root/pattern || true"));
  }

  /** How many processes on this host hold either value in their arguments. */
  private static long hereArguments() {
    return ProcessHandle.allProcesses()
        .map(process -> String.join(" ", process.info().arguments().orElse(new String[0])))
        .filter(line -> line.contains(TOKEN) || line.contains(KEY))
        .count();
  }

  /** How many of the session's records here hold either value. */
  private long inRecords() throws IOException {
    try (Stream<Path> files = Files.walk(dir.resolve("s"))) {
      return files
          .filter(Files::isRegularFile)
          .filter(
              file -> {
                try {
                  var held = new String(Files.readAllBytes(file), StandardCharsets.ISO_8859_1);
                  return held.contains(TOKEN) || held.contains(KEY);
                } catch (IOException unreadable) {
                  return true;
                }
              })
          .count();
    }
  }

  private static String fingerprint(LoginStore.Login login) {
    return login.row().fingerprint();
  }

  /**
   * Scenarios 1, 2, 3, 9 and 13 on a real instance: Claude Code and Codex start signed in with
   * their owner's own logins, the login file gone before either starts and Codex's key in its own
   * store, an admin is refused, a leftover from a killed hand-over is removed, and neither value is
   * on any process's arguments, in the session's records or in the listing.
   */
  @Test
  void anAgentStartsInItsOwnersWorkspaceSignedInAsThemWithTheLoginOnNoLineOrRecord()
      throws Exception {
    ensureIncusOrSkip();
    try (var db = Sqlite.open(dir.resolve("sail.db"))) {
      new SchemaManager(db).migrate();
      HeldProjects.hold(db, PROJECT);
      var lane = lane(db);
      adaId = Acting.system(() -> fdes.add("ada", "Ada", null, "member")).id();
      Acting.system(() -> fdes.add("root", "Root", null, "admin"));
      fdes.logins().store(adaId, Harnesses.of("claude-code"), "work", "token", TOKEN);
      fdes.logins().store(adaId, Harnesses.of("codex"), "work", "api-key", KEY);
      var claude = fdes.logins().defaultFor(adaId, Harnesses.of("claude-code")).orElseThrow();
      var codex = fdes.logins().defaultFor(adaId, Harnesses.of("codex")).orElseThrow();
      try {
        prepare();
        inInstance(
            "mkdir -p /home/dev/.sail/sessions/0f0e0d0c-0000-7000-8000-000000000000"
                + " && touch /home/dev/.sail/sessions/0f0e0d0c-0000-7000-8000-000000000000/login.env"
                + " && touch -d '-5 min' /home/dev/.sail/sessions/0f0e0d0c-0000-7000-8000-000000000000"
                + " && chown -R 1000:1000 /home/dev/.sail");
        try (var host = startHost(lane);
            var ada = connect("ada");
            var root = connect("root")) {
          var refused = root.ask(create("a0", new PtyMessage.Agent("claude-code", "full", false)));
          assertEquals(
              "w is ada's workspace, so an agent starts in it only as ada, with ada's login.",
              assertInstanceOf(PtyMessage.Err.class, refused).reason());

          assertInstanceOf(
              PtyMessage.Ok.class,
              ada.ask(create("a1", new PtyMessage.Agent("claude-code", "full", false))));
          assertEquals(
              List.of(
                  "claude --dangerously-skip-permissions",
                  fingerprint(claude),
                  "set:CLAUDE_CODE_OAUTH_TOKEN"),
              report());
          assertEquals(
              "gone", inInstance("ls -A /home/dev/.sail/sessions | wc -l | sed 's/^0$/gone/'"));

          assertInstanceOf(
              PtyMessage.Ok.class,
              ada.ask(create("a2", new PtyMessage.Agent("codex", "full", false))));
          assertEquals(
              List.of(
                  "codex --dangerously-bypass-approvals-and-sandbox", fingerprint(codex), "set:"),
              report());
          assertEquals("600 1000", inInstance("stat -c '%a %u' /home/dev/.codex/auth.json"));

          assertEquals(0, inInstanceArguments(), "neither login is on a line in the workspace");
          assertEquals(0, hereArguments(), "neither login is on a line here");
          assertEquals(0, inRecords(), "neither login is in the session's records");
          var listed =
              (PtyMessage.Sessions) ada.ask(new PtyMessage.ListSessions("", PtyMessage.PAGE_LIMIT));
          assertTrue(
              listed.sessions().stream()
                  .noneMatch(
                      info -> info.toString().contains(TOKEN) || info.toString().contains(KEY)));
          assertEquals(
              List.of(
                  new PtyMessage.Agent("claude-code", "full", false),
                  new PtyMessage.Agent("codex", "full", false)),
              listed.sessions().stream().map(PtyMessage.SessionInfo::agent).toList());
        }
      } finally {
        deleteContainerQuietly(INSTANCE);
      }
    }
  }

  private static PtyMessage.Create create(String session, PtyMessage.Agent agent) {
    return new PtyMessage.Create(session, List.of(), "/", PROJECT, "", 80, 24, WORKSPACE, agent);
  }

  /** One connection to the host, as {@code token}'s person. */
  private static final class SocketHolder implements AutoCloseable {
    private final java.nio.channels.SocketChannel channel;

    SocketHolder(Path socket, String token) throws IOException {
      channel = java.nio.channels.SocketChannel.open(java.net.StandardProtocolFamily.UNIX);
      channel.connect(java.net.UnixDomainSocketAddress.of(socket));
      PtyWire.handshake(channel, channel);
      PtyWire.write(channel, new PtyMessage.Hello(token));
      assertInstanceOf(PtyMessage.Welcome.class, PtyWire.read(channel));
    }

    PtyMessage ask(PtyMessage message) throws IOException {
      PtyWire.write(channel, message);
      return PtyWire.read(channel);
    }

    @Override
    public void close() throws IOException {
      channel.close();
    }
  }
}
