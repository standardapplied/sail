/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.pty;

import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.stream.Stream;

/**
 * A container that is a directory here, for the upload tests: each script the host sends a
 * container runs on this machine as this account, under a root of the test's own, with this account
 * standing for both root and the dev user. Each knob makes one script fail, hang or wait for the
 * test the way a real container could. The scripts are a Linux container's (GNU {@code find},
 * {@code stat} and {@code df}), so the tests that use it run on Linux alone.
 */
final class ContainerDirectory {

  private final Path dir;

  /** Every command a container was handed, in order. */
  final LinkedBlockingQueue<List<String>> commands = new LinkedBlockingQueue<>();

  /** Each script but an open, put here once it exited, which its caller waits for anyway. */
  final LinkedBlockingQueue<String> finished = new LinkedBlockingQueue<>();

  /** The same scripts, every one, in the order they exited. */
  final List<String> order = Collections.synchronizedList(new ArrayList<>());

  /** Every copy's process, as it started. */
  final List<Process> copies = Collections.synchronizedList(new ArrayList<>());

  /**
   * Whether each copy stops taking its input once open, as a stalled container's would: a process
   * that holds the temporary and outlives the host's ending of its exec, as one inside a workspace
   * outlives its {@code incus exec}.
   */
  volatile boolean copyStalls;

  /** Whether the open's measurement of the directory fails, as a missing tool's would. */
  volatile boolean findFails;

  /** Whose the root must be, when not this account's, standing as root. */
  String rootOwner;

  /** How long the host waits for an open to answer before it gives the open up. */
  Duration patience = Duration.ofSeconds(3);

  /** Whether a copy's input throws as it is written, as a fault no stream declares would. */
  volatile boolean writeThrows;

  /**
   * Whether an abort waits for the session's removal to finish first, the order a race can take.
   */
  volatile boolean abortAfterRemove;

  /** Whether the next open throws as it starts, as a launcher refusing its command would. */
  volatile boolean openThrows;

  /** Whether the next open never answers, as a frozen container's would not. */
  volatile boolean openHangs;

  /** The status the next open exits with instead of running, when set. */
  volatile Integer openExits;

  /** The bytes free an open told to exit by {@link #openExits} says the disk has. */
  static final long FREE = 18L << 20;

  /** Whether each copy into the container keeps only its first byte, as a cut-off stream would. */
  volatile boolean copyTruncates;

  /** What a commit or removal told to exit by its knob says on stderr first: a file's name. */
  static final String SAYS = "ln: failed to access '1-secret-plan.pdf': No such file or directory";

  /** The script that never ends instead of running, when set, as one a frozen container runs. */
  volatile String hangs;

  /** Whether the open's measurement of the disk prints no figure, as a broken {@code df} would. */
  volatile boolean dfSaysNothing;

  /** The status every commit exits with instead of running, when set. */
  volatile Integer commitExits;

  /** Held before a commit runs, when set: a commit the test releases. */
  volatile CountDownLatch commitHeld;

  /** Held before the next open runs, when set, and cleared as it is taken. */
  volatile CountDownLatch openHeld;

  /** Held before the next sweep runs, when set, and cleared as it is taken. */
  volatile CountDownLatch sweepHeld;

  /** The status every removal exits with instead of running, when set. */
  volatile Integer removeExits;

  /** Held before a removal runs, when set. */
  volatile CountDownLatch removeHeld;

  /** Counted down once the host has seen a removal end, for {@link #abortAfterRemove}. */
  final CountDownLatch removed = new CountDownLatch(1);

  /** Held as the host waits for a copy to exit, when set; {@link #copyReached} once it waits. */
  volatile CountDownLatch copyHeld;

  final CountDownLatch copyReached = new CountDownLatch(1);

  ContainerDirectory(Path dir) {
    this.dir = dir;
  }

  /** The uploads root, standing for {@link UploadCopy#ROOT}. */
  Path root() {
    return dir.resolve("var-lib-sail-paste");
  }

  /** The place: this account for root and the dev user, a 3 s patience. */
  UploadCopy.Place place() throws IOException {
    var uid = Files.getAttribute(dir, "unix:uid").toString();
    var gid = Files.getAttribute(dir, "unix:gid").toString();
    Function<String, List<String>> asRoot = container -> List.of("incus", "exec", container, "--");
    Function<String, List<String>> asDev =
        container -> List.of("incus", "exec", container, "--user", uid, "--");
    return new UploadCopy.Place(
        asRoot,
        asDev,
        root().toString(),
        rootOwner == null ? uid : rootOwner,
        uid,
        gid,
        List.of(),
        patience);
  }

  /** Copies into this directory. */
  UploadCopy copies() throws IOException {
    return new UploadCopy(processes, place());
  }

  /** The container: each command run here after its {@code incus exec ... --}, every one kept. */
  final UploadCopy.Processes processes =
      command -> {
        commands.add(List.copyOf(command));
        var inner = new ArrayList<>(command.subList(command.indexOf("--") + 1, command.size()));
        var script = inner.size() > 2 ? inner.get(2) : "";
        if (script.equals(UploadCopy.OPEN)) {
          var held = openHeld;
          openHeld = null;
          await(held);
        }
        if (script.equals(UploadCopy.ABORT) && abortAfterRemove) {
          await(removed);
        }
        if (script.equals(UploadCleanup.SWEEP)) {
          var held = sweepHeld;
          sweepHeld = null;
          await(held);
        }
        if (script.equals(UploadCopy.OPEN) && openThrows) {
          openThrows = false;
          throw new IllegalStateException("the launcher refused the command");
        }
        if (script.equals(UploadCopy.OPEN) && copyStalls) {
          inner.set(2, inner.get(2).replace("cat >&3", "sleep 600 >&3 </dev/null"));
        }
        if (script.equals(UploadCopy.OPEN) && dfSaysNothing) {
          inner.set(2, inner.get(2).replace("df -Pk \"$at\"", "echo Filesystem"));
        }
        if (script.equals(UploadCopy.OPEN) && findFails) {
          inner.set(2, inner.get(2).replace("names=$(find", "names=$(false"));
        }
        if (script.equals(UploadCopy.OPEN) && copyTruncates) {
          inner.set(2, inner.get(2).replace("cat >&3", "{ head -c 1; cat > /dev/null; } >&3"));
        }
        if (script.equals(UploadCopy.OPEN) && openHangs) {
          openHangs = false;
          inner = new ArrayList<>(List.of("sh", "-c", "exec tail -f /dev/null"));
        }
        if (script.equals(UploadCopy.OPEN) && openExits != null) {
          inner = new ArrayList<>(List.of("sh", "-c", "echo free " + FREE + "; exit " + openExits));
        }
        if (script.equals(UploadCopy.COMMIT)) {
          await(commitHeld);
          if (commitExits != null) {
            inner = saying(commitExits);
          }
        }
        if (script.equals(UploadCleanup.REMOVE)) {
          await(removeHeld);
          if (removeExits != null) {
            inner = saying(removeExits);
          }
        }
        if (script.equals(hangs)) {
          inner = new ArrayList<>(List.of("sh", "-c", "exec tail -f /dev/null"));
        }
        var process =
            new ProcessBuilder(inner).redirectError(ProcessBuilder.Redirect.DISCARD).start();
        if (script.equals(UploadCopy.OPEN)) {
          copies.add(process);
        }
        return new HeldCopy(process, script);
      };

  /** A script that says {@link #SAYS} on stderr, then exits {@code status}. */
  private static ArrayList<String> saying(int status) {
    return new ArrayList<>(
        List.of("sh", "-c", "printf '%s\\n' \"$1\" >&2; exit \"$2\"", "sh", SAYS, "" + status));
  }

  /**
   * A script's process as the host has it, unwaited as a container's would be: a copy's exit is
   * waited for only once {@link #copyHeld} lets it, when set, and any other script is counted among
   * those finished when the host sees it end.
   */
  private final class HeldCopy extends Process {
    private final Process process;
    private final String script;
    private boolean counted;

    HeldCopy(Process process, String script) {
      this.process = process;
      this.script = script;
    }

    @Override
    public boolean waitFor(long timeout, TimeUnit unit) throws InterruptedException {
      if (script.equals(UploadCopy.OPEN)) {
        copyReached.countDown();
        await(copyHeld);
        return process.waitFor(timeout, unit);
      }
      var ended = process.waitFor(timeout, unit);
      synchronized (this) {
        if (ended && !counted) {
          counted = true;
          order.add(script);
          finished.add(script);
          if (script.equals(UploadCleanup.REMOVE)) {
            removed.countDown();
          }
        }
      }
      return ended;
    }

    @Override
    public int waitFor() throws InterruptedException {
      return waitFor(1, TimeUnit.MINUTES) ? process.exitValue() : -1;
    }

    @Override
    public OutputStream getOutputStream() {
      if (!writeThrows) {
        return process.getOutputStream();
      }
      return new OutputStream() {
        @Override
        public void write(int b) {
          throw new IllegalStateException("a fault in the copy's input");
        }

        @Override
        public void close() throws IOException {
          process.getOutputStream().close();
        }
      };
    }

    @Override
    public InputStream getInputStream() {
      return process.getInputStream();
    }

    @Override
    public InputStream getErrorStream() {
      return process.getErrorStream();
    }

    @Override
    public int exitValue() {
      return process.exitValue();
    }

    @Override
    public void destroy() {
      process.destroy();
    }

    @Override
    public Process destroyForcibly() {
      return process.destroyForcibly();
    }

    @Override
    public boolean isAlive() {
      return process.isAlive();
    }
  }

  static void await(CountDownLatch latch) {
    if (latch == null) {
      return;
    }
    try {
      latch.await();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  /** Returns once {@code script} exited, after any that exited before it. */
  void awaitFinished(String script) throws InterruptedException {
    while (true) {
      var done = finished.poll(30, TimeUnit.SECONDS);
      assertNotNull(done, "no script finished " + script.lines().findFirst().orElse(""));
      if (done.equals(script)) {
        return;
      }
    }
  }

  /** Returns once a command running {@code script} was handed to a container. */
  void awaitCommand(String script) throws InterruptedException {
    while (true) {
      var command = commands.poll(30, TimeUnit.SECONDS);
      assertNotNull(command, "no command ran " + script.lines().findFirst().orElse(""));
      if (command.contains(script)) {
        return;
      }
    }
  }

  /** The files and links {@code directory} holds, by name. */
  /** The processes that hold a file in {@code directory} open, by their ids. */
  static List<Long> holders(Path directory) {
    var prefix = directory.toAbsolutePath().toString() + "/";
    return ProcessHandle.allProcesses()
        .map(ProcessHandle::pid)
        .filter(pid -> holds(pid, prefix))
        .toList();
  }

  private static boolean holds(long pid, String prefix) {
    try (Stream<Path> fds = Files.list(Path.of("/proc", Long.toString(pid), "fd"))) {
      return fds.anyMatch(fd -> linksUnder(fd, prefix));
    } catch (IOException | UncheckedIOException e) {
      return false;
    }
  }

  private static boolean linksUnder(Path fd, String prefix) {
    try {
      return Files.readSymbolicLink(fd).toString().startsWith(prefix);
    } catch (IOException e) {
      return false;
    }
  }

  static List<String> held(Path directory) throws IOException {
    if (!Files.exists(directory)) {
      return List.of();
    }
    try (Stream<Path> entries = Files.list(directory)) {
      return entries.map(path -> path.getFileName().toString()).sorted().toList();
    }
  }
}
