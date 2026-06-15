/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.api;

import ai.singlr.sail.engine.SailPaths;
import ai.singlr.sail.store.SpecStore;
import ai.singlr.sail.store.TokenStore;
import com.sun.net.httpserver.HttpHandler;
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
  private final LocalApiSocket socketListener;

  /**
   * Construct with database-backed token auth. Used by the control plane server ({@code sail server
   * start}). When {@code eventBus} is non-null, the persister is registered as a subscriber, the
   * SSE handler is mounted, and a Unix-socket events listener is started at {@link
   * SailPaths#apiSocketPath()} so project containers can publish events without going over TCP.
   */
  public SailApiServer(
      String host,
      int port,
      ApiOperations operations,
      TokenStore tokenStore,
      EventBus eventBus,
      EventSubscriber auditSubscriber)
      throws IOException {
    this(
        host,
        port,
        operations,
        new TokenAuth(tokenStore),
        eventBus,
        auditSubscriber,
        SailPaths.apiSocketPath(),
        null);
  }

  /**
   * Control-plane constructor that also mounts the passkey ceremony endpoints at {@code /v1/auth}.
   * Pass {@code null} for {@code passkeyHandler} to leave passkey login unmounted.
   */
  public SailApiServer(
      String host,
      int port,
      ApiOperations operations,
      TokenStore tokenStore,
      EventBus eventBus,
      EventSubscriber auditSubscriber,
      HttpHandler passkeyHandler)
      throws IOException {
    this(
        host,
        port,
        operations,
        new TokenAuth(tokenStore),
        eventBus,
        auditSubscriber,
        SailPaths.apiSocketPath(),
        passkeyHandler);
  }

  /** Test / advanced constructor that lets the caller pick the UDS path. */
  public SailApiServer(
      String host,
      int port,
      ApiOperations operations,
      TokenStore tokenStore,
      EventBus eventBus,
      EventSubscriber auditSubscriber,
      Path socketPath)
      throws IOException {
    this(
        host,
        port,
        operations,
        new TokenAuth(tokenStore),
        eventBus,
        auditSubscriber,
        socketPath,
        null);
  }

  SailApiServer(
      String host,
      int port,
      ApiOperations operations,
      ApiAuth auth,
      EventBus eventBus,
      EventSubscriber auditSubscriber,
      Path socketPath)
      throws IOException {
    this(host, port, operations, auth, eventBus, auditSubscriber, socketPath, null);
  }

  /**
   * Full control-plane constructor with a caller-supplied {@link ApiAuth} (e.g. {@link
   * SessionAwareAuth}, which accepts both login sessions and API tokens), the UDS path, and the
   * passkey endpoints mounted at {@code /v1/auth} when {@code passkeyHandler} is non-null.
   */
  public SailApiServer(
      String host,
      int port,
      ApiOperations operations,
      ApiAuth auth,
      EventBus eventBus,
      EventSubscriber auditSubscriber,
      Path socketPath,
      HttpHandler passkeyHandler)
      throws IOException {
    this(host, port, operations, auth, eventBus, auditSubscriber, socketPath, passkeyHandler, null);
  }

  /**
   * Full constructor; {@code specStore} wires the DB-backed spec-lifecycle reactor when present.
   */
  public SailApiServer(
      String host,
      int port,
      ApiOperations operations,
      ApiAuth auth,
      EventBus eventBus,
      EventSubscriber auditSubscriber,
      Path socketPath,
      HttpHandler passkeyHandler,
      SpecStore specStore)
      throws IOException {
    this.eventBus = eventBus;
    this.auditPersister = auditSubscriber instanceof AuditPersister ap ? ap : null;
    this.persisterSubscription =
        eventBus != null && auditSubscriber != null ? eventBus.subscribe(auditSubscriber) : null;
    this.webhookSubscription =
        eventBus != null ? eventBus.subscribe(WebhookReactor.withDefaultResolver()) : null;
    this.specLifecycleSubscription =
        eventBus != null && specStore != null
            ? eventBus.subscribe(new SpecLifecycleReactor(specStore))
            : null;
    server = HttpServer.create(new InetSocketAddress(host, port), 32);
    executor = Executors.newVirtualThreadPerTaskExecutor();
    server.createContext("/", new ApiRouter(operations, auth));
    if (passkeyHandler != null) {
      server.createContext("/v1/auth", passkeyHandler);
      var pages = new WebauthnPageHandler();
      server.createContext("/login", pages);
      server.createContext("/enroll", pages);
    }
    if (eventBus != null) {
      sseHandler = new SseHandler(eventBus, auth);
      server.createContext("/v1/events/stream", sseHandler);
      socketListener =
          socketPath == null ? null : new LocalApiSocket(eventBus, operations, socketPath);
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

  /** Returns the local Unix-socket API, or {@code null} when no bus was configured. */
  public LocalApiSocket socketListener() {
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
