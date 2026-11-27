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
import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
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
@Timeout(value = 60, unit = TimeUnit.SECONDS)
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
  void aProcessThatNoLongerReadsIsEndedAndTheMessageGoesToTheNext() {
    var session = bench.samsChat();
    bench.send(session, "first");
    var fake = bench.says(INIT, ENDS);
    fake.breaksStdin();

    var sent = bench.send(session, "second");
    fake.settle();
    bench.send(session, "third");

    assertFalse(sent.queued());
    assertEquals(2, bench.processes.starts.size());
    assertEquals(
        List.of(LiveStream.userMessage("second")),
        bench.processes.latest().written,
        "the message no process took goes first to the next");
  }

  @Test
  void anImageGoneBeforeItsMessageWasHandedIsSaidAndTheMessageGoesWithoutIt() {
    var session = bench.samsChat();
    bench.send(session, "first");
    bench.says(INIT);
    as("sam", () -> bench.host.send(session, "look", List.of("red.png")));
    bench.incus.on("sail-files", new ShellExec.Result(20, "", ""));

    var fake = bench.says(ENDS);

    assertEquals(
        List.of(LiveStream.userMessage("first"), LiveStream.userMessage("look")), fake.written);
    assertEquals(
        "Attachment red.png is not a file Sail may read in workspace try. The message went"
            + " without its images.",
        bench.items(session).get(4).get("words"));
  }

  @Test
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
  void aProcessWhoseOutputBreaksIsOverAndItsTurnCut() {
    var session = bench.samsChat();
    bench.send(session, "hi");
    var fake = bench.says(INIT);

    fake.breaks(new IOException("lost"));
    fake.settle();

    assertEquals(List.of("message", "session", "told", "interrupted"), bench.kinds(session));
  }

  @Test
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
  void anEndInterruptedWhileItWaitsSignalsAtOnce() throws Exception {
    var patient = Duration.ofMinutes(5);
    var host = bench.restarted(new ProcessEnd.Grace(patient, patient, patient, patient));
    var session = bench.samsChat();
    as("sam", () -> host.send(session, "hi", List.of()));
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
    as("sam", () -> host.send(session, "hi", List.of()));
    var fake = bench.says(INIT, ENDS);

    host.close();

    assertTrue(fake.stdinClosed());
    assertEquals(List.of(), fake.signals);
  }

  @Test
  void aReadInterruptedWhileItWaitsAnswersNothing() {
    var session = bench.samsChat();
    bench.readsWait();
    Thread.currentThread().interrupt();

    var read = bench.read(session, 0);

    assertEquals(new ChatHost.Read(List.of(), false), read);
    assertTrue(Thread.interrupted(), "the interrupt is kept");
  }

  @Test
  void aReadFromBeforeTheFirstItemIsRefused() {
    var session = bench.samsChat();

    var refused = assertThrows(IllegalArgumentException.class, () -> bench.read(session, -1));

    assertEquals("after is the seq of the last item read, 0 for none.", refused.getMessage());
  }

  @Test
  void aCallerOnNoRosterOpensNoChat() {
    var refused =
        assertThrows(
            ApiException.class,
            () ->
                as("ghost", () -> bench.host.open("acme", "try", "claude-code", "default", true)));

    assertEquals("forbidden", refused.code());
  }

  @Test
  void aWorkspaceGoneOrADoorThatCannotSayIsRefusedSo() {
    var session = bench.samsChat();
    bench.incus.on("incus list ^acme-try$", new ShellExec.Result(0, "[]", ""));
    var gone = assertThrows(ApiException.class, () -> bench.read(session, 0));
    bench.incus.onThrow("incus list ^acme-try$", new IOException("incus is down"));
    var unanswered = assertThrows(ApiException.class, () -> bench.read(session, 0));

    assertEquals(List.of("not_found", "container_error"), List.of(gone.code(), unanswered.code()));
  }

  @Test
  void anImageTheWorkspaceCannotBeAskedForIsRefusedAsTheContainersFault() {
    var session = bench.samsChat();
    bench.incus.onThrow("sail-files", new IOException("incus is down"));

    var refused =
        assertThrows(
            ApiException.class,
            () -> as("sam", () -> bench.host.send(session, "look", List.of("red.png"))));

    assertEquals(
        List.of("container_error", "Attachment red.png could not be read in workspace try."),
        List.of(refused.code(), refused.reason()));
  }

  /** I10: what is no path in the workspace is refused before anything reaches it. */
  @Test
  void anAttachmentThatIsNoPathIsRefusedBeforeTheWorkspaceIsAsked() {
    var session = bench.samsChat();
    var asked = bench.incus.invocations().size();

    var refused =
        Stream.of(" ", "a\0b.png", "a".repeat(4097) + ".png")
            .map(
                path ->
                    assertThrows(
                            IllegalArgumentException.class,
                            () -> as("sam", () -> bench.host.send(session, "look", List.of(path))))
                        .getMessage())
            .distinct()
            .toList();

    assertEquals(List.of("An attachment is a path in the workspace."), refused);
    assertEquals(
        List.of(),
        bench.incus.invocations().subList(asked, bench.incus.invocations().size()).stream()
            .filter(command -> command.contains("sail-files"))
            .toList());
  }

  /** A read of an attachment cut short by an interrupt keeps the interrupt and says so. */
  @Test
  void anAttachmentsReadInterruptedKeepsTheInterrupt() {
    ShellExec interrupted =
        new ShellExec() {
          @Override
          public Result exec(List<String> command) throws InterruptedException {
            throw new InterruptedException("stopping");
          }

          @Override
          public Result exec(List<String> command, Path workDir, Duration timeout)
              throws InterruptedException {
            throw new InterruptedException("stopping");
          }

          @Override
          public boolean isDryRun() {
            return false;
          }
        };
    var chat = bench.store.find(bench.samsChat()).orElseThrow();

    var refused =
        assertThrows(
            ApiException.class,
            () -> new ChatAttachments(interrupted).images(chat, List.of("red.png")));

    assertTrue(Thread.interrupted(), "the interrupt is kept");
    assertEquals(
        List.of("container_error", "Attachment red.png could not be read in workspace try."),
        List.of(refused.code(), refused.reason()));
  }

  @Test
  void theRealTimingWaitsOnTheArrivalsItIsGivenForItsBound() throws Exception {
    var timing = ChatTiming.real();
    var arrivals = new Object();

    synchronized (arrivals) {
      timing.patience().await(arrivals, Duration.ofMillis(1));
    }

    assertEquals(
        List.of(Duration.ofSeconds(25), Duration.ofMinutes(5), ProcessEnd.Grace.DEFAULT),
        List.of(timing.poll(), timing.requestBound(), timing.grace()));
  }

  @Test
  void theSweepRunsOnItsScheduleUntilTheHostCloses() {
    bench.host.startSweep(Duration.ofHours(1));

    bench.host.close();

    assertEquals(0, bench.processes.starts.size());
  }
}
