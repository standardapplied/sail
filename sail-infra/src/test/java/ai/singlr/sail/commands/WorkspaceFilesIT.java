/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.commands;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.sail.api.OperationsFactory;
import ai.singlr.sail.api.SessionYield;
import ai.singlr.sail.common.Secrets;
import ai.singlr.sail.engine.AbstractIncusIT;
import ai.singlr.sail.engine.BoxInstances;
import ai.singlr.sail.identity.Actor;
import ai.singlr.sail.identity.Role;
import ai.singlr.sail.pty.FileNames;
import ai.singlr.sail.pty.PtyEvents;
import ai.singlr.sail.pty.PtyIdentity;
import ai.singlr.sail.pty.PtyMessage;
import ai.singlr.sail.pty.PtySessionHost;
import ai.singlr.sail.pty.PtyWire;
import ai.singlr.sail.store.FdeStore;
import ai.singlr.sail.store.HeldProjects;
import ai.singlr.sail.store.SchemaManager;
import ai.singlr.sail.store.Sqlite;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A workspace's files read through the terminal door against a real Incus container labelled as
 * ana's workspace, admitted as production admits them ({@link PtyHostAccess}, the box's roster and
 * Incus's labels): a files ticket for its owner and an admin, refused another member and a stopped
 * workspace, which stays stopped; the cost of the exec, 50 listings of 5,000 entries and 50 reads
 * of 256 KiB, and a change to a watched folder, printed and held to five times the spec's ceilings,
 * each listing one set of frames and the change reported once; a connection closed mid-read and
 * mid-watch leaves nothing of its scripts in the workspace within 5 s (F5); a stop ends the
 * connection in its own sentence. Self-cleaning: the instance is removed.
 */
class WorkspaceFilesIT extends AbstractIncusIT {

  private static final String PROJECT = "sail-it-files";
  private static final String WORKSPACE = "w";
  private static final String INSTANCE = PROJECT + "-" + WORKSPACE;

  /**
   * The spec's ceilings (list 250 ms, read 200 ms, change 600 ms) are gated where the hardware is
   * steady, by the fleet's storage row on GCP. A shared CI runner is not: it went 13 ms over once.
   * Here each figure is printed and held to five times its ceiling, which no runner's noise reaches
   * and which still catches a hang or a listing that went quadratic: an exec per entry would cost
   * 5,000 times about 60 ms.
   */
  private static final long LIST_BOUND_MS = 5 * 250;

  private static final long READ_BOUND_MS = 5 * 200;
  private static final long CHANGE_BOUND_MS = 5 * 600;

  /**
   * A 20 MiB read hashed twice has no ceiling of the spec's: its figure is printed, and a bound of
   * 10 s catches only a hang, since two SHA-256 passes over 20 MiB take well under a second.
   */
  private static final long WHOLE_BOUND_MS = 10_000;

  @TempDir Path dir;

  private final Map<String, PtyIdentity.Person> people =
      Map.of(
          "tok-ana", new PtyIdentity.Person(new Actor("ana", Role.MEMBER, Actor.Lane.API)),
          "tok-ben", new PtyIdentity.Person(new Actor("ben", Role.MEMBER, Actor.Lane.API)),
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

  private PtyMessage ticket(String credential) throws IOException {
    return SessionClient.ticket(
        dir.resolve("h.sock"), credential, PtyMessage.TicketAction.FILES, PROJECT, WORKSPACE);
  }

  private SocketChannel open(PtySessionHost host, String credential) throws IOException {
    var ticket = assertInstanceOf(PtyMessage.Ticket.class, ticket(credential));
    var channel = SocketChannel.open(host.tcpAddress().orElseThrow());
    PtyWire.handshake(channel, channel);
    PtyWire.write(channel, new PtyMessage.Hello(ticket.ticket()));
    assertInstanceOf(PtyMessage.Welcome.class, PtyWire.read(channel));
    return channel;
  }

  private static PtyMessage next(SocketChannel channel) throws IOException {
    while (true) {
      var message = PtyWire.read(channel);
      if (!(message instanceof PtyMessage.FilesTick)) {
        return message;
      }
    }
  }

  /** The next frame that is not a tick, as {@code type}, or a failure saying what came instead. */
  private static <T extends PtyMessage> T next(SocketChannel channel, Class<T> type)
      throws IOException {
    var message = next(channel);
    return assertInstanceOf(type, message, () -> "the host answered " + message);
  }

  private void inInstance(String script) throws Exception {
    var ran =
        shell.exec(
            List.of(
                "incus",
                "exec",
                INSTANCE,
                "--user",
                "1000",
                "--group",
                "1000",
                "--cwd",
                "/",
                "--env",
                "HOME=/home/dev",
                "--",
                "bash",
                "-c",
                script));
    assertTrue(ran.ok(), script + " failed: " + ran.stderr());
  }

  /**
   * How many processes in the instance are the dev user's: the files scripts and what they exec
   * ({@code dd}, {@code find}), since nothing else runs as that user here. The image has no procps.
   */
  private int scripts() throws Exception {
    var ran =
        exec(
            INSTANCE,
            List.of(
                "sh",
                "-c",
                "n=0; for p in /proc/[0-9]*; do [ \"$(stat -c %u $p 2>/dev/null)\" = 1000 ] &&"
                    + " n=$((n+1)); done; echo $n"));
    return Integer.parseInt(ran.stdout().strip());
  }

  private void awaitNoScripts() throws Exception {
    var deadline = System.nanoTime() + 5_000_000_000L;
    while (scripts() > 0) {
      assertTrue(System.nanoTime() < deadline, "a files script outlived its connection by 5 s");
    }
  }

  private static long p95(List<Long> millis) {
    var sorted = millis.stream().sorted().toList();
    return sorted.get((int) Math.ceil(sorted.size() * 0.95) - 1);
  }

  @Test
  void aWorkspacesFilesAreReadThroughItsTerminalDoorAndNothingOfThemOutlivesTheirConnection()
      throws Exception {
    ensureIncusOrSkip();
    var dbPath = dir.resolve("sail.db");
    String ana;
    try (var db = Sqlite.open(dbPath)) {
      new SchemaManager(db).migrate();
      HeldProjects.hold(db, PROJECT);
      var fdes = new FdeStore(db);
      ana = fdes.add("ana", "Ana", "ana@example.com", "member").id();
      fdes.add("ben", "Ben", "ben@example.com", "member");
      fdes.add("uday", "Uday", "uday@example.com", "admin");
    }
    try {
      launch(
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
                  "bash", "-c", "mkdir -p /home/dev/workspace && chown -R 1000:1000 /home/dev"));
      assertTrue(prepared.ok(), prepared.stderr());
      inInstance(
          "mkdir -p /home/dev/workspace/big && cd /home/dev/workspace/big && for i in $(seq 1"
              + " 5000); do : > src-$i.java; done && head -c 1048576 /dev/urandom > /home/dev/blob"
              + " && head -c 104857600 /dev/zero > /home/dev/huge");
      try (var host = startHost(dbPath)) {
        assertInstanceOf(PtyMessage.Ticket.class, ticket("tok-ana"), "its owner");
        assertInstanceOf(PtyMessage.Ticket.class, ticket("tok-uday"), "an admin");
        var refused = assertInstanceOf(PtyMessage.TicketRefused.class, ticket("tok-ben"));
        assertEquals(PtyMessage.TicketRefusal.FORBIDDEN, refused.kind());
        assertEquals("Workspace 'w' belongs to 'ana', not you.", refused.reason());

        var lists = new ArrayList<Long>();
        var reads = new ArrayList<Long>();
        try (var files = open(host, "tok-ana")) {
          for (var i = 0; i < 50; i++) {
            var started = System.nanoTime();
            PtyWire.write(files, new PtyMessage.FilesList(i, "/home/dev/workspace/big"));
            var count = 0;
            PtyMessage.FilesListing listing;
            String version = null;
            do {
              listing = next(files, PtyMessage.FilesListing.class);
              assertEquals(i, listing.id(), "one listing's frames, in a row");
              assertTrue(version == null || version.equals(listing.version()));
              version = listing.version();
              count += listing.entries().size();
            } while (listing.more());
            lists.add((System.nanoTime() - started) / 1_000_000);
            assertEquals(5_000, count, "every entry, once, in one set of frames");
            assertTrue(!listing.truncated());
          }
          for (var i = 0; i < 50; i++) {
            var started = System.nanoTime();
            PtyWire.write(
                files,
                new PtyMessage.FilesRead(100 + i, "/home/dev/blob", 0, PtyMessage.FILE_CHUNK));
            var data = next(files, PtyMessage.FilesData.class);
            reads.add((System.nanoTime() - started) / 1_000_000);
            assertEquals(PtyMessage.FILE_CHUNK, data.bytes().length);
          }
          PtyWire.write(files, new PtyMessage.FilesStat(200, "/home/dev/workspace"));
          var version = next(files, PtyMessage.FilesEntry.class).version();
          PtyWire.write(
              files,
              new PtyMessage.FilesWatch(
                  List.of(new PtyMessage.Watched("/home/dev/workspace", version))));
          assertInstanceOf(PtyMessage.FilesTick.class, PtyWire.read(files));
          var changed = System.nanoTime();
          inInstance(": > /home/dev/workspace/new.txt");
          assertEquals(new PtyMessage.FilesChanged(List.of("/home/dev/workspace")), next(files));
          var change = (System.nanoTime() - changed) / 1_000_000;
          assertInstanceOf(PtyMessage.FilesTick.class, PtyWire.read(files), "reported once");
          assertInstanceOf(PtyMessage.FilesTick.class, PtyWire.read(files), "and only once");

          inInstance(
              "mkdir -p /home/dev/odd && printf body > \"/home/dev/odd/bad$(printf '\\377')n\"");
          PtyWire.write(files, new PtyMessage.FilesList(300, "/home/dev/odd"));
          var odd = next(files, PtyMessage.FilesListing.class).entries().getFirst().name();
          assertArrayEquals(new byte[] {'b', 'a', 'd', (byte) 0xff, 'n'}, FileNames.encode(odd));
          var oddPath = "/home/dev/odd/" + odd;
          PtyWire.write(files, new PtyMessage.FilesRead(301, oddPath, 0, 100));
          var oddData = next(files, PtyMessage.FilesData.class);
          assertEquals(
              "body", new String(oddData.bytes(), java.nio.charset.StandardCharsets.UTF_8));
          PtyWire.write(files, new PtyMessage.FilesStat(302, oddPath));
          var oddVersion = next(files, PtyMessage.FilesEntry.class).version();
          PtyWire.write(
              files,
              new PtyMessage.FilesWatch(List.of(new PtyMessage.Watched(oddPath, oddVersion))));
          assertInstanceOf(PtyMessage.FilesTick.class, PtyWire.read(files));
          inInstance("printf more >> \"/home/dev/odd/bad$(printf '\\377')n\"");
          assertEquals(
              new PtyMessage.FilesChanged(List.of(oddPath)), next(files), "watched by its bytes");
          System.out.printf(
              "files over incus exec, host side: list 5,000 p95 %d ms, first 256 KiB p95 %d ms,"
                  + " change to FilesChanged %d ms (gated on GCP at 250, 200 and 600 ms; here at"
                  + " %d, %d and %d ms, which only a hang or a quadratic reaches)%n",
              p95(lists), p95(reads), change, LIST_BOUND_MS, READ_BOUND_MS, CHANGE_BOUND_MS);
          assertTrue(p95(lists) <= LIST_BOUND_MS, "list p95 " + p95(lists) + " ms");
          assertTrue(p95(reads) <= READ_BOUND_MS, "read p95 " + p95(reads) + " ms");
          assertTrue(change <= CHANGE_BOUND_MS, "change " + change + " ms");

          inInstance("head -c 20971520 /dev/urandom > /home/dev/twenty");
          var whole = new ArrayList<Long>();
          for (var i = 0; i < 10; i++) {
            var started = System.nanoTime();
            PtyWire.write(
                files, new PtyMessage.FilesRead(400 + i, "/home/dev/twenty", 0, 20L << 20));
            var sha = java.security.MessageDigest.getInstance("SHA-256");
            PtyMessage.FilesData data;
            do {
              data = next(files, PtyMessage.FilesData.class);
              sha.update(data.bytes());
            } while (!data.eof());
            whole.add((System.nanoTime() - started) / 1_000_000);
            assertEquals(java.util.HexFormat.of().formatHex(sha.digest()), data.digest());
          }
          System.out.printf(
              "a 20 MiB read, hashed as sent and again from the file: p95 %d ms (bound %d ms)%n",
              p95(whole), WHOLE_BOUND_MS);
          assertTrue(p95(whole) <= WHOLE_BOUND_MS, "20 MiB read p95 " + p95(whole) + " ms");

          PtyWire.write(files, new PtyMessage.FilesRead(300, "/home/dev/huge", 0, 20L << 20));
          next(files, PtyMessage.FilesData.class);
          assertTrue(scripts() >= 1, "the watch, and a read its unread connection holds");
        }
        awaitNoScripts();

        var watched = open(host, "tok-ana");
        PtyWire.write(watched, new PtyMessage.FilesWatch(List.of()));
        assertInstanceOf(PtyMessage.FilesTick.class, PtyWire.read(watched));
        new PtyHostYield(dir.resolve("h.sock"))
            .endIn(
                PROJECT,
                WORKSPACE,
                new SessionYield.Ending(
                    "Workspace w of sail-it-files is being stopped by ana, which ends this"
                        + " terminal.",
                    "Workspace w of sail-it-files was stopped by ana."));
        assertEquals(
            new PtyMessage.Err("Workspace w of sail-it-files was stopped by ana."), next(watched));
        assertEquals(-1, watched.read(ByteBuffer.allocate(1)));
        watched.close();
        awaitNoScripts();

        assertTrue(shell.exec(List.of("incus", "stop", "--force", INSTANCE)).ok());
        var stopped = assertInstanceOf(PtyMessage.TicketRefused.class, ticket("tok-ana"));
        assertEquals(PtyMessage.TicketRefusal.STOPPED, stopped.kind());
        assertEquals(
            "STOPPED",
            shell
                .exec(List.of("incus", "list", "^" + INSTANCE + "$", "-c", "s", "-f", "csv"))
                .stdout()
                .strip(),
            "a files ticket starts nothing");
      }
    } finally {
      deleteContainerQuietly(INSTANCE);
    }
  }
}
