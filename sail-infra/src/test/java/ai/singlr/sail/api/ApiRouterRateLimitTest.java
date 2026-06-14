/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpContext;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpPrincipal;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ApiRouterRateLimitTest {

  private final ApiOperations ops = new TestOperations();

  @Test
  void aRequestWithinBudgetIsServed() throws Exception {
    var router =
        new ApiRouter(ops, new FixedTokenTestAuth("token"), new RateLimiter(1, 0d, () -> 0L));

    var response = router.route(exchange("GET", "/v1/specs/board"));

    assertEquals(200, response.status());
  }

  @Test
  void exceedingTheBudgetIsRejectedWith429() throws Exception {
    var router =
        new ApiRouter(ops, new FixedTokenTestAuth("token"), new RateLimiter(1, 0d, () -> 0L));
    router.route(exchange("GET", "/v1/specs/board"));

    var e =
        assertThrows(ApiException.class, () -> router.route(exchange("GET", "/v1/specs/board")));

    assertEquals(ErrorCode.RATE_LIMITED, e.failure().errorCode());
    assertEquals(429, e.failure().errorCode().httpCode());
  }

  private static HttpExchange exchange(String method, String path) {
    var headers = new Headers();
    headers.add("Authorization", "Bearer token");
    return new FakeExchange(method, URI.create(path), headers);
  }

  private static final class FakeExchange extends HttpExchange {
    private final String method;
    private final URI uri;
    private final Headers requestHeaders;
    private final Map<String, Object> attributes = new HashMap<>();

    private FakeExchange(String method, URI uri, Headers requestHeaders) {
      this.method = method;
      this.uri = uri;
      this.requestHeaders = requestHeaders;
    }

    @Override
    public Headers getRequestHeaders() {
      return requestHeaders;
    }

    @Override
    public String getRequestMethod() {
      return method;
    }

    @Override
    public URI getRequestURI() {
      return uri;
    }

    @Override
    public Object getAttribute(String name) {
      return attributes.get(name);
    }

    @Override
    public void setAttribute(String name, Object value) {
      attributes.put(name, value);
    }

    @Override
    public Headers getResponseHeaders() {
      throw new UnsupportedOperationException();
    }

    @Override
    public HttpContext getHttpContext() {
      throw new UnsupportedOperationException();
    }

    @Override
    public void close() {
      throw new UnsupportedOperationException();
    }

    @Override
    public InputStream getRequestBody() {
      throw new UnsupportedOperationException();
    }

    @Override
    public OutputStream getResponseBody() {
      throw new UnsupportedOperationException();
    }

    @Override
    public void sendResponseHeaders(int rCode, long responseLength) {
      throw new UnsupportedOperationException();
    }

    @Override
    public InetSocketAddress getRemoteAddress() {
      throw new UnsupportedOperationException();
    }

    @Override
    public int getResponseCode() {
      throw new UnsupportedOperationException();
    }

    @Override
    public InetSocketAddress getLocalAddress() {
      throw new UnsupportedOperationException();
    }

    @Override
    public String getProtocol() {
      throw new UnsupportedOperationException();
    }

    @Override
    public void setStreams(InputStream i, OutputStream o) {
      throw new UnsupportedOperationException();
    }

    @Override
    public HttpPrincipal getPrincipal() {
      throw new UnsupportedOperationException();
    }
  }
}
