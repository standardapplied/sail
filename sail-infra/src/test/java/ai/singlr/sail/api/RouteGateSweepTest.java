/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.sail.config.YamlUtil;
import ai.singlr.sail.identity.Actor;
import ai.singlr.sail.identity.Role;
import ai.singlr.sail.store.FdeStore;
import ai.singlr.sail.store.RunStore;
import ai.singlr.sail.store.SchemaManager;
import ai.singlr.sail.store.Sqlite;
import ai.singlr.sail.store.TokenStore;
import ai.singlr.sail.sync.SyncEngine;
import com.sun.net.httpserver.HttpServer;
import java.lang.reflect.Proxy;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A3 at the web API and the container socket: every route declares one gate, the table each router
 * dispatches through is the whole of what it serves, and the router applies the gates that need no
 * row (operating the box, managing people) before any handler runs. Each table is pinned whole, so
 * a route added, removed or re-gated is a change to its list; the box routes are driven, so a gate
 * taken off one is a caller served what is not theirs; and a request no route of the socket
 * declares reaches no operation.
 */
class RouteGateSweepTest {

  private static final String BOX = "uday";
  private static final RunStore.RunRow RUN =
      new TestOperations().runForCredential(TestOperations.RUN_CREDENTIAL).orElseThrow();
  private static final Set<String> METHODS = Set.of("GET", "POST", "PUT", "DELETE", "PATCH");

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
            "* /v1/health reads in scope",
            "GET /v1/whoami reads in scope",
            "* /v1/whoami reads in scope",
            "GET /v1/fdes reads in scope",
            "* /v1/fdes reads in scope",
            "GET /v1/agents reads in scope",
            "* /v1/agents reads in scope",
            "GET /v1/sync operates the box",
            "POST /v1/sync operates the box",
            "GET /v1/conflicts reads in scope",
            "GET /v1/conflicts/{id...} reads in scope",
            "POST /v1/conflicts/{id...} decides CHANGE through ConflictOperations",
            "GET /v1/events reads in scope",
            "POST /v1/events the journal decides",
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
            "* /v1/specs/{spec}/{sub} reads in scope",
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
            "* /v1/rooms/{room}/{sub} reads in scope",
            "GET /v1/reviews/{review} reads in scope",
            "POST /v1/reviews/{review}/approve the journal decides",
            "POST /v1/reviews/{review}/dismiss/{finding} the journal decides",
            "GET /v1/runs reads in scope",
            "GET /v1/runs/{run} reads in scope",
            "GET /v1/runs/{run}/log decides OPERATE_RUN through LogAdmission",
            "POST /v1/runs/{run}/stop decides OPERATE_RUN through StopOperations",
            "GET /v1/projects reads in scope",
            "POST /v1/projects the journal decides",
            "GET /v1/projects/{project} reads in scope",
            "GET /v1/projects/{project}/specs reads in scope",
            "GET /v1/projects/{project}/specs/{id} reads in scope",
            "* /v1/projects/{project}/specs/{id}/{rest...} reads in scope",
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
            "* /v1/projects/{project}/files reads in scope",
            "* /v1/projects/{project}/files/{path...} reads in scope",
            "POST /v1/projects/{project}/serve decides SERVE through ProjectGate",
            "DELETE /v1/projects/{project}/serve decides SERVE through ProjectGate",
            "GET /v1/projects/{project}/ports reads in scope",
            "GET /v1/projects/{project}/definition decides READ_DEFINITION through ProjectGate",
            "PUT /v1/projects/{project}/definition/{section} the journal decides",
            "* /v1/projects/{project}/{rest...} reads in scope",
            "* /v1/runs/{run}/stream decides OPERATE_RUN through LogAdmission",
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
      var method = route.method().equals("*") ? "PATCH" : route.method();
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

  @Test
  void theSocketDeclaresEachOfItsRoutesWithItsGate() {
    assertEquals(
        List.of(
            "GET /v1/sync operates the box",
            "GET /v1/conflicts reads in scope",
            "POST /v1/conflicts/{id...} decides CHANGE through ConflictOperations",
            "GET /v1/conflicts/{id...} reads in scope",
            "GET /v1/whoami reads in scope",
            "POST /v1/events the journal decides",
            "GET /v1/run/messages reads in scope",
            "POST /v1/run/messages decides NARRATE through LocalLaneOperations",
            "POST /v1/run/session the journal decides",
            "GET /v1/projects/{project}/skills reads in scope",
            "POST /v1/projects/{project}/skills the journal decides",
            "GET /v1/projects/{project}/skills/{name} reads in scope",
            "DELETE /v1/projects/{project}/skills/{name} the journal decides",
            "POST /v1/projects/{project}/serve decides SERVE through ProjectGate",
            "DELETE /v1/projects/{project}/serve decides SERVE through ProjectGate",
            "GET /v1/projects/{project}/ports reads in scope",
            "GET /v1/projects/{project}/{rest...} reads in scope",
            "GET /v1/specs reads in scope",
            "POST /v1/specs the journal decides",
            "GET /v1/specs/board reads in scope",
            "GET /v1/specs/{id} reads in scope",
            "PUT /v1/specs/{id} the journal decides",
            "DELETE /v1/specs/{id} the journal decides",
            "GET /v1/specs/{id}/content reads in scope",
            "PUT /v1/specs/{id}/content the journal decides",
            "GET /v1/specs/{id}/messages reads in scope",
            "POST /v1/specs/{id}/messages the journal decides"),
        socket(new ArrayList<>()).routes().stream().map(RouteGateSweepTest::described).toList());
  }

  @Test
  void aSocketRouteNeitherReadsWhatItWritesNorIsServedToNoOne() {
    for (var route : socket(new ArrayList<>()).routes()) {
      var gate = route.gate();
      assertTrue(
          !(gate instanceof Gate.Unauthenticated),
          "every request on the socket presents a credential: " + described(route));
      assertTrue(
          "GET".equals(route.method()) || !(gate instanceof Gate.ReadsInScope),
          "a write is decided, not merely read in scope: " + described(route));
    }
  }

  @Test
  void aMethodNoRouteOfASocketPathDeclaresReachesNoOperation() {
    var reached = new ArrayList<String>();
    var router = socket(reached);
    var declared =
        router.routes().stream()
            .collect(
                Collectors.groupingBy(
                    Route::pattern, Collectors.mapping(Route::method, Collectors.toSet())));

    for (var pattern : declared.entrySet()) {
      for (var method : METHODS) {
        if (pattern.getValue().contains(method)) {
          continue;
        }
        for (var credential :
            List.of(TestOperations.RUN_CREDENTIAL, TestOperations.BOX_CREDENTIAL)) {
          var asked = method + " " + pattern.getKey() + " as " + credential;
          var answer = router.handle(request(method, path(pattern.getKey()), credential));

          assertTrue(answer.status() >= 400 && answer.status() < 500, asked + ": " + answer);
          assertEquals(List.of(), reached, asked);
        }
      }
    }
  }

  @Test
  void aSocketPathNoRouteServesIsNotThereAndReachesNothing() {
    var reached = new ArrayList<String>();
    var router = socket(reached);

    for (var path :
        List.of(
            "/v1/widgets",
            "/v1/specs/auth/history",
            "/v1/specs/",
            "/v1/projects/acme",
            "/v1/projects/acme/files/a.md",
            "/v1/projects/acme/skills/a/b",
            "/v1/conflicts/")) {
      var answer = router.handle(request("GET", path, TestOperations.RUN_CREDENTIAL));

      assertEquals("404 {error=No route for " + path + "}", answer.status() + " " + answer.body());
    }
    assertEquals(List.of(), reached);
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

  /** A concrete path {@code pattern} matches, in the run's own project. */
  private static String path(String pattern) {
    return pattern
        .replace("{project}", RUN.project())
        .replaceAll("\\{[a-z]+\\.\\.\\.}", "a/b/resolve")
        .replaceAll("\\{[a-z]+}", "a");
  }

  private static LocalApiRequest request(String method, String path, String credential) {
    return new LocalApiRequest(
        method,
        path,
        Map.of(),
        Map.of("authorization", "Bearer " + credential),
        "a=b".getBytes(StandardCharsets.UTF_8));
  }

  /**
   * The socket over operations resolving the test run's credential to its principal and the box
   * credential to the box's person, a member; every other operation is recorded in {@code reached}
   * and fails.
   */
  private static LocalApiRouter socket(List<String> reached) {
    var lane =
        (LocalLaneOperations)
            Proxy.newProxyInstance(
                LocalLaneOperations.class.getClassLoader(),
                new Class<?>[] {LocalLaneOperations.class},
                (proxy, method, args) ->
                    switch (method.getName()) {
                      case "runForCredential" ->
                          Optional.of(RUN)
                              .filter(_ -> TestOperations.RUN_CREDENTIAL.equals(args[0]));
                      case "runActor" -> Optional.of(RUN.principalActor());
                      case "boxActorForCredential" ->
                          Optional.of(new Actor(BOX, Role.MEMBER, Actor.Lane.CLI))
                              .filter(_ -> TestOperations.BOX_CREDENTIAL.equals(args[0]));
                      default -> {
                        reached.add(method.getName());
                        throw new IllegalStateException(method.getName() + " was reached");
                      }
                    });
    return new LocalApiRouter(lane, () -> BOX);
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
