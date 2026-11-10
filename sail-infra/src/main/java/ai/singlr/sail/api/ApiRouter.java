/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.api;

import ai.singlr.sail.access.Action;
import ai.singlr.sail.access.Principal;
import ai.singlr.sail.authority.WriteRefused;
import ai.singlr.sail.common.Remedied;
import ai.singlr.sail.common.Remedy;
import ai.singlr.sail.common.Strings;
import ai.singlr.sail.config.YamlUtil;
import ai.singlr.sail.engine.ConflictOperations;
import ai.singlr.sail.engine.FilePicker;
import ai.singlr.sail.engine.NameValidator;
import ai.singlr.sail.engine.NodeIdentity;
import ai.singlr.sail.identity.Actor;
import ai.singlr.sail.store.SpecStore;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import java.io.IOException;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * The web API's routes, each declaring its {@link Gate} (A3), dispatched through one table: the
 * first route whose pattern matches a request's path answers it, by the route of that pattern that
 * serves the request's method, or {@code 405} when none does; a path no pattern matches is {@code
 * 404}. A route served before any credential is asked ({@link Gate.Unauthenticated}) answers first;
 * every other request is authenticated and rate-limited, then answered as its caller, the actor
 * {@link ApiAuth} stamped, once the router has applied the route's gate.
 */
public final class ApiRouter implements HttpHandler, RouteTable {

  private static final String GET = "GET";
  private static final String POST = "POST";
  private static final String PUT = "PUT";
  private static final String DELETE = "DELETE";

  /** A route of this method serves every method. */
  private static final String ANY = "*";

  private static final String PROJECT = "/v1/projects/{project}";
  private static final String SPEC = "spec";
  private static final String SINCE = "since";
  private static final String LIMIT = "limit";
  private static final String TAIL = "tail";
  private static final String CONFLICTS = "/v1/conflicts/";
  private static final String RESOLVE = "/resolve";
  private static final int DEFAULT_MESSAGES = 50;
  private static final int MAX_MESSAGES = 100;
  private static final int DEFAULT_TAIL = 200;
  private static final int MIN_TAIL = 1;
  private static final int MAX_TAIL = 5000;
  private static final int DEFAULT_RECENT = 100;
  private static final int MAX_SPEC_EVENTS = 1000;

  private final Operations operations;
  private final ApiAuth auth;
  private final RateLimitGate rateLimits;
  private final AgentLogStreamer agentStreamer;
  private final Supplier<String> nodeHandle;
  private final List<Route<Handler>> routes = table();

  public ApiRouter(
      Operations operations,
      ApiAuth auth,
      RateLimitGate rateLimits,
      AgentLogStreamer agentStreamer) {
    this(operations, auth, rateLimits, agentStreamer, NodeIdentity::handle);
  }

  ApiRouter(
      Operations operations,
      ApiAuth auth,
      RateLimitGate rateLimits,
      AgentLogStreamer agentStreamer,
      Supplier<String> nodeHandle) {
    this.operations = operations;
    this.auth = auth;
    this.rateLimits = rateLimits;
    this.agentStreamer = Objects.requireNonNull(agentStreamer, "agentStreamer");
    this.nodeHandle = nodeHandle;
  }

  /** What answers a request a route serves, or null when it answered the exchange itself. */
  @FunctionalInterface
  private interface Handler {
    ApiResponse handle(Call call) throws Exception;
  }

  /**
   * A request a route serves: its exchange, its URI, and what its path fills of the route's
   * placeholders.
   */
  private record Call(HttpExchange exchange, URI uri, Map<String, String> params) {

    String param(String name) {
      return params.get(name);
    }

    Map<String, String> query() {
      return QueryParameters.from(uri).values();
    }

    Map<String, Object> body() throws IOException {
      return JsonBody.readMap(exchange);
    }

    /** The project this request addresses, refused when it is not a valid project name. */
    String project() {
      var project = param("project");
      NameValidator.requireValidProjectName(project);
      return project;
    }

    /** The spec or room this request addresses as {@code name}, refused when it is not an id. */
    String id(String name) {
      var id = param(name);
      NameValidator.requireValidSpecId(id);
      return id;
    }
  }

  /** The route that serves a request, and what its path fills of the route's placeholders. */
  private record Match(Route<Handler> route, Map<String, String> params) {}

  @Override
  public List<Route<?>> routes() {
    return List.copyOf(routes);
  }

  private List<Route<Handler>> table() {
    var reads = Gate.READS_IN_SCOPE;
    var journal = Gate.JOURNAL;
    var serving = Gate.decides(Action.SERVE, ProjectGate.class);
    var restoring = Gate.decides(Action.RESTORE, ProjectGate.class);
    var seating = Gate.decides(Action.DISPATCH, MembershipService.class);
    var runOperated = Gate.decides(Action.OPERATE_RUN, LogAdmission.class);
    return List.of(
        route(GET, "/v1/health", Gate.unauthenticated("the liveness probe"), this::health),
        route(GET, "/v1/whoami", reads, this::whoami),
        route(GET, "/v1/fdes", reads, call -> ApiResponse.from(operations.fdes())),
        route(GET, "/v1/agents", reads, call -> ApiResponse.from(operations.agents())),
        route(GET, "/v1/sync", Gate.BOX, this::syncStatus),
        route(POST, "/v1/sync", Gate.BOX, this::sync),
        route(GET, "/v1/conflicts", reads, this::conflicts),
        route(GET, "/v1/conflicts/{id...}", reads, this::conflict),
        route(
            POST,
            "/v1/conflicts/{id...}",
            Gate.decides(Action.CHANGE, ConflictOperations.class),
            this::resolveConflict),
        route(GET, "/v1/events", reads, this::specEvents),
        route(
            POST,
            "/v1/events",
            Gate.decides(Action.NARRATE, EventDoor.class),
            call -> ApiResponse.from(operations.publishEvent(JsonBody.readEvent(call.exchange())))),
        route(GET, "/v1/events/recent", reads, this::recentEvents),
        route(GET, "/v1/events/stats", Gate.BOX, this::eventBusStats),
        route(POST, "/v1/specs:prune", Gate.decides(Action.PRUNE, SpecPruner.class), this::prune),
        route(GET, "/v1/specs", reads, this::globalSpecs),
        route(POST, "/v1/specs", journal, this::createGlobalSpec),
        route(GET, "/v1/specs/board", reads, this::globalBoard),
        route(GET, "/v1/specs/{spec}", reads, call -> globalSpecWithEtag(call.id(SPEC))),
        route(PUT, "/v1/specs/{spec}", journal, this::updateGlobalSpec),
        route(DELETE, "/v1/specs/{spec}", journal, this::deleteGlobalSpec),
        route(GET, "/v1/specs/{spec}/content", reads, this::globalSpecContent),
        route(PUT, "/v1/specs/{spec}/content", journal, this::setGlobalSpecContent),
        route(GET, "/v1/specs/{spec}/reviews", reads, this::reviewsForSpec),
        route(GET, "/v1/specs/{spec}/history", reads, this::globalSpecHistory),
        route(POST, "/v1/specs/{spec}/restore", journal, this::restoreGlobalSpec),
        route(POST, "/v1/specs/{spec}/followup", journal, this::createFollowupSpec),
        route(GET, "/v1/rooms", reads, this::rooms),
        route(POST, "/v1/rooms", journal, this::createRoom),
        route(
            GET,
            "/v1/rooms/{room}",
            reads,
            call -> ApiResponse.from(operations.room(call.id("room")))),
        route(
            DELETE,
            "/v1/rooms/{room}",
            journal,
            call -> ApiResponse.from(operations.deleteRoom(call.id("room")))),
        route(
            GET,
            "/v1/rooms/{room}/members",
            reads,
            call -> ApiResponse.from(operations.roomMembers(call.id("room")))),
        route(POST, "/v1/rooms/{room}/members", seating, this::addRoomMember),
        route(DELETE, "/v1/rooms/{room}/members", seating, this::removeRoomMember),
        route(ANY, "/v1/rooms/{room}/invite", reads, this::retiredInvite),
        route(GET, "/v1/rooms/{room}/messages", reads, this::roomMessages),
        route(POST, "/v1/rooms/{room}/messages", journal, this::postRoomMessage),
        route(GET, "/v1/reviews/{review}", reads, this::reviewDetail),
        route(POST, "/v1/reviews/{review}/approve", journal, this::approveReview),
        route(POST, "/v1/reviews/{review}/dismiss/{finding}", journal, this::dismissFinding),
        route(GET, "/v1/runs", reads, this::runs),
        route(
            GET,
            "/v1/runs/{run}",
            reads,
            call -> ApiResponse.from(operations.run(call.param("run")))),
        route(GET, "/v1/runs/{run}/log", runOperated, this::runLog),
        route(
            POST,
            "/v1/runs/{run}/stop",
            Gate.decides(Action.OPERATE_RUN, StopOperations.class),
            this::stopRun),
        route(ANY, "/v1/runs/{run}/stream", runOperated, this::streamRunLog),
        route(GET, "/v1/projects", reads, call -> ApiResponse.from(operations.projects())),
        route(POST, "/v1/projects", journal, this::createProject),
        route(GET, PROJECT, reads, call -> ApiResponse.from(operations.project(call.project()))),
        route(
            GET,
            PROJECT + "/specs",
            reads,
            call -> ApiResponse.from(operations.specs(call.project()))),
        route(GET, PROJECT + "/specs/{spec}", reads, this::projectSpec),
        route(ANY, PROJECT + "/specs/{spec}/{rest...}", reads, this::malformed),
        route(
            POST,
            PROJECT + "/dispatch",
            Gate.decides(Action.DISPATCH, LaunchAdmission.class),
            this::dispatch),
        route(ANY, PROJECT + "/dispatch/{rest...}", reads, this::malformed),
        route(GET, PROJECT + "/agent", reads, this::agentStatus),
        route(POST, PROJECT + "/agent/report", reads, this::agentReport),
        route(ANY, PROJECT + "/agent/{sub}/{rest...}", reads, this::malformed),
        route(
            GET,
            PROJECT + "/connect",
            reads,
            call -> ApiResponse.from(operations.connect(call.project()))),
        route(
            GET,
            PROJECT + "/snapshots",
            reads,
            call -> ApiResponse.from(operations.snapshots(call.project()))),
        route(DELETE, PROJECT + "/snapshots/{label}", restoring, this::deleteSnapshot),
        route(POST, PROJECT + "/snapshots/{label}/restore", restoring, this::restoreSnapshot),
        route(GET, PROJECT + "/files", reads, this::listFiles),
        route(GET, PROJECT + "/files/{path...}", reads, this::readFile),
        route(PUT, PROJECT + "/files/{path...}", journal, this::putFile),
        route(DELETE, PROJECT + "/files/{path...}", journal, this::removeFile),
        route(POST, PROJECT + "/serve", serving, this::serve),
        route(
            DELETE,
            PROJECT + "/serve",
            serving,
            call -> ApiResponse.ok(operations.stack().stop(call.project()))),
        route(
            GET,
            PROJECT + "/ports",
            reads,
            call -> ApiResponse.ok(operations.stack().ports(call.project()))),
        route(
            GET,
            PROJECT + "/definition",
            Gate.decides(Action.READ_DEFINITION, ProjectGate.class),
            call -> ApiResponse.from(operations.projectDefinition(call.project()))),
        route(PUT, PROJECT + "/definition/{section}", journal, this::writeProjectSection));
  }

  private static Route<Handler> route(String method, String pattern, Gate gate, Handler handler) {
    return new Route<>(method, pattern, gate, handler);
  }

  @Override
  public void handle(HttpExchange exchange) throws IOException {
    try {
      var response =
          ScopedValue.where(SyncControl.NO_SYNC, noSyncRequested(exchange))
              .call(() -> route(exchange));
      if (response != null) {
        write(exchange, response);
      }
    } catch (ApiException e) {
      write(exchange, ApiResponse.error(e.failure()));
    } catch (WriteRefused e) {
      write(exchange, ApiResponse.error(Refusals.exception(e.refusal()).failure()));
    } catch (IllegalArgumentException e) {
      write(exchange, ApiResponse.error(ApiException.of(ErrorCode.INVALID_REQUEST, e).failure()));
    } catch (Exception e) {
      ApiLog.unexpected(exchange.getRequestMethod() + " " + exchange.getRequestURI().getPath(), e);
      write(
          exchange,
          ApiResponse.error(
              e instanceof Remedied
                  ? ApiException.of(ErrorCode.INTERNAL, e).failure()
                  : new Result.Failure<>(
                      ErrorCode.INTERNAL,
                      "sail API request failed.",
                      Remedy.of("Check sail API logs."),
                      null,
                      e)));
    } finally {
      exchange.close();
    }
  }

  /**
   * Answers {@code exchange} by the route that serves it: one served before any credential at once;
   * any other only once its caller is authenticated and within its rate, as that caller, past the
   * route's gate. A request no route serves is refused once its caller is authenticated, so an
   * unauthenticated caller learns nothing of the table.
   */
  ApiResponse route(HttpExchange exchange) throws Exception {
    var uri = exchange.getRequestURI();
    var path = canonical(uri);
    var method = exchange.getRequestMethod();
    var match = match(path, method);
    if (match.isPresent() && match.get().route().gate() instanceof Gate.Unauthenticated) {
      return match.get().route().handler().handle(new Call(exchange, uri, match.get().params()));
    }
    auth.require(exchange);
    rateLimits.require(exchange);
    var served = match.orElseThrow(() -> unserved(path));
    var actor = actorOf(exchange);
    return Actor.call(
        actor,
        () -> {
          served.route().gate().admit(actor, nodeHandle);
          return served.route().handler().handle(new Call(exchange, uri, served.params()));
        });
  }

  /**
   * The route serving {@code method} on {@code path}: of the routes whose pattern is the first to
   * match it, the one of that method.
   */
  private Optional<Match> match(String path, String method) {
    return routes.stream()
        .flatMap(route -> route.match(path).map(params -> new Match(route, params)).stream())
        .findFirst()
        .flatMap(
            first ->
                routes.stream()
                    .filter(route -> route.pattern().equals(first.route().pattern()))
                    .filter(route -> route.method().equals(ANY) || route.method().equals(method))
                    .findFirst()
                    .map(route -> new Match(route, first.params())));
  }

  /**
   * Why no route serves {@code path}: its method is not one a matching route serves, or none does.
   */
  private ApiException unserved(String path) {
    return routes.stream().anyMatch(route -> route.match(path).isPresent())
        ? methodNotAllowed()
        : notFound();
  }

  /** {@code uri}'s path as the table matches it: no empty segment, and no trailing slash. */
  private static String canonical(URI uri) {
    return Arrays.stream(Objects.requireNonNullElse(uri.getPath(), "").split("/"))
        .filter(segment -> !segment.isBlank())
        .collect(Collectors.joining("/", "/", ""));
  }

  /**
   * The actor {@link ApiAuth} stamped on {@code exchange} as its caller: the router binds it for
   * the route, and the streams re-read it after each re-authentication. A request no credential
   * resolved has none, which is a door that skipped its authentication, refused loudly.
   */
  static Actor actorOf(HttpExchange exchange) {
    if (exchange.getAttribute(ApiAuth.ACTOR) instanceof Actor actor) {
      return actor;
    }
    throw new IllegalStateException("No credential was resolved for this request.");
  }

  private ApiResponse health(Call call) {
    return ApiResponse.from(operations.health());
  }

  private ApiResponse syncStatus(Call call) {
    return ApiResponse.ok(SyncViews.status(operations.syncStatus()));
  }

  private ApiResponse sync(Call call) throws Exception {
    var main = text(call.body(), "main");
    return ApiResponse.ok(SyncViews.round(operations.sync(new SyncRequest(main))));
  }

  private ApiResponse conflicts(Call call) {
    return ApiResponse.ok(SyncViews.conflicts(operations.conflicts()));
  }

  /**
   * A parked conflict, by the id its URL names as sent: a file conflict's id is the file's path,
   * whitespace segments and all.
   */
  private ApiResponse conflict(Call call) {
    var query = call.query();
    var type = query.get("type");
    var id = call.uri().getPath().substring(CONFLICTS.length());
    if (Boolean.parseBoolean(query.get("template"))) {
      return ApiResponse.ok(Map.of("template", operations.conflictMergeTemplate(type, id)));
    }
    var conflict = operations.conflict(type, id);
    if (conflict == null) {
      throw notFound();
    }
    return ApiResponse.ok(SyncViews.conflict(conflict));
  }

  /** {@code POST /v1/conflicts/{id}/resolve}; a POST to a conflict that names no resolve is 405. */
  private ApiResponse resolveConflict(Call call) throws IOException {
    var path = call.uri().getPath();
    if (!path.endsWith(RESOLVE) || !call.param("id").contains("/")) {
      throw methodNotAllowed();
    }
    var id = path.substring(CONFLICTS.length(), path.length() - RESOLVE.length());
    var body = call.body();
    var strategy = text(body, "strategy");
    if (strategy == null) {
      throw new IllegalArgumentException("resolution strategy is required");
    }
    var resolution =
        new Resolution(
            Resolution.Strategy.valueOf(strategy.toUpperCase(Locale.ROOT)), text(body, "merged"));
    return ApiResponse.ok(
        SyncViews.conflict(operations.resolveConflict(call.query().get("type"), id, resolution)));
  }

  private ApiResponse listFiles(Call call) {
    var project = call.project();
    var files = operations.projectFiles(project);
    sharedPath(call, project);
    return ApiResponse.ok(
        Map.of(
            "files",
            files.list().stream()
                .map(
                    row ->
                        Map.of(
                            "path",
                            row.path(),
                            "bytes",
                            row.size(),
                            "mode",
                            row.mode(),
                            "kind",
                            row.kind(),
                            "content_hash",
                            row.contentHash()))
                .toList()));
  }

  private ApiResponse readFile(Call call) {
    var project = call.project();
    var files = operations.projectFiles(project);
    var row = files.find(sharedPath(call, project)).orElseThrow(ApiRouter::notFound);
    var etag = "\"" + row.contentHash() + "\"";
    var condition = call.exchange().getRequestHeaders().getFirst("If-None-Match");
    if (condition != null
        && Arrays.stream(condition.split(","))
            .map(String::strip)
            .anyMatch(
                value -> value.equals("*") || value.equals(etag) || value.equals("W/" + etag))) {
      return new ApiResponse(304, Map.of(), Map.of("ETag", etag));
    }
    return ApiResponse.file(files.open(row), row.size(), row.contentHash());
  }

  private ApiResponse putFile(Call call) throws IOException {
    var project = call.project();
    var files = operations.projectFiles(project);
    var path = sharedPath(call, project);
    var declared = call.exchange().getRequestHeaders().getFirst("Content-Length");
    if (declared == null) {
      throw new IllegalArgumentException("Content-Length is required for a shared file");
    }
    var size = Long.parseLong(declared);
    files
        .limits()
        .problem(size)
        .ifPresent(
            problem -> {
              throw new ApiException(ErrorCode.REQUEST_TOO_LARGE, problem);
            });
    var mode = files.find(path).map(row -> row.mode()).orElse(0644);
    var storedPath = files.put(path, call.exchange().getRequestBody(), size, mode);
    files.materialize();
    return ApiResponse.ok(Map.of("path", storedPath));
  }

  private ApiResponse removeFile(Call call) throws IOException {
    var project = call.project();
    var files = operations.projectFiles(project);
    var path = sharedPath(call, project);
    if (!files.remove(path)) {
      throw notFound();
    }
    return ApiResponse.ok(Map.of("path", path, "removed", true));
  }

  /**
   * The shared file {@code call}'s URL names in {@code project}, empty for the list, read from the
   * URL as sent and refused when it is not a path a project shares.
   */
  private static String sharedPath(Call call, String project) {
    var prefix = "/v1/projects/" + project + "/files";
    var fullPath = call.uri().getPath();
    if (!fullPath.startsWith(prefix)) {
      throw new IllegalArgumentException("Invalid shared file URL.");
    }
    var suffix = fullPath.substring(prefix.length());
    if (suffix.isEmpty() || suffix.equals("/")) {
      return "";
    }
    var path = suffix.substring(1);
    if (!FilePicker.isShareablePath(path)) {
      throw new IllegalArgumentException("Unsafe share path: '" + path + "'.");
    }
    return path;
  }

  private static String text(Map<String, Object> body, String field) {
    var value = body.get(field);
    if (value == null || value instanceof String) {
      return (String) value;
    }
    throw new IllegalArgumentException(field + " must be a string");
  }

  /**
   * {@code DELETE /v1/projects/{p}/snapshots/{label}} and {@code POST
   * .../snapshots/{label}/restore} are accepted asynchronously (202); their completion arrives as
   * {@code snapshot_deleted} / {@code snapshot_restored} events on the stream, so no client ever
   * holds a request open for a mutation that can outlive its timeout.
   */
  private ApiResponse deleteSnapshot(Call call) {
    return ApiResponse.fromAccepted(operations.deleteSnapshot(call.project(), call.param("label")));
  }

  private ApiResponse restoreSnapshot(Call call) {
    return ApiResponse.fromAccepted(
        operations.restoreSnapshot(call.project(), call.param("label"), nodeHandle.get()));
  }

  /**
   * {@code PUT /v1/projects/{p}/definition/{section}} with {@code {"value": ...}} writes one
   * section, only over the one its {@code If-Match} tag names when it names one.
   */
  private ApiResponse writeProjectSection(Call call) throws IOException {
    var project = call.project();
    var body = call.body();
    if (!body.containsKey("value")) {
      throw new IllegalArgumentException(
          "The body names the section's new value as {\"value\": ...}; null unsets it.");
    }
    return ApiResponse.from(
        operations.writeProjectSection(
            project,
            call.param("section"),
            body.get("value"),
            call.exchange().getRequestHeaders().getFirst("If-Match")));
  }

  /**
   * {@code POST} brings the project's stack up and answers when every declared port listens or its
   * process exits, or {@code timeout} seconds (default 60) have passed.
   */
  private ApiResponse serve(Call call) throws Exception {
    var project = call.project();
    var timeout = call.query().getOrDefault("timeout", "60");
    return ApiResponse.ok(operations.stack().serve(project, Duration.ofSeconds(seconds(timeout))));
  }

  /** {@code value} as whole seconds, refused with the field named when it is not one. */
  static long seconds(String value) {
    try {
      return Long.parseLong(value.strip());
    } catch (NumberFormatException notSeconds) {
      throw new IllegalArgumentException("timeout must be whole seconds, got: " + value);
    }
  }

  private ApiResponse projectSpec(Call call) {
    var project = call.project();
    return ApiResponse.from(operations.spec(project, call.id(SPEC)));
  }

  /** A path below a resource that has none, which names no method that could serve it. */
  private ApiResponse malformed(Call call) {
    call.project();
    throw methodNotAllowed();
  }

  private ApiResponse dispatch(Call call) throws IOException {
    var project = call.project();
    return ApiResponse.from(
        operations.dispatch(
            project, JsonBody.readDispatchRequest(call.exchange()), nodeHandle.get()));
  }

  private ApiResponse createProject(Call call) throws IOException {
    return ApiResponse.fromCreated(operations.createProject(call.body()));
  }

  private ApiResponse agentStatus(Call call) {
    return ApiResponse.from(operations.agentStatus(call.project(), nodeHandle.get()));
  }

  private ApiResponse agentReport(Call call) {
    return ApiResponse.from(operations.agentReport(call.project(), nodeHandle.get()));
  }

  private ApiResponse rooms(Call call) {
    return ApiResponse.from(operations.rooms(call.query().get("project")));
  }

  private ApiResponse createRoom(Call call) throws IOException {
    return ApiResponse.fromCreated(
        operations.createRoom(RoomCreateRequest.fromMap(call.body()), nodeHandle.get()));
  }

  private ApiResponse addRoomMember(Call call) throws IOException {
    var roomId = call.id("room");
    return ApiResponse.from(
        operations.addRoomMember(roomId, EngageRequest.fromMap(call.body()), nodeHandle.get()));
  }

  private ApiResponse removeRoomMember(Call call) {
    return ApiResponse.from(operations.removeRoomMember(call.id("room"), nodeHandle.get()));
  }

  /**
   * The retired one-shot invite door answers with a pointed 404, so an old client learns where the
   * verb went.
   */
  private ApiResponse retiredInvite(Call call) {
    call.id("room");
    throw new ApiException(
        ErrorCode.NOT_FOUND,
        "Room invites are retired: each room verb maps to one primitive.",
        "Add a member (POST /v1/rooms/{id}/members) to converse, or dispatch a spec to"
            + " delegate work.");
  }

  private ApiResponse roomMessages(Call call) {
    var roomId = call.id("room");
    var params = call.query();
    return ApiResponse.from(
        operations.roomMessages(
            roomId,
            params.get("before"),
            params.get("after"),
            clampedLimit(params.get(LIMIT), DEFAULT_MESSAGES, MAX_MESSAGES)));
  }

  /** A post is the caller's: the message rule decides it as the journal records it. */
  private ApiResponse postRoomMessage(Call call) throws IOException {
    var roomId = call.id("room");
    return ApiResponse.fromCreated(
        operations.postRoomMessage(
            roomId,
            SpecMessageRequest.fromMap(JsonBody.readMessageMap(call.exchange())),
            actorOf(call.exchange()).handle()));
  }

  private ApiResponse globalSpecs(Call call) {
    var params = call.query();
    return ApiResponse.from(
        operations.globalSpecs(
            new SpecStore.SpecFilter(
                params.get("project"),
                params.get("status"),
                resolveAssignee(params.get("assignee"), caller(call.exchange())),
                params.get("repo"),
                params.get("q"))));
  }

  private ApiResponse createGlobalSpec(Call call) throws IOException {
    return ApiResponse.fromCreated(
        operations.createGlobalSpec(SpecCreateRequest.fromMap(call.body())));
  }

  private ApiResponse globalBoard(Call call) {
    return ApiResponse.from(operations.globalBoard(call.query().get("project")));
  }

  private ApiResponse updateGlobalSpec(Call call) throws IOException {
    var specId = call.id(SPEC);
    checkIfMatch(call.exchange(), specId);
    return ApiResponse.from(
        operations.updateGlobalSpec(specId, SpecUpdateRequest.fromMap(call.body())));
  }

  private ApiResponse deleteGlobalSpec(Call call) {
    var specId = call.id(SPEC);
    checkIfMatch(call.exchange(), specId);
    return ApiResponse.from(operations.deleteGlobalSpec(specId));
  }

  private ApiResponse globalSpecContent(Call call) {
    return ApiResponse.from(operations.globalSpecContent(call.id(SPEC)));
  }

  private ApiResponse setGlobalSpecContent(Call call) throws IOException {
    var specId = call.id(SPEC);
    checkIfMatch(call.exchange(), specId);
    return ApiResponse.from(
        operations.setGlobalSpecContent(specId, SpecContentRequest.fromMap(call.body())));
  }

  private ApiResponse reviewsForSpec(Call call) {
    return ApiResponse.from(operations.reviewsForSpec(call.id(SPEC)));
  }

  private ApiResponse globalSpecHistory(Call call) {
    return ApiResponse.from(operations.globalSpecHistory(call.id(SPEC)));
  }

  private ApiResponse restoreGlobalSpec(Call call) throws IOException {
    var specId = call.id(SPEC);
    return ApiResponse.from(
        operations.restoreGlobalSpec(specId, SpecRestoreRequest.fromMap(call.body())));
  }

  private ApiResponse createFollowupSpec(Call call) throws IOException {
    var specId = call.id(SPEC);
    return ApiResponse.fromCreated(
        operations.createFollowupSpec(specId, FollowupCreateRequest.fromMap(call.body())));
  }

  private ApiResponse prune(Call call) throws IOException {
    return ApiResponse.from(operations.pruneSpecs(PruneRequest.fromMap(call.body())));
  }

  private ApiResponse reviewDetail(Call call) {
    return ApiResponse.from(operations.reviewDetail(call.param("review")));
  }

  private ApiResponse approveReview(Call call) {
    return ApiResponse.from(operations.approveReview(call.param("review")));
  }

  private ApiResponse dismissFinding(Call call) {
    return ApiResponse.from(operations.dismissFinding(call.param("review"), call.param("finding")));
  }

  /**
   * {@code GET /v1/events?spec=<id>[&since=<eventId>][&limit=]} is the spec-scoped history read:
   * {@code spec} is required for this route shape (the unscoped firehose stays {@code /recent}),
   * {@code since} is the exclusive monotonic event-id cursor for gap-fill after an SSE reconnect,
   * and {@code limit} is clamped rather than rejected.
   */
  private ApiResponse specEvents(Call call) {
    var params = call.query();
    var specId = params.get(SPEC);
    if (Strings.isBlank(specId)) {
      throw new ApiException(
          ErrorCode.INVALID_REQUEST,
          "spec query parameter is required.",
          "Use GET /v1/events?spec=<id> for a spec's history;"
              + " the unscoped window is GET /v1/events/recent.");
    }
    NameValidator.requireValidSpecId(specId);
    return ApiResponse.from(
        operations.specEvents(
            specId,
            sinceOf(params.get(SINCE)),
            clampedLimit(params.get(LIMIT), DEFAULT_RECENT, MAX_SPEC_EVENTS)));
  }

  private ApiResponse recentEvents(Call call) {
    return ApiResponse.from(operations.recentEvents(QueryParameters.from(call.uri()).limit()));
  }

  private ApiResponse eventBusStats(Call call) {
    return ApiResponse.from(operations.eventBusStats());
  }

  private static Long sinceOf(String value) {
    if (Strings.isBlank(value)) {
      return null;
    }
    try {
      var since = Long.parseLong(value);
      if (since < 0) {
        throw new NumberFormatException();
      }
      return since;
    } catch (NumberFormatException e) {
      throw new ApiException(ErrorCode.INVALID_REQUEST, "since must be a non-negative integer.");
    }
  }

  private ApiResponse runs(Call call) {
    var params = call.query();
    return ApiResponse.from(operations.runs(params.get("project"), params.get(SPEC)));
  }

  /**
   * A run's log and its stop pass this box's handle so the operation's provenance guard can refuse
   * a run that executed elsewhere.
   */
  private ApiResponse runLog(Call call) {
    return ApiResponse.from(
        operations.runLog(
            call.param("run"), QueryParameters.from(call.uri()).tail(), nodeHandle.get()));
  }

  private ApiResponse stopRun(Call call) {
    return ApiResponse.from(operations.stopRun(call.param("run"), nodeHandle.get()));
  }

  /** The run's log as a stream, which {@link AgentLogStreamer} answers and closes itself. */
  private ApiResponse streamRunLog(Call call) throws IOException {
    agentStreamer.handle(call.exchange());
    return null;
  }

  /** Whether the caller sent the {@code --no-sync} opt-out header for this request. */
  private static boolean noSyncRequested(HttpExchange exchange) {
    return "true"
        .equalsIgnoreCase(exchange.getRequestHeaders().getFirst(SyncControl.NO_SYNC_HEADER));
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
   * Reflects the authenticated caller back: {@code fde} is present only for a credential that acts
   * as an FDE, {@code name} is the credential's name, and {@code display_name} and {@code email}
   * are present only on a passkey session (the {@link SessionAwareAuth} path resolves the FDE
   * record). Its role and capabilities are {@link Principal}'s view of the caller, so clients gate
   * UI on a capability rather than a hardcoded role→power map. Marked {@code Cache-Control:
   * no-store} because the response is per-credential session state.
   */
  private ApiResponse whoami(Call call) {
    var exchange = call.exchange();
    var actor = actorOf(exchange);
    var principal = Principal.of(actor);
    var response =
        new WhoamiResponse(
            actor.handle(),
            Objects.toString(exchange.getAttribute("token.name"), null),
            Objects.toString(exchange.getAttribute("token.displayName"), null),
            Objects.toString(exchange.getAttribute("token.email"), null),
            principal.role(),
            principal.capabilities());
    return ApiResponse.ok(response).withHeader("Cache-Control", "no-store");
  }

  /**
   * Who {@code me} names in a list filter: the caller's FDE when it acts as one, otherwise the
   * token's name (a machine credential).
   */
  private static String caller(HttpExchange exchange) {
    var fde = actorOf(exchange).handle();
    if (fde != null) {
      return fde;
    }
    var name = exchange.getAttribute("token.name");
    return name == null ? null : name.toString();
  }

  private static int clampedLimit(String value, int fallback, int maximum) {
    if (Strings.isBlank(value)) {
      return fallback;
    }
    try {
      return Math.clamp(Integer.parseInt(value), 1, maximum);
    } catch (NumberFormatException e) {
      throw new ApiException(ErrorCode.BAD_REQUEST, "limit must be an integer");
    }
  }

  private static ApiException methodNotAllowed() {
    return new ApiException(
        ErrorCode.METHOD_NOT_ALLOWED, "HTTP method is not allowed for this endpoint.");
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
      throw ApiException.of(failure);
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
    var headers = exchange.getResponseHeaders();
    headers.set("Content-Type", "application/json; charset=utf-8");
    for (var entry : response.headers().entrySet()) {
      headers.set(entry.getKey(), entry.getValue());
    }
    if (response.status() == 304) {
      exchange.sendResponseHeaders(304, -1);
      return;
    }
    if (response.content() != null) {
      try (var input = response.content()) {
        var size = Long.parseLong(response.headers().get("Content-Length"));
        exchange.sendResponseHeaders(response.status(), size == 0 ? -1 : size);
        try (var output = exchange.getResponseBody()) {
          input.transferTo(output);
        }
      }
      return;
    }
    var body =
        YamlUtil.dumpJson(new LinkedHashMap<>(response.body())).getBytes(StandardCharsets.UTF_8);
    exchange.sendResponseHeaders(response.status(), body.length == 0 ? -1 : body.length);
    try (var output = exchange.getResponseBody()) {
      output.write(body);
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
