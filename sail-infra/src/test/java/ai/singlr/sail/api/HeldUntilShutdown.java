/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.api;

import java.util.List;
import java.util.Set;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * An executor each of whose tasks, once run, keeps its thread until the executor is shut down, as
 * the task that closes a dropped stream's connection may still run once the client has closed. A
 * {@link FollowingClient} on it has closed only once this has terminated.
 */
public final class HeldUntilShutdown extends AbstractExecutorService {

  private final Set<Thread> made = ConcurrentHashMap.newKeySet();
  private final ExecutorService threads =
      Executors.newCachedThreadPool(
          task -> {
            var thread = new Thread(task);
            thread.setDaemon(true);
            made.add(thread);
            return thread;
          });
  private final CountDownLatch shutDown = new CountDownLatch(1);
  private final AtomicInteger ran = new AtomicInteger();

  /** How many tasks have run to their end. */
  public int ran() {
    return ran.get();
  }

  /** Whether {@code thread} is one this executor ran its tasks on. */
  public boolean owns(Thread thread) {
    return made.contains(thread);
  }

  @Override
  public void execute(Runnable task) {
    threads.execute(
        () -> {
          try {
            task.run();
          } finally {
            ran.incrementAndGet();
            holdUntilShutdown();
          }
        });
  }

  private void holdUntilShutdown() {
    try {
      shutDown.await();
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
    }
  }

  @Override
  public void shutdown() {
    shutDown.countDown();
    threads.shutdown();
  }

  @Override
  public List<Runnable> shutdownNow() {
    shutDown.countDown();
    return threads.shutdownNow();
  }

  @Override
  public boolean isShutdown() {
    return threads.isShutdown();
  }

  @Override
  public boolean isTerminated() {
    return threads.isTerminated();
  }

  @Override
  public boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException {
    return threads.awaitTermination(timeout, unit);
  }
}
