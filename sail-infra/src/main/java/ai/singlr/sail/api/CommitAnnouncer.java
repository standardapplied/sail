/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.api;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Tells this box's API server that this process committed to the change log, by waking its
 * change-feed streams ({@link SailEventPublisher#wake}): the server's streams wake on what its own
 * process commits and on such a wake, never on a timer. The wake carries nothing and is delivered
 * to no client; any caller who authenticates may send it. A commit hands it to one thread of its
 * own, since a commit runs its hooks holding the database's lock; commits made while one is waiting
 * are told by it, and one in flight is followed by another. It is best effort: with no server to
 * tell, there is no stream to wake. A process exiting waits at most {@link #DRAIN} for the one it
 * owes, and a wake waits for no answer ({@link SailEventPublisher#wake}).
 */
public final class CommitAnnouncer implements Runnable, AutoCloseable {

  /** How long a process exiting waits for the announcement it still owes. */
  static final Duration DRAIN = Duration.ofMillis(500);

  private final Callable<SailEventPublisher> connect;
  private final AtomicBoolean owed = new AtomicBoolean();
  private final ExecutorService announcing =
      Executors.newSingleThreadExecutor(
          work -> {
            var thread = new Thread(work, "sail-commit-announcer");
            thread.setDaemon(true);
            return thread;
          });
  private SailEventPublisher publisher;

  /** Announces commits through the publisher {@code connect} opens once. */
  CommitAnnouncer(Callable<SailEventPublisher> connect) {
    this.connect = Objects.requireNonNull(connect, "connect");
  }

  /**
   * The announcer of a process that is not the box's API server, telling the server this box's
   * configuration names, and waiting for its last announcement as the process exits. The binary's
   * entry binds it around a command ({@code Sqlite.ANNOUNCING}), so every database the command
   * opens runs it after a commit that appended to the change log.
   */
  public static CommitAnnouncer toThisBox() {
    var announcer = new CommitAnnouncer(SailEventPublisher::localDefault);
    Runtime.getRuntime()
        .addShutdownHook(new Thread(announcer::close, "sail-commit-announcer-drain"));
    return announcer;
  }

  @Override
  public void run() {
    if (owed.compareAndSet(false, true)) {
      try {
        announcing.execute(this::announce);
      } catch (RuntimeException shutDown) {
        owed.set(false);
      }
    }
  }

  private void announce() {
    owed.set(false);
    try {
      if (publisher == null) {
        publisher = connect.call();
      }
      publisher.wake();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    } catch (Exception noServer) {
      var unused = noServer;
    }
  }

  /** Sends the announcement still owed, waiting at most {@link #DRAIN}, and stops. */
  @Override
  public void close() {
    announcing.shutdown();
    try {
      announcing.awaitTermination(DRAIN.toMillis(), TimeUnit.MILLISECONDS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }
}
