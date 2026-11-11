/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * A route's pattern: literal segments, one-segment placeholders, a rest placeholder, and the one
 * dispatch every router asks of its table.
 */
class RouteTest {

  private static final Route<String> SPEC =
      new Route<>("GET", "/v1/specs/{id}", Gate.READS_IN_SCOPE, "spec");
  private static final Route<String> FILE =
      new Route<>("PUT", "/v1/projects/{project}/files/{path...}", Gate.JOURNAL, "file");
  private static final Route<String> BOARD =
      new Route<>("GET", "/v1/specs/board", Gate.READS_IN_SCOPE, "board");

  @Test
  void aRouteServesItsMethodAsSentAndAnyRouteEveryMethod() {
    assertTrue(SPEC.serves("GET"));
    assertFalse(SPEC.serves("get"), "a method is matched as the client sent it");
    assertFalse(SPEC.serves("PUT"));
    assertTrue(new Route<>(Route.ANY, "/x", Gate.UNSERVED, "any").serves("get"));
  }

  @Test
  void aLiteralMatchesItselfAndAPlaceholderOneSegmentAsSentEmptyOrNot() {
    assertEquals(Optional.of(Map.of()), BOARD.match("/v1/specs/board"));
    assertEquals(Optional.of(Map.of("id", "auth")), SPEC.match("/v1/specs/auth"));
    assertEquals(Optional.of(Map.of("id", "")), SPEC.match("/v1/specs/"));
    for (var path : List.of("/v1/specs", "/v1/specs/auth/content", "/v1/spec/auth", "v1/specs")) {
      assertEquals(Optional.empty(), SPEC.match(path), path);
    }
    assertEquals(Optional.empty(), BOARD.match("/v1/specs/boards"));
    var unclosed = new Route<>("GET", "/v1/{id", Gate.READS_IN_SCOPE, "literal");
    assertEquals(Optional.of(Map.of()), unclosed.match("/v1/{id"), "an unclosed brace is literal");
    assertEquals(Optional.empty(), unclosed.match("/v1/auth"));
  }

  @Test
  void aRestPlaceholderTakesTheRestOfThePathSlashesAndAllUpToTheLiteralsAfterIt() {
    assertEquals(
        Optional.of(Map.of("project", "acme", "path", "docs/a.md")),
        FILE.match("/v1/projects/acme/files/docs/a.md"));
    assertEquals(
        Optional.of(Map.of("project", "acme", "path", "docs/")),
        FILE.match("/v1/projects/acme/files/docs/"));
    assertEquals(
        Optional.of(Map.of("project", "acme", "path", "")),
        FILE.match("/v1/projects/acme/files/"),
        "last, the rest is what is sent");
    assertEquals(
        Optional.of(Map.of("project", "", "path", "a.md")), FILE.match("/v1/projects//files/a.md"));
    for (var path : List.of("/v1/projects/acme/files", "/v1/projects/acme/skills/a.md")) {
      assertEquals(Optional.empty(), FILE.match(path), path);
    }
    var resolve = new Route<>("POST", "/v1/conflicts/{id...}/resolve", Gate.JOURNAL, "resolve");
    assertEquals(
        Optional.of(Map.of("id", "acme/config")),
        resolve.match("/v1/conflicts/acme/config/resolve"));
    for (var path :
        List.of("/v1/conflicts/resolve", "/v1/conflicts//resolve", "/v1/conflicts/acme/config")) {
      assertEquals(
          Optional.empty(), resolve.match(path), "before literals the rest holds something");
    }
  }

  @Test
  void aRouteNamesAllItsPartsAndCapturesTheRestOnce() {
    assertThrows(NullPointerException.class, () -> new Route<>(null, "/v1", Gate.JOURNAL, "h"));
    assertThrows(NullPointerException.class, () -> new Route<>("GET", null, Gate.JOURNAL, "h"));
    assertThrows(NullPointerException.class, () -> new Route<>("GET", "/v1", null, "h"));
    assertThrows(NullPointerException.class, () -> new Route<>("GET", "/v1", Gate.JOURNAL, null));
    assertEquals(
        "A route's pattern starts with '/': v1/specs",
        assertThrows(
                IllegalArgumentException.class,
                () -> new Route<>("GET", "v1/specs", Gate.JOURNAL, "h"))
            .getMessage());
    assertEquals(
        "Route /v1/{path...}/{more...} captures the rest more than once.",
        assertThrows(
                IllegalArgumentException.class,
                () -> new Route<>("GET", "/v1/{path...}/{more...}", Gate.JOURNAL, "h"))
            .getMessage());
  }

  @Test
  void theFirstMatchingPatternServesByItsMethodOrRefusesTheMethodAndNoMatchIsNotFound() {
    var update = new Route<>("PUT", "/v1/specs/{id}", Gate.JOURNAL, "update");
    var any = new Route<>(Route.ANY, "/v1/specs/{id}/{sub}", Gate.UNSERVED, "any");
    var routes = List.of(BOARD, SPEC, update, any);

    assertEquals(
        new RouteTable.Served.Match<>(update, Map.of("id", "auth")),
        RouteTable.serve(routes, "PUT", "/v1/specs/auth"));
    assertEquals(
        new RouteTable.Served.MethodNotAllowed<>(Map.of()),
        RouteTable.serve(routes, "PUT", "/v1/specs/board"),
        "a literal answers for its own methods, not the placeholder beside it");
    assertEquals(
        new RouteTable.Served.Match<>(any, Map.of("id", "auth", "sub", "x")),
        RouteTable.serve(routes, "PATCH", "/v1/specs/auth/x"));
    assertEquals(
        new RouteTable.Served.NotFound<>(), RouteTable.serve(routes, "GET", "/v1/widgets"));
  }

  @Test
  void aTableListsItsRoutesEachWithItsGate() {
    RouteTable table = () -> List.of(SPEC, FILE);

    assertEquals(
        List.of(Gate.READS_IN_SCOPE, Gate.JOURNAL),
        table.routes().stream().map(Route::gate).toList());
  }
}
