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
import ai.singlr.sail.pty.Locales;
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
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The terminal host wired for this machine's own person ({@code sail _pty-host --local}), through
 * the real {@code PtySessionHost} on a real unix socket and real ptys: one test per scenario of
 * {@code sail-a-terminal-host-on-your-own-machine}, and one per finding of its first review. The
 * person is this account, in a home of the test's own, so nothing of the account's real home is
 * read or written. Every wait is bounded by {@link #STEP}, and every process a test starts is gone
 * when it ends, killed if a failure left it.
 */
class LocalPtyHostTest {

  static final Duration STEP = Duration.ofSeconds(20);

  @TempDir Path dir;

  private final Instant began = Instant.now();
  private final List<LocalPtyHost> running = new ArrayList<>();
  private final AtomicInteger spawns = new AtomicInteger();

  @AfterEach
  void leaveNothingRunning() {
    running.forEach(LocalPtyHost::close);
    startedSince(began).forEach(ProcessHandle::destroyForcibly);
  }

  /** This account, at home in {@code home}, made as a home is: others may read it, not write. */
  static Account at(Path home) throws IOException {
    var account = Account.current();
    Files.createDirectories(home);
    Files.setPosixFilePermissions(home, PosixFilePermissions.fromString("rwxr-xr-x"));
    return new Account(account.name(), home, account.shell());
  }

  private Account person() throws IOException {
    return at(dir.resolve("home"));
  }

  private static LocalPtyAccess access(Account account) {
    return new LocalPtyAccess(account, System.getenv(), Locales::installed);
  }

  /** The host for {@link #person()}, every spawn it makes counted in {@link #spawns}. */
  private LocalPtyHost start() throws IOException {
    return start(person());
  }

  private LocalPtyHost start(Account account) throws IOException {
    Function<Path, List<String>> counted =
        exit -> {
          spawns.incrementAndGet();
          return PtyHostCommand.shimCommand(exit);
        };
    var host = LocalPtyHost.start(access(account), counted);
    running.add(host);
    return host;
  }

  private void stop(LocalPtyHost host) {
    running.remove(host);
    host.close();
  }

  private Path socket() throws IOException {
    return LocalPtyHost.socketOf(person());
  }

  private Path local() throws IOException {
    return LocalPtyHost.dirOf(person());
  }

  private Path folder(String name) throws IOException {
    return Files.createDirectories(dir.resolve(name));
  }

  /** {@code ~/.sail} and {@code ~/.sail/local} made as the host makes them. */
  private Path privateLocal() throws IOException {
    var owner = PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------"));
    Files.createDirectory(local().getParent(), owner);
    return Files.createDirectory(local(), owner);
  }

  /**
   * Scenario 1. The login shell answers its command line, its folder, its terminal and its
   * character set; it runs in a home of the test's own, so no profile of the account's real one
   * speaks. A {@code sh -c} session, which reads no profile at all, answers the environment the
   * host gives.
   */
  @Test
  void aSessionWithNoCommandRunsThePersonsLoginShellInItsFolderWithTheTerminalsEnvironment()
      throws Exception {
    var account = person();
    var work = folder("work");
    start();
    try (var client = Client.connect(socket())) {
      client.open("shell", work, List.of());
      client.attach("shell");
      client.type(Client.PROBE);

      assertEquals(
          "@42@" + account.shell() + " -l@" + work.toRealPath() + "@xterm-256color@UTF-8@",
          client.probed());
    }
    try (var client = Client.connect(socket())) {
      client.open("environment", work, Client.ENVIRONMENT);
      client.attach("environment");

      assertEquals(
          "@42@"
              + account.home()
              + "@"
              + account.name()
              + "@"
              + account.name()
              + "@"
              + account.shell()
              + "@"
              + LocalPtyAccess.locale(System.getenv(), Locales::installed)
                  .getOrDefault("LANG", System.getenv("LANG"))
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
      assertEquals(0, spawns.get(), "nothing was spawned");
    }
  }

  /** Scenario 4, and L2: the socket and the directories above it are the owner's alone. */
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
    assertEquals("rwx------", permissions(local().getParent()));
    assertEquals("rwx------", permissions(local()));
    assertEquals("rwx------", permissions(socket()));
  }

  /**
   * Scenario 5: no folder, not a folder, a relative path, a blank one, one the person cannot enter.
   * Every spawn the host makes goes through its shim, which counts them: none is made.
   */
  @Test
  void aFolderThatIsNotAnExistingDirectoryThePersonMayEnterIsRefusedAndStartsNoProcess()
      throws Exception {
    var file = Files.writeString(dir.resolve("file"), "not a folder");
    var locked = folder("locked");
    Files.setPosixFilePermissions(locked, PosixFilePermissions.fromString("---------"));
    start();
    try (var client = Client.connect(socket())) {
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
      assertEquals(0, spawns.get(), "no process was started");
      assertEquals(List.of(), client.list());

      client.open("there", folder("work"), List.of("sh", "-c", "exec cat"));
      assertEquals(1, spawns.get(), "the shim counts what the host spawns");
    } finally {
      Files.setPosixFilePermissions(locked, PosixFilePermissions.fromString("rwx------"));
    }
  }

  /** A folder the person may enter but not read is where the session runs, all the same. */
  @Test
  void aSessionRunsInAFolderThePersonMayEnterButNotRead() throws Exception {
    var hidden = folder("hidden");
    Files.setPosixFilePermissions(hidden, PosixFilePermissions.fromString("-wx------"));
    start();
    try (var client = Client.connect(socket())) {
      client.open("there", hidden, List.of("sh", "-c", "echo \"@$((6*7))@$(pwd)@\"; exec cat"));
      client.attach("there");

      assertEquals("@42@" + hidden.toRealPath() + "@", client.probed());
    } finally {
      Files.setPosixFilePermissions(hidden, PosixFilePermissions.fromString("rwx------"));
    }
  }

  private static PtyMessage.Create open(String folder) {
    return new PtyMessage.Create("s", List.of(), folder, "", "", 80, 24);
  }

  /**
   * Scenario 6. Stopping hangs every session up, as a closing terminal does, so an interactive
   * shell ends at once and writes its history; a cycle of the host first opens what the JDK opens
   * once (its pollers, its process reaper), so the count after the measured cycle is exact.
   */
  @Test
  void stoppingTheHostEndsBothSessionsAtOnceWithTheirHistoryAndLeavesNoProcessOrDescriptor()
      throws Exception {
    var work = folder("work");
    var warmUp = cycle(work, "warm", 1);
    stop(warmUp.host());
    warmUp.clients().forEach(Client::close);
    var descriptors = openDescriptors();

    var measured = cycle(work, "shell", 2);
    var processes = startedSince(began);
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
        stopped.compareTo(Duration.ofSeconds(3)) < 0,
        "a hangup ends an interactive shell at once, where SIGTERM waited for the kill: "
            + stopped);
    for (var i = 0; i < 2; i++) {
      assertTrue(Files.exists(dir.resolve("shell-" + i + ".history")), "shell-" + i + " saved");
    }
    assertEquals(
        List.of(), processes.stream().filter(ProcessHandle::isAlive).toList(), "nothing is left");
    assertEquals(descriptors, openDescriptors());
  }

  private record Cycle(LocalPtyHost host, List<Client> clients) {}

  /**
   * A host serving {@code sessions} interactive shells named after {@code name}, each with a client
   * attached that saw it run and pointed its history at a file of its own.
   */
  private Cycle cycle(Path work, String name, int sessions) throws Exception {
    var host = start();
    var clients = new ArrayList<Client>();
    for (var i = 0; i < sessions; i++) {
      var client = Client.connect(socket());
      clients.add(client);
      client.open(name + "-" + i, work, List.of("bash", "--norc", "--noprofile", "-i"));
      client.attach(name + "-" + i);
      client.type(
          "HISTFILE=" + dir.resolve(name + "-" + i + ".history") + "; echo \"@$((6*7))@\"\n");
      client.awaitText("@42@");
    }
    return new Cycle(host, clients);
  }

  /** Scenario 7: the child floods its terminal as it is hung up. */
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
              "trap 'yes burst | head -c 1000000; exit 0' HUP TERM; echo ready; while :; do sleep"
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
        "the stop reads the burst to its end, well inside the escalation: " + stopped);
    assertEquals(List.of(), processes.stream().filter(ProcessHandle::isAlive).toList());
  }

  /**
   * Scenario 8, within one process, and the lock as another process sees it after the refusal:
   * still held. Across two processes it is {@code LocalPtyHostProcessTest}'s.
   */
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
              + person().name()
              + " on this machine, at "
              + socket()
              + ": connect to it there, or stop it before starting another.",
          refused.getMessage());
      assertEquals("HELD", LockProbe.from(local().resolve(LocalPtyHost.LOCK)));
      try (var again = Client.connect(socket())) {
        assertEquals(boot, again.boot());
        assertEquals(first.host().bootId(), again.boot());
        assertEquals(List.of("mine"), again.list().stream().map(i -> i.name()).toList());
      }
    }
  }

  /**
   * The lock is let go only after the socket is gone: a successor started while its predecessor is
   * still stopping is refused, so it never binds a socket the predecessor then deletes.
   */
  @Test
  void aSuccessorCannotStartWhileItsPredecessorIsStillStopping() throws Exception {
    var first = start();
    running.remove(first);
    var closer = new AtomicReference<Thread>();
    Object successor;
    synchronized (first.host()) {
      closer.set(Thread.ofPlatform().start(first::close));
      var deadline = System.nanoTime() + STEP.toNanos();
      while (closer.get().getState() != Thread.State.BLOCKED) {
        assertTrue(System.nanoTime() < deadline, "the stop never reached the host");
        Thread.onSpinWait();
      }
      try {
        successor = start();
      } catch (IOException refused) {
        successor = refused;
      }
    }
    assertTrue(closer.get().join(STEP), "the predecessor stopped");

    assertInstanceOf(IOException.class, successor, "a successor waits for the lock");
    var next = start();
    try (var client = Client.connect(next.socket())) {
      assertEquals(List.of(), client.list());
    }
  }

  /** Scenario 9. */
  @Test
  void aHostReplacesTheSocketAndLockADeadHostLeftAndServes() throws Exception {
    var local = privateLocal();
    try (var dead = ServerSocketChannel.open(StandardProtocolFamily.UNIX)) {
      dead.bind(UnixDomainSocketAddress.of(socket()));
    }
    Files.writeString(local.resolve(LocalPtyHost.LOCK), "");
    assertTrue(Files.exists(socket()), "the dead host's socket is left behind");
    assertThrows(IOException.class, () -> SocketChannel.open(UnixDomainSocketAddress.of(socket())));

    start();

    try (var client = Client.connect(socket())) {
      client.open("fresh", folder("work"), List.of("sh", "-c", "exec cat"));
      assertEquals(List.of("fresh"), client.list().stream().map(i -> i.name()).toList());
    }
  }

  /** A clean stop leaves no transcript: of the host's directory only its lock remains. */
  @Test
  void aCleanStopRemovesEverySessionsTranscriptAndSidecar() throws Exception {
    var work = folder("work");
    var host = start();
    try (var client = Client.connect(socket())) {
      client.open("one", work, List.of("sh", "-c", "echo ready; exec cat"));
      client.open("two", work, List.of("sh", "-c", "echo ready; exec cat"));
    }
    assertTrue(Files.exists(local().resolve("sessions/one.ring")), "a ring while it runs");

    stop(host);

    try (var left = Files.list(local())) {
      assertEquals(List.of(local().resolve(LocalPtyHost.LOCK)), left.toList());
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
    privateLocal();
    Files.createDirectories(socket().resolve("in-the-way"));

    assertThrows(IOException.class, this::start);

    Files.delete(socket().resolve("in-the-way"));
    Files.delete(socket());
    start();
    try (var client = Client.connect(socket())) {
      assertEquals(List.of(), client.list());
    }
  }

  /** L2 checked: a link in place of the host's directory is refused, and nothing is made there. */
  @Test
  void aLinkInPlaceOfTheHostsDirectoryIsRefusedAndNothingIsMadeWhereItPoints() throws Exception {
    var elsewhere = folder("elsewhere");
    Files.setPosixFilePermissions(elsewhere, PosixFilePermissions.fromString("rwxrwxrwx"));
    Files.createDirectories(local().getParent());
    Files.setPosixFilePermissions(
        local().getParent(), PosixFilePermissions.fromString("rwx------"));
    Files.createSymbolicLink(local(), elsewhere);

    var refused = assertThrows(IOException.class, this::start);

    assertEquals(
        "This machine's terminal host keeps its socket in "
            + local()
            + ", so "
            + local()
            + " must be a directory that "
            + person().name()
            + " owns and no one else may open, and it is a symbolic link owned by "
            + person().name()
            + " with mode "
            + permissions(local())
            + ". Remove it, and the host makes the directory itself.",
        refused.getMessage());
    try (var made = Files.list(elsewhere)) {
      assertEquals(List.of(), made.toList(), "nothing was made where the link points");
    }
  }

  /** L2, as ssh's StrictModes: others may read and search {@code ~/.sail}, and the host serves. */
  @Test
  void aDotSailOthersMayReadButNotWriteIsServedFrom() throws Exception {
    var sail = Files.createDirectories(local().getParent());
    for (var mode : List.of("rwxr-xr-x", "rwxr-x---")) {
      Files.setPosixFilePermissions(sail, PosixFilePermissions.fromString(mode));

      var host = start();

      try (var client = Client.connect(socket())) {
        assertEquals(List.of(), client.list(), mode);
      }
      assertEquals("rwx------", permissions(local()), mode + ": the host's own directory");
      stop(host);
    }
  }

  /** L2: a {@code ~/.sail} its group or others may write is refused, with how to take that away. */
  @Test
  void aDotSailOthersMayWriteIsRefusedSayingToTakeTheirWriteAway() throws Exception {
    var sail = Files.createDirectories(local().getParent());
    for (var mode : List.of("rwxrwxr-x", "rwxr-xrwx", "rwxrwxrwx")) {
      Files.setPosixFilePermissions(sail, PosixFilePermissions.fromString(mode));

      var refused = assertThrows(IOException.class, this::start, mode);

      assertEquals(
          "This machine's terminal host keeps its socket in "
              + local()
              + ", so "
              + sail
              + " must be a directory that "
              + person().name()
              + " owns and no one else may write, and it is a directory owned by "
              + person().name()
              + " with mode "
              + mode
              + ". Run 'chmod go-w "
              + sail
              + "'.",
          refused.getMessage());
      assertFalse(Files.exists(local(), LinkOption.NOFOLLOW_LINKS), mode + ": nothing was made");
    }
  }

  /** L2: a home its group may write is refused, as ssh refuses one under its {@code ~/.ssh}. */
  @Test
  void aHomeOthersMayWriteIsRefusedSayingToTakeTheirWriteAway() throws Exception {
    var account = person();
    var home = account.home();
    var local = LocalPtyHost.dirOf(account);
    Files.setPosixFilePermissions(home, PosixFilePermissions.fromString("rwxrwxr-x"));
    try {
      var refused = assertThrows(IOException.class, () -> start(account));

      assertEquals(
          "This machine's terminal host keeps its socket in "
              + local
              + ", so "
              + home
              + " must be a directory that "
              + account.name()
              + " or root owns and no one else may write, and it is a directory owned by "
              + account.name()
              + " with mode rwxrwxr-x. Run 'chmod go-w "
              + home
              + "'.",
          refused.getMessage());
      assertFalse(Files.exists(local.getParent(), LinkOption.NOFOLLOW_LINKS), "nothing made");
    } finally {
      Files.setPosixFilePermissions(home, PosixFilePermissions.fromString("rwxr-xr-x"));
    }
  }

  /**
   * L2: the host's own directory stays the person's alone; one others may open at all, even only
   * read, is refused with "remove it", since it may hold what they put there.
   */
  @Test
  void aHostsDirectoryOthersMayOpenIsRefusedAndNothingIsMadeInIt() throws Exception {
    privateLocal();
    for (var mode : List.of("rwxr-x---", "rwxrwxrwx")) {
      Files.setPosixFilePermissions(local(), PosixFilePermissions.fromString(mode));

      var refused = assertThrows(IOException.class, this::start, mode);

      assertEquals(
          "This machine's terminal host keeps its socket in "
              + local()
              + ", so "
              + local()
              + " must be a directory that "
              + person().name()
              + " owns and no one else may open, and it is a directory owned by "
              + person().name()
              + " with mode "
              + mode
              + ". Remove it, and the host makes the directory itself.",
          refused.getMessage());
      try (var made = Files.list(local())) {
        assertEquals(List.of(), made.toList(), mode + ": no socket and no lock in it");
      }
    }
  }

  /** L2 checked: a link in place of {@code ~/.sail} itself, even to a private directory. */
  @Test
  void aLinkInPlaceOfDotSailIsRefusedAndNothingIsMadeWhereItPoints() throws Exception {
    var real =
        Files.createDirectory(
            dir.resolve("real"),
            PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
    Files.createSymbolicLink(local().getParent(), real);

    var refused = assertThrows(IOException.class, this::start);

    assertEquals(
        "This machine's terminal host keeps its socket in "
            + local()
            + ", so "
            + local().getParent()
            + " must be a directory that "
            + person().name()
            + " owns and no one else may write, and it is a symbolic link owned by "
            + person().name()
            + " with mode "
            + permissions(local().getParent())
            + ". Remove it, and the host makes the directory itself.",
        refused.getMessage());
    try (var made = Files.list(real)) {
      assertEquals(List.of(), made.toList(), "nothing was made where the link points");
    }
  }

  /**
   * L2 checked: a directory the account does not own is refused, here the home of a host for
   * another account's name, which this test's account owns.
   */
  @Test
  void aDirectoryAnotherAccountOwnsIsRefusedNamingItsOwner() throws Exception {
    var me = person();
    var other = new Account("sail-nobody", me.home(), me.shell());

    var refused = assertThrows(IOException.class, () -> start(other));

    assertEquals(
        "This machine's terminal host keeps its socket in "
            + local()
            + ", so "
            + me.home()
            + " must be a directory that sail-nobody or root owns and no one else may write, and"
            + " it is a directory owned by "
            + me.name()
            + " with mode "
            + permissions(me.home())
            + ". Make it a directory of sail-nobody's.",
        refused.getMessage());
    assertFalse(Files.exists(local().getParent(), LinkOption.NOFOLLOW_LINKS), "nothing made");
  }

  /**
   * L2 checked inside: what the host keeps in its directory must be of its own kind and the
   * account's, read without following a link, or the start names it and says to remove it.
   */
  @Test
  void anEntryOfTheHostsDirectoryThatIsALinkOrOfAnotherKindIsRefusedNamingIt() throws Exception {
    var local = privateLocal();
    var elsewhere = folder("elsewhere");
    Files.setPosixFilePermissions(elsewhere, PosixFilePermissions.fromString("rwxrwxrwx"));
    var me = person().name();
    var cases =
        List.of(
            new Planted(
                "sessions",
                () -> Files.createSymbolicLink(local.resolve("sessions"), elsewhere),
                "a directory",
                "a symbolic link"),
            new Planted(
                "pty.lock",
                () ->
                    Files.createSymbolicLink(local.resolve("pty.lock"), elsewhere.resolve("lock")),
                "a file",
                "a symbolic link"),
            new Planted(
                "pty.lock",
                () -> Files.createDirectory(local.resolve("pty.lock")),
                "a file",
                "a directory"),
            new Planted("pty.sock", () -> mkfifo(local.resolve("pty.sock")), "a socket", "a pipe"),
            new Planted(
                "sessions",
                () -> Files.writeString(local.resolve("sessions"), "not a directory"),
                "a directory",
                "a file"));
    for (var planted : cases) {
      planted.make().run();
      var entry = local.resolve(planted.name());

      var refused = assertThrows(IOException.class, this::start, planted.toString());

      assertEquals(
          "This machine's terminal host keeps its socket in "
              + local
              + ", so "
              + entry
              + " must be "
              + planted.must()
              + " of "
              + me
              + "'s, and it is "
              + planted.is()
              + " owned by "
              + me
              + ". Remove it, and the host makes it again.",
          refused.getMessage());
      try (var made = Files.list(elsewhere)) {
        assertEquals(List.of(), made.toList(), planted + ": nothing was made where it leads");
      }
      Files.delete(entry);
    }
    start();
    try (var client = Client.connect(socket())) {
      assertEquals(List.of(), client.list());
    }
  }

  private record Planted(String name, Making make, String must, String is) {}

  @FunctionalInterface
  private interface Making {
    void run() throws Exception;
  }

  private static void mkfifo(Path path) throws Exception {
    var made = new ProcessBuilder("mkfifo", path.toString()).start();
    assertTrue(made.waitFor(STEP.toMillis(), TimeUnit.MILLISECONDS), "mkfifo ran");
    assertEquals(0, made.exitValue());
  }

  /**
   * One host per person holds even against a lock another process holds: the start is refused as
   * for a running host, and goes ahead once the lock is let go.
   */
  @Test
  void aLockAnotherProcessHoldsRefusesTheStartUntilItIsLetGo() throws Exception {
    var lock = privateLocal().resolve(LocalPtyHost.LOCK);
    try (var holder = LockProbe.holding(lock)) {
      var refused = assertThrows(IOException.class, this::start);

      assertTrue(
          refused.getMessage().startsWith("A terminal host already serves "), refused.getMessage());
    }
    start();
    try (var client = Client.connect(socket())) {
      assertEquals(List.of(), client.list());
    }
  }

  /** L2 checked: a file in place of the host's directory is refused. */
  @Test
  void aFileInPlaceOfTheHostsDirectoryIsRefused() throws Exception {
    privateLocal();
    Files.delete(local());
    Files.writeString(local(), "not a directory");

    var refused = assertThrows(IOException.class, this::start);

    assertTrue(
        refused.getMessage().endsWith(". Remove it, and the host makes the directory itself."),
        refused.getMessage());
    assertTrue(refused.getMessage().contains("it is a file owned by"), refused.getMessage());
  }

  /** A login shell that cannot be run is refused at start, before any session spawns it. */
  @Test
  void aLoginShellThatCannotBeRunIsRefusedAtStartNamingTheAccountDatabase() throws Exception {
    var account = person();
    for (var shell : List.of(Path.of("/nonexistent/shell"), Path.of("/etc/passwd"))) {
      var broken = new Account(account.name(), account.home(), shell);

      var refused = assertThrows(IOException.class, () -> start(broken));

      assertEquals(
          account.name()
              + "'s login shell, "
              + shell
              + " as the account database names it, is not a program that can be run, so this"
              + " machine's terminal host has nothing to open a session with. Set one with 'chsh"
              + " -s <shell>'.",
          refused.getMessage());
    }
    assertEquals(0, spawns.get());
  }

  /** A ticket is a server's: the local host has no network door to issue one for. */
  @Test
  void aTicketRequestHearsThatTicketsComeFromAServer() throws Exception {
    start();
    try (var client = Client.connect(socket())) {
      client.send(new PtyMessage.IssueTicket(PtyMessage.TicketAction.OPEN, "acme", "credential"));

      assertEquals(
          new PtyMessage.TicketRefused(PtyMessage.TicketRefusal.UNAVAILABLE, LocalPtyHost.NO_DOOR),
          client.read());
    }
  }

  /**
   * L2's one other credential: the dispatch token the host mints beside its socket lists every
   * session and ends one, and may neither open nor kill; only the person can read it.
   */
  @Test
  void theDispatchCredentialListsAndEndsSessionsAndNothingMore() throws Exception {
    var work = folder("work");
    start();
    var token = local().resolve("pty-dispatch.token");
    assertEquals("rw-------", permissions(token));
    try (var owner = Client.connect(socket())) {
      owner.open("victim", work, List.of("sh", "-c", "echo ready; exec cat"));
    }
    try (var dispatch = Client.raw(socket())) {
      assertInstanceOf(PtyMessage.Welcome.class, dispatch.hello(Files.readString(token)));
      assertEquals(
          List.of("victim"), dispatch.list().stream().map(PtyMessage.SessionInfo::name).toList());
      for (var refused :
          List.of(
              new PtyMessage.Create("x", List.of(), work.toString(), "", "", 80, 24),
              new PtyMessage.Kill("victim"))) {
        dispatch.send(refused);
        assertEquals(
            "The dispatch authority may only yield and list sessions.",
            assertInstanceOf(PtyMessage.Err.class, dispatch.answer()).message(),
            refused.toString());
      }
      dispatch.send(new PtyMessage.Yield("victim", "displaced"));
      assertEquals(new PtyMessage.Ok(), dispatch.answer());
    }
    try (var owner = Client.connect(socket())) {
      assertEquals(List.of(), owner.list());
    }
  }

  /** Scenario 11, at the limit and one byte past it. */
  @Test
  void aSocketPathLongerThanEverySystemTakesIsRefusedAtStartNamingThePathAndTheLimit()
      throws Exception {
    var past = homeWithSocketOf(LocalPtyHost.MAX_SOCKET_PATH_BYTES + 1);

    var refused = assertThrows(IOException.class, () -> start(at(past)));

    assertEquals(
        "This machine's terminal host cannot listen at "
            + LocalPtyHost.socketOf(at(past))
            + ": the path is 103 bytes, and a unix socket's path may be at most 102 bytes on"
            + " every system the host runs on. Run it as an account whose home directory is"
            + " shorter.",
        refused.getMessage());
    try (var made = Files.list(past)) {
      assertEquals(List.of(), made.toList(), "nothing was made");
    }

    var limit = at(homeWithSocketOf(LocalPtyHost.MAX_SOCKET_PATH_BYTES));
    start(limit);
    try (var client = Client.connect(LocalPtyHost.socketOf(limit))) {
      assertEquals(List.of(), client.list());
    }
  }

  private Path homeWithSocketOf(int bytes) {
    var pad = bytes - dir.toString().length() - "/".length() - "/.sail/local/pty.sock".length();
    var home = dir.resolve("h".repeat(pad));
    assertEquals(bytes, home.resolve(".sail/local/pty.sock").toString().length());
    return home;
  }

  private static String permissions(Path path) throws IOException {
    return PosixFilePermissions.toString(
        Files.getPosixFilePermissions(path, LinkOption.NOFOLLOW_LINKS));
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
     * A line typed into a session's shell that answers who runs it and where: the shell's own
     * command line, its folder, its terminal and its character set, after {@code @42@}, which only
     * an evaluated line holds, never the terminal's echo of the typed one.
     */
    static final String PROBE =
        "echo \"@$((6*7))@$(ps -o args= -p $$)@$(pwd)@$TERM@$(locale charmap)@\"\n";

    /**
     * A session's command that says what the host put in its environment. {@code sh -c} reads no
     * profile, where a login shell's start-up may set variables of its own: a macOS runner's
     * profile sets {@code LANG}.
     */
    static final List<String> ENVIRONMENT =
        List.of("sh", "-c", "echo \"@$((6*7))@$HOME@$USER@$LOGNAME@$SHELL@$LANG@\"; exec cat");

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
