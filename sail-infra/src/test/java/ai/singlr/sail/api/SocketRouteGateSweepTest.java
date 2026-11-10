/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.sail.identity.Actor;
import ai.singlr.sail.identity.Role;
import ai.singlr.sail.store.RunStore;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/**
 * A3 on the container socket: every route the socket serves is one of its table's, each declaring
 * its gate, and a request the table declares no route for reaches no operation, so nothing is
 * served past the gates.
 */
class SocketRouteGateSweepTest {

  private static final RunStore.RunRow RUN =
      new TestOperations().runForCredential(TestOperations.RUN_CREDENTIAL).orElseThrow();
  private static final Set<String> METHODS = Set.of("GET", "POST", "PUT", "DELETE", "PATCH");

  @Test
  void theSocketDeclaresEachOfItsRoutesWithItsGate() {
    assertEquals(
        List.of(
            "GET /v1/sync operates the box",
            "GET /v1/conflicts reads in scope",
            "POST /v1/conflicts/{id...} decides CHANGE in ConflictOperations",
            "GET /v1/conflicts/{id...} reads in scope",
            "GET /v1/whoami reads in scope",
            "POST /v1/events journal",
            "GET /v1/run/messages reads in scope",
            "POST /v1/run/messages decides NARRATE in LocalLaneOperations",
            "POST /v1/run/session journal",
            "GET /v1/projects/{project}/skills reads in scope",
            "POST /v1/projects/{project}/skills journal",
            "GET /v1/projects/{project}/skills/{name} reads in scope",
            "DELETE /v1/projects/{project}/skills/{name} journal",
            "POST /v1/projects/{project}/serve decides SERVE in ProjectGate",
            "DELETE /v1/projects/{project}/serve decides SERVE in ProjectGate",
            "GET /v1/projects/{project}/ports reads in scope",
            "GET /v1/specs reads in scope",
            "POST /v1/specs journal",
            "GET /v1/specs/board reads in scope",
            "GET /v1/specs/{id} reads in scope",
            "PUT /v1/specs/{id} journal",
            "DELETE /v1/specs/{id} journal",
            "GET /v1/specs/{id}/content reads in scope",
            "PUT /v1/specs/{id}/content journal",
            "GET /v1/specs/{id}/messages reads in scope",
            "POST /v1/specs/{id}/messages journal"),
        new LocalApiRouter(lane(new ArrayList<>()))
            .routes().stream().map(SocketRouteGateSweepTest::declared).toList());
  }

  @Test
  void aRouteNeitherReadsWhatItWritesNorIsServedToNoOne() {
    for (var route : new LocalApiRouter(lane(new ArrayList<>())).routes()) {
      var gate = route.gate();
      assertTrue(
          !(gate instanceof Gate.Unauthenticated),
          "every request on the socket presents a credential: " + declared(route));
      assertTrue(
          "GET".equals(route.method()) || !(gate instanceof Gate.ReadsInScope),
          "a write is decided, not merely read in scope: " + declared(route));
    }
  }

  @Test
  void aMethodNoRouteOfAPathDeclaresReachesNoOperation() {
    var reached = new ArrayList<String>();
    var router = new LocalApiRouter(lane(reached), () -> TestOperations.BOX_HANDLE);
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
  void aPathNoRouteMatchesIsNotThereAndReachesNothing() {
    var reached = new ArrayList<String>();
    var router = new LocalApiRouter(lane(reached), () -> TestOperations.BOX_HANDLE);

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

  private static String declared(Route<?> route) {
    return route.method()
        + " "
        + route.pattern()
        + " "
        + switch (route.gate()) {
          case Gate.Decides(var action, var seam) ->
              "decides " + action + " in " + seam.getSimpleName();
          case Gate.ReadsInScope _ -> "reads in scope";
          case Gate.Journal _ -> "journal";
          case Gate.OperatesBox _ -> "operates the box";
          case Gate.ManagesPeople _ -> "manages people";
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
   * The socket's operations, resolving the test run's credential to its principal and the box
   * credential to ada, a member; every other operation is recorded in {@code reached} and fails.
   */
  private static LocalLaneOperations lane(List<String> reached) {
    return (LocalLaneOperations)
        Proxy.newProxyInstance(
            LocalLaneOperations.class.getClassLoader(),
            new Class<?>[] {LocalLaneOperations.class},
            (proxy, method, args) ->
                switch (method.getName()) {
                  case "runForCredential" ->
                      Optional.of(RUN).filter(_ -> TestOperations.RUN_CREDENTIAL.equals(args[0]));
                  case "runActor" -> Optional.of(RUN.principalActor());
                  case "boxActorForCredential" ->
                      Optional.of(new Actor(TestOperations.BOX_HANDLE, Role.MEMBER, Actor.Lane.CLI))
                          .filter(_ -> TestOperations.BOX_CREDENTIAL.equals(args[0]));
                  default -> {
                    reached.add(method.getName());
                    throw new IllegalStateException(method.getName() + " was reached");
                  }
                });
  }
}
