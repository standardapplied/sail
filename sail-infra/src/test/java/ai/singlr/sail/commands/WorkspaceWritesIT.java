/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.commands;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.sail.api.OperationsFactory;
import ai.singlr.sail.common.Secrets;
import ai.singlr.sail.engine.AbstractIncusIT;
import ai.singlr.sail.engine.BoxInstances;
import ai.singlr.sail.engine.IncusStorage;
import ai.singlr.sail.engine.PoolSource;
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
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A workspace's files changed through the terminal door against a real Incus container on a ZFS
 * pool, labelled as ana's workspace and admitted as production admits it: two savers on one base,
 * and two on one new name, of whom exactly one is written; a save cut by its connection leaves the
 * file and, within 5 s, no temporary; a save past the workspace's disk quota is refused with the
 * bytes free and leaves the file and no temporary; a temporary a stopped workspace left is never
 * listed and goes at the next save in its folder; a relabel between a save's bytes and its end
 * writes nothing and ends the connection in the decision's words. The checked save's cost is
 * printed and held to five times the spec's ceiling. Self-cleaning: the instance and its pool are
 * removed.
 */
class WorkspaceWritesIT extends AbstractIncusIT {

  private static final String PROJECT = "sail-it-writes";
  private static final String WORKSPACE = "w";
  private static final String INSTANCE = PROJECT + "-" + WORKSPACE;
  private static final String POOL = "sail-it-writes";
  private static final String FOLDER = "/home/dev/workspace";

  /**
   * The spec's ceiling for a checked save of 100 KB is 300 ms, gated on GCP; five times it here.
   */
  private static final long SAVE_BOUND_MS = 5 * 300;

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

  private SocketChannel open(PtySessionHost host, String credential) throws IOException {
    var ticket =
        assertInstanceOf(
            PtyMessage.Ticket.class,
            SessionClient.ticket(
                dir.resolve("h.sock"),
                credential,
                PtyMessage.TicketAction.FILES,
                PROJECT,
                WORKSPACE));
    var channel = SocketChannel.open(host.tcpAddress().orElseThrow());
    PtyWire.handshake(channel, channel);
    PtyWire.write(channel, new PtyMessage.Hello(ticket.ticket()));
    assertInstanceOf(PtyMessage.Welcome.class, PtyWire.read(channel));
    return channel;
  }

  /** The next frame that is neither a tick nor an acknowledgement. */
  private static PtyMessage next(SocketChannel channel) throws IOException {
    while (true) {
      var message = PtyWire.read(channel);
      if (!(message instanceof PtyMessage.FilesTick) && !(message instanceof PtyMessage.PutAck)) {
        return message;
      }
    }
  }

  private static String version(SocketChannel channel, String path) throws IOException {
    PtyWire.write(channel, new PtyMessage.FilesStat(900, path));
    var answer = next(channel);
    return assertInstanceOf(PtyMessage.FilesEntry.class, answer, () -> "" + answer).version();
  }

  private static byte[] sha256(byte[] bytes) throws Exception {
    return MessageDigest.getInstance("SHA-256").digest(bytes);
  }

  /** Sends save {@code id} of {@code bytes} as {@code path} on {@code base}, in 64 KiB chunks. */
  private static void send(SocketChannel channel, int id, String path, String base, byte[] bytes)
      throws Exception {
    PtyWire.write(channel, new PtyMessage.FilesWrite(id, path, base, bytes.length));
    for (var offset = 0; offset < bytes.length; offset += 64 << 10) {
      var end = Math.min(bytes.length, offset + (64 << 10));
      PtyWire.write(
          channel, new PtyMessage.PutChunk(id, offset, Arrays.copyOfRange(bytes, offset, end)));
    }
    PtyWire.write(channel, new PtyMessage.PutEnd(id, sha256(bytes)));
  }

  private String inInstance(String script) throws Exception {
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
    return ran.stdout().strip();
  }

  private String temporaries() throws Exception {
    return inInstance("ls -A " + FOLDER + " | grep '^\\.sail-save-' || true");
  }

  /** How many processes in the instance are the dev user's: the files scripts, nothing else. */
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
  void aWorkspacesFilesAreChangedWholeAndNeverOverANewerOne() throws Exception {
    ensureIncusOrSkip();
    var dbPath = dir.resolve("sail.db");
    String ana;
    String ben;
    try (var db = Sqlite.open(dbPath)) {
      new SchemaManager(db).migrate();
      HeldProjects.hold(db, PROJECT);
      var fdes = new FdeStore(db);
      ana = fdes.add("ana", "Ana", "ana@example.com", "member").id();
      ben = fdes.add("ben", "Ben", "ben@example.com", "member").id();
      fdes.add("uday", "Uday", "uday@example.com", "admin");
    }
    try {
      shell.exec(List.of("incus", "storage", "delete", POOL));
      var made = new IncusStorage(shell).create(POOL, new PoolSource.File(2048));
      assertTrue(made.ok(), "Incus could not make a file-backed zfs pool: " + made.stderr());
      launch(
          INSTANCE,
          "--storage",
          POOL,
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
              List.of("bash", "-c", "mkdir -p " + FOLDER + " && chown -R 1000:1000 /home/dev"));
      assertTrue(prepared.ok(), prepared.stderr());
      inInstance("printf base > " + FOLDER + "/notes.md");
      var random = new SecureRandom();
      try (var host = startHost(dbPath)) {
        try (var mine = open(host, "tok-ana");
            var theirs = open(host, "tok-uday")) {
          var notes = FOLDER + "/notes.md";
          var base = version(mine, notes);
          send(mine, 1, notes, base, "ana's".getBytes(StandardCharsets.UTF_8));
          send(theirs, 1, notes, base, "uday's".getBytes(StandardCharsets.UTF_8));
          var answers = List.of(next(mine), next(theirs));
          assertEquals(
              1,
              answers.stream().filter(PtyMessage.FilesWritten.class::isInstance).count(),
              "two savers on one base: exactly one written, " + answers);
          assertEquals(
              1, answers.stream().filter(PtyMessage.FilesConflict.class::isInstance).count());
          var kept = inInstance("cat " + notes);
          assertTrue(List.of("ana's", "uday's").contains(kept), kept);

          var fresh = FOLDER + "/fresh.md";
          send(mine, 2, fresh, PtyMessage.ABSENT, "ana's".getBytes(StandardCharsets.UTF_8));
          send(theirs, 2, fresh, PtyMessage.ABSENT, "uday's".getBytes(StandardCharsets.UTF_8));
          var made2 = List.of(next(mine), next(theirs));
          assertEquals(
              1,
              made2.stream().filter(PtyMessage.FilesWritten.class::isInstance).count(),
              "two savers of one new name: exactly one made it, " + made2);
          assertEquals(
              PtyMessage.FilesRefusal.EXISTS,
              made2.stream()
                  .filter(PtyMessage.FilesRefused.class::isInstance)
                  .map(m -> ((PtyMessage.FilesRefused) m).kind())
                  .findFirst()
                  .orElseThrow());

          var saves = new ArrayList<Long>();
          var hundred = new byte[100_000];
          for (var i = 0; i < 20; i++) {
            random.nextBytes(hundred);
            var on = version(mine, notes);
            var started = System.nanoTime();
            send(mine, 10 + i, notes, on, hundred);
            assertInstanceOf(PtyMessage.FilesWritten.class, next(mine));
            saves.add((System.nanoTime() - started) / 1_000_000);
          }
          System.out.printf(
              "a checked save of 100 KB over incus exec, host side: p95 %d ms (gated on GCP at"
                  + " 300 ms; here at %d ms)%n",
              p95(saves), SAVE_BOUND_MS);
          assertTrue(p95(saves) <= SAVE_BOUND_MS, "save p95 " + p95(saves) + " ms");
          assertEquals("", temporaries());
        }
        awaitNoScripts();

        try (var cut = open(host, "tok-ana")) {
          var notes = FOLDER + "/notes.md";
          var before = inInstance("sha256sum < " + notes);
          PtyWire.write(cut, new PtyMessage.FilesWrite(1, notes, version(cut, notes), 1 << 20));
          PtyWire.write(cut, new PtyMessage.PutChunk(1, 0, new byte[64 << 10]));
          assertEquals(new PtyMessage.PutAck(1, 64 << 10), PtyWire.read(cut));
          assertTrue(!temporaries().isEmpty(), "the save's temporary, mid-save");
          cut.close();
          awaitNoScripts();
          assertEquals("", temporaries(), "a cut save removes its own temporary");
          assertEquals(before, inInstance("sha256sum < " + notes));
        }

        var usage = rootUsage();
        var quota = (usage >> 20) + 8;
        assertTrue(
            shell
                .exec(
                    List.of(
                        "incus",
                        "config",
                        "device",
                        "override",
                        INSTANCE,
                        "root",
                        "size=" + quota + "MiB"))
                .ok(),
            "a quota just above the workspace's contents");
        try (var full = open(host, "tok-ana")) {
          var notes = FOLDER + "/notes.md";
          var before = inInstance("sha256sum < " + notes);
          var big = new byte[16 << 20];
          random.nextBytes(big);
          send(full, 1, notes, version(full, notes), big);
          var refused = assertInstanceOf(PtyMessage.FilesRefused.class, next(full));
          assertEquals(PtyMessage.FilesRefusal.NO_SPACE, refused.kind(), refused.message());
          assertTrue(
              refused.message().matches(".* has [0-9]+ bytes free and the save needs 16777216;.*"),
              refused.message());
          assertEquals(before, inInstance("sha256sum < " + notes), "the old file, byte for byte");
          assertEquals("", temporaries(), "no temporary on a full disk");
        }
        assertTrue(
            shell
                .exec(List.of("incus", "config", "device", "unset", INSTANCE, "root", "size"))
                .ok());

        try (var stopping = open(host, "tok-ana")) {
          var notes = FOLDER + "/notes.md";
          PtyWire.write(
              stopping, new PtyMessage.FilesWrite(1, notes, version(stopping, notes), 1 << 20));
          PtyWire.write(stopping, new PtyMessage.PutChunk(1, 0, new byte[64 << 10]));
          assertEquals(new PtyMessage.PutAck(1, 64 << 10), PtyWire.read(stopping));
          assertTrue(shell.exec(List.of("incus", "stop", "--force", INSTANCE)).ok());
          assertEquals(-1, drain(stopping), "a stop ends the connection");
        }
        assertTrue(shell.exec(List.of("incus", "start", INSTANCE)).ok());
        var left = temporaries();
        assertTrue(!left.isEmpty(), "a stopped workspace may leave a temporary");
        inInstance("touch -d '-3 min' " + FOLDER + "/" + left);
        try (var after = open(host, "tok-ana")) {
          PtyWire.write(after, new PtyMessage.FilesList(1, FOLDER));
          var listing = assertInstanceOf(PtyMessage.FilesListing.class, next(after));
          assertTrue(
              listing.entries().stream().noneMatch(e -> e.name().startsWith(".sail-save-")),
              "a listing never shows it");
          send(
              after,
              2,
              FOLDER + "/next.md",
              PtyMessage.ABSENT,
              "x".getBytes(StandardCharsets.UTF_8));
          assertInstanceOf(PtyMessage.FilesWritten.class, next(after));
          assertEquals("", temporaries(), "the next save in its folder removed it");

          var notes = FOLDER + "/notes.md";
          var before = inInstance("sha256sum < " + notes);
          var bytes = "ana's again".getBytes(StandardCharsets.UTF_8);
          PtyWire.write(
              after, new PtyMessage.FilesWrite(3, notes, version(after, notes), bytes.length));
          PtyWire.write(after, new PtyMessage.PutChunk(3, 0, bytes));
          assertEquals(new PtyMessage.PutAck(3, bytes.length), PtyWire.read(after));
          assertTrue(
              shell
                  .exec(
                      List.of(
                          "incus",
                          "config",
                          "set",
                          INSTANCE,
                          BoxInstances.OWNER_KEY + "=ben",
                          BoxInstances.OWNER_ID_KEY + "=" + ben))
                  .ok());
          PtyWire.write(after, new PtyMessage.PutEnd(3, sha256(bytes)));
          var ended = assertInstanceOf(PtyMessage.Err.class, next(after));
          assertEquals("Workspace 'w' belongs to 'ben', not you.", ended.reason());
          assertTrue(!ended.code().isBlank(), "coded, so the app tells it apart");
          assertEquals(-1, drain(after));
          awaitNoScripts();
          assertEquals(before, inInstance("sha256sum < " + notes), "a relabel writes nothing");
          assertEquals("", temporaries());
        }
      }
    } finally {
      deleteContainerQuietly(INSTANCE);
      try {
        shell.exec(List.of("incus", "storage", "delete", POOL));
      } catch (Exception ignored) {
      }
    }
  }

  /** The bytes the instance's root disk holds, as Incus reports them in its state. */
  private long rootUsage() throws Exception {
    var state =
        shell.exec(List.of("incus", "query", "/1.0/instances/" + INSTANCE + "/state")).stdout();
    var usage =
        java.util.regex.Pattern.compile("\"root\":\\s*\\{[^}]*\"usage\":\\s*([0-9]+)")
            .matcher(state);
    assertTrue(usage.find(), "Incus's state names no root disk usage: " + state);
    return Long.parseLong(usage.group(1));
  }

  /** Reads what is left until the host closes the connection: -1 then. */
  private static int drain(SocketChannel channel) throws IOException {
    var buffer = ByteBuffer.allocate(64 << 10);
    int read;
    while ((read = channel.read(buffer)) > 0) {
      buffer.clear();
    }
    return read;
  }
}
