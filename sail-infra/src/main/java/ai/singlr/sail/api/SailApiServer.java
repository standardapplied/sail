/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.api;

import ai.singlr.sail.engine.SailPaths;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class SailApiServer implements AutoCloseable {

  private final HttpServer server;
  private final ExecutorService executor;
  private final EventBus eventBus;
  private final AuditPersister auditPersister;
  private final EventBus.Subscription persisterSubscription;
  private final EventBus.Subscription webhookSubscription;
  private final EventBus.Subscription specLifecycleSubscription;
  private final SseHandler sseHandler;
  private final UnixSocketEventsListener socketListener;

  public SailApiServer(String host, int port, ApiOperations operations, String token)
      throws IOException {
    this(host, port, operations, token, null, null);
  }

  /**
   * Construct with explicit event-bus wiring. When {@code eventBus} is non-null, the persister is
   * registered as a subscriber, the SSE handler is mounted, and a Unix-socket events listener is
   * started at {@link SailPaths#apiSocketPath()} so project containers can publish events without
   * going over TCP.
   */
  public SailApiServer(
      String host,
      int port,
      ApiOperations operations,
      String token,
      EventBus eventBus,
      AuditPersister auditPersister)
      throws IOException {
    this(host, port, operations, token, eventBus, auditPersister, SailPaths.apiSocketPath());
  }

  /** Test / advanced constructor that lets the caller pick the UDS path. */
  public SailApiServer(
      String host,
      int port,
      ApiOperations operations,
      String token,
      EventBus eventBus,
      AuditPersister auditPersister,
      Path socketPath)
      throws IOException {
    this.eventBus = eventBus;
    this.auditPersister = auditPersister;
    this.persisterSubscription =
        eventBus != null && auditPersister != null ? eventBus.subscribe(auditPersister) : null;
    this.webhookSubscription =
        eventBus != null ? eventBus.subscribe(WebhookReactor.withDefaultResolver()) : null;
    this.specLifecycleSubscription =
        eventBus != null ? eventBus.subscribe(SpecLifecycleReactor.withDefaults()) : null;
    server = HttpServer.create(new InetSocketAddress(host, port), 32);
    executor = Executors.newVirtualThreadPerTaskExecutor();
    var auth = new BearerAuth(token);
    server.createContext("/", new ApiRouter(operations, auth));
    if (eventBus != null) {
      sseHandler = new SseHandler(eventBus, auth);
      server.createContext("/v1/events/stream", sseHandler);
      socketListener =
          socketPath == null ? null : new UnixSocketEventsListener(eventBus, socketPath);
    } else {
      sseHandler = null;
      socketListener = null;
    }
    server.setExecutor(executor);
  }

  public void start() throws IOException {
    server.start();
    if (socketListener != null) {
      socketListener.start();
    }
  }

  public int port() {
    return server.getAddress().getPort();
  }

  /** Returns the event bus, or {@code null} when the server was constructed without one. */
  public EventBus eventBus() {
    return eventBus;
  }

  /** Returns the audit persister, or {@code null} when the server was constructed without one. */
  public AuditPersister auditPersister() {
    return auditPersister;
  }

  /** Returns the SSE handler, or {@code null} when no bus was configured. */
  public SseHandler sseHandler() {
    return sseHandler;
  }

  /** Returns the Unix-socket events listener, or {@code null} when no bus was configured. */
  public UnixSocketEventsListener socketListener() {
    return socketListener;
  }

  @Override
  public void close() {
    if (socketListener != null) {
      socketListener.close();
    }
    if (specLifecycleSubscription != null) {
      specLifecycleSubscription.close();
    }
    if (webhookSubscription != null) {
      webhookSubscription.close();
    }
    if (persisterSubscription != null) {
      persisterSubscription.close();
    }
    if (eventBus != null) {
      eventBus.close();
    }
    server.stop(0);
    executor.close();
  }
}
