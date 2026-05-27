/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.api;

import ai.singlr.sail.config.YamlUtil;
import ai.singlr.sail.engine.NameValidator;
import ai.singlr.sail.store.SpecStore;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import java.io.IOException;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class ApiRouter implements HttpHandler {

  private static final String GET = "GET";
  private static final String POST = "POST";
  private static final String PUT = "PUT";
  private static final String DELETE = "DELETE";
  private static final String V1 = "v1";
  private static final String HEALTH = "health";
  private static final String PROJECTS = "projects";
  private static final String SPECS = "specs";
  private static final String SPEC_SYNC = "spec-sync";
  private static final String DISPATCH = "dispatch";
  private static final String AGENT = "agent";
  private static final String LOG = "log";
  private static final String STOP = "stop";
  private static final String REPORT = "report";
  private static final String TAIL = "tail";
  private static final String EVENTS = "events";
  private static final String RECENT = "recent";
  private static final String STATS = "stats";
  private static final String LIMIT = "limit";
  private static final String BOARD = "board";
  private static final String CONTENT = "content";
  private static final String REVIEWS = "reviews";
  private static final String SESSIONS = "sessions";
  private static final String APPROVE = "approve";
  private static final String DISMISS = "dismiss";
  private static final int DEFAULT_TAIL = 200;
  private static final int MIN_TAIL = 1;
  private static final int MAX_TAIL = 5000;
  private static final int DEFAULT_RECENT = 100;

  private final ApiOperations operations;
  private final ApiAuth auth;

  public ApiRouter(ApiOperations operations, ApiAuth auth) {
    this.operations = operations;
    this.auth = auth;
  }

  @Override
  public void handle(HttpExchange exchange) throws IOException {
    try {
      var response = route(exchange);
      write(exchange, response);
    } catch (ApiException e) {
      write(exchange, ApiResponse.error(e.failure()));
    } catch (IllegalArgumentException e) {
      write(
          exchange,
          ApiResponse.error(
              new Result.Failure<>(ErrorCode.INVALID_REQUEST, e.getMessage(), null, null, e)));
    } catch (Exception e) {
      write(
          exchange,
          ApiResponse.error(
              new Result.Failure<>(
                  ErrorCode.INTERNAL,
                  "sail API request failed.",
                  "Check sail API logs.",
                  null,
                  e)));
    } finally {
      exchange.close();
    }
  }

  ApiResponse route(HttpExchange exchange) throws IOException {
    var request = RouteRequest.from(exchange);
    if (request.matches(GET, V1, HEALTH)) {
      return ApiResponse.from(operations.health());
    }

    auth.require(exchange);

    if (request.hasEventsPrefix()) {
      return routeEvents(exchange, request);
    }

    if (request.hasGlobalSpecsPrefix()) {
      return routeGlobalSpecs(exchange, request);
    }

    if (request.hasReviewsPrefix()) {
      return routeReviews(exchange, request);
    }

    if (!request.hasProjectPrefix()) {
      throw notFound();
    }

    var project = request.project();
    NameValidator.requireValidProjectName(project);
    if (request.isProjectRoot()) {
      return routeProject(request, project);
    }

    return switch (request.resource()) {
      case SPECS -> routeSpecs(request, project);
      case SPEC_SYNC -> routeSpecSync(exchange, request, project);
      case DISPATCH -> routeDispatch(exchange, request, project);
      case AGENT -> routeAgent(request, project);
      default -> throw notFound();
    };
  }

  private ApiResponse routeProject(RouteRequest request, String project) {
    if (request.is(GET)) {
      return ApiResponse.from(operations.project(project));
    }
    throw methodNotAllowed();
  }

  private ApiResponse routeSpecs(RouteRequest request, String project) {
    if (request.size() == 4) {
      requireMethod(request, GET);
      return ApiResponse.from(operations.specs(project));
    }
    if (request.size() == 5) {
      requireMethod(request, GET);
      var specId = request.subResource();
      NameValidator.requireValidSpecId(specId);
      return ApiResponse.from(operations.spec(project, specId));
    }
    throw methodNotAllowed();
  }

  private ApiResponse routeSpecSync(HttpExchange exchange, RouteRequest request, String project)
      throws IOException {
    if (request.size() != 4) {
      throw methodNotAllowed();
    }
    if (request.is(GET)) {
      return ApiResponse.from(operations.specSyncStatus(project));
    }
    requireMethod(request, POST);
    return ApiResponse.from(operations.specSync(project, JsonBody.readSpecSyncRequest(exchange)));
  }

  private ApiResponse routeDispatch(HttpExchange exchange, RouteRequest request, String project)
      throws IOException {
    if (request.size() != 4) {
      throw methodNotAllowed();
    }
    requireMethod(request, POST);
    return ApiResponse.from(operations.dispatch(project, JsonBody.readDispatchRequest(exchange)));
  }

  private ApiResponse routeGlobalSpecs(HttpExchange exchange, RouteRequest request)
      throws IOException {
    if (request.size() == 2) {
      return switch (request.method()) {
        case GET -> {
          var params = QueryParameters.from(request.uri());
          yield ApiResponse.from(
              operations.globalSpecs(
                  new SpecStore.SpecFilter(
                      params.values().get("status"),
                      params.values().get("assignee"),
                      params.values().get("repo"),
                      params.values().get("q"))));
        }
        case POST ->
            ApiResponse.fromCreated(
                operations.createGlobalSpec(SpecCreateRequest.fromMap(JsonBody.readMap(exchange))));
        default -> throw methodNotAllowed();
      };
    }
    if (request.size() == 3) {
      var segment = request.segments().get(2);
      if (BOARD.equals(segment)) {
        requireMethod(request, GET);
        return ApiResponse.from(operations.globalBoard());
      }
      var specId = segment;
      NameValidator.requireValidSpecId(specId);
      return switch (request.method()) {
        case GET -> ApiResponse.from(operations.globalSpec(specId));
        case PUT ->
            ApiResponse.from(
                operations.updateGlobalSpec(
                    specId, SpecUpdateRequest.fromMap(JsonBody.readMap(exchange))));
        case DELETE -> ApiResponse.from(operations.deleteGlobalSpec(specId));
        default -> throw methodNotAllowed();
      };
    }
    if (request.size() == 4) {
      var specId = request.segments().get(2);
      NameValidator.requireValidSpecId(specId);
      var sub = request.segments().get(3);
      if (CONTENT.equals(sub)) {
        return switch (request.method()) {
          case GET -> ApiResponse.from(operations.globalSpecContent(specId));
          case PUT ->
              ApiResponse.from(
                  operations.setGlobalSpecContent(
                      specId, SpecContentRequest.fromMap(JsonBody.readMap(exchange))));
          default -> throw methodNotAllowed();
        };
      }
      if (REVIEWS.equals(sub)) {
        requireMethod(request, GET);
        return ApiResponse.from(operations.reviewsForSpec(specId));
      }
    }
    throw notFound();
  }

  private ApiResponse routeReviews(HttpExchange exchange, RouteRequest request) throws IOException {
    if (request.size() == 3) {
      var reviewId = request.segments().get(2);
      requireMethod(request, GET);
      return ApiResponse.from(operations.reviewDetail(reviewId));
    }
    if (request.size() == 4) {
      var reviewId = request.segments().get(2);
      var sub = request.segments().get(3);
      return switch (sub) {
        case APPROVE -> {
          requireMethod(request, POST);
          yield ApiResponse.from(operations.approveReview(reviewId));
        }
        default -> throw notFound();
      };
    }
    if (request.size() == 5) {
      var reviewId = request.segments().get(2);
      var sub = request.segments().get(3);
      var findingId = request.segments().get(4);
      if (DISMISS.equals(sub)) {
        requireMethod(request, POST);
        return ApiResponse.from(operations.dismissFinding(reviewId, findingId));
      }
    }
    throw notFound();
  }

  private ApiResponse routeEvents(HttpExchange exchange, RouteRequest request) throws IOException {
    if (request.size() == 2) {
      requireMethod(request, POST);
      return ApiResponse.from(operations.publishEvent(JsonBody.readEvent(exchange)));
    }
    if (request.size() == 3) {
      return switch (request.segments().get(2)) {
        case RECENT -> {
          requireMethod(request, GET);
          yield ApiResponse.from(
              operations.recentEvents(QueryParameters.from(request.uri()).limit()));
        }
        case STATS -> {
          requireMethod(request, GET);
          yield ApiResponse.from(operations.eventBusStats());
        }
        default -> throw notFound();
      };
    }
    throw notFound();
  }

  private ApiResponse routeAgent(RouteRequest request, String project) {
    if (request.size() == 4) {
      requireMethod(request, GET);
      return ApiResponse.from(operations.agentStatus(project));
    }
    if (request.size() != 5) {
      throw methodNotAllowed();
    }
    return switch (request.subResource()) {
      case LOG -> {
        requireMethod(request, GET);
        yield ApiResponse.from(
            operations.agentLog(project, QueryParameters.from(request.uri()).tail()));
      }
      case STOP -> {
        requireMethod(request, POST);
        yield ApiResponse.from(operations.stopAgent(project));
      }
      case REPORT -> {
        requireMethod(request, POST);
        yield ApiResponse.from(operations.agentReport(project));
      }
      case SESSIONS -> {
        requireMethod(request, GET);
        yield ApiResponse.from(operations.agentSessions(project));
      }
      default -> throw notFound();
    };
  }

  private static void requireMethod(RouteRequest request, String method) {
    if (!request.is(method)) {
      throw methodNotAllowed();
    }
  }

  private static ApiException methodNotAllowed() {
    return new ApiException(
        ErrorCode.METHOD_NOT_ALLOWED, "HTTP method is not allowed for this endpoint.");
  }

  private static String cleanPath(URI uri) {
    var path = uri.getPath();
    return path == null || path.isBlank() ? "/" : path;
  }

  private static ApiException notFound() {
    return new ApiException(ErrorCode.NOT_FOUND, "API endpoint was not found.");
  }

  private static void write(HttpExchange exchange, ApiResponse response) throws IOException {
    var body =
        YamlUtil.dumpJson(new LinkedHashMap<>(response.body())).getBytes(StandardCharsets.UTF_8);
    exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
    exchange.sendResponseHeaders(response.status(), body.length);
    try (var output = exchange.getResponseBody()) {
      output.write(body);
    }
  }

  private record RouteRequest(String method, URI uri, List<String> segments) {
    static RouteRequest from(HttpExchange exchange) {
      var uri = exchange.getRequestURI();
      return new RouteRequest(exchange.getRequestMethod(), uri, segments(cleanPath(uri)));
    }

    boolean matches(String method, String... path) {
      return is(method) && segments.equals(List.of(path));
    }

    boolean is(String method) {
      return this.method.equals(method);
    }

    boolean hasProjectPrefix() {
      return segments.size() >= 3 && V1.equals(segments.get(0)) && PROJECTS.equals(segments.get(1));
    }

    boolean hasEventsPrefix() {
      return segments.size() >= 2 && V1.equals(segments.get(0)) && EVENTS.equals(segments.get(1));
    }

    boolean hasGlobalSpecsPrefix() {
      return segments.size() >= 2 && V1.equals(segments.get(0)) && SPECS.equals(segments.get(1));
    }

    boolean hasReviewsPrefix() {
      return segments.size() >= 2 && V1.equals(segments.get(0)) && REVIEWS.equals(segments.get(1));
    }

    boolean isProjectRoot() {
      return segments.size() == 3;
    }

    String project() {
      return segments.get(2);
    }

    String resource() {
      return segments.get(3);
    }

    String subResource() {
      return segments.get(4);
    }

    int size() {
      return segments.size();
    }

    private static List<String> segments(String path) {
      if (path.equals("/")) {
        return List.of();
      }
      return Arrays.stream(path.substring(1).split("/"))
          .filter(segment -> !segment.isBlank())
          .toList();
    }
  }

  private record QueryParameters(Map<String, String> values) {
    static QueryParameters from(URI uri) {
      var query = uri.getRawQuery();
      if (query == null || query.isBlank()) {
        return new QueryParameters(Map.of());
      }
      var values = new LinkedHashMap<String, String>();
      for (var part : query.split("&")) {
        var separator = part.indexOf('=');
        var name = separator >= 0 ? part.substring(0, separator) : part;
        var value = separator >= 0 ? part.substring(separator + 1) : "";
        values.put(decode(name), decode(value));
      }
      return new QueryParameters(values);
    }

    int tail() {
      var value = values.get(TAIL);
      if (value == null) {
        return DEFAULT_TAIL;
      }
      try {
        var tail = Integer.parseInt(value);
        if (tail >= MIN_TAIL && tail <= MAX_TAIL) {
          return tail;
        }
      } catch (NumberFormatException ignored) {
      }
      throw new ApiException(ErrorCode.INVALID_TAIL, "tail must be between 1 and 5000.");
    }

    int limit() {
      var value = values.get(LIMIT);
      if (value == null) {
        return DEFAULT_RECENT;
      }
      try {
        var limit = Integer.parseInt(value);
        if (limit >= MIN_TAIL && limit <= MAX_TAIL) {
          return limit;
        }
      } catch (NumberFormatException ignored) {
      }
      throw new ApiException(ErrorCode.INVALID_REQUEST, "limit must be between 1 and 5000.");
    }

    private static String decode(String value) {
      return URLDecoder.decode(value, StandardCharsets.UTF_8);
    }
  }
}
