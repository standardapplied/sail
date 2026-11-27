/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.api;

import java.net.http.HttpClient;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;

/**
 * An HTTP client a stream is followed on, closed with the executor its work runs on. A client's
 * close waits for its selector thread only: when the server drops a stream, the client's task that
 * wakes the stream's reader goes on to close the connection, and can still be closing it once the
 * client's close has returned. The executor's close waits for that task, so once this is closed, so
 * is every connection the client opened.
 */
public final class FollowingClient implements AutoCloseable {

  private final HttpClient http;
  private final ExecutorService work;
  private final Threads threads;

  private FollowingClient(HttpClient.Builder builder, ExecutorService work, Threads threads) {
    this.http = builder.executor(work).build();
    this.work = work;
    this.threads = threads;
  }

  /**
   * The client {@code builder} builds, its work run on a pool of its own, as the JDK's is, whose
   * daemon threads are named {@code sail-follow-<n>}.
   */
  public static FollowingClient of(HttpClient.Builder builder) {
    var threads = new Threads();
    return new FollowingClient(builder, Executors.newCachedThreadPool(threads), threads);
  }

  /** The client {@code builder} builds, its work run on {@code work}, which closing it closes. */
  public static FollowingClient on(ExecutorService work, HttpClient.Builder builder) {
    return new FollowingClient(builder, work, new Threads());
  }

  public HttpClient http() {
    return http;
  }

  /**
   * Runs {@code reading} on the client's work, so that closing this interrupts it and waits for it
   * as for the client's own tasks: a reader that asks for more of a dropped stream closes its
   * connection on its own thread. A reader that goes on once interrupted holds the close.
   */
  public Future<?> follow(Runnable reading) {
    return work.submit(reading);
  }

  /**
   * Aborts what the client still has in flight, waits for its selector thread to end, then
   * interrupts every task of its work and waits for them. What the JDK still runs there by then (a
   * buffer offered to a body subscriber, a future completed, a connection closed) waits on nothing
   * outside the client, and a reader given to {@link #follow} is interrupted, so the wait is as
   * long as that work takes to end. Refused from a thread of its own pool, which would wait for
   * itself.
   */
  @Override
  public void close() {
    if (threads.madeCurrent()) {
      throw new IllegalStateException(
          "A followed client was closed from a thread of its own work, which would wait for"
              + " itself: close it from the thread that opened it.");
    }
    try {
      http.shutdownNow();
      http.close();
    } finally {
      work.shutdownNow();
      work.close();
    }
  }

  /** The threads of one client's own pool: named for a thread dump, and known to its close. */
  private static final class Threads implements ThreadFactory {

    private static final ThreadFactory NAMED =
        Thread.ofPlatform().name("sail-follow-", 0).daemon().factory();
    private static final ThreadLocal<Threads> MAKER = new ThreadLocal<>();

    @Override
    public Thread newThread(Runnable task) {
      return NAMED.newThread(
          () -> {
            MAKER.set(this);
            task.run();
          });
    }

    boolean madeCurrent() {
      return MAKER.get() == this;
    }
  }
}
