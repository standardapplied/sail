/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import ai.singlr.sail.identity.Actor;
import ai.singlr.sail.identity.ProjectScope;
import ai.singlr.sail.identity.Role;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

class ScopedTest {

  private static final Actor UNRESTRICTED = new Actor("ada", Role.ADMIN, Actor.Lane.API);
  private static final Actor OF_ALPHA =
      new Actor("sam", Role.ADMIN, Actor.Lane.API).withScope(ProjectScope.of(List.of("alpha")));
  private static final Actor OF_NOTHING =
      new Actor("nia", Role.ADMIN, Actor.Lane.API).withScope(ProjectScope.none());

  /** The project of each row id; a row the map does not hold is of no known project. */
  private static final Function<String, String> PROJECT =
      Map.of("a1", "alpha", "a2", "alpha", "b1", "bravo")::get;

  @Test
  void aScopedActorIsHandedTheRowsOfItsProjectsInTheirOrder() {
    var rows = List.of("b1", "a2", "unplaced", "a1");

    assertEquals(List.of("a2", "a1"), Scoped.rows(OF_ALPHA, rows, PROJECT));
    assertEquals(List.of(), Scoped.rows(OF_NOTHING, rows, PROJECT));
  }

  @Test
  void anUnrestrictedActorIsHandedTheVeryRowsWithoutAskingTheirProject() {
    var rows = Arrays.asList("b1", "a1", "unplaced", null);
    Function<String, String> neverAsked =
        row -> {
          throw new AssertionError("an unrestricted read asked the project of " + row);
        };

    assertSame(rows, Scoped.rows(UNRESTRICTED, rows, neverAsked));
  }

  @Test
  void aRowOutsideTheScopeIsARowThatIsNotThere() {
    assertEquals(Optional.of("a1"), Scoped.row(OF_ALPHA, Optional.of("a1"), PROJECT));
    assertEquals(Optional.empty(), Scoped.row(OF_ALPHA, Optional.of("b1"), PROJECT));
    assertEquals(Optional.empty(), Scoped.row(OF_ALPHA, Optional.of("unplaced"), PROJECT));
    assertEquals(Optional.empty(), Scoped.row(OF_ALPHA, Optional.empty(), PROJECT));
    assertEquals(Optional.empty(), Scoped.row(OF_NOTHING, Optional.of("a1"), PROJECT));
  }

  @Test
  void anUnrestrictedActorIsHandedEveryRowEvenOneOfNoKnownProject() {
    assertEquals(Optional.of("b1"), Scoped.row(UNRESTRICTED, Optional.of("b1"), PROJECT));
    assertEquals(
        Optional.of("unplaced"), Scoped.row(UNRESTRICTED, Optional.of("unplaced"), PROJECT));
    assertEquals(Optional.empty(), Scoped.row(UNRESTRICTED, Optional.empty(), PROJECT));
  }
}
