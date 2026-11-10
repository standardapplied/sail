/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import ai.singlr.sail.config.YamlUtil;
import ai.singlr.sail.store.FdeStore;
import ai.singlr.sail.store.SchemaManager;
import ai.singlr.sail.store.Sqlite;
import ai.singlr.sail.store.TokenStore;
import ai.singlr.sail.sync.SyncEngine;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A3 at the web API: every route declares one gate, the table the router dispatches through is the
 * whole of what it serves, and the router applies the gates that need no row (operating the box,
 * managing people) before any handler runs. The table is pinned whole, so a route added, removed or
 * re-gated is a change to this list; and the box routes are driven, so a gate taken off one is a
 * caller served what is not theirs.
 */
class RouteGateSweepTest {

  private static final String BOX = "uday";

  @TempDir Path tempDir;
  private Sqlite db;
  private BoxOperations operations;
  private HttpServer http;
  private ApiRouter router;

  @BeforeEach
  void setUp() throws Exception {
    db = Sqlite.open(tempDir.resolve("sail.db"));
    new SchemaManager(db).migrate();
    operations = new BoxOperations();
    var auth = TestAuth.tokens(db);
    router =
        new ApiRouter(operations, auth, new RateLimitGate(), new AgentLogStreamer(auth), () -> BOX);
    http = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    PrivateAttributes.context(http, "/", router);
    http.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
    http.start();
  }

  @AfterEach
  void tearDown() {
    http.stop(0);
    db.close();
  }

  @Test
  void everyWebRouteDeclaresTheOneGateThisTableNames() {
    var passkeys = new WebauthnAuthHandler(null, null, TestAuth.sessions(db), null);

    assertEquals(
        List.of(
            "GET /v1/health unauthenticated: the liveness probe",
            "GET /v1/whoami reads in scope",
            "GET /v1/fdes reads in scope",
            "GET /v1/agents reads in scope",
            "GET /v1/sync operates the box",
            "POST /v1/sync operates the box",
            "GET /v1/conflicts reads in scope",
            "GET /v1/conflicts/{id...} reads in scope",
            "POST /v1/conflicts/{id...} decides CHANGE through ConflictOperations",
            "GET /v1/events reads in scope",
            "POST /v1/events decides NARRATE through EventDoor",
            "GET /v1/events/recent reads in scope",
            "GET /v1/events/stats operates the box",
            "POST /v1/specs:prune decides PRUNE through SpecPruner",
            "GET /v1/specs reads in scope",
            "POST /v1/specs the journal decides",
            "GET /v1/specs/board reads in scope",
            "GET /v1/specs/{spec} reads in scope",
            "PUT /v1/specs/{spec} the journal decides",
            "DELETE /v1/specs/{spec} the journal decides",
            "GET /v1/specs/{spec}/content reads in scope",
            "PUT /v1/specs/{spec}/content the journal decides",
            "GET /v1/specs/{spec}/reviews reads in scope",
            "GET /v1/specs/{spec}/history reads in scope",
            "POST /v1/specs/{spec}/restore the journal decides",
            "POST /v1/specs/{spec}/followup the journal decides",
            "GET /v1/rooms reads in scope",
            "POST /v1/rooms the journal decides",
            "GET /v1/rooms/{room} reads in scope",
            "DELETE /v1/rooms/{room} the journal decides",
            "GET /v1/rooms/{room}/members reads in scope",
            "POST /v1/rooms/{room}/members decides DISPATCH through MembershipService",
            "DELETE /v1/rooms/{room}/members decides DISPATCH through MembershipService",
            "* /v1/rooms/{room}/invite reads in scope",
            "GET /v1/rooms/{room}/messages reads in scope",
            "POST /v1/rooms/{room}/messages the journal decides",
            "GET /v1/reviews/{review} reads in scope",
            "POST /v1/reviews/{review}/approve the journal decides",
            "POST /v1/reviews/{review}/dismiss/{finding} the journal decides",
            "GET /v1/runs reads in scope",
            "GET /v1/runs/{run} reads in scope",
            "GET /v1/runs/{run}/log decides OPERATE_RUN through LogAdmission",
            "POST /v1/runs/{run}/stop decides OPERATE_RUN through StopOperations",
            "* /v1/runs/{run}/stream decides OPERATE_RUN through LogAdmission",
            "GET /v1/projects reads in scope",
            "POST /v1/projects the journal decides",
            "GET /v1/projects/{project} reads in scope",
            "GET /v1/projects/{project}/specs reads in scope",
            "GET /v1/projects/{project}/specs/{spec} reads in scope",
            "* /v1/projects/{project}/specs/{spec}/{rest...} reads in scope",
            "POST /v1/projects/{project}/dispatch decides DISPATCH through LaunchAdmission",
            "* /v1/projects/{project}/dispatch/{rest...} reads in scope",
            "GET /v1/projects/{project}/agent reads in scope",
            "POST /v1/projects/{project}/agent/report reads in scope",
            "* /v1/projects/{project}/agent/{sub}/{rest...} reads in scope",
            "GET /v1/projects/{project}/connect reads in scope",
            "GET /v1/projects/{project}/snapshots reads in scope",
            "DELETE /v1/projects/{project}/snapshots/{label} decides RESTORE through ProjectGate",
            "POST /v1/projects/{project}/snapshots/{label}/restore decides RESTORE through"
                + " ProjectGate",
            "GET /v1/projects/{project}/files reads in scope",
            "GET /v1/projects/{project}/files/{path...} reads in scope",
            "PUT /v1/projects/{project}/files/{path...} the journal decides",
            "DELETE /v1/projects/{project}/files/{path...} the journal decides",
            "POST /v1/projects/{project}/serve decides SERVE through ProjectGate",
            "DELETE /v1/projects/{project}/serve decides SERVE through ProjectGate",
            "GET /v1/projects/{project}/ports reads in scope",
            "GET /v1/projects/{project}/definition decides READ_DEFINITION through ProjectGate",
            "PUT /v1/projects/{project}/definition/{section} the journal decides",
            "POST /v1/auth/register/ticket manages people",
            "POST /v1/auth/register/start unauthenticated: an enrollment ticket authorizes it;"
                + " without one, the people gate does",
            "POST /v1/auth/register/finish unauthenticated: an enrollment ticket authorizes it;"
                + " without one, the people gate does",
            "POST /v1/auth/login/start unauthenticated: the caller signs in with a passkey: it"
                + " holds no credential yet",
            "POST /v1/auth/login/finish unauthenticated: the caller signs in with a passkey: it"
                + " holds no credential yet"),
        Stream.of(router, passkeys)
            .flatMap(table -> table.routes().stream())
            .map(RouteGateSweepTest::described)
            .toList());
  }

  @Test
  void aRouteCannotBeDeclaredWithoutAGate() {
    assertEquals(
        "gate",
        assertThrows(
                NullPointerException.class,
                () -> new Route<>("GET", "/v1/ungated", null, (Runnable) () -> {}))
            .getMessage());
  }

  @Test
  void everyRouteButTheHealthProbeAsksForACredentialBeforeAnythingElse() throws Exception {
    var answers = new LinkedHashMap<String, Integer>();
    for (var route : router.routes()) {
      var method = route.method().equals("*") ? "GET" : route.method();
      answers.put(route.method() + " " + route.pattern(), send(method, sample(route), null, "{}"));
    }

    var expected = new LinkedHashMap<String, Integer>();
    for (var route : router.routes()) {
      expected.put(
          route.method() + " " + route.pattern(),
          route.gate() instanceof Gate.Unauthenticated ? 200 : 401);
    }
    assertEquals(expected, answers);
    assertEquals(List.of(), operations.reached);
  }

  @Test
  void theBoxIsOperatedByItsOwnPersonOrAnAdminAtEveryBoxRoute() throws Exception {
    var roster = new FdeStore(db);
    var tokens = new TokenStore(db);
    var owner = tokens.create("uday", "viewer", roster.add(BOX, null, null, "viewer").id(), null);
    var eng = tokens.create("eng", "member", roster.add("eng", null, null, "member").id(), null);
    var root = tokens.create("root", "admin", roster.add("root", null, null, "admin").id(), null);
    var machine = tokens.create("ci-bot", "member");
    var adminMachine = tokens.create("ops-bot", "admin");
    var boxRoutes = router.routes().stream().filter(route -> route.gate() == Gate.BOX).toList();
    assertEquals(3, boxRoutes.size());

    for (var refused : List.of(eng.token(), machine.token())) {
      for (var route : boxRoutes) {
        var response = call(route.method(), route.pattern(), refused, "{\"main\": \"elsewhere\"}");
        assertEquals(403, response.statusCode(), route.pattern());
        assertEquals(
            Map.of(
                "code",
                "forbidden_not_assignee",
                "message",
                "Box 'uday' belongs to 'uday', not you.",
                "action",
                "Ask uday to operate it, or have an admin do it.",
                "field_errors",
                List.of()),
            YamlUtil.parseMap(response.body()).get("error"),
            route.pattern());
      }
    }
    assertEquals(List.of(), operations.reached);

    for (var allowed : List.of(owner.token(), root.token(), adminMachine.token())) {
      for (var route : boxRoutes) {
        var response = call(route.method(), route.pattern(), allowed, "{\"main\": \"elsewhere\"}");
        assertEquals(200, response.statusCode(), route.pattern() + " " + response.body());
      }
    }
    assertEquals(
        List.of(
            "syncStatus",
            "sync elsewhere",
            "eventBusStats",
            "syncStatus",
            "sync elsewhere",
            "eventBusStats",
            "syncStatus",
            "sync elsewhere",
            "eventBusStats"),
        operations.reached);
  }

  /** The route's pattern with each placeholder filled. */
  private static String sample(Route<?> route) {
    return route
        .pattern()
        .replace("{id...}", "spec/auth")
        .replaceAll("\\{[a-z]+(\\.\\.\\.)?}", "acme");
  }

  private static String described(Route<?> route) {
    return route.method()
        + " "
        + route.pattern()
        + " "
        + switch (route.gate()) {
          case Gate.ReadsInScope reads -> "reads in scope";
          case Gate.Journal journal -> "the journal decides";
          case Gate.OperatesBox box -> "operates the box";
          case Gate.ManagesPeople people -> "manages people";
          case Gate.Decides(var action, var seam) ->
              "decides " + action.name() + " through " + seam.getSimpleName();
          case Gate.Unauthenticated(var reason) -> "unauthenticated: " + reason;
        };
  }

  private int send(String method, String path, String token, String body) throws Exception {
    return call(method, path, token, body).statusCode();
  }

  private HttpResponse<String> call(String method, String path, String token, String body)
      throws Exception {
    var request =
        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + http.getAddress().getPort() + path))
            .method(method, HttpRequest.BodyPublishers.ofString(body));
    if (token != null) {
      request.header("Authorization", "Bearer " + token);
    }
    try (var client = HttpClient.newHttpClient()) {
      return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }
  }

  /** The box's health, rounds and bus, recording each that is reached. */
  private static final class BoxOperations extends TestOperations {
    private final List<String> reached = new ArrayList<>();

    @Override
    public SyncStatus syncStatus() {
      reached.add("syncStatus");
      return new SyncStatus(
          "node",
          "main@host",
          SyncEngine.Report.NONE,
          "in_sync",
          null,
          null,
          0,
          null,
          null,
          null,
          0,
          0,
          0,
          List.of());
    }

    @Override
    public SyncReport sync(SyncRequest request) {
      reached.add("sync " + request.main());
      return new SyncReport(SyncEngine.Report.NONE, null, List.of());
    }

    @Override
    public Result<EventBusStatsResponse> eventBusStats() {
      reached.add("eventBusStats");
      return super.eventBusStats();
    }
  }
}
