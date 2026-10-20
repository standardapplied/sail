/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.api;

import com.sun.net.httpserver.Filter;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpContext;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpPrincipal;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Gives every exchange its own attributes. Before JDK 26 (JDK-7105350) an exchange's attributes are
 * its {@link HttpContext}'s, shared by every request to that context, and the release build runs on
 * 25: a caller authenticating on another connection would overwrite the {@code token.*} identity
 * {@link ApiAuth#require} stamped on this one between the stamp and its read. Installed first on
 * every context, so the rate limit, the auth and an open stream's re-checks all see this request's
 * own attributes.
 */
final class PrivateAttributes extends Filter {

  private static final PrivateAttributes INSTANCE = new PrivateAttributes();

  private PrivateAttributes() {}

  /**
   * Creates {@code path} on {@code server} with each of its exchanges holding its own attributes.
   */
  static HttpContext context(HttpServer server, String path, HttpHandler handler) {
    var context = server.createContext(path, handler);
    context.getFilters().add(INSTANCE);
    return context;
  }

  @Override
  public void doFilter(HttpExchange exchange, Chain chain) throws IOException {
    chain.doFilter(new Exchange(exchange));
  }

  @Override
  public String description() {
    return "Gives each exchange its own attributes";
  }

  private static final class Exchange extends HttpExchange {

    private final HttpExchange delegate;
    private final Map<String, Object> attributes = new ConcurrentHashMap<>();

    Exchange(HttpExchange delegate) {
      this.delegate = delegate;
    }

    @Override
    public Object getAttribute(String name) {
      return attributes.get(name);
    }

    @Override
    public void setAttribute(String name, Object value) {
      if (value == null) {
        attributes.remove(name);
      } else {
        attributes.put(name, value);
      }
    }

    @Override
    public Headers getRequestHeaders() {
      return delegate.getRequestHeaders();
    }

    @Override
    public Headers getResponseHeaders() {
      return delegate.getResponseHeaders();
    }

    @Override
    public URI getRequestURI() {
      return delegate.getRequestURI();
    }

    @Override
    public String getRequestMethod() {
      return delegate.getRequestMethod();
    }

    @Override
    public HttpContext getHttpContext() {
      return delegate.getHttpContext();
    }

    @Override
    public void close() {
      delegate.close();
    }

    @Override
    public InputStream getRequestBody() {
      return delegate.getRequestBody();
    }

    @Override
    public OutputStream getResponseBody() {
      return delegate.getResponseBody();
    }

    @Override
    public void sendResponseHeaders(int rCode, long responseLength) throws IOException {
      delegate.sendResponseHeaders(rCode, responseLength);
    }

    @Override
    public InetSocketAddress getRemoteAddress() {
      return delegate.getRemoteAddress();
    }

    @Override
    public int getResponseCode() {
      return delegate.getResponseCode();
    }

    @Override
    public InetSocketAddress getLocalAddress() {
      return delegate.getLocalAddress();
    }

    @Override
    public String getProtocol() {
      return delegate.getProtocol();
    }

    @Override
    public void setStreams(InputStream i, OutputStream o) {
      delegate.setStreams(i, o);
    }

    @Override
    public HttpPrincipal getPrincipal() {
      return delegate.getPrincipal();
    }
  }
}
