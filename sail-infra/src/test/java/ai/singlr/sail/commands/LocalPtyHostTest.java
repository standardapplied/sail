/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.commands;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.sail.pty.Account;
import ai.singlr.sail.pty.PtyMessage;
import ai.singlr.sail.pty.PtyWire;
import java.io.IOException;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The terminal host wired for this machine's own person ({@code sail _pty-host --local}), through
 * the real {@code PtySessionHost} on a real unix socket and real ptys: one test per scenario of
 * {@code sail-a-terminal-host-on-your-own-machine}. Every wait is bounded by {@link #STEP}, and
 * every process a test starts is gone when it ends, killed if a failure left it.
 */
class LocalPtyHostTest {

  static final Duration STEP = Duration.ofSeconds(20);

  @TempDir Path dir;

  private final Instant began = Instant.now();
  private final List<LocalPtyHost> running = new ArrayList<>();

  @AfterEach
  void leaveNothingRunning() {
    running.forEach(LocalPtyHost::close);
    startedSince(began).forEach(ProcessHandle::destroyForcibly);
  }

  private LocalPtyHost start() throws IOException {
    return start(dir.resolve("local"));
  }

  private LocalPtyHost start(Path where) throws IOException {
    var host = LocalPtyHost.start(where, new LocalPtyAccess(Account.current(), "C.UTF-8"));
    running.add(host);
    return host;
  }

  private void stop(LocalPtyHost host) {
    running.remove(host);
    host.close();
  }

  private Path socket() {
    return LocalPtyHost.socketIn(dir.resolve("local"));
  }

  private Path folder(String name) throws IOException {
    return Files.createDirectories(dir.resolve(name));
  }

  /** Scenario 1. */
  @Test
  void aSessionWithNoCommandRunsThePersonsLoginShellInItsFolderWithTheTerminalsEnvironment()
      throws Exception {
    var account = Account.current();
    var work = folder("work");
    start();
    try (var client = Client.connect(socket())) {
      client.open("one", work, List.of());
      client.attach("one");
      client.type(Client.PROBE);

      assertEquals(
          "@42@"
              + account.shell()
              + " -l@"
              + work.toRealPath()
              + "@xterm-256color@C.UTF-8@"
              + account.home()
              + "@"
              + account.name()
              + "@"
              + account.name()
              + "@"
              + account.shell()
              + "@",
          client.probed());
    }
  }

  /** Scenario 2, and the sweep: the local host has no store, so it revokes nothing. */
  @Test
  void aSessionOutlivesItsClientAndASecondOneReplaysItAndTypesIntoTheSameShell() throws Exception {
    var work = folder("work");
    var host = start();
    try (var first = Client.connect(socket())) {
      first.open("kept", work, List.of());
      first.attach("kept");
      first.type("x=kept; echo \"@$((6*7))@$x@\"\n");
      first.awaitText("@42@kept@");
    }
    host.host().sweep(System.nanoTime());
    try (var second = Client.connect(socket())) {
      second.attach("kept");
      second.awaitText("@42@kept@");
      host.host().sweep(System.nanoTime());
      second.type("echo \"#$((7*7))#$x#\"\n");
      second.awaitText("#49#kept#");
    }
  }

  /** Scenario 3. */
  @Test
  void anOpenThatNamesAProjectOrARoomIsRefusedSayingTheLocalHostOpensFolders() throws Exception {
    var work = folder("work");
    start();
    try (var client = Client.connect(socket())) {
      assertEquals(
          LocalPtyAccess.FOLDERS_ONLY,
          client.refused(
              new PtyMessage.Create("p", List.of(), work.toString(), "acme", "", 80, 24)));
      assertEquals(
          "Refusing room for session 't': " + LocalPtyAccess.FOLDERS_ONLY,
          client.refused(
              new PtyMessage.Create("t", List.of(), work.toString(), "", "design-talk", 80, 24)));
      assertEquals(List.of(), client.list());
    }
  }

  /** Scenario 4, and L2: the socket and its directory are the owner's alone. */
  @Test
  void aConnectionThatPresentsAnyTokenIsRefusedBeforeASessionIsTouched() throws Exception {
    var work = folder("work");
    start();
    try (var owner = Client.connect(socket())) {
      owner.open("mine", work, List.of("sh", "-c", "echo ready; exec cat"));
      for (var token : List.of("tok-uday", "x")) {
        try (var stranger = Client.raw(socket())) {
          assertEquals(
              LocalPtyAccess.TOKEN_REFUSED,
              assertInstanceOf(PtyMessage.Err.class, stranger.hello(token)).message(),
              token);
        }
      }
      assertEquals(
          List.of(new PtyMessage.SessionInfo("mine", "", true, 0, "", "", List.of())),
          owner.list().stream().map(Client::shape).toList());
    }
    assertEquals("rwx------", permissions(dir.resolve("local")));
    assertEquals("rwx------", permissions(socket()));
  }

  /** Scenario 5: no folder, a file, a relative path, a blank one, one the person cannot enter. */
  @Test
  void aFolderThatIsNotAnExistingDirectoryThePersonMayEnterIsRefusedAndStartsNoProcess()
      throws Exception {
    var file = Files.writeString(dir.resolve("file"), "not a folder");
    var locked = folder("locked");
    Files.setPosixFilePermissions(locked, PosixFilePermissions.fromString("---------"));
    start();
    try (var client = Client.connect(socket())) {
      var before = children();
      assertEquals(
          "Refusing folder for session 's': There is no folder '"
              + dir.resolve("gone")
              + "': name an existing folder to open the session in.",
          client.refused(open(dir.resolve("gone").toString())));
      assertEquals(
          "Refusing folder for session 's': '"
              + file
              + "' is not a folder: name the folder to open the session in.",
          client.refused(open(file.toString())));
      assertEquals(
          "Refusing folder for session 's': The local terminal host opens a session in a folder"
              + " named by its absolute path, not 'work'.",
          client.refused(open("work")));
      assertEquals(
          "Refusing folder for session 's': The local terminal host opens a session in a folder"
              + " named by its absolute path, not ''.",
          client.refused(open("")));
      assertEquals(
          "Refusing folder for session 's': You may not enter the folder '"
              + locked
              + "': name one you may open.",
          client.refused(open(locked.toString())));
      assertEquals(before, children(), "no process was started");
      assertEquals(List.of(), client.list());
    } finally {
      Files.setPosixFilePermissions(locked, PosixFilePermissions.fromString("rwx------"));
    }
  }

  private static PtyMessage.Create open(String folder) {
    return new PtyMessage.Create("s", List.of(), folder, "", "", 80, 24);
  }

  /**
   * Scenario 6. A cycle of the host first opens what the JDK opens once (its pollers, its process
   * reaper), so the count after the measured cycle is exact.
   */
  @Test
  void stoppingTheHostEndsBothSessionsWithinItsBoundAndLeavesNoProcessOrDescriptor()
      throws Exception {
    var work = folder("work");
    var warmUp = cycle(work, 1);
    stop(warmUp.host());
    warmUp.clients().forEach(Client::close);
    var descriptors = openDescriptors();

    var measured = cycle(work, 2);
    var processes = startedSince(began);
    assertFalse(processes.isEmpty(), "the sessions run");
    var stopping = System.nanoTime();
    stop(measured.host());
    var stopped = Duration.ofNanos(System.nanoTime() - stopping);

    for (var client : measured.clients()) {
      try (client) {
        assertEquals(
            new PtyMessage.SessionEnded(PtyHostCommand.STOPPED_REASON), client.awaitEnding());
      }
    }
    assertTrue(
        stopped.compareTo(Duration.ofSeconds(12)) < 0,
        "an interactive shell ignores SIGTERM, so it is killed at the 5 s escalation: " + stopped);
    assertEquals(
        List.of(), processes.stream().filter(ProcessHandle::isAlive).toList(), "nothing is left");
    assertEquals(descriptors, openDescriptors());
  }

  private record Cycle(LocalPtyHost host, List<Client> clients) {}

  /** A host serving {@code sessions} login shells, each with a client attached that saw it run. */
  private Cycle cycle(Path work, int sessions) throws Exception {
    var host = start();
    var clients = new ArrayList<Client>();
    for (var i = 0; i < sessions; i++) {
      var client = Client.connect(socket());
      clients.add(client);
      client.open("shell-" + i, work, List.of());
      client.attach("shell-" + i);
      client.type("echo \"@$((6*7))@\"\n");
      client.awaitText("@42@");
    }
    return new Cycle(host, clients);
  }

  /** Scenario 7. */
  @Test
  void stoppingTheHostAsAChildFloodsItsTerminalOnTheWayOutDoesNotHang() throws Exception {
    var work = folder("work");
    var host = start();
    try (var client = Client.connect(socket())) {
      client.open(
          "burst",
          work,
          List.of(
              "sh",
              "-c",
              "trap 'yes burst | head -c 1000000; exit 0' TERM; echo ready; while :; do sleep"
                  + " 0.05; done"));
      client.attach("burst");
      client.awaitText("ready");
    }
    var processes = startedSince(began);
    var stopping = System.nanoTime();
    stop(host);
    var stopped = Duration.ofNanos(System.nanoTime() - stopping);

    assertTrue(
        stopped.compareTo(Duration.ofSeconds(4)) < 0,
        "the stop reads the burst to its end, well inside the 5 s escalation: " + stopped);
    assertEquals(List.of(), processes.stream().filter(ProcessHandle::isAlive).toList());
  }

  /** Scenario 8, within one process; across two, {@code LocalPtyHostProcessTest}. */
  @Test
  void aSecondHostForTheSamePersonIsRefusedSayingOneIsRunningAndTheFirstIsUntouched()
      throws Exception {
    var work = folder("work");
    var first = start();
    try (var client = Client.connect(socket())) {
      client.open("mine", work, List.of("sh", "-c", "exec cat"));
      var boot = client.boot();

      var refused = assertThrows(IOException.class, this::start);

      assertEquals(
          "A terminal host already serves "
              + Account.current().name()
              + " on this machine, at "
              + socket()
              + ": connect to it there, or stop it before starting another.",
          refused.getMessage());
      try (var again = Client.connect(socket())) {
        assertEquals(boot, again.boot());
        assertEquals(first.host().bootId(), again.boot());
        assertEquals(List.of("mine"), again.list().stream().map(i -> i.name()).toList());
      }
    }
  }

  /** Scenario 9. */
  @Test
  void aHostReplacesTheSocketAndLockADeadHostLeftAndServes() throws Exception {
    var local = Files.createDirectories(dir.resolve("local"));
    try (var dead = ServerSocketChannel.open(StandardProtocolFamily.UNIX)) {
      dead.bind(UnixDomainSocketAddress.of(socket()));
    }
    Files.writeString(local.resolve("pty.lock"), "");
    assertTrue(Files.exists(socket()), "the dead host's socket is left behind");
    assertThrows(IOException.class, () -> SocketChannel.open(UnixDomainSocketAddress.of(socket())));

    start();

    try (var client = Client.connect(socket())) {
      client.open("fresh", folder("work"), List.of("sh", "-c", "exec cat"));
      assertEquals(List.of("fresh"), client.list().stream().map(i -> i.name()).toList());
    }
  }

  /** L5: sessions outlive their clients, not their host, and the boot id says so. */
  @Test
  void aRestartedHostHasANewBootIdAndNoneOfTheSessionsItsPredecessorHeld() throws Exception {
    var work = folder("work");
    var first = start();
    String boot;
    try (var client = Client.connect(socket())) {
      client.open("before", work, List.of("sh", "-c", "exec cat"));
      boot = client.boot();
    }
    stop(first);

    start();

    try (var client = Client.connect(socket())) {
      assertNotEquals(boot, client.boot());
      assertEquals(List.of(), client.list());
    }
  }

  @Test
  void aHostThatCannotStartLetsGoOfThePersonsLockSoTheNextStartIsNotRefused() throws Exception {
    Files.createDirectories(socket().resolve("in-the-way"));

    assertThrows(IOException.class, this::start);

    Files.delete(socket().resolve("in-the-way"));
    Files.delete(socket());
    start();
    try (var client = Client.connect(socket())) {
      assertEquals(List.of(), client.list());
    }
  }

  /** Scenario 11, at the limit and one byte past it. */
  @Test
  void aSocketPathLongerThanEverySystemTakesIsRefusedAtStartNamingThePathAndTheLimit()
      throws Exception {
    var past = localDirWithSocketOf(LocalPtyHost.MAX_SOCKET_PATH_BYTES + 1);

    var refused = assertThrows(IOException.class, () -> start(past));

    assertEquals(
        "This machine's terminal host cannot listen at "
            + LocalPtyHost.socketIn(past)
            + ": the path is 103 bytes, and a unix socket's path may be at most 102 bytes on"
            + " every system the host runs on. Run it as an account whose home directory is"
            + " shorter.",
        refused.getMessage());
    assertFalse(Files.exists(past), "nothing was made");

    var limit = localDirWithSocketOf(LocalPtyHost.MAX_SOCKET_PATH_BYTES);
    start(limit);
    try (var client = Client.connect(LocalPtyHost.socketIn(limit))) {
      assertEquals(List.of(), client.list());
    }
  }

  private Path localDirWithSocketOf(int bytes) {
    var pad = bytes - dir.toString().length() - "/".length() - "/local/pty.sock".length();
    var local = dir.resolve("h".repeat(pad)).resolve("local");
    assertEquals(bytes, LocalPtyHost.socketIn(local).toString().length());
    return local;
  }

  private static String permissions(Path path) throws IOException {
    return PosixFilePermissions.toString(Files.getPosixFilePermissions(path));
  }

  private static Set<Long> children() {
    return ProcessHandle.current().children().map(ProcessHandle::pid).collect(Collectors.toSet());
  }

  /** What this JVM started since {@code instant} and still runs, its children's children too. */
  static List<ProcessHandle> startedSince(Instant instant) {
    return ProcessHandle.current()
        .descendants()
        .filter(
            process ->
                process.info().startInstant().map(start -> !start.isBefore(instant)).orElse(true))
        .toList();
  }

  /** The descriptors this process holds open, as {@code /dev/fd} lists them on both systems. */
  private static long openDescriptors() throws IOException {
    try (var fds = Files.list(Path.of("/dev/fd"))) {
      return fds.count();
    }
  }

  /** A unix connection to the local host, every read of which is bounded by {@link #STEP}. */
  static final class Client implements AutoCloseable {

    /**
     * A line that answers who runs it and where: the shell's own command line, its folder, its
     * terminal, its locale and the account's variables, after {@code @42@}, which only an evaluated
     * line holds, never the terminal's echo of the typed one.
     */
    static final String PROBE =
        "echo \"@$((6*7))@$(ps -o args= -p $$)@$(pwd)@$TERM@$LANG@$HOME@$USER@$LOGNAME@$SHELL@\"\n";

    private final SocketChannel channel;
    private final StringBuilder said = new StringBuilder();
    private long seq;

    private Client(SocketChannel channel) {
      this.channel = channel;
    }

    /** A connection that has not said hello. */
    static Client raw(Path socket) throws IOException {
      var channel = SocketChannel.open(StandardProtocolFamily.UNIX);
      channel.connect(UnixDomainSocketAddress.of(socket));
      return new Client(channel);
    }

    /** A connection welcomed as this machine's person, presenting no token. */
    static Client connect(Path socket) throws IOException {
      var client = raw(socket);
      assertInstanceOf(PtyMessage.Welcome.class, client.hello(""));
      return client;
    }

    PtyMessage hello(String token) throws IOException {
      PtyWire.handshake(channel, channel);
      send(new PtyMessage.Hello(token));
      return read();
    }

    String boot() throws IOException {
      try (var other = raw(socketOf(channel))) {
        return ((PtyMessage.Welcome) other.hello("")).hostBootId();
      }
    }

    private static Path socketOf(SocketChannel channel) throws IOException {
      return ((UnixDomainSocketAddress) channel.getRemoteAddress()).getPath();
    }

    void send(PtyMessage message) throws IOException {
      PtyWire.write(channel, message);
    }

    PtyMessage read() throws IOException {
      var deadline = deadline();
      try {
        return PtyWire.read(channel);
      } catch (IOException closed) {
        throw new IOException("nothing within " + STEP + "; the terminal said: " + said, closed);
      } finally {
        deadline.cancel(false);
      }
    }

    /** Closes this connection once {@link #STEP} passes, unless cancelled first. */
    private CompletableFuture<Void> deadline() {
      return CompletableFuture.runAsync(
          this::abandon, CompletableFuture.delayedExecutor(STEP.toMillis(), TimeUnit.MILLISECONDS));
    }

    /** The next answer to a command, past whatever output the session sends meanwhile. */
    PtyMessage answer() throws IOException {
      while (true) {
        var message = read();
        if (message instanceof PtyMessage.Output(var lastInputSeq, var bytes)) {
          said.append(new String(bytes, StandardCharsets.UTF_8));
        } else if (message instanceof PtyMessage.Ok
            || message instanceof PtyMessage.Err
            || message instanceof PtyMessage.Sessions
            || message instanceof PtyMessage.SessionEnded) {
          return message;
        }
      }
    }

    void open(String name, Path folder, List<String> command) throws IOException {
      send(new PtyMessage.Create(name, command, folder.toString(), "", "", 250, 50));
      assertEquals(new PtyMessage.Ok(), answer(), "opened " + name);
    }

    String refused(PtyMessage.Create create) throws IOException {
      send(create);
      return assertInstanceOf(PtyMessage.Err.class, answer()).message();
    }

    void attach(String name) throws IOException {
      send(new PtyMessage.Attach(name, true));
      assertEquals(new PtyMessage.Ok(), answer(), "attached " + name);
    }

    List<PtyMessage.SessionInfo> list() throws IOException {
      send(new PtyMessage.ListSessions("", PtyMessage.PAGE_LIMIT));
      return assertInstanceOf(PtyMessage.Sessions.class, answer()).sessions();
    }

    /** {@code info} with what changes from run to run blanked. */
    static PtyMessage.SessionInfo shape(PtyMessage.SessionInfo info) {
      return new PtyMessage.SessionInfo(
          info.name(), "", info.live(), info.attached(), "", info.room(), List.of());
    }

    void type(String line) throws IOException {
      send(new PtyMessage.Input(++seq, line.getBytes(StandardCharsets.UTF_8)));
    }

    /** Reads output until {@code marker} has been said. */
    void awaitText(String marker) throws IOException {
      while (!said.toString().contains(marker)) {
        more();
      }
    }

    /** The line {@link #PROBE} evaluated to, from its {@code @42@} to its end. */
    String probed() throws IOException {
      awaitText("@42@");
      var start = said.indexOf("@42@");
      while (said.indexOf("\r\n", start) < 0) {
        more();
      }
      return said.substring(start, said.indexOf("\r\n", start));
    }

    private void more() throws IOException {
      var message = read();
      if (message instanceof PtyMessage.Output(var lastInputSeq, var bytes)) {
        said.append(new String(bytes, StandardCharsets.UTF_8));
      } else if (message instanceof PtyMessage.SessionEnded ended) {
        throw new AssertionError("the session ended (" + ended.reason() + "): " + said);
      }
    }

    PtyMessage.SessionEnded awaitEnding() throws IOException {
      while (true) {
        if (answer() instanceof PtyMessage.SessionEnded ended) {
          return ended;
        }
      }
    }

    /**
     * Hangs up, and returns once the host has closed its side too: what it says meanwhile is read
     * to its end of stream, which comes as the host's own close.
     */
    @Override
    public void close() {
      var deadline = deadline();
      try {
        channel.shutdownOutput();
        var rest = ByteBuffer.allocate(64 * 1024);
        var read = 0;
        while (read >= 0) {
          read = channel.read(rest.clear());
        }
      } catch (IOException gone) {
        var unused = gone;
      } finally {
        deadline.cancel(false);
        abandon();
      }
    }

    private void abandon() {
      try {
        channel.close();
      } catch (IOException ignored) {
        var unused = ignored;
      }
    }
  }
}
