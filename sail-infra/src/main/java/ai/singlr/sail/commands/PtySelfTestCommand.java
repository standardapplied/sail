/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.commands;

import ai.singlr.sail.SailVersion;
import ai.singlr.sail.common.Secrets;
import ai.singlr.sail.identity.Actor;
import ai.singlr.sail.pty.Account;
import ai.singlr.sail.pty.Locales;
import ai.singlr.sail.pty.Pty;
import ai.singlr.sail.pty.PtyAdmission;
import ai.singlr.sail.pty.PtyEvents;
import ai.singlr.sail.pty.PtyIdentity;
import ai.singlr.sail.pty.PtyMessage;
import ai.singlr.sail.pty.PtySessionHost;
import ai.singlr.sail.pty.PtyWire;
import ai.singlr.sail.pty.SdNotify;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnixDomainSocketAddress;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import picocli.CommandLine.Command;

/**
 * A native-image self-check for the layers that only fail in the GraalVM binary, never in the JVM
 * unit tests, so the release smoke test catches them rather than a user.
 *
 * <p>The FFM pty layer: opens a real pty, spawns a child through the session shim this binary
 * spawns every session under, resizes the pty and reads the size the child's {@code stty} sees.
 * Opening the pty forces {@link Pty}'s static initializer, which builds every libc downcall handle
 * — a missing foreign registration surfaces here — and the shim's claim of the terminal runs each
 * call the child side makes.
 *
 * <p>The descriptor store binding: a datagram receiver stands in for systemd, a pty master is
 * pushed through the production {@link SdNotify} with {@code NOTIFY_SOCKET} pointed at it, the
 * received descriptor is adopted and read through — a real duplicate, not a number — and the
 * removal round-trips. A Panama layout that only breaks in the native image breaks here. Only a
 * system with a store runs it.
 *
 * <p>The terminal's TCP door: a host listening on loopback issues a ticket on its unix socket,
 * which is presented over TCP, opens a session and has a line echoed back. The ticketed connection
 * sets keepalive's idle, interval and count, {@code jdk.net} options backed by native code that
 * only a native image can fail to link.
 *
 * <p>The terminal host on a person's own machine ({@code _pty-host --local}): it starts in a
 * temporary directory, opens a session in a folder running the account's login shell, read from the
 * account database through the binary's own downcalls, has a line evaluated, is detached from and
 * attached to again, finds the line in the replay, and stops.
 *
 * <p>The CLI JSON layer: {@link CliJson} reads record components reflectively, and a record it
 * serializes that is missing from the reflection configuration dies in the native binary with an
 * unsupported-feature error (the {@code sail session ls --json} field failure). Every record a
 * {@code --json} verb prints is stringified here on every platform.
 */
@Command(
    name = "_pty-selftest",
    description =
        "Internal: verify the pty FFM layer, the TCP door, the local host and CLI JSON reflection.",
    hidden = true)
public final class PtySelfTestCommand implements Callable<Integer> {

  /** How long each step of a probe may take before the probe fails instead of waiting on. */
  static final Duration PROBE_STEP = Duration.ofSeconds(10);

  static final List<String> JSON_KEYS =
      List.of("host_boot_id", "instance_id", "event_drops", "spec_title", "dispatched");

  @Override
  public Integer call() {
    try {
      var json = jsonProbe();
      for (var key : JSON_KEYS) {
        if (!json.contains("\"" + key + "\"")) {
          throw new IllegalStateException("CLI JSON lost the key '" + key + "': " + json);
        }
      }
      System.out.println("json-selftest: ok");
    } catch (Throwable e) {
      System.err.println("json-selftest: FAILED: " + e);
      return 1;
    }
    try {
      ptyProbe();
      System.out.println("pty-selftest: ok");
    } catch (Exception e) {
      System.err.println("pty-selftest: FAILED: " + e);
      return 1;
    }
    try {
      tcpProbe();
      System.out.println("tcp-selftest: ok");
    } catch (Throwable e) {
      System.err.println("tcp-selftest: FAILED: " + e);
      return 1;
    }
    try {
      localProbe();
      System.out.println("local-selftest: ok");
    } catch (Throwable e) {
      System.err.println("local-selftest: FAILED: " + e);
      return 1;
    }
    if (!SdNotify.available()) {
      System.out.println("fdstore-selftest: skipped (no descriptor store on this system)");
      return 0;
    }
    try {
      fdStoreProbe();
      System.out.println("fdstore-selftest: ok");
      return 0;
    } catch (Exception e) {
      System.err.println("fdstore-selftest: FAILED: " + e);
      return 1;
    }
  }

  /** Spawns {@code sh -c script} on {@code pty} under the shim, its exit file in {@code dir}. */
  private static Process spawn(Pty pty, Path dir, String script) throws IOException {
    var argv = new ArrayList<>(PtyHostCommand.shimCommand(dir.resolve("probe.exit")));
    argv.addAll(List.of("sh", "-c", script));
    return pty.spawn(argv, Map.of("TERM", "dumb"), dir);
  }

  /** Reads {@code pty} until {@code marker} has been said, or throws with what was. */
  private static void readUntil(Pty pty, String marker) throws IOException {
    var said = readWhile(pty, seen -> !seen.contains(marker));
    if (!said.contains(marker)) {
      throw new IllegalStateException("the pty ended before '" + marker + "': " + said);
    }
  }

  /** Reads {@code pty} to its end of stream: the child gone and its terminal drained. */
  private static String readToEnd(Pty pty) throws IOException {
    return readWhile(pty, seen -> true);
  }

  /**
   * Reads {@code pty} while {@code more} holds of what it said, up to its end of stream. A probe
   * step that takes longer than {@link #PROBE_STEP} closes the pty and fails, rather than leave a
   * release smoke waiting on a child that will never speak.
   */
  private static String readWhile(Pty pty, Predicate<String> more) throws IOException {
    var deadline =
        CompletableFuture.runAsync(
            pty::close,
            CompletableFuture.delayedExecutor(PROBE_STEP.toMillis(), TimeUnit.MILLISECONDS));
    try {
      var buf = new byte[4096];
      var said = new StringBuilder();
      while (more.test(said.toString())) {
        var n = pty.read(buf);
        if (n < 0) {
          break;
        }
        said.append(new String(buf, 0, n, StandardCharsets.UTF_8));
      }
      if (deadline.isDone()) {
        throw new IllegalStateException(
            "the pty said no more within " + PROBE_STEP.toSeconds() + " s: " + said);
      }
      return said.toString();
    } finally {
      deadline.cancel(false);
    }
  }

  /** The child's exit status, once it exits within {@link #PROBE_STEP}. */
  private static int exitOf(Process child) throws InterruptedException {
    if (!child.waitFor(PROBE_STEP.toMillis(), TimeUnit.MILLISECONDS)) {
      child.destroyForcibly();
      throw new IllegalStateException(
          "the child did not exit within " + PROBE_STEP.toSeconds() + " s");
    }
    return child.exitValue();
  }

  /**
   * Opens a pty, resizes it once the child is waiting, and reads the new size from the child's own
   * {@code stty}; the child's exit status comes back through the shim's exit file, taken once the
   * terminal has been read to its end, since on macOS the shim's exit waits for that.
   */
  static void ptyProbe() throws Exception {
    var dir = Files.createTempDirectory("sail-pty");
    try (var pty = Pty.open(80, 24)) {
      var child = spawn(pty, dir, "echo waiting; read go; stty size");
      readUntil(pty, "waiting");
      pty.resize(100, 40);
      pty.write("go\n".getBytes(StandardCharsets.UTF_8));
      var said = readToEnd(pty);
      if (!said.contains("40 100")) {
        throw new IllegalStateException("the child did not see the new size: " + said);
      }
      if (exitOf(child) != 0 || !"0".equals(Files.readString(dir.resolve("probe.exit")))) {
        throw new IllegalStateException("the child did not exit cleanly: " + child.exitValue());
      }
    } finally {
      Files.deleteIfExists(dir.resolve("probe.exit"));
      Files.deleteIfExists(dir);
    }
  }

  /**
   * Starts a host whose TCP door listens on loopback, asks it for a ticket on its unix socket as
   * this box's own process, presents the ticket over TCP, opens a session and reads back a line
   * typed into it. The session runs {@code cat} under this binary's shim, in place of a project
   * container this check has none of; its person and admission are this check's own.
   */
  static void tcpProbe() throws Exception {
    var dir = Files.createTempDirectory("sail-tcp");
    var probe = new PtyIdentity.Person(Actor.system());
    var credential = "selftest-credential";
    var admitAll =
        new PtyAdmission() {
          @Override
          public void box(PtyIdentity.Person who) {}

          @Override
          public void project(String project, PtyIdentity.Person who) {}

          @Override
          public void room(String room, String project, PtyIdentity.Person who) {}
        };
    var socket = dir.resolve("pty.sock");
    try (var host =
        new PtySessionHost(
            socket,
            dir.resolve("sessions"),
            64 * 1024,
            token -> probe,
            admitAll,
            PtyEvents.NONE,
            SailVersion.version(),
            new PtySessionHost.Handoff(
                SdNotify.NONE,
                Map.of(),
                exit -> {
                  var argv = new ArrayList<>(PtyHostCommand.shimCommand(exit));
                  argv.addAll(List.of("sh", "-c", "exec cat", "selftest"));
                  return argv;
                }),
            PtySessionHost.Network.listening(
                new InetSocketAddress(InetAddress.getLoopbackAddress(), 0),
                System::nanoTime,
                hash -> {
                  if (!Secrets.hash(credential).equals(hash)) {
                    throw new IOException("not the selftest's credential");
                  }
                  return probe;
                }))) {
      host.start();
      if (!(SessionClient.ticket(socket, credential, PtyMessage.TicketAction.OPEN, "selftest")
          instanceof PtyMessage.Ticket(var ticket))) {
        throw new IllegalStateException("the host issued no ticket");
      }
      try (var tcp = SocketChannel.open(host.tcpAddress().orElseThrow())) {
        var deadline =
            CompletableFuture.runAsync(
                () -> closeQuietly(tcp),
                CompletableFuture.delayedExecutor(PROBE_STEP.toMillis(), TimeUnit.MILLISECONDS));
        try {
          PtyWire.handshake(tcp, tcp);
          PtyWire.write(tcp, new PtyMessage.Hello(ticket));
          expect(PtyMessage.Welcome.class, PtyWire.read(tcp));
          PtyWire.write(
              tcp,
              new PtyMessage.Create("selftest", List.of(), dir.toString(), "selftest", "", 80, 24));
          expect(PtyMessage.Ok.class, PtyWire.read(tcp));
          PtyWire.write(tcp, new PtyMessage.Attach("selftest", true));
          expect(PtyMessage.Ok.class, PtyWire.read(tcp));
          PtyWire.write(
              tcp, new PtyMessage.Input(1, "over-tcp\n".getBytes(StandardCharsets.UTF_8)));
          var said = new StringBuilder();
          while (!said.toString().contains("over-tcp")) {
            if (PtyWire.read(tcp) instanceof PtyMessage.Output(var seq, var bytes)) {
              said.append(new String(bytes, StandardCharsets.UTF_8));
            }
          }
        } finally {
          deadline.cancel(false);
        }
      }
    } finally {
      try (var left = Files.walk(dir)) {
        for (var path : left.sorted(Comparator.reverseOrder()).toList()) {
          Files.deleteIfExists(path);
        }
      }
    }
  }

  /**
   * Starts the local host as {@code _pty-host --local} does, for this account in a temporary home,
   * and opens a session there with no command, so the account's login shell runs; has it evaluate
   * {@code local-$((6*7))}, which its terminal's echo of the typed line never reads as, detaches,
   * attaches again from a new connection and finds the answer in the replay, then stops.
   */
  static void localProbe() throws Exception {
    var dir = Files.createTempDirectory("sail-local");
    var account = Account.current();
    var access =
        new LocalPtyAccess(
            new Account(account.name(), dir, account.shell()), System.getenv(), Locales::installed);
    try (var host = LocalPtyHost.start(access, PtyHostCommand::shimCommand)) {
      var socket = host.socket();
      try (var first = SocketChannel.open(UnixDomainSocketAddress.of(socket))) {
        converse(
            first,
            () -> {
              hello(first);
              PtyWire.write(
                  first, new PtyMessage.Create("probe", List.of(), dir.toString(), "", "", 80, 24));
              expect(PtyMessage.Ok.class, PtyWire.read(first));
              PtyWire.write(first, new PtyMessage.Attach("probe", true));
              expect(PtyMessage.Ok.class, PtyWire.read(first));
              PtyWire.write(
                  first,
                  new PtyMessage.Input(
                      1, "echo local-$((6*7))\n".getBytes(StandardCharsets.UTF_8)));
              outputUntil(first, "local-42");
              PtyWire.write(first, new PtyMessage.Detach());
              readTo(first, PtyMessage.Ok.class);
            });
      }
      try (var again = SocketChannel.open(UnixDomainSocketAddress.of(socket))) {
        converse(
            again,
            () -> {
              hello(again);
              PtyWire.write(again, new PtyMessage.Attach("probe", false));
              expect(PtyMessage.Ok.class, PtyWire.read(again));
              outputUntil(again, "local-42");
            });
      }
      if (host.host().sessionCount() != 1) {
        throw new IllegalStateException("the session did not outlive its client");
      }
    } finally {
      try (var left = Files.walk(dir)) {
        for (var path : left.sorted(Comparator.reverseOrder()).toList()) {
          Files.deleteIfExists(path);
        }
      }
    }
  }

  /** One step of a probe's conversation on {@code channel}, closed if it outlasts the step. */
  private interface Step {
    void run() throws IOException;
  }

  private static void converse(SocketChannel channel, Step step) throws IOException {
    var deadline =
        CompletableFuture.runAsync(
            () -> closeQuietly(channel),
            CompletableFuture.delayedExecutor(PROBE_STEP.toMillis(), TimeUnit.MILLISECONDS));
    try {
      step.run();
    } finally {
      deadline.cancel(false);
    }
  }

  private static void hello(SocketChannel channel) throws IOException {
    PtyWire.handshake(channel, channel);
    PtyWire.write(channel, new PtyMessage.Hello(""));
    expect(PtyMessage.Welcome.class, PtyWire.read(channel));
  }

  /** Reads {@code channel}'s output until {@code marker} has been said. */
  private static void outputUntil(SocketChannel channel, String marker) throws IOException {
    var said = new StringBuilder();
    while (!said.toString().contains(marker)) {
      switch (PtyWire.read(channel)) {
        case PtyMessage.Output(var seq, var bytes) ->
            said.append(new String(bytes, StandardCharsets.UTF_8));
        case PtyMessage.SessionEnded(var reason) ->
            throw new IllegalStateException("the session ended (" + reason + "): " + said);
        default -> {}
      }
    }
  }

  /** Reads {@code channel} past whatever the session says meanwhile, to a {@code wanted} frame. */
  private static void readTo(SocketChannel channel, Class<? extends PtyMessage> wanted)
      throws IOException {
    var frame = PtyWire.read(channel);
    while (!wanted.isInstance(frame)) {
      frame = PtyWire.read(channel);
    }
  }

  private static void expect(Class<? extends PtyMessage> wanted, PtyMessage got) {
    if (!wanted.isInstance(got)) {
      throw new IllegalStateException("expected " + wanted.getSimpleName() + ", got " + got);
    }
  }

  private static void closeQuietly(SocketChannel channel) {
    try {
      channel.close();
    } catch (IOException ignored) {
      var unused = ignored;
    }
  }

  /** Pushes a pty master through a stand-in store and reads the child's output via the copy. */
  static void fdStoreProbe() throws Exception {
    var dir = Files.createTempDirectory("sail-fdstore");
    try (var receiver = SdNotify.Receiver.bind(dir.resolve("notify.sock"));
        var pty = Pty.open(80, 24)) {
      var store = SdNotify.to(receiver.path().toString());
      store.storeFd("probe", pty.fd());
      var stored = receiver.receive();
      if (!"1".equals(stored.field("FDSTORE")) || !"probe".equals(stored.field("FDNAME"))) {
        throw new IllegalStateException("unexpected store message: " + stored.text());
      }
      if (stored.fds().size() != 1) {
        throw new IllegalStateException("expected one descriptor, got " + stored.fds());
      }
      try (var copy = Pty.adopt(stored.fds().getFirst())) {
        var child = spawn(pty, dir, "echo through-the-store");
        readUntil(copy, "through-the-store");
        exitOf(child);
      }
      store.removeFd("probe");
      var removed = receiver.receive();
      if (!"1".equals(removed.field("FDSTOREREMOVE")) || !"probe".equals(removed.field("FDNAME"))) {
        throw new IllegalStateException("unexpected removal message: " + removed.text());
      }
    } finally {
      Files.deleteIfExists(dir.resolve("probe.exit"));
      Files.deleteIfExists(dir.resolve("notify.sock"));
      Files.deleteIfExists(dir);
    }
  }

  /** Stringifies one instance of every record a {@code --json} verb prints. */
  static String jsonProbe() {
    var info =
        new PtyMessage.SessionInfo("probe", "instance", true, 0, "fde", "", List.of("bash", "-l"));
    var listing = new SessionCommand.Ls.Listing(1, "boot", List.of(info), PtyEventDrops.Drops.NONE);
    var preview = new DispatchCommand.DispatchPreview("box", "spec", "title", "foreground", "task");
    var idle = new DispatchCommand.NoDispatch("box", false, "no_pending_specs");
    return CliJson.stringify(List.of(listing, preview, idle));
  }
}
