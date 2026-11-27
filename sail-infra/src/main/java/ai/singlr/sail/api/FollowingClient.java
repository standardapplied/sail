/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.api;

import java.net.http.HttpClient;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

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

  private FollowingClient(HttpClient http, ExecutorService work) {
    this.http = http;
    this.work = work;
  }

  /** The client {@code builder} builds, its work run on a pool of its own, as the JDK's is. */
  public static FollowingClient of(HttpClient.Builder builder) {
    return on(Executors.newCachedThreadPool(), builder);
  }

  /** The client {@code builder} builds, its work run on {@code work}, which closing it closes. */
  public static FollowingClient on(ExecutorService work, HttpClient.Builder builder) {
    return new FollowingClient(builder.executor(work).build(), work);
  }

  public HttpClient http() {
    return http;
  }

  /**
   * Runs {@code reading} on the client's work, so that closing this waits for it as for the
   * client's own tasks: a reader that asks for more of a dropped stream closes its connection on
   * its own thread.
   */
  public Future<?> follow(Runnable reading) {
    return work.submit(reading);
  }

  /**
   * Aborts what the client still has in flight, waits for its selector thread to end, then for
   * every task of its work. What the JDK still runs there by then (a buffer offered to a body
   * subscriber, a future completed, a connection closed) waits on nothing outside the client, so
   * the wait is as long as that work takes; an interrupt aborts the tasks and keeps waiting.
   */
  @Override
  public void close() {
    try {
      http.shutdownNow();
      http.close();
    } finally {
      work.close();
    }
  }
}
