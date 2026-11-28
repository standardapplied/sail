/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.api;

import static ai.singlr.sail.api.ChatBench.as;
import static ai.singlr.sail.api.ChatBench.line;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.sail.engine.AgentProcess;
import ai.singlr.sail.engine.LiveStream;
import ai.singlr.sail.engine.ShellExec;
import ai.singlr.sail.harness.Harnesses;
import ai.singlr.sail.store.ProjectStore;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/**
 * What a chat meets at its edges: a process that stops reading or answering, an image gone before
 * its message was handed, text whose window closes on its own, a read or an end interrupted, a door
 * that cannot say, a caller on no roster, and the host's own wiring.
 */
class ChatEdgesTest {

  private static final String INIT = line("04b-set-permission-mode-and-background-task", 148);
  private static final String ENDS = line("02b-relay-stdio", 138);

  @TempDir Path dir;
  private ChatBench bench;

  @BeforeEach
  void setUp() {
    bench = new ChatBench(dir);
  }

  @AfterEach
  void tearDown() {
    bench.close();
  }

  @Test
  @Timeout(value = 60, unit = TimeUnit.SECONDS)
  void aProcessThatNoLongerReadsIsEndedAndTheMessageGoesToTheNext() {
    var session = bench.samsChat();
    bench.send(session, "first");
    var fake = bench.says(INIT, ENDS);
    fake.breaksStdin();

    var sent = bench.send(session, "second");
    fake.settle();
    var third = bench.send(session, "third");

    assertFalse(sent.queued());
    assertTrue(third.queued(), "the next process has its turn open on the second");
    assertEquals(2, bench.processes.starts.size());
    assertEquals(
        List.of(LiveStream.userMessage("second")),
        bench.processes.latest().written,
        "the message no process took goes first to the next");
  }

  @Test
  @Timeout(value = 60, unit = TimeUnit.SECONDS)
  void textWhoseWindowClosesWithNoLineAfterItIsLoggedOnItsOwn() throws Exception {
    bench.windowsWait();
    var session = bench.samsChat();
    bench.send(session, "hi");
    bench.says(line("01-baseline-live-flags", 20), line("01-baseline-live-flags", 21));
    bench.readsWait();

    var read = CompletableFuture.supplyAsync(() -> bench.read(session, 1));
    bench.windows.release();

    assertEquals("done", read.get(30, TimeUnit.SECONDS).items().getFirst().get("text"));
  }

  @Test
  @Timeout(value = 60, unit = TimeUnit.SECONDS)
  void aProcessWhoseOutputBreaksIsOverAndItsTurnCut() {
    var session = bench.samsChat();
    bench.send(session, "hi");
    var fake = bench.says(INIT);

    fake.breaks(new IOException("lost"));
    fake.settle();

    assertEquals(List.of("message", "session", "told", "interrupted"), bench.kinds(session));
  }

  @Test
  @Timeout(value = 60, unit = TimeUnit.SECONDS)
  void aProcessWhoseExitCannotBeAskedIsSaidWithoutOne() {
    var session = bench.samsChat();
    bench.send(session, "hi");
    var fake = bench.says(INIT);
    fake.interruptsWait();

    fake.vanishes();
    fake.settle();

    assertEquals(
        "Claude Code's process ended before its turn did; your next message starts it again.",
        bench.items(session).get(2).get("words"));
  }

  @Test
  @Timeout(value = 60, unit = TimeUnit.SECONDS)
  void anEndInterruptedWhileItWaitsSignalsAtOnce() throws Exception {
    var patient = Duration.ofMinutes(5);
    var host = bench.restarted(new ProcessEnd.Grace(patient, patient, patient, patient));
    var session = bench.samsChat();
    as("sam", () -> host.send(session, "hi"));
    var fake = bench.says(INIT, ENDS);
    fake.onClose(() -> {});
    var ending = Thread.ofVirtual().start(host::close);
    fake.awaitStdinClosed();

    ending.interrupt();
    ending.join(Duration.ofSeconds(30));

    assertFalse(ending.isAlive());
    assertEquals(
        List.of(AgentProcess.Signal.INT, AgentProcess.Signal.TERM),
        fake.signals,
        "interrupted, it signals at once rather than wait out its grace");
    fake.exits(0);
    fake.settle();
  }

  /**
   * An end is over once the process is: one that exits as its stdin closes is never waited out for
   * its grace, here five minutes a step, nor signalled.
   */
  @Test
  @Timeout(value = 30, unit = TimeUnit.SECONDS)
  void aProcessThatEndsOnItsOwnIsNotWaitedOutForItsGrace() {
    var patient = Duration.ofMinutes(5);
    var host = bench.restarted(new ProcessEnd.Grace(patient, patient, patient, patient));
    var session = bench.samsChat();
    as("sam", () -> host.send(session, "hi"));
    var fake = bench.says(INIT, ENDS);

    host.close();

    assertTrue(fake.stdinClosed());
    assertEquals(List.of(), fake.signals);
  }

  @Test
  @Timeout(value = 60, unit = TimeUnit.SECONDS)
  void aReadInterruptedWhileItWaitsAnswersNothing() {
    var session = bench.samsChat();
    bench.readsWait();
    Thread.currentThread().interrupt();

    var read = bench.read(session, 0);

    assertEquals(new ChatHost.Read(List.of(), false), read);
    assertTrue(Thread.interrupted(), "the interrupt is kept");
  }

  @Test
  @Timeout(value = 60, unit = TimeUnit.SECONDS)
  void aReadFromBeforeTheFirstItemIsRefused() {
    var session = bench.samsChat();

    var refused = assertThrows(IllegalArgumentException.class, () -> bench.read(session, -1));

    assertEquals("after is the seq of the last item read, 0 for none.", refused.getMessage());
  }

  @Test
  @Timeout(value = 60, unit = TimeUnit.SECONDS)
  void aCallerOnNoRosterOpensNoChat() {
    var refused =
        assertThrows(
            ApiException.class,
            () ->
                as(
                    "ghost",
                    () -> bench.host.open("acme", "try", "claude-code", "default", true, true)));

    assertEquals("forbidden", refused.code());
  }

  @Test
  @Timeout(value = 60, unit = TimeUnit.SECONDS)
  void aWorkspaceGoneOrADoorThatCannotSayIsRefusedSo() {
    var session = bench.samsChat();
    bench.incus.on("incus list ^acme-try$", new ShellExec.Result(0, "[]", ""));
    var gone = assertThrows(ApiException.class, () -> bench.read(session, 0));
    bench.incus.onThrow("incus list ^acme-try$", new IOException("incus is down"));
    var unanswered = assertThrows(ApiException.class, () -> bench.read(session, 0));

    assertEquals(List.of("not_found", "container_error"), List.of(gone.code(), unanswered.code()));
  }

  @Test
  @Timeout(value = 60, unit = TimeUnit.SECONDS)
  void theRealTimingWaitsOnTheArrivalsItIsGivenForItsBound() throws Exception {
    var timing = ChatTiming.real();
    var arrivals = new Object();

    synchronized (arrivals) {
      timing.patience().await(arrivals, Duration.ofMillis(1));
    }

    assertEquals(
        List.of(Duration.ofSeconds(25), Duration.ofMinutes(5), ProcessEnd.Grace.DEFAULT),
        List.of(timing.poll(), timing.stopBound(), timing.grace()));
  }

  @Test
  @Timeout(value = 60, unit = TimeUnit.SECONDS)
  void theSweepRunsOnItsScheduleUntilTheHostCloses() {
    bench.host.start(Duration.ofHours(1));

    bench.host.close();

    assertEquals(0, bench.processes.starts.size());
  }

  /** A chat's folder its workspace cannot remove stays, said so; an interrupt is kept. */
  @Test
  @Timeout(value = 60, unit = TimeUnit.SECONDS)
  void aChatsFolderItsWorkspaceCannotRemoveStaysAndAnInterruptIsKept() {
    var chat = bench.store.find(bench.samsChat()).orElseThrow();

    launchOver(failing(new IOException("incus is down"))).forget(chat);
    launchOver(failing(new InterruptedException("stopping"))).forget(chat);

    assertTrue(Thread.interrupted(), "the interrupt is kept");
  }

  /** The bench's chat launch, its workspaces asked after through {@code shell}. */
  private ChatLaunch launchOver(ShellExec shell) {
    return new ChatLaunch(
        bench.door,
        bench.fdes,
        ProjectReader.ofCatalog(new ProjectStore(bench.db)),
        bench.processes,
        shell,
        TestAuth.roles(bench.db),
        Harnesses::of);
  }

  /** A shell whose every command fails with {@code failure}. */
  private static ShellExec failing(Exception failure) {
    return new ShellExec() {
      @Override
      public Result exec(List<String> command) throws IOException, InterruptedException {
        if (failure instanceof InterruptedException interrupted) {
          throw interrupted;
        }
        throw (IOException) failure;
      }

      @Override
      public Result exec(List<String> command, Path workDir, Duration timeout)
          throws IOException, InterruptedException {
        return exec(command);
      }

      @Override
      public boolean isDryRun() {
        return false;
      }
    };
  }
}
