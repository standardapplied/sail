/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.pty;

import static ai.singlr.sail.pty.ContainerDirectory.held;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.sail.engine.ClosesWhatItOpens;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

/**
 * Uploads leaving a {@link ContainerDirectory}, by the scripts production sends a container: a
 * session's directory removed, and a sweep that keeps what is live; no delete follows a link. Each
 * test plants the link or the name the rule it names must not be fooled by.
 */
@EnabledOnOs(OS.LINUX)
@Timeout(value = 60, unit = TimeUnit.SECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
class UploadCleanupTest {

  private static final String ID = "01a1ea00-0000-7000-8000-000000000001";
  private static final String STALE = "0f0e0d0c-0000-0000-0000-000000000000";

  @TempDir Path dir;

  private ContainerDirectory c;
  private UploadCleanup cleanup;
  private Path outside;

  @BeforeEach
  void container() throws Exception {
    c = new ContainerDirectory(dir);
    cleanup = new UploadCleanup(c.copies());
    outside = Files.createDirectories(dir.resolve("home"));
    Files.writeString(outside.resolve("work"), "the person's own");
  }

  /**
   * Ends what each test started, as the host does as it stops: a run outlives the exit of its
   * script until it has closed the script's output, so a test that ends at the exit would leave
   * that pipe.
   */
  @AfterEach
  void closeTheCleanup() {
    cleanup.close(UploadCleanup.CLOSING);
  }

  private Path root() throws Exception {
    Files.createDirectories(c.root());
    Files.setPosixFilePermissions(c.root(), PosixFilePermissions.fromString("rwxr-xr-x"));
    return c.root();
  }

  private Path directory(String id) throws Exception {
    var directory = Files.createDirectories(root().resolve(id));
    Files.writeString(directory.resolve("1-a.png"), "uploaded");
    return directory;
  }

  /**
   * Closing waits for a removal still running, so none outlives the host that started it: the
   * directory is gone and its script has ended by the time {@code close} returns.
   */
  @Test
  void closingWaitsForARemovalStillRunning() throws Exception {
    var session = directory(ID);

    cleanup.remove("acme", ID, null);
    cleanup.close(UploadCleanup.CLOSING);

    assertEquals(List.of(UploadCleanup.REMOVE), List.copyOf(c.finished));
    assertFalse(Files.exists(session, LinkOption.NOFOLLOW_LINKS));
  }

  /**
   * Closing gives every removal still running one deadline together, not one each: three that never
   * return, closed with a second, take about that second, never three.
   */
  @Test
  void closingWaitsForEveryRemovalWithinOneDeadline() throws Exception {
    var session = new ReentrantLock();
    session.lock();
    try {
      for (var id : List.of(ID, STALE, "0a0b0c0d-1111-1111-1111-111111111111")) {
        cleanup.remove("acme", id, session);
      }

      assertTimeoutPreemptively(
          Duration.ofSeconds(2), () -> cleanup.close(Duration.ofSeconds(1)), "one second in all");
    } finally {
      session.unlock();
    }
    cleanup.close(UploadCleanup.CLOSING);
  }

  /**
   * A sweep's output is still open once its script is seen to exit, until the JDK's reaper or the
   * sweep's own run closes it, and closing waits for that run: nothing of the sweep is open once
   * closing returns, though the run let go of its output only while closing waited.
   */
  @Test
  void closingWaitsForARunStillHoldingItsScriptsOutput() throws Exception {
    var before = ClosesWhatItOpens.baseline();
    var closer = Thread.currentThread();
    c.outputHeld = new CountDownLatch(1);
    try {
      cleanup.sweep("acme", List::of);
      c.awaitFinished(UploadCleanup.SWEEP);
      c.outputReached.await();
      var open =
          assertThrows(
              AssertionError.class, () -> ClosesWhatItOpens.assertClosed("a sweep", before));
      assertTrue(open.getMessage().contains(" pipe:["), open.getMessage());

      Thread.ofVirtual().start(() -> letGoOnceWaiting(closer));
      cleanup.close(UploadCleanup.CLOSING);

      ClosesWhatItOpens.assertClosed("a closed cleanup", before);
    } finally {
      c.outputHeld.countDown();
    }
  }

  private void letGoOnceWaiting(Thread closer) {
    while (closer.getState() != Thread.State.TIMED_WAITING && c.outputHeld.getCount() > 0) {
      Thread.onSpinWait();
    }
    c.outputHeld.countDown();
  }

  /** Major 5: links a session's directory holds are removed as links, never followed. */
  @Test
  void aSessionsDirectoryGoesWithItsLinksRemovedAsLinks() throws Exception {
    var session = directory(ID);
    Files.createSymbolicLink(session.resolve("2-a"), outside);
    Files.createSymbolicLink(session.resolve("3-a"), outside.resolve("work"));

    cleanup.remove("acme", ID, null);
    c.awaitFinished(UploadCleanup.REMOVE);

    assertFalse(Files.exists(session, LinkOption.NOFOLLOW_LINKS));
    assertEquals(List.of("work"), held(outside));
    assertEquals("the person's own", Files.readString(outside.resolve("work")));
  }

  /** Major 5: a session's directory that is a link goes as the link; what it names stays. */
  @Test
  void aSessionsDirectoryThatIsALinkGoesAsTheLink() throws Exception {
    var session = Files.createSymbolicLink(root().resolve(ID), outside);

    cleanup.remove("acme", ID, null);
    c.awaitFinished(UploadCleanup.REMOVE);

    assertFalse(Files.exists(session, LinkOption.NOFOLLOW_LINKS));
    assertEquals(List.of("work"), held(outside));
  }

  /** Major 5: a root that is a link is neither removed through nor swept. */
  @Test
  void aRootThatIsALinkIsNeitherRemovedThroughNorSwept() throws Exception {
    Files.createDirectories(outside.resolve(ID));
    Files.createDirectories(outside.resolve(STALE));
    Files.createSymbolicLink(c.root(), outside);

    cleanup.remove("acme", ID, null);
    c.awaitFinished(UploadCleanup.REMOVE);
    cleanup.sweep("acme", List::of);
    c.awaitFinished(UploadCleanup.SWEEP);

    assertEquals(List.of(ID, STALE, "work"), held(outside));
  }

  @Test
  void aSweepRemovesOnlyTheDirectoriesOfSessionsNotLive() throws Exception {
    var live = directory(ID);
    var stale = directory(STALE);
    var notAnId = Files.createDirectories(root().resolve("keep-me"));
    Files.createSymbolicLink(root().resolve("0a0b0c0d-1111-1111-1111-111111111111"), outside);

    cleanup.sweep("acme", () -> List.of(ID));
    c.awaitFinished(UploadCleanup.SWEEP);

    assertFalse(Files.exists(stale));
    assertTrue(Files.exists(live.resolve("1-a.png")), "a live session's file is kept");
    assertTrue(Files.exists(notAnId), "a name that is no instance id is kept");
    assertEquals(List.of("work"), held(outside), "no link followed");
  }

  @Test
  void aRootNotYetMadeIsNothingToRemoveOrSweep() throws Exception {
    cleanup.remove("acme", ID, null);
    c.awaitFinished(UploadCleanup.REMOVE);
    cleanup.sweep("acme", List::of);
    c.awaitFinished(UploadCleanup.SWEEP);

    assertFalse(Files.exists(c.root(), LinkOption.NOFOLLOW_LINKS));
  }

  /**
   * Round 2 Minor 6 (N05): a removal waits for its session's lock, which a commit holds, and does
   * not touch the container until it has it.
   */
  @Test
  void aRemovalWaitsForItsSessionsLock() throws Exception {
    directory(ID);
    var session = new ReentrantLock();
    session.lock();
    try {
      cleanup.remove("acme", ID, session);
      while (!session.hasQueuedThreads() && c.commands.isEmpty()) {
        Thread.onSpinWait();
      }
      assertTrue(c.commands.isEmpty(), "the removal ran while the session's lock was held");
    } finally {
      session.unlock();
    }
    c.awaitFinished(UploadCleanup.REMOVE);
    assertFalse(Files.exists(c.root().resolve(ID)));
  }

  /**
   * Round 2 Minor 6 (N30): a sweep waits for its container's lock, which a session's first upload
   * holds, and reads which sessions are live only once it has it.
   */
  @Test
  void aSweepWaitsForItsContainersLockAndReadsTheLiveOnlyThen() throws Exception {
    directory(ID);
    var lock = cleanup.lockOf("acme");
    var read = new java.util.concurrent.atomic.AtomicBoolean();
    lock.lock();
    try {
      cleanup.sweep(
          "acme",
          () -> {
            read.set(true);
            return List.of(ID);
          });
      while (!lock.hasQueuedThreads() && c.commands.isEmpty()) {
        Thread.onSpinWait();
      }
      assertTrue(c.commands.isEmpty(), "the sweep ran while the container's lock was held");
      assertFalse(read.get(), "the live sessions were read before the lock was taken");
    } finally {
      lock.unlock();
    }
    c.awaitFinished(UploadCleanup.SWEEP);
    assertTrue(Files.exists(c.root().resolve(ID)));
  }

  @Test
  void eachContainerHasOneLock() {
    assertTrue(cleanup.lockOf("acme") == cleanup.lockOf("acme"));
    assertFalse(cleanup.lockOf("acme") == cleanup.lockOf("acme-dock"));
  }
}
