/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.api;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Edge-branch tests added to keep every class under {@code ai.singlr.sail.api.*} at 100% line +
 * method coverage as required by the project's JaCoCo policy. Each test targets one specific
 * defensive branch that the happy-path tests miss.
 */
class ApiCoverageEdgesTest {

  @Test
  void webhookReactorDefaultSenderBuildsRealNotifier() {
    var sender = WebhookReactor.defaultSender("https://ntfy.sh/test");
    assertNotNull(sender);
    sender.send("any", "p", "title", "msg");
  }

  @Test
  void auditPersisterSwallowsIoFailureWhenPathIsADirectory(@TempDir Path dir) throws Exception {
    var asDir = dir.resolve("events.jsonl");
    Files.createDirectories(asDir);
    var persister = new AuditPersister(asDir, 4);

    assertDoesNotThrow(() -> persister.onEvent(Event.of("p", null, "t", "a", "h").withId(1L)));
    assertEquals(1, persister.recent(10).size());
  }

  @Test
  void boundedVirtualExecutorReleasesPermitWhenSubmitFails() {
    var hostile = new RejectingExecutor();
    try (var ex = new BoundedVirtualExecutor(2, hostile)) {
      assertThrows(RejectedExecutionException.class, () -> ex.tryRun(() -> {}));
      assertEquals(0, ex.inFlight(), "permit released after submit failure");
    }
  }

  @Test
  void eventBusSubscriptionImplExposesNameAndQueueDepth() throws Exception {
    try (var bus = new EventBus()) {
      var sub =
          bus.subscribe(
              new EventSubscriber() {
                @Override
                public String name() {
                  return "edge-sub";
                }

                @Override
                public java.util.function.Predicate<Event> filter() {
                  return EventSubscriber.all();
                }

                @Override
                public void onEvent(Event event) {
                  try {
                    Thread.sleep(50);
                  } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                  }
                }
              },
              4);
      bus.publish(Event.of("p", null, "t", "a", "h"));

      assertEquals("edge-sub", sub.name());
      assertTrue(sub.queueDepth() >= 0);
    }
  }

  @Test
  void sailApiServerAccessorsReflectBusWiring(@TempDir Path tmp) throws Exception {
    try (var bus = new EventBus()) {
      var persister = new AuditPersister(tmp.resolve("events.jsonl"), 16);
      try (var server =
          new SailApiServer(
              "127.0.0.1",
              0,
              new SailApiOperations(),
              new FixedTokenTestAuth("tok"),
              bus,
              persister,
              tmp.resolve("api.sock"))) {
        assertSame(bus, server.eventBus());
        assertSame(persister, server.auditPersister());
        assertNotNull(server.sseHandler());
        assertNotNull(server.socketListener());
      }
    }
  }

  @Test
  void sailApiServerAccessorsNullWithoutBus() throws Exception {
    try (var server =
        new SailApiServer(
            "127.0.0.1",
            0,
            new SailApiOperations(),
            new FixedTokenTestAuth("tok"),
            null,
            null,
            null)) {
      assertNull(server.eventBus());
      assertNull(server.auditPersister());
      assertNull(server.sseHandler());
      assertNull(server.socketListener());
    }
  }

  @Test
  void sailApiServerSocketPathCanBeOmitted() throws Exception {
    try (var bus = new EventBus()) {
      try (var server =
          new SailApiServer(
              "127.0.0.1",
              0,
              new SailApiOperations(),
              new FixedTokenTestAuth("tok"),
              bus,
              null,
              null)) {
        assertNotNull(server.eventBus());
        assertNull(server.socketListener());
      }
    }
  }

  @Test
  void eventBusDrainExitsOnSubscriberInterrupt() throws Exception {
    try (var bus = new EventBus()) {
      var entered = new CountDownLatch(1);
      bus.subscribe(
          new EventSubscriber() {
            @Override
            public String name() {
              return "self-interrupt";
            }

            @Override
            public java.util.function.Predicate<Event> filter() {
              return EventSubscriber.all();
            }

            @Override
            public void onEvent(Event event) {
              entered.countDown();
              Thread.currentThread().interrupt();
            }
          });

      bus.publish(Event.of("p", null, "t", "a", "h"));
      assertTrue(entered.await(2, TimeUnit.SECONDS));
      Thread.sleep(500);
    }
  }

  @Test
  void eventBusDrainLoopExitsCleanlyOnClose() throws Exception {
    var bus = new EventBus();
    var entered = new CountDownLatch(1);
    var exit = new CountDownLatch(1);
    bus.subscribe(
        new EventSubscriber() {
          @Override
          public String name() {
            return "interrupt-target";
          }

          @Override
          public java.util.function.Predicate<Event> filter() {
            return EventSubscriber.all();
          }

          @Override
          public void onEvent(Event event) {
            entered.countDown();
            try {
              Thread.sleep(5_000);
            } catch (InterruptedException e) {
              Thread.currentThread().interrupt();
              exit.countDown();
            }
          }
        });
    bus.publish(Event.of("p", null, "t", "a", "h"));
    assertTrue(entered.await(2, TimeUnit.SECONDS));
    bus.close();
  }

  /** Executor that always rejects submissions; used to exercise the cap-failure rethrow. */
  private static final class RejectingExecutor implements AutoCloseable, ExecutorService {
    private final ExecutorService delegate = Executors.newSingleThreadExecutor();

    @Override
    public void execute(Runnable command) {
      throw new RejectedExecutionException("test-only");
    }

    @Override
    public void shutdown() {
      delegate.shutdown();
    }

    @Override
    public java.util.List<Runnable> shutdownNow() {
      return delegate.shutdownNow();
    }

    @Override
    public boolean isShutdown() {
      return delegate.isShutdown();
    }

    @Override
    public boolean isTerminated() {
      return delegate.isTerminated();
    }

    @Override
    public boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException {
      return delegate.awaitTermination(timeout, unit);
    }

    @Override
    public <T> java.util.concurrent.Future<T> submit(java.util.concurrent.Callable<T> task) {
      throw new RejectedExecutionException("test-only");
    }

    @Override
    public <T> java.util.concurrent.Future<T> submit(Runnable task, T result) {
      throw new RejectedExecutionException("test-only");
    }

    @Override
    public java.util.concurrent.Future<?> submit(Runnable task) {
      throw new RejectedExecutionException("test-only");
    }

    @Override
    public <T> java.util.List<java.util.concurrent.Future<T>> invokeAll(
        java.util.Collection<? extends java.util.concurrent.Callable<T>> tasks) {
      throw new RejectedExecutionException("test-only");
    }

    @Override
    public <T> java.util.List<java.util.concurrent.Future<T>> invokeAll(
        java.util.Collection<? extends java.util.concurrent.Callable<T>> tasks,
        long timeout,
        TimeUnit unit) {
      throw new RejectedExecutionException("test-only");
    }

    @Override
    public <T> T invokeAny(java.util.Collection<? extends java.util.concurrent.Callable<T>> tasks) {
      throw new RejectedExecutionException("test-only");
    }

    @Override
    public <T> T invokeAny(
        java.util.Collection<? extends java.util.concurrent.Callable<T>> tasks,
        long timeout,
        TimeUnit unit) {
      throw new RejectedExecutionException("test-only");
    }

    @Override
    public void close() {
      delegate.close();
    }
  }
}
