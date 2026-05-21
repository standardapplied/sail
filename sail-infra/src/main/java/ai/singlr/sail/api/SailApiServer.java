/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.api;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class SailApiServer implements AutoCloseable {

  private final HttpServer server;
  private final ExecutorService executor;
  private final EventBus eventBus;
  private final AuditPersister auditPersister;
  private final EventBus.Subscription persisterSubscription;

  public SailApiServer(String host, int port, ApiOperations operations, String token)
      throws IOException {
    this(host, port, operations, token, null, null);
  }

  /**
   * Construct with explicit event-bus wiring. When {@code eventBus} is non-null, the persister is
   * registered as a subscriber and the operations layer will publish to it.
   */
  public SailApiServer(
      String host,
      int port,
      ApiOperations operations,
      String token,
      EventBus eventBus,
      AuditPersister auditPersister)
      throws IOException {
    this.eventBus = eventBus;
    this.auditPersister = auditPersister;
    this.persisterSubscription =
        eventBus != null && auditPersister != null ? eventBus.subscribe(auditPersister) : null;
    server = HttpServer.create(new InetSocketAddress(host, port), 32);
    executor = Executors.newVirtualThreadPerTaskExecutor();
    server.createContext("/", new ApiRouter(operations, new BearerAuth(token)));
    server.setExecutor(executor);
  }

  public void start() {
    server.start();
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

  @Override
  public void close() {
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
