/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.api;

import ai.singlr.sail.config.YamlUtil;
import ai.singlr.sail.store.MessageStore;
import com.sun.net.httpserver.HttpExchange;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class JsonBody {

  private static final int MAX_BYTES = 66 * 1024;
  private static final int MAX_MESSAGE_REQUEST_BYTES = MessageStore.MAX_BODY_BYTES * 6 + 2048;

  private JsonBody() {}

  public static DispatchRequest readDispatchRequest(HttpExchange exchange) throws IOException {
    var map = read(exchange, MAX_BYTES);
    return new DispatchRequest(
        optionalString(map, "spec_id"),
        optionalString(map, "mode"),
        Boolean.TRUE.equals(map.get("dry_run")),
        optionalStringList(map, "repo", "repos"),
        Boolean.TRUE.equals(map.get("restart")));
  }

  public static Map<String, Object> readMap(HttpExchange exchange) throws IOException {
    return read(exchange, MAX_BYTES);
  }

  public static Map<String, Object> readMessageMap(HttpExchange exchange) throws IOException {
    return read(exchange, MAX_MESSAGE_REQUEST_BYTES);
  }

  public static Event readEvent(HttpExchange exchange) throws IOException {
    var map = read(exchange, MAX_BYTES);
    try {
      return Event.fromMap(map);
    } catch (IllegalArgumentException e) {
      throw new ApiException(ErrorCode.INVALID_JSON, e.getMessage(), e);
    }
  }

  private static Map<String, Object> read(HttpExchange exchange, int maxBytes) throws IOException {
    var contentType = exchange.getRequestHeaders().getFirst("Content-Type");
    if (contentType != null && !contentType.toLowerCase().startsWith("application/json")) {
      throw new ApiException(
          ErrorCode.UNSUPPORTED_MEDIA_TYPE,
          "Requests with a body must use application/json.",
          "Set Content-Type to application/json.");
    }
    var bytes = exchange.getRequestBody().readNBytes(maxBytes + 1);
    if (bytes.length > maxBytes) {
      throw new ApiException(
          ErrorCode.REQUEST_TOO_LARGE, "Request body is too large.", "Send a smaller JSON body.");
    }
    if (bytes.length == 0) {
      return Map.of();
    }
    try {
      return YamlUtil.parseMap(new String(bytes, StandardCharsets.UTF_8));
    } catch (RuntimeException e) {
      throw new ApiException(ErrorCode.INVALID_JSON, "Request body is not valid JSON.", e);
    }
  }

  private static String optionalString(Map<String, Object> map, String key) {
    var value = map.get(key);
    return value == null ? null : Objects.toString(value);
  }

  @SuppressWarnings("unchecked")
  private static List<String> optionalStringList(
      Map<String, Object> map, String single, String many) {
    var one = map.get(single);
    var list = map.get(many);
    if (one != null && list != null) {
      throw new ApiException(
          ErrorCode.INVALID_JSON, "Request body may define repo or repos, not both.");
    }
    if (one != null) {
      return List.of(Objects.toString(one));
    }
    if (list == null) {
      return List.of();
    }
    if (list instanceof List<?> values) {
      return values.stream().map(Objects::toString).toList();
    }
    return List.of(Objects.toString(list));
  }
}
