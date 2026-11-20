/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.pty;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.sail.common.Remedied;
import ai.singlr.sail.common.Remedy;
import ai.singlr.sail.common.Secrets;
import ai.singlr.sail.identity.Actor;
import ai.singlr.sail.identity.Role;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

/**
 * A workspace's files read through the terminal door: a {@code files} ticket decided as entering
 * the workspace and never for a stopped one, a connection it admits that lists, stats, reads and
 * watches and does nothing else, four scripts at once and two of them reads, and every way a
 * connection ends taking its scripts and its watch with it. The workspace is a directory here, each
 * script run by {@link FileScriptsTest#over}'s stand-in for {@code incus exec}.
 */
@EnabledOnOs(OS.LINUX)
@Timeout(value = 60, unit = TimeUnit.SECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
class FilesHostTest {

  @TempDir Path dir;
  private Path home;
  private PtySessionHost host;
  private final AtomicLong now = new AtomicLong(1_000_000_000L);
  private final Set<String> revoked = ConcurrentHashMap.newKeySet();
  private final Set<String> stopped = ConcurrentHashMap.newKeySet();
  private final Set<String> goneNow = ConcurrentHashMap.newKeySet();
  private final List<String> entered = new CopyOnWriteArrayList<>();

  /** The scripts that stall instead of running, as a frozen container's would. */
  private final Set<String> stall = ConcurrentHashMap.newKeySet();

  /** The scripts that exit 1 instead of running, as an exec into a stopped instance does. */
  private final Set<String> failing = ConcurrentHashMap.newKeySet();

  /** Each script a container was handed, by its kind, and its process, in the order started. */
  private final LinkedBlockingQueue<String> started = new LinkedBlockingQueue<>();

  private final List<Process> processes = new CopyOnWriteArrayList<>();
  private final List<List<String>> commands = new CopyOnWriteArrayList<>();

  private final PtyIdentity.Resolver resolver =
      token -> {
        if (revoked.contains(token)) {
          throw new IOException("Session token is not valid or has expired.");
        }
        return switch (token) {
          case "", "tok-uday" ->
              new PtyIdentity.Person(new Actor("uday", Role.MEMBER, Actor.Lane.API));
          case "tok-mady" -> new PtyIdentity.Person(new Actor("mady", Role.MEMBER, Actor.Lane.API));
          case "tok-root" -> new PtyIdentity.Person(new Actor("root", Role.ADMIN, Actor.Lane.API));
          default -> throw new IOException("Session token is not valid or has expired.");
        };
      };

  private final PtyIdentity.Holders holders =
      hash -> {
        for (var token : List.of("tok-uday", "tok-mady", "tok-root")) {
          if (Secrets.hash(token).equals(hash)) {
            return resolver.resolve(token);
          }
        }
        throw new IOException("The credential is not valid or has expired.");
      };

  private final PtyAdmission admission =
      new PtyAdmission() {
        @Override
        public void box(PtyIdentity.Person who) {}

        @Override
        public void project(String project, PtyIdentity.Person who) throws IOException {
          if (project.equals("beta")) {
            throw new IOException("Project 'beta' is not in the catalog.");
          }
        }

        @Override
        public void workspace(String project, String workspace, PtyIdentity.Person who)
            throws IOException {
          if (workspace.equals("gone") || goneNow.contains(workspace)) {
            throw new PtyAdmission.Absent(
                "Project '" + project + "' has no workspace '" + workspace + "' on this box.",
                Remedy.NONE);
          }
          if (workspace.equals("broken")) {
            throw new PtyAdmission.Unavailable(new IOException("incus list failed"));
          }
          if (!who.actor().isAdmin() && !who.fde().equals("mady")) {
            throw Remedied.io(
                "Workspace '" + workspace + "' belongs to 'mady', not you.", Remedy.NONE);
          }
        }

        @Override
        public void files(String project, String workspace, PtyIdentity.Person who)
            throws IOException {
          workspace(project, workspace, who);
          if (stopped.contains(workspace)) {
            throw new PtyAdmission.Stopped(
                "Workspace '" + workspace + "' of " + project + " is stopped.",
                Remedy.of(
                    "Start it with: ", Remedy.sail("project workspace start", project, workspace)));
          }
        }

        @Override
        public void enter(String project, String workspace, PtyIdentity.Person who)
            throws IOException {
          entered.add(workspace);
          workspace(project, workspace, who);
        }

        @Override
        public void room(String room, String project, PtyIdentity.Person who) {}
      };

  /** Files read in {@code home} as {@code acme-dock}, with this test's knobs. */
  private FileScripts files() {
    var place =
        new UploadCopy.Place(
            container -> List.of("incus", "exec", container, "--"),
            container -> List.of("incus", "exec", container, "--user", "1000", "--"),
            "/unused",
            "0",
            "1000",
            "1000",
            List.of(),
            Duration.ofSeconds(10));
    return new FileScripts(
        command -> {
          commands.add(List.copyOf(command));
          if (!command.get(2).equals(FileScriptsTest.CONTAINER)) {
            throw new IOException("Error: Instance not found");
          }
          var inner = new ArrayList<>(command.subList(command.indexOf("--") + 1, command.size()));
          var script = inner.get(2);
          var kind = kindOf(script);
          if (stall.contains(kind)) {
            inner.set(2, "exec sleep 30");
          } else if (failing.contains(kind)) {
            inner.set(2, "exit 1");
          }
          var process =
              new ProcessBuilder(inner).redirectError(ProcessBuilder.Redirect.DISCARD).start();
          processes.add(process);
          started.add(kind);
          return process;
        },
        place,
        home.toString());
  }

  private static String kindOf(String script) {
    if (script.equals(FileScripts.LIST)) {
      return "list";
    }
    if (script.equals(FileScripts.READ)) {
      return "read";
    }
    if (script.equals(FileScripts.STAT)) {
      return "stat";
    }
    return script.equals(FileScripts.WATCH) ? "watch" : "other";
  }

  @BeforeEach
  void setUp() throws IOException {
    home = Files.createDirectory(dir.toRealPath().resolve("home"));
    host =
        new PtySessionHost(
            dir.resolve("host.sock"),
            dir.resolve("sessions"),
            64 * 1024,
            resolver,
            admission,
            PtyEvents.NONE,
            "0.0.0-test",
            PtySessionHost.Limits.DEFAULTS,
            new PtySessionHost.Handoff(
                SdNotify.NONE,
                Map.of(),
                exit -> PtyChildShimTest.shim(exit, "sh", "-c", "read a", "--")),
            PtySessionHost.Network.listening(
                new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), now::get, holders),
            PtyUploads.intoContainers(),
            files());
    host.start();
  }

  @AfterEach
  void tearDown() {
    host.close();
    processes.forEach(Process::destroyForcibly);
  }

  private SocketChannel unix(String token) throws IOException {
    var channel = SocketChannel.open(StandardProtocolFamily.UNIX);
    channel.connect(UnixDomainSocketAddress.of(dir.resolve("host.sock")));
    PtyWire.handshake(channel, channel);
    PtyWire.write(channel, new PtyMessage.Hello(token));
    assertInstanceOf(PtyMessage.Welcome.class, PtyWire.read(channel));
    return channel;
  }

  /** What the host answers a files ticket for {@code credential} to {@code workspace} of acme. */
  private PtyMessage ticket(String credential, String project, String workspace)
      throws IOException {
    try (var box = unix("")) {
      PtyWire.write(
          box,
          new PtyMessage.IssueTicket(
              PtyMessage.TicketAction.FILES, project, credential, workspace));
      return PtyWire.read(box);
    }
  }

  /** A files connection of {@code credential} to workspace dock of acme, welcomed. */
  private SocketChannel open(String credential) throws IOException {
    var ticket = assertInstanceOf(PtyMessage.Ticket.class, ticket(credential, "acme", "dock"));
    var channel = SocketChannel.open(host.tcpAddress().orElseThrow());
    PtyWire.handshake(channel, channel);
    PtyWire.write(channel, new PtyMessage.Hello(ticket.ticket()));
    assertInstanceOf(PtyMessage.Welcome.class, PtyWire.read(channel));
    return channel;
  }

  /** The next frame that is not a tick. */
  private static PtyMessage next(SocketChannel channel) throws IOException {
    while (true) {
      var message = PtyWire.read(channel);
      if (!(message instanceof PtyMessage.FilesTick)) {
        return message;
      }
    }
  }

  private static String said(PtyMessage answer) {
    var refused = (PtyMessage.TicketRefused) answer;
    return refused.kind().wire() + " " + Remedied.message(refused.reason(), refused.remedy());
  }

  private static void ended(SocketChannel channel) throws IOException {
    assertEquals(-1, channel.read(ByteBuffer.allocate(1)), "the host closed the connection");
  }

  @Test
  void aFilesTicketIsDecidedAsEnteringTheWorkspaceNeverStartsOneAndNamesAWorkspace()
      throws Exception {
    stopped.add("idle");

    assertInstanceOf(PtyMessage.Ticket.class, ticket("tok-mady", "acme", "dock"));
    assertInstanceOf(PtyMessage.Ticket.class, ticket("tok-root", "acme", "dock"));
    assertEquals(
        "forbidden Workspace 'dock' belongs to 'mady', not you.",
        said(ticket("tok-uday", "acme", "dock")));
    assertEquals(
        "not_found Project 'acme' has no workspace 'gone' on this box.",
        said(ticket("tok-mady", "acme", "gone")));
    var refused = (PtyMessage.TicketRefused) ticket("tok-mady", "acme", "idle");
    assertEquals(
        "stopped Workspace 'idle' of acme is stopped. Start it with: sail project workspace start"
            + " acme idle",
        said(refused));
    assertEquals(
        List.of(
            new Remedy.Command(
                Remedy.Runs.SAIL, "project workspace start", List.of("acme", "idle"))),
        refused.remedy().commands(),
        "the remedy that starts it, typed");
    assertEquals(
        "unavailable The runner could not read its workspaces, so no terminal was opened. An admin"
            + " looks at the runner.",
        said(ticket("tok-mady", "acme", "broken")));
    assertEquals(
        "not_found Project 'beta' is not in the catalog.",
        said(ticket("tok-mady", "beta", "dock")));
    for (var container : List.of("", "main")) {
      assertEquals(
          "invalid A files ticket names a workspace of the project: the project's own container"
              + " has none of a person's files to read.",
          said(ticket("tok-mady", "acme", container)));
    }
    assertEquals(List.of(), entered, "a files ticket enters nothing, so starts nothing");
    assertEquals(List.of(), commands, "and runs nothing in a workspace");
  }

  @Test
  void aConnectionItAdmitsListsStatsAndReadsItsWorkspaceAndNothingElse() throws Exception {
    var src = Files.createDirectory(home.resolve("src"));
    Files.writeString(src.resolve("App.java"), "class App {}");
    var bytes = new byte[600_000];
    Arrays.fill(bytes, (byte) 7);
    bytes[599_999] = 9;
    var blob = Files.write(home.resolve("blob"), bytes);

    try (var files = open("tok-mady")) {
      PtyWire.write(files, new PtyMessage.FilesList(1, src.toString()));
      var listing = assertInstanceOf(PtyMessage.FilesListing.class, next(files));
      assertEquals(1, listing.id());
      assertEquals(List.of("App.java"), listing.entries().stream().map(e -> e.name()).toList());
      assertFalse(listing.more());
      assertFalse(listing.truncated());

      PtyWire.write(files, new PtyMessage.FilesStat(2, src.resolve("App.java").toString()));
      var entry = assertInstanceOf(PtyMessage.FilesEntry.class, next(files));
      assertEquals(12, entry.entry().size());

      PtyWire.write(files, new PtyMessage.FilesRead(3, blob.toString(), 10, 1L << 30));
      var read = new java.io.ByteArrayOutputStream();
      var frames = 0;
      while (true) {
        var data = assertInstanceOf(PtyMessage.FilesData.class, next(files));
        frames++;
        read.write(data.bytes());
        if (data.eof()) {
          break;
        }
        assertEquals(PtyMessage.FILE_CHUNK, data.bytes().length);
      }
      assertEquals(3, frames, "256 KiB frames");
      assertArrayEquals(Arrays.copyOfRange(bytes, 10, bytes.length), read.toByteArray());

      PtyWire.write(files, new PtyMessage.FilesRead(4, blob.toString(), 0, 5));
      var head = assertInstanceOf(PtyMessage.FilesData.class, next(files));
      assertEquals(5, head.bytes().length);
      assertFalse(head.eof(), "five bytes of 600,000 are not its end");

      PtyWire.write(files, new PtyMessage.FilesRead(5, home.resolve("gone.txt").toString(), 0, 5));
      assertEquals(
          new PtyMessage.FilesRefused(
              5, PtyMessage.FilesRefusal.NOT_FOUND, "No file at " + home + "/gone.txt in dock."),
          next(files));
      var before = commands.size();
      PtyWire.write(files, new PtyMessage.FilesList(6, "/etc"));
      assertEquals(
          new PtyMessage.FilesRefused(
              6,
              PtyMessage.FilesRefusal.OUTSIDE_HOME,
              "/etc is outside dock's home, " + home + "; Sail does not go there."),
          next(files));
      PtyWire.write(files, new PtyMessage.FilesRead(7, blob.toString(), -1, 5));
      assertEquals(PtyMessage.FilesRefusal.FAILED, ((PtyMessage.FilesRefused) next(files)).kind());
      assertEquals(before, commands.size(), "nothing is run for a path outside the home");

      PtyWire.write(
          files, new PtyMessage.Create("s", List.of(), "/tmp", "acme", "", 80, 24, "dock"));
      assertEquals(
          new PtyMessage.Err(
              "This connection's ticket is to read the files of workspace 'dock' of project"
                  + " 'acme', and it admits nothing else; ask the API for a ticket for that."),
          next(files));
      ended(files);
    }
  }

  @Test
  void aFolderPastFiftyThousandIsListedInFramesAndCutSayingSo() throws Exception {
    var big = Files.createDirectory(home.resolve("big"));
    for (var i = 0; i <= PtyMessage.LISTING_CAP; i++) {
      Files.createFile(big.resolve("generated-source-file-" + i + ".java"));
    }
    try (var files = open("tok-mady")) {
      PtyWire.write(files, new PtyMessage.FilesList(1, big.toString()));
      var count = 0;
      var frames = 0;
      PtyMessage.FilesListing last;
      do {
        last = assertInstanceOf(PtyMessage.FilesListing.class, next(files));
        frames++;
        count += last.entries().size();
        assertFalse(last.more() && last.truncated(), "only the last frame says it was cut");
      } while (last.more());
      assertEquals(PtyMessage.LISTING_CAP, count);
      assertTrue(last.truncated());
      assertTrue(frames > 1, "continued past its frame budget");
    }
  }

  @Test
  void aWatchTicksEachSecondAndSaysWhichShownPathChanged() throws Exception {
    var src = Files.createDirectory(home.resolve("src"));
    try (var files = open("tok-mady")) {
      PtyWire.write(files, new PtyMessage.FilesStat(1, src.toString()));
      var version = ((PtyMessage.FilesEntry) next(files)).version();
      PtyWire.write(
          files,
          new PtyMessage.FilesWatch(List.of(new PtyMessage.Watched(src.toString(), version))));
      assertInstanceOf(PtyMessage.FilesTick.class, PtyWire.read(files));
      Files.createFile(src.resolve("New.java"));
      assertEquals(new PtyMessage.FilesChanged(List.of(src.toString())), next(files));

      var many = new ArrayList<PtyMessage.Watched>();
      for (var i = 0; i <= FilesConnection.WATCHED; i++) {
        many.add(new PtyMessage.Watched(src + "/f" + i, ""));
      }
      PtyWire.write(files, new PtyMessage.FilesWatch(many));
      assertEquals(
          new PtyMessage.Err(
              "A watch holds at most 1024 paths and this one held 1025, so the watch is as it was."),
          next(files));
      PtyWire.write(
          files, new PtyMessage.FilesWatch(List.of(new PtyMessage.Watched("/etc/hosts", ""))));
      assertEquals(
          new PtyMessage.Err(
              "/etc/hosts is not in this workspace's home, so the watch is as it was."),
          next(files));
      Files.delete(src.resolve("New.java"));
      assertEquals(
          new PtyMessage.FilesChanged(List.of(src.toString())),
          next(files),
          "the set it had is still watched");
      assertEquals(1, started.stream().filter("watch"::equals).count(), "one loop a connection");
    }
  }

  @Test
  void fourScriptsRunAtOnceTwoOfThemReadsTheRestInTheOrderAsked() throws Exception {
    stall.add("read");
    stall.add("list");
    var file = Files.writeString(home.resolve("f"), "x");
    try (var files = open("tok-mady")) {
      for (var id = 1; id <= 3; id++) {
        PtyWire.write(files, new PtyMessage.FilesRead(id, file.toString(), 0, 1));
      }
      for (var id = 4; id <= 6; id++) {
        PtyWire.write(files, new PtyMessage.FilesList(id, home.toString()));
      }
      assertEquals(List.of("list", "list", "read", "read"), take(4).stream().sorted().toList());
      assertTrue(started.poll(300, TimeUnit.MILLISECONDS) == null, "no fifth runs");

      PtyWire.write(files, new PtyMessage.FilesCancel(1));
      assertEquals(List.of("read"), take(1), "the read asked before the last list goes first");
      assertTrue(
          processes.get(0).waitFor(5, TimeUnit.SECONDS), "the cancelled read's script ended");
      PtyWire.write(files, new PtyMessage.FilesCancel(4));
      assertEquals(List.of("list"), take(1));

      PtyWire.write(files, new PtyMessage.FilesList(5, home.toString()));
      assertEquals(
          new PtyMessage.FilesRefused(
              5,
              PtyMessage.FilesRefusal.FAILED,
              "Request 5 is under way already, so this one was not started."),
          next(files));
    }
  }

  private List<String> take(int count) throws InterruptedException {
    var taken = new ArrayList<String>();
    for (var i = 0; i < count; i++) {
      var kind = started.poll(10, TimeUnit.SECONDS);
      assertNotNull(kind, "script " + (i + 1) + " of " + count + " started");
      taken.add(kind);
    }
    return taken;
  }

  /** Waits until every script and loop a container was handed has ended. */
  private void everyScriptEnds() throws InterruptedException {
    for (var process : processes) {
      assertTrue(process.waitFor(5, TimeUnit.SECONDS), "a script outlived its connection");
    }
  }

  @Test
  void closingTheConnectionMidReadAndMidWatchEndsEveryScriptWithinFiveSeconds() throws Exception {
    stall.add("read");
    var file = Files.writeString(home.resolve("f"), "x");
    var files = open("tok-mady");
    PtyWire.write(files, new PtyMessage.FilesRead(1, file.toString(), 0, 1));
    PtyWire.write(
        files, new PtyMessage.FilesWatch(List.of(new PtyMessage.Watched(home.toString(), ""))));
    PtyWire.write(files, new PtyMessage.FilesRead(2, file.toString(), 0, 1));
    PtyWire.write(files, new PtyMessage.FilesRead(3, file.toString(), 0, 1));
    assertEquals(Set.of("read", "watch"), Set.copyOf(take(3)));
    files.close();
    everyScriptEnds();
    assertTrue(started.poll(300, TimeUnit.MILLISECONDS) == null, "the waiting read never runs");
  }

  @Test
  void aRevokedCredentialLosesItsFilesConnectionAndItsScriptsAtTheNextSweep() throws Exception {
    stall.add("read");
    var file = Files.writeString(home.resolve("f"), "x");
    try (var files = open("tok-mady")) {
      PtyWire.write(files, new PtyMessage.FilesRead(1, file.toString(), 0, 1));
      take(1);
      revoked.add("tok-mady");
      host.sweep(now.get());
      ended(files);
      everyScriptEnds();
    }
  }

  @Test
  void aStopEndsTheWorkspacesFilesConnectionsInItsSentenceAndItsSessionsToo() throws Exception {
    try (var mady = unix("tok-mady")) {
      PtyWire.write(
          mady, new PtyMessage.Create("in-dock", List.of(), "/tmp", "acme", "", 80, 24, "dock"));
      assertInstanceOf(PtyMessage.Ok.class, PtyWire.read(mady));
      PtyWire.write(
          mady, new PtyMessage.Create("in-spare", List.of(), "/tmp", "acme", "", 80, 24, "spare"));
      assertInstanceOf(PtyMessage.Ok.class, PtyWire.read(mady));
    }
    try (var files = open("tok-mady")) {
      PtyWire.write(
          files, new PtyMessage.FilesWatch(List.of(new PtyMessage.Watched(home.toString(), ""))));
      assertEquals(new PtyMessage.FilesChanged(List.of(home.toString())), PtyWire.read(files));
      try (var member = unix("tok-mady")) {
        PtyWire.write(member, new PtyMessage.YieldWorkspace("acme", "dock", "no", "no"));
        assertEquals(
            new PtyMessage.Err("Yield requires host dispatch authority."), PtyWire.read(member));
      }
      var dispatch =
          Files.readString(PtySessionHost.dispatchCredentialOf(dir.resolve("host.sock"))).strip();
      try (var authority = unix(dispatch)) {
        PtyWire.write(
            authority,
            new PtyMessage.YieldWorkspace(
                "acme",
                "dock",
                "Workspace dock of acme is being stopped by mady, which ends this terminal.",
                "Workspace dock of acme was stopped by mady."));
        assertInstanceOf(PtyMessage.Ok.class, PtyWire.read(authority));
      }
      assertEquals(new PtyMessage.Err("Workspace dock of acme was stopped by mady."), next(files));
      ended(files);
      everyScriptEnds();
    }
    try (var mady = unix("tok-mady")) {
      PtyWire.write(mady, new PtyMessage.ListSessions("", PtyMessage.PAGE_LIMIT));
      var listed = (PtyMessage.Sessions) PtyWire.read(mady);
      var byName = new java.util.HashMap<String, PtyMessage.SessionInfo>();
      listed.sessions().forEach(info -> byName.put(info.name(), info));
      assertFalse(byName.get("in-dock").live());
      assertEquals(
          "Workspace dock of acme was stopped by mady.", byName.get("in-dock").endedReason());
      assertTrue(byName.get("in-spare").live(), "another workspace's session lives on");
    }
  }

  @Test
  void aScriptThatFailsIsRefusedAsFailedUnlessTheWorkspaceStoppedOrWentWhichEndsTheConnection()
      throws Exception {
    failing.add("list");
    try (var files = open("tok-mady")) {
      PtyWire.write(files, new PtyMessage.FilesList(1, home.toString()));
      assertEquals(
          new PtyMessage.FilesRefused(
              1,
              PtyMessage.FilesRefusal.FAILED,
              "Sail could not read " + home + " in dock; nothing was changed."),
          next(files));
      stopped.add("dock");
      PtyWire.write(files, new PtyMessage.FilesList(2, home.toString()));
      var why = assertInstanceOf(PtyMessage.Err.class, next(files));
      assertEquals("workspace_stopped", why.code());
      assertEquals("Workspace 'dock' of acme is stopped.", why.reason());
      ended(files);
    }
    stopped.clear();
    try (var files = open("tok-mady")) {
      goneNow.add("dock");
      PtyWire.write(files, new PtyMessage.FilesList(1, home.toString()));
      var why = assertInstanceOf(PtyMessage.Err.class, next(files));
      assertEquals("not_found", why.code());
      ended(files);
    }
  }

  @Test
  void aWatchThatEndsUnaskedEndsTheConnectionSayingWhy() throws Exception {
    try (var files = open("tok-mady")) {
      PtyWire.write(files, new PtyMessage.FilesWatch(List.of()));
      assertInstanceOf(PtyMessage.FilesTick.class, PtyWire.read(files));
      stopped.add("dock");
      processes.getLast().destroyForcibly();
      var why = assertInstanceOf(PtyMessage.Err.class, next(files));
      assertEquals("workspace_stopped", why.code());
      ended(files);
    }
    stopped.clear();
    try (var files = open("tok-mady")) {
      PtyWire.write(files, new PtyMessage.FilesWatch(List.of()));
      assertInstanceOf(PtyMessage.FilesTick.class, PtyWire.read(files));
      processes.getLast().destroyForcibly();
      assertEquals(
          new PtyMessage.Err("The watch of dock's files stopped; connect again to watch them."),
          next(files));
      ended(files);
    }
  }

  @Test
  void aConnectionNoFilesTicketAdmittedReadsNoFiles() throws Exception {
    try (var box = unix("")) {
      PtyWire.write(box, new PtyMessage.FilesList(1, home.toString()));
      assertEquals(
          new PtyMessage.Err(
              "Files are read on a connection a files ticket admitted, and this is not one."),
          PtyWire.read(box));
    }
    assertEquals(List.of(), commands);
  }
}
