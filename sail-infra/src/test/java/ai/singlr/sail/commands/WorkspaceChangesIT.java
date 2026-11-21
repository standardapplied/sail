/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.commands;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.sail.api.OperationsFactory;
import ai.singlr.sail.common.Secrets;
import ai.singlr.sail.engine.AbstractIncusIT;
import ai.singlr.sail.engine.BoxInstances;
import ai.singlr.sail.identity.Actor;
import ai.singlr.sail.identity.Role;
import ai.singlr.sail.pty.PtyEvents;
import ai.singlr.sail.pty.PtyIdentity;
import ai.singlr.sail.pty.PtyMessage;
import ai.singlr.sail.pty.PtySessionHost;
import ai.singlr.sail.pty.PtyWire;
import ai.singlr.sail.store.FdeStore;
import ai.singlr.sail.store.HeldProjects;
import ai.singlr.sail.store.SchemaManager;
import ai.singlr.sail.store.Sqlite;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * What changed since {@code HEAD} in a real Incus workspace, made from the prepared image, whose
 * git is the baseline's: an edit made by a terminal session there appears in the list as modified,
 * its diff is that edit, and an ignored build folder of 1,000 files is named once with nothing
 * beneath it. The time an answer took is printed; the fleet's storage row gates it. Self-cleaning:
 * the instance is removed.
 */
class WorkspaceChangesIT extends AbstractIncusIT {

  private static final String PROJECT = "sail-it-changes";
  private static final String WORKSPACE = "w";
  private static final String INSTANCE = PROJECT + "-" + WORKSPACE;
  private static final String REPO = "/home/dev/workspace/app";

  @TempDir Path dir;

  private final Map<String, PtyIdentity.Person> people =
      Map.of(
          "tok-ana", new PtyIdentity.Person(new Actor("ana", Role.MEMBER, Actor.Lane.API)),
          "tok-uday", new PtyIdentity.Person(new Actor("uday", Role.ADMIN, Actor.Lane.API)));

  private PtySessionHost startHost(Path dbPath) throws IOException {
    var host =
        new PtySessionHost(
            dir.resolve("h.sock"),
            dir.resolve("s"),
            64 * 1024,
            token -> people.get(token.isEmpty() ? "tok-uday" : token),
            new PtyHostAccess(() -> OperationsFactory.open(dbPath)),
            PtyEvents.NONE,
            "0.0.0-test",
            PtySessionHost.Handoff.NONE,
            PtySessionHost.Network.on(
                new InetSocketAddress(InetAddress.getLoopbackAddress(), 0),
                hash ->
                    people.entrySet().stream()
                        .filter(person -> Secrets.hash(person.getKey()).equals(hash))
                        .findFirst()
                        .map(Map.Entry::getValue)
                        .orElseThrow(() -> new IOException("The credential is not valid."))));
    host.start();
    return host;
  }

  private SocketChannel files(PtySessionHost host) throws IOException {
    var ticket =
        assertInstanceOf(
            PtyMessage.Ticket.class,
            SessionClient.ticket(
                dir.resolve("h.sock"),
                "tok-ana",
                PtyMessage.TicketAction.FILES,
                PROJECT,
                WORKSPACE));
    var channel = SocketChannel.open(host.tcpAddress().orElseThrow());
    PtyWire.handshake(channel, channel);
    PtyWire.write(channel, new PtyMessage.Hello(ticket.ticket()));
    assertInstanceOf(PtyMessage.Welcome.class, PtyWire.read(channel));
    return channel;
  }

  /** A session in the workspace, opened as its terminal is, that runs {@code command} and ends. */
  private void inASession(String name, String command) throws IOException {
    try (var channel = SocketChannel.open(StandardProtocolFamily.UNIX)) {
      channel.connect(UnixDomainSocketAddress.of(dir.resolve("h.sock")));
      PtyWire.handshake(channel, channel);
      PtyWire.write(channel, new PtyMessage.Hello(""));
      assertInstanceOf(PtyMessage.Welcome.class, PtyWire.read(channel));
      PtyWire.write(
          channel,
          new PtyMessage.Create(
              name, List.of("sh", "-c", command), "/tmp", PROJECT, "", 80, 24, WORKSPACE));
      assertInstanceOf(PtyMessage.Ok.class, PtyWire.read(channel), "the session opened");
      PtyWire.write(channel, new PtyMessage.Attach(name, false));
      while (!(PtyWire.read(channel) instanceof PtyMessage.SessionEnded)) {
        continue;
      }
    }
  }

  /** One answer to a changes request: its frames, in order, and how long it took to come. */
  private record Answered(List<PtyMessage.FilesChangeList> frames, long millis) {}

  private static Answered changes(SocketChannel files, int id) throws IOException {
    var started = System.nanoTime();
    PtyWire.write(files, new PtyMessage.FilesChanges(id));
    var frames = new ArrayList<PtyMessage.FilesChangeList>();
    PtyMessage.FilesChangeList frame;
    do {
      var next = next(files);
      frame = assertInstanceOf(PtyMessage.FilesChangeList.class, next, () -> "answered " + next);
      assertEquals(id, frame.id());
      frames.add(frame);
    } while (frame.more());
    return new Answered(frames, (System.nanoTime() - started) / 1_000_000);
  }

  private static PtyMessage next(SocketChannel channel) throws IOException {
    while (true) {
      var message = PtyWire.read(channel);
      if (!(message instanceof PtyMessage.FilesTick)) {
        return message;
      }
    }
  }

  @Test
  void anEditInAWorkspacesTerminalIsItsChangeAndAnIgnoredBuildFolderIsNamedOnce() throws Exception {
    ensureIncusOrSkip();
    var dbPath = dir.resolve("sail.db");
    String ana;
    try (var db = Sqlite.open(dbPath)) {
      new SchemaManager(db).migrate();
      HeldProjects.hold(db, PROJECT);
      var fdes = new FdeStore(db);
      ana = fdes.add("ana", "Ana", "ana@example.com", "member").id();
      fdes.add("uday", "Uday", "uday@example.com", "admin");
    }
    try {
      launchPrepared(
          INSTANCE,
          "-c",
          BoxInstances.PROJECT_KEY + "=" + PROJECT,
          "-c",
          BoxInstances.WORKSPACE_KEY + "=" + WORKSPACE,
          "-c",
          BoxInstances.OWNER_KEY + "=ana",
          "-c",
          BoxInstances.OWNER_ID_KEY + "=" + ana,
          "-c",
          BoxInstances.READY_KEY + "=1762941601000");
      var prepared =
          exec(
              INSTANCE,
              List.of(
                  "bash",
                  "-c",
                  "mkdir -p "
                      + REPO
                      + " && cd "
                      + REPO
                      + " && git init -q -b main && printf 'one\\ntwo\\n' > app.txt && printf"
                      + " 'build/\\n' > .gitignore && git add . && git -c user.name=t -c"
                      + " user.email=t@t commit -qm base && mkdir -p build/out && for i in $(seq 1"
                      + " 1000); do : > build/out/o$i; done && chown -R 1000:1000 /home/dev"));
      assertTrue(prepared.ok(), prepared.stderr());
      try (var host = startHost(dbPath);
          var files = files(host)) {
        var before = changes(files, 1);
        assertEquals(List.of(), before.frames().getFirst().entries(), "nothing changed yet");

        inASession("edit", "printf 'three\\n' >> " + REPO + "/app.txt");

        var after = changes(files, 2);
        assertEquals(1, after.frames().size());
        var list = after.frames().getFirst();
        assertEquals(REPO, list.root());
        assertEquals("main", list.branch());
        assertEquals(
            List.of(new PtyMessage.Change(REPO + "/app.txt", PtyMessage.ChangeState.MODIFIED, "")),
            list.entries());
        assertEquals(List.of(REPO + "/build"), list.ignored(), "the build folder, named once");
        assertFalse(list.truncated());
        assertFalse(list.more());

        PtyWire.write(files, new PtyMessage.FilesDiff(3, REPO + "/app.txt"));
        var diff = new ByteArrayOutputStream();
        PtyMessage.FilesDiffData data;
        do {
          var next = next(files);
          data = assertInstanceOf(PtyMessage.FilesDiffData.class, next, () -> "answered " + next);
          diff.write(data.bytes());
        } while (!data.eof());
        var shown = diff.toString(StandardCharsets.UTF_8);
        assertTrue(shown.contains("@@ -1,2 +1,3 @@\n one\n two\n+three\n"), shown);

        var took = new ArrayList<Long>();
        for (var i = 0; i < 20; i++) {
          took.add(changes(files, 10 + i).millis());
        }
        System.out.printf(
            "what changed over incus exec, host side, a small repository beside an ignored folder"
                + " of 1,000 files: p95 %d ms, each answer saying it took %d ms (gated on GCP by"
                + " the fleet's storage row)%n",
            took.stream().sorted().toList().get(18), after.frames().getFirst().tookMillis());
      }
    } finally {
      deleteContainerQuietly(INSTANCE);
    }
  }
}
