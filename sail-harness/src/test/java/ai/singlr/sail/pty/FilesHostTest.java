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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
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
import java.util.HexFormat;
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

  /** Workspaces relabelled for another person, which mady may no longer enter. */
  private final Set<String> relabelled = ConcurrentHashMap.newKeySet();

  /** Workspaces Incus cannot say anything of now. */
  private final Set<String> brokenNow = ConcurrentHashMap.newKeySet();

  /** Each script's text as this test changes it, by its kind, as a racing writer would. */
  private final Map<String, java.util.function.UnaryOperator<String>> edits =
      new ConcurrentHashMap<>();

  private final List<String> entered = new CopyOnWriteArrayList<>();

  /** The scripts that stall instead of running, as a frozen container's would. */
  private final Set<String> stall = ConcurrentHashMap.newKeySet();

  /** The scripts that exit 1 instead of running, as an exec into a stopped instance does. */
  private final Set<String> failing = ConcurrentHashMap.newKeySet();

  /** Each script a container was handed, by its kind, and its process, in the order started. */
  private final LinkedBlockingQueue<String> started = new LinkedBlockingQueue<>();

  private final List<Process> processes = new CopyOnWriteArrayList<>();

  /** The save scripts among {@link #processes}, in the order started. */
  private final List<Process> saves = new CopyOnWriteArrayList<>();

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
          if (workspace.equals("broken") || brokenNow.contains(workspace)) {
            throw new PtyAdmission.Unavailable(new IOException("incus list failed"));
          }
          if (!who.actor().isAdmin() && relabelled.contains(workspace)) {
            throw Remedied.io(
                "Workspace '" + workspace + "' belongs to 'uday', not you.", Remedy.NONE);
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
          var at = inner.indexOf("-c") + 1;
          var script = inner.get(at);
          var kind = kindOf(script);
          if (edits.containsKey(kind)) {
            inner.set(at, edits.get(kind).apply(script));
          }
          if (stall.contains(kind)) {
            inner.set(at, "exec sleep 30");
          } else if (failing.contains(kind)) {
            inner.set(at, "exit 1");
          }
          var process =
              new ProcessBuilder(FileScriptsTest.onASocket(inner))
                  .redirectError(ProcessBuilder.Redirect.DISCARD)
                  .start();
          processes.add(process);
          if (kind.equals("write")) {
            saves.add(process);
          }
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
    if (script.equals(FileScripts.WRITE)) {
      return "write";
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
    return open(credential, "dock");
  }

  /** A files connection of {@code credential} to {@code workspace} of acme, welcomed. */
  private SocketChannel open(String credential, String workspace) throws IOException {
    var channel = present(credential, workspace);
    assertInstanceOf(PtyMessage.Welcome.class, PtyWire.read(channel));
    return channel;
  }

  /** A files ticket of {@code credential} to {@code workspace} of acme, presented. */
  private SocketChannel present(String credential, String workspace) throws IOException {
    return present(
        assertInstanceOf(PtyMessage.Ticket.class, ticket(credential, "acme", workspace)));
  }

  private SocketChannel present(PtyMessage.Ticket ticket) throws IOException {
    var channel = SocketChannel.open(host.tcpAddress().orElseThrow());
    PtyWire.handshake(channel, channel);
    PtyWire.write(channel, new PtyMessage.Hello(ticket.ticket()));
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
              "This connection's ticket is to read and change the files of workspace 'dock' of"
                  + " project 'acme', and it admits nothing else; ask the API for a ticket for"
                  + " that."),
          next(files));
      ended(files);
    }
  }

  /**
   * A listing costs one exec, whatever the folder holds: three listings of 5,000 entries run three
   * list scripts and nothing else, no exec per entry, each answered by one set of frames of its
   * own.
   */
  @Test
  void aListingIsOneExecWhateverItsFolderHolds() throws Exception {
    var big = Files.createDirectory(home.resolve("big"));
    for (var i = 0; i < 5_000; i++) {
      Files.createFile(big.resolve("src-" + i + ".java"));
    }
    try (var files = open("tok-mady")) {
      for (var id = 1; id <= 3; id++) {
        PtyWire.write(files, new PtyMessage.FilesList(id, big.toString()));
        var count = 0;
        PtyMessage.FilesListing listing;
        do {
          listing = assertInstanceOf(PtyMessage.FilesListing.class, next(files));
          assertEquals(id, listing.id());
          count += listing.entries().size();
        } while (listing.more());
        assertEquals(5_000, count);
      }
    }
    assertEquals(List.of("list", "list", "list"), List.copyOf(started));
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

  /**
   * F1's window: a change made after a folder was listed and before its watch took it is reported
   * at the watch's first tick, since the watch starts from the listing's version; a folder
   * unchanged since its listing is not.
   */
  @Test
  void aChangeBetweenAListingAndItsWatchIsReportedAndNoneIsNot() throws Exception {
    var src = Files.createDirectory(home.resolve("src"));
    var docs = Files.createDirectory(home.resolve("docs"));
    try (var files = open("tok-mady")) {
      PtyWire.write(files, new PtyMessage.FilesList(1, src.toString()));
      var listed = assertInstanceOf(PtyMessage.FilesListing.class, next(files));
      PtyWire.write(files, new PtyMessage.FilesList(2, docs.toString()));
      var quiet = assertInstanceOf(PtyMessage.FilesListing.class, next(files));
      Files.createFile(src.resolve("Late.java"));

      PtyWire.write(
          files,
          new PtyMessage.FilesWatch(
              List.of(
                  new PtyMessage.Watched(src.toString(), listed.version()),
                  new PtyMessage.Watched(docs.toString(), quiet.version()))));

      assertEquals(new PtyMessage.FilesChanged(List.of(src.toString())), next(files));
      assertInstanceOf(PtyMessage.FilesTick.class, PtyWire.read(files));
      assertInstanceOf(PtyMessage.FilesTick.class, PtyWire.read(files), "docs never changed");
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
      for (var asked :
          List.<PtyMessage>of(
              new PtyMessage.FilesList(1, home.toString()),
              new PtyMessage.FilesChanges(2),
              new PtyMessage.FilesDiff(3, home + "/a.md"))) {
        PtyWire.write(box, asked);
        assertEquals(
            new PtyMessage.Err(
                "Files are read on a connection a files ticket admitted, and this is not one."),
            PtyWire.read(box));
      }
    }
    assertEquals(List.of(), commands);
  }

  /** The reason and code the host ended {@code channel} with, which it then closed. */
  private static List<String> endedWith(SocketChannel channel) throws IOException {
    var why = assertInstanceOf(PtyMessage.Err.class, next(channel));
    ended(channel);
    return List.of(why.code(), why.reason());
  }

  /**
   * m1: every sweep asks the workspace again, as its ticket did: a connection whose person no
   * longer enters it (a re-own, a disown, a hand-made relabel) ends saying so, coded, as one whose
   * workspace went or stopped does; one the runner cannot read now goes on, an outage not a change.
   */
  @Test
  void everySweepAsksTheWorkspaceAgainAndEndsAConnectionItNoLongerAdmits() throws Exception {
    var relabel = open("tok-mady");
    var gone = open("tok-mady", "spare");
    var idle = open("tok-mady", "idle");
    var unread = open("tok-mady", "lost");
    var staying = open("tok-root");
    relabelled.add("dock");
    goneNow.add("spare");
    stopped.add("idle");
    brokenNow.add("lost");

    host.sweep(now.get());

    assertEquals(
        List.of("forbidden", "Workspace 'dock' belongs to 'uday', not you."), endedWith(relabel));
    assertEquals(
        List.of("not_found", "Project 'acme' has no workspace 'spare' on this box."),
        endedWith(gone));
    assertEquals("workspace_stopped", endedWith(idle).getFirst());
    PtyWire.write(unread, new PtyMessage.FilesStat(1, home.toString()));
    assertEquals(
        PtyMessage.FilesRefusal.FAILED,
        assertInstanceOf(PtyMessage.FilesRefused.class, next(unread)).kind(),
        "an outage ends nothing: the connection still answers");
    PtyWire.write(staying, new PtyMessage.FilesStat(1, home.toString()));
    assertInstanceOf(PtyMessage.FilesEntry.class, next(staying), "an admin still enters it");
    unread.close();
    staying.close();
  }

  /** Nit 3: a handover ends every files connection, saying so in words an app reconnects on. */
  @Test
  void aHandoverEndsEveryFilesConnectionSayingSo() throws Exception {
    var files = open("tok-mady");
    PtyWire.write(files, new PtyMessage.FilesWatch(List.of()));
    assertInstanceOf(PtyMessage.FilesTick.class, PtyWire.read(files));
    host.handoff();
    assertEquals(
        List.of(
            "terminal_unavailable",
            "The runner's terminal host is restarting, which ends this connection to dock's files;"
                + " connect again."),
        endedWith(files));
    everyScriptEnds();
  }

  @Test
  void aStopOfTheHostEndsEveryFilesConnectionSayingSo() throws Exception {
    var files = open("tok-mady");
    host.stop("the host stops");
    assertEquals("terminal_unavailable", endedWith(files).getFirst());
  }

  /**
   * Nit 4: a workspace holds at most 8 files connections and a person 16, all workspaces together,
   * and one person at most 6 of a workspace's, so the owner and an admin never shut each other out:
   * the ticket past them is refused, and so is one presented past them, in true words.
   */
  @Test
  void aWorkspaceHoldsEightFilesConnectionsAPersonSixOfThemAndSixteenInAll() throws Exception {
    var held = new ArrayList<SocketChannel>();
    for (var i = 0; i < 6; i++) {
      held.add(open("tok-mady"));
    }
    assertEquals(
        "limited mady has 6 files connections open in dock, the most one person may hold there, so"
            + " others can still connect; close one, then ask again.",
        said(ticket("tok-mady", "acme", "dock")));
    held.add(open("tok-root"));
    var late = assertInstanceOf(PtyMessage.Ticket.class, ticket("tok-root", "acme", "dock"));
    held.add(open("tok-root"));
    assertEquals(
        "limited dock has 8 files connections open, the most a workspace holds; close one, then"
            + " ask again.",
        said(ticket("tok-root", "acme", "dock")));
    var presented = present(late);
    var why = assertInstanceOf(PtyMessage.Err.class, PtyWire.read(presented));
    assertEquals(
        List.of(
            "rate_limited",
            "dock has 8 files connections open, the most a workspace holds; close one, then ask"
                + " again."),
        List.of(why.code(), why.reason()));
    ended(presented);
    for (var i = 0; i < 6; i++) {
      held.add(open("tok-mady", "spare"));
    }
    for (var i = 0; i < 4; i++) {
      held.add(open("tok-mady", "third"));
    }
    assertEquals(
        "limited mady has 16 files connections open, the most a person holds; close one, then"
            + " ask again.",
        said(ticket("tok-mady", "acme", "fourth")));
    for (var channel : held) {
      channel.close();
    }
  }

  /** m4: one read takes at most 20 MiB, however much it asks for. */
  @Test
  void aReadTakesAtMostTwentyMebibytes() throws Exception {
    var file = home.resolve("big.bin");
    try (var out = Files.newOutputStream(file)) {
      out.write(new byte[30 << 20]);
    }
    try (var files = open("tok-mady")) {
      PtyWire.write(files, new PtyMessage.FilesRead(1, file.toString(), 0, 64L << 20));
      var total = 0L;
      PtyMessage.FilesData data;
      do {
        data = assertInstanceOf(PtyMessage.FilesData.class, next(files));
        total += data.bytes().length;
      } while (!data.eof() && total < (20L << 20));
      assertEquals(20L << 20, total);
      assertFalse(data.eof(), "20 MiB of 30 is not the file's end");
      PtyWire.write(files, new PtyMessage.FilesStat(2, file.toString()));
      assertInstanceOf(
          PtyMessage.FilesEntry.class, next(files), "and the read sent nothing past 20 MiB");
    }
  }

  /**
   * m4: past 64 waiting requests the connection's next frame waits to be read: a cancel sent after
   * the 69th request is not heard until a running script ends.
   */
  @Test
  void pastSixtyFourWaitingTheNextFrameWaitsToBeRead() throws Exception {
    stall.add("list");
    try (var files = open("tok-mady")) {
      for (var id = 1; id <= 4 + FilesConnection.WAITING + 1; id++) {
        PtyWire.write(files, new PtyMessage.FilesList(id, home.toString()));
      }
      take(4);
      var running = List.copyOf(processes);
      for (var id = 1; id <= 4; id++) {
        PtyWire.write(files, new PtyMessage.FilesCancel(id));
      }
      assertFalse(
          running.getFirst().waitFor(1, TimeUnit.SECONDS),
          "the cancels wait behind the 69th request");
      running.getLast().destroyForcibly();
      for (var process : running) {
        assertTrue(process.waitFor(10, TimeUnit.SECONDS), "and are heard once one ended");
      }
    }
  }

  /**
   * m2, deterministically: a file written after its bytes were read and before the read ended is
   * answered {@code changed}, never with bytes under a version they do not match.
   */
  @Test
  void aReadOfAFileWrittenWhileItWasReadIsAnsweredChanged() throws Exception {
    var file = Files.writeString(home.resolve("notes.md"), "hello");
    edits.put("read", script -> script.replace(READ_DONE, READ_DONE + "printf x >> \"$at\"\n"));
    try (var files = open("tok-mady")) {
      PtyWire.write(files, new PtyMessage.FilesRead(1, file.toString(), 0, 100));
      assertEquals(
          new PtyMessage.FilesRefused(
              1,
              PtyMessage.FilesRefusal.CHANGED,
              file + " changed while it was read; read it again."),
          next(files));
    }
  }

  /** Where a read's bytes have been written and its version is stat-ed again. */
  private static final String READ_DONE = ">&3; } 4>&1 ) || exit 1\n";

  /** The sent bytes' pass of the read script, which a test may split around a writer. */
  private static final String SENT =
      "sent=$( { dd if=\"$at\" iflag=skip_bytes,count_bytes,nonblock skip=\"$2\" count=\"$3\""
          + " bs=262144 status=none |";

  /** The read script's second stat, which a test may make the first's, as a coarse clock would. */
  private static final String AFTER = "after=$(stat -L --printf ";

  /**
   * A read split at half its bytes: the script tells the test on {@code paused}, a pipe, once it
   * sent the first half, waits on {@code go}, then sends the rest; and its second stat says the
   * first's, as two stats inside one write's tick of a coarse clock do.
   */
  private void splitRead(Path paused, Path go) throws Exception {
    for (var pipe : List.of(paused, go)) {
      assertEquals(0, new ProcessBuilder("mkfifo", pipe.toString()).start().waitFor());
    }
    edits.put(
        "read",
        script ->
            script
                .replace(
                    SENT,
                    "sent=$( { { head -c 524288 \"$at\"; printf p > '"
                        + paused
                        + "'; read g < '"
                        + go
                        + "'; tail -c +524289 \"$at\"; } |")
                .replaceFirst(
                    java.util.regex.Pattern.quote(AFTER) + "[^\n]*\n", "after=\\$before\n"));
  }

  /**
   * m2 by content: a read spliced across a write, its first half sent before a writer rewrote the
   * file in place and its rest after, with its two stats equal as a coarse clock makes them, is
   * answered {@code changed}: the bytes sent hash otherwise than the file after them.
   */
  @Test
  void aReadSplicedAcrossAWriteIsAnsweredChangedThoughItsStatsAgree() throws Exception {
    var file = Files.write(home.resolve("racing.txt"), filled('a'));
    var paused = dir.resolve("paused");
    var go = dir.resolve("go");
    splitRead(paused, go);
    try (var files = open("tok-mady")) {
      PtyWire.write(files, new PtyMessage.FilesRead(1, file.toString(), 0, 4L << 20));
      try (var told = Files.newInputStream(paused)) {
        assertEquals('p', told.read());
      }
      try (var channel =
          java.nio.channels.FileChannel.open(file, java.nio.file.StandardOpenOption.WRITE)) {
        channel.write(ByteBuffer.wrap(filled('b')), 0);
      }
      Files.writeString(go, "g\n");
      PtyMessage answer;
      do {
        answer = next(files);
      } while (answer instanceof PtyMessage.FilesData data && !data.eof());
      assertEquals(
          new PtyMessage.FilesRefused(
              1,
              PtyMessage.FilesRefusal.CHANGED,
              file + " changed while it was read; read it again."),
          answer,
          "half of one write and half of the next, answered whole, would be a torn read");
    }
  }

  /** A read no writer touched is answered whole, its last frame carrying its bytes' digest. */
  @Test
  void aWholeReadEndsWithTheDigestOfItsBytes() throws Exception {
    var file = Files.write(home.resolve("still.txt"), filled('a'));
    var paused = dir.resolve("paused");
    var go = dir.resolve("go");
    splitRead(paused, go);
    try (var files = open("tok-mady")) {
      PtyWire.write(files, new PtyMessage.FilesRead(1, file.toString(), 0, 4L << 20));
      try (var told = Files.newInputStream(paused)) {
        assertEquals('p', told.read());
      }
      Files.writeString(go, "g\n");
      var read = new java.io.ByteArrayOutputStream();
      PtyMessage.FilesData data;
      do {
        data = assertInstanceOf(PtyMessage.FilesData.class, next(files));
        read.write(data.bytes());
        assertTrue(data.eof() || data.digest().isEmpty(), "only the last frame says a digest");
      } while (!data.eof());
      assertArrayEquals(filled('a'), read.toByteArray());
      assertEquals(
          HexFormat.of()
              .formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(filled('a'))),
          data.digest());
    }
  }

  private static byte[] filled(char c) {
    var bytes = new byte[1 << 20];
    Arrays.fill(bytes, (byte) c);
    return bytes;
  }

  /**
   * Nit 1: a pipe swapped in for a file after the read's checks is read without waiting for a
   * writer, and the read answered.
   */
  @Test
  void aPipeSwappedInMidReadNeverLeavesTheReadWaiting() throws Exception {
    var file = Files.writeString(home.resolve("notes.md"), "hello");
    edits.put("read", script -> script.replace("dd if=", "rm -f \"$at\"; mkfifo \"$at\"\ndd if="));
    try (var files = open("tok-mady")) {
      PtyWire.write(files, new PtyMessage.FilesRead(1, file.toString(), 0, 100));
      assertEquals(PtyMessage.FilesRefusal.CHANGED, ((PtyMessage.FilesRefused) next(files)).kind());
    }
  }

  /** A watch Incus cannot start ends the connection saying so, never silently. */
  @Test
  void aWatchThatCannotStartEndsTheConnectionSayingSo() throws Exception {
    var files = open("tok-mady", "elsewhere");
    PtyWire.write(files, new PtyMessage.FilesWatch(List.of()));
    assertEquals(
        List.of("", "The watch of elsewhere's files could not start; connect again to watch them."),
        endedWith(files));
  }

  private static byte[] sha256(byte[] bytes) throws Exception {
    return java.security.MessageDigest.getInstance("SHA-256").digest(bytes);
  }

  /** The version a stat of {@code path} answers on {@code channel}. */
  private static String versionOf(SocketChannel channel, Path path) throws IOException {
    PtyWire.write(channel, new PtyMessage.FilesStat(99, path.toString()));
    return assertInstanceOf(PtyMessage.FilesEntry.class, next(channel)).version();
  }

  /** A save's base as a client takes it: the digest of a whole read, and the version it read at. */
  private record Base(String digest, String seen) {}

  private static Base baseOf(SocketChannel channel, Path path) throws IOException {
    PtyWire.write(
        channel, new PtyMessage.FilesRead(98, path.toString(), 0, FilesConnection.READ_CAP));
    PtyMessage.FilesData data;
    do {
      data = assertInstanceOf(PtyMessage.FilesData.class, next(channel));
    } while (!data.eof());
    assertTrue(data.whole(), "a base is a whole read's");
    return new Base(data.digest(), data.version());
  }

  /** Save {@code id} of {@code size} bytes over {@code file} on {@code base}. */
  private static PtyMessage.FilesWrite onBase(int id, Path file, Base base, long size) {
    return new PtyMessage.FilesWrite(id, file.toString(), base.digest(), base.seen(), size);
  }

  /**
   * A files ticket's connection changes its workspace's files: a save's bytes travel as an
   * upload's, each acknowledged, and it is answered the version the file has now; a create, a move
   * and a remove are each answered done. A connection no files ticket admitted changes nothing.
   */
  @Test
  void aConnectionItAdmitsSavesMakesMovesAndRemovesItsWorkspacesFiles() throws Exception {
    var file = Files.writeString(home.resolve("notes.md"), "old");
    try (var files = open("tok-mady")) {
      var bytes = "new words".getBytes(java.nio.charset.StandardCharsets.UTF_8);
      PtyWire.write(files, onBase(1, file, baseOf(files, file), bytes.length));
      PtyWire.write(files, new PtyMessage.PutChunk(1, 0, bytes));
      PtyWire.write(files, new PtyMessage.PutEnd(1, sha256(bytes)));
      assertEquals(new PtyMessage.PutAck(1, bytes.length), next(files));
      var written = assertInstanceOf(PtyMessage.FilesWritten.class, next(files));
      assertEquals(versionOf(files, file), written.version());
      assertEquals(baseOf(files, file).digest(), written.digest(), "the new content's digest");
      assertEquals("new words", Files.readString(file));

      PtyWire.write(files, new PtyMessage.FilesCreate(2, home + "/docs", true));
      assertEquals(new PtyMessage.FilesDone(2), next(files));
      PtyWire.write(files, new PtyMessage.FilesMove(3, file.toString(), home + "/docs/notes.md"));
      assertEquals(new PtyMessage.FilesDone(3), next(files));
      PtyWire.write(files, new PtyMessage.FilesRemove(4, home + "/docs"));
      assertEquals(new PtyMessage.FilesDone(4), next(files));
      assertFalse(Files.exists(home.resolve("docs")));
    }
    try (var box = unix("")) {
      for (var change :
          List.<PtyMessage>of(
              new PtyMessage.FilesWrite(1, home + "/x", PtyMessage.ABSENT, "", 0),
              new PtyMessage.FilesCreate(1, home + "/x", false),
              new PtyMessage.FilesMove(1, home + "/x", home + "/y"),
              new PtyMessage.FilesRemove(1, home + "/x"))) {
        PtyWire.write(box, change);
        assertEquals(
            "Files are read on a connection a files ticket admitted, and this is not one.",
            assertInstanceOf(PtyMessage.Err.class, PtyWire.read(box)).reason());
      }
    }
    assertFalse(Files.exists(home.resolve("x")));
  }

  /**
   * A save asks the workspace's decision again before it writes, not only at the sweep: a relabel
   * between its bytes and its end ends the connection in the decision's words and writes nothing;
   * an outage refuses it and the connection goes on.
   */
  @Test
  void aRelabelBetweenASavesBytesAndItsEndWritesNothingAndEndsTheConnection() throws Exception {
    var file = Files.writeString(home.resolve("notes.md"), "kept");
    var bytes = "mine".getBytes(java.nio.charset.StandardCharsets.UTF_8);
    var files = open("tok-mady");
    PtyWire.write(files, onBase(1, file, baseOf(files, file), 4));
    PtyWire.write(files, new PtyMessage.PutChunk(1, 0, bytes));
    assertEquals(new PtyMessage.PutAck(1, 4), next(files));
    brokenNow.add("dock");
    PtyWire.write(files, new PtyMessage.PutEnd(1, sha256(bytes)));
    assertEquals(
        PtyMessage.FilesRefusal.FAILED,
        assertInstanceOf(PtyMessage.FilesRefused.class, next(files)).kind());
    assertEquals("kept", Files.readString(file), "an outage writes nothing");
    brokenNow.remove("dock");

    PtyWire.write(files, onBase(2, file, baseOf(files, file), 4));
    PtyWire.write(files, new PtyMessage.PutChunk(2, 0, bytes));
    assertEquals(new PtyMessage.PutAck(2, 4), next(files), "and ends nothing");
    relabelled.add("dock");
    PtyWire.write(files, new PtyMessage.PutEnd(2, sha256(bytes)));

    assertEquals(
        List.of("forbidden", "Workspace 'dock' belongs to 'uday', not you."), endedWith(files));
    everyScriptEnds();
    assertEquals("kept", Files.readString(file));
    try (var names = Files.list(home)) {
      assertEquals(List.of("notes.md"), names.map(p -> p.getFileName().toString()).toList());
    }
  }

  /** A save's script held between its go and its compare until {@link Hold#release}. */
  private final class Hold {
    final Path at;
    final Path release;

    Hold() throws Exception {
      at = dir.resolve("at-compare");
      release = dir.resolve("release");
      assertEquals(0, new ProcessBuilder("mkfifo", release.toString()).start().waitFor());
      var once = new java.util.concurrent.atomic.AtomicBoolean();
      edits.put(
          "write",
          script ->
              !once.compareAndSet(false, true)
                  ? script
                  : script.replace(
                      "IFS= read -r -d '' word && [ \"$word\" = go ] || exit 43",
                      "IFS= read -r -d '' word && [ \"$word\" = go ] || exit 43\n"
                          + ": > '"
                          + at
                          + "'; read -r _ < '"
                          + release
                          + "'"));
    }

    /** Waits until a save's script stands at its compare, its go sent and the lock held. */
    void reached() {
      var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
      while (!Files.exists(at)) {
        assertTrue(System.nanoTime() < deadline, "the save reached its compare within 5 s");
        Thread.onSpinWait();
      }
    }

    void release() throws IOException {
      Files.writeString(release, "go\n");
    }
  }

  /** Sends save {@code id} of {@code text} on {@code base}, and reads its acknowledgement. */
  private static void saveOn(SocketChannel channel, int id, Path file, Base base, String text)
      throws Exception {
    var bytes = text.getBytes(java.nio.charset.StandardCharsets.UTF_8);
    PtyWire.write(channel, onBase(id, file, base, bytes.length));
    PtyWire.write(channel, new PtyMessage.PutChunk(id, 0, bytes));
    PtyWire.write(channel, new PtyMessage.PutEnd(id, sha256(bytes)));
    assertEquals(new PtyMessage.PutAck(id, bytes.length), next(channel));
  }

  private static void awaitQueued(java.util.concurrent.locks.ReentrantLock lock) {
    var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
    while (!lock.hasQueuedThreads()) {
      assertTrue(System.nanoTime() < deadline, "a save waits for the workspace's lock");
      Thread.onSpinWait();
    }
  }

  /**
   * m1: a save past its go outlives its connection: closing the connection never cuts it, and it
   * holds the workspace's write lock until its script ends, so another connection's save on the
   * same base waits for it and is a conflict, never a second winner.
   */
  @Test
  void aSavePastItsGoOutlivesItsConnectionAndHoldsTheWorkspacesLock() throws Exception {
    var file = Files.writeString(home.resolve("notes.md"), "base");
    var hold = new Hold();
    var staying = open("tok-root");
    var first = open("tok-mady");
    var base = baseOf(first, file);
    saveOn(first, 1, file, base, "first's");
    hold.reached();
    var lock = host.writeLock("acme", "dock");
    var script = saves.getLast();

    first.close();
    assertFalse(
        script.waitFor(FileScripts.GRACE.toMillis() + 1000, TimeUnit.MILLISECONDS),
        "never cut: a cut's script would be ended within its grace");
    var second = open("tok-mady");
    saveOn(second, 2, file, base, "second's");
    awaitQueued(lock);
    assertTrue(lock.isLocked(), "the closed connection's save holds the lock still");
    hold.release();

    assertInstanceOf(PtyMessage.FilesConflict.class, next(second));
    assertEquals("first's", Files.readString(file), "the save past its go was written");
    second.close();
    staying.close();
  }

  /**
   * m1: the write lock is the workspace's, not its connections': when the last connection ends
   * while its save is past its go, the next connection's save waits on that same lock.
   */
  @Test
  void aWorkspacesLockOutlivesItsLastConnectionWhileASaveHoldsIt() throws Exception {
    var file = Files.writeString(home.resolve("notes.md"), "base");
    var hold = new Hold();
    var last = open("tok-mady");
    var base = baseOf(last, file);
    saveOn(last, 1, file, base, "first's");
    hold.reached();
    var lock = host.writeLock("acme", "dock");

    last.close();
    var next = open("tok-mady");
    assertTrue(lock == host.writeLock("acme", "dock"), "the same lock, not a fresh one");
    saveOn(next, 2, file, base, "second's");
    awaitQueued(lock);
    hold.release();

    assertInstanceOf(PtyMessage.FilesConflict.class, next(next));
    assertEquals("first's", Files.readString(file));
    next.close();
  }

  /**
   * Every files connection to a workspace shares its one write lock, an owner's and an admin's
   * alike, so two saves on one base from two windows write exactly one; the lock goes with the
   * workspace's last connection.
   */
  @Test
  void everyConnectionToAWorkspaceSharesItsWriteLock() throws Exception {
    var file = Files.writeString(home.resolve("notes.md"), "base");
    var bytes = "mine".getBytes(java.nio.charset.StandardCharsets.UTF_8);
    var mine = open("tok-mady");
    var admins = open("tok-root");
    var base = baseOf(mine, file);
    var lock = host.writeLock("acme", "dock");
    lock.lock();
    try {
      for (var channel : List.of(mine, admins)) {
        PtyWire.write(channel, onBase(1, file, base, 4));
        PtyWire.write(channel, new PtyMessage.PutChunk(1, 0, bytes));
        PtyWire.write(channel, new PtyMessage.PutEnd(1, sha256(bytes)));
        assertEquals(new PtyMessage.PutAck(1, 4), next(channel));
      }
      var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
      while (lock.getQueueLength() < 2) {
        assertTrue(System.nanoTime() < deadline, lock.getQueueLength() + " saves wait for it");
        Thread.onSpinWait();
      }
    } finally {
      lock.unlock();
    }
    var answers = List.of(next(mine), next(admins));
    assertEquals(
        1,
        answers.stream().filter(PtyMessage.FilesWritten.class::isInstance).count(),
        "" + answers);
    assertEquals(
        1,
        answers.stream().filter(PtyMessage.FilesConflict.class::isInstance).count(),
        "" + answers);
    mine.close();
    admins.close();
    var gone = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
    while (host.writeLock("acme", "dock") != null) {
      assertTrue(System.nanoTime() < gone, "the lock outlived the workspace's last connection");
      Thread.onSpinWait();
    }
  }

  /**
   * A save that still holds the workspace's write lock as its last connection goes keeps the lock
   * until it lets go, and the lock goes then: a save answers before it releases the lock, so a
   * client may close on its answer before the save has let go.
   */
  @Test
  void aLockHeldAsTheLastConnectionGoesGoesWithTheSaveThatHeldIt() throws Exception {
    var mine = open("tok-mady");
    var lock = host.writeLock("acme", "dock");
    lock.lock();
    try {
      mine.close();
      var unserved = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
      while (host.changeRuns("acme", "dock") != null) {
        assertTrue(
            System.nanoTime() < unserved, "the workspace's last connection was never dropped");
        Thread.onSpinWait();
      }
      assertSame(
          lock, host.writeLock("acme", "dock"), "a lock a save holds went with the connection");
    } finally {
      lock.unlock();
    }
    assertNull(
        host.writeLock("acme", "dock"),
        "the lock outlived the save that held it past the last connection");
  }

  /**
   * Every connection to a workspace asks its changes of one run at a time, which the host drops
   * with the workspace's last connection: each is answered, here that no repository is there.
   */
  @Test
  void everyConnectionToAWorkspaceAsksItsChangesOfOneRun() throws Exception {
    var mine = open("tok-mady");
    var admins = open("tok-root");
    var refusal =
        new PtyMessage.FilesRefused(
            7,
            PtyMessage.FilesRefusal.NO_REPOSITORY,
            "No git repository is in " + home + "/workspace, so dock has no changes to show.");
    for (var channel : List.of(mine, admins)) {
      PtyWire.write(channel, new PtyMessage.FilesChanges(7));
    }
    assertEquals(refusal, next(mine));
    assertEquals(refusal, next(admins));
    var runs = host.changeRuns("acme", "dock");
    PtyWire.write(admins, new PtyMessage.FilesDiff(8, home + "/a.md"));
    assertEquals(
        PtyMessage.FilesRefusal.NO_REPOSITORY, ((PtyMessage.FilesRefused) next(admins)).kind());
    assertTrue(runs != null && runs == host.changeRuns("acme", "dock"), "one run for both");
    mine.close();
    admins.close();
    var gone = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
    while (host.changeRuns("acme", "dock") != null) {
      assertTrue(System.nanoTime() < gone, "the runs outlived the workspace's last connection");
      Thread.onSpinWait();
    }
  }

  /**
   * Round 3's nit 3: a stuck save outlives every connection to its workspace: once all of them are
   * dropped, the next connection's writes are still refused, naming the temporary, until nothing
   * holds it.
   */
  @Test
  void aStuckSaveOutlivesEveryConnectionToItsWorkspace() throws Exception {
    var old = Files.writeString(home.resolve(FileScripts.SAVING + "Stuck00001.part"), "x");
    Files.setLastModifiedTime(
        old, java.nio.file.attribute.FileTime.from(java.time.Instant.now().minusSeconds(60)));
    var holder = new ProcessBuilder("sleep", "30").redirectInput(old.toFile()).start();
    var refusal =
        "A save in dock has not ended since Sail ended it: a process there still holds "
            + old
            + "; when it ends, or once that file is removed, writes resume. Nothing was changed.";
    try {
      var first = open("tok-mady");
      PtyWire.write(first, new PtyMessage.FilesCreate(1, home + "/made.md", false));
      assertEquals(
          new PtyMessage.FilesRefused(1, PtyMessage.FilesRefusal.FAILED, refusal), next(first));
      first.close();

      var next = open("tok-mady");
      PtyWire.write(next, new PtyMessage.FilesCreate(2, home + "/made.md", false));
      assertEquals(
          new PtyMessage.FilesRefused(2, PtyMessage.FilesRefusal.FAILED, refusal),
          next(next),
          "still refused once every connection was dropped");
      holder.destroyForcibly();
      assertTrue(holder.waitFor(5, TimeUnit.SECONDS));
      PtyWire.write(next, new PtyMessage.FilesCreate(3, home + "/made.md", false));
      assertEquals(new PtyMessage.FilesDone(3), next(next), "and free once nothing holds it");
      next.close();
    } finally {
      holder.destroyForcibly();
    }
  }

  /** A connection closed mid-save leaves the file as it was and, within 5 s, no temporary. */
  @Test
  void closingTheConnectionMidSaveLeavesTheFileAndNoTemporary() throws Exception {
    var file = Files.writeString(home.resolve("notes.md"), "kept");
    var files = open("tok-mady");
    PtyWire.write(files, onBase(1, file, baseOf(files, file), 1 << 20));
    PtyWire.write(files, new PtyMessage.PutChunk(1, 0, new byte[64 << 10]));
    assertEquals(new PtyMessage.PutAck(1, 64 << 10), next(files));

    files.close();

    everyScriptEnds();
    assertEquals("kept", Files.readString(file));
    try (var names = Files.list(home)) {
      assertEquals(List.of("notes.md"), names.map(p -> p.getFileName().toString()).toList());
    }
  }

  /**
   * #510's nits: a watch path outside the home is named as shown, each byte that is no UTF-8 as
   * U+FFFD; one that resolves outside it is dropped and the client told, by name.
   */
  @Test
  void aWatchSaysWhichPathItRefusedOrDroppedByItsShownName() throws Exception {
    var files = open("tok-mady");
    var raw = "/etc/" + FileNames.decode(new byte[] {'b', (byte) 0xff});
    PtyWire.write(files, new PtyMessage.FilesWatch(List.of(new PtyMessage.Watched(raw, ""))));
    assertEquals(
        "/etc/b� is not in this workspace's home, so the watch is as it was.",
        assertInstanceOf(PtyMessage.Err.class, next(files)).reason());

    var outside = Files.createDirectories(dir.resolve("outside"));
    var link = Files.createSymbolicLink(home.resolve("away"), outside).toString();
    PtyWire.write(files, new PtyMessage.FilesWatch(List.of(new PtyMessage.Watched(link, ""))));
    assertEquals(
        link + " leads outside this workspace's home, so it is not watched.",
        assertInstanceOf(PtyMessage.Err.class, next(files)).reason());
    files.close();
  }

  /**
   * Review m1, on the host: digests that are not SHA-256s, even equal ones, never prove a read; a
   * workspace with no sha256sum is said so.
   */
  @Test
  void aReadWhoseDigestsAreNoSha256IsRefusedAsFailed() throws Exception {
    var file = Files.writeString(home.resolve("notes.md"), "hello");
    edits.put(
        "read",
        script ->
            script
                .replace(SHAPE_CHECK, "")
                .replace("\"${sent%% *}\" \"${again%% *}\" \"$after\"", "\"\" \"\" \"$after\""));
    try (var files = open("tok-mady")) {
      PtyWire.write(files, new PtyMessage.FilesRead(1, file.toString(), 0, 100));
      assertEquals(
          new PtyMessage.FilesRefused(
              1,
              PtyMessage.FilesRefusal.FAILED,
              "Sail could not read " + file + " in dock; nothing was changed."),
          next(files));
    }
    edits.put("read", script -> script.replace(SHA256_CHECK, "exit 27\n"));
    try (var files = open("tok-mady")) {
      PtyWire.write(files, new PtyMessage.FilesRead(2, file.toString(), 0, 100));
      assertEquals(
          new PtyMessage.FilesRefused(
              2,
              PtyMessage.FilesRefusal.FAILED,
              "dock has no sha256sum, so Sail cannot prove what it reads of "
                  + file
                  + "; nothing was changed."),
          next(files));
    }
  }

  /** Where the read script checks it has a sha256sum, and that each digest is one. */
  private static final String SHA256_CHECK = "command -v sha256sum > /dev/null || exit 27\n";

  private static final String SHAPE_CHECK =
      "[[ ${sent%% *} =~ ^[0-9a-f]{64}$ && ${again%% *} =~ ^[0-9a-f]{64}$ ]] || exit 28\n";

  /** Review nit 2: a read whose {@code dd} fails mid-way is failed, never mislabelled changed. */
  @Test
  void aReadWhoseDdFailsIsAnsweredFailed() throws Exception {
    var file = Files.writeString(home.resolve("notes.md"), "hello");
    edits.put(
        "read",
        script ->
            script.replace(
                SENT,
                SENT.replace("status=none |", "status=none; false; } |")
                    .replace("sent=$( { dd", "sent=$( { { dd")));
    try (var files = open("tok-mady")) {
      PtyWire.write(files, new PtyMessage.FilesRead(1, file.toString(), 0, 100));
      assertEquals(
          PtyMessage.FilesRefusal.FAILED,
          assertInstanceOf(PtyMessage.FilesRefused.class, next(files)).kind());
    }
  }

  /** Review nit 1: the last frame says whether the read was whole, from 0 to the file's end. */
  @Test
  void theLastFrameSaysWhetherTheReadWasWhole() throws Exception {
    var file = Files.write(home.resolve("still.txt"), filled('a'));
    try (var files = open("tok-mady")) {
      PtyWire.write(files, new PtyMessage.FilesRead(1, file.toString(), 0, 4L << 20));
      PtyMessage.FilesData data;
      do {
        data = assertInstanceOf(PtyMessage.FilesData.class, next(files));
        assertTrue(data.eof() || !data.whole(), "no frame before the last says whole");
      } while (!data.eof());
      assertTrue(data.whole(), "from 0 to the end");
      PtyWire.write(files, new PtyMessage.FilesRead(2, file.toString(), 10, 4L << 20));
      do {
        data = assertInstanceOf(PtyMessage.FilesData.class, next(files));
      } while (!data.eof());
      assertFalse(data.whole(), "from 10, to the end, is no whole read");
      PtyWire.write(files, new PtyMessage.FilesRead(3, file.toString(), 0, 10));
      data = assertInstanceOf(PtyMessage.FilesData.class, next(files));
      assertFalse(data.eof() || data.whole(), "the first 10 bytes are not the file");
    }
  }
}
