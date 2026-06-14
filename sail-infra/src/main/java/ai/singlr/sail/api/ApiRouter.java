/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.api;

import ai.singlr.sail.common.Strings;
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
import java.util.Objects;

public final class ApiRouter implements HttpHandler {

  private static final String GET = "GET";
  private static final String POST = "POST";
  private static final String PUT = "PUT";
  private static final String DELETE = "DELETE";
  private static final String V1 = "v1";
  private static final String HEALTH = "health";
  private static final String PROJECTS = "projects";
  private static final String SPECS = "specs";
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
  private static final String HISTORY = "history";
  private static final String RESTORE = "restore";
  private static final String SESSIONS = "sessions";
  private static final String APPROVE = "approve";
  private static final String DISMISS = "dismiss";
  private static final int DEFAULT_TAIL = 200;
  private static final int MIN_TAIL = 1;
  private static final int MAX_TAIL = 5000;
  private static final int DEFAULT_RECENT = 100;

  private static final int DEFAULT_PERMITS_PER_MINUTE = 600;
  private static final int DEFAULT_BURST = 600;

  private final ApiOperations operations;
  private final ApiAuth auth;
  private final RateLimiter rateLimiter;

  public ApiRouter(ApiOperations operations, ApiAuth auth) {
    this(operations, auth, RateLimiter.perMinute(DEFAULT_PERMITS_PER_MINUTE, DEFAULT_BURST));
  }

  public ApiRouter(ApiOperations operations, ApiAuth auth, RateLimiter rateLimiter) {
    this.operations = operations;
    this.auth = auth;
    this.rateLimiter = rateLimiter;
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
    requireWithinRateLimit(exchange);
    Authorizer.require(exchange, Authorizer.capabilityFor(request.method()));

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
      case DISPATCH -> routeDispatch(exchange, request, project);
      case AGENT -> routeAgent(request, project);
      default -> throw notFound();
    };
  }

  /**
   * Throttles per credential after authentication, so one client (a token's worth of identity)
   * cannot exhaust the server. Keyed by token name; the role gate runs next, so even a request the
   * caller is not authorized for still counts against their budget.
   */
  private void requireWithinRateLimit(HttpExchange exchange) {
    var key = Objects.toString(exchange.getAttribute("token.name"), "anonymous");
    if (!rateLimiter.tryAcquire(key)) {
      throw new ApiException(
          ErrorCode.RATE_LIMITED,
          "Rate limit exceeded. Slow down and retry shortly.",
          "This credential exceeded " + DEFAULT_PERMITS_PER_MINUTE + " requests per minute.");
    }
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

  private ApiResponse routeDispatch(HttpExchange exchange, RouteRequest request, String project)
      throws IOException {
    if (request.size() != 4) {
      throw methodNotAllowed();
    }
    requireMethod(request, POST);
    Authorizer.require(exchange, Capability.ADMIN);
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
                      params.values().get("project"),
                      params.values().get("status"),
                      resolveAssignee(params.values().get("assignee"), actor(exchange)),
                      params.values().get("repo"),
                      params.values().get("q"))));
        }
        case POST ->
            ApiResponse.fromCreated(
                operations.createGlobalSpec(
                    SpecCreateRequest.fromMap(JsonBody.readMap(exchange))
                        .withCreatedBy(actor(exchange))));
        default -> throw methodNotAllowed();
      };
    }
    if (request.size() == 3) {
      var segment = request.segments().get(2);
      if (BOARD.equals(segment)) {
        requireMethod(request, GET);
        var params = QueryParameters.from(request.uri());
        return ApiResponse.from(operations.globalBoard(params.values().get("project")));
      }
      var specId = segment;
      NameValidator.requireValidSpecId(specId);
      return switch (request.method()) {
        case GET -> globalSpecWithEtag(specId);
        case PUT -> {
          checkIfMatch(exchange, specId);
          yield ApiResponse.from(
              operations.updateGlobalSpec(
                  specId,
                  SpecUpdateRequest.fromMap(JsonBody.readMap(exchange))
                      .withUpdatedBy(actor(exchange))));
        }
        case DELETE -> {
          checkIfMatch(exchange, specId);
          yield ApiResponse.from(operations.deleteGlobalSpec(specId));
        }
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
          case PUT -> {
            checkIfMatch(exchange, specId);
            yield ApiResponse.from(
                operations.setGlobalSpecContent(
                    specId, SpecContentRequest.fromMap(JsonBody.readMap(exchange))));
          }
          default -> throw methodNotAllowed();
        };
      }
      if (REVIEWS.equals(sub)) {
        requireMethod(request, GET);
        return ApiResponse.from(operations.reviewsForSpec(specId));
      }
      if (HISTORY.equals(sub)) {
        requireMethod(request, GET);
        return ApiResponse.from(operations.globalSpecHistory(specId));
      }
      if (RESTORE.equals(sub)) {
        requireMethod(request, POST);
        return ApiResponse.from(
            operations.restoreGlobalSpec(
                specId, SpecRestoreRequest.fromMap(JsonBody.readMap(exchange))));
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
          Authorizer.require(exchange, Capability.ADMIN);
          yield ApiResponse.from(operations.approveReview(reviewId, actor(exchange)));
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
        Authorizer.require(exchange, Capability.ADMIN);
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

  /**
   * Resolves the {@code assignee} filter value, expanding the sentinel {@code "me"} to the acting
   * principal so {@code spec list --assignee me} returns the caller's own specs. Left unchanged
   * when the actor is unknown, or for any other value.
   */
  static String resolveAssignee(String assignee, String actor) {
    return "me".equals(assignee) && actor != null ? actor : assignee;
  }

  /**
   * Resolves the acting principal for attribution: the authenticated FDE handle when the token has
   * an owner, otherwise the token name (machine/CI credential). Null only when unauthenticated.
   */
  private static String actor(HttpExchange exchange) {
    var fde = exchange.getAttribute("token.fde");
    if (fde != null) {
      return fde.toString();
    }
    var name = exchange.getAttribute("token.name");
    return name == null ? null : name.toString();
  }

  private static ApiException methodNotAllowed() {
    return new ApiException(
        ErrorCode.METHOD_NOT_ALLOWED, "HTTP method is not allowed for this endpoint.");
  }

  private static String cleanPath(URI uri) {
    var path = uri.getPath();
    return Strings.isBlank(path) ? "/" : path;
  }

  private static ApiException notFound() {
    return new ApiException(ErrorCode.NOT_FOUND, "API endpoint was not found.");
  }

  /**
   * Loads a spec and returns the response with an {@code ETag} header set to its {@code updated_at}
   * timestamp. Clients echo that value back via {@code If-Match} on subsequent {@code PUT}/{@code
   * DELETE} to get optimistic-concurrency protection — the server rejects the write with {@code 412
   * Precondition Failed} if anyone modified the row in the meantime.
   */
  private ApiResponse globalSpecWithEtag(String specId) {
    var result = operations.globalSpec(specId);
    var response = ApiResponse.from(result);
    if (result instanceof Result.Success<?> success
        && success.value() instanceof GlobalSpecDetailResponse detail) {
      var etag = etagOf(detail.spec().updatedAt());
      if (etag != null) {
        return response.withHeader("ETag", etag);
      }
    }
    return response;
  }

  /**
   * Enforces optimistic-concurrency when the caller sent {@code If-Match}. Absent header = no
   * check, last-write-wins (preserves CLI ergonomics). Present header must equal the current spec's
   * {@code updated_at}, otherwise the write is rejected with {@code 412}.
   */
  private void checkIfMatch(HttpExchange exchange, String specId) {
    var ifMatchHeaders = exchange.getRequestHeaders().get("If-Match");
    if (ifMatchHeaders == null || ifMatchHeaders.isEmpty()) {
      return;
    }
    var ifMatch = ifMatchHeaders.getFirst();
    if (Strings.isBlank(ifMatch) || "*".equals(ifMatch.trim())) {
      return;
    }
    var detail = operations.globalSpec(specId);
    if (detail instanceof Result.Failure<?> failure) {
      throw new ApiException(failure.errorCode(), failure.errorMessage());
    }
    var current = ((Result.Success<GlobalSpecDetailResponse>) detail).value();
    var currentEtag = etagOf(current.spec().updatedAt());
    if (!ifMatch.trim().equals(currentEtag)) {
      throw new ApiException(
          ErrorCode.PRECONDITION_FAILED,
          "Spec '" + specId + "' was modified by another writer.",
          "Re-GET the spec, replay your changes against the fresh ETag, then retry.");
    }
  }

  private static String etagOf(String updatedAt) {
    if (Strings.isBlank(updatedAt)) {
      return null;
    }
    return "\"" + updatedAt + "\"";
  }

  private static void write(HttpExchange exchange, ApiResponse response) throws IOException {
    var body =
        YamlUtil.dumpJson(new LinkedHashMap<>(response.body())).getBytes(StandardCharsets.UTF_8);
    var headers = exchange.getResponseHeaders();
    headers.set("Content-Type", "application/json; charset=utf-8");
    for (var entry : response.headers().entrySet()) {
      headers.set(entry.getKey(), entry.getValue());
    }
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
      if (Strings.isBlank(query)) {
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
