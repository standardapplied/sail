/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** A route's pattern: literal segments, one-segment placeholders, and a last rest placeholder. */
class RouteTest {

  private static final Route<String> SPEC =
      new Route<>("GET", "/v1/specs/{id}", Gate.READS_IN_SCOPE, "spec");
  private static final Route<String> FILE =
      new Route<>("PUT", "/v1/projects/{project}/files/{path...}", Gate.JOURNAL, "file");
  private static final Route<String> BOARD =
      new Route<>("GET", "/v1/specs/board", Gate.READS_IN_SCOPE, "board");

  @Test
  void aLiteralMatchesItselfAndAPlaceholderOneNonEmptySegment() {
    assertEquals(Optional.of(Map.of()), BOARD.match("/v1/specs/board"));
    assertEquals(Optional.of(Map.of("id", "auth")), SPEC.match("/v1/specs/auth"));
    for (var path :
        List.of("/v1/specs", "/v1/specs/", "/v1/specs/auth/content", "/v1/spec/auth", "v1/specs")) {
      assertEquals(Optional.empty(), SPEC.match(path), path);
    }
    assertEquals(Optional.empty(), BOARD.match("/v1/specs/boards"));
    var unclosed = new Route<>("GET", "/v1/{id", Gate.READS_IN_SCOPE, "literal");
    assertEquals(Optional.of(Map.of()), unclosed.match("/v1/{id"), "an unclosed brace is literal");
    assertEquals(Optional.empty(), unclosed.match("/v1/auth"));
  }

  @Test
  void aRestPlaceholderTakesTheNonEmptyRestOfThePathSlashesAndAll() {
    assertEquals(
        Optional.of(Map.of("project", "acme", "path", "docs/a.md")),
        FILE.match("/v1/projects/acme/files/docs/a.md"));
    assertEquals(
        Optional.of(Map.of("project", "acme", "path", "docs/")),
        FILE.match("/v1/projects/acme/files/docs/"));
    for (var path :
        List.of(
            "/v1/projects/acme/files",
            "/v1/projects/acme/files/",
            "/v1/projects//files/a.md",
            "/v1/projects/acme/skills/a.md")) {
      assertEquals(Optional.empty(), FILE.match(path), path);
    }
  }

  @Test
  void aRouteNamesAllItsPartsAndCapturesTheRestOnlyLast() {
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
        "Route /v1/{path...}/raw captures the rest of the path before its last segment.",
        assertThrows(
                IllegalArgumentException.class,
                () -> new Route<>("GET", "/v1/{path...}/raw", Gate.JOURNAL, "h"))
            .getMessage());
  }

  @Test
  void aTableListsItsRoutesEachWithItsGate() {
    RouteTable table = () -> List.of(SPEC, FILE);

    assertEquals(
        List.of(Gate.READS_IN_SCOPE, Gate.JOURNAL),
        table.routes().stream().map(Route::gate).toList());
  }
}
